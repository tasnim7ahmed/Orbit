package com.wearos.ancsbridge.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.os.SystemClock
import android.util.Log
import com.wearos.ancsbridge.model.ClockStatus
import com.wearos.ancsbridge.model.PhoneStatus
import java.time.LocalDateTime
import java.util.TimeZone
import java.util.UUID

/**
 * Non-ANCS services the iPhone exposes to a bonded accessory:
 *  - Battery Service (0x180F)       → iPhone battery %
 *  - Current Time Service (0x1805)  → clock check against the watch
 *  - Apple Media Service (AMS)      → now playing + remote control
 *
 * Started once the ANCS session is active. All GATT traffic goes through the
 * caller's operation queue via [subscribe], [read] and [write].
 */
class PhoneServices(
    private val subscribe: (BluetoothGattCharacteristic) -> Unit,
    private val read: (BluetoothGattCharacteristic) -> Unit,
    private val write: (BluetoothGattCharacteristic, ByteArray) -> Unit
) {

    companion object {
        private const val TAG = "PhoneServices"

        private fun sig(short: Int): UUID =
            UUID.fromString(String.format("%08X-0000-1000-8000-00805F9B34FB", short))

        val BATTERY_SERVICE: UUID = sig(0x180F)
        val BATTERY_LEVEL: UUID = sig(0x2A19)

        val CURRENT_TIME_SERVICE: UUID = sig(0x1805)
        val CURRENT_TIME: UUID = sig(0x2A2B)
        val LOCAL_TIME_INFO: UUID = sig(0x2A0F)

        val AMS_SERVICE: UUID = UUID.fromString("89D3502B-0F36-433A-8EF4-C502AD55F8DC")
        val AMS_REMOTE_COMMAND: UUID = UUID.fromString("9B3C81D8-57B1-4A8A-B8DF-0E56F7CA51C2")
        val AMS_ENTITY_UPDATE: UUID = UUID.fromString("2F7CABCE-808D-411F-9A0C-BB92BA96C102")
    }

    private var remoteCommandChar: BluetoothGattCharacteristic? = null
    private var iphoneUtcOffsetMinutes: Int? = null

    fun start(gatt: BluetoothGatt) {
        gatt.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL)?.let { level ->
            Log.i(TAG, "Battery Service found")
            read(level)
            if (level.canNotify()) subscribe(level)
        } ?: Log.w(TAG, "Battery Service not available")

        gatt.getService(CURRENT_TIME_SERVICE)?.let { cts ->
            Log.i(TAG, "Current Time Service found")
            // Offset first, so the Current Time read can be interpreted immediately
            cts.getCharacteristic(LOCAL_TIME_INFO)?.let { read(it) }
            cts.getCharacteristic(CURRENT_TIME)?.let { current ->
                read(current)
                if (current.canNotify()) subscribe(current)
            }
        } ?: Log.w(TAG, "Current Time Service not available")

        gatt.getService(AMS_SERVICE)?.let { ams ->
            val remote = ams.getCharacteristic(AMS_REMOTE_COMMAND)
            val entity = ams.getCharacteristic(AMS_ENTITY_UPDATE)
            if (remote == null || entity == null) {
                Log.w(TAG, "AMS characteristics missing")
                return@let
            }
            Log.i(TAG, "Apple Media Service found")
            remoteCommandChar = remote
            subscribe(remote)
            subscribe(entity)
            // Entity subscriptions must follow the CCCD writes — the queue keeps order
            write(entity, AmsProtocol.SUBSCRIBE_PLAYER)
            write(entity, AmsProtocol.SUBSCRIBE_TRACK)
        } ?: Log.w(TAG, "Apple Media Service not available")
    }

    /** Handle a read result or notification. Returns true if the UUID belonged to us. */
    fun onValue(uuid: UUID, value: ByteArray): Boolean {
        when (uuid) {
            BATTERY_LEVEL -> {
                if (value.isNotEmpty()) {
                    val percent = value[0].toInt() and 0xFF
                    Log.i(TAG, "iPhone battery: $percent%")
                    PhoneStatus.setBattery(percent)
                }
            }
            LOCAL_TIME_INFO -> {
                iphoneUtcOffsetMinutes = CtsProtocol.parseUtcOffsetMinutes(value)
                Log.i(TAG, "iPhone UTC offset: $iphoneUtcOffsetMinutes min")
            }
            CURRENT_TIME -> {
                val iphoneLocal = CtsProtocol.parseCurrentTime(value) ?: return true
                updateClock(iphoneLocal)
            }
            AMS_ENTITY_UPDATE -> {
                val update = AmsProtocol.parseEntityUpdate(value) ?: return true
                Log.d(TAG, "AMS update entity=${update.entity} attr=${update.attribute} value='${update.value}'")
                val now = SystemClock.elapsedRealtime()
                PhoneStatus.updateMedia { AmsProtocol.apply(it, update, now) }
            }
            AMS_REMOTE_COMMAND -> {
                val commands = AmsProtocol.parseSupportedCommands(value)
                Log.d(TAG, "AMS supported commands: $commands")
                PhoneStatus.updateMedia { it.copy(supportedCommands = commands) }
            }
            else -> return false
        }
        return true
    }

    /** Send an AMS remote command (play/pause/next/…) to the iPhone's active player. */
    fun sendMediaCommand(command: Int): Boolean {
        val char = remoteCommandChar ?: return false
        write(char, byteArrayOf(command.toByte()))
        return true
    }

    fun reset() {
        remoteCommandChar = null
        iphoneUtcOffsetMinutes = null
        PhoneStatus.reset()
    }

    private fun updateClock(iphoneLocal: LocalDateTime) {
        val nowMillis = System.currentTimeMillis()
        val watchOffsetMinutes = TimeZone.getDefault().getOffset(nowMillis) / 60_000
        val iphoneOffset = iphoneUtcOffsetMinutes
        val status = ClockStatus(
            driftSeconds = CtsProtocol.driftSeconds(iphoneLocal, iphoneOffset, nowMillis, watchOffsetMinutes),
            timeZoneMismatch = iphoneOffset?.let { it != watchOffsetMinutes },
            iphoneUtcOffsetMinutes = iphoneOffset,
            watchUtcOffsetMinutes = watchOffsetMinutes
        )
        Log.i(TAG, "Clock check: drift=${"%.1f".format(status.driftSeconds)}s " +
            "iphoneOffset=$iphoneOffset watchOffset=$watchOffsetMinutes")
        PhoneStatus.setClock(status)
    }

    private fun BluetoothGattCharacteristic.canNotify() =
        properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
}
