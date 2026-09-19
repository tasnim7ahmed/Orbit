package com.wearos.ancsbridge.ancs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wearos.ancsbridge.ble.AncsConstants
import com.wearos.ancsbridge.model.AncsNotification
import com.wearos.ancsbridge.model.MediaState
import com.wearos.ancsbridge.model.PhoneStatus

/**
 * Test hook: inject fake iPhone notifications and calls into the real pipeline,
 * so watch-side behavior (stacks, quiet delivery, ringing, dismiss sync) can be
 * verified without waiting for a real iPhone event.
 *
 * Only reachable via adb: the receiver requires android.permission.DUMP, which
 * only the shell and system hold. Example:
 *   adb shell am broadcast -n com.wearos.ancsbridge/.ancs.DebugInjectReceiver \
 *     --es kind notif --es app net.whatsapp.WhatsApp --es title Alice --es msg Hi --ei cat 4
 * kinds: notif, call, call_end, remove / dismiss (--el uid N),
 * media (--es title --es artist --ez playing)
 */
object DebugInjector {

    private const val TAG = "DebugInjector"
    private var nextUid = 900_000L

    fun handle(
        intent: Intent,
        post: (AncsNotification) -> Unit,
        call: (AncsNotification?) -> Unit,
        remove: (Long) -> Unit,
        dismiss: (Long) -> Unit
    ) {
        val kind = intent.getStringExtra("kind") ?: return
        val uid = intent.getLongExtra("uid", nextUid++)
        Log.i(TAG, "Injecting $kind uid=$uid")
        when (kind) {
            "notif" -> post(notification(intent, uid))
            "call" -> call(notification(intent, uid).copy(
                categoryId = AncsConstants.CATEGORY_INCOMING_CALL,
                positiveActionLabel = "Answer", negativeActionLabel = "Decline"
            ))
            "call_end" -> call(null)
            "remove" -> remove(uid)
            "dismiss" -> dismiss(uid)
            "media" -> PhoneStatus.updateMedia {
                it.copy(
                    available = true,
                    playerName = intent.getStringExtra("player") ?: "Spotify",
                    title = intent.getStringExtra("title") ?: "Test Track",
                    artist = intent.getStringExtra("artist") ?: "Test Artist",
                    playbackState = if (intent.getBooleanExtra("playing", true)) MediaState.PLAYBACK_PLAYING else MediaState.PLAYBACK_PAUSED,
                    playbackRate = if (intent.getBooleanExtra("playing", true)) 1f else 0f,
                    durationSec = 200.0,
                    elapsedSec = 30.0,
                    elapsedReportedAt = android.os.SystemClock.elapsedRealtime()
                )
            }
        }
    }

    private fun notification(intent: Intent, uid: Long): AncsNotification {
        val flags = intent.getIntExtra("flags",
            AncsConstants.EVENT_FLAG_POSITIVE_ACTION or AncsConstants.EVENT_FLAG_NEGATIVE_ACTION)
        return AncsNotification(
            uid = uid,
            appIdentifier = intent.getStringExtra("app") ?: "net.whatsapp.WhatsApp",
            appDisplayName = null,
            title = intent.getStringExtra("title") ?: "Test",
            subtitle = null,
            message = intent.getStringExtra("msg") ?: "Test message",
            date = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", java.util.Locale.US).format(java.util.Date()),
            categoryId = intent.getIntExtra("cat", AncsConstants.CATEGORY_SOCIAL),
            eventFlags = flags,
            positiveActionLabel = intent.getStringExtra("pos"),
            negativeActionLabel = intent.getStringExtra("neg")
        )
    }

    /** 8-byte Notification Source REMOVED event, as the iPhone would send it. */
    fun removedEvent(uid: Long, category: Int): ByteArray = byteArrayOf(
        AncsConstants.EVENT_ID_REMOVED.toByte(), 0, category.toByte(), 0,
        (uid and 0xFF).toByte(), ((uid shr 8) and 0xFF).toByte(),
        ((uid shr 16) and 0xFF).toByte(), ((uid shr 24) and 0xFF).toByte()
    )
}

/** adb-only entry point for [DebugInjector]; protected by android.permission.DUMP in the manifest. */
class DebugInjectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.startService(
            Intent(context, AncsService::class.java)
                .setAction(AncsService.ACTION_DEBUG_INJECT)
                .putExtras(intent)
        )
    }
}
