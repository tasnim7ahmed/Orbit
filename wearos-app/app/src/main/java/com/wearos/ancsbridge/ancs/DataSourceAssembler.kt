package com.wearos.ancsbridge.ancs

import android.util.Log
import com.wearos.ancsbridge.ble.AncsConstants
import com.wearos.ancsbridge.model.AncsNotification
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reassembles fragmented ANCS Data Source responses into complete notifications.
 *
 * ANCS responses can span multiple GATT notifications due to MTU limits.
 * This state machine accumulates bytes and parses attribute tuples incrementally.
 *
 * Response format for GetNotificationAttributes (Command 0):
 *   [0]    CommandID = 0x00
 *   [1-4]  NotificationUID (uint32 LE)
 *   [5+]   Attribute tuples: [AttrID(1)] [Length(2 LE)] [Data(Length bytes)]
 *
 * Response format for GetAppAttributes (Command 1):
 *   [0]    CommandID = 0x01
 *   [1+]   AppIdentifier (null-terminated UTF-8)
 *   [n+]   Attribute tuples: [AttrID(1)] [Length(2 LE)] [Data(Length bytes)]
 */
class DataSourceAssembler {

    companion object {
        private const val TAG = "DataSourceAssembler"
    }

    private enum class State {
        IDLE,
        READING_HEADER,
        READING_ATTRIBUTES
    }

    private var state = State.IDLE
    private val buffer = ByteArrayOutputStream()
    private var commandId: Int = -1
    private var notificationUid: Long = 0
    private var headerParsed = false

    // Parsed attributes
    private val attributes = mutableMapOf<Int, String>()

    // Expected attributes from the request (in order)
    private var expectedAttributes = listOf<Int>()

    // Current attribute being read
    private var currentAttrId: Int = -1
    private var currentAttrLength: Int = -1
    private var currentAttrBytesRead: Int = 0
    private val currentAttrData = ByteArrayOutputStream()

    // Category and flags from the original NS event (set externally)
    private var categoryId: Int = 0
    private var eventFlags: Int = 0

    /**
     * Start expecting a GetNotificationAttributes response.
     *
     * @param uid The notification UID we requested attributes for
     * @param requestedAttributes The attribute IDs in the order we requested them
     * @param category The category ID from the original NS event
     * @param flags The event flags from the original NS event
     */
    fun expectNotificationAttributes(
        uid: Long,
        requestedAttributes: List<Int>,
        category: Int,
        flags: Int
    ) {
        reset()
        notificationUid = uid
        expectedAttributes = requestedAttributes
        categoryId = category
        eventFlags = flags
        state = State.READING_HEADER
    }

    /**
     * Feed incoming Data Source bytes. Call this for each onCharacteristicChanged.
     *
     * @return A completed AncsNotification if all attributes have been parsed, null otherwise.
     */
    fun onDataReceived(data: ByteArray): AncsNotification? {
        if (state == State.IDLE) {
            // Nothing requested — e.g. trailing tuples iOS appends after a completed
            // response. Parsing it as a new response would post an empty notification.
            Log.d(TAG, "Ignoring ${data.size} unsolicited Data Source bytes")
            return null
        }

        buffer.write(data)
        return tryParse()
    }

    private fun tryParse(): AncsNotification? {
        val bytes = buffer.toByteArray()
        var offset = 0

        // Parse header if not yet done
        if (!headerParsed) {
            if (bytes.isEmpty()) return null

            commandId = bytes[0].toInt() and 0xFF
            offset = 1

            when (commandId) {
                AncsConstants.COMMAND_GET_NOTIFICATION_ATTRIBUTES -> {
                    if (bytes.size < 5) return null // Need at least CommandID + UID
                    notificationUid = readUInt32LE(bytes, 1)
                    offset = 5
                    headerParsed = true
                    state = State.READING_ATTRIBUTES
                }
                AncsConstants.COMMAND_GET_APP_ATTRIBUTES -> {
                    // Read null-terminated app identifier
                    val nullIndex = bytes.indexOf(0.toByte(), fromIndex = 1)
                    if (nullIndex == -1) return null // Haven't received full app ID yet
                    offset = nullIndex + 1
                    headerParsed = true
                    state = State.READING_ATTRIBUTES
                }
                else -> {
                    Log.w(TAG, "Unknown command ID: $commandId")
                    reset()
                    return null
                }
            }
        }

        // Parse attribute tuples
        while (state == State.READING_ATTRIBUTES && !allAttributesReceived()) {
            if (currentAttrId == -1) {
                // Start reading a new attribute
                if (offset >= bytes.size) break

                // Need the full 3-byte tuple header (ID + 2-byte length). If the
                // fragment ends mid-header, keep those bytes for the next one.
                if (offset + 2 >= bytes.size) {
                    compactBuffer(bytes, offset)
                    return null
                }

                currentAttrId = bytes[offset].toInt() and 0xFF
                currentAttrLength = readUInt16LE(bytes, offset + 1)
                offset += 3
                currentAttrBytesRead = 0
                currentAttrData.reset()
            }

            // Read attribute data
            val remaining = currentAttrLength - currentAttrBytesRead
            val available = bytes.size - offset
            val toRead = minOf(remaining, available)

            if (toRead > 0) {
                currentAttrData.write(bytes, offset, toRead)
                currentAttrBytesRead += toRead
                offset += toRead
            }

            if (currentAttrBytesRead >= currentAttrLength) {
                // Attribute complete
                val value = currentAttrData.toString(Charsets.UTF_8.name())
                // iOS 27 interleaves extra tuples (attribute 0xFF with length 0, then a
                // repeated AppIdentifier) after each action label, so match by ID, not position.
                if (currentAttrId in expectedAttributes && currentAttrId !in attributes) {
                    attributes[currentAttrId] = value
                    Log.d(TAG, "Attribute $currentAttrId = \"$value\" (${currentAttrLength} bytes)")
                }

                currentAttrId = -1
                currentAttrLength = -1
                currentAttrBytesRead = 0
                currentAttrData.reset()
            } else {
                // Need more data
                compactBuffer(bytes, offset)
                return null
            }
        }

        // Check if all attributes are parsed
        if (allAttributesReceived()) {
            val notification = buildNotification()
            reset()
            return notification
        }

        // Still need more data
        compactBuffer(bytes, offset)
        return null
    }

    private fun allAttributesReceived() = attributes.keys.containsAll(expectedAttributes)

    /**
     * Give up waiting and return what arrived, if anything useful did. Called when the
     * response times out — e.g. iOS omitted a requested attribute.
     */
    fun flushPartial(): AncsNotification? {
        val useful = state == State.READING_ATTRIBUTES &&
            (AncsConstants.ATTR_TITLE in attributes || AncsConstants.ATTR_MESSAGE in attributes)
        val notification = if (useful) buildNotification() else null
        reset()
        return notification
    }

    private fun buildNotification(): AncsNotification {
        return AncsNotification(
            uid = notificationUid,
            appIdentifier = attributes[AncsConstants.ATTR_APP_IDENTIFIER] ?: "",
            appDisplayName = null, // Fetched separately via GetAppAttributes
            title = attributes[AncsConstants.ATTR_TITLE] ?: "",
            subtitle = attributes[AncsConstants.ATTR_SUBTITLE],
            message = MessageText.clean(attributes[AncsConstants.ATTR_MESSAGE] ?: ""),
            date = attributes[AncsConstants.ATTR_DATE],
            categoryId = categoryId,
            eventFlags = eventFlags,
            positiveActionLabel = attributes[AncsConstants.ATTR_POSITIVE_ACTION_LABEL],
            negativeActionLabel = attributes[AncsConstants.ATTR_NEGATIVE_ACTION_LABEL]
        )
    }

    private fun compactBuffer(currentBytes: ByteArray, offset: Int) {
        buffer.reset()
        if (offset < currentBytes.size) {
            buffer.write(currentBytes, offset, currentBytes.size - offset)
        }
    }

    private fun readUInt32LE(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF)) or
                ((data[offset + 1].toLong() and 0xFF) shl 8) or
                ((data[offset + 2].toLong() and 0xFF) shl 16) or
                ((data[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun readUInt16LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun ByteArray.indexOf(byte: Byte, fromIndex: Int = 0): Int {
        for (i in fromIndex until size) {
            if (this[i] == byte) return i
        }
        return -1
    }

    fun reset() {
        state = State.IDLE
        buffer.reset()
        commandId = -1
        notificationUid = 0
        headerParsed = false
        attributes.clear()
        expectedAttributes = emptyList()
        currentAttrId = -1
        currentAttrLength = -1
        currentAttrBytesRead = 0
        currentAttrData.reset()
        categoryId = 0
        eventFlags = 0
    }
}
