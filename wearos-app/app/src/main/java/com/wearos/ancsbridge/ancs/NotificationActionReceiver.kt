package com.wearos.ancsbridge.ancs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.wearos.ancsbridge.settings.AppSettings

/**
 * Receives notification action button presses. Positive/negative go to AncsService for
 * Control Point writing; "Mute 1 hour" is handled here, since it stays on the watch.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotifActionReceiver"
        const val ACTION_POSITIVE = "com.wearos.ancsbridge.ACTION_POSITIVE"
        const val ACTION_NEGATIVE = "com.wearos.ancsbridge.ACTION_NEGATIVE"
        const val EXTRA_NOTIFICATION_UID = "notification_uid"
        const val ACTION_MUTE_APP = "com.wearos.ancsbridge.ACTION_MUTE_APP"
        const val EXTRA_BUNDLE_ID = "bundle_id"
        const val EXTRA_APP_NAME = "app_name"
        const val MUTE_DURATION_MS = 60 * 60_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_MUTE_APP) {
            val bundleId = intent.getStringExtra(EXTRA_BUNDLE_ID) ?: return
            val name = intent.getStringExtra(EXTRA_APP_NAME) ?: bundleId
            AppSettings.init(context)
            AppSettings.muteFor(bundleId, name, MUTE_DURATION_MS)
            Log.i(TAG, "Muted an app for 1 hour")
            Toast.makeText(context, "$name muted for 1 hour", Toast.LENGTH_SHORT).show()
            return
        }

        val uid = intent.getLongExtra(EXTRA_NOTIFICATION_UID, -1)
        if (uid == -1L) {
            Log.w(TAG, "No notification UID in intent")
            return
        }

        val actionId = when (intent.action) {
            ACTION_POSITIVE -> 0
            ACTION_NEGATIVE -> 1
            else -> {
                Log.w(TAG, "Unknown action: ${intent.action}")
                return
            }
        }

        Log.d(TAG, "Notification action: uid=$uid actionId=$actionId")

        // Forward to service
        val serviceIntent = Intent(context, AncsService::class.java).apply {
            action = AncsService.ACTION_PERFORM_NOTIFICATION_ACTION
            putExtra(EXTRA_NOTIFICATION_UID, uid)
            putExtra(AncsService.EXTRA_ACTION_ID, actionId)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
