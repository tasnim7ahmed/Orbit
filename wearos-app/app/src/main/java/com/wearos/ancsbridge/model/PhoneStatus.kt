package com.wearos.ancsbridge.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live iPhone status read over BLE from standard services the iPhone exposes to
 * bonded accessories: Battery (0x180F), Current Time (0x1805) and Apple Media
 * Service. Written by BleConnectionManager, observed by the UI and AncsService.
 */
object PhoneStatus {

    private val _battery = MutableStateFlow<Int?>(null)
    val battery: StateFlow<Int?> = _battery.asStateFlow()

    private val _media = MutableStateFlow(MediaState())
    val media: StateFlow<MediaState> = _media.asStateFlow()

    private val _clock = MutableStateFlow<ClockStatus?>(null)
    val clock: StateFlow<ClockStatus?> = _clock.asStateFlow()

    fun setBattery(percent: Int) { _battery.value = percent }

    fun updateMedia(transform: (MediaState) -> MediaState) { _media.value = transform(_media.value) }

    fun setClock(status: ClockStatus) { _clock.value = status }

    fun reset() {
        _battery.value = null
        _media.value = MediaState()
        _clock.value = null
    }
}

/** Now-playing state mirrored from the iPhone via Apple Media Service (AMS). */
data class MediaState(
    val available: Boolean = false,
    val playerName: String = "",
    val playbackState: Int = PLAYBACK_PAUSED,
    val playbackRate: Float = 0f,
    val elapsedSec: Double = 0.0,
    /** SystemClock.elapsedRealtime() when [elapsedSec] was reported. */
    val elapsedReportedAt: Long = 0L,
    val volume: Float? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationSec: Double? = null,
    val supportedCommands: Set<Int> = emptySet(),
    /** Position of the current track in the player's queue, zero-based. */
    val queueIndex: Int? = null,
    val queueCount: Int? = null,
    /** AmsProtocol.MODE_OFF / MODE_ONE / MODE_ALL, null until the iPhone reports it. */
    val shuffleMode: Int? = null,
    val repeatMode: Int? = null
) {
    val isPlaying get() = playbackState == PLAYBACK_PLAYING
    val hasTrack get() = title.isNotEmpty() || artist.isNotEmpty()

    /** "3 of 21", or null when the player doesn't report a queue. */
    val queuePosition: String?
        get() {
            val count = queueCount?.takeIf { it > 0 } ?: return null
            val index = queueIndex ?: return null
            return "${index + 1} of $count"
        }

    fun supports(command: Int) = supportedCommands.isEmpty() || command in supportedCommands

    /** Current position extrapolated from the last report. */
    fun positionAt(nowElapsedRealtime: Long): Double {
        val advanced = elapsedSec + playbackRate * (nowElapsedRealtime - elapsedReportedAt) / 1000.0
        val clamped = advanced.coerceAtLeast(0.0)
        return durationSec?.let { clamped.coerceAtMost(it) } ?: clamped
    }

    companion object {
        const val PLAYBACK_PAUSED = 0
        const val PLAYBACK_PLAYING = 1
        const val PLAYBACK_REWINDING = 2
        const val PLAYBACK_FAST_FORWARDING = 3
    }
}

/**
 * Watch clock compared with the iPhone's Current Time Service.
 *
 * @param driftSeconds iPhone time minus watch time (positive = watch is behind).
 * @param timeZoneMismatch true when the iPhone's UTC offset differs from the watch's;
 *                         null if the iPhone didn't report its offset.
 */
data class ClockStatus(
    val driftSeconds: Double,
    val timeZoneMismatch: Boolean?,
    val iphoneUtcOffsetMinutes: Int?,
    val watchUtcOffsetMinutes: Int
) {
    val inSync get() = kotlin.math.abs(driftSeconds) < SYNC_TOLERANCE_SEC && timeZoneMismatch != true

    companion object {
        /** BLE read latency + 1/256 s CTS resolution — below this counts as in sync. */
        const val SYNC_TOLERANCE_SEC = 3.0
    }
}
