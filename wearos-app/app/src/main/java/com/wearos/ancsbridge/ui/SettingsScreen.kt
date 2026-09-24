package com.wearos.ancsbridge.ui

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
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
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.wearos.ancsbridge.settings.AppSettings
import com.wearos.ancsbridge.viewmodel.MainViewModel
import java.util.Date

/**
 * Like the Apple Watch app's Notifications screen: how notifications behave overall,
 * then every iPhone app that has notified the watch. Tap an app to cycle
 * Alert, Quiet and Off; press and hold to cycle its vibration.
 */
@Composable
fun SettingsScreen(viewModel: MainViewModel, onPairNewDevice: () -> Unit, onDismiss: () -> Unit) {
    val toggles by viewModel.toggles.collectAsState()
    val apps by viewModel.knownApps.collectAsState()
    val listState = rememberTransformingLazyColumnState()
    val context = LocalContext.current
    // Follows the watch's 12/24-hour setting
    val timeFormat = remember { DateFormat.getTimeFormat(context) }
    BackHandler(onBack = onDismiss)

    ScreenScaffold(
        scrollState = listState,
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
                Toggle("Stack by app", "One group per app", toggles.stackByApp) {
                    viewModel.setToggles(toggles.copy(stackByApp = it))
                }
            }
            item {
                Toggle("Now Playing", "Opens with playback", toggles.autoLaunchNowPlaying) {
                    viewModel.setToggles(toggles.copy(autoLaunchNowPlaying = it))
                }
            }
            item {
                Toggle("Left behind", "Buzz if iPhone leaves", toggles.leftBehindAlert) {
                    viewModel.setToggles(toggles.copy(leftBehindAlert = it))
                }
            }
            item {
                Toggle("Show missed", "After reconnect", toggles.showMissedWhileAway) {
                    viewModel.setToggles(toggles.copy(showMissedWhileAway = it))
                }
            }
            item {
                Toggle("Off wrist", "No buzzing when not worn", toggles.quietOffWrist) {
                    viewModel.setToggles(toggles.copy(quietOffWrist = it))
                }
            }
            item {
                // The switch says what leaves the watch: song names go to Apple's search
                Toggle("Album art", "Looks up songs with Apple", toggles.albumArt) {
                    viewModel.setToggles(toggles.copy(albumArt = it))
                }
            }

            item {
                OutlinedButton(
                    onClick = onPairNewDevice,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Pair new iPhone") }
                )
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
                // Muted from a notification: the first tap lifts the mute instead of changing the mode
                val muted = app.isMuted()
                Button(
                    onClick = {
                        if (muted) viewModel.unmuteApp(app.bundleId)
                        else viewModel.setAppMode(app.bundleId, app.mode.next())
                    },
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
                            if (muted) "Muted until ${timeFormat.format(Date(app.mutedUntil))}"
                            else "${app.mode.label} · ${app.haptic.label}",
                            color = when {
                                muted -> MaterialTheme.colorScheme.secondary
                                app.mode == AppSettings.AlertMode.ALERT -> MaterialTheme.colorScheme.tertiary
                                app.mode == AppSettings.AlertMode.QUIET -> MaterialTheme.colorScheme.secondary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
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
