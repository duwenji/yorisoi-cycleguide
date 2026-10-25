package io.github.duwenji.yorisoi.cycleguide.companion

import io.github.duwenji.yorisoi.cycleguide.history.SpotTalk
import io.github.duwenji.yorisoi.kit.geo.LatLon
import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.kit.geo.RouteGeometry
import io.github.duwenji.yorisoi.kit.geo.weather.WeatherChangeKind

/**
 * One ride so far: where the rider has been, what was said and when, and what was kept for a
 * stop. Plain state, no Android types; the ride service updates it and reads it into a
 * [RideSituation].
 */
class RideSession(val startedAt: Long) {
    /** The last points of the track, oldest first, for the way the rider goes ([Ahead.heading]). */
    val track: List<LatLon> get() = _track
    private val _track = ArrayDeque<LatLon>()

    var distanceM = 0.0
        private set

    /** The way ridden so far, oldest first, for the map; thinned to [MAX_ROUTE_POINTS] on a long ride. */
    val route: List<LatLon> get() = _route
    private val _route = mutableListOf<LatLon>()

    var lastSpokeAt = 0L
        private set
    var lastSpotTalkAt = 0L
        private set
    var lastMilestoneDistanceM = 0.0
        private set
    var restRemarked = false
        private set
    var sunsetWarned = false
        private set
    val weatherWarnedAt: Map<WeatherChangeKind, Long> get() = _weatherWarnedAt
    private val _weatherWarnedAt = mutableMapOf<WeatherChangeKind, Long>()

    /** The new channel's greeting, said before anything else; null once said. */
    var pendingGreeting: String? = null

    private var switchedAt: Long? = null

    /** Spots talked about, or kept for a stop, on this ride, by id. */
    private val talkedSpots = mutableSetOf<String>()

    /** The spots talked about, in order, for the map of the ride once it's over. */
    val spots: List<Poi> get() = _spots
    private val _spots = mutableListOf<Poi>()

    /** What was said about each spot, in order, for the history (requirements F-28). */
    val talks: List<SpotTalk> get() = _talks
    private val _talks = mutableListOf<SpotTalk>()

    /** The channels used, in order (`Station.key`). */
    val channels: List<String> get() = _channels
    private val _channels = mutableListOf<String>()

    /** The names of the spots talked about, in order, for the AI's sense of the ride. */
    val spotNames: List<String> get() = _spots.map { it.name }

    /** The last lines said, oldest first, so the AI doesn't repeat itself. */
    val recentLines: List<String> get() = _recentLines
    private val _recentLines = ArrayDeque<String>()

    fun remember(line: String) {
        _recentLines.addLast(line)
        while (_recentLines.size > RECENT_LINES) _recentLines.removeFirst()
    }

    /**
     * A line about [poi] came back too long to say now: kept, written, for the next stop, and the
     * spot counts as talked about.
     */
    fun keepLonger(poi: Poi, text: String, credit: String?, now: Long) {
        talkedSpots += poi.id
        _deferred += DeferredTalk(poi, text, now, credit)
    }

    val deferred: List<DeferredTalk> get() = _deferred
    private val _deferred = mutableListOf<DeferredTalk>()

    /** A location fix; small steps (GPS jitter while standing) don't add to the distance. */
    fun onLocation(p: LatLon) {
        val last = _track.lastOrNull()
        if (last != null) {
            val step = RouteGeometry.distanceM(last, p)
            if (step < MIN_STEP_M) return
            distanceM += step
        }
        _track.addLast(p)
        while (_track.size > TRACK_POINTS) _track.removeFirst()
        _route += p
        if (_route.size > MAX_ROUTE_POINTS) {
            val thinned = RouteGeometry.thin(_route, MAX_ROUTE_POINTS / 2)
            _route.clear()
            _route += thinned
        }
    }

    fun alreadyTalked(poi: Poi): Boolean = poi.id in talkedSpots

    /** [text] was said about [poi] at [at]. */
    fun said(poi: Poi, text: String, at: Long) {
        _talks += SpotTalk(poi.id, poi.name, at, text)
    }

    /** The ride goes on with the channel [key]; the same one again isn't listed twice. */
    fun useChannel(key: String) {
        if (_channels.lastOrNull() != key) _channels += key
    }

    /** The rider is moving again after a stop: the next rest is remarked on again. */
    fun onMoving() {
        restRemarked = false
    }

    fun switchedChannel(now: Long, greeting: String) {
        switchedAt = now
        pendingGreeting = greeting
    }

    fun justSwitched(now: Long): Boolean = switchedAt?.let { now - it < SWITCH_QUIET_MS } ?: false

    /** Keeps only the kept talks still worth saying. */
    fun prune(now: Long, here: LatLon?) {
        val kept = DeferredTalk.prune(_deferred, now, here)
        _deferred.retainAll(kept.toSet())
    }

    /** Records that [decision] was carried out at [now] (a line was said, or a spot kept). */
    fun carriedOut(decision: RideDecision, now: Long) {
        when (decision) {
            is RideDecision.WeatherTurn -> _weatherWarnedAt[decision.change.kind] = now
            is RideDecision.Sunset -> sunsetWarned = true
            is RideDecision.Greeting -> pendingGreeting = null
            is RideDecision.Spot -> {
                talkedSpots += decision.poi.id
                _spots += decision.poi
                lastSpotTalkAt = now
            }
            is RideDecision.Defer -> {
                talkedSpots += decision.poi.id
                _deferred += DeferredTalk(decision.poi, null, now)
                return
            }
            is RideDecision.Deferred -> {
                _deferred.remove(decision.talk)
                _spots += decision.talk.poi
                lastSpotTalkAt = now
            }
            is RideDecision.Rest -> restRemarked = true
            is RideDecision.Milestone -> lastMilestoneDistanceM = distanceM
            RideDecision.Nothing -> return
        }
        lastSpokeAt = now
    }

    companion object {
        const val TRACK_POINTS = 30
        const val RECENT_LINES = 4
        const val MAX_ROUTE_POINTS = 3000
        const val MIN_STEP_M = 3.0

        /** After a channel switch, its greeting has the floor this long. */
        const val SWITCH_QUIET_MS = 60_000L
    }
}
