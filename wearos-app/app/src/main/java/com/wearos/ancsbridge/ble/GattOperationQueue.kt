package com.wearos.ancsbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Serializes GATT operations. Android allows only one outstanding read/write per
 * connection — a second call while one is in flight fails silently (returns busy).
 * With ANCS Control Point writes, AMS commands and Battery/CTS reads all sharing
 * the link, every operation goes through here.
 *
 * Must be used from the main thread (BleConnectionManager delivers GATT callbacks
 * on the main looper).
 */
@SuppressLint("MissingPermission")
class GattOperationQueue(
    private val scope: CoroutineScope,
    private val gattProvider: () -> BluetoothGatt?
) {

    companion object {
        private const val TAG = "GattOperationQueue"
        private const val OPERATION_TIMEOUT_MS = 5_000L
    }

    sealed class Op {
        /** Characteristic this operation targets — completions are matched against it. */
        abstract val uuid: UUID

        class WriteDescriptor(val descriptor: BluetoothGattDescriptor, val value: ByteArray) : Op() {
            override val uuid: UUID get() = descriptor.characteristic.uuid
            override fun toString() = "WriteDescriptor($uuid)"
        }
        class WriteCharacteristic(
            val characteristic: BluetoothGattCharacteristic,
            val value: ByteArray,
            val writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ) : Op() {
            override val uuid: UUID get() = characteristic.uuid
            override fun toString() = "WriteCharacteristic($uuid)"
        }
        class ReadCharacteristic(val characteristic: BluetoothGattCharacteristic) : Op() {
            override val uuid: UUID get() = characteristic.uuid
            override fun toString() = "ReadCharacteristic($uuid)"
        }
    }

    private val pending = ArrayDeque<Op>()
    private var inFlight: Op? = null
    private var timeoutJob: Job? = null

    fun enqueue(op: Op) {
        pending.addLast(op)
        if (inFlight == null) startNext()
    }

    /**
     * Call from every GATT completion callback (success or failure) with the
     * characteristic it was for. A late callback for an operation that already timed
     * out is ignored, so it can't prematurely "complete" the next one.
     */
    fun onOperationComplete(uuid: UUID) {
        val current = inFlight ?: return
        if (current.uuid != uuid) {
            Log.w(TAG, "Ignoring completion for $uuid while waiting for $current")
            return
        }
        advance()
    }

    private fun advance() {
        timeoutJob?.cancel()
        inFlight = null
        startNext()
    }

    fun clear() {
        timeoutJob?.cancel()
        pending.clear()
        inFlight = null
    }

    private fun startNext() {
        val gatt = gattProvider() ?: run {
            pending.clear()
            return
        }
        while (pending.isNotEmpty()) {
            val op = pending.removeFirst()
            val started = when (op) {
                is Op.WriteDescriptor ->
                    gatt.writeDescriptor(op.descriptor, op.value) == BluetoothStatusCodes.SUCCESS
                is Op.WriteCharacteristic ->
                    gatt.writeCharacteristic(op.characteristic, op.value, op.writeType) == BluetoothStatusCodes.SUCCESS
                is Op.ReadCharacteristic ->
                    gatt.readCharacteristic(op.characteristic)
            }
            if (started) {
                inFlight = op
                timeoutJob = scope.launch {
                    delay(OPERATION_TIMEOUT_MS)
                    Log.w(TAG, "Timed out waiting for $op")
                    advance()
                }
                return
            }
            Log.w(TAG, "Failed to start $op, skipping")
        }
    }
}
