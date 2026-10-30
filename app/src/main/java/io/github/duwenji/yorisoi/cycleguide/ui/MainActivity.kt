package io.github.duwenji.yorisoi.cycleguide.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.duwenji.yorisoi.cycleguide.history.PastTalks
import io.github.duwenji.yorisoi.cycleguide.ways.WaySections
import io.github.duwenji.yorisoi.kit.geo.map.FrequentWaysScreen
import io.github.duwenji.yorisoi.kit.geo.map.WayActions
import io.github.duwenji.yorisoi.kit.geo.map.WayWords
import io.github.duwenji.yorisoi.kit.mood.MoodTheme
import io.github.duwenji.yorisoi.cycleguide.BuildConfig
import io.github.duwenji.yorisoi.cycleguide.CycleApp
import io.github.duwenji.yorisoi.cycleguide.diagnostics.RideExport
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
        enableEdgeToEdge()
        hasLocationPermission = isGranted(Manifest.permission.ACCESS_FINE_LOCATION) || isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!hasLocationPermission) requestPermissions()
        setContent {
            // The ecosystem's green, as SanpoGuide's with moods off; dark with the system.
            MoodTheme(null, systemDark = isSystemInDarkTheme()) {
                val state by RideService.state.collectAsStateWithLifecycle()
                val finishing by RideService.finishing.collectAsStateWithLifecycle()
                val settings by app.settings.settings.collectAsStateWithLifecycle()
                val station by app.stations.current.collectAsStateWithLifecycle()
                val lastLine by RideService.lastLine.collectAsStateWithLifecycle()
                val lastRide by app.history.latest.collectAsStateWithLifecycle()
                val rides by app.history.rides.collectAsStateWithLifecycle()
                // Tried again at the start and end of a ride, when OpenStreetMap had failed.
                val map = rememberMapChoice(settings.mapStyle, app.osmTiles, retry = state != null)
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var showHistory by rememberSaveable { mutableStateOf(false) }
                var showChannels by rememberSaveable { mutableStateOf(false) }
                var showWays by rememberSaveable { mutableStateOf(false) }
                val ways by app.ways.ways.collectAsStateWithLifecycle()
                val stations by app.stations.list.collectAsStateWithLifecycle()
                when {
                    // Nothing to read while moving (requirements S-1).
                    state == MotionState.MOVING -> RidingScreen()
                    // Not during a ride: lists are fetched at its start, never during it (API-002 取得の時期).
                    showChannels && state == null -> {
                        BackHandler { showChannels = false }
                        ChannelsScreen(app, onClose = { showChannels = false })
                    }
                    // Not during a ride (REQ-FREQUENT-WAYS F-18).
                    showWays && state == null -> {
                        BackHandler { showWays = false }
                        FrequentWaysScreen(
                            ways,
                            WayActions(
                                rename = app.ways::rename, save = app.ways::save, delete = app.ways::delete,
                                talksOn = { way -> WaySections.talksOn(way, app.history.since(System.currentTimeMillis() - PastTalks.WINDOW_MS)) },
                            ),
                            WayWords.RIDE,
                            onClose = { showWays = false },
                        )
                    }
                    showSettings -> {
                        BackHandler { showSettings = false }
                        SettingsScreen(
                            settings, onChange = app.settings::update, onClose = { showSettings = false }, exportRides = ::exportRides,
                            hasHistory = rides.isNotEmpty(), clearHistory = app::clearHistory,
                            stations = stations, current = station, riding = state != null, onOpenChannels = { showChannels = true },
                            onOpenWays = { showWays = true }.takeIf { state == null },
                        )
                    }
                    // Not during a ride: the history is for afterwards (S-2).
                    showHistory && state == null -> {
                        BackHandler { showHistory = false }
                        HistoryScreen(rides, app.history::ride, saveWay = { name, points, at -> app.ways.add(name, points, at) }, map = map, onClose = { showHistory = false })
                    }
                    else -> MainScreen(
                        riding = state != null,
                        finishing = finishing,
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
                        onHistory = { showHistory = true }.takeIf { state == null && rides.isNotEmpty() },
                        map = when {
                            state != null -> { modifier -> RideMap(app, map, modifier) }
                            else -> lastRide?.let { ride -> { modifier -> LastRideMap(ride, map, modifier) } }
                        },
                    )
                }
            }
        }
    }

    /** Writes every ride recorded in developer mode to [uri]; what to tell the developer. */
    private fun exportRides(uri: Uri): String = try {
        val json = RideExport.toJson(app.diagnostics.ids(), app.diagnostics::read, BuildConfig.VERSION_NAME, System.currentTimeMillis())
        contentResolver.openOutputStream(uri)?.use { it.write(json.toString(2).toByteArray()) } ?: error("No file to write")
        "走行の記録を書き出しました"
    } catch (e: Exception) {
        "書き出せませんでした: ${e.message}"
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
