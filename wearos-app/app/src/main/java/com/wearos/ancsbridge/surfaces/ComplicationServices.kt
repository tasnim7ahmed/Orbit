package com.wearos.ancsbridge.surfaces

import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.wearos.ancsbridge.R
import com.wearos.ancsbridge.model.MediaState
import com.wearos.ancsbridge.model.PhoneStatus

private fun text(s: String) = PlainComplicationText.Builder(s).build()

/**
 * iPhone battery + link status for the watch face — like the iPhone battery in
 * Apple Watch Control Center. Shows "—" when the iPhone isn't connected.
 */
class IPhoneBatteryComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, 80, connected = true)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        build(request.complicationType, PhoneStatus.battery.value, SurfaceUpdater.isConnected)

    private fun build(type: ComplicationType, battery: Int?, connected: Boolean): ComplicationData? {
        val label = if (connected && battery != null) "$battery%" else "—"
        val description = text(if (connected) "iPhone battery $label" else "iPhone disconnected")
        val icon = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_iphone)).build()
        val tap = SurfaceUpdater.openApp(this)
        return when (type) {
            ComplicationType.RANGED_VALUE ->
                RangedValueComplicationData.Builder(
                    value = if (connected) (battery ?: 0).toFloat() else 0f,
                    min = 0f, max = 100f,
                    contentDescription = description
                ).setText(text(label)).setMonochromaticImage(icon).setTapAction(tap).build()
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(text(label), description)
                    .setMonochromaticImage(icon).setTapAction(tap).build()
            else -> null
        }
    }
}

/** Current iPhone track for the watch face; tap opens Now Playing. */
class NowPlayingComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, MediaState(available = true, playbackState = MediaState.PLAYBACK_PLAYING,
            title = "Blinding Lights", artist = "The Weeknd"))

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        build(request.complicationType, PhoneStatus.media.value)

    private fun build(type: ComplicationType, media: MediaState): ComplicationData? {
        val hasTrack = media.available && media.hasTrack && SurfaceUpdater.isConnected
        val title = if (hasTrack) media.title.ifEmpty { media.playerName } else "Not playing"
        val icon = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_music)).build()
        val tap = SurfaceUpdater.openApp(this, media = true)
        val description = text(if (hasTrack) "Now playing $title" else "Nothing playing on iPhone")
        return when (type) {
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(text(if (hasTrack) title.take(7) else "—"), description)
                    .setMonochromaticImage(icon).setTapAction(tap).build()
            ComplicationType.LONG_TEXT ->
                LongTextComplicationData.Builder(text(title), description)
                    .setTitle(text(if (hasTrack) media.artist.ifEmpty { media.playerName } else "iPhone"))
                    .setMonochromaticImage(icon).setTapAction(tap).build()
            else -> null
        }
    }
}
