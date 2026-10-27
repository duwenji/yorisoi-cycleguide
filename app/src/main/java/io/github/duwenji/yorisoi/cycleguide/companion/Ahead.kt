package io.github.duwenji.yorisoi.cycleguide.companion

import io.github.duwenji.yorisoi.kit.geo.LatLon
import io.github.duwenji.yorisoi.kit.geo.RouteGeometry
import kotlin.math.abs

/** When to start talking about a spot ahead. */
enum class Timing {
    /** Still far: there is time to talk later. */
    WAIT,

    /** Now, to finish before reaching it. */
    NOW,

    /** It can't be finished before reaching it: keep it for the next stop. */
    TOO_LATE,
}

/**
 * The spots ahead of the rider (requirements F-2, architecture.md「前方」): the way they go, the fan
 * in front of them, where to search, and when to start talking so the line ends [MARGIN_M] before
 * the spot.
 */
object Ahead {
    /** The fan's half angle, either side of the way the rider goes. */
    const val HALF_ANGLE_DEG = 45.0

    /** Below this the fix's bearing isn't trusted; the track gives the way instead. */
    const val MIN_BEARING_SPEED_MPS = 1f

    /** How far back on the track to look for the way the rider goes. */
    const val TRACK_M = 30.0

    /** The line ends this far before the spot. */
    const val MARGIN_M = 50.0

    /** Time to write the line (the AI's answer) before it is read out. */
    const val COMPOSE_SEC = 5.0

    /** Start once less than this is left before the last moment; a tick comes every few seconds. */
    const val LEAD_SEC = 15.0

    /**
     * The way the rider goes, in degrees clockwise from north: the fix's [bearingDeg] when moving
     * fast enough, else from [track] (oldest first, the newest point last); null if neither tells.
     */
    fun heading(bearingDeg: Float?, speedMps: Float?, track: List<LatLon>): Double? {
        if (bearingDeg != null && speedMps != null && speedMps >= MIN_BEARING_SPEED_MPS) return bearingDeg.toDouble()
        val here = track.lastOrNull() ?: return null
        val back = track.asReversed().firstOrNull { RouteGeometry.distanceM(it, here) >= TRACK_M } ?: return null
        return RouteGeometry.bearingDeg(back, here)
    }

    /** Whether [p] is within [maxM] of [here] and in the fan towards [heading]. */
    fun isAhead(here: LatLon, heading: Double, p: LatLon, maxM: Double): Boolean {
        val d = RouteGeometry.distanceM(here, p)
        if (d > maxM) return false
        if (d < 1.0) return true
        val diff = abs(RouteGeometry.bearingDeg(here, p) - heading) % 360.0
        return minOf(diff, 360.0 - diff) <= HALF_ANGLE_DEG
    }

    /** Where to search: half [aheadM] ahead, so the search covers the fan; [here] when the way is unknown. */
    fun searchCenter(here: LatLon, heading: Double?, aheadM: Int): LatLon =
        heading?.let { RouteGeometry.destination(here, it, aheadM / 2.0) } ?: here

    /** When to start a line of [talkSec] about a spot [distanceM] ahead, at [speedMps]. */
    fun timing(distanceM: Double, speedMps: Float, talkSec: Int): Timing {
        if (speedMps < Motion.STOP_MPS) return Timing.NOW
        val left = (distanceM - MARGIN_M) / speedMps - talkSec - COMPOSE_SEC
        return when {
            left < 0 -> Timing.TOO_LATE
            left <= LEAD_SEC -> Timing.NOW
            else -> Timing.WAIT
        }
    }

    /**
     * How long to wait for the line about a spot [distanceM] ahead, so that a line of [talkSec]
     * still ends [MARGIN_M] before it; at least [COMPOSE_SEC]. Null when stopped or the speed is
     * unknown: no spot comes nearer.
     */
    fun answerSec(distanceM: Double, speedMps: Float?, talkSec: Int): Double? {
        if (speedMps == null || speedMps < Motion.STOP_MPS) return null
        return maxOf(COMPOSE_SEC, (distanceM - MARGIN_M) / speedMps - talkSec)
    }
}
