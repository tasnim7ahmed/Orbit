package com.wearos.ancsbridge.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.ContextCompat
import com.wearos.ancsbridge.ui.theme.AncsBridgeTheme
import com.wearos.ancsbridge.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_OPEN = "open"
        private const val OPEN_MEDIA = "media"

        // NEW_TASK (+ CLEAR_TOP below) is required: these open Orbit from a service, tile,
        // complication or notification, outside any task, and must reuse the open screen
        @SuppressLint("WearRecents")
        fun launchIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Opens straight into Now Playing (tile, complication, ongoing activity, auto-launch). */
        @SuppressLint("WearRecents")
        fun openMediaIntent(context: Context): Intent =
            launchIntent(context)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_OPEN, OPEN_MEDIA)
    }

    private val viewModel: MainViewModel by viewModels()

    /** Bumped each time an intent asks for Now Playing, so MainScreen reacts even when already open */
    private val openMediaRequests = mutableIntStateOf(0)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            viewModel.startService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissionsIfNeeded()
        handleIntent(intent)

        setContent {
            AncsBridgeTheme {
                MainScreen(viewModel = viewModel, openMediaRequests = openMediaRequests.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_OPEN) == OPEN_MEDIA) {
            openMediaRequests.intValue++
        }
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.POST_NOTIFICATIONS,
        )

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isEmpty()) {
            viewModel.startService()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}
