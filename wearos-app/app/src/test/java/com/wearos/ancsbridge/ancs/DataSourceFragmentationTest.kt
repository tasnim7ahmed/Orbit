package com.wearos.ancsbridge.ancs

import com.wearos.ancsbridge.ble.AncsConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class DataSourceFragmentationTest {

    private val requested = listOf(
        AncsConstants.ATTR_APP_IDENTIFIER,
        AncsConstants.ATTR_TITLE,
        AncsConstants.ATTR_SUBTITLE,
        AncsConstants.ATTR_MESSAGE,
        AncsConstants.ATTR_DATE
    )

    private val attrs = linkedMapOf(
        AncsConstants.ATTR_APP_IDENTIFIER to "net.whatsapp.WhatsApp",
        AncsConstants.ATTR_TITLE to "Alice",
        AncsConstants.ATTR_SUBTITLE to "",
        AncsConstants.ATTR_MESSAGE to "See you at 7 — bring the charger",
        AncsConstants.ATTR_DATE to "20260918T213000"
    )

    private fun response(uid: Long, message: String = attrs.getValue(AncsConstants.ATTR_MESSAGE)): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(AncsConstants.COMMAND_GET_NOTIFICATION_ATTRIBUTES)
        for (i in 0 until 4) out.write(((uid shr (8 * i)) and 0xFF).toInt())
        for ((id, attrValue) in attrs) {
            val value = if (id == AncsConstants.ATTR_MESSAGE) message else attrValue
            val bytes = value.toByteArray(Charsets.UTF_8)
            out.write(id)
            out.write(bytes.size and 0xFF)
            out.write((bytes.size shr 8) and 0xFF)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    private fun assembler(uid: Long) = DataSourceAssembler().apply {
        expectNotificationAttributes(uid, requested, AncsConstants.CATEGORY_SOCIAL, 0)
    }

    @Test
    fun parsesUnfragmentedResponse() {
        val n = assembler(42).onDataReceived(response(42))
        assertNotNull(n)
        assertEquals(42L, n!!.uid)
        assertEquals("Alice", n.title)
        assertEquals("See you at 7 — bring the charger", n.message)
        assertEquals("net.whatsapp.WhatsApp", n.appIdentifier)
        assertEquals("20260918T213000", n.date)
    }

    @Test
    fun parsesResponseSplitAtEveryPossibleBoundary() {
        val full = response(7)
        for (split in 1 until full.size) {
            val a = assembler(7)
            assertNull("split=$split", a.onDataReceived(full.copyOfRange(0, split)))
            val n = a.onDataReceived(full.copyOfRange(split, full.size))
            assertNotNull("split=$split", n)
            assertEquals("split=$split", "Alice", n!!.title)
            assertEquals("split=$split", "See you at 7 — bring the charger", n.message)
            assertEquals("split=$split", "20260918T213000", n.date)
        }
    }

    @Test
    fun parsesLongMessageSpanningSeveralNotifications() {
        // Near MAX_MESSAGE_LENGTH, multi-byte characters, a length above 255 (both length
        // bytes used), delivered in 512-byte chunks like at the negotiated MTU of 517
        val message = "আমি ভালো আছি। ".repeat(50) + "End"
        assertEquals(true, message.toByteArray(Charsets.UTF_8).size in 256..AncsConstants.MAX_MESSAGE_LENGTH)
        val full = response(9, message)
        val a = assembler(9)
        var result: com.wearos.ancsbridge.model.AncsNotification? = null
        var offset = 0
        while (offset < full.size) {
            val end = minOf(offset + 512, full.size)
            val n = a.onDataReceived(full.copyOfRange(offset, end))
            if (end < full.size) assertNull(n) else result = n
            offset = end
        }
        assertNotNull(result)
        assertEquals(message, result!!.message)
        assertEquals("20260918T213000", result.date)
    }

    @Test
    fun parsesResponseDeliveredOneByteAtATime() {
        val full = response(0xFFFFFFFFL)
        val a = assembler(0xFFFFFFFFL)
        var result: com.wearos.ancsbridge.model.AncsNotification? = null
        for (b in full) result = a.onDataReceived(byteArrayOf(b)) ?: result
        assertNotNull(result)
        assertEquals(0xFFFFFFFFL, result!!.uid)
        assertEquals("Alice", result.title)
    }
}
