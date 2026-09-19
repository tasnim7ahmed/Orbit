package com.wearos.ancsbridge.ble

import com.wearos.ancsbridge.model.MediaState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class PhoneServiceParsersTest {

    private fun update(entity: Int, attr: Int, value: String, flags: Int = 0) =
        byteArrayOf(entity.toByte(), attr.toByte(), flags.toByte()) + value.toByteArray(Charsets.UTF_8)

    private fun applyAll(vararg updates: ByteArray, now: Long = 1_000L): MediaState =
        updates.fold(MediaState()) { state, bytes ->
            AmsProtocol.apply(state, AmsProtocol.parseEntityUpdate(bytes)!!, now)
        }

    @Test
    fun `AMS entity update parses header and UTF-8 value`() {
        val u = AmsProtocol.parseEntityUpdate(update(2, 2, "Blinding Lights — Remix", flags = 1))!!
        assertEquals(AmsProtocol.ENTITY_TRACK, u.entity)
        assertEquals(AmsProtocol.TRACK_TITLE, u.attribute)
        assertTrue(u.truncated)
        assertEquals("Blinding Lights — Remix", u.value)
    }

    @Test
    fun `AMS rejects too-short updates`() {
        assertNull(AmsProtocol.parseEntityUpdate(byteArrayOf(0, 1)))
    }

    @Test
    fun `AMS builds full now-playing state`() {
        val s = applyAll(
            update(0, 0, "Spotify"),
            update(0, 1, "1,1.0,42.5"),
            update(0, 2, "0.625"),
            update(2, 0, "The Weeknd"),
            update(2, 1, "After Hours"),
            update(2, 2, "Blinding Lights"),
            update(2, 3, "200.04")
        )
        assertTrue(s.available)
        assertEquals("Spotify", s.playerName)
        assertTrue(s.isPlaying)
        assertEquals(1.0f, s.playbackRate)
        assertEquals(42.5, s.elapsedSec, 0.001)
        assertEquals(0.625f, s.volume!!, 0.0001f)
        assertEquals("The Weeknd", s.artist)
        assertEquals("After Hours", s.album)
        assertEquals("Blinding Lights", s.title)
        assertEquals(200.04, s.durationSec!!, 0.001)
    }

    @Test
    fun `AMS position extrapolates while playing and clamps to duration`() {
        val s = applyAll(update(0, 1, "1,1.0,10.0"), update(2, 3, "30"), now = 5_000L)
        assertEquals(15.0, s.positionAt(10_000L), 0.001)
        assertEquals(30.0, s.positionAt(100_000L), 0.001)
        val paused = applyAll(update(0, 1, "0,0.0,10.0"), now = 5_000L)
        assertEquals(10.0, paused.positionAt(60_000L), 0.001)
    }

    @Test
    fun `AMS empty player name and playback info mean no player`() {
        val s = applyAll(update(0, 0, "Spotify"), update(0, 1, "1,1.0,3"), update(0, 0, ""), update(0, 1, ""))
        assertFalse(s.available)
        assertFalse(s.isPlaying)
    }

    @Test
    fun `AMS supported commands`() {
        assertEquals(setOf(0, 1, 2, 3, 4), AmsProtocol.parseSupportedCommands(byteArrayOf(0, 1, 2, 3, 4)))
    }

    @Test
    fun `CTS current time parses local time with fractions`() {
        // 2026-09-18 23:13:48, Friday(5), fractions256=128 (0.5 s), adjust reason 0
        val bytes = byteArrayOf(0xEA.toByte(), 0x07, 9, 18, 23, 13, 48, 5, 128.toByte(), 0)
        assertEquals(LocalDateTime.of(2026, 9, 18, 23, 13, 48, 500_000_000), CtsProtocol.parseCurrentTime(bytes))
    }

    @Test
    fun `CTS unknown date returns null`() {
        assertNull(CtsProtocol.parseCurrentTime(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun `CTS local time info offsets`() {
        // UTC+6 (Dhaka), no DST: 24 * 15 min
        assertEquals(360, CtsProtocol.parseUtcOffsetMinutes(byteArrayOf(24, 0)))
        // UTC-8 + 1h DST (PDT): -32*15 + 4*15 = -420
        assertEquals(-420, CtsProtocol.parseUtcOffsetMinutes(byteArrayOf(-32, 4)))
        // UTC+5:45 (Nepal), DST unknown
        assertEquals(345, CtsProtocol.parseUtcOffsetMinutes(byteArrayOf(23, 255.toByte())))
        assertNull(CtsProtocol.parseUtcOffsetMinutes(byteArrayOf(-128, 0)))
    }

    @Test
    fun `CTS drift is iPhone minus watch`() {
        val iphoneLocal = LocalDateTime.of(2026, 9, 18, 23, 13, 25)
        // Same instant in PDT (-420 min): 2026-09-19T06:13:25Z
        val watchEpoch = java.time.Instant.parse("2026-09-19T06:13:48Z").toEpochMilli()
        assertEquals(-23.0, CtsProtocol.driftSeconds(iphoneLocal, -420, watchEpoch, -420), 0.001)
    }
}
