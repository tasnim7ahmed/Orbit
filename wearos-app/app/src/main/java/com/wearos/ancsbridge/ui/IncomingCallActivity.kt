package com.wearos.ancsbridge.ui

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import com.wearos.ancsbridge.R
import com.wearos.ancsbridge.ancs.AncsService
import com.wearos.ancsbridge.ancs.NotificationActionReceiver
import com.wearos.ancsbridge.ble.AncsConstants
import com.wearos.ancsbridge.ui.theme.AncsBridgeTheme

/**
 * Full-screen incoming call activity shown when ANCS reports an incoming call.
 * Shows caller name with Answer/Decline using Lucide icons. The Ringer keeps vibrating
 * until one is pressed (or the side button silences it).
 * Auto-dismisses when ANCS sends a REMOVE event for the call notification.
 */
class IncomingCallActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_NOTIFICATION_UID = "notification_uid"
        const val EXTRA_APP_NAME = "app_name"
        const val ACTION_CALL_ENDED = "com.wearos.ancsbridge.CALL_ENDED"
        const val ACTION_CALL_ANSWERED = "com.wearos.ancsbridge.CALL_ANSWERED"
        const val ACTION_CALLER_NAME_UPDATED = "com.wearos.ancsbridge.CALLER_NAME_UPDATED"

        /** Auto-dismiss after this many ms if no ANCS event arrives */
        private const val AUTO_DISMISS_TIMEOUT_MS = 45_000L
        /** After call is answered on iPhone, auto-dismiss after this many ms */
        private const val ACTIVE_CALL_DISMISS_MS = 5_000L
    }

    private var notificationUid: Long = -1
    private var callerNameState = mutableStateOf("Unknown Caller")
    /** true = call was answered on iPhone, show "Active on iPhone" UI */
    private var callAnsweredOnPhone = mutableStateOf(false)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val callEndedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_CALL_ENDED -> finish()
                ACTION_CALL_ANSWERED -> {
                    // Call was answered on iPhone — transition to "active call" UI
                    callAnsweredOnPhone.value = true
                    // Auto-dismiss after a few seconds
                    handler.postDelayed({ finish() }, ACTIVE_CALL_DISMISS_MS)
                }
                ACTION_CALLER_NAME_UPDATED -> {
                    val name = intent.getStringExtra(EXTRA_CALLER_NAME)
                    if (!name.isNullOrEmpty()) {
                        callerNameState.value = name
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over lock screen and turn screen on
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        // Dismiss the keyguard so the call screen is interactive
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        keyguardManager.requestDismissKeyguard(this, null)

        // Keep screen on while call screen is showing
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        callerNameState.value = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "Unknown Caller"
        val appName = intent.getStringExtra(EXTRA_APP_NAME) ?: "Phone"
        notificationUid = intent.getLongExtra(EXTRA_NOTIFICATION_UID, -1)

        val filter = IntentFilter().apply {
            addAction(ACTION_CALL_ENDED)
            addAction(ACTION_CALL_ANSWERED)
            addAction(ACTION_CALLER_NAME_UPDATED)
        }
        registerReceiver(callEndedReceiver, filter, RECEIVER_NOT_EXPORTED)

        // Safety timeout: auto-dismiss if no ANCS REMOVE/MODIFIED arrives
        handler.postDelayed({ finish() }, AUTO_DISMISS_TIMEOUT_MS)

        setContent {
            AncsBridgeTheme {
                val callerName by callerNameState
                val answeredOnPhone by callAnsweredOnPhone

                if (answeredOnPhone) {
                    CallActiveOnPhoneScreen(
                        callerName = callerName,
                        onDismiss = { finish() }
                    )
                } else {
                    IncomingCallScreen(
                        callerName = callerName,
                        appName = appName,
                        onAnswer = {
                            performAction(AncsConstants.ACTION_POSITIVE)
                            finish()
                        },
                        onDecline = {
                            performAction(AncsConstants.ACTION_NEGATIVE)
                            finish()
                        }
                    )
                }
            }
        }
    }

    /**
     * Pressing the side button / leaving the call screen silences the ringing
     * without declining — like pressing the side button on an Apple Watch.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        startService(Intent(this, AncsService::class.java).setAction(AncsService.ACTION_SILENCE_RING))
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(callEndedReceiver)
        } catch (_: IllegalArgumentException) { }
    }

    private fun performAction(actionId: Int) {
        if (notificationUid == -1L) return
        val intent = Intent(this, AncsService::class.java).apply {
            action = AncsService.ACTION_PERFORM_NOTIFICATION_ACTION
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_UID, notificationUid)
            putExtra(AncsService.EXTRA_ACTION_ID, actionId)
        }
        startService(intent)
    }
}


@Composable
fun IncomingCallScreen(
    callerName: String,
    appName: String,
    onAnswer: () -> Unit,
    onDecline: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            appName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            maxLines = 1
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            callerName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            "Incoming call",
            style = MaterialTheme.typography.bodyExtraSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledIconButton(
                onClick = onDecline,
                modifier = Modifier.size(52.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_call_decline),
                    contentDescription = "Decline",
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(24.dp))

            FilledIconButton(
                onClick = onAnswer,
                modifier = Modifier.size(52.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                )
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_call_answer),
                    contentDescription = "Answer",
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
fun CallActiveOnPhoneScreen(
    callerName: String,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_call_answer),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.tertiary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            callerName,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            "Active on iPhone",
            style = MaterialTheme.typography.bodyExtraSmall,
            color = MaterialTheme.colorScheme.tertiary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(0.7f),
            colors = ButtonDefaults.filledTonalButtonColors(),
            label = { Text("Dismiss") }
        )
    }
}
