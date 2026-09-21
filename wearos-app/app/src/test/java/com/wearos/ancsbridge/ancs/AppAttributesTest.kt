package com.wearos.ancsbridge.ancs

import com.wearos.ancsbridge.ble.AncsConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class AppAttributesTest {

    private val requested = listOf(AncsConstants.APP_ATTR_DISPLAY_NAME)

    /** GetAppAttributes response: command, null-terminated app id, then attribute tuples. */
    private fun response(appId: String, displayName: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(AncsConstants.COMMAND_GET_APP_ATTRIBUTES)
        out.write(appId.toByteArray(Charsets.UTF_8))
        out.write(0)
        val name = displayName.toByteArray(Charsets.UTF_8)
        out.write(AncsConstants.APP_ATTR_DISPLAY_NAME)
        out.write(name.size and 0xFF)
        out.write((name.size shr 8) and 0xFF)
        out.write(name)
        return out.toByteArray()
    }

    private fun assembler(appId: String) = DataSourceAssembler().apply {
        expectAppAttributes(appId, requested)
    }

    @Test
    fun `reads the display name the iPhone reports`() {
        val result = assembler("com.toyopagroup.picaboo")
            .feedForAppName(response("com.toyopagroup.picaboo", "Snapchat"))
        assertNotNull(result)
        assertEquals("com.toyopagroup.picaboo", result!!.appIdentifier)
        assertEquals("Snapchat", result.displayName)
    }

    @Test
    fun `handles names with non-Latin characters and emoji`() {
        val name = "বাংলা অ্যাপ 🎧"
        val result = assembler("com.example.app").feedForAppName(response("com.example.app", name))
        assertEquals(name, result!!.displayName)
    }

    @Test
    fun `reassembles a response split at every boundary`() {
        val full = response("com.burbn.instagram", "Instagram")
        for (split in 1 until full.size) {
            val a = assembler("com.burbn.instagram")
            assertNull("split=$split", a.feedForAppName(full.copyOfRange(0, split)))
            val result = a.feedForAppName(full.copyOfRange(split, full.size))
            assertNotNull("split=$split", result)
            assertEquals("split=$split", "Instagram", result!!.displayName)
        }
    }

    @Test
    fun `an empty display name still completes, so the caller can fall back`() {
        val result = assembler("com.example.app").feedForAppName(response("com.example.app", ""))
        assertNotNull(result)
        assertEquals("", result!!.displayName)
    }

    @Test
    fun `a notification response is not mistaken for an app name`() {
        val a = DataSourceAssembler().apply {
            expectNotificationAttributes(1, listOf(AncsConstants.ATTR_TITLE), 0, 0)
        }
        val out = ByteArrayOutputStream()
        out.write(AncsConstants.COMMAND_GET_NOTIFICATION_ATTRIBUTES)
        for (i in 0 until 4) out.write(if (i == 0) 1 else 0)
        out.write(AncsConstants.ATTR_TITLE)
        out.write(5); out.write(0)
        out.write("Alice".toByteArray(Charsets.UTF_8))
        assertNull(a.feedForAppName(out.toByteArray()))
    }

    @Test
    fun `bytes arriving with nothing requested are ignored`() {
        assertNull(DataSourceAssembler().feedForAppName(response("com.example.app", "Example")))
    }
}
