package com.wearos.ancsbridge.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
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

    // AppScaffold puts the clock at the top of every screen, the way Wear OS expects
    AppScaffold {
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
                onPairNewDevice = {
                    showSettings = false
                    showPairScreen = true
                },
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
    val listState = rememberTransformingLazyColumnState()
    val connected = connectionState is ConnectionState.Connected

    ScreenScaffold(
        scrollState = listState,
        // The screen's one destructive or primary action sits on the bottom edge
        edgeButton = {
            if (connected) {
                EdgeButton(
                    onClick = { viewModel.disconnect() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text("Disconnect")
                }
            } else {
                EdgeButton(onClick = onPairNewDevice) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pair iPhone")
                    }
                }
            }
        }
    ) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item { ListHeader { Text("Orbit") } }

            when (connectionState) {
                is ConnectionState.Connected -> {
                    val deviceName = (connectionState as ConnectionState.Connected).deviceName ?: "iPhone"

                    item {
                        StatusCard(
                            icon = Icons.Rounded.CheckCircle,
                            iconTint = MaterialTheme.colorScheme.tertiary,
                            title = "Connected",
                            subtitle = "Notifications active"
                        )
                    }

                    item {
                        StatusCard(
                            icon = Icons.Rounded.PhoneIphone,
                            iconTint = MaterialTheme.colorScheme.primary,
                            title = deviceName,
                            subtitle = battery?.let { "Battery $it%" } ?: "Linked over Bluetooth",
                            subtitleColor = batteryColor(battery)
                        )
                    }

                    clock?.let { status ->
                        item {
                            InfoRow(
                                icon = Icons.Rounded.Schedule,
                                iconTint = if (status.inSync) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.secondary,
                                text = clockText(status)
                            )
                        }
                    }

                    item {
                        FilledTonalButton(
                            onClick = onOpenMedia,
                            modifier = Modifier.fillMaxWidth(),
                            icon = {
                                Icon(
                                    Icons.Rounded.MusicNote,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            label = {
                                Text(
                                    if (media.hasTrack) media.title else "Now Playing",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            secondaryLabel = if (media.hasTrack && media.artist.isNotEmpty()) {
                                { Text(media.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            } else null
                        )
                    }

                    item {
                        Button(
                            onClick = { viewModel.clearAllNotifications() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.filledVariantButtonColors(),
                            icon = {
                                Icon(
                                    Icons.Rounded.ClearAll,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            label = { Text("Clear all") }
                        )
                    }

                    item { SettingsButton(onOpenSettings) }
                }

                is ConnectionState.Connecting, is ConnectionState.Bonding -> {
                    item {
                        BusyCard(
                            when (connectionState) {
                                is ConnectionState.Connecting ->
                                    "Connecting to ${(connectionState as ConnectionState.Connecting).deviceName ?: "iPhone"}"
                                is ConnectionState.Bonding ->
                                    "Pairing with ${(connectionState as ConnectionState.Bonding).deviceName ?: "iPhone"}"
                                else -> "Connecting"
                            }
                        )
                    }
                    item { SettingsButton(onOpenSettings) }
                }

                else -> {
                    item {
                        StatusCard(
                            icon = Icons.Rounded.BluetoothDisabled,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            title = if (hasBondedIPhone) "Not in range" else "No iPhone yet",
                            subtitle = when {
                                connectionState is ConnectionState.Error ->
                                    (connectionState as ConnectionState.Error).message
                                hasBondedIPhone -> "Reconnects on its own"
                                else -> "Pair to start mirroring"
                            }
                        )
                    }
                    if (hasBondedIPhone) {
                        item {
                            Button(
                                onClick = { viewModel.startService() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.filledVariantButtonColors(),
                                label = { Text("Reconnect now") }
                            )
                        }
                    }
                    item { SettingsButton(onOpenSettings) }
                }
            }
        }
    }
}

/** States one thing plainly: an icon, a title, and a line under it. */
@Composable
private fun StatusCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Card(
        onClick = { },
        enabled = false,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = subtitleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** A quieter line for secondary facts, such as the clock check. */
@Composable
private fun InfoRow(icon: ImageVector, iconTint: Color, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyExtraSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2
        )
    }
}

@Composable
private fun BusyCard(text: String) {
    Card(
        onClick = { },
        enabled = false,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 3)
        }
    }
}

@Composable
private fun SettingsButton(onOpenSettings: () -> Unit) {
    Button(
        onClick = onOpenSettings,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(),
        icon = { Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(20.dp)) },
        label = { Text("Settings") }
    )
}

@SuppressLint("MissingPermission")
@Composable
fun PairNewDeviceScreen(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val connectionState by viewModel.connectionState.collectAsState()
    val pairingState by viewModel.pairingState.collectAsState()
    val listState = rememberTransformingLazyColumnState()

    // Auto-dismiss when connected
    if (connectionState is ConnectionState.Connected) {
        onDismiss()
        return
    }

    val advertising = pairingState as? AncsService.PairingState.Advertising

    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(
                onClick = { if (advertising != null) viewModel.stopPairing() else viewModel.startPairing() },
                colors = if (advertising != null) {
                    ButtonDefaults.filledTonalButtonColors()
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text(if (advertising != null) "Stop" else "Start pairing")
            }
        }
    ) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item { ListHeader { Text("Pair iPhone") } }

            when {
                connectionState is ConnectionState.Connecting -> item {
                    BusyCard("Connecting to ${(connectionState as ConnectionState.Connecting).deviceName ?: "iPhone"}")
                }
                connectionState is ConnectionState.Bonding -> item {
                    BusyCard("On iPhone: tap Pair, then Allow notifications")
                }
                advertising != null -> item {
                    Card(
                        onClick = { },
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "On iPhone open Settings, then Bluetooth, and tap",
                                style = MaterialTheme.typography.bodyExtraSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                advertising.watchName ?: "this watch",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                else -> item {
                    Text(
                        (pairingState as? AncsService.PairingState.Failed)?.message
                            ?: "Makes this watch visible in the iPhone's Bluetooth settings. No iPhone app needed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                    )
                }
            }

            item {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.childButtonColors(),
                    label = { Text("Back") }
                )
            }
        }
    }
}

@Composable
private fun batteryColor(percent: Int?) = when {
    percent == null -> MaterialTheme.colorScheme.onSurfaceVariant
    percent <= 20 -> MaterialTheme.colorScheme.error
    percent <= 40 -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.tertiary
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
