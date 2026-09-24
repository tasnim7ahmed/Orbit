package com.wearos.ancsbridge.ancs

import com.wearos.ancsbridge.ble.AncsConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * A request can time out while its response is still on the way. What arrives late must
 * not be taken for the next request's response, nor make the assembler drop it.
 */
class StaleResponseTest {

    private val requested = listOf(AncsConstants.ATTR_APP_IDENTIFIER, AncsConstants.ATTR_TITLE)

    private fun response(uid: Long, title: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(AncsConstants.COMMAND_GET_NOTIFICATION_ATTRIBUTES)
        for (i in 0 until 4) out.write(((uid shr (8 * i)) and 0xFF).toInt())
        for ((id, value) in listOf(AncsConstants.ATTR_APP_IDENTIFIER to "com.example", AncsConstants.ATTR_TITLE to title)) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            out.write(id)
            out.write(bytes.size and 0xFF)
            out.write((bytes.size shr 8) and 0xFF)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    private fun waitingFor(uid: Long) = DataSourceAssembler().apply {
        expectNotificationAttributes(uid, requested, AncsConstants.CATEGORY_SOCIAL, 0)
    }

    @Test
    fun `the tail of a late response is dropped and the awaited one still completes`() {
        val a = waitingFor(8)
        val late = response(7, "Late one")
        // Only the tail of the late response arrives after the new request went out
        assertNull(a.feed(late.copyOfRange(9, late.size)))
        val n = a.feed(response(8, "Awaited"))
        assertNotNull(n)
        assertEquals(8L, n!!.uid)
        assertEquals("Awaited", n.title)
    }

    @Test
    fun `a whole late response for another notification is not taken for the awaited one`() {
        val a = waitingFor(8)
        assertNull(a.feed(response(7, "Late one")))
        val n = a.feed(response(8, "Awaited"))
        assertEquals(8L, n!!.uid)
        assertEquals("Awaited", n.title)
    }

    @Test
    fun `a late app name answer does not stand in for another app`() {
        val a = DataSourceAssembler().apply {
            expectAppAttributes("com.b", listOf(AncsConstants.APP_ATTR_DISPLAY_NAME))
        }
        fun appResponse(appId: String, name: String): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(AncsConstants.COMMAND_GET_APP_ATTRIBUTES)
            out.write(appId.toByteArray(Charsets.UTF_8))
            out.write(0)
            out.write(AncsConstants.APP_ATTR_DISPLAY_NAME)
            out.write(name.length); out.write(0)
            out.write(name.toByteArray(Charsets.UTF_8))
            return out.toByteArray()
        }
        assertNull(a.feedForAppName(appResponse("com.a", "App A")))
        val result = a.feedForAppName(appResponse("com.b", "App B"))
        assertEquals("com.b", result!!.appIdentifier)
        assertEquals("App B", result.displayName)
    }
}
