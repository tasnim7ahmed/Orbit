package com.wearos.ancsbridge.ble

import com.wearos.ancsbridge.model.MediaState
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Apple Media Service (AMS) protocol.
 * Reference: https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleMediaService_Reference/
 */
object AmsProtocol {

    // Remote Command IDs
    const val CMD_PLAY = 0
    const val CMD_PAUSE = 1
    const val CMD_TOGGLE_PLAY_PAUSE = 2
    const val CMD_NEXT_TRACK = 3
    const val CMD_PREVIOUS_TRACK = 4
    const val CMD_VOLUME_UP = 5
    const val CMD_VOLUME_DOWN = 6
    const val CMD_ADVANCE_REPEAT_MODE = 7
    const val CMD_ADVANCE_SHUFFLE_MODE = 8
    /** Jump within the current track, the 15-second skip podcast players offer. */
    const val CMD_SKIP_FORWARD = 9
    const val CMD_SKIP_BACKWARD = 10
    const val CMD_LIKE_TRACK = 11
    const val CMD_DISLIKE_TRACK = 12
    const val CMD_BOOKMARK_TRACK = 13

    // Entity IDs
    const val ENTITY_PLAYER = 0
    const val ENTITY_QUEUE = 1
    const val ENTITY_TRACK = 2

    // Player attributes
    const val PLAYER_NAME = 0
    const val PLAYER_PLAYBACK_INFO = 1
    const val PLAYER_VOLUME = 2

    // Queue attributes
    const val QUEUE_INDEX = 0
    const val QUEUE_COUNT = 1
    const val QUEUE_SHUFFLE_MODE = 2
    const val QUEUE_REPEAT_MODE = 3

    // Track attributes
    const val TRACK_ARTIST = 0
    const val TRACK_ALBUM = 1
    const val TRACK_TITLE = 2
    const val TRACK_DURATION = 3

    // Shuffle and repeat mode values, shared by both attributes
    const val MODE_OFF = 0
    const val MODE_ONE = 1
    const val MODE_ALL = 2

    const val FLAG_TRUNCATED = 0x01

    /** Entity Update subscription writes: [EntityID, AttributeID...] */
    val SUBSCRIBE_PLAYER = byteArrayOf(
        ENTITY_PLAYER.toByte(), PLAYER_NAME.toByte(), PLAYER_PLAYBACK_INFO.toByte(), PLAYER_VOLUME.toByte()
    )
    val SUBSCRIBE_TRACK = byteArrayOf(
        ENTITY_TRACK.toByte(), TRACK_ARTIST.toByte(), TRACK_ALBUM.toByte(),
        TRACK_TITLE.toByte(), TRACK_DURATION.toByte()
    )
    val SUBSCRIBE_QUEUE = byteArrayOf(
        ENTITY_QUEUE.toByte(), QUEUE_INDEX.toByte(), QUEUE_COUNT.toByte(),
        QUEUE_SHUFFLE_MODE.toByte(), QUEUE_REPEAT_MODE.toByte()
    )

    data class EntityUpdate(val entity: Int, val attribute: Int, val truncated: Boolean, val value: String)

    /** Entity Update notification: [EntityID][AttributeID][Flags][UTF-8 value...] */
    fun parseEntityUpdate(data: ByteArray): EntityUpdate? {
        if (data.size < 3) return null
        return EntityUpdate(
            entity = data[0].toInt() and 0xFF,
            attribute = data[1].toInt() and 0xFF,
            truncated = (data[2].toInt() and FLAG_TRUNCATED) != 0,
            value = String(data, 3, data.size - 3, Charsets.UTF_8)
        )
    }

    /** Remote Command notification: one byte per currently supported command. */
    fun parseSupportedCommands(data: ByteArray): Set<Int> =
        data.map { it.toInt() and 0xFF }.toSet()

    /** Apply one Entity Update to the media state. */
    fun apply(state: MediaState, update: EntityUpdate, nowElapsedRealtime: Long): MediaState {
        val v = update.value
        return when (update.entity) {
            ENTITY_PLAYER -> when (update.attribute) {
                PLAYER_NAME -> state.copy(available = v.isNotEmpty(), playerName = v)
                PLAYER_PLAYBACK_INFO -> {
                    // "PlaybackState,PlaybackRate,ElapsedTime" — empty when no player
                    val parts = v.split(",")
                    if (parts.size < 3) {
                        state.copy(playbackState = MediaState.PLAYBACK_PAUSED, playbackRate = 0f)
                    } else {
                        state.copy(
                            playbackState = parts[0].trim().toIntOrNull() ?: MediaState.PLAYBACK_PAUSED,
                            playbackRate = parts[1].trim().toFloatOrNull() ?: 0f,
                            elapsedSec = parts[2].trim().toDoubleOrNull() ?: 0.0,
                            elapsedReportedAt = nowElapsedRealtime
                        )
                    }
                }
                PLAYER_VOLUME -> state.copy(volume = v.trim().toFloatOrNull())
                else -> state
            }
            ENTITY_QUEUE -> when (update.attribute) {
                // Every queue attribute arrives as the integer written out as text
                QUEUE_INDEX -> state.copy(queueIndex = v.trim().toIntOrNull())
                QUEUE_COUNT -> state.copy(queueCount = v.trim().toIntOrNull())
                QUEUE_SHUFFLE_MODE -> state.copy(shuffleMode = v.trim().toIntOrNull())
                QUEUE_REPEAT_MODE -> state.copy(repeatMode = v.trim().toIntOrNull())
                else -> state
            }
            ENTITY_TRACK -> when (update.attribute) {
                TRACK_ARTIST -> state.copy(artist = v)
                TRACK_ALBUM -> state.copy(album = v)
                TRACK_TITLE -> state.copy(title = v)
                TRACK_DURATION -> state.copy(durationSec = v.trim().toDoubleOrNull())
                else -> state
            }
            else -> state
        }
    }
}

/**
 * Bluetooth SIG Current Time Service (0x1805) parsing.
 */
object CtsProtocol {

    /**
     * Current Time (0x2A2B): year(u16 LE) month day hours minutes seconds dayOfWeek
     * fractions256 adjustReason. This is the iPhone's *local* wall-clock time.
     * Returns local time with sub-second precision, or null if malformed/unknown.
     */
    fun parseCurrentTime(data: ByteArray): LocalDateTime? {
        if (data.size < 7) return null
        val year = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val month = data[2].toInt() and 0xFF
        val day = data[3].toInt() and 0xFF
        val hour = data[4].toInt() and 0xFF
        val minute = data[5].toInt() and 0xFF
        val second = data[6].toInt() and 0xFF
        val fractions256 = if (data.size >= 9) data[8].toInt() and 0xFF else 0
        return try {
            LocalDateTime.of(year, month, day, hour, minute, second, (fractions256 * 1_000_000_000L / 256).toInt())
        } catch (_: Exception) {
            null // year/month/day of 0 means "unknown" in the spec
        }
    }

    /**
     * Local Time Information (0x2A0F): timeZone(int8, 15-min units, -128 unknown)
     * dstOffset(u8, 15-min units: 0/2/4/8, 255 unknown).
     * Returns the total UTC offset in minutes, or null if unknown.
     */
    fun parseUtcOffsetMinutes(data: ByteArray): Int? {
        if (data.size < 2) return null
        val tz = data[0].toInt() // signed
        val dst = data[1].toInt() and 0xFF
        // The spec allows -48..+56 quarter hours (UTC-12 to UTC+14) and -128 for unknown.
        // Anything else is malformed: treated as unknown rather than trusted, because an
        // out-of-range offset makes ZoneOffset throw further down.
        if (tz !in -48..56) return null
        // DST offset is 0, 2, 4 or 8 quarter hours, or 255 for unknown
        val dstMinutes = if (dst in 0..8) dst * 15 else 0
        return tz * 15 + dstMinutes
    }

    /**
     * Seconds the iPhone is ahead of the watch (negative = watch ahead).
     * With a known iPhone UTC offset we compare absolute time; otherwise we compare
     * wall clocks, which also folds any time zone difference into the drift.
     */
    fun driftSeconds(
        iphoneLocal: LocalDateTime,
        iphoneUtcOffsetMinutes: Int?,
        watchEpochMillis: Long,
        watchUtcOffsetMinutes: Int
    ): Double {
        // ZoneOffset only accepts ±18 hours; clamp so a strange value can't throw here
        val offsetMinutes = (iphoneUtcOffsetMinutes ?: watchUtcOffsetMinutes).coerceIn(-18 * 60, 18 * 60)
        val iphoneEpochMillis = iphoneLocal.toInstant(ZoneOffset.ofTotalSeconds(offsetMinutes * 60)).toEpochMilli()
        return (iphoneEpochMillis - watchEpochMillis) / 1000.0
    }
}
