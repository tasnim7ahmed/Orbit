package com.wearos.ancsbridge.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.wearos.ancsbridge.ble.AmsProtocol
import com.wearos.ancsbridge.model.MediaState
import com.wearos.ancsbridge.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Now Playing for whatever the iPhone is playing, over Apple Media Service.
 *
 * Laid out the way the watch's own media control is: what is playing at the top, one
 * big play control in the middle, and the less-used commands smaller and below.
 */
@Composable
fun MediaScreen(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val media by viewModel.media.collectAsState()
    val artwork by viewModel.artwork.collectAsState()
    BackHandler(onBack = onDismiss)

    // Digital Crown / rotating bezel = iPhone volume, like Apple Watch Now Playing
    val focusRequester = remember { FocusRequester() }
    var crownAccumulator by remember { mutableFloatStateOf(0f) }
    // Safe if the node is not attached yet: the crown just will not control volume
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    ScreenScaffold {
        Box(Modifier.fillMaxSize()) {
            // Album art fills the round screen behind the controls, dimmed so text stays readable
            artwork?.let { art ->
                val image = remember(art) { art.bitmap.asImageBitmap() }
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background.copy(alpha = ART_SCRIM_ALPHA))
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .onRotaryScrollEvent { event ->
                        crownAccumulator += event.verticalScrollPixels
                        // One volume step per notch-ish of rotation
                        while (crownAccumulator > CROWN_STEP_PX) {
                            crownAccumulator -= CROWN_STEP_PX
                            viewModel.sendMediaCommand(AmsProtocol.CMD_VOLUME_UP)
                        }
                        while (crownAccumulator < -CROWN_STEP_PX) {
                            crownAccumulator += CROWN_STEP_PX
                            viewModel.sendMediaCommand(AmsProtocol.CMD_VOLUME_DOWN)
                        }
                        true
                    }
                    .focusRequester(focusRequester)
                    .focusable()
                    // Players that offer extra commands make this taller than the screen;
                    // the crown is taken by volume, so this scrolls by swipe
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    media.playerName.ifEmpty { "iPhone" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (media.hasTrack) media.title.ifEmpty { "Unknown title" } else "Nothing playing",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                if (media.artist.isNotEmpty()) {
                    Text(
                        media.artist,
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
                media.queuePosition?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(Modifier.height(8.dp))
                ProgressBar(media)
                Spacer(Modifier.height(10.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TransportButton(Icons.Rounded.SkipPrevious, "Previous", media, AmsProtocol.CMD_PREVIOUS_TRACK) {
                        viewModel.sendMediaCommand(AmsProtocol.CMD_PREVIOUS_TRACK)
                    }
                    FilledIconButton(
                        onClick = { viewModel.sendMediaCommand(AmsProtocol.CMD_TOGGLE_PLAY_PAUSE) },
                        enabled = media.available,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            if (media.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (media.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    TransportButton(Icons.Rounded.SkipNext, "Next", media, AmsProtocol.CMD_NEXT_TRACK) {
                        viewModel.sendMediaCommand(AmsProtocol.CMD_NEXT_TRACK)
                    }
                }

                // Everything below depends on what the current player offers, so these show
                // for a podcast app and stay hidden for one that cannot do them.
                ExtraCommandRow(media) { viewModel.sendMediaCommand(it) }

                Spacer(Modifier.height(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SmallButton(Icons.Rounded.VolumeDown, "Volume down") {
                        viewModel.sendMediaCommand(AmsProtocol.CMD_VOLUME_DOWN)
                    }
                    Text(
                        media.volume?.let { "${(it * 100).toInt()}%" } ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(36.dp),
                        textAlign = TextAlign.Center
                    )
                    SmallButton(Icons.Rounded.VolumeUp, "Volume up") {
                        viewModel.sendMediaCommand(AmsProtocol.CMD_VOLUME_UP)
                    }
                }
            }
        }
    }
}

@Composable
private fun TransportButton(
    icon: ImageVector,
    description: String,
    media: MediaState,
    command: Int,
    onClick: () -> Unit
) {
    // Before the iPhone reports supported commands, assume everything works
    val supported = media.supportedCommands.isEmpty() || command in media.supportedCommands
    IconButton(
        onClick = onClick,
        enabled = media.available && supported,
        modifier = Modifier.size(44.dp)
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(24.dp))
    }
}

/** Skip within the track, shuffle and repeat, for players that support them. */
@Composable
private fun ExtraCommandRow(media: MediaState, onCommand: (Int) -> Unit) {
    val shuffleOn = (media.shuffleMode ?: AmsProtocol.MODE_OFF) != AmsProtocol.MODE_OFF
    val repeatMode = media.repeatMode ?: AmsProtocol.MODE_OFF
    val buttons = listOfNotNull(
        offered(media, AmsProtocol.CMD_SKIP_BACKWARD) {
            SmallButton(Icons.Rounded.FastRewind, "Skip back") { onCommand(AmsProtocol.CMD_SKIP_BACKWARD) }
        },
        offered(media, AmsProtocol.CMD_ADVANCE_SHUFFLE_MODE) {
            SmallButton(
                Icons.Rounded.Shuffle,
                if (shuffleOn) "Shuffle on" else "Shuffle off",
                active = shuffleOn
            ) { onCommand(AmsProtocol.CMD_ADVANCE_SHUFFLE_MODE) }
        },
        offered(media, AmsProtocol.CMD_ADVANCE_REPEAT_MODE) {
            SmallButton(
                if (repeatMode == AmsProtocol.MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                when (repeatMode) {
                    AmsProtocol.MODE_ONE -> "Repeat track"
                    AmsProtocol.MODE_ALL -> "Repeat all"
                    else -> "Repeat off"
                },
                active = repeatMode != AmsProtocol.MODE_OFF
            ) { onCommand(AmsProtocol.CMD_ADVANCE_REPEAT_MODE) }
        },
        offered(media, AmsProtocol.CMD_SKIP_FORWARD) {
            SmallButton(Icons.Rounded.FastForward, "Skip forward") { onCommand(AmsProtocol.CMD_SKIP_FORWARD) }
        },
        offered(media, AmsProtocol.CMD_LIKE_TRACK) {
            SmallButton(Icons.Rounded.ThumbUp, "Like") { onCommand(AmsProtocol.CMD_LIKE_TRACK) }
        },
        offered(media, AmsProtocol.CMD_DISLIKE_TRACK) {
            SmallButton(Icons.Rounded.ThumbDown, "Dislike") { onCommand(AmsProtocol.CMD_DISLIKE_TRACK) }
        }
    )
    if (buttons.isEmpty()) return
    Spacer(Modifier.height(4.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        buttons.forEach { it() }
    }
}

/**
 * These appear only once the iPhone says the player supports the command, rather than
 * optimistically like the transport controls: a dead Shuffle button is worse than none,
 * and the supported list arrives as soon as a player is active.
 */
private fun offered(media: MediaState, command: Int, button: @Composable () -> Unit): (@Composable () -> Unit)? =
    if (media.available && command in media.supportedCommands) button else null

@Composable
private fun SmallButton(
    icon: ImageVector,
    description: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
    }
}

/** Ticks once a second while playing; only this bar recomposes, not the whole screen. */
@Composable
private fun ProgressBar(media: MediaState) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(media.isPlaying, media.elapsedReportedAt) {
        now = SystemClock.elapsedRealtime()
        while (media.isPlaying) {
            delay(1000)
            now = SystemClock.elapsedRealtime()
        }
    }
    val duration = media.durationSec
    val position = media.positionAt(now)
    val fraction = if (duration != null && duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        if (duration != null && media.hasTrack) {
            Text(
                "${formatTime(position)} / ${formatTime(duration)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

private fun formatTime(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    return String.format(Locale.getDefault(), "%d:%02d", total / 60, total % 60)
}

/** Rotary pixels per iPhone volume step (~one crown detent / bezel click). */
private const val CROWN_STEP_PX = 48f

/** How much of the background colour covers the album art. */
private const val ART_SCRIM_ALPHA = 0.72f

@Suppress("unused")
private val unusedColorReference: Color = Color.Transparent
