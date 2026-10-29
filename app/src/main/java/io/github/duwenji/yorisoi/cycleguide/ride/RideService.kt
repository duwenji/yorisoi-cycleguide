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
import io.github.duwenji.yorisoi.cycleguide.BuildConfig
import io.github.duwenji.yorisoi.cycleguide.CycleApp
import io.github.duwenji.yorisoi.cycleguide.R
import io.github.duwenji.yorisoi.cycleguide.companion.Ahead
import io.github.duwenji.yorisoi.cycleguide.companion.AheadSpot
import io.github.duwenji.yorisoi.cycleguide.companion.Lines
import io.github.duwenji.yorisoi.cycleguide.companion.Motion
import io.github.duwenji.yorisoi.cycleguide.companion.MotionState
import io.github.duwenji.yorisoi.cycleguide.companion.NearFacility
import io.github.duwenji.yorisoi.cycleguide.companion.PointCue
import io.github.duwenji.yorisoi.cycleguide.companion.QuietZones
import io.github.duwenji.yorisoi.cycleguide.companion.RideDecider
import io.github.duwenji.yorisoi.cycleguide.companion.RideDecision
import io.github.duwenji.yorisoi.cycleguide.companion.RideSession
import io.github.duwenji.yorisoi.cycleguide.companion.RideSituation
import io.github.duwenji.yorisoi.cycleguide.companion.SpeechBudget
import io.github.duwenji.yorisoi.cycleguide.cue.RideContext
import io.github.duwenji.yorisoi.cycleguide.guide.Line
import io.github.duwenji.yorisoi.cycleguide.guide.PoiSpot
import io.github.duwenji.yorisoi.cycleguide.history.RideRecord
import io.github.duwenji.yorisoi.cycleguide.history.SpotTalk
import io.github.duwenji.yorisoi.cycleguide.guide.Narrator
import io.github.duwenji.yorisoi.cycleguide.prompt.Prompts
import io.github.duwenji.yorisoi.kit.channel.Station
import io.github.duwenji.yorisoi.kit.channel.material.CueFire
import io.github.duwenji.yorisoi.kit.channel.material.Subject
import io.github.duwenji.yorisoi.cycleguide.style.StyleItem
import io.github.duwenji.yorisoi.cycleguide.ui.MainActivity
import io.github.duwenji.yorisoi.kit.geo.LatLon
import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.kit.geo.RouteGeometry
import io.github.duwenji.yorisoi.kit.geo.relativeDirection
import io.github.duwenji.yorisoi.kit.geo.weather.Weather
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

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

    /** The ride is ending: the look back is being written, and the companion says nothing else. */
    private var finishing = false

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
            finish()
            return START_NOT_STICKY
        }
        if (session == null) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, rideNotification(MotionState.SIGNAL_STOP, Lines.start()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
            val now = System.currentTimeMillis()
            session = RideSession(now).also {
                it.pendingGreeting = app.stations.current.value.manifest.greeting
                it.useChannel(app.stations.current.value.key)
                // Read while the ride starts; until then, nothing was said before.
                lifecycleScope.launch { it.past = app.pastTalks(now) }
            }
            if (app.settings.settings.value.developerMode) startDiagnostics(now)
            // The lists, if a day has passed: at the start, never during the ride (API-002 取得の時期).
            app.channels.refreshIfDue()
            earphones.start()
            watchSpeech()
            startLocationUpdates()
            startTicker()
            _state.value = MotionState.SIGNAL_STOP
        }
        return START_STICKY
    }

    override fun onDestroy() {
        session?.let { s ->
            app.diagnostics.finish(s.startedAt)
            // Ended some other way than by the rider (the system stopped it): kept without a look back.
            if (!finishing) RideRecord.of(s, System.currentTimeMillis())?.let(app::keepRide)
        }
        fused.removeLocationUpdates(callback)
        earphones.stop()
        session = null
        app.heading = null
        _state.value = null
        _lastLine.value = null
        _walked.value = emptyList()
        _heading.value = null
        _talks.value = emptyList()
        _talkedSpots.value = emptyList()
        super.onDestroy()
    }

    /**
     * Ends the ride: the look back at it (requirements F-28) is written, said and kept with the ride,
     * then the service stops. The rider has stopped, so it may be as long as at a rest; the fixed
     * line when the AI doesn't answer in time.
     */
    private fun finish() {
        val s = session
        if (s == null) {
            stopSelf()
            return
        }
        if (finishing) return
        finishing = true
        fused.removeLocationUpdates(callback)
        lifecycleScope.launch {
            val endedAt = System.currentTimeMillis()
            val recap = withTimeoutOrNull(RECAP_TIMEOUT_MS) { talking.withLock { recap(s, endedAt) } }
            val line = recap ?: Line(Lines.finish(s.distanceM))
            say(line, MotionState.REST)
            RideRecord.of(s, endedAt, recap?.text)?.let(app::keepRide)
            stopSelf()
        }
    }

    private suspend fun recap(s: RideSession, endedAt: Long): Line? {
        val maxChars = SpeechBudget.maxChars(app.settings.settings.value.values, MotionState.REST)
        val vars = mapOf(
            "km" to String.format(Locale.ROOT, "%.1f", s.distanceM / 1000),
            "minutes" to ((endedAt - s.startedAt) / 60_000).toInt(),
            "spots" to s.spotNames.distinct().joinToString("、").ifEmpty { null },
        )
        val situation = Narrator.situationVars(
            endedAt, app.settings.settings.value.values.style.label, weather,
            rideMinutes = ((endedAt - s.startedAt) / 60_000).toInt(), distanceM = s.distanceM, spots = s.spotNames, recent = s.recentLines,
        )
        return app.narrator.talk(Prompts.Event.FINISH, vars, situation, app.stations.current.value, maxChars)
            ?.takeIf { SpeechBudget.fits(it.text, maxChars) }
    }

    /** Starts the developer's record of this ride, with how it was set up. */
    private fun startDiagnostics(id: Long) {
        val settings = app.settings.settings.value
        val values = settings.values
        app.diagnostics.start(
            id,
            listOf(
                "app" to BuildConfig.VERSION_NAME,
                "style" to values.style.id,
                "values" to JSONObject().apply { StyleItem.entries.forEach { put(it.name, values[it]) } },
                "channel" to app.stations.current.value.key,
                "ai" to settings.connection.name,
                "charsPerSec" to SpeechBudget.CHARS_PER_SEC,
            ),
        )
    }

    /** How long each line took to read out, to check the characters a second (architecture.md A-2). */
    private fun watchSpeech() = lifecycleScope.launch {
        var startedAt = 0L
        app.speaker.speaking.collect { speaking ->
            val now = System.currentTimeMillis()
            if (speaking) {
                startedAt = now
            } else if (startedAt != 0L) {
                val text = _lastLine.value?.text
                app.diagnostics.event("speech_done", "ms" to now - startedAt, "chars" to text?.let(SpeechBudget::chars))
                startedAt = 0L
            }
        }
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
        val here = LatLon(location.latitude, location.longitude)
        val accuracy = location.accuracy.takeIf { location.hasAccuracy() }
        motion.onFix(System.currentTimeMillis(), here, location.speed.takeIf { location.hasSpeed() }, accuracy)
        s.onLocation(here, accuracy)
        if (s.route.size != _walked.value.size) _walked.value = s.route.toList()
        heading = Ahead.heading(location.bearing.takeIf { location.hasBearing() }, motion.speedMps, s.track)
        app.heading = heading
        _heading.value = heading
        app.diagnostics.event(
            "location", "lat" to location.latitude, "lon" to location.longitude,
            "speed" to location.speed.takeIf { location.hasSpeed() }, "accuracy" to location.accuracy.takeIf { location.hasAccuracy() },
            "medianSpeed" to motion.speedMps, "heading" to heading, "km" to s.distanceM / 1000,
        )
        lifecycleScope.launch {
            try {
                if (app.spots.onLocation(location)) {
                    app.diagnostics.event(
                        "spots", "source" to app.spots.source?.name?.lowercase(),
                        "spots" to app.spots.spots.value.size, "signals" to app.spots.trafficSignals.value.size,
                        "centerLat" to app.spots.searchedAt?.lat, "centerLon" to app.spots.searchedAt?.lon,
                    )
                }
            } catch (e: IOException) {
                Log.w(TAG, "Spot search failed", e)
                // What is shown instead: the nearest kept answer, or the last one, now behind.
                app.diagnostics.event(
                    "spot_search_failed", "error" to e.toString(),
                    "centerLat" to app.spots.searchedAt?.lat, "centerLon" to app.spots.searchedAt?.lon,
                    "source" to app.spots.source?.name?.lowercase(), "spots" to app.spots.spots.value.size,
                )
            }
            if (weatherFetchedAt == 0L) refreshWeather(location)
            maybeTalk()
        }
    }

    private suspend fun maybeTalk() {
        val s = session ?: return
        if (finishing || !talking.tryLock()) return
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
                .map {
                    AheadSpot(
                        it, RouteGeometry.distanceM(here, LatLon(it.lat, it.lon)), s.alreadyTalked(it), app.cues.names(station, it, s.shownCues, now),
                        talkedRecently = s.past.recent(it, now),
                    )
                }
            val facilities = if (here == null) emptyList() else app.spots.facilities.value.map { f ->
                val at = LatLon(f.lat, f.lon)
                NearFacility(
                    f, RouteGeometry.distanceM(here, at),
                    ahead = way != null && Ahead.isAhead(here, way, at, values[StyleItem.AHEAD_M].toDouble()),
                    side = way?.let { relativeDirection(it.toFloat(), RouteGeometry.bearingDeg(here, at).toFloat()) } ?: "",
                )
            }
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
                pointCue = here?.let { pointCue(station, it, s, now) },
                startedAt = s.startedAt, facilities = facilities,
                mentionedFacilities = s.mentionedFacilities, amenityMentionedAt = s.amenityMentionedAt,
            )
            val decision = RideDecider.decide(situation)
            if (decision != RideDecision.Nothing) {
                Log.i(TAG, "$state ${motion.speedMps} quiet=${situation.quiet} ahead=${ahead.size}: $decision")
                app.diagnostics.event(
                    "decision", "decision" to decision.javaClass.simpleName, "about" to about(decision),
                    "state" to state.name, "speed" to motion.speedMps, "quiet" to situation.quiet,
                    "ahead" to ahead.size, "deferred" to s.deferred.size,
                )
            }
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
                // A channel's cue on this spot says its own line (F-32); otherwise the AI's narration.
                // Not waited for past the time it can still be said before the spot; the fixed line then.
                val wait = Ahead.answerSec(decision.distanceM.toDouble(), motion.speedMps, decision.maxChars / SpeechBudget.CHARS_PER_SEC)
                val written = within(wait, decision.poi.name) {
                    val cue = app.cues.spot(station, decision.poi, decision.distanceM, s.shownCues, now)
                    val cued = cue?.let { cueLine(s, it, station, RideContext(riding = true, decision.maxChars), now) }
                    cued ?: app.narrator.spot(decision.poi, decision.distanceM, riding = true, station, decision.maxChars, before = s.past.before(decision.poi, now))
                }
                // The rider may have stopped while it was written: the length allowed now counts.
                if (written != null && !SpeechBudget.fits(written.text, maxCharsNow())) {
                    s.keepLonger(decision.poi, written.text, written.credit, now)
                    return
                }
                (written ?: again(s, decision.poi, now) ?: fixed)?.also { s.said(decision.poi, it.text, now) }
            }
            is RideDecision.Deferred -> {
                val kept = decision.talk
                val written = when {
                    kept.text != null -> Line(kept.text, kept.credit)
                    kept.poi != null -> {
                        // A spot a cue names, passed too late: its own line now (F-32).
                        val cue = app.cues.spot(station, kept.poi, null, s.shownCues, now)
                        cue?.let { cueLine(s, it, station, RideContext(riding = false, decision.maxChars), now) }
                            ?: app.narrator.spot(kept.poi, null, riding = false, station, decision.maxChars, before = s.past.before(kept.poi, now))
                    }
                    else -> null
                }
                (written?.takeIf { SpeechBudget.fits(it.text, maxCharsNow()) } ?: kept.poi?.let { again(s, it, now, passed = true) } ?: fixed)?.also { line -> kept.poi?.let { s.said(it, line.text, now) } }
            }
            is RideDecision.Point -> {
                val cue = decision.cue
                val state = motion.state(now)
                val line = cueLine(s, cue.fire, station, RideContext(state == MotionState.MOVING, decision.maxChars, pointSpot(s, cue)), now) ?: return
                if (!SpeechBudget.fits(line.text, maxCharsNow())) {
                    s.keepPoint(cue.name, cue.lat, cue.lon, line.text, line.credit, now)
                    return
                }
                line
            }
            is RideDecision.Rest -> talk(s, Prompts.Event.REST, mapOf("minutes" to decision.minutes), station, decision.maxChars) ?: fixed
            is RideDecision.Milestone -> talk(s, Prompts.Event.MILESTONE, emptyMap(), station, decision.maxChars) ?: fixed
            else -> fixed
        }
        line?.let { say(it, motion.state(System.currentTimeMillis())) }
        s.carriedOut(decision, now)
        if (s.talks.size != _talks.value.size) _talks.value = s.talks.toList()
        if (s.spots.size != _talkedSpots.value.size) _talkedSpots.value = s.spots.toList()
    }

    /** The fixed line for a spot talked about on an earlier ride (REQ-FREQUENT-WAYS F-11); null for a new one. */
    private fun again(s: RideSession, poi: Poi, now: Long, passed: Boolean = false): Line? =
        s.past.before(poi, now)?.let { Line(Lines.again(poi, passed)) }

    /**
     * [write]'s line if it comes within [sec] (always when null); else null, and the line, when it
     * comes, is dropped. The call goes on meanwhile: the AI's client can't be stopped halfway.
     */
    private suspend fun within(sec: Double?, about: String, write: suspend () -> Line?): Line? {
        if (sec == null) return write()
        val line = lifecycleScope.async { write() }
        val written = withTimeoutOrNull((sec * 1000).toLong()) { line.await() }
        if (written == null && !line.isCompleted) app.diagnostics.event("ai_late", "about" to about, "waitedSec" to sec)
        return written
    }

    private suspend fun talk(s: RideSession, event: Prompts.Event, vars: Map<String, Any?>, station: Station, maxChars: Int): Line? {
        val now = System.currentTimeMillis()
        val situation = Narrator.situationVars(
            now, app.settings.settings.value.values.style.label, weather,
            rideMinutes = ((now - s.startedAt) / 60_000).toInt(), distanceM = s.distanceM, spots = s.spotNames, recent = s.recentLines,
        )
        return app.narrator.talk(event, vars, situation, station, maxChars)?.takeIf { SpeechBudget.fits(it.text, maxCharsNow()) }
    }

    /** The point cue the rider at [here] is inside, if any may still fire (F-32). */
    private fun pointCue(station: Station, here: LatLon, s: RideSession, now: Long): PointCue? {
        val fire = app.cues.point(station, here.lat, here.lon, s.shownCues, now) ?: return null
        val at = fire.subject as? Subject.Point ?: return null
        return PointCue(fire, station.name, at.lat, at.lon)
    }

    /**
     * The spot inside a point cue for the AI to narrate: one not talked about on this ride yet, the
     * most notable, then the nearest its middle.
     */
    private fun pointSpot(s: RideSession, cue: PointCue): PoiSpot? {
        val radius = (cue.fire.subject as? Subject.Point)?.radiusM ?: return null
        val middle = LatLon(cue.lat, cue.lon)
        val here = lastLocation?.let { LatLon(it.latitude, it.longitude) }
        val poi = app.spots.spots.value
            .filter { !s.alreadyTalked(it) }
            .map { it to RouteGeometry.distanceM(middle, LatLon(it.lat, it.lon)) }
            .filter { (_, d) -> d <= radius }
            .minWithOrNull(compareBy<Pair<Poi, Double>> { (p, _) -> -p.notability }.thenBy { (_, d) -> d })?.first ?: return null
        return PoiSpot(poi, here?.let { RouteGeometry.distanceM(it, LatLon(poi.lat, poi.lon)).toInt() })
    }

    /** What a cue says now, counted for its `repeat` whether or not it has a line (it isn't tried again this ride). */
    private suspend fun cueLine(s: RideSession, fire: CueFire, station: Station, ride: RideContext, now: Long): Line? {
        val line = app.cues.speak(fire, station, ride)
        app.cues.record(fire, s.shownCues, now)
        app.diagnostics.event("cue", "cue" to fire.cue.id, "key" to fire.key, "chars" to line?.let { SpeechBudget.chars(it.text) }, "credit" to line?.credit)
        return line
    }

    private fun maxCharsNow(): Int = SpeechBudget.maxChars(app.settings.settings.value.values, motion.state(System.currentTimeMillis()))

    /** What a decision is about, for the record: a spot's name, or the minutes before sunset. */
    private fun about(d: RideDecision): Any? = when (d) {
        is RideDecision.Spot -> "${d.poi.name} ${d.distanceM}m"
        is RideDecision.Defer -> d.poi.name
        is RideDecision.Deferred -> d.talk.name
        is RideDecision.Point -> "${d.cue.fire.cue.id} ${d.cue.name}"
        is RideDecision.Sunset -> d.minutesLeft
        is RideDecision.WeatherTurn -> "${d.change.kind} ${d.change.minutes}min"
        is RideDecision.AmenityNear -> "${d.advice.facility.kind.name} ${d.advice.distanceM}m ${d.advice.side}"
        is RideDecision.Rest -> d.minutes
        else -> null
    }

    private fun onStateChanged(s: RideSession, state: MotionState) {
        app.diagnostics.event("state", "from" to _state.value?.name, "to" to state.name, "stoppedMs" to motion.stoppedMs(System.currentTimeMillis()))
        _state.value = state
        if (state == MotionState.MOVING) s.onMoving()
        notify(rideNotification(state, _lastLine.value?.text))
    }

    private fun say(line: Line, state: MotionState) {
        Log.i(TAG, "say: ${line.text} (${line.credit})")
        app.diagnostics.event("said", "text" to line.text, "credit" to line.credit, "chars" to SpeechBudget.chars(line.text), "state" to state.name)
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
                .also { app.diagnostics.event("weather", "description" to it.description, "temperature" to it.temperatureC, "sunsetAt" to it.sunsetAt) }
        } catch (e: Exception) {
            Log.w(TAG, "Weather fetch failed", e)
            app.diagnostics.event("weather_failed", "error" to e.toString())
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
        // The AI's usual reply takes a few seconds; the rider shouldn't wait long for the goodbye.
        private const val RECAP_TIMEOUT_MS = 15_000L
        // Often enough that the lookahead for weather changes stays current.
        private const val WEATHER_REFRESH_MS = 15 * 60_000L

        private val _state = MutableStateFlow<MotionState?>(null)

        /** The rider's state while a ride goes on; null when there is no ride. */
        val state: StateFlow<MotionState?> = _state.asStateFlow()

        private val _lastLine = MutableStateFlow<Line?>(null)

        /** The last line said, with its credit when the AI wrote it (platform ADR-AI-ROLES H-15). */
        val lastLine: StateFlow<Line?> = _lastLine.asStateFlow()

        private val _walked = MutableStateFlow<List<LatLon>>(emptyList())

        /** The way ridden so far on the current ride, for the map; empty when there is no ride. */
        val walked: StateFlow<List<LatLon>> = _walked.asStateFlow()

        private val _heading = MutableStateFlow<Double?>(null)

        /**
         * The way the rider goes ([Ahead.heading]), for the map; null when not yet known or there
         * is no ride. Kept from the way ridden while stopped.
         */
        val heading: StateFlow<Double?> = _heading.asStateFlow()

        private val _talks = MutableStateFlow<List<SpotTalk>>(emptyList())

        /** What was said about each spot on the current ride, in order, for the map while stopped; empty when there is no ride. */
        val talks: StateFlow<List<SpotTalk>> = _talks.asStateFlow()

        private val _talkedSpots = MutableStateFlow<List<Poi>>(emptyList())

        /**
         * The spots talked about on the current ride, in order, for the map while stopped: kept there
         * after the search has moved on from them. Empty when there is no ride.
         */
        val talkedSpots: StateFlow<List<Poi>> = _talkedSpots.asStateFlow()

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RideService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RideService::class.java).setAction(ACTION_STOP))
        }
    }
}
