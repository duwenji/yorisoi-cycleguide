package io.github.duwenji.yorisoi.cycleguide.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.duwenji.yorisoi.cycleguide.CycleApp
import io.github.duwenji.yorisoi.cycleguide.companion.MotionState
import io.github.duwenji.yorisoi.cycleguide.ride.RideService

class MainActivity : ComponentActivity() {
    private val app get() = application as CycleApp
    private var hasLocationPermission by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        hasLocationPermission = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasLocationPermission = isGranted(Manifest.permission.ACCESS_FINE_LOCATION) || isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!hasLocationPermission) requestPermissions()
        setContent {
            MaterialTheme {
                val state by RideService.state.collectAsStateWithLifecycle()
                val settings by app.settings.settings.collectAsStateWithLifecycle()
                val station by app.stations.current.collectAsStateWithLifecycle()
                val lastLine by RideService.lastLine.collectAsStateWithLifecycle()
                var showSettings by rememberSaveable { mutableStateOf(false) }
                when {
                    // Nothing to read while moving (requirements S-1).
                    state == MotionState.MOVING -> RidingScreen()
                    showSettings -> {
                        BackHandler { showSettings = false }
                        SettingsScreen(settings, onChange = app.settings::update, onClose = { showSettings = false })
                    }
                    else -> MainScreen(
                        riding = state != null,
                        settings = settings,
                        channelName = station.displayName,
                        lastLine = lastLine,
                        hasLocationPermission = hasLocationPermission,
                        onRequestPermission = ::requestPermissions,
                        onStart = {
                            app.settings.update { it.copy(earphoneNoticeShown = true) }
                            RideService.start(this)
                        },
                        onStop = { RideService.stop(this) },
                        onSettings = { showSettings = true },
                    )
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
