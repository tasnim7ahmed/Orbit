package com.wearos.ancsbridge.ancs

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.wearos.ancsbridge.ble.BleConnectionManager

/**
 * Restarts AncsService after device boot if a bonded iPhone exists.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Installing an update stops the app, and nothing restarts a service on its own,
        // so the watch would sit disconnected until the app was opened by hand.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        // Nothing to reconnect to: don't run a foreground service for no reason
        if (BleConnectionManager.rememberedIPhoneAddress(context) == null) {
            Log.i(TAG, "Nothing to reconnect to: no iPhone has been paired yet")
            return
        }
        // The service enters the foreground as a connected-device service, which the
        // system refuses without this permission — starting it anyway would crash us
        // at boot on an install where the user hasn't granted it yet.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "The Bluetooth permission is not granted")
            return
        }

        Log.i(TAG, "Starting AncsService after ${intent.action}")
        val serviceIntent = Intent(context, AncsService::class.java).apply {
            action = AncsService.ACTION_RECONNECT
        }
        context.startForegroundService(serviceIntent)
    }
}
