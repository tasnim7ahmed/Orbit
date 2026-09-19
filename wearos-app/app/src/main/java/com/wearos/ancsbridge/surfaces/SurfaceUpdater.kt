package com.wearos.ancsbridge.surfaces

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.wearos.ancsbridge.ancs.AncsService
import com.wearos.ancsbridge.model.ConnectionState
import com.wearos.ancsbridge.ui.MainActivity

/** Pushes fresh iPhone state to the watch face complications and the tile. */
object SurfaceUpdater {

    fun requestAll(context: Context) {
        listOf(IPhoneBatteryComplicationService::class.java, NowPlayingComplicationService::class.java)
            .forEach { cls ->
                ComplicationDataSourceUpdateRequester
                    .create(context, ComponentName(context, cls))
                    .requestUpdateAll()
            }
        TileService.getUpdater(context).requestUpdate(IPhoneTileService::class.java)
    }

    val isConnected: Boolean
        get() = AncsService.sharedConnectionState.value is ConnectionState.Connected

    fun openApp(context: Context, media: Boolean = false): PendingIntent =
        PendingIntent.getActivity(
            context, if (media) 31 else 30,
            if (media) MainActivity.openMediaIntent(context) else MainActivity.launchIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
