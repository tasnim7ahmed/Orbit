package com.wearos.ancsbridge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.wearos.ancsbridge.settings.AppSettings
import com.wearos.ancsbridge.viewmodel.MainViewModel

/**
 * Like the Apple Watch app's Notifications screen: global behavior toggles, then
 * every iPhone app that has notified the watch. Tap an app to cycle
 * Alert → Quiet → Off; long-press to cycle its haptic.
 */
@Composable
fun SettingsScreen(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val toggles by viewModel.toggles.collectAsState()
    val apps by viewModel.knownApps.collectAsState()
    BackHandler(onBack = onDismiss)

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize().rotaryFocus(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item { ListHeader { Text("Settings") } }

        item {
            Toggle("Stack by app", "Group each app's notifications", toggles.stackByApp) {
                viewModel.setToggles(toggles.copy(stackByApp = it))
            }
        }
        item {
            Toggle("Auto-open Now Playing", "When iPhone starts playing", toggles.autoLaunchNowPlaying) {
                viewModel.setToggles(toggles.copy(autoLaunchNowPlaying = it))
            }
        }
        item {
            Toggle("Left-behind alert", "Buzz when iPhone disconnects", toggles.leftBehindAlert) {
                viewModel.setToggles(toggles.copy(leftBehindAlert = it))
            }
        }
        item {
            Toggle("Show missed", "Notifications from while away", toggles.showMissedWhileAway) {
                viewModel.setToggles(toggles.copy(showMissedWhileAway = it))
            }
        }

        item { ListHeader { Text("App notifications") } }

        if (apps.isEmpty()) {
            item {
                Text(
                    "Apps appear here after they send a notification",
                    fontSize = 12.sp,
                    color = Color(0xFF9CA3AF),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        } else {
            item {
                Text(
                    "Tap: alert mode · Hold: haptic",
                    fontSize = 11.sp,
                    color = Color(0xFF9CA3AF),
                    textAlign = TextAlign.Center
                )
            }
        }

        items(apps, key = { it.bundleId }) { app ->
            Button(
                onClick = { viewModel.setAppMode(app.bundleId, app.mode.next()) },
                onLongClick = { viewModel.setAppHaptic(app.bundleId, app.haptic.next()) },
                onLongClickLabel = "Change haptic",
                modifier = Modifier.fillMaxWidth(0.92f),
                colors = if (app.mode == AppSettings.AlertMode.OFF) {
                    ButtonDefaults.buttonColors(containerColor = Color(0xFF374151))
                } else {
                    ButtonDefaults.filledTonalButtonColors()
                }
            ) {
                Column {
                    Text(app.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${app.mode.label} · ${app.haptic.label} haptic",
                        fontSize = 11.sp,
                        color = when (app.mode) {
                            AppSettings.AlertMode.ALERT -> Color(0xFF34D399)
                            AppSettings.AlertMode.QUIET -> Color(0xFFFBBF24)
                            AppSettings.AlertMode.OFF -> Color(0xFF9CA3AF)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, secondary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SwitchButton(
        checked = checked,
        onCheckedChange = onChange,
        modifier = Modifier.fillMaxWidth(0.92f),
        label = { Text(label, fontSize = 13.sp, maxLines = 2) },
        secondaryLabel = { Text(secondary, fontSize = 11.sp, maxLines = 2) }
    )
}
