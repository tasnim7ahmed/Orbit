package com.wearos.ancsbridge.media

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.wearos.ancsbridge.AncsApplication
import com.wearos.ancsbridge.R
import com.wearos.ancsbridge.ancs.AncsService
import com.wearos.ancsbridge.ble.AmsProtocol
import com.wearos.ancsbridge.model.MediaState
import com.wearos.ancsbridge.model.PhoneStatus
import com.wearos.ancsbridge.settings.AppSettings
import com.wearos.ancsbridge.surfaces.SurfaceUpdater
import com.wearos.ancsbridge.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Apple Watch-style Now Playing for whatever plays on the iPhone:
 *  - ongoing activity (music icon on the watch face, tap → Now Playing) with controls
 *  - auto-opens Now Playing when playback starts on the iPhone
 *  - hides after the player has been paused for a while
 */
class NowPlayingController(
    private val context: Context,
    private val scope: CoroutineScope,
    /** true right after (re)connecting, when AMS replays the current state */
    private val isSessionStarting: () -> Boolean
) {

    companion object {
        private const val TAG = "NowPlaying"
        const val NOTIFICATION_ID = 998
        private const val HIDE_AFTER_PAUSE_MS = 10 * 60_000L
        private const val AUTO_LAUNCH_COOLDOWN_MS = 60_000L
    }

    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private var collectJob: Job? = null
    private var hideJob: Job? = null
    private var previous = MediaState()
    private var lastAutoLaunch = 0L
    private var showing = false

    fun start() {
        collectJob = scope.launch {
            PhoneStatus.media.collect { onMedia(it) }
        }
    }

    fun stop() {
        collectJob?.cancel()
        hide()
    }

    private fun onMedia(media: MediaState) {
        val prev = previous
        previous = media

        if (!media.available || !media.hasTrack) {
            hide()
        } else if (!showing || media.title != prev.title || media.artist != prev.artist ||
            media.isPlaying != prev.isPlaying || media.playerName != prev.playerName
        ) {
            // Only re-post on visible changes, not every elapsed-time/volume update
            show(media)
            hideJob?.cancel()
            if (!media.isPlaying) {
                hideJob = scope.launch {
                    delay(HIDE_AFTER_PAUSE_MS)
                    hide()
                }
            }
        }

        // Playback just started on the iPhone → open Now Playing (not when AMS is
        // merely replaying current state after a reconnect)
        val startedPlaying = media.isPlaying && prev.available && !prev.isPlaying
        val now = System.currentTimeMillis()
        if (startedPlaying && AppSettings.toggles.value.autoLaunchNowPlaying && !isSessionStarting() &&
            now - lastAutoLaunch > AUTO_LAUNCH_COOLDOWN_MS &&
            android.provider.Settings.canDrawOverlays(context)
        ) {
            lastAutoLaunch = now
            Log.i(TAG, "Playback started on iPhone — auto-opening Now Playing")
            try {
                context.startActivity(MainActivity.openMediaIntent(context))
            } catch (e: Exception) {
                Log.w(TAG, "Auto-open failed: ${e.message}")
            }
        }

        if (media.title != prev.title || media.isPlaying != prev.isPlaying || media.available != prev.available) {
            SurfaceUpdater.requestAll(context)
        }
    }

    private fun show(media: MediaState) {
        val open = PendingIntent.getActivity(
            context, 20, MainActivity.openMediaIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = media.title.ifEmpty { media.playerName }
        val builder = NotificationCompat.Builder(context, AncsApplication.CHANNEL_NOW_PLAYING)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle(title)
            .setContentText(listOf(media.artist, media.playerName).filter { it.isNotEmpty() }.joinToString(" · "))
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_skip_previous, "Previous", command(AmsProtocol.CMD_PREVIOUS_TRACK, 21))
            .addAction(
                R.drawable.ic_play_pause, if (media.isPlaying) "Pause" else "Play",
                command(AmsProtocol.CMD_TOGGLE_PLAY_PAUSE, 22)
            )
            .addAction(R.drawable.ic_skip_next, "Next", command(AmsProtocol.CMD_NEXT_TRACK, 23))

        // Ongoing activity: music icon on the watch face + entry in recents
        OngoingActivity.Builder(context, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_music)
            .setTouchIntent(open)
            .setStatus(Status.Builder().addTemplate(if (media.isPlaying) title else "Paused · $title").build())
            .build()
            .apply(context)

        notificationManager.notify(NOTIFICATION_ID, builder.build())
        showing = true
    }

    private fun hide() {
        hideJob?.cancel()
        if (!showing) return
        notificationManager.cancel(NOTIFICATION_ID)
        showing = false
    }

    private fun command(cmd: Int, requestCode: Int): PendingIntent =
        PendingIntent.getForegroundService(
            context, requestCode,
            Intent(context, AncsService::class.java)
                .setAction(AncsService.ACTION_MEDIA_COMMAND)
                .putExtra(AncsService.EXTRA_MEDIA_COMMAND, cmd),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
