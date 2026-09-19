package com.wearos.ancsbridge.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.wear.compose.material3.Icon
import com.wearos.ancsbridge.ancs.AncsService
import com.wearos.ancsbridge.model.ClockStatus
import com.wearos.ancsbridge.model.ConnectionState
import com.wearos.ancsbridge.viewmodel.MainViewModel

@SuppressLint("MissingPermission")
@Composable
fun MainScreen(viewModel: MainViewModel, openMediaRequests: Int = 0) {
    var showPairScreen by remember { mutableStateOf(false) }
    var showMediaScreen by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // Tile, complication, ongoing activity or auto-launch asked for Now Playing
    LaunchedEffect(openMediaRequests) {
        if (openMediaRequests > 0) {
            showPairScreen = false
            showSettings = false
            showMediaScreen = true
        }
    }

    when {
        showPairScreen -> PairNewDeviceScreen(
            viewModel = viewModel,
            onDismiss = { showPairScreen = false }
        )
        showMediaScreen -> MediaScreen(
            viewModel = viewModel,
            onDismiss = { showMediaScreen = false }
        )
        showSettings -> SettingsScreen(
            viewModel = viewModel,
            onDismiss = { showSettings = false }
        )
        else -> HomeScreen(
            viewModel = viewModel,
            onPairNewDevice = { showPairScreen = true },
            onOpenMedia = { showMediaScreen = true },
            onOpenSettings = { showSettings = true }
        )
    }
}

@SuppressLint("MissingPermission")
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onPairNewDevice: () -> Unit,
    onOpenMedia: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val battery by viewModel.iphoneBattery.collectAsState()
    val clock by viewModel.clock.collectAsState()
    val media by viewModel.media.collectAsState()
    val hasBondedIPhone by viewModel.hasBondedIPhone.collectAsState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize().rotaryFocus(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            ListHeader {
                Text("Orbit")
            }
        }

        when (connectionState) {
            is ConnectionState.Connected -> {
                val deviceName = (connectionState as ConnectionState.Connected).deviceName ?: "iPhone"

                // Status
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF34D399),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Connected", fontSize = 14.sp)
                            Text(
                                "Notifications active",
                                fontSize = 11.sp,
                                color = Color(0xFF34D399)
                            )
                        }
                    }
                }

                // iPhone info
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .padding(vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Rounded.PhoneIphone,
                            contentDescription = null,
                            tint = Color(0xFF60A5FA),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(deviceName, fontSize = 14.sp)
                            Text(
                                battery?.let { "Battery $it%" } ?: "via ANCS over BLE",
                                fontSize = 11.sp,
                                color = batteryColor(battery)
                            )
                        }
                    }
                }

                // Clock check against iPhone (Current Time Service)
                clock?.let { status ->
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .padding(vertical = 4.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Schedule,
                                contentDescription = null,
                                tint = if (status.inSync) Color(0xFF34D399) else Color(0xFFFBBF24),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                clockText(status),
                                fontSize = 11.sp,
                                color = Color(0xFF9CA3AF)
                            )
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }

                // Now Playing (Apple Media Service)
                item {
                    Button(
                        onClick = onOpenMedia,
                        modifier = Modifier.fillMaxWidth(0.9f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A5F))
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.MusicNote,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (media.hasTrack) media.title else "Media Controls",
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(4.dp)) }

                // Clear notifications
                item {
                    Button(
                        onClick = { viewModel.clearAllNotifications() },
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        Text("Clear Notifications", fontSize = 12.sp)
                    }
                }

                item { Spacer(modifier = Modifier.height(4.dp)) }

                item { SettingsButton(onOpenSettings) }

                item { Spacer(modifier = Modifier.height(4.dp)) }

                // Disconnect
                item {
                    Button(
                        onClick = { viewModel.disconnect() },
                        modifier = Modifier.fillMaxWidth(0.9f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFEF4444)
                        )
                    ) {
                        Text("Disconnect", fontSize = 12.sp, color = Color.White)
                    }
                }
            }

            is ConnectionState.Connecting, is ConnectionState.Bonding -> {
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            when (connectionState) {
                                is ConnectionState.Connecting ->
                                    "Connecting to ${(connectionState as ConnectionState.Connecting).deviceName ?: "iPhone"}…"
                                is ConnectionState.Bonding ->
                                    "Pairing with ${(connectionState as ConnectionState.Bonding).deviceName ?: "iPhone"}…"
                                else -> "Connecting…"
                            },
                            textAlign = TextAlign.Center,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            else -> {
                // Idle / Disconnected / Error — show reconnect or pair

                if (hasBondedIPhone) {
                    // Has a bonded device — show reconnecting state
                    item {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Icon(
                                Icons.Rounded.PhoneIphone,
                                contentDescription = null,
                                tint = Color(0xFF9CA3AF),
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                if (connectionState is ConnectionState.Error)
                                    (connectionState as ConnectionState.Error).message
                                else "iPhone not in range",
                                textAlign = TextAlign.Center,
                                fontSize = 13.sp,
                                color = Color(0xFF9CA3AF)
                            )
                        }
                    }

                    item {
                        Button(
                            onClick = { viewModel.startService() },
                            modifier = Modifier.fillMaxWidth(0.9f)
                        ) {
                            Text("Reconnect", fontSize = 12.sp)
                        }
                    }

                    item { Spacer(modifier = Modifier.height(4.dp)) }
                } else {
                    // No bonded device — prompt to pair
                    item {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Icon(
                                Icons.Rounded.PhoneIphone,
                                contentDescription = null,
                                tint = Color(0xFF9CA3AF),
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "No iPhone paired",
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                "Pair your iPhone to start receiving notifications",
                                fontSize = 11.sp,
                                color = Color(0xFF9CA3AF),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // Always show Pair New Device button
                item {
                    Button(
                        onClick = onPairNewDevice,
                        modifier = Modifier.fillMaxWidth(0.9f),
                        colors = if (hasBondedIPhone)
                            ButtonDefaults.buttonColors(containerColor = Color(0xFF374151))
                        else ButtonDefaults.buttonColors()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pair New Device", fontSize = 12.sp)
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(4.dp)) }

                item { SettingsButton(onOpenSettings) }
            }
        }
    }
}

@Composable
private fun SettingsButton(onOpenSettings: () -> Unit) {
    Button(
        onClick = onOpenSettings,
        modifier = Modifier.fillMaxWidth(0.9f),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF374151))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Settings", fontSize = 12.sp)
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun PairNewDeviceScreen(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val connectionState by viewModel.connectionState.collectAsState()
    val pairingState by viewModel.pairingState.collectAsState()

    // Auto-dismiss when connected
    if (connectionState is ConnectionState.Connected) {
        onDismiss()
        return
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize().rotaryFocus(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            ListHeader {
                Text("Pair New Device")
            }
        }

        when (connectionState) {
            is ConnectionState.Connecting -> {
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Connecting to ${(connectionState as ConnectionState.Connecting).deviceName ?: "iPhone"}…",
                            textAlign = TextAlign.Center,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            is ConnectionState.Bonding -> {
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "On iPhone: tap Pair, then Allow notifications",
                            textAlign = TextAlign.Center,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            else -> {
                when (val pairing = pairingState) {
                    is AncsService.PairingState.Advertising -> {
                        item {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "On iPhone open\nSettings → Bluetooth\nand tap:",
                                    textAlign = TextAlign.Center,
                                    fontSize = 12.sp,
                                    color = Color(0xFF9CA3AF)
                                )
                                Text(
                                    pairing.watchName ?: "this watch",
                                    textAlign = TextAlign.Center,
                                    fontSize = 14.sp,
                                    color = Color(0xFF60A5FA)
                                )
                            }
                        }
                        item {
                            Button(
                                onClick = { viewModel.stopPairing() },
                                modifier = Modifier.fillMaxWidth(0.9f)
                            ) {
                                Text("Stop Pairing", fontSize = 12.sp)
                            }
                        }
                    }

                    else -> {
                        item {
                            Text(
                                if (pairing is AncsService.PairingState.Failed) pairing.message
                                else "Makes this watch visible in iPhone Bluetooth settings. No iPhone app needed.",
                                textAlign = TextAlign.Center,
                                fontSize = 12.sp,
                                color = Color(0xFF9CA3AF),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        item {
                            Button(
                                onClick = { viewModel.startPairing() },
                                modifier = Modifier.fillMaxWidth(0.9f)
                            ) {
                                Text("Start Pairing", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }

        // Back button
        item {
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(0.9f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF374151)
                )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Back", fontSize = 12.sp)
                }
            }
        }
    }
}

private fun batteryColor(percent: Int?): Color = when {
    percent == null -> Color(0xFF9CA3AF)
    percent <= 20 -> Color(0xFFEF4444)
    percent <= 40 -> Color(0xFFFBBF24)
    else -> Color(0xFF34D399)
}

private fun clockText(status: ClockStatus): String = when {
    status.timeZoneMismatch == true -> "Time zone differs from iPhone"
    status.inSync -> "Clock matches iPhone"
    else -> {
        val seconds = kotlin.math.abs(status.driftSeconds).toLong()
        val amount = if (seconds >= 120) "${seconds / 60} min" else "$seconds s"
        if (status.driftSeconds > 0) "Watch $amount behind iPhone" else "Watch $amount ahead of iPhone"
    }
}
