package com.wearos.ancsbridge.ancs

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * Whether the watch is on a wrist, from the low-latency off-body sensor
 * (Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT). The Pixel Watch has it, and it needs no
 * permission. Like an Apple Watch, Orbit stops buzzing while the watch is off the wrist:
 * the iPhone still alerts as usual.
 *
 * The wake-up variant is used so the state is current even while the CPU sleeps; it is
 * on-change, so it only wakes the watch when the watch is put on or taken off.
 * Callbacks arrive on the main thread.
 */
class WristDetector(context: Context, private val onChange: (offWrist: Boolean) -> Unit) : SensorEventListener {

    companion object {
        private const val TAG = "Wrist"
    }

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, true)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)

    /**
     * true only once the sensor has said the watch is off the wrist. Unknown counts as
     * worn, so a missing or silent sensor can never mute anything.
     */
    var isOffWrist = false
        private set

    /** Test hook: pretend the watch is on (false) or off (true) the wrist until the sensor next reports. */
    fun simulate(offWrist: Boolean) = update(offWrist)

    fun start() {
        val s = sensor ?: run {
            Log.i(TAG, "No off-body sensor on this watch")
            return
        }
        // The sensor reports its current state when it is enabled
        sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT || event.values.isEmpty()) return
        // 1.0 = on body, 0.0 = off body
        update(event.values[0] < 0.5f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun update(offWrist: Boolean) {
        if (offWrist == isOffWrist) return
        isOffWrist = offWrist
        Log.i(TAG, if (offWrist) "Watch taken off the wrist" else "Watch on the wrist")
        onChange(offWrist)
    }
}
