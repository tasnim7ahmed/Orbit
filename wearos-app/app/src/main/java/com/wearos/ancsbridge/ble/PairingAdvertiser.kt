package com.wearos.ancsbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log

/**
 * Makes the watch discoverable to the iPhone without any iPhone app.
 *
 * The watch advertises as a connectable BLE peripheral with an ANCS *service
 * solicitation* UUID. iOS treats that as "this accessory wants notifications":
 * the watch shows up in iPhone Settings → Bluetooth, and tapping it makes the
 * iPhone connect as central. Once the link is up, the watch attaches a GATT
 * client to the same link (connectGatt on an already-connected device) and runs
 * the normal ANCS flow: bond → discover → subscribe DS → subscribe NS.
 *
 * The same advertising also lets a bonded iPhone reconnect to the watch.
 */
@SuppressLint("MissingPermission")
class PairingAdvertiser(
    private val context: Context,
    private val onIncomingConnection: (BluetoothDevice) -> Unit
) {

    companion object {
        private const val TAG = "PairingAdvertiser"
    }

    private val bluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    private var gattServer: BluetoothGattServer? = null
    private var isAdvertising = false
    private var includeName = true

    /** Mode the current advertising was started in (pairing = fast, reconnect = low power). */
    private var fastMode = false

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            // Fires for every LE link on this adapter, inbound or outbound.
            // BleConnectionManager.connect() ignores devices it's already handling.
            Log.d(TAG, "Server link state: ${device.address} status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onIncomingConnection(device)
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "Advertising started (fast=$fastMode, name=$includeName)")
            isAdvertising = true
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed: $errorCode")
            isAdvertising = false
            // Long adapter names overflow the 31-byte scan response — retry without it.
            if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && includeName) {
                includeName = false
                start(fastMode)
            }
        }
    }

    val isSupported: Boolean
        get() = bluetoothManager.adapter?.isMultipleAdvertisementSupported == true

    /** Name the iPhone will show in Settings → Bluetooth. */
    val advertisedName: String?
        get() = bluetoothManager.adapter?.name

    /**
     * Start advertising.
     * @param fast true while the user is actively pairing (low latency); false for
     *             background reconnect advertising (low power).
     */
    fun start(fast: Boolean) {
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth off — can't advertise")
            return
        }
        val advertiser = adapter.bluetoothLeAdvertiser ?: run {
            Log.e(TAG, "BLE advertising not supported on this device")
            return
        }

        if (isAdvertising) {
            if (fast == fastMode) return
            advertiser.stopAdvertising(advertiseCallback)
            isAdvertising = false
        }
        fastMode = fast

        if (gattServer == null) {
            gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(
                if (fast) AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                else AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
            )
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        // flags (3) + 128-bit solicitation (18) = 21 of 31 bytes
        val data = AdvertiseData.Builder()
            .addServiceSolicitationUuid(ParcelUuid(AncsConstants.ANCS_SERVICE_UUID))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(includeName)
            .build()

        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    fun stop() {
        if (isAdvertising) {
            bluetoothManager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            isAdvertising = false
            Log.i(TAG, "Advertising stopped")
        }
    }

    fun destroy() {
        stop()
        gattServer?.close()
        gattServer = null
    }
}
