package com.wearos.ancsbridge.ancs

import com.wearos.ancsbridge.ble.AncsConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Real Data Source response captured from an iPhone 17 Pro on iOS 27 (incoming
 * FaceTime call). iOS inserts extra tuples — attribute 0xFF with length 0 and a
 * repeated AppIdentifier — after each action label.
 */
class Ios27ResponseTest {

    private val requested = listOf(
        AncsConstants.ATTR_APP_IDENTIFIER, AncsConstants.ATTR_TITLE, AncsConstants.ATTR_SUBTITLE,
        AncsConstants.ATTR_MESSAGE, AncsConstants.ATTR_DATE,
        AncsConstants.ATTR_POSITIVE_ACTION_LABEL, AncsConstants.ATTR_NEGATIVE_ACTION_LABEL
    )

    private val capturedPacket = (
        "00 0E 00 00 00 00 12 00 63 6F 6D 2E 61 70 70 6C 65 2E 66 61 63 65 74 69 6D 65 01 1E 00 4D 79 20 4C 6F " +
        "76 65 F0 9F 92 95 4D 79 20 4C 69 66 65 F0 9F 92 95 4D 79 20 57 69 66 65 79 02 04 00 68 6F 6D 65 03 0D " +
        "00 49 6E 63 6F 6D 69 6E 67 20 43 61 6C 6C 05 0F 00 32 30 32 36 30 39 31 38 54 32 33 34 30 35 32 06 06 " +
        "00 41 6E 73 77 65 72 FF 00 00 00 12 00 63 6F 6D 2E 61 70 70 6C 65 2E 66 61 63 65 74 69 6D 65 07 07 00 " +
        "44 65 63 6C 69 6E 65 FF 00 00 00 12 00 63 6F 6D 2E 61 70 70 6C 65 2E 66 61 63 65 74 69 6D 65"
    ).split(" ").map { it.toInt(16).toByte() }.toByteArray()

    private fun assembler() = DataSourceAssembler().apply {
        expectNotificationAttributes(uid = 14, requestedAttributes = requested, category = 1, flags = 0x1A)
    }

    @Test
    fun `parses interleaved iOS 27 response including negative label`() {
        val n = assembler().onDataReceived(capturedPacket)
        assertNotNull(n)
        assertEquals(14L, n!!.uid)
        assertEquals("com.apple.facetime", n.appIdentifier)
        assertEquals("My Love💕My Life💕My Wifey", n.title)
        assertEquals("home", n.subtitle)
        assertEquals("Incoming Call", n.message)
        assertEquals("20260918T234052", n.date)
        assertEquals("Answer", n.positiveActionLabel)
        assertEquals("Decline", n.negativeActionLabel)
    }

    @Test
    fun `parses interleaved response split at every boundary`() {
        for (split in 1 until capturedPacket.size) {
            val a = assembler()
            val first = a.onDataReceived(capturedPacket.copyOfRange(0, split))
            val n = first ?: a.onDataReceived(capturedPacket.copyOfRange(split, capturedPacket.size))
            assertNotNull("split=$split", n)
            assertEquals("split=$split", "Decline", n!!.negativeActionLabel)
        }
    }

    @Test
    fun `trailing tuples after completion are ignored`() {
        val a = assembler()
        assertNotNull(a.onDataReceived(capturedPacket))
        assertNull(a.onDataReceived(byteArrayOf(0xFF.toByte(), 0, 0)))
    }

    @Test
    fun `partial flush returns what arrived when a requested attribute never comes`() {
        val a = assembler()
        // Cut before the negative label: everything up to and including "Answer"
        assertNull(a.onDataReceived(capturedPacket.copyOfRange(0, 105)))
        val n = a.flushPartial()
        assertNotNull(n)
        assertEquals("Incoming Call", n!!.message)
        assertNull(n.negativeActionLabel)
    }
}
