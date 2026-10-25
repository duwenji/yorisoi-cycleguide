package io.github.duwenji.yorisoi.cycleguide.ride

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import io.github.duwenji.yorisoi.cycleguide.CycleApp
import io.github.duwenji.yorisoi.cycleguide.R
import io.github.duwenji.yorisoi.cycleguide.companion.Ahead
import io.github.duwenji.yorisoi.cycleguide.companion.AheadSpot
import io.github.duwenji.yorisoi.cycleguide.companion.Lines
import io.github.duwenji.yorisoi.cycleguide.companion.Motion
import io.github.duwenji.yorisoi.cycleguide.companion.MotionState
import io.github.duwenji.yorisoi.cycleguide.companion.QuietZones
import io.github.duwenji.yorisoi.cycleguide.companion.RideDecider
import io.github.duwenji.yorisoi.cycleguide.companion.RideDecision
import io.github.duwenji.yorisoi.cycleguide.companion.RideSession
import io.github.duwenji.yorisoi.cycleguide.companion.RideSituation
import io.github.duwenji.yorisoi.cycleguide.companion.SpeechBudget
import io.github.duwenji.yorisoi.cycleguide.guide.Line
import io.github.duwenji.yorisoi.cycleguide.guide.Narrator
import io.github.duwenji.yorisoi.cycleguide.prompt.Prompts
import io.github.duwenji.yorisoi.kit.channel.Station
import io.github.duwenji.yorisoi.cycleguide.style.StyleItem
import io.github.duwenji.yorisoi.cycleguide.ui.MainActivity
import io.github.duwenji.yorisoi.kit.geo.LatLon
import io.github.duwenji.yorisoi.kit.geo.RouteGeometry
import io.github.duwenji.yorisoi.kit.geo.weather.Weather
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.io.IOException

/**
 * Ride mode: follows the ride in the foreground and lets the companion talk (architecture.md 図 1・図 2).
 * While the rider moves, the notification says only that a ride is on, with no buttons, and the
 * screen shows nothing to read (requirements S-1, S-2).
 */
class RideService : LifecycleService() {
    private val app get() = application as CycleApp
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val motion = Motion { app.settings.settings.value.values }
    private val earphones by lazy { EarphoneWatch(this) }

    private var session: RideSession? = null
    private var lastLocation: Location? = null
    private var heading: Double? = null
    private var weather: Weather? = null
    private var weatherFetchedAt = 0L
    private var earphoneNoticed = false

    /** One line at a time; a decision that comes while the companion is talking waits for the next tick. */
    private val talking = Mutex()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::onLocation)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            session?.let { app.speaker.speak(Lines.finish(it.distanceM)) }
            stopSelf()
            return START_NOT_STICKY
        }
        if (session == null) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, rideNotification(MotionState.SIGNAL_STOP, Lines.start()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
            val now = System.currentTimeMillis()
            session = RideSession(now).also { it.pendingGreeting = app.stations.current.value.manifest.greeting }
            earphones.start()
            startLocationUpdates()
            startTicker()
            _state.value = MotionState.SIGNAL_STOP
        }
        return START_STICKY
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        earphones.stop()
        session = null
        app.heading = null
        _state.value = null
        _lastLine.value = null
        super.onDestroy()
    }

    @SuppressLint("MissingPermission") // Checked by MainActivity before starting the service.
    private fun startLocationUpdates() {
        // No distance filter: fixes keep coming while the rider stands at a light, so Motion can tell
        // a stop (speed 0) from a gap without fixes (a tunnel).
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_MS).build()
        fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    /** Location updates stop while the rider stands still, so lights and rests need a clock. */
    private fun startTicker() = lifecycleScope.launch {
        while (isActive) {
            delay(TICK_MS)
            lastLocation?.let { refreshWeather(it) }
            maybeTalk()
        }
    }

    private fun onLocation(location: Location) {
        val s = session ?: return
        lastLocation = location
        // The device's clock, as maybeTalk() reads the state with (a fix's own time can differ).
        motion.onFix(
            System.currentTimeMillis(),
            location.speed.takeIf { location.hasSpeed() },
            location.accuracy.takeIf { location.hasAccuracy() },
        )
        s.onLocation(LatLon(location.latitude, location.longitude))
        heading = Ahead.heading(location.bearing.takeIf { location.hasBearing() }, motion.speedMps, s.track)
        app.heading = heading
        lifecycleScope.launch {
            try {
                app.spots.onLocation(location)
            } catch (e: IOException) {
                Log.w(TAG, "Spot search failed", e)
            }
            if (weatherFetchedAt == 0L) refreshWeather(location)
            maybeTalk()
        }
    }

    private suspend fun maybeTalk() {
        val s = session ?: return
        if (!talking.tryLock()) return
        try {
            if (app.speaker.isSpeaking) return
            val now = System.currentTimeMillis()
            val state = motion.state(now)
            if (state != _state.value) onStateChanged(s, state)
            val here = lastLocation?.let { LatLon(it.latitude, it.longitude) }
            s.prune(now, here)

            if (EarphoneRule.shouldNotice(earphones.connected, state, earphoneNoticed)) {
                earphoneNoticed = true
                say(Line(Lines.EARPHONE_NOTICE), state)
                return
            }

            val values = app.settings.settings.value.values
            val station = app.stations.current.value
            val way = heading
            val ahead = if (here == null || way == null) emptyList() else app.spots.spots.value
                .filter { Ahead.isAhead(here, way, LatLon(it.lat, it.lon), values[StyleItem.AHEAD_M].toDouble()) }
                .map { AheadSpot(it, RouteGeometry.distanceM(here, LatLon(it.lat, it.lon)), s.alreadyTalked(it)) }
            val situation = RideSituation(
                now = now, values = values, level = station.talkLevel, events = station.events,
                skippedCategories = station.skippedCategories,
                state = state, speedMps = motion.speedMps, stoppedMs = motion.stoppedMs(now),
                quiet = QuietZones.isQuiet(state, here, motion.speedMps, app.spots.trafficSignals.value, values[StyleItem.QUIET_SPEED_KMH]),
                ahead = ahead, deferred = s.deferred, weather = weather,
                distanceM = s.distanceM, lastSpokeAt = s.lastSpokeAt, lastSpotTalkAt = s.lastSpotTalkAt,
                lastMilestoneDistanceM = s.lastMilestoneDistanceM, restRemarked = s.restRemarked,
                sunsetWarned = s.sunsetWarned, weatherWarnedAt = s.weatherWarnedAt,
                pendingGreeting = s.pendingGreeting, justSwitched = s.justSwitched(now),
            )
            val decision = RideDecider.decide(situation)
            if (decision != RideDecision.Nothing) Log.i(TAG, "$state ${motion.speedMps} quiet=${situation.quiet} ahead=${ahead.size}: $decision")
            carryOut(s, decision, now)
        } finally {
            talking.unlock()
        }
    }

    /**
     * Says what [decision] calls for: the AI's line where there is one (spots, rests, milestones)
     * if it fits the length allowed when it comes back, else the fixed line. A spot line too long
     * for riding is kept for the next stop. Safety and greetings are always the fixed lines.
     */
    private suspend fun carryOut(s: RideSession, decision: RideDecision, now: Long) {
        val station = app.stations.current.value
        val fixed = Lines.of(decision)?.let { Line(it) }
        val line: Line? = when (decision) {
            is RideDecision.Spot -> {
                val written = app.narrator.spot(decision.poi, decision.distanceM, riding = true, station, decision.maxChars)
                // The rider may have stopped while it was written: the length allowed now counts.
                if (written != null && !SpeechBudget.fits(written.text, maxCharsNow())) {
                    s.keepLonger(decision.poi, written.text, written.credit, now)
                    return
                }
                written ?: fixed
            }
            is RideDecision.Deferred -> {
                val kept = decision.talk
                val written = if (kept.text != null) Line(kept.text, kept.credit) else app.narrator.spot(kept.poi, null, riding = false, station, decision.maxChars)
                written?.takeIf { SpeechBudget.fits(it.text, maxCharsNow()) } ?: fixed
            }
            is RideDecision.Rest -> talk(s, Prompts.Event.REST, mapOf("minutes" to decision.minutes), station, decision.maxChars) ?: fixed
            is RideDecision.Milestone -> talk(s, Prompts.Event.MILESTONE, emptyMap(), station, decision.maxChars) ?: fixed
            else -> fixed
        }
        line?.let { say(it, motion.state(System.currentTimeMillis())) }
        s.carriedOut(decision, now)
    }

    private suspend fun talk(s: RideSession, event: Prompts.Event, vars: Map<String, Any?>, station: Station, maxChars: Int): Line? {
        val now = System.currentTimeMillis()
        val situation = Narrator.situationVars(
            now, app.settings.settings.value.values.style.label, weather,
            rideMinutes = ((now - s.startedAt) / 60_000).toInt(), distanceM = s.distanceM, spots = s.spotNames, recent = s.recentLines,
        )
        return app.narrator.talk(event, vars, situation, station, maxChars)?.takeIf { SpeechBudget.fits(it.text, maxCharsNow()) }
    }

    private fun maxCharsNow(): Int = SpeechBudget.maxChars(app.settings.settings.value.values, motion.state(System.currentTimeMillis()))

    private fun onStateChanged(s: RideSession, state: MotionState) {
        _state.value = state
        if (state == MotionState.MOVING) s.onMoving()
        notify(rideNotification(state, _lastLine.value?.text))
    }

    private fun say(line: Line, state: MotionState) {
        Log.i(TAG, "say: ${line.text} (${line.credit})")
        app.speaker.speak(line.text)
        session?.remember(line.text)
        _lastLine.value = line
        notify(rideNotification(state, line.text))
    }

    private suspend fun refreshWeather(location: Location) {
        val now = System.currentTimeMillis()
        if (now - weatherFetchedAt < WEATHER_REFRESH_MS) return
        weatherFetchedAt = now
        weather = try {
            app.weather.current(location.latitude, location.longitude)
        } catch (e: Exception) {
            Log.w(TAG, "Weather fetch failed", e)
            weather
        }
    }

    private fun notify(notification: Notification) =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)

    /**
     * While moving, only that a ride is on: no line to read and no button to press (S-1, S-2,
     * AC-18). Stopped, the last line and the button to finish.
     */
    private fun rideNotification(state: MotionState, text: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CycleApp.CHANNEL_RIDE)
            .setSmallIcon(R.drawable.ic_bike)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (state == MotionState.MOVING) return builder.setContentTitle("走行中").build()
        val stop = PendingIntent.getService(this, 1, Intent(this, RideService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return builder
            .setContentTitle("止まっています")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(0, "終了", stop)
            .build()
    }

    companion object {
        private const val TAG = "RideService"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "stop"
        private const val LOCATION_MS = 2_000L
        private const val TICK_MS = 5_000L
        // Often enough that the lookahead for weather changes stays current.
        private const val WEATHER_REFRESH_MS = 15 * 60_000L

        private val _state = MutableStateFlow<MotionState?>(null)

        /** The rider's state while a ride goes on; null when there is no ride. */
        val state: StateFlow<MotionState?> = _state.asStateFlow()

        private val _lastLine = MutableStateFlow<Line?>(null)

        /** The last line said, with its credit when the AI wrote it (platform ADR-AI-ROLES H-15). */
        val lastLine: StateFlow<Line?> = _lastLine.asStateFlow()

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RideService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RideService::class.java).setAction(ACTION_STOP))
        }
    }
}
