package com.wearos.ancsbridge.ble

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class PhoneServiceUuidTest {

    private val original: Locale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    @Test
    fun `service uuids are correct`() {
        assertEquals("0000180f-0000-1000-8000-00805f9b34fb", PhoneServices.BATTERY_SERVICE.toString())
        assertEquals("00002a19-0000-1000-8000-00805f9b34fb", PhoneServices.BATTERY_LEVEL.toString())
        assertEquals("00001805-0000-1000-8000-00805f9b34fb", PhoneServices.CURRENT_TIME_SERVICE.toString())
        assertEquals("00002a2b-0000-1000-8000-00805f9b34fb", PhoneServices.CURRENT_TIME.toString())
        assertEquals("00002a0f-0000-1000-8000-00805f9b34fb", PhoneServices.LOCAL_TIME_INFO.toString())
    }

    /**
     * Locales whose digits aren't 0-9 (Bengali, Arabic) must not change how a UUID is
     * built. A localized digit would make UUID.fromString throw and take the Bluetooth
     * session down with it.
     */
    @Test
    fun `uuids build the same under locales with other digits`() {
        val expected = PhoneServices.BATTERY_SERVICE.toString()
        for (tag in listOf("bn-BD", "ar-EG", "hi-IN", "my-MM")) {
            Locale.setDefault(Locale.forLanguageTag(tag))
            assertEquals(tag, expected, PhoneServices.sigForTest(0x180F).toString())
        }
    }
}
