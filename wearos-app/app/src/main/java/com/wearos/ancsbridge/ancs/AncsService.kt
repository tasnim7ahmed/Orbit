package com.wearos.ancsbridge.ancs

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.graphics.Bitmap
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.wearos.ancsbridge.AncsApplication
import com.wearos.ancsbridge.R
import com.wearos.ancsbridge.ble.AncsConstants
import com.wearos.ancsbridge.ble.BleConnectionManager
import com.wearos.ancsbridge.ble.BondStateReceiver
import com.wearos.ancsbridge.ble.PairingAdvertiser
import com.wearos.ancsbridge.model.AncsEvent
import com.wearos.ancsbridge.model.AncsNotification
import com.wearos.ancsbridge.model.ConnectionState
import com.wearos.ancsbridge.model.PhoneStatus
import com.wearos.ancsbridge.media.NowPlayingController
import com.wearos.ancsbridge.settings.AppSettings
import com.wearos.ancsbridge.surfaces.SurfaceUpdater
import com.wearos.ancsbridge.ui.IncomingCallActivity
import com.wearos.ancsbridge.ui.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that owns the iPhone link and everything built on it.
 *
 * Pipeline: pair (watch advertises, iPhone connects) or reconnect to the bonded iPhone →
 *           bond → subscribe → receive NS events → fetch attributes via CP →
 *           parse DS response → post Android notification.
 * Also: incoming calls (Ringer + Telecom), two-way dismiss with UID re-linking after
 * reconnects, per-app settings and stacks, missed-while-away recovery, left-behind
 * alert, Now Playing, and complication/tile refreshes.
 */
@SuppressLint("MissingPermission")
class AncsService : Service() {

    companion object {
        private const val TAG = "AncsService"
        private const val SERVICE_NOTIFICATION_ID = 1
        private const val NOTIFICATION_ID_BASE = 1000

        const val ACTION_START = "com.wearos.ancsbridge.START"
        const val ACTION_STOP = "com.wearos.ancsbridge.STOP"
        const val ACTION_DISCONNECT = "com.wearos.ancsbridge.DISCONNECT"
        const val ACTION_RECONNECT = "com.wearos.ancsbridge.RECONNECT"
        const val ACTION_PERFORM_NOTIFICATION_ACTION = "com.wearos.ancsbridge.PERFORM_ACTION"
        const val ACTION_CLEAR_ALL = "com.wearos.ancsbridge.CLEAR_ALL"
        const val ACTION_START_PAIRING = "com.wearos.ancsbridge.START_PAIRING"
        const val ACTION_STOP_PAIRING = "com.wearos.ancsbridge.STOP_PAIRING"
        const val ACTION_MEDIA_COMMAND = "com.wearos.ancsbridge.MEDIA_COMMAND"
        const val ACTION_NOTIFICATION_DISMISSED = "com.wearos.ancsbridge.NOTIFICATION_DISMISSED"
        const val ACTION_GROUP_DISMISSED = "com.wearos.ancsbridge.GROUP_DISMISSED"
        const val ACTION_SILENCE_RING = "com.wearos.ancsbridge.SILENCE_RING"
        /** Internal: the alarm that runs the next reconnect advertising burst. */
        const val ACTION_ADVERTISE_BURST = "com.wearos.ancsbridge.ADVERTISE_BURST"
        const val ACTION_DEBUG_INJECT = "com.wearos.ancsbridge.DEBUG_INJECT"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_BUNDLE_ID = "bundle_id"
        const val NOTIFICATION_ID_LEFT_BEHIND = 996
        private const val LEFT_BEHIND_DELAY_MS = 15_000L
        // Writing the link timestamp every 30 s woke the CPU ~2,900 times a day; 5 minutes
        // is accurate enough for the "missed while away" window.
        private const val LINK_HEARTBEAT_MS = 300_000L
        // Reconnect advertising while the iPhone is away. 180 s is the longest the
        // Bluetooth controller accepts for one advertising run.
        private const val ADVERTISE_FIRST_MS = 180_000L
        private const val ADVERTISE_BURST_MS = 30_000L
        private const val ADVERTISE_GAP_MS = 120_000L
        private const val ADVERTISE_SLOW_AFTER_MS = 1_800_000L
        private const val ADVERTISE_SLOW_GAP_MS = 900_000L
        private const val SCREEN_RETRY_COOLDOWN_MS = 120_000L
        private const val PREF_LAST_LINK_UP = "last_link_up_at"
        private const val PREF_NEXT_NOTIF_ID = "next_notification_id"
        // Keeps notifId + 1_000_000 (dismiss request codes) below the summary range (2_000_000+)
        private const val MAX_NOTIF_ID = 400_000
        private const val MAX_PENDING_CLEARS = 100
        private const val NOTIF_ID_BLOCK = 50
        /** ANCS Date attribute: local time, "yyyyMMdd'T'HHmmss". */
        private val ANCS_DATE: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", java.util.Locale.ROOT)
        // ASCII unit separator — cannot appear in notification text
        private val UNIT_SEPARATOR = 31.toChar().toString()
        const val EXTRA_MEDIA_COMMAND = "media_command"
        const val EXTRA_ACTION_ID = "action_id"
        const val NOTIFICATION_ID_CALL = 999
        private const val MAX_ACTIVE_NOTIFICATIONS = 20 // Android limit is 25; keep headroom
        // Long messages over a low-power connection interval need more than 3 s
        private const val ATTRIBUTE_RESPONSE_TIMEOUT_MS = 6_000L
        private const val APP_NAME_TIMEOUT_MS = 3_000L
        /** Orbit blue, the same accent the apps Material theme uses. */
        private const val ACCENT_COLOR = 0xFFA8C7FA.toInt()
        private const val PAIRING_WINDOW_MS = 180_000L
        /** Longest call age trusted from a re-announced call's date (clock skew guard). */
        private const val MAX_CALL_AGE_MS = 12 * 60 * 60_000L

        /** Shared connection state observable by the ViewModel */
        private val _sharedConnectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        val sharedConnectionState: StateFlow<ConnectionState> = _sharedConnectionState.asStateFlow()

        /** Pairing mode (watch discoverable in iPhone Settings → Bluetooth), observable by the UI */
        private val _pairingState = MutableStateFlow<PairingState>(PairingState.Off)
        val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()
    }

    sealed class PairingState {
        data object Off : PairingState()
        data class Advertising(val watchName: String?) : PairingState()
        data class Failed(val message: String) : PairingState()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var connectionManager: BleConnectionManager
    private lateinit var advertiser: PairingAdvertiser
    private var pairingTimeoutJob: Job? = null
    private lateinit var notificationManager: NotificationManager

    private val dataSourceAssembler = DataSourceAssembler()
    private val appNameCache = mutableMapOf<String, String>()

    /** Apps the iPhone would not name; asking again in this session only costs a timeout. */
    private val appNameMisses = mutableSetOf<String>()

    // Track active notification IDs in posting order (oldest first) for auto-cleanup
    private val activeNotificationIds = ArrayDeque<Int>()

    private var activeCallUid: Long = -1

    // Reconnection grace period — notifications in the first 10 seconds after subscribing
    // are treated as backlog. iOS delivers its unflagged backlog within milliseconds of
    // subscribing; a longer window would swallow genuinely new ones.
    private val reconnectionGracePeriodMs = 10_000L

    // Repeating vibration for incoming calls
    private lateinit var ringer: Ringer
    private lateinit var nowPlaying: NowPlayingController
    private lateinit var wrist: WristDetector

    /**
     * The call in progress on the iPhone (ANCS category 12), shown with a running timer
     * and as an ongoing activity on the watch face. [startedElapsed] is in the
     * SystemClock.elapsedRealtime() base, [startedWall] the same moment in wall-clock time.
     */
    private data class OngoingCall(val uid: Long, val notifId: Int, val startedElapsed: Long, val startedWall: Long)
    private var ongoingCall: OngoingCall? = null

    // ANCS session counter — bumped on each new subscription. Watch-side dismissals
    // only clear the iPhone notification if it was posted in the current session,
    // so a stale UID can never clear the wrong notification.
    private var sessionId = 0
    private var wasConnected = false

    /** What each posted watch notification maps to, for dismiss sync and per-app stacks */
    private data class Posted(
        val uid: Long,
        val session: Int,
        val bundleId: String,
        val hasNegativeAction: Boolean,
        val signature: String
    )
    private val posted = mutableMapOf<Int, Posted>()

    /** Current session's ANCS UID → watch notification ID (UIDs restart each session) */
    private val uidToNotifId = mutableMapOf<Long, Int>()

    /** Dismissed on the watch while the iPhone was unreachable — cleared there after reconnect */
    private val pendingIPhoneClears = linkedSetOf<String>()

    // UIDs whose attributes are being fetched as backlog (arrived while disconnected)
    // or as an update (MODIFIED) — decided at NS level, used when posting
    private val backlogUids = mutableSetOf<Long>()
    private val modifiedUids = mutableSetOf<Long>()

    /** Last time the iPhone link was known up before this session; 0 = never */
    private var missedWindowStart = 0L
    private var linkHeartbeatJob: Job? = null
    private var leftBehindJob: Job? = null
    /** When the iPhone link went down, for deciding how often to advertise. 0 = link is up. */
    private var awaySince = 0L
    private var lastScreenOnRetry = 0L

    // Serial queue for Control Point requests
    private val attributeRequestQueue = Channel<AttributeRequest>(Channel.BUFFERED)
    private var requestProcessorJob: Job? = null
    private var pendingAttributeResponse: CompletableDeferred<Unit>? = null

    /** The Data Source response the waiting request processor is about to consume. */
    private var dataSourceResult: DataSourceResult? = null
    private var serviceStatusText = "Starting..."

    private data class AttributeRequest(
        val event: AncsEvent,
        val requestedAttributes: List<Int>
    )

    override fun onCreate() {
        super.onCreate()
        connectionManager = BleConnectionManager(this)
        advertiser = PairingAdvertiser(this, ::onIncomingLink)
        notificationManager = getSystemService(NotificationManager::class.java)
        ringer = Ringer(this)
        AppSettings.init(this)
        nowPlaying = NowPlayingController(
            this, scope,
            isSessionStarting = { System.currentTimeMillis() - connectionManager.sessionStartedAt < 8_000L },
            sendCommand = { connectionManager.sendMediaCommand(it) }
        )
        nowPlaying.start()
        wrist = WristDetector(this) { offWrist ->
            // Taken off mid-ring: stop buzzing on the table (the call screen stays)
            if (offWrist && AppSettings.toggles.value.quietOffWrist && ringer.isRinging) {
                ringer.stop("taken off the wrist")
            }
        }
        wrist.start()

        // Register bond state receiver
        // All three are system broadcasts; no other app may send them to us
        ContextCompat.registerReceiver(
            this, connectionManager.bondStateReceiver, BondStateReceiver.intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, adapterStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Register PhoneAccount for Telecom framework integration
        // This lets us show incoming calls via the system call UI
        AncsConnectionService.registerPhoneAccount(this)

        startForeground(SERVICE_NOTIFICATION_ID, buildServiceNotification("Starting..."))

        // Listen for Notification Source events
        scope.launch {
            connectionManager.notificationSourceEvents.collect { data ->
                handleNotificationSourceEvent(data)
            }
        }

        // Listen for Data Source events
        scope.launch {
            connectionManager.dataSourceEvents.collect { data ->
                handleDataSourceEvent(data)
            }
        }

        // Show iPhone battery in the ongoing notification, complication and tile
        scope.launch {
            PhoneStatus.battery.collect {
                refreshServiceNotification()
                SurfaceUpdater.request(this@AncsService, SurfaceUpdater.BATTERY)
            }
        }

        // Update service notification and shared state based on connection state
        scope.launch {
            connectionManager.connectionState.collectLatest { state ->
                // Publish to shared flow so ViewModel can observe
                _sharedConnectionState.value = state

                val text = when (state) {
                    is ConnectionState.Idle -> "Idle"
                    is ConnectionState.Scanning -> "Scanning..."
                    is ConnectionState.Connecting -> "Connecting to ${state.deviceName ?: "iPhone"}..."
                    is ConnectionState.Bonding -> "Pairing with ${state.deviceName ?: "iPhone"}..."
                    is ConnectionState.Connected -> "Connected to ${state.deviceName ?: "iPhone"}"
                    is ConnectionState.Disconnected -> "Disconnected"
                    is ConnectionState.Error -> "Error: ${state.message}"
                }
                serviceStatusText = text
                refreshServiceNotification()

                when (state) {
                    is ConnectionState.Connected -> {
                        stopPairing()
                        stopReconnectAdvertising()
                        onLinkUp()
                    }
                    // Unexpected drop: advertise (low power) so the bonded iPhone can
                    // reconnect to us, in parallel with our own autoConnect attempts.
                    is ConnectionState.Disconnected -> {
                        if (_pairingState.value == PairingState.Off && connectionManager.getBondedIPhone() != null) {
                            startReconnectAdvertising()
                        }
                        onLinkDown(unexpected = true)
                    }
                    is ConnectionState.Idle, is ConnectionState.Error -> onLinkDown(unexpected = false)
                    else -> {}
                }
                SurfaceUpdater.request(this@AncsService)
            }
        }

        // Start the attribute request processor
        startRequestProcessor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() must be answered with startForeground(), and
        // notification actions, the tile and the advertising alarm all start us that way.
        // Calling it again on an already-foreground service only refreshes the notification.
        startForeground(SERVICE_NOTIFICATION_ID, buildServiceNotification(serviceStatusText))

        when (intent?.action) {
            ACTION_START, null -> {
                // Try to reconnect to bonded iPhone
                tryReconnectBonded()
            }
            ACTION_DISCONNECT -> {
                stopPairing()
                stopReconnectAdvertising()
                connectionManager.disconnect()
            }
            ACTION_MEDIA_COMMAND -> {
                val command = intent.getIntExtra(EXTRA_MEDIA_COMMAND, -1)
                if (command >= 0) nowPlaying.onWatchCommand()
                if (command >= 0 && !connectionManager.sendMediaCommand(command)) {
                    Log.w(TAG, "Media command $command dropped — Apple Media Service not connected")
                }
            }
            ACTION_START_PAIRING -> {
                startPairing()
            }
            ACTION_STOP_PAIRING -> {
                stopPairing()
            }
            ACTION_RECONNECT -> {
                tryReconnectBonded()
            }
            ACTION_PERFORM_NOTIFICATION_ACTION -> {
                val uid = intent.getLongExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_UID, -1)
                val actionId = intent.getIntExtra(EXTRA_ACTION_ID, -1)
                if (uid != -1L && actionId != -1) {
                    performNotificationAction(uid, actionId)
                }
            }
            ACTION_CLEAR_ALL -> {
                Log.i(TAG, "Clearing all notifications (${posted.size} tracked) on watch and iPhone")
                posted.values.forEach { clearOnIPhone(it) }
                val bundles = posted.values.map { it.bundleId }.toSet()
                activeNotificationIds.forEach { id -> notificationManager.cancel(id) }
                activeNotificationIds.clear()
                posted.clear()
                uidToNotifId.clear()
                bundles.forEach { notificationManager.cancel(summaryIdFor(it)) }
                notificationManager.cancel(NOTIFICATION_ID_CALL)
            }
            ACTION_NOTIFICATION_DISMISSED -> {
                val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
                if (notifId != -1) onWatchDismissed(notifId)
            }
            ACTION_GROUP_DISMISSED -> {
                val bundleId = intent.getStringExtra(EXTRA_BUNDLE_ID) ?: return START_STICKY
                val ids = posted.filterValues { it.bundleId == bundleId }.keys.toList()
                Log.i(TAG, "App stack dismissed on watch: $bundleId (${ids.size})")
                // The whole stack is gone, summary included: no need to re-count it per child
                ids.forEach { onWatchDismissed(it, refreshSummary = false) }
                notificationManager.cancel(summaryIdFor(bundleId))
            }
            ACTION_SILENCE_RING -> ringer.stop("silenced on watch")
            ACTION_ADVERTISE_BURST -> onAdvertiseBurstAlarm()
            ACTION_DEBUG_INJECT -> DebugInjector.handle(
                intent, this::debugPost, this::debugCall, this::debugRemove,
                // Like a real swipe, which knows the watch notification ID even after a reconnect
                dismiss = { uid ->
                    (notifIdForUid(uid) ?: posted.entries.firstOrNull { it.value.uid == uid }?.key)
                        ?.let { onWatchDismissed(it) }
                },
                wrist = { off -> wrist.simulate(off) }
            )
            ACTION_STOP -> {
                stopPairing()
                stopReconnectAdvertising()
                connectionManager.disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(connectionManager.bondStateReceiver)
        } catch (_: IllegalArgumentException) { }
        try {
            unregisterReceiver(adapterStateReceiver)
        } catch (_: IllegalArgumentException) { }
        try {
            unregisterReceiver(screenOnReceiver)
        } catch (_: IllegalArgumentException) { }
        stopReconnectAdvertising()
        advertiser.destroy()
        ringer.stop("service destroyed")
        nowPlaying.stop()
        wrist.stop()
        _pairingState.value = PairingState.Off
        connectionManager.destroy()
        attributeRequestQueue.close()
        scope.cancel()
    }

    /**
     * Make the watch discoverable so the iPhone can pair from Settings → Bluetooth.
     * No iOS app needed: the ANCS solicitation in the advertisement is what makes
     * iOS list the watch and offer to share notifications with it.
     */
    private fun startPairing() {
        if (!advertiser.isSupported) {
            _pairingState.value = PairingState.Failed("This watch can't advertise over BLE")
            return
        }
        // Pairing takes over from any reconnect advertising in progress
        stopReconnectAdvertising()
        advertiser.start(fast = true)
        _pairingState.value = PairingState.Advertising(advertiser.advertisedName)
        Log.i(TAG, "Pairing mode on — watch visible as '${advertiser.advertisedName}'")

        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = scope.launch {
            kotlinx.coroutines.delay(PAIRING_WINDOW_MS)
            Log.i(TAG, "Pairing window expired")
            stopPairing()
        }
    }

    private fun stopPairing() {
        pairingTimeoutJob?.cancel()
        pairingTimeoutJob = null
        if (_pairingState.value is PairingState.Advertising) {
            advertiser.stop()
        }
        _pairingState.value = PairingState.Off
    }

    /**
     * A central (hopefully the iPhone) connected to our advertisement. Attach a GATT
     * client to the same link and run the ANCS flow. Outside the pairing window only
     * the remembered iPhone is accepted, so strangers can't trigger bonding attempts.
     */
    private fun onIncomingLink(device: BluetoothDevice) {
        // GATT server callbacks arrive on a binder thread; connection state lives on main.
        scope.launch { handleIncomingLink(device) }
    }

    private fun handleIncomingLink(device: BluetoothDevice) {
        val pairing = _pairingState.value is PairingState.Advertising
        val knownIPhone = connectionManager.getBondedIPhone()?.address == device.address
        if (!pairing && !knownIPhone) {
            Log.d(TAG, "Ignoring link from ${device.address} (not pairing, not our iPhone)")
            return
        }
        Log.i(TAG, "Incoming link (pairing=$pairing), attaching GATT client")
        Log.d(TAG, "Incoming link from ${device.address}")
        connectionManager.connect(device)
    }

    /**
     * Advertise so the bonded iPhone can find us again, without burning the radio all day
     * when it simply isn't around.
     *
     * The first run after a drop is long (3 minutes), which covers the usual case of
     * walking out of range and coming back. After that it advertises in short bursts,
     * rarer the longer the iPhone stays away. A screen wake starts a long run again, so
     * looking at the watch after coming home reconnects quickly.
     */
    private fun startReconnectAdvertising() {
        if (awaySince == 0L) awaySince = SystemClock.elapsedRealtime()
        advertiseBurst(ADVERTISE_FIRST_MS)
    }

    /**
     * One advertising burst. The length is set on the advertiser itself, so the Bluetooth
     * controller ends it on time even if the watch's CPU sleeps through it. A coroutine
     * timer can't do this: its delay freezes while the CPU is suspended, which is most of
     * the time on a sleeping watch.
     */
    private fun advertiseBurst(lengthMs: Long) {
        advertiser.start(fast = false, timeoutMs = lengthMs.toInt())
        val away = SystemClock.elapsedRealtime() - awaySince
        val next = if (away > ADVERTISE_SLOW_AFTER_MS) ADVERTISE_SLOW_GAP_MS else ADVERTISE_GAP_MS
        scheduleNextBurst(next)
    }

    /**
     * Inexact wake-up alarm for the next burst. Exact alarms aren't permitted here, and
     * inexact ones are the cheaper choice anyway: the system fires them together with
     * other work it already had to wake up for.
     */
    private fun scheduleNextBurst(delayMs: Long) {
        val alarmManager = getSystemService(AlarmManager::class.java)
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + delayMs,
            advertiseBurstIntent()
        )
    }

    // getForegroundService, not getService: if the process was killed, a plain service
    // start from an alarm is refused in the background, and this service is a foreground
    // service in every start path.
    private fun advertiseBurstIntent(): PendingIntent = PendingIntent.getForegroundService(
        this, 40,
        Intent(this, AncsService::class.java).setAction(ACTION_ADVERTISE_BURST),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Alarm fired: advertise again, unless the iPhone is back or pairing took over. */
    private fun onAdvertiseBurstAlarm() {
        if (connectionManager.connectionState.value is ConnectionState.Connected ||
            _pairingState.value != PairingState.Off ||
            connectionManager.getBondedIPhone() == null
        ) {
            stopReconnectAdvertising()
            return
        }
        advertiseBurst(ADVERTISE_BURST_MS)
    }

    private fun stopReconnectAdvertising() {
        awaySince = 0L
        getSystemService(AlarmManager::class.java).cancel(advertiseBurstIntent())
        advertiser.stop()
    }

    /** Wrist raise or a button press while the iPhone is away: try harder for a moment. */
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (connectionManager.connectionState.value is ConnectionState.Connected) return
            if (_pairingState.value != PairingState.Off) return
            if (connectionManager.getBondedIPhone() == null) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastScreenOnRetry < SCREEN_RETRY_COOLDOWN_MS) return
            lastScreenOnRetry = now
            // You are looking at the watch, so this is when a reconnect matters.
            // Via startReconnectAdvertising so the away timer is set if it is not yet.
            startReconnectAdvertising()
        }
    }

    /** Watch Bluetooth toggled (airplane mode, settings, …): GATT gives no callback for this. */
    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    stopReconnectAdvertising()
                    connectionManager.onAdapterOff()
                }
                BluetoothAdapter.STATE_ON -> {
                    Log.i(TAG, "Bluetooth back on — reconnecting to iPhone")
                    // tryReconnectBonded also starts advertising in parallel
                    tryReconnectBonded()
                }
            }
        }
    }

    private fun tryReconnectBonded() {
        val device = connectionManager.getBondedIPhone()
        if (device != null) {
            Log.i(TAG, "Found bonded iPhone")
            Log.d(TAG, "Bonded device: ${device.name ?: device.address}")
            connectionManager.reconnect(device)
            // Our own connection attempt sits pending with no callback while the iPhone is
            // out of range, so advertise as well and let the iPhone come to us. Stops as
            // soon as either side succeeds.
            if (_pairingState.value == PairingState.Off) startReconnectAdvertising()
        } else {
            Log.i(TAG, "No bonded iPhone found")
        }
    }

    private fun handleNotificationSourceEvent(data: ByteArray) {
        val event = NotificationSourceParser.parse(data) ?: run {
            Log.w(TAG, "Failed to parse NS event")
            return
        }

        Log.d(TAG, "NS event: id=${event.eventId} cat=${event.categoryId} uid=${event.notificationUid}")

        when {
            event.isAdded || event.isModified -> {
                val uid = event.notificationUid
                ensureSessionStarted()

                // Backlog = already on the iPhone when we subscribed: flagged pre-existing,
                // or the unflagged burst iOS sends in the first seconds. Normally skipped
                // (no attribute fetch). With "show missed", fetch it so postNotification
                // can quietly show what arrived while the watch was away.
                val inGrace = (System.currentTimeMillis() - connectionManager.sessionStartedAt) < reconnectionGracePeriodMs
                val isBacklog = event.isPreExisting || (inGrace && !event.isIncomingCall)
                if (isBacklog) {
                    // Fetch backlog to recover missed ones, and to re-link notifications the
                    // watch still shows (or cleared while away) to their new UIDs
                    val recoverMissed = AppSettings.toggles.value.showMissedWhileAway && missedWindowStart > 0
                    val relink = posted.isNotEmpty() || pendingIPhoneClears.isNotEmpty()
                    if (event.isIncomingCall || !(recoverMissed || relink)) {
                        Log.d(TAG, "Skipping backlog NS event uid=$uid")
                        return
                    }
                    backlogUids.add(uid)
                }

                // MODIFIED on active call = call was answered on iPhone.
                // Transition the call overlay to "Active on iPhone" state.
                if (event.isModified && event.isIncomingCall && activeCallUid == event.notificationUid) {
                    Log.i(TAG, "Call MODIFIED (answered on iPhone) uid=${event.notificationUid}")
                    ringer.stop("answered on iPhone")
                    // Cancel the CallStyle notification — no longer ringing
                    notificationManager.cancel(NOTIFICATION_ID_CALL)
                    // Tell the activity to transition to "active call" UI
                    sendBroadcast(Intent(IncomingCallActivity.ACTION_CALL_ANSWERED).apply {
                        setPackage(packageName)
                    })
                    return
                }

                if (event.isIncomingCall && event.isAdded) {
                    // IMMEDIATELY show call screen — don't wait for attribute fetch.
                    // (Only for ADDED: a MODIFIED call is one already answered/handled.)
                    // The NS payload already tells us it's a call. We'll update
                    // the caller name later if attributes arrive in time.
                    Log.i(TAG, "Incoming call NS event (cat=1) uid=${event.notificationUid} — showing call screen NOW")
                    showIncomingCall(AncsNotification(
                        uid = event.notificationUid,
                        categoryId = event.categoryId,
                        eventFlags = event.eventFlags,
                        appIdentifier = "com.apple.mobilephone",
                        title = "Incoming Call",
                        subtitle = null,
                        message = "",
                        date = null,
                        appDisplayName = "Phone",
                        positiveActionLabel = "Answer",
                        negativeActionLabel = "Decline"
                    ))
                }

                // Build list of attributes to request
                val attrs = mutableListOf(
                    AncsConstants.ATTR_APP_IDENTIFIER,
                    AncsConstants.ATTR_TITLE,
                    AncsConstants.ATTR_SUBTITLE,
                    AncsConstants.ATTR_MESSAGE,
                    AncsConstants.ATTR_DATE
                )
                if (event.hasPositiveAction) attrs.add(AncsConstants.ATTR_POSITIVE_ACTION_LABEL)
                if (event.hasNegativeAction) attrs.add(AncsConstants.ATTR_NEGATIVE_ACTION_LABEL)

                // MODIFIED = content update of something already shown: update quietly
                if (event.isModified) modifiedUids.add(uid)

                // Enqueue attribute fetch (for all notifications including calls)
                scope.launch {
                    attributeRequestQueue.send(AttributeRequest(event, attrs))
                }
            }
            event.isRemoved -> {
                // Cleared/handled on the iPhone → remove from the watch (two-way sync)
                notifIdForUid(event.notificationUid)?.let { removeWatchNotification(it) }
                // By tracking too: "Clear Notifications" forgets UIDs, and a call must not keep ticking
                ongoingCall?.takeIf { it.uid == event.notificationUid }?.let {
                    notificationManager.cancel(it.notifId)
                    ongoingCall = null
                    // Close the in-call screen if it is open (not another call's ringing screen)
                    sendBroadcast(
                        Intent(IncomingCallActivity.ACTION_CALL_ENDED).setPackage(packageName)
                            .putExtra(IncomingCallActivity.EXTRA_NOTIFICATION_UID, it.uid)
                    )
                }
                uidToNotifId.remove(event.notificationUid)
                backlogUids.remove(event.notificationUid)
                modifiedUids.remove(event.notificationUid)

                // If this was an incoming call, dismiss everything
                if (event.isIncomingCall || activeCallUid == event.notificationUid) {
                    Log.i(TAG, "Call ended (REMOVE) uid=${event.notificationUid}")
                    activeCallUid = -1
                    ringer.stop("call removed")
                    notificationManager.cancel(NOTIFICATION_ID_CALL)
                    AncsConnectionService.endActiveCall()
                    sendBroadcast(Intent(IncomingCallActivity.ACTION_CALL_ENDED).apply {
                        setPackage(packageName)
                        // Only this call's screen: a call in progress keeps its in-call screen
                        putExtra(IncomingCallActivity.EXTRA_NOTIFICATION_UID, event.notificationUid)
                    })
                }
            }
        }
    }

    private fun handleDataSourceEvent(data: ByteArray) {
        val result = dataSourceAssembler.onDataReceived(data) ?: return
        val waiting = pendingAttributeResponse
        if (waiting != null) {
            // The request processor is waiting for exactly this, and posts it
            dataSourceResult = result
            waiting.complete(Unit)
            return
        }
        // Arrived after we stopped waiting: still worth showing
        if (result is DataSourceResult.Notification) {
            Log.w(TAG, "Late Data Source response for uid=${result.notification.uid}")
            scope.launch { postNotification(result.notification) }
        }
    }

    private fun startRequestProcessor() {
        requestProcessorJob = scope.launch {
            for (request in attributeRequestQueue) {
                processAttributeRequest(request)
            }
        }
    }

    private suspend fun processAttributeRequest(request: AttributeRequest) {
        val event = request.event

        // Prepare the assembler to expect this response
        dataSourceAssembler.expectNotificationAttributes(
            uid = event.notificationUid,
            requestedAttributes = request.requestedAttributes,
            category = event.categoryId,
            flags = event.eventFlags
        )

        val result = awaitDataSourceResponse(
            ControlPointWriter.buildGetNotificationAttributes(
                uid = event.notificationUid,
                hasPositiveAction = event.hasPositiveAction,
                hasNegativeAction = event.hasNegativeAction
            ),
            ATTRIBUTE_RESPONSE_TIMEOUT_MS
        )

        val notification = (result as? DataSourceResult.Notification)?.notification
            ?: dataSourceAssembler.flushPartial()?.also {
                Log.w(TAG, "Incomplete Data Source response for uid=${event.notificationUid}, posting partial")
            }
        if (notification == null) {
            Log.w(TAG, "No Data Source response for uid=${event.notificationUid}")
            return
        }

        Log.i(TAG, "Notification complete: app=${notification.appIdentifier} uid=${notification.uid}")
        Log.d(TAG, "Content: ${notification.title} - ${notification.message}")
        // Ask the iPhone what this app is really called, once per app, before showing it
        resolveAppNameFromIPhone(notification.appIdentifier)
        postNotification(notification)
    }

    /**
     * Write a Control Point command and wait for its Data Source response.
     *
     * Commands are sent one at a time: starting the next one resets the assembler and
     * would drop a response still in flight. The timeout covers ANCS errors (such as a
     * notification the iPhone has already removed), which produce no response at all.
     */
    private suspend fun awaitDataSourceResponse(command: ByteArray, timeoutMs: Long): DataSourceResult? {
        dataSourceResult = null
        val response = CompletableDeferred<Unit>()
        pendingAttributeResponse = response
        if (!connectionManager.writeControlPoint(command)) {
            Log.e(TAG, "Control Point write failed")
            dataSourceAssembler.reset()
            pendingAttributeResponse = null
            return null
        }
        withTimeoutOrNull(timeoutMs) { response.await() }
        pendingAttributeResponse = null
        return dataSourceResult.also { dataSourceResult = null }
    }

    /**
     * Fetch an app's real display name from the iPhone, the name iOS itself shows, so
     * notifications read "Snapchat" rather than the tail of its bundle ID ("picaboo").
     * Asked once per app; the answer is kept across restarts.
     */
    private suspend fun resolveAppNameFromIPhone(appId: String) {
        if (appId.isEmpty() || appId in appNameCache || appId in appNameMisses) return
        if (appId in knownAppNames) return // Apple's own apps are already named properly
        if (appNamePrefs().contains(appId)) return

        dataSourceAssembler.expectAppAttributes(appId, listOf(AncsConstants.APP_ATTR_DISPLAY_NAME))
        val result = awaitDataSourceResponse(
            ControlPointWriter.buildGetAppAttributes(appId), APP_NAME_TIMEOUT_MS
        ) as? DataSourceResult.AppName

        val name = result?.displayName?.takeIf { it.isNotEmpty() && result.appIdentifier == appId }
        if (name == null) {
            // iOS answers nothing for apps it no longer knows — don't ask again this session
            Log.d(TAG, "No app name from the iPhone for $appId")
            appNameMisses.add(appId)
            dataSourceAssembler.reset()
            return
        }
        appNameCache[appId] = name
        appNamePrefs().edit { putString(appId, name) }
        Log.i(TAG, "Resolved the display name for $appId from the iPhone")
    }

    private suspend fun postNotification(notification: AncsNotification) {
        val isPreExisting = notification.eventFlags and AncsConstants.EVENT_FLAG_PRE_EXISTING != 0
        val isBacklog = backlogUids.remove(notification.uid)
        val isUpdate = modifiedUids.remove(notification.uid)
        val appId = notification.appIdentifier

        Log.d(TAG, "postNotification: uid=${notification.uid} cat=${notification.categoryId} " +
            "flags=0x${notification.eventFlags.toString(16)} app=$appId " +
            "title=${notification.title} preExisting=$isPreExisting backlog=$isBacklog update=$isUpdate")

        // Resolve app display name if not cached
        val appName = resolveAppName(appId)
        val updatedNotification = notification.copy(appDisplayName = appName)

        // Detect incoming calls by category OR by message content
        // WhatsApp sends calls as cat=4 (Social) with "☎" or "Incoming" in message
        // Regular phone may send as cat=1 (IncomingCall) or cat=2 with "Incoming" content
        val callApps = setOf(
            "net.whatsapp.WhatsApp", "net.whatsapp.WhatsAppSMB",
            "com.apple.mobilephone", "com.apple.facetime"
        )
        val messageAndTitle = "${notification.message} ${notification.title}"
        val hasStrongCallIndicator = messageAndTitle.contains("Incoming Call", ignoreCase = true) ||
            messageAndTitle.contains("Incoming Voice", ignoreCase = true) ||
            messageAndTitle.contains("Incoming Video", ignoreCase = true) ||
            messageAndTitle.contains("Incoming Audio", ignoreCase = true) ||
            messageAndTitle.contains("☎") ||
            messageAndTitle.contains("is calling", ignoreCase = true)
        // Generic phrases only count as a call when they are the whole message — a chat
        // like "let's do a video call later" must not ring the watch for 45 s
        val weakPhrases = listOf("Audio Call", "Video Call", "Voice Call", "Ringing")
        val hasWeakCallIndicator = notification.message.length <= 30 &&
            weakPhrases.any { notification.message.contains(it, ignoreCase = true) }
        // Text heuristics only apply to apps that actually place calls (WhatsApp,
        // FaceTime, Phone) — not to e.g. an email that mentions a video call
        val isIncomingCall = notification.categoryId == AncsConstants.CATEGORY_INCOMING_CALL ||
            (appId in callApps && (hasStrongCallIndicator || hasWeakCallIndicator))

        if (isIncomingCall) {
            // A call from the backlog is already over or being handled, and an updated
            // (MODIFIED) call was answered or handled — never (re)start ringing for those
            if (isBacklog || (isUpdate && activeCallUid != notification.uid)) return
            Log.i(TAG, "Showing incoming call screen")
            showIncomingCall(updatedNotification)
            return
        }

        if (notification.categoryId == AncsConstants.CATEGORY_ACTIVE_CALL) {
            showActiveCall(updatedNotification, reannounced = isBacklog)
            return
        }

        // Pre-existing notifications are only shown when recovered as backlog
        if (isPreExisting && !isBacklog) {
            Log.d(TAG, "Skipping pre-existing notification uid=${notification.uid}")
            return
        }

        val notifTime = parseAncsDate(notification.date)
        val signature = signatureOf(notification)
        val hasNegativeAction = notification.eventFlags and AncsConstants.EVENT_FLAG_NEGATIVE_ACTION != 0
        var quiet = false
        if (isBacklog) {
            // Cleared on the watch while the iPhone was unreachable → clear it there now
            if (pendingIPhoneClears.remove(signature)) {
                Log.i(TAG, "Clearing on iPhone what was dismissed on watch while away: uid=${notification.uid}")
                if (hasNegativeAction) sendNegativeAction(notification.uid)
                return
            }
            // Still showing on the watch from an earlier session: iOS re-announced it under
            // a new UID — re-link it so clearing works in both directions again
            posted.entries.firstOrNull { it.value.signature == signature }?.let { (id, p) ->
                posted[id] = p.copy(uid = notification.uid, session = sessionId, hasNegativeAction = hasNegativeAction)
                uidToNotifId[notification.uid] = id
                Log.d(TAG, "Re-linked watch notification $id to uid=${notification.uid}")
                return
            }
            // Otherwise show it only if it arrived after the link was last up and the user
            // wants missed ones — anything older was already shown (and maybe dismissed)
            val recoverMissed = AppSettings.toggles.value.showMissedWhileAway && missedWindowStart > 0
            if (!recoverMissed || notification.date == null || notifTime <= missedWindowStart) {
                Log.d(TAG, "Backlog uid=${notification.uid} already seen, skipping")
                return
            }
            // Arrived while we were away → quietly; arrived just now → alert normally
            quiet = notifTime < connectionManager.sessionStartedAt - 5_000L
            Log.i(TAG, "Recovered missed notification uid=${notification.uid} quiet=$quiet")
        }

        // Per-app watch settings (Apple Watch app → Notifications)
        AppSettings.recordSeen(appId, appName ?: appId)
        val appSettings = AppSettings.entryFor(appId)
        if (appSettings?.mode == AppSettings.AlertMode.OFF) {
            Log.d(TAG, "Notifications from $appId are off on the watch")
            return
        }
        // Mirror iPhone quiet delivery (Focus, Deliver Quietly) via the ANCS Silent flag
        val silentOnIPhone = notification.eventFlags and AncsConstants.EVENT_FLAG_SILENT != 0
        if (silentOnIPhone || appSettings?.mode == AppSettings.AlertMode.QUIET) quiet = true
        // "Mute for 1 hour" from a notification, and a watch that is not being worn
        val muted = appSettings?.isMuted() == true
        if (muted || isQuietOffWrist()) quiet = true

        // Route Clock/Reminders/Calendar to the schedule channel regardless of ANCS category
        val scheduleApps = setOf("com.apple.mobiletimer", "com.apple.reminders", "com.apple.mobilecal")
        val channelId = when {
            quiet -> AncsApplication.CHANNEL_QUIET
            appSettings?.haptic == AppSettings.Haptic.TAP -> AncsApplication.CHANNEL_HAPTIC_TAP
            appSettings?.haptic == AppSettings.Haptic.DOUBLE -> AncsApplication.CHANNEL_HAPTIC_DOUBLE
            appSettings?.haptic == AppSettings.Haptic.LONG -> AncsApplication.CHANNEL_HAPTIC_LONG
            appId in scheduleApps -> AncsApplication.CHANNEL_SCHEDULE
            else -> AncsApplication.channelForCategory(notification.categoryId)
        }
        val iconResId = AppIconMapper.getIconResId(appId, notification.categoryId)
        // Updates (MODIFIED) replace the watch notification they belong to
        val notifId = uidToNotifId[notification.uid]?.takeIf { isUpdate } ?: allocateNotifId()
        uidToNotifId[notification.uid] = notifId

        // Layout: contentTitle = sender, contentText = message preview, subText = app name
        val senderName = notification.title.ifEmpty { notification.appDisplayName ?: appId }
        val messagePreview = notification.message.ifEmpty { notification.subtitle ?: "" }

        // Full-color app icon: App Store artwork if cached, else iOS-style generated icon
        val cachedIcon = AppIconRepository.cached(this, appId)
        val icon = cachedIcon ?: AppIconRepository.generated(this, appId, notification.categoryId)

        val spec = PostSpec(
            notification = notification,
            notifId = notifId,
            channelId = channelId,
            iconResId = iconResId,
            senderName = senderName,
            messagePreview = messagePreview,
            notifTime = notifTime,
            appName = appName,
            // Updates (MODIFIED) refresh the content without buzzing again
            alertOnce = isUpdate,
            stackByApp = AppSettings.toggles.value.stackByApp,
            offerMute = !muted
        )

        if (notifId !in posted) evictOldestIfNeeded()
        notificationManager.notify(notifId, buildAppNotification(spec, icon))
        if (notifId !in activeNotificationIds) activeNotificationIds.addLast(notifId)
        posted[notifId] = Posted(
            uid = notification.uid,
            session = sessionId,
            bundleId = appId,
            hasNegativeAction = hasNegativeAction,
            signature = signature
        )
        if (spec.stackByApp) updateAppSummary(appId, appName, icon, iconResId)

        // First notification from this app: fetch its real icon, then swap it in
        // silently (setOnlyAlertOnce) if the notification is still showing
        if (cachedIcon == null && AppIconRepository.shouldFetch(this, appId)) {
            scope.launch {
                val fetched = AppIconRepository.fetch(this@AncsService, appId) ?: return@launch
                if (notifId in posted) {
                    notificationManager.notify(notifId, buildAppNotification(spec.copy(alertOnce = true), fetched))
                    if (spec.stackByApp) updateAppSummary(appId, appName, fetched, iconResId)
                }
            }
        }
    }

    private data class PostSpec(
        val notification: AncsNotification,
        val notifId: Int,
        val channelId: String,
        val iconResId: Int,
        val senderName: String,
        val messagePreview: String,
        val notifTime: Long,
        val appName: String?,
        val alertOnce: Boolean,
        val stackByApp: Boolean,
        /** Show "Mute 1 hr" (not when the app is already muted) */
        val offerMute: Boolean
    )

    private fun buildAppNotification(spec: PostSpec, appIcon: Bitmap): Notification {
        val notification = spec.notification
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, spec.channelId)
            .setSmallIcon(spec.iconResId)
            .setLargeIcon(appIcon)
            .setOnlyAlertOnce(spec.alertOnce)
            .setContentTitle(spec.senderName)
            .setContentText(spec.messagePreview)
            .setSubText(spec.appName ?: notification.appIdentifier)
            .setTicker("${spec.senderName}: ${spec.messagePreview}")
            .setWhen(spec.notifTime)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            // Swiped away on the watch → cleared on the iPhone too
            .setDeleteIntent(dismissPendingIntent(spec.notifId))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(androidCategory(notification.categoryId))
            .setColor(ACCENT_COLOR)
            .setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)

        if (spec.stackByApp) {
            // One stack per iPhone app, like Apple Watch. We post the summary ourselves
            // and let the children alert (GROUP_ALERT_CHILDREN); Android's auto-grouping
            // (which adds SILENT to children) only touches ungrouped notifications.
            builder.setGroup(appGroupKey(notification.appIdentifier))
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
        } else {
            // Unique group per notification prevents Android's auto-grouping, which adds
            // SILENT via GROUP_ALERT_SUMMARY and kills heads-up and vibration.
            builder.setGroup("ancs_${notification.uid}")
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_ALL)
        }

        // MessagingStyle — Samsung One UI Watch renders this with message body
        // in the heads-up overlay, matching native Android-paired notification style.
        // BigTextStyle only shows body in expanded view, not the overlay.
        if (spec.messagePreview.isNotEmpty()) {
            // App icon as the sender avatar — Wear shows it beside the message
            val person = androidx.core.app.Person.Builder()
                .setName(spec.senderName)
                .setIcon(androidx.core.graphics.drawable.IconCompat.createWithBitmap(appIcon))
                .build()
            builder.setStyle(
                NotificationCompat.MessagingStyle(person)
                    .addMessage(spec.messagePreview, spec.notifTime, person)
            )
        } else if (notification.message.isNotEmpty()) {
            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(notification.message)
                    .setBigContentTitle(spec.senderName)
                    .setSummaryText(spec.appName)
            )
        }

        // Add action buttons if available
        if (notification.positiveActionLabel != null) {
            val positiveIntent = actionPendingIntent(
                NotificationActionReceiver.ACTION_POSITIVE, notification.uid, requestCode = spec.notifId
            )
            builder.addAction(R.drawable.ic_check, notification.positiveActionLabel, positiveIntent)
        }

        // iOS sometimes omits the negative label even when the flag says the action exists
        val hasNegativeAction = notification.eventFlags and AncsConstants.EVENT_FLAG_NEGATIVE_ACTION != 0
        val negativeLabel = notification.negativeActionLabel ?: if (hasNegativeAction) "Dismiss" else null
        if (negativeLabel != null) {
            val negativeIntent = actionPendingIntent(
                NotificationActionReceiver.ACTION_NEGATIVE, notification.uid, requestCode = spec.notifId + 500_000
            )
            builder.addAction(R.drawable.ic_close, negativeLabel, negativeIntent)
        }

        // Watch-only, like Apple Watch's "Mute for 1 hour": later ones from this app arrive quietly
        if (spec.offerMute && notification.appIdentifier.isNotEmpty()) {
            builder.addAction(R.drawable.ic_mute, "Mute 1 hr", mutePendingIntent(spec))
        }

        // Never call setNotificationSilent() — it suppresses the heads-up overlay
        // on Wear OS. Quiet delivery uses the IMPORTANCE_LOW quiet channel instead.
        return builder.build()
    }

    private fun mutePendingIntent(spec: PostSpec): PendingIntent =
        PendingIntent.getBroadcast(
            this, spec.notifId + 3_500_000,
            Intent(this, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_MUTE_APP)
                .putExtra(NotificationActionReceiver.EXTRA_BUNDLE_ID, spec.notification.appIdentifier)
                .putExtra(NotificationActionReceiver.EXTRA_APP_NAME, spec.appName ?: spec.notification.appIdentifier),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** The watch is off the wrist and the user wants it silent then. */
    private fun isQuietOffWrist() = wrist.isOffWrist && AppSettings.toggles.value.quietOffWrist

    private fun appGroupKey(bundleId: String) = "app_$bundleId"

    /** Stable ID for an app's stack summary; outside the per-UID range (1000..501000). */
    private fun summaryIdFor(bundleId: String) = 2_000_000 + (bundleId.hashCode() and 0xFFFFF)

    /**
     * Post/refresh or remove the summary of an app's stack. Children are checked
     * against what's actually still showing, so a stack being dismissed as a whole
     * doesn't get its summary re-posted mid-teardown.
     */
    private fun updateAppSummary(bundleId: String, appName: String?, icon: Bitmap?, iconResId: Int?) {
        // Asked of the system rather than taken from `posted`: tapping a notification
        // removes it (auto-cancel) without telling the app, so `posted` can list ones
        // that are already gone
        val showing = notificationManager.activeNotifications.map { it.id }.toSet()
        val children = posted.filter { (id, p) -> p.bundleId == bundleId && id in showing }
        val summaryId = summaryIdFor(bundleId)
        if (children.isEmpty()) {
            notificationManager.cancel(summaryId)
            return
        }
        val name = appName ?: resolveAppName(bundleId) ?: bundleId
        val summary = NotificationCompat.Builder(this, AncsApplication.CHANNEL_QUIET)
            .setSmallIcon(iconResId ?: AppIconMapper.getIconResId(bundleId, 0))
            .setLargeIcon(icon ?: AppIconRepository.generated(this, bundleId, 0))
            .setContentTitle(name)
            .setContentText(if (children.size == 1) "1 notification" else "${children.size} notifications")
            .setSubText(name)
            .setGroup(appGroupKey(bundleId))
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setDeleteIntent(groupDismissPendingIntent(bundleId, summaryId))
            .build()
        notificationManager.notify(summaryId, summary)
    }

    // getForegroundService throughout: these fire while the app is in the background,
    // where a plain service start is refused if the service is not already running
    private fun dismissPendingIntent(notifId: Int): PendingIntent =
        PendingIntent.getForegroundService(
            this, notifId + 1_000_000,
            Intent(this, AncsService::class.java)
                .setAction(ACTION_NOTIFICATION_DISMISSED)
                .putExtra(EXTRA_NOTIFICATION_ID, notifId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun groupDismissPendingIntent(bundleId: String, summaryId: Int): PendingIntent =
        PendingIntent.getForegroundService(
            this, summaryId,
            Intent(this, AncsService::class.java)
                .setAction(ACTION_GROUP_DISMISSED)
                .putExtra(EXTRA_BUNDLE_ID, bundleId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** User swiped a notification away on the watch → clear it on the iPhone as well. */
    private fun onWatchDismissed(notifId: Int, refreshSummary: Boolean = true) {
        val p = posted.remove(notifId) ?: return
        activeNotificationIds.remove(notifId)
        Log.i(TAG, "Dismissed on watch: uid=${p.uid} app=${p.bundleId}")
        clearOnIPhone(p)
        if (refreshSummary) updateAppSummary(p.bundleId, null, AppIconRepository.peek(p.bundleId), null)
    }

    /**
     * Send the ANCS negative action ("Clear") for a notification — only if it's from
     * the current session (UIDs from earlier sessions may now mean something else).
     */
    private fun clearOnIPhone(p: Posted) {
        if (!p.hasNegativeAction) return
        if (p.session != sessionId || connectionManager.connectionState.value !is ConnectionState.Connected) {
            // UID is stale or the iPhone is away — clear it by content after reconnect
            Log.d(TAG, "Queuing iPhone clear for uid=${p.uid} (stale session or disconnected)")
            pendingIPhoneClears.add(p.signature)
            while (pendingIPhoneClears.size > MAX_PENDING_CLEARS) pendingIPhoneClears.remove(pendingIPhoneClears.first())
            return
        }
        sendNegativeAction(p.uid)
    }

    private fun sendNegativeAction(uid: Long) {
        connectionManager.writeControlPoint(
            ControlPointWriter.buildPerformNotificationAction(uid, AncsConstants.ACTION_NEGATIVE)
        )
    }

    /** Remove a watch notification we (not the user) decided to drop; keeps stacks consistent. */
    private fun removeWatchNotification(notifId: Int) {
        notificationManager.cancel(notifId)
        activeNotificationIds.remove(notifId)
        uidToNotifId.values.remove(notifId)
        val p = posted.remove(notifId) ?: return
        updateAppSummary(p.bundleId, null, AppIconRepository.peek(p.bundleId), null)
    }

    /**
     * Evict the oldest notifications to stay under the system limit.
     * Keeps MAX_ACTIVE_NOTIFICATIONS slots, leaving room for service + call notifications.
     */
    private fun evictOldestIfNeeded() {
        while (activeNotificationIds.size >= MAX_ACTIVE_NOTIFICATIONS) {
            val oldId = activeNotificationIds.first()
            removeWatchNotification(oldId)
            Log.d(TAG, "Auto-evicted oldest notification id=$oldId (${activeNotificationIds.size} active)")
        }
    }

    /**
     * First NS event or Connected state of a new ANCS session (whichever comes first):
     * bump the session and capture when the link was last up, for missed-while-away.
     */
    private var sessionMarker = 0L
    private fun ensureSessionStarted() {
        val started = connectionManager.sessionStartedAt
        if (started == 0L || started == sessionMarker) return
        sessionMarker = started
        sessionId++
        // UIDs from the previous session are meaningless now; watch notifications get
        // re-linked as iOS re-announces them (see postNotification backlog handling).
        // iOS restarts UIDs at 0, so leftovers here would match fresh notifications and
        // make them post quietly (backlog) or silently replace another one (update).
        uidToNotifId.clear()
        backlogUids.clear()
        modifiedUids.clear()
        missedWindowStart = getSharedPreferences("wearbridge", MODE_PRIVATE).getLong(PREF_LAST_LINK_UP, 0L)
        Log.i(TAG, "ANCS session $sessionId started; link last up at $missedWindowStart")
    }

    private fun onLinkUp() {
        ensureSessionStarted()
        leftBehindJob?.cancel()
        notificationManager.cancel(NOTIFICATION_ID_LEFT_BEHIND)
        if (wasConnected) return
        wasConnected = true
        // Remember the link is up (survives process death) for missed-while-away
        linkHeartbeatJob?.cancel()
        linkHeartbeatJob = scope.launch {
            while (true) {
                markLinkUp()
                kotlinx.coroutines.delay(LINK_HEARTBEAT_MS)
            }
        }
    }

    private fun onLinkDown(unexpected: Boolean) {
        if (!wasConnected) return
        wasConnected = false
        linkHeartbeatJob?.cancel()
        markLinkUp()
        // Can't act on the iPhone any more — stop anything ringing on the wrist
        if (activeCallUid != -1L) {
            activeCallUid = -1
            ringer.stop("link lost")
            notificationManager.cancel(NOTIFICATION_ID_CALL)
            AncsConnectionService.endActiveCall()
            sendBroadcast(Intent(IncomingCallActivity.ACTION_CALL_ENDED).setPackage(packageName))
        }
        // Its timer would run on forever; iOS re-announces the call if it is still going
        ongoingCall?.let {
            notificationManager.cancel(it.notifId)
            ongoingCall = null
            sendBroadcast(Intent(IncomingCallActivity.ACTION_CALL_ENDED).setPackage(packageName))
        }

        if (unexpected && AppSettings.toggles.value.leftBehindAlert) {
            leftBehindJob?.cancel()
            // Screen-off watches suspend the CPU, which pauses coroutine delays; hold a
            // short wakelock so the alert fires ~15 s after the drop, not at next wake.
            val wakeLock = getSystemService(android.os.PowerManager::class.java)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Orbit:leftBehind")
                .apply { acquire(LEFT_BEHIND_DELAY_MS + 5_000L) }
            leftBehindJob = scope.launch {
                try {
                    // Short blips reconnect within seconds — only alert if it stays down
                    kotlinx.coroutines.delay(LEFT_BEHIND_DELAY_MS)
                    // Nobody to warn if the watch is not being worn (on its charger, say)
                    if (connectionManager.connectionState.value !is ConnectionState.Connected && !isQuietOffWrist()) {
                        showLeftBehindAlert()
                    }
                } finally {
                    if (wakeLock.isHeld) wakeLock.release()
                }
            }
        }
    }

    private fun markLinkUp() {
        getSharedPreferences("wearbridge", MODE_PRIVATE).edit { putLong(PREF_LAST_LINK_UP, System.currentTimeMillis()) }
    }

    /** Like Apple Watch's "iPhone disconnected": one buzz, cleared on reconnect. */
    private fun showLeftBehindAlert() {
        Log.i(TAG, "iPhone still disconnected — left-behind alert")
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val alert = NotificationCompat.Builder(this, AncsApplication.CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentTitle("iPhone disconnected")
            .setContentText("Your iPhone is out of range or its Bluetooth is off")
            .setCategory(Notification.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID_LEFT_BEHIND, alert)
    }

    // Debug injection (adb only) — feeds the real pipeline without an iPhone event
    private fun debugPost(n: AncsNotification) { scope.launch { postNotification(n) } }
    private fun debugCall(n: AncsNotification?) {
        if (n != null) showIncomingCall(n) else if (activeCallUid != -1L) {
            handleNotificationSourceEvent(DebugInjector.removedEvent(activeCallUid, AncsConstants.CATEGORY_INCOMING_CALL))
        }
    }
    private fun debugRemove(uid: Long) {
        handleNotificationSourceEvent(DebugInjector.removedEvent(uid, 0))
    }

    /**
     * Call in progress on the iPhone: silent ongoing notification with End Call, which sends
     * the negative action (hang up), and a running call timer. As an ongoing activity it
     * puts a call icon on the watch face, like Apple Watch's green phone indicator.
     * Cleared by the REMOVED event.
     *
     * [reannounced]: iOS re-sent the call after a reconnect, so its ANCS date, not now, is
     * the best estimate of when it began.
     */
    private fun showActiveCall(notification: AncsNotification, reannounced: Boolean) {
        val call = ongoingCall?.takeIf { it.uid == notification.uid } ?: run {
            val nowWall = System.currentTimeMillis()
            val nowElapsed = SystemClock.elapsedRealtime()
            val sinceMs = if (reannounced && notification.date != null) {
                (nowWall - parseAncsDate(notification.date)).coerceIn(0L, MAX_CALL_AGE_MS)
            } else 0L
            OngoingCall(
                uid = notification.uid,
                notifId = uidToNotifId.getOrPut(notification.uid) { allocateNotifId() },
                startedElapsed = nowElapsed - sinceMs,
                startedWall = nowWall - sinceMs
            )
        }
        ongoingCall = call

        val callerName = notification.title.ifEmpty { "Call" }
        val hasEndCall = notification.eventFlags and AncsConstants.EVENT_FLAG_NEGATIVE_ACTION != 0
        // Tapping the indicator or the notification opens the in-call screen
        val open = PendingIntent.getActivity(
            this, 41,
            IncomingCallActivity.inCallIntent(
                this, notification.uid, callerName, call.startedElapsed,
                if (hasEndCall) notification.negativeActionLabel ?: "End Call" else null
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, AncsApplication.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentTitle(callerName)
            .setContentText("On call · iPhone")
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            // Call duration, counting up from when the call began
            .setWhen(call.startedWall)
            .setShowWhen(true)
            .setUsesChronometer(true)
        if (hasEndCall) {
            builder.addAction(
                R.drawable.ic_orbit_call_decline,
                notification.negativeActionLabel ?: "End Call",
                actionPendingIntent(NotificationActionReceiver.ACTION_NEGATIVE, notification.uid, call.notifId + 500_000)
            )
        }
        OngoingActivity.Builder(this, call.notifId, builder)
            .setStaticIcon(R.drawable.ic_phone)
            .setTouchIntent(open)
            .setCategory(Notification.CATEGORY_CALL)
            .setStatus(
                Status.Builder()
                    .addTemplate("#name# · #time#")
                    .addPart("name", Status.TextPart(callerName))
                    .addPart("time", Status.StopwatchPart(call.startedElapsed))
                    .build()
            )
            .build()
            .apply(this)
        notificationManager.notify(call.notifId, builder.build())
        Log.i(TAG, "Active call notification uid=${notification.uid}")
    }

    // The call screen is started from this service, outside any task: NEW_TASK and
    // CLEAR_TOP (reuse a call screen already up) are what make that work
    @SuppressLint("WearRecents")
    private fun showIncomingCall(notification: AncsNotification) {
        val callerName = notification.title.ifEmpty { "Incoming Call" }
        val appName = notification.appDisplayName ?: "Phone"

        // Track active call UID to avoid duplicate showIncomingCall calls
        if (activeCallUid == notification.uid) {
            // Already showing — just update caller name if it changed
            if (callerName != "Incoming Call") {
                AncsConnectionService.activeConnection?.updateCallerName(callerName)
                // Also update the IncomingCallActivity via broadcast
                sendBroadcast(Intent(IncomingCallActivity.ACTION_CALLER_NAME_UPDATED).apply {
                    setPackage(packageName)
                    putExtra(IncomingCallActivity.EXTRA_CALLER_NAME, callerName)
                })
                Log.i(TAG, "Updated caller name for uid=${notification.uid}")
            }
            return
        }
        activeCallUid = notification.uid

        // Keep buzzing until answered/declined/silenced — unless the iPhone delivered
        // the call silently (Focus / silenced unknown callers) or the watch is off the wrist
        if (notification.eventFlags and AncsConstants.EVENT_FLAG_SILENT == 0 && !isQuietOffWrist()) {
            ringer.start()
        }

        // 1. Report to Telecom framework — self-managed connection allows
        //    our foreground service to launch activities while ringing
        AncsConnectionService.reportIncomingCall(
            this, notification.uid, callerName, appName
        )

        // 2. Directly launch call activity. Android 14+ blocks background activity
        //    starts unless the app holds "Display over other apps" (SYSTEM_ALERT_WINDOW,
        //    granted via adb appops) — otherwise the full-screen intent below takes over.
        val callIntent = Intent(this, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra(IncomingCallActivity.EXTRA_CALLER_NAME, callerName)
            putExtra(IncomingCallActivity.EXTRA_NOTIFICATION_UID, notification.uid)
            putExtra(IncomingCallActivity.EXTRA_APP_NAME, appName)
        }
        if (android.provider.Settings.canDrawOverlays(this)) {
            try {
                startActivity(callIntent)
                Log.i(TAG, "Started IncomingCallActivity directly")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start IncomingCallActivity: ${e.message}")
            }
        } else {
            Log.w(TAG, "No overlay permission — relying on full-screen intent for call screen")
        }

        // 3. CallStyle notification: full-screen intent + Answer/Decline that act on the iPhone directly
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 0, callIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val answerPendingIntent = actionPendingIntent(
            NotificationActionReceiver.ACTION_POSITIVE, notification.uid, requestCode = 1
        )
        val declinePendingIntent = actionPendingIntent(
            NotificationActionReceiver.ACTION_NEGATIVE, notification.uid, requestCode = 2
        )
        val caller = androidx.core.app.Person.Builder()
            .setName(callerName)
            .setImportant(true)
            .build()
        val callNotification = NotificationCompat.Builder(this, AncsApplication.CHANNEL_INCOMING_CALL)
            .setSmallIcon(R.drawable.ic_phone)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    caller, declinePendingIntent, answerPendingIntent
                )
            )
            .build()
        notificationManager.notify(NOTIFICATION_ID_CALL, callNotification)
        Log.i(TAG, "Posted CallStyle notification")
    }


    /**
     * Explicit broadcast to NotificationActionReceiver. It has no intent-filter, so an
     * implicit action+package intent would never be delivered.
     */
    private fun actionPendingIntent(action: String, uid: Long, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            this, requestCode,
            Intent(this, NotificationActionReceiver::class.java)
                .setAction(action)
                .putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_UID, uid),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun performNotificationAction(uid: Long, actionId: Int) {
        val data = ControlPointWriter.buildPerformNotificationAction(uid, actionId)
        val sent = connectionManager.writeControlPoint(data)
        Log.i(TAG, "Perform action $actionId on uid=$uid (queued=$sent)")
        // Cancel the Android notification and remove from tracking
        notifIdForUid(uid)?.let { removeWatchNotification(it) }
        // By tracking too: "Clear Notifications" may have forgotten the UID's mapping
        ongoingCall?.takeIf { it.uid == uid }?.let {
            notificationManager.cancel(it.notifId)
            ongoingCall = null
        }

        // Call answered/declined from the watch: stop ringing UI right away instead
        // of waiting for the iPhone's MODIFIED/REMOVED event
        if (uid == activeCallUid) {
            ringer.stop("call action from watch")
            notificationManager.cancel(NOTIFICATION_ID_CALL)
            AncsConnectionService.endActiveCall()
            val broadcast = if (actionId == AncsConstants.ACTION_POSITIVE) {
                IncomingCallActivity.ACTION_CALL_ANSWERED
            } else {
                activeCallUid = -1
                IncomingCallActivity.ACTION_CALL_ENDED
            }
            sendBroadcast(Intent(broadcast).setPackage(packageName))
        }
    }

    /** Where names the iPhone told us are kept, so we only ever ask once per app. */
    private fun appNamePrefs() = getSharedPreferences("app_names", MODE_PRIVATE)

    private fun resolveAppName(appIdentifier: String): String? {
        if (appIdentifier.isEmpty()) return null
        appNameCache[appIdentifier]?.let { return it }
        appNamePrefs().getString(appIdentifier, null)?.let {
            appNameCache[appIdentifier] = it
            return it
        }
        // Until the iPhone tells us: our list of Apple's own apps, else the last part of
        // the bundle ID ("com.apple.MobileSMS" -> "MobileSMS"). Not cached, so the real
        // name replaces it as soon as it arrives.
        return knownAppNames[appIdentifier] ?: appIdentifier.substringAfterLast(".")
    }

    private fun parseAncsDate(date: String?): Long {
        if (date == null) return System.currentTimeMillis()
        return try {
            java.time.LocalDateTime.parse(date.trim(), ANCS_DATE)
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun androidCategory(categoryId: Int): String = when (categoryId) {
        AncsConstants.CATEGORY_INCOMING_CALL -> Notification.CATEGORY_CALL
        AncsConstants.CATEGORY_MISSED_CALL -> Notification.CATEGORY_MISSED_CALL
        // CATEGORY_VOICEMAIL exists from API 35; before that a missed call is the closest
        AncsConstants.CATEGORY_VOICEMAIL ->
            if (android.os.Build.VERSION.SDK_INT >= 35) Notification.CATEGORY_VOICEMAIL
            else Notification.CATEGORY_MISSED_CALL
        AncsConstants.CATEGORY_SOCIAL -> Notification.CATEGORY_SOCIAL
        AncsConstants.CATEGORY_SCHEDULE -> Notification.CATEGORY_EVENT
        AncsConstants.CATEGORY_EMAIL -> Notification.CATEGORY_EMAIL
        else -> Notification.CATEGORY_MESSAGE
    }

    /** Watch notification ID for an ANCS UID of the current session, if we posted one. */
    private fun notifIdForUid(uid: Long): Int? = uidToNotifId[uid]

    /**
     * Fresh watch notification ID. Never derived from the ANCS UID: iOS restarts UIDs
     * at 0 on reconnect, so a UID-based ID would overwrite an unrelated notification.
     * Persisted so IDs stay unique across service restarts too.
     */
    private fun allocateNotifId(): Int {
        // IDs are reserved on disk a block at a time instead of one write per notification.
        // After a restart the rest of a block is skipped, which only matters for uniqueness.
        if (nextNotifId !in NOTIFICATION_ID_BASE until reservedNotifIdLimit) {
            var start = nextNotifId.takeIf { it in NOTIFICATION_ID_BASE until MAX_NOTIF_ID }
                ?: getSharedPreferences("wearbridge", MODE_PRIVATE).getInt(PREF_NEXT_NOTIF_ID, NOTIFICATION_ID_BASE)
            if (start !in NOTIFICATION_ID_BASE until MAX_NOTIF_ID) start = NOTIFICATION_ID_BASE
            reservedNotifIdLimit = minOf(start + NOTIF_ID_BLOCK, MAX_NOTIF_ID)
            getSharedPreferences("wearbridge", MODE_PRIVATE).edit { putInt(PREF_NEXT_NOTIF_ID, reservedNotifIdLimit) }
            nextNotifId = start
        }
        return nextNotifId++
    }

    private var nextNotifId = -1
    private var reservedNotifIdLimit = -1

    /** Identity of a notification across sessions (its UID changes, its content doesn't). */
    private fun signatureOf(n: AncsNotification) =
        listOf(n.appIdentifier, n.title, n.message, n.date.orEmpty()).joinToString(UNIT_SEPARATOR)

    private fun refreshServiceNotification() {
        val battery = PhoneStatus.battery.value
        val text = if (connectionManager.connectionState.value is ConnectionState.Connected && battery != null) {
            "$serviceStatusText · iPhone $battery%"
        } else {
            serviceStatusText
        }
        notificationManager.notify(SERVICE_NOTIFICATION_ID, buildServiceNotification(text))
    }

    private fun buildServiceNotification(text: String): Notification {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, AncsApplication.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_orbit)
            .setContentTitle("Orbit")
            .setContentText(text)
            .setContentIntent(intent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private val knownAppNames = mapOf(
        // Apple Apps
        "com.apple.MobileSMS" to "Messages",
        "com.apple.mobilephone" to "Phone",
        "com.apple.mobilemail" to "Mail",
        "com.apple.mobilecal" to "Calendar",
        "com.apple.facetime" to "FaceTime",
        "com.apple.reminders" to "Reminders",
        "com.apple.news" to "News",
        "com.apple.mobilenotes" to "Notes",
        "com.apple.Health" to "Health",
        "com.apple.Fitness" to "Fitness",
        "com.apple.weather" to "Weather",
        "com.apple.Maps" to "Maps",
        "com.apple.Music" to "Music",
        "com.apple.stocks" to "Stocks",
        "com.apple.camera" to "Camera",
        "com.apple.Passbook" to "Wallet",
        "com.apple.Preferences" to "Settings",
        "com.apple.mobiletimer" to "Clock",
        "com.apple.podcasts" to "Podcasts",
        "com.apple.findmy" to "Find My",
        "com.apple.Photos" to "Photos",
        "com.apple.TestFlight" to "TestFlight",
        "com.apple.AppStore" to "App Store",
        "com.apple.Passwords" to "Passwords",
        "com.apple.tips" to "Tips",
        "com.apple.shortcuts" to "Shortcuts",
        "com.apple.Home" to "Home",
        "com.apple.iBooks" to "Books",
        "com.apple.tv" to "TV",
        "com.apple.ScreenTimeNotifications" to "Screen Time",

        // Messaging
        "net.whatsapp.WhatsApp" to "WhatsApp",
        "net.whatsapp.WhatsAppSMB" to "WhatsApp Business",
        "org.telegram.Telegram" to "Telegram",
        "com.facebook.Messenger" to "Messenger",
        "org.whispersystems.signal" to "Signal",
        "com.slack.Slack" to "Slack",
        "com.hammerandchisel.discord" to "Discord",

        // Social
        "com.atebits.Tweetie2" to "X",
        "com.burbn.instagram" to "Instagram",
        "com.facebook.Facebook" to "Facebook",
        "com.linkedin.LinkedIn" to "LinkedIn",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.toyopagroup.picaboo" to "Snapchat",
        "com.burbn.barcelona" to "Threads",
        "com.reddit.Reddit" to "Reddit",

        // Email & Productivity
        "com.google.Gmail" to "Gmail",
        "com.microsoft.Office.Outlook" to "Outlook",
        "com.google.calendar" to "Google Calendar",
        "notion.id" to "Notion",
        "com.grammarly.keyboard" to "Grammarly",

        // Google Apps
        "com.google.GoogleMobile" to "Google",
        "com.google.Maps" to "Google Maps",
        "com.google.Drive" to "Google Drive",
        "com.google.Photos" to "Google Photos",
        "com.google.photos" to "Google Photos",
        "com.google.Home" to "Google Home",
        "com.google.chrome.ios" to "Chrome",
        "com.google.ios.youtube" to "YouTube",

        // Entertainment & Media
        "com.spotify.client" to "Spotify",
        "com.shazam.Shazam" to "Shazam",

        // Ride-sharing & Delivery
        "com.ubercab.UberClient" to "Uber",
        "com.ubercab.UberEats" to "Uber Eats",

        // Finance & Banking
        "au.com.westpac.ConsultWPC" to "Westpac",
        "au.com.westpac.banking" to "Westpac",
        "com.stake.stake" to "Stake",
        "au.com.hellostake" to "Stake",
        "com.afterpay.afterpay-consumer" to "Afterpay",

        // Health & Fitness
        "com.anytimefitness.club" to "Anytime Fitness",
        "com.anytimefitness.atfmobile" to "Anytime Fitness",
        "au.com.hotdoc.app" to "HotDoc",
        "com.hotdoc.patient" to "HotDoc",

        // Telecom
        "com.jio.myjio" to "Jio",
        "com.ril.ajio" to "Jio",

        // Shopping & Services
        "au.com.auspost" to "AusPost",
        "com.auspost.MyPost" to "AusPost",

        // Smart Home
        "com.philips.hue.gen4" to "Hue",
        "com.signify.hue.blue" to "Hue",

        // Gaming
        "com.microsoft.smartglass" to "Xbox",
        "com.microsoft.xboxapp" to "Xbox",

        // VPN
        "com.surfshark.vpnclient" to "Surfshark",

        // Other
        "com.producthunt.ProductHuntApp" to "Product Hunt",
        "com.github.stormcrow" to "GitHub",
    )
}
