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

    /** What a GATT callback reports completing; matched together with the UUID. */
    enum class Kind { DESCRIPTOR_WRITE, WRITE, READ }

    sealed class Op {
        /** Characteristic this operation targets — completions are matched against it. */
        abstract val uuid: UUID
        abstract val kind: Kind

        class WriteDescriptor(val descriptor: BluetoothGattDescriptor, val value: ByteArray) : Op() {
            override val uuid: UUID get() = descriptor.characteristic.uuid
            override val kind get() = Kind.DESCRIPTOR_WRITE
            override fun toString() = "WriteDescriptor($uuid)"
        }
        class WriteCharacteristic(
            val characteristic: BluetoothGattCharacteristic,
            val value: ByteArray,
            val writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ) : Op() {
            override val uuid: UUID get() = characteristic.uuid
            override val kind get() = Kind.WRITE
            override fun toString() = "WriteCharacteristic($uuid)"
        }
        /**
         * [onResult], when given, receives this read's value, or null if it failed, timed
         * out or could not start. Tying the answer to the read itself keeps several reads of
         * one characteristic apart, which a lookup by UUID could not.
         */
        class ReadCharacteristic(
            val characteristic: BluetoothGattCharacteristic,
            val onResult: ((ByteArray?) -> Unit)? = null
        ) : Op() {
            override val uuid: UUID get() = characteristic.uuid
            override val kind get() = Kind.READ
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
    fun onOperationComplete(uuid: UUID, kind: Kind) {
        val current = inFlight ?: return
        // The kind matters too: AMS Entity Attribute alternates a write and a read on one
        // characteristic, and a read answered after its timeout must not complete the write
        if (current.uuid != uuid || current.kind != kind) {
            Log.w(TAG, "Ignoring $kind completion for $uuid while waiting for $current")
            return
        }
        advance()
    }

    /**
     * A read finished (value null = failed). Returns true when it belonged to a read with its
     * own result handler, which has now had it, so the caller should not handle it again.
     * Call before [onOperationComplete].
     */
    fun deliverRead(uuid: UUID, value: ByteArray?): Boolean {
        val read = inFlight as? Op.ReadCharacteristic ?: return false
        if (read.uuid != uuid) return false
        val handler = read.onResult ?: return false
        handler(value)
        return true
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
                    (op as? Op.ReadCharacteristic)?.onResult?.invoke(null)
                    advance()
                }
                return
            }
            Log.w(TAG, "Failed to start $op, skipping")
            (op as? Op.ReadCharacteristic)?.onResult?.invoke(null)
        }
    }
}
