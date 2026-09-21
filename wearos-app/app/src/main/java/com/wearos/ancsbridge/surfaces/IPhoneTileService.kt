package com.wearos.ancsbridge.surfaces

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
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
import com.wearos.ancsbridge.ancs.AncsService
import com.wearos.ancsbridge.ble.AmsProtocol
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
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(requestParams.deviceConfiguration)))
            .build()
        return CallbackToFutureAdapter.getFuture { it.set(tile); "tile" }
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> {
        val resources = ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
        return CallbackToFutureAdapter.getFuture { it.set(resources); "resources" }
    }

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
            .addContent(
                Text.Builder(this, if (hasTrack) media.title.ifEmpty { media.playerName } else "Nothing playing")
                    .setTypography(Typography.TYPOGRAPHY_BODY1)
                    .setColor(argb(0xFFE3E3E7.toInt()))
                    .setMaxLines(1)
                    .build()
            )
        if (hasTrack && media.artist.isNotEmpty()) {
            column.addContent(
                Text.Builder(this, media.artist)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(argb(GREY))
                    .setMaxLines(1)
                    .build()
            )
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
