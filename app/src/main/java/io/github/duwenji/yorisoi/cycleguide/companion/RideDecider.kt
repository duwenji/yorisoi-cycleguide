package io.github.duwenji.yorisoi.cycleguide.companion

import io.github.duwenji.yorisoi.core.format.TalkEventKind
import io.github.duwenji.yorisoi.core.format.TalkLevel
import io.github.duwenji.yorisoi.cycleguide.style.StyleItem
import io.github.duwenji.yorisoi.cycleguide.style.StyleValues
import io.github.duwenji.yorisoi.kit.companion.TalkEngine
import io.github.duwenji.yorisoi.kit.companion.TalkGates
import io.github.duwenji.yorisoi.kit.companion.TalkRule
import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.kit.geo.weather.Weather
import io.github.duwenji.yorisoi.kit.geo.weather.WeatherChange
import io.github.duwenji.yorisoi.kit.geo.weather.WeatherChangeDetector
import io.github.duwenji.yorisoi.kit.geo.weather.WeatherChangeKind

/**
 * A spot in the fan ahead of the rider ([Ahead.isAhead]), as the ride service sees it.
 *
 * @param alreadyThisRide talked about (or kept for later) on this ride already
 */
data class AheadSpot(val poi: Poi, val distanceM: Double, val alreadyThisRide: Boolean = false)

/**
 * Everything [RideDecider] reads, as plain values gathered by the ride service each time.
 * No Android types, so a scene can be written down in a unit test.
 */
data class RideSituation(
    val now: Long,
    val values: StyleValues,
    /** The channel's talk level, the events it talks on, and the spot kinds it skips (as `Poi.category`). */
    val level: TalkLevel,
    val events: Set<TalkEventKind>,
    val skippedCategories: Set<String>,
    val state: MotionState,
    val speedMps: Float?,
    val stoppedMs: Long,
    /** [QuietZones.isQuiet] now. */
    val quiet: Boolean,
    /** The spots in the fan ahead; the decider checks which are worth it. */
    val ahead: List<AheadSpot>,
    /** Talks kept for a stop, still worth saying ([DeferredTalk.prune]), oldest first. */
    val deferred: List<DeferredTalk>,
    val weather: Weather?,
    // The ride so far (RideSession).
    val distanceM: Double,
    val lastSpokeAt: Long,
    val lastSpotTalkAt: Long,
    val lastMilestoneDistanceM: Double,
    val restRemarked: Boolean,
    val sunsetWarned: Boolean,
    val weatherWarnedAt: Map<WeatherChangeKind, Long>,
    val pendingGreeting: String?,
    val justSwitched: Boolean,
) : TalkGates<RideGate> {
    override fun isOpen(gate: RideGate): Boolean = when (gate) {
        RideGate.NOT_QUIET -> !quiet
        RideGate.SPOT_GAP -> now - lastSpotTalkAt >= gap(StyleItem.SPOT_GAP_MIN)
        RideGate.CHAT_GAP -> now - lastSpokeAt >= gap(StyleItem.CHAT_GAP_MIN)
        RideGate.NOT_SWITCHED -> !justSwitched
    }

    /** The style's gap, longer or shorter as the channel's talk level says (architecture.md「走り方」). */
    private fun gap(item: StyleItem): Long = (values.ms(item) * RideDecider.gapFactor(level)).toLong()

    /** How long the companion may talk now. */
    val maxChars: Int get() = SpeechBudget.maxChars(values, state)
}

/** When a kind of talk may come up on a ride (architecture.md 図 2). */
enum class RideGate {
    /** Not near a traffic light or too fast while moving ([QuietZones]). */
    NOT_QUIET,

    /** The gap since the last spot talk has passed. */
    SPOT_GAP,

    /** The gap since the companion last said anything has passed. */
    CHAT_GAP,

    /** Not right after a channel switch. */
    NOT_SWITCHED,
}

/** What to do now, one per tick. The ride service carries it out; [maxChars] bounds the line it writes. */
sealed interface RideDecision {
    data class WeatherTurn(val change: WeatherChange) : RideDecision
    data class Sunset(val minutesLeft: Int) : RideDecision
    data class Greeting(val line: String) : RideDecision
    data class Spot(val poi: Poi, val distanceM: Int, val maxChars: Int) : RideDecision
    /** Too late to finish before the spot: keep it for the next stop. Says nothing. */
    data class Defer(val poi: Poi) : RideDecision
    data class Deferred(val talk: DeferredTalk, val maxChars: Int) : RideDecision
    data class Rest(val minutes: Int, val maxChars: Int) : RideDecision
    data class Milestone(val maxChars: Int) : RideDecision
    data object Nothing : RideDecision
}

private typealias Rule = TalkRule<RideSituation, RideDecision, RideGate>

/**
 * Decides whether anything is worth saying right now, most important first (architecture.md 図 2):
 * the ride's rules in order, each behind its gates. Pure: the ride service gathers a
 * [RideSituation] and carries out the [RideDecision].
 */
object RideDecider {
    /** The style's gaps on a channel that talks less (×2) or more (×0.5). */
    fun gapFactor(level: TalkLevel): Double = when (level) {
        TalkLevel.QUIET -> 2.0
        TalkLevel.NORMAL -> 1.0
        TalkLevel.CHATTY -> 0.5
    }

    /** The ride's rules, in the order they are asked. [TalkRule.safety]: a channel can't switch it off (S-4). */
    val rules: List<Rule> = listOf(
        // Safety first, near a light and at speed too.
        Rule("weather", safety = true) { s ->
            val change = s.weather?.let {
                WeatherChangeDetector.detect(it, s.now, s.values.ms(StyleItem.WEATHER_LOOKAHEAD_MIN), s.values[StyleItem.HEAVY_RAIN_MM_PER_H])
            }
            val warnedAt = change?.let { s.weatherWarnedAt[it.kind] }
            change?.takeIf { warnedAt == null || s.now - warnedAt >= s.values.ms(StyleItem.WEATHER_REPEAT_MIN) }
                ?.let(RideDecision::WeatherTurn)
        },
        Rule("sunset", safety = true) { s ->
            val minutesLeft = s.weather?.sunsetAt?.let { ((it - s.now) / 60_000).toInt() }
            minutesLeft?.takeIf { !s.sunsetWarned && it in s.values[StyleItem.SUNSET_LATEST_MIN]..s.values[StyleItem.SUNSET_NOTICE_MIN] }
                ?.let(RideDecision::Sunset)
        },
        // From here on, nothing near a light or at speed while moving.
        Rule("greeting", setOf(RideGate.NOT_QUIET)) { s -> s.pendingGreeting?.let(RideDecision::Greeting) },
        Rule("spot", setOf(RideGate.NOT_QUIET, RideGate.SPOT_GAP, RideGate.NOT_SWITCHED)) spot@{ s ->
            val speed = s.speedMps
            if (s.state != MotionState.MOVING || speed == null || TalkEventKind.SPOT !in s.events) return@spot null
            val spot = s.ahead
                .filter { !it.alreadyThisRide && it.poi.category !in s.skippedCategories && s.values.style.spotScale.accepts(it.poi) }
                .filter { it.distanceM <= s.values[StyleItem.AHEAD_M] }
                .minByOrNull { it.distanceM } ?: return@spot null
            when (Ahead.timing(spot.distanceM, speed, SpeechBudget.seconds(s.values, s.state))) {
                Timing.WAIT -> null
                Timing.NOW -> RideDecision.Spot(spot.poi, spot.distanceM.toInt(), s.maxChars)
                Timing.TOO_LATE -> RideDecision.Defer(spot.poi)
            }
        },
        // Stopped, at a light too: what was kept while riding, if it fits now.
        Rule("deferred", setOf(RideGate.NOT_QUIET, RideGate.NOT_SWITCHED)) deferred@{ s ->
            if (s.state == MotionState.MOVING) return@deferred null
            s.deferred.firstOrNull { it.text == null || SpeechBudget.fits(it.text, s.maxChars) }
                ?.let { RideDecision.Deferred(it, s.maxChars) }
        },
        // From here on the small-talk gap holds.
        Rule("rest", setOf(RideGate.CHAT_GAP, RideGate.NOT_QUIET, RideGate.NOT_SWITCHED)) { s ->
            RideDecision.Rest((s.stoppedMs / 60_000).toInt(), s.maxChars)
                .takeIf { s.state == MotionState.REST && !s.restRemarked && TalkEventKind.REST in s.events }
        },
        Rule("milestone", setOf(RideGate.CHAT_GAP, RideGate.NOT_QUIET, RideGate.NOT_SWITCHED)) { s ->
            val due = s.distanceM - s.lastMilestoneDistanceM >= s.values[StyleItem.MILESTONE_KM] * 1000.0
            RideDecision.Milestone(s.maxChars).takeIf { due && TalkEventKind.MILESTONE in s.events }
        },
    )

    private val engine = TalkEngine(rules, RideDecision.Nothing)

    fun decide(s: RideSituation): RideDecision = engine.decide(s)
}
