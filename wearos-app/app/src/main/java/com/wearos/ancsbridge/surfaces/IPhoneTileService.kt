package com.wearos.ancsbridge.surfaces

import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.core.graphics.scale
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream
import com.wearos.ancsbridge.ancs.AncsService
import com.wearos.ancsbridge.ble.AmsProtocol
import com.wearos.ancsbridge.media.ArtworkRepository
import com.wearos.ancsbridge.model.Artwork
import com.wearos.ancsbridge.model.PhoneStatus
import com.wearos.ancsbridge.ui.MainActivity

/**
 * "iPhone" tile: link status, battery, and what's playing with a play/pause chip —
 * a glanceable Control Center + Now Playing.
 */
class IPhoneTileService : TileService() {

    companion object {
        private const val RESOURCES_VERSION = "1"
        private const val ID_TOGGLE = "toggle_play"
        private const val ID_ART = "art"
        private const val ART_DP = 36f
        private const val ART_PX = 96
        // Same roles as the apps Material 3 theme: muted text, healthy link, warning
        private const val GREY = 0xFFC3C6CF.toInt()
        private const val GREEN = 0xFF6DD58C.toInt()
        private const val RED = 0xFFF2B8B5.toInt()
        private const val BLUE = 0xFFA8C7FA.toInt()
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        // Play/pause chip uses a LoadAction, so the click arrives here
        if (requestParams.currentState.lastClickableId == ID_TOGGLE) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, AncsService::class.java)
                    .setAction(AncsService.ACTION_MEDIA_COMMAND)
                    .putExtra(AncsService.EXTRA_MEDIA_COMMAND, AmsProtocol.CMD_TOGGLE_PLAY_PAUSE)
            )
        }
        val tile = TileBuilders.Tile.Builder()
            // The cover is a resource, so its track is part of the version: a new song
            // makes the tile ask for resources again
            .setResourcesVersion(resourcesVersion(currentArt()))
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(requestParams.deviceConfiguration)))
            .build()
        return CallbackToFutureAdapter.getFuture { it.set(tile); "tile" }
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> {
        val resources = ResourceBuilders.Resources.Builder().setVersion(requestParams.version)
        // Only the cover the tile was built with; if the song changed in between, the
        // tile is already being refreshed with a new version
        currentArt()?.takeIf { resourcesVersion(it) == requestParams.version }?.let { art ->
            val px = ART_PX.coerceAtMost(art.bitmap.width)
            val scaled = art.bitmap.scale(px, px)
            val bytes = ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
            resources.addIdToImageMapping(
                ID_ART,
                ResourceBuilders.ImageResource.Builder()
                    .setInlineResource(
                        // Format left undefined: the renderer decodes the PNG itself
                        ResourceBuilders.InlineImageResource.Builder()
                            .setData(bytes)
                            .setWidthPx(px)
                            .setHeightPx(px)
                            .build()
                    ).build()
            )
        }
        val built = resources.build()
        return CallbackToFutureAdapter.getFuture { it.set(built); "resources" }
    }

    /** Album art for the track on the tile, when connected and found. */
    private fun currentArt(): Artwork? {
        if (!SurfaceUpdater.isConnected) return null
        val media = PhoneStatus.media.value
        if (!media.available || !media.hasTrack) return null
        return PhoneStatus.artwork.value?.takeIf { it.trackKey == ArtworkRepository.keyOf(media) }
    }

    private fun resourcesVersion(art: Artwork?): String =
        if (art == null) RESOURCES_VERSION
        else RESOURCES_VERSION + "-" + Integer.toHexString(art.trackKey.hashCode())

    private fun layout(device: DeviceParameters): LayoutElementBuilders.LayoutElement {
        val connected = SurfaceUpdater.isConnected
        val battery = PhoneStatus.battery.value
        val media = PhoneStatus.media.value
        val hasTrack = connected && media.available && media.hasTrack

        val status = when {
            !connected -> "Disconnected"
            battery != null -> "Connected · $battery%"
            else -> "Connected"
        }
        val column = LayoutElementBuilders.Column.Builder()
            .addContent(
                Text.Builder(this, status)
                    .setTypography(Typography.TYPOGRAPHY_TITLE3)
                    .setColor(argb(if (connected) GREEN else RED))
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())

        val track = LayoutElementBuilders.Column.Builder()
            .addContent(
                Text.Builder(this, if (hasTrack) media.displayTitle else "Nothing playing")
                    .setTypography(Typography.TYPOGRAPHY_BODY1)
                    .setColor(argb(0xFFE3E3E7.toInt()))
                    .setMaxLines(1)
                    .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
                    .build()
            )
        if (hasTrack && media.artist.isNotEmpty()) {
            track.addContent(
                Text.Builder(this, media.artist)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(argb(GREY))
                    .setMaxLines(1)
                    .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
                    .build()
            )
        }
        if (hasTrack && currentArt() != null) {
            // Cover beside the title and artist, like the watch's own media tile
            track.setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
                .setWidth(expand())
            // expand() needs a sized parent all the way up, or the row collapses
            column.setWidth(expand())
            column.addContent(
                LayoutElementBuilders.Row.Builder()
                    .setWidth(expand())
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(
                        LayoutElementBuilders.Image.Builder()
                            .setResourceId(ID_ART)
                            .setWidth(dp(ART_DP))
                            .setHeight(dp(ART_DP))
                            .setModifiers(
                                ModifiersBuilders.Modifiers.Builder()
                                    .setBackground(
                                        ModifiersBuilders.Background.Builder()
                                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(6f)).build())
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(8f)).build())
                    .addContent(track.build())
                    .build()
            )
        } else {
            column.addContent(track.build())
        }

        val chip = if (hasTrack) {
            CompactChip.Builder(
                this, if (media.isPlaying) "Pause" else "Play",
                ModifiersBuilders.Clickable.Builder()
                    .setId(ID_TOGGLE)
                    .setOnClick(ActionBuilders.LoadAction.Builder().build())
                    .build(),
                device
            ).build()
        } else {
            CompactChip.Builder(
                this, "Open",
                ModifiersBuilders.Clickable.Builder()
                    .setId("open")
                    .setOnClick(
                        ActionBuilders.LaunchAction.Builder()
                            .setAndroidActivity(
                                ActionBuilders.AndroidActivity.Builder()
                                    .setPackageName(packageName)
                                    .setClassName(MainActivity::class.java.name)
                                    .build()
                            ).build()
                    ).build(),
                device
            ).build()
        }

        return PrimaryLayout.Builder(device)
            .setResponsiveContentInsetEnabled(true)
            .setPrimaryLabelTextContent(
                Text.Builder(this, "iPhone")
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(argb(BLUE))
                    .build()
            )
            .setContent(column.build())
            .setPrimaryChipContent(chip)
            .build()
    }
}
