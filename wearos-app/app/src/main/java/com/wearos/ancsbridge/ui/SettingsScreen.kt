package com.wearos.ancsbridge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.wearos.ancsbridge.settings.AppSettings
import com.wearos.ancsbridge.viewmodel.MainViewModel

/**
 * Like the Apple Watch app's Notifications screen: how notifications behave overall,
 * then every iPhone app that has notified the watch. Tap an app to cycle
 * Alert, Quiet and Off; press and hold to cycle its vibration.
 */
@Composable
fun SettingsScreen(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val toggles by viewModel.toggles.collectAsState()
    val apps by viewModel.knownApps.collectAsState()
    val listState = rememberTransformingLazyColumnState()
    BackHandler(onBack = onDismiss)

    ScreenScaffold(
        scrollState = listState,
        contentPadding = ScreenPadding,
        edgeButton = {
            EdgeButton(onClick = onDismiss, colors = ButtonDefaults.filledTonalButtonColors()) {
                Text("Done")
            }
        }
    ) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item { ListHeader { Text("Settings") } }

            item {
                Toggle("Stack by app", "Group each app's notifications", toggles.stackByApp) {
                    viewModel.setToggles(toggles.copy(stackByApp = it))
                }
            }
            item {
                Toggle("Open Now Playing", "When the iPhone starts playing", toggles.autoLaunchNowPlaying) {
                    viewModel.setToggles(toggles.copy(autoLaunchNowPlaying = it))
                }
            }
            item {
                Toggle("Left-behind alert", "Buzz when the iPhone goes out of range", toggles.leftBehindAlert) {
                    viewModel.setToggles(toggles.copy(leftBehindAlert = it))
                }
            }
            item {
                Toggle("Show missed", "Notifications from while away", toggles.showMissedWhileAway) {
                    viewModel.setToggles(toggles.copy(showMissedWhileAway = it))
                }
            }

            item { ListSubHeader { Text("Apps") } }

            item {
                Text(
                    if (apps.isEmpty()) "Apps appear here once they notify you"
                    else "Tap to change alerts, hold to change vibration",
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }

            items(apps.size, key = { apps[it].bundleId }) { index ->
                val app = apps[index]
                val off = app.mode == AppSettings.AlertMode.OFF
                Button(
                    onClick = { viewModel.setAppMode(app.bundleId, app.mode.next()) },
                    onLongClick = { viewModel.setAppHaptic(app.bundleId, app.haptic.next()) },
                    onLongClickLabel = "Change vibration",
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (off) ButtonDefaults.childButtonColors()
                    else ButtonDefaults.filledTonalButtonColors(),
                    label = {
                        Text(app.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    secondaryLabel = {
                        Text(
                            "${app.mode.label} · ${app.haptic.label}",
                            color = when (app.mode) {
                                AppSettings.AlertMode.ALERT -> MaterialTheme.colorScheme.tertiary
                                AppSettings.AlertMode.QUIET -> MaterialTheme.colorScheme.secondary
                                AppSettings.AlertMode.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun Toggle(label: String, secondary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SwitchButton(
        checked = checked,
        onCheckedChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label, maxLines = 2) },
        secondaryLabel = { Text(secondary, maxLines = 2) }
    )
}
