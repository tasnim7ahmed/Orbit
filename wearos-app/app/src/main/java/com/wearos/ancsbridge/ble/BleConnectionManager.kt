package com.wearos.ancsbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.wearos.ancsbridge.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.os.Handler
import android.os.Looper

/**
 * Manages the BLE GATT connection to an iPhone and the ANCS subscription lifecycle.
 *
 * Flow: connect → bond → discover services → subscribe DS → subscribe NS → active
 */
@SuppressLint("MissingPermission")
class BleConnectionManager(private val context: Context) {

    companion object {
        private const val TAG = "BleConnectionManager"
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 3_000L
        /** Wait before asking for a slower connection interval, so setup traffic stays fast. */
        private const val LOW_POWER_SETTLE_MS = 10_000L
        private const val TARGET_MTU = 512
        private const val MAX_SERVICE_DISCOVERY_RETRIES = 2
        private const val PREFS_NAME = "wearbridge"
        private const val PREF_IPHONE_ADDRESS = "iphone_address"

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun rememberedIPhoneAddress(context: Context): String? =
            prefs(context).getString(PREF_IPHONE_ADDRESS, null)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _notificationSourceEvents = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val notificationSourceEvents: SharedFlow<ByteArray> = _notificationSourceEvents.asSharedFlow()

    private val _dataSourceEvents = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val dataSourceEvents: SharedFlow<ByteArray> = _dataSourceEvents.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var targetDevice: BluetoothDevice? = null
    private var reconnectJob: Job? = null
    private var reconnectDelay = INITIAL_RECONNECT_DELAY_MS
    private var autoReconnect = true

    // All GATT reads/writes are serialized — only one may be in flight per connection
    private val opQueue = GattOperationQueue(scope) { gatt }

    // Battery / Current Time / Apple Media Service on the iPhone
    private val phoneServices = PhoneServices(
        subscribe = { subscribeToCharacteristic(it) },
        read = { opQueue.enqueue(GattOperationQueue.Op.ReadCharacteristic(it)) },
        write = { char, value -> opQueue.enqueue(GattOperationQueue.Op.WriteCharacteristic(char, value)) }
    )

    // GATT callbacks are delivered here so all connection state is touched on one thread
    private val mainHandler = Handler(Looper.getMainLooper())

    private var serviceDiscoveryRetries = 0
    private var servicesDiscoveryStarted = false
    private var ancsSubscribed = false
    private var notificationSourceChar: BluetoothGattCharacteristic? = null
    private var controlPointChar: BluetoothGattCharacteristic? = null
    private var dataSourceChar: BluetoothGattCharacteristic? = null

    // Bond broadcasts arrive for every Bluetooth device (headphones, other phones…);
    // only react to the device we're connected to.
    private fun isOurDevice(device: BluetoothDevice) = device.address == gatt?.device?.address

    val bondStateReceiver = BondStateReceiver(
        onBonded = { device ->
            if (isOurDevice(device)) {
                Log.i(TAG, "Bond completed, re-discovering services")
                // Small delay after bonding before service discovery
                scope.launch {
                    delay(1000)
                    gatt?.discoverServices()
                }
            }
        },
        onBondFailed = { device ->
            if (isOurDevice(device)) {
                Log.e(TAG, "Bond failed")
                _connectionState.value = ConnectionState.Error("Pairing failed")
                disconnect()
            }
        }
    )

    private val gattCallback = GattCallback(
        onConnected = { gatt ->
            reconnectDelay = INITIAL_RECONNECT_DELAY_MS
            val displayName = displayNameOf(gatt.device)
            _connectionState.value = ConnectionState.Connecting(displayName)

            // Request higher MTU for less fragmentation. If the iPhone opened the
            // link (Settings → Bluetooth), the MTU may already be negotiated and
            // onMtuChanged might not fire — fall back to discovery after a timeout.
            servicesDiscoveryStarted = false
            if (!gatt.requestMtu(TARGET_MTU)) {
                startServiceDiscovery(gatt)
            } else {
                scope.launch {
                    delay(3000)
                    if (this@BleConnectionManager.gatt === gatt) startServiceDiscovery(gatt)
                }
            }
        },
        onDisconnected = { gatt, status ->
            if (gatt !== this.gatt) {
                // Late callback from a connection we already replaced — don't touch the current one
                gatt.close()
                return@GattCallback
            }
            Log.i(TAG, "Disconnected, status=$status")
            _connectionState.value = ConnectionState.Disconnected()
            clearCharacteristics()
            gatt.close()
            this.gatt = null

            if (autoReconnect && targetDevice != null) {
                scheduleReconnect()
            }
        },
        onServicesDiscoveredCallback = { gatt, status ->
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // Drop the half-open link; onDisconnected schedules a reconnect
                Log.e(TAG, "Service discovery failed: $status — reconnecting")
                gatt.disconnect()
                return@GattCallback
            }

            val ancsService = gatt.getService(AncsConstants.ANCS_SERVICE_UUID)
            if (ancsService == null) {
                // ANCS not visible — likely not bonded yet
                if (gatt.device.bondState != BluetoothDevice.BOND_BONDED) {
                    Log.i(TAG, "ANCS not found, initiating bonding")
                    ensureBonding(gatt.device)
                } else {
                    // Bonded but ANCS still not visible — retry once, then give up
                    // (this device may not be an iPhone)
                    serviceDiscoveryRetries++
                    if (serviceDiscoveryRetries <= MAX_SERVICE_DISCOVERY_RETRIES) {
                        Log.w(TAG, "Bonded but ANCS not found, retry $serviceDiscoveryRetries/$MAX_SERVICE_DISCOVERY_RETRIES")
                        scope.launch {
                            gatt.disconnect()
                            delay(2000)
                            connect(gatt.device, autoReconnect = false)
                        }
                    } else {
                        Log.e(TAG, "ANCS not found after $MAX_SERVICE_DISCOVERY_RETRIES retries — not an iPhone?")
                        _connectionState.value = ConnectionState.Error("Not an iPhone (no ANCS)")
                        scope.launch {
                            gatt.disconnect()
                            gatt.close()
                            this@BleConnectionManager.gatt = null
                        }
                    }
                }
                return@GattCallback
            }

            // ANCS service found — get characteristics
            Log.i(TAG, "ANCS service found!")
            serviceDiscoveryRetries = 0

            // Rediscovery (e.g. after bonding) on a session that's already subscribed
            if (ancsSubscribed) {
                Log.i(TAG, "ANCS already subscribed")
                return@GattCallback
            }

            notificationSourceChar = ancsService.getCharacteristic(AncsConstants.NOTIFICATION_SOURCE_UUID)
            controlPointChar = ancsService.getCharacteristic(AncsConstants.CONTROL_POINT_UUID)
            dataSourceChar = ancsService.getCharacteristic(AncsConstants.DATA_SOURCE_UUID)

            if (notificationSourceChar == null || dataSourceChar == null) {
                // Transient on iOS while ANCS is (re)starting — reconnect rather than get stuck
                Log.e(TAG, "Missing ANCS characteristics — reconnecting")
                gatt.disconnect()
                return@GattCallback
            }

            // Subscribe to Data Source FIRST (Apple recommendation),
            // then Notification Source
            subscribeToCharacteristic(dataSourceChar!!)
        },
        onNotificationSourceChanged = { data ->
            _notificationSourceEvents.tryEmit(data)
        },
        onDataSourceChanged = { data ->
            _dataSourceEvents.tryEmit(data)
        },
        onDescriptorWritten = { descriptor, status ->
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Descriptor write failed: $status for ${descriptor.characteristic.uuid}")
                val uuid = descriptor.characteristic.uuid
                if (uuid == AncsConstants.DATA_SOURCE_UUID || uuid == AncsConstants.NOTIFICATION_SOURCE_UUID) {
                    // ANCS was visible before encryption (insufficient auth/encryption).
                    // Bond, and BondStateReceiver will re-discover and re-subscribe.
                    opQueue.clear()
                    gatt?.device?.let { device ->
                        if (device.bondState != BluetoothDevice.BOND_BONDED) ensureBonding(device)
                    }
                } else {
                    opQueue.onOperationComplete(descriptor.characteristic.uuid)
                }
                return@GattCallback
            }

            val charUuid = descriptor.characteristic.uuid
            when (charUuid) {
                AncsConstants.DATA_SOURCE_UUID -> {
                    Log.i(TAG, "Subscribed to Data Source, now subscribing to Notification Source")
                    notificationSourceChar?.let { subscribeToCharacteristic(it) }
                }
                AncsConstants.NOTIFICATION_SOURCE_UUID -> {
                    Log.i(TAG, "Subscribed to Notification Source — ANCS session active!")
                    ancsSubscribed = true
                    sessionStartedAt = System.currentTimeMillis()
                    gatt?.device?.let { rememberIPhone(it) }
                    // Show initial name (may be truncated BLE name)
                    val deviceName = resolveDeviceName()
                    _connectionState.value = ConnectionState.Connected(deviceName)
                    // Read the real iPhone name from GAP service
                    readGapDeviceName()
                    // iPhone battery, clock check, media controls
                    gatt?.let { phoneServices.start(it) }
                    // Once the session has settled, ask for a slower connection interval.
                    // Fewer radio wake-ups on both sides; ANCS events still arrive in
                    // well under a second.
                    scope.launch {
                        delay(LOW_POWER_SETTLE_MS)
                        if (ancsSubscribed) {
                            val accepted = gatt?.requestConnectionPriority(
                                BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
                            )
                            Log.i(TAG, "Low-power connection interval requested: $accepted")
                        }
                    }
                }
            }

            opQueue.onOperationComplete(descriptor.characteristic.uuid)
        },
        onCharacteristicWritten = { characteristic, status ->
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Characteristic write failed: $status for ${characteristic.uuid}")
            }
            opQueue.onOperationComplete(characteristic.uuid)
        },
        onMtuChanged = { mtu, status ->
            Log.i(TAG, "MTU negotiated: $mtu (status=$status)")
            // After MTU negotiation, discover services
            gatt?.let { startServiceDiscovery(it) }
        },
        onCharacteristicReadCallback = { characteristic, value, status ->
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onCharacteristicRead(characteristic.uuid, value)
            } else {
                Log.w(TAG, "Characteristic read failed: $status for ${characteristic.uuid}")
            }
            opQueue.onOperationComplete(characteristic.uuid)
        },
        onOtherCharacteristicChanged = { uuid, value ->
            phoneServices.onValue(uuid, value)
        }
    )

    /**
     * When the current ANCS session's Notification Source subscription completed.
     * Set before any NS event of the session can arrive, so event handlers can rely on it.
     */
    var sessionStartedAt = 0L
        private set

    /** Send an Apple Media Service remote command (see [AmsProtocol]). */
    fun sendMediaCommand(command: Int): Boolean = phoneServices.sendMediaCommand(command)

    /**
     * Connect to a BLE device (iPhone).
     */
    fun connect(device: BluetoothDevice, autoReconnect: Boolean = true) {
        // Guard: skip if already connected or connecting to this device
        val currentState = _connectionState.value
        if (gatt != null && gatt?.device?.address == device.address &&
            (currentState is ConnectionState.Connected || currentState is ConnectionState.Connecting ||
                currentState is ConnectionState.Bonding)) {
            Log.d(TAG, "Already connected/connecting to ${device.address}, skipping")
            return
        }

        reconnectJob?.cancel()
        this.autoReconnect = autoReconnect
        this.targetDevice = device
        this.serviceDiscoveryRetries = 0

        _connectionState.value = ConnectionState.Connecting(displayNameOf(device))

        // Replacing a link: reset per-connection state, or the new link would think
        // ANCS is already subscribed and never subscribe
        gatt?.close()
        clearCharacteristics()
        gatt = device.connectGatt(
            context,
            false, // autoConnect=false for faster initial connection
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK,
            mainHandler
        )
    }

    /**
     * Reconnect to a previously bonded device using autoConnect.
     */
    fun reconnect(device: BluetoothDevice) {
        // Guard: skip if already connected or connecting to this device
        val currentState = _connectionState.value
        if (gatt != null && gatt?.device?.address == device.address &&
            (currentState is ConnectionState.Connected || currentState is ConnectionState.Connecting ||
                currentState is ConnectionState.Bonding)) {
            Log.d(TAG, "Already connected/connecting to ${device.address}, skipping reconnect")
            return
        }

        reconnectJob?.cancel()
        this.targetDevice = device
        this.autoReconnect = true
        this.serviceDiscoveryRetries = 0

        _connectionState.value = ConnectionState.Connecting(displayNameOf(device))

        // Replacing a link: reset per-connection state, or the new link would think
        // ANCS is already subscribed and never subscribe
        gatt?.close()
        clearCharacteristics()
        gatt = device.connectGatt(
            context,
            true, // autoConnect=true uses chipset whitelist, battery efficient
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK,
            mainHandler
        )
    }

    /**
     * Queue a write to the ANCS Control Point characteristic.
     */
    fun writeControlPoint(data: ByteArray): Boolean {
        if (gatt == null) return false
        val char = controlPointChar ?: return false
        opQueue.enqueue(GattOperationQueue.Op.WriteCharacteristic(char, data))
        return true
    }

    /** Cached real device name read from GAP characteristic */
    private var cachedDeviceName: String? = null

    /**
     * User-friendly iPhone name until the GAP name arrives: bonded name, else the
     * GATT device name, else "iPhone".
     */
    private fun resolveDeviceName(): String {
        cachedDeviceName?.let { return it }
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bondedName = bluetoothManager.adapter?.bondedDevices?.firstOrNull { device ->
            device.address == gatt?.device?.address
        }?.name
        return bondedName ?: gatt?.device?.name ?: "iPhone"
    }

    /** Display name for a device before we know its real GAP name. */
    private fun displayNameOf(device: BluetoothDevice) = device.name ?: "iPhone"

    /**
     * Read the real device name from the GAP (Generic Access Profile) service.
     * The Device Name characteristic (0x2A00) contains the name set on the iPhone
     * (e.g., "Tasnim's iPhone"), which the Bluetooth device name may not match.
     */
    fun readGapDeviceName() {
        val gatt = this.gatt ?: return
        val gapServiceUuid = java.util.UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
        val deviceNameUuid = java.util.UUID.fromString("00002A00-0000-1000-8000-00805f9b34fb")
        val service = gatt.getService(gapServiceUuid) ?: return
        val char = service.getCharacteristic(deviceNameUuid) ?: return
        opQueue.enqueue(GattOperationQueue.Op.ReadCharacteristic(char))
        Log.i(TAG, "Reading GAP Device Name characteristic")
    }

    /**
     * Called from GattCallback when a characteristic read completes.
     * If it's the GAP Device Name, cache and broadcast it.
     */
    fun onCharacteristicRead(characteristicUuid: java.util.UUID, value: ByteArray) {
        if (phoneServices.onValue(characteristicUuid, value)) return

        val deviceNameUuid = java.util.UUID.fromString("00002A00-0000-1000-8000-00805f9b34fb")
        if (characteristicUuid == deviceNameUuid) {
            val name = String(value, Charsets.UTF_8)
            if (name.isNotBlank()) {
                cachedDeviceName = name
                Log.i(TAG, "GAP Device Name: $name")
                _connectionState.value = ConnectionState.Connected(name)
            }
        }
    }

    /**
     * The watch's Bluetooth adapter is turning off. Android does NOT deliver
     * onConnectionStateChange for that, so without this the app would think it's
     * still connected to a dead link and never reconnect.
     */
    fun onAdapterOff() {
        if (gatt == null && _connectionState.value !is ConnectionState.Connected) return
        Log.i(TAG, "Bluetooth turned off — dropping GATT connection")
        reconnectJob?.cancel()
        gatt?.close()
        gatt = null
        clearCharacteristics()
        // Keep targetDevice so the service can reconnect when Bluetooth returns
        _connectionState.value = ConnectionState.Disconnected("Bluetooth off")
    }

    fun disconnect() {
        autoReconnect = false
        reconnectJob?.cancel()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        clearCharacteristics()
        targetDevice = null
        _connectionState.value = ConnectionState.Idle
    }

    /**
     * Get the bonded iPhone device if one exists.
     */
    fun getBondedIPhone(): BluetoothDevice? {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter ?: return null
        val bonded = adapter.bondedDevices ?: return null

        // Prefer the device that last completed an ANCS session — the iPhone's
        // name may not contain "iPhone" (e.g. "Tasnim's 17 Pro").
        rememberedIPhoneAddress(context)?.let { saved ->
            bonded.firstOrNull { it.address == saved }?.let { return it }
        }

        return bonded.firstOrNull { device ->
            (device.name ?: "").contains("iPhone", ignoreCase = true)
        }
    }

    private fun subscribeToCharacteristic(characteristic: BluetoothGattCharacteristic) {
        val gatt = this.gatt ?: return

        gatt.setCharacteristicNotification(characteristic, true)

        val descriptor = characteristic.getDescriptor(AncsConstants.CCCD_UUID)
        if (descriptor == null) {
            Log.e(TAG, "CCCD not found for ${characteristic.uuid}")
            return
        }

        val value = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }

        // Queue the write since BLE only allows one outstanding GATT operation at a time
        opQueue.enqueue(GattOperationQueue.Op.WriteDescriptor(descriptor, value))
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Log.i(TAG, "Scheduling reconnect in ${reconnectDelay}ms")
            delay(reconnectDelay)
            reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            targetDevice?.let { reconnect(it) }
        }
    }

    private fun rememberIPhone(device: BluetoothDevice) {
        prefs(context).edit().putString(PREF_IPHONE_ADDRESS, device.address).apply()
    }

    /** Start pairing unless the iPhone (or the stack) already started it. */
    private fun ensureBonding(device: BluetoothDevice) {
        val displayName = displayNameOf(device)
        _connectionState.value = ConnectionState.Bonding(displayName)
        if (device.bondState == BluetoothDevice.BOND_NONE) {
            device.createBond()
        } else {
            Log.i(TAG, "Bonding already in progress, waiting for BOND_BONDED")
        }
    }

    /** discoverServices() once per connection — reached via onMtuChanged or the MTU timeout. */
    private fun startServiceDiscovery(gatt: BluetoothGatt) {
        if (servicesDiscoveryStarted) return
        servicesDiscoveryStarted = true
        gatt.discoverServices()
    }

    private fun clearCharacteristics() {
        ancsSubscribed = false
        servicesDiscoveryStarted = false
        notificationSourceChar = null
        controlPointChar = null
        dataSourceChar = null
        opQueue.clear()
        phoneServices.reset()
    }

    fun destroy() {
        autoReconnect = false
        reconnectJob?.cancel()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }
}
