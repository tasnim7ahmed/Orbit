package com.wearos.ancsbridge.surfaces

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.wearos.ancsbridge.ancs.AncsService
import com.wearos.ancsbridge.model.ConnectionState
import com.wearos.ancsbridge.ui.MainActivity

/** Pushes fresh iPhone state to the watch face complications and the tile. */
object SurfaceUpdater {

    /** The battery complication (and the tile, which shows battery and link). */
    const val BATTERY = 1
    /** The Now Playing complication (and the tile). */
    const val MEDIA = 2
    const val ALL = BATTERY or MEDIA

    /** Battery, link and media change in bursts; one refresh covers a burst. */
    private const val COALESCE_MS = 500L

    private val handler = Handler(Looper.getMainLooper())
    private var pending = 0
    private var appContext: Context? = null

    private val flush = Runnable {
        val context = appContext ?: return@Runnable
        val what = pending
        pending = 0
        if (what and BATTERY != 0) requestComplication(context, IPhoneBatteryComplicationService::class.java)
        if (what and MEDIA != 0) requestComplication(context, NowPlayingComplicationService::class.java)
        TileService.getUpdater(context).requestUpdate(IPhoneTileService::class.java)
    }

    /** Refresh the surfaces that show [what] (BATTERY, MEDIA or ALL), coalesced. Main thread. */
    fun request(context: Context, what: Int = ALL) {
        appContext = context.applicationContext
        val idle = pending == 0
        pending = pending or what
        if (idle) handler.postDelayed(flush, COALESCE_MS)
    }

    private fun requestComplication(context: Context, cls: Class<*>) =
        ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, cls)).requestUpdateAll()

    val isConnected: Boolean
        get() = AncsService.sharedConnectionState.value is ConnectionState.Connected

    fun openApp(context: Context, media: Boolean = false): PendingIntent =
        PendingIntent.getActivity(
            context, if (media) 31 else 30,
            if (media) MainActivity.openMediaIntent(context) else MainActivity.launchIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
