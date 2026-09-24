package com.wearos.ancsbridge.media

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.graphics.scale
import androidx.media.VolumeProviderCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.wearos.ancsbridge.AncsApplication
import com.wearos.ancsbridge.R
import com.wearos.ancsbridge.ancs.AncsService
import com.wearos.ancsbridge.ble.AmsProtocol
import com.wearos.ancsbridge.model.Artwork
import com.wearos.ancsbridge.model.MediaState
import com.wearos.ancsbridge.model.PhoneStatus
import com.wearos.ancsbridge.settings.AppSettings
import com.wearos.ancsbridge.surfaces.SurfaceUpdater
import com.wearos.ancsbridge.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Apple Watch-style Now Playing for whatever plays on the iPhone:
 *  - ongoing activity (music icon on the watch face, tap → Now Playing) with controls
 *  - a media session, so the watch's own media controls show and drive the iPhone's player
 *  - album art, looked up by name (Apple Media Service sends none)
 *  - auto-opens Now Playing when playback starts on the iPhone
 *  - hides after the player has been paused for a while
 */
class NowPlayingController(
    private val context: Context,
    private val scope: CoroutineScope,
    /** true right after (re)connecting, when AMS replays the current state */
    private val isSessionStarting: () -> Boolean,
    /** Sends an AmsProtocol.CMD_* to the iPhone; false when AMS is not connected. */
    private val sendCommand: (Int) -> Boolean
) {

    companion object {
        private const val TAG = "NowPlaying"
        const val NOTIFICATION_ID = 998
        private const val HIDE_AFTER_PAUSE_MS = 10 * 60_000L
        private const val AUTO_LAUNCH_COOLDOWN_MS = 60_000L
        /** Playback that starts this soon after a command from the watch was started from the watch. */
        private const val WATCH_COMMAND_WINDOW_MS = 5_000L
        /** Session volume scale; AMS reports the iPhone's volume as 0.0–1.0. */
        private const val VOLUME_STEPS = 100
    }

    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private var collectJob: Job? = null
    private var toggleJob: Job? = null
    private var artJob: Job? = null
    private var hideJob: Job? = null
    private var previous = MediaState()
    private var lastAutoLaunch = 0L
    private var lastWatchCommand = 0L
    private var showing = false
    /** Put away after a long pause; stays away until playback resumes or the track changes. */
    private var hiddenForPause = false
    private var session: MediaSessionCompat? = null
    private var volumeProvider: VolumeProviderCompat? = null
    /** Track the current artwork lookup is for, so a slow answer can't paint the next song. */
    private var artKey: String? = null
    private var lastMetadataKey: List<Any?>? = null
    /** Notification-sized copy of the cover (the full one is sized for Now Playing). */
    private var largeIcon: Pair<Bitmap, Bitmap>? = null

    fun start() {
        collectJob = scope.launch {
            PhoneStatus.media.collect { onMedia(it) }
        }
        // Album art switched off or on in Settings: drop the cover, or look it up now
        toggleJob = scope.launch {
            AppSettings.toggles.map { it.albumArt }.distinctUntilChanged().drop(1).collect {
                // Start over: no cover, no lookup in flight; switched on, look it up again
                artJob?.cancel()
                artKey = null
                PhoneStatus.setArtwork(null)
                updateArtwork(previous)
                if (!it) {
                    val current = previous
                    if (showing && current.available && current.hasTrack) {
                        updateSession(current)
                        show(current)
                    }
                    SurfaceUpdater.request(context, SurfaceUpdater.MEDIA)
                }
            }
        }
    }

    /**
     * A media command was sent from the watch (a media screen, tile, notification). Playback it
     * starts must not auto-open Now Playing over the screen the user pressed Play on.
     */
    fun onWatchCommand() {
        lastWatchCommand = System.currentTimeMillis()
    }

    fun stop() {
        collectJob?.cancel()
        toggleJob?.cancel()
        artJob?.cancel()
        hide()
        session?.release()
        session = null
        volumeProvider = null
        lastMetadataKey = null
    }

    private fun onMedia(media: MediaState) {
        val prev = previous
        previous = media

        updateArtwork(media)

        if (!media.available || !media.hasTrack) {
            hiddenForPause = false
            hide()
        } else if (hiddenForPause && !media.isPlaying && media.title == prev.title && media.artist == prev.artist) {
            // Still the same paused track: a volume or position update must not bring it back
        } else {
            hiddenForPause = false
            // Position, volume and supported commands change without a visible notification change
            updateSession(media)
            if (!showing || media.title != prev.title || media.artist != prev.artist ||
                media.isPlaying != prev.isPlaying || media.playerName != prev.playerName
            ) {
                // Only re-post on visible changes, not every elapsed-time/volume update
                show(media)
                hideJob?.cancel()
                if (!media.isPlaying) {
                    hideJob = scope.launch {
                        delay(HIDE_AFTER_PAUSE_MS)
                        hiddenForPause = true
                        hide()
                    }
                }
            }
        }

        // Playback just started on the iPhone → open Now Playing (not when AMS is
        // merely replaying current state after a reconnect)
        val startedPlaying = media.isPlaying && prev.available && !prev.isPlaying
        val now = System.currentTimeMillis()
        if (startedPlaying && AppSettings.toggles.value.autoLaunchNowPlaying && !isSessionStarting() &&
            now - lastAutoLaunch > AUTO_LAUNCH_COOLDOWN_MS &&
            now - lastWatchCommand > WATCH_COMMAND_WINDOW_MS &&
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
            SurfaceUpdater.request(context, SurfaceUpdater.MEDIA)
        }
    }

    /** New track → drop the old cover at once, then look the new one up (if allowed). */
    private fun updateArtwork(media: MediaState) {
        val key = ArtworkRepository.keyOf(media)?.takeIf { AppSettings.toggles.value.albumArt }
        if (key == artKey) return
        artKey = key
        artJob?.cancel()
        if (PhoneStatus.artwork.value?.trackKey != key) PhoneStatus.setArtwork(null)
        if (key == null) return
        artJob = scope.launch {
            val bitmap = ArtworkRepository.fetch(context, media) ?: return@launch
            if (artKey != key) return@launch
            PhoneStatus.setArtwork(Artwork(key, bitmap))
            val current = previous
            if (showing && current.available && current.hasTrack) {
                updateSession(current)
                show(current)
            }
            SurfaceUpdater.request(context, SurfaceUpdater.MEDIA)
        }
    }

    /** Artwork for [media]'s track, if it has arrived. */
    private fun artFor(media: MediaState) =
        PhoneStatus.artwork.value?.takeIf { it.trackKey == ArtworkRepository.keyOf(media) }?.bitmap

    private fun notificationIcon(art: Bitmap): Bitmap {
        largeIcon?.takeIf { it.first === art }?.let { return it.second }
        val px = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)
            .coerceIn(64, ArtworkRepository.ART_PX)
        return art.scale(px, px).also { largeIcon = art to it }
    }

    private fun show(media: MediaState) {
        val session = sessionFor()
        val open = PendingIntent.getActivity(
            context, 20, MainActivity.openMediaIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = media.displayTitle
        val builder = NotificationCompat.Builder(context, AncsApplication.CHANNEL_NOW_PLAYING)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle(title)
            .setContentText(listOf(media.artist, media.playerName).filter { it.isNotEmpty() }.joinToString(" · "))
            .setLargeIcon(artFor(media)?.let { notificationIcon(it) })
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
            // Media style + session token: the watch's own media controls pick this up
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

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
        // Leave the session idle so the watch's media controls stop offering it
        session?.isActive = false
        showing = false
    }

    /**
     * The media session that stands in for the iPhone's player. Its transport callbacks
     * and its (remote, relative) volume send Apple Media Service commands.
     */
    private fun sessionFor(): MediaSessionCompat {
        session?.let { return it }
        val volume = object : VolumeProviderCompat(VOLUME_CONTROL_RELATIVE, VOLUME_STEPS, 0) {
            override fun onAdjustVolume(direction: Int) {
                when {
                    direction > 0 -> send(AmsProtocol.CMD_VOLUME_UP)
                    direction < 0 -> send(AmsProtocol.CMD_VOLUME_DOWN)
                }
            }
        }
        return MediaSessionCompat(context, "Orbit iPhone").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { send(AmsProtocol.CMD_PLAY) }
                override fun onPause() { send(AmsProtocol.CMD_PAUSE) }
                override fun onStop() { send(AmsProtocol.CMD_PAUSE) }
                override fun onSkipToNext() { send(AmsProtocol.CMD_NEXT_TRACK) }
                override fun onSkipToPrevious() { send(AmsProtocol.CMD_PREVIOUS_TRACK) }
                override fun onFastForward() { send(AmsProtocol.CMD_SKIP_FORWARD) }
                override fun onRewind() { send(AmsProtocol.CMD_SKIP_BACKWARD) }
            })
            setPlaybackToRemote(volume)
            volumeProvider = volume
            session = this
        }
    }

    private fun send(command: Int) {
        onWatchCommand()
        if (sendCommand(command)) Log.i(TAG, "Media session command $command sent to the iPhone")
        else Log.w(TAG, "Media command $command dropped — Apple Media Service not connected")
    }

    private fun updateSession(media: MediaState) {
        val session = sessionFor()
        var actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP
        if (media.supports(AmsProtocol.CMD_NEXT_TRACK)) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        if (media.supports(AmsProtocol.CMD_PREVIOUS_TRACK)) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        // Like the watch's Now Playing screen: skip buttons only when the player names them
        if (AmsProtocol.CMD_SKIP_FORWARD in media.supportedCommands) actions = actions or PlaybackStateCompat.ACTION_FAST_FORWARD
        if (AmsProtocol.CMD_SKIP_BACKWARD in media.supportedCommands) actions = actions or PlaybackStateCompat.ACTION_REWIND

        val state = when (media.playbackState) {
            MediaState.PLAYBACK_PLAYING -> PlaybackStateCompat.STATE_PLAYING
            MediaState.PLAYBACK_REWINDING -> PlaybackStateCompat.STATE_REWINDING
            MediaState.PLAYBACK_FAST_FORWARDING -> PlaybackStateCompat.STATE_FAST_FORWARDING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        // AMS reports elapsed time with the SystemClock.elapsedRealtime() it arrived at,
        // which is the time base the session's position extrapolation uses too
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    state,
                    (media.elapsedSec * 1000).toLong().coerceAtLeast(0L),
                    media.playbackRate,
                    media.elapsedReportedAt
                )
                .build()
        )

        // Metadata carries the cover, so it is only sent again when something in it changed
        val art = artFor(media)
        val metadataKey = listOf(media.title, media.artist, media.album, media.playerName, media.durationSec, art?.let { System.identityHashCode(it) })
        if (metadataKey != lastMetadataKey) {
            lastMetadataKey = metadataKey
            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, media.displayTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, media.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, media.album)
            media.durationSec?.let { metadata.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, (it * 1000).toLong()) }
            art?.let { metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
            session.setMetadata(metadata.build())
        }

        media.volume?.let { volumeProvider?.currentVolume = (it * VOLUME_STEPS).toInt().coerceIn(0, VOLUME_STEPS) }
        if (!session.isActive) session.isActive = true
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
