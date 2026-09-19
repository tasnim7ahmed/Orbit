package com.wearos.ancsbridge.ancs

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

/**
 * Continuous vibration for incoming calls — keeps buzzing until answered, declined
 * or silenced, like an Apple Watch does.
 *
 * Notification channels can only buzz once, so this drives the vibrator directly
 * with a repeating waveform and stops on [stop] or after a safety cap.
 */
class Ringer(context: Context) {

    companion object {
        private const val TAG = "Ringer"

        // ring-ring … pause, repeated
        private val CALL_PATTERN = longArrayOf(0, 500, 250, 500, 1400)

        const val CALL_MAX_MS = 45_000L   // matches IncomingCallActivity's safety timeout
    }

    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable { stop("timeout") }

    var isRinging = false
        private set

    fun start() {
        handler.removeCallbacks(stopRunnable)
        // Ringtone usage keeps vibrating with the screen off and follows the watch's
        // own "vibrate for calls" and Do Not Disturb rules.
        vibrator.vibrate(
            VibrationEffect.createWaveform(CALL_PATTERN, 0),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE)
        )
        isRinging = true
        handler.postDelayed(stopRunnable, CALL_MAX_MS)
        Log.i(TAG, "Ringing")
    }

    fun stop(reason: String = "") {
        handler.removeCallbacks(stopRunnable)
        if (isRinging) Log.i(TAG, "Stopped: $reason")
        isRinging = false
        vibrator.cancel()
    }
}
