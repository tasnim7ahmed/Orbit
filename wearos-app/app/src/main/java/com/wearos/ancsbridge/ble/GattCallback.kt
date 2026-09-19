package com.wearos.ancsbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.util.Log
import java.util.UUID

/**
 * BluetoothGattCallback implementation that routes ANCS characteristic changes
 * to the appropriate handlers.
 */
@SuppressLint("MissingPermission")
class GattCallback(
    private val onConnected: (BluetoothGatt) -> Unit,
    private val onDisconnected: (BluetoothGatt, Int) -> Unit,
    private val onServicesDiscoveredCallback: (BluetoothGatt, Int) -> Unit,
    private val onNotificationSourceChanged: (ByteArray) -> Unit,
    private val onDataSourceChanged: (ByteArray) -> Unit,
    private val onDescriptorWritten: (BluetoothGattDescriptor, Int) -> Unit,
    private val onCharacteristicWritten: (BluetoothGattCharacteristic, Int) -> Unit,
    private val onMtuChanged: (Int, Int) -> Unit,
    private val onCharacteristicReadCallback: (BluetoothGattCharacteristic, ByteArray, Int) -> Unit,
    /** Notifications from non-ANCS characteristics (Battery, CTS, AMS) */
    private val onOtherCharacteristicChanged: ((UUID, ByteArray) -> Unit)? = null
) : BluetoothGattCallback() {

    companion object {
        private const val TAG = "GattCallback"
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        Log.d(TAG, "Connection state changed: status=$status newState=$newState")
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                Log.i(TAG, "Connected to ${gatt.device.address}")
                onConnected(gatt)
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                Log.i(TAG, "Disconnected from ${gatt.device.address} status=$status")
                onDisconnected(gatt, status)
            }
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        Log.d(TAG, "Services discovered: status=$status count=${gatt.services.size}")
        if (status == BluetoothGatt.GATT_SUCCESS) {
            gatt.services.forEach { service ->
                Log.d(TAG, "  Service: ${service.uuid}")
            }
        }
        onServicesDiscoveredCallback(gatt, status)
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        when (characteristic.uuid) {
            AncsConstants.NOTIFICATION_SOURCE_UUID -> {
                Log.d(TAG, "Notification Source: ${value.toHexString()}")
                onNotificationSourceChanged(value)
            }
            AncsConstants.DATA_SOURCE_UUID -> {
                Log.d(TAG, "Data Source: ${value.size} bytes")
                onDataSourceChanged(value)
            }
            else -> onOtherCharacteristicChanged?.invoke(characteristic.uuid, value)
        }
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int
    ) {
        Log.d(TAG, "Descriptor written: ${descriptor.characteristic.uuid} status=$status")
        onDescriptorWritten(descriptor, status)
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int
    ) {
        Log.d(TAG, "Characteristic written: ${characteristic.uuid} status=$status")
        onCharacteristicWritten(characteristic, status)
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ) {
        Log.d(TAG, "Characteristic read: ${characteristic.uuid} (${value.size} bytes) status=$status")
        onCharacteristicReadCallback(characteristic, value, status)
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        Log.d(TAG, "MTU changed: mtu=$mtu status=$status")
        onMtuChanged(mtu, status)
    }

    private fun ByteArray.toHexString(): String =
        joinToString(" ") { "%02X".format(it) }
}
