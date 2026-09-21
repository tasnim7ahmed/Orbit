package com.wearos.ancsbridge.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.Text
import com.wearos.ancsbridge.ble.AmsProtocol
import com.wearos.ancsbridge.model.MediaState
import com.wearos.ancsbridge.viewmodel.MainViewModel
import kotlinx.coroutines.delay

/**
 * Now-playing remote for whatever is playing on the iPhone (Spotify, Apple Music,
 * YouTube, podcasts…) via Apple Media Service.
 */
@Composable
fun MediaScreen(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val media by viewModel.media.collectAsState()
    BackHandler(onBack = onDismiss)

    // Tick once a second while playing so the progress bar moves
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(media.isPlaying) {
        while (media.isPlaying) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
        now = SystemClock.elapsedRealtime()
    }

    // Digital Crown / rotating bezel = iPhone volume, like Apple Watch Now Playing
    val focusRequester = remember { FocusRequester() }
    var crownAccumulator by remember { mutableFloatStateOf(0f) }
    // Safe if the node is not attached yet: the crown just will not control volume
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

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
            // The extra command rows can push past a small screen; the crown drives
            // volume here, so this scrolls by swipe
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            if (media.available) media.playerName else "iPhone",
            fontSize = 11.sp,
            color = Color(0xFF9CA3AF)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            if (media.hasTrack) media.title.ifEmpty { "Unknown title" } else "Nothing playing",
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        if (media.artist.isNotEmpty()) {
            Text(
                media.artist,
                fontSize = 12.sp,
                color = Color(0xFF9CA3AF),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
        // Position in the player's queue, e.g. "3 of 21"
        media.queuePosition?.let {
            Text(it, fontSize = 10.sp, color = Color(0xFF6B7280), textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(8.dp))
        ProgressBar(media, now)
        Spacer(Modifier.height(10.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ControlButton(Icons.Rounded.SkipPrevious, "Previous", media, AmsProtocol.CMD_PREVIOUS_TRACK) {
                viewModel.sendMediaCommand(AmsProtocol.CMD_PREVIOUS_TRACK)
            }
            FilledIconButton(
                onClick = { viewModel.sendMediaCommand(AmsProtocol.CMD_TOGGLE_PLAY_PAUSE) },
                enabled = media.available,
                modifier = Modifier.size(52.dp)
            ) {
                Icon(
                    if (media.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (media.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(28.dp)
                )
            }
            ControlButton(Icons.Rounded.SkipNext, "Next", media, AmsProtocol.CMD_NEXT_TRACK) {
                viewModel.sendMediaCommand(AmsProtocol.CMD_NEXT_TRACK)
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ControlButton(Icons.Rounded.VolumeDown, "Volume down", media, AmsProtocol.CMD_VOLUME_DOWN) {
                viewModel.sendMediaCommand(AmsProtocol.CMD_VOLUME_DOWN)
            }
            Text(
                media.volume?.let { "${(it * 100).toInt()}%" } ?: "",
                fontSize = 11.sp,
                color = Color(0xFF9CA3AF),
                modifier = Modifier.width(34.dp),
                textAlign = TextAlign.Center
            )
            ControlButton(Icons.Rounded.VolumeUp, "Volume up", media, AmsProtocol.CMD_VOLUME_UP) {
                viewModel.sendMediaCommand(AmsProtocol.CMD_VOLUME_UP)
            }
        }

        // Everything below depends on what the current player actually offers, so these
        // appear for a podcast app and stay hidden for one that can't do them.
        ExtraCommandRow(media) { viewModel.sendMediaCommand(it) }
        RatingRow(media) { viewModel.sendMediaCommand(it) }
    }
}

/** Skip within the track, shuffle and repeat, for players that support them. */
@Composable
private fun ExtraCommandRow(media: MediaState, onCommand: (Int) -> Unit) {
    val shuffleOn = (media.shuffleMode ?: AmsProtocol.MODE_OFF) != AmsProtocol.MODE_OFF
    val repeatMode = media.repeatMode ?: AmsProtocol.MODE_OFF
    val buttons = listOfNotNull(
        offered(media, AmsProtocol.CMD_SKIP_BACKWARD) {
            ExtraButton(Icons.Rounded.FastRewind, "Skip back", onClick = { onCommand(AmsProtocol.CMD_SKIP_BACKWARD) })
        },
        offered(media, AmsProtocol.CMD_ADVANCE_SHUFFLE_MODE) {
            ExtraButton(
                Icons.Rounded.Shuffle,
                if (shuffleOn) "Shuffle on" else "Shuffle off",
                active = shuffleOn,
                onClick = { onCommand(AmsProtocol.CMD_ADVANCE_SHUFFLE_MODE) }
            )
        },
        offered(media, AmsProtocol.CMD_ADVANCE_REPEAT_MODE) {
            ExtraButton(
                if (repeatMode == AmsProtocol.MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                when (repeatMode) {
                    AmsProtocol.MODE_ONE -> "Repeat track"
                    AmsProtocol.MODE_ALL -> "Repeat all"
                    else -> "Repeat off"
                },
                active = repeatMode != AmsProtocol.MODE_OFF,
                onClick = { onCommand(AmsProtocol.CMD_ADVANCE_REPEAT_MODE) }
            )
        },
        offered(media, AmsProtocol.CMD_SKIP_FORWARD) {
            ExtraButton(Icons.Rounded.FastForward, "Skip forward", onClick = { onCommand(AmsProtocol.CMD_SKIP_FORWARD) })
        }
    )
    if (buttons.isEmpty()) return
    Spacer(Modifier.height(2.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        buttons.forEach { it() }
    }
}

/** Like and dislike, which Apple Music and podcast apps offer. */
@Composable
private fun RatingRow(media: MediaState, onCommand: (Int) -> Unit) {
    val buttons = listOfNotNull(
        offered(media, AmsProtocol.CMD_LIKE_TRACK) {
            ExtraButton(Icons.Rounded.ThumbUp, "Like", onClick = { onCommand(AmsProtocol.CMD_LIKE_TRACK) })
        },
        offered(media, AmsProtocol.CMD_DISLIKE_TRACK) {
            ExtraButton(Icons.Rounded.ThumbDown, "Dislike", onClick = { onCommand(AmsProtocol.CMD_DISLIKE_TRACK) })
        }
    )
    if (buttons.isEmpty()) return
    Spacer(Modifier.height(2.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        buttons.forEach { it() }
    }
}

/**
 * These buttons appear only once the iPhone has said the player supports the command,
 * rather than optimistically like the transport controls: a dead Shuffle button is worse
 * than none, and the supported list arrives as soon as a player is active.
 */
private fun offered(media: MediaState, command: Int, button: @Composable () -> Unit): (@Composable () -> Unit)? =
    if (media.available && command in media.supportedCommands) button else null

@Composable
private fun ExtraButton(
    icon: ImageVector,
    description: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(
            icon,
            contentDescription = description,
            modifier = Modifier.size(18.dp),
            tint = if (active) Color(0xFF60A5FA) else Color(0xFF9CA3AF)
        )
    }
}

@Composable
private fun ControlButton(
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
        modifier = Modifier.size(40.dp)
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun ProgressBar(media: MediaState, now: Long) {
    val duration = media.durationSec
    val position = media.positionAt(now)
    val fraction = if (duration != null && duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF374151))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(Color(0xFF60A5FA))
            )
        }
        if (duration != null && media.hasTrack) {
            Text(
                "${formatTime(position)} / ${formatTime(duration)}",
                fontSize = 10.sp,
                color = Color(0xFF9CA3AF)
            )
        }
    }
}

private fun formatTime(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/** Rotary pixels per iPhone volume step (~one crown detent / bezel click). */
private const val CROWN_STEP_PX = 48f
