package com.wearos.ancsbridge.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.wearos.ancsbridge.ancs.AncsService
import com.wearos.ancsbridge.ble.BleConnectionManager
import com.wearos.ancsbridge.model.ClockStatus
import com.wearos.ancsbridge.model.ConnectionState
import com.wearos.ancsbridge.model.MediaState
import com.wearos.ancsbridge.model.PhoneStatus
import com.wearos.ancsbridge.settings.AppSettings
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@SuppressLint("MissingPermission")
class MainViewModel(application: Application) : AndroidViewModel(application) {
    // Observe the service's shared connection state directly
    val connectionState: StateFlow<ConnectionState> = AncsService.sharedConnectionState

    // Pairing mode: watch advertises so the iPhone can pair from Settings → Bluetooth
    val pairingState: StateFlow<AncsService.PairingState> = AncsService.pairingState

    // Live iPhone status from Battery / Current Time / Apple Media services
    val iphoneBattery: StateFlow<Int?> = PhoneStatus.battery
    val media: StateFlow<MediaState> = PhoneStatus.media
    val clock: StateFlow<ClockStatus?> = PhoneStatus.clock

    // Watch-side notification settings
    val toggles: StateFlow<AppSettings.Toggles> = AppSettings.toggles
    val knownApps: StateFlow<List<AppSettings.AppEntry>> = AppSettings.apps

    fun setToggles(toggles: AppSettings.Toggles) = AppSettings.setToggles(toggles)
    fun setAppMode(bundleId: String, mode: AppSettings.AlertMode) = AppSettings.setMode(bundleId, mode)
    fun setAppHaptic(bundleId: String, haptic: AppSettings.Haptic) = AppSettings.setHaptic(bundleId, haptic)

    /** Send an Apple Media Service command (AmsProtocol.CMD_*) to the iPhone's player. */
    fun sendMediaCommand(command: Int) {
        val context = getApplication<Application>()
        val intent = Intent(context, AncsService::class.java)
            .setAction(AncsService.ACTION_MEDIA_COMMAND)
            .putExtra(AncsService.EXTRA_MEDIA_COMMAND, command)
        context.startService(intent)
    }

    /**
     * Make the watch discoverable to the iPhone (no iPhone app required).
     * The user then taps the watch in iPhone Settings → Bluetooth.
     */
    fun startPairing() {
        sendServiceAction(AncsService.ACTION_START_PAIRING, foreground = true)
    }

    fun stopPairing() {
        sendServiceAction(AncsService.ACTION_STOP_PAIRING, foreground = false)
    }

    private fun sendServiceAction(action: String, foreground: Boolean) {
        val context = getApplication<Application>()
        val intent = Intent(context, AncsService::class.java).setAction(action)
        if (foreground) context.startForegroundService(intent) else context.startService(intent)
    }

    fun startService() {
        val context = getApplication<Application>()
        val intent = Intent(context, AncsService::class.java).apply {
            action = AncsService.ACTION_START
        }
        context.startForegroundService(intent)
    }

    fun disconnect() {
        val context = getApplication<Application>()
        val intent = Intent(context, AncsService::class.java).apply {
            action = AncsService.ACTION_DISCONNECT
        }
        context.startService(intent)
    }

    // Bonded-device lookup is a Binder call — refresh on connection changes, not per recomposition
    val hasBondedIPhone: StateFlow<Boolean> = connectionState
        .map { checkBondedIPhone() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, checkBondedIPhone())

    private fun checkBondedIPhone(): Boolean {
        val context = getApplication<Application>()
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: return false
        val saved = BleConnectionManager.rememberedIPhoneAddress(context)
        return adapter.bondedDevices.any { device ->
            val name = device.name ?: ""
            device.address == saved || name.contains("iPhone", ignoreCase = true)
        }
    }

    /**
     * Clear all Orbit notifications from the watch.
     * Frees up slots so new notifications can come through.
     */
    fun clearAllNotifications() {
        val context = getApplication<Application>()
        // The service clears its iPhone notifications on the watch and on the iPhone
        // (leaves the service and Now Playing notifications alone)
        val intent = Intent(context, AncsService::class.java).apply {
            action = AncsService.ACTION_CLEAR_ALL
        }
        context.startService(intent)
    }
}
