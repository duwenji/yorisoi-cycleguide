package io.github.duwenji.yorisoi.cycleguide.companion

import io.github.duwenji.yorisoi.cycleguide.style.StyleItem
import io.github.duwenji.yorisoi.cycleguide.style.StyleValues
import io.github.duwenji.yorisoi.kit.geo.LatLon
import io.github.duwenji.yorisoi.kit.geo.RouteGeometry

/** Whether the rider is moving, waiting at a light, or resting (requirements F-3). */
enum class MotionState { MOVING, SIGNAL_STOP, REST }

/**
 * Tells moving from stopping, from the speeds the location fixes report (architecture.md「動いて
 * いるかどうか」). The speed is the median of the last [SPEED_POINTS] fixes, so a single jump of
 * the GPS doesn't count. Walking the bike (about 1.4 m/s) is still moving.
 *
 * Standing still, the GPS can report a speed above [STOP_MPS] while the position stays put; a
 * stopped rider only counts as moving off once away from where they were last slow, by more than
 * [STILL_M] or the fix's accuracy.
 *
 * While no fix comes (a tunnel), the state stays as it was and stopping isn't counted; after such a
 * gap, a stop is counted from the first fix (requirements AC-26). An inaccurate fix (resting under
 * a roof) isn't a gap: only its speed isn't used.
 *
 * @param values the ride style's, read at each [state]
 */
class Motion(private val values: () -> StyleValues) {
    private val speeds = ArrayDeque<Float>()
    private var lastFixAt: Long? = null
    private var stoppedSince: Long? = null

    /** Where the rider was last slow, while stopped. */
    private var slowAt: LatLon? = null

    /** The median speed of the last fixes, or null before the first fix with a speed. */
    val speedMps: Float? get() = speeds.takeIf { it.isNotEmpty() }?.sorted()?.let { it[it.size / 2] }

    /** A location fix at [at]; [speedMps] null when the fix has none, [accuracyM] null when unknown. */
    fun onFix(at: Long, position: LatLon, speedMps: Float?, accuracyM: Float?) {
        val previous = lastFixAt
        lastFixAt = at
        if (accuracyM != null && accuracyM > MAX_ACCURACY_M) return
        if (speedMps == null) return
        speeds.addLast(speedMps)
        while (speeds.size > SPEED_POINTS) speeds.removeFirst()
        val slow = (this.speedMps ?: 0f) < STOP_MPS
        val stillThere = stoppedSince != null &&
            slowAt?.let { RouteGeometry.distanceM(it, position) < maxOf(STILL_M, (accuracyM ?: 0f).toDouble()) } == true
        if (!slow && !stillThere) {
            stoppedSince = null
            slowAt = null
            return
        }
        val afterGap = previous != null && at - previous > FIX_GAP_MS
        if (stoppedSince == null || afterGap) stoppedSince = at
        if (slow) slowAt = position
    }

    /** How long the rider has been stopped at [now]; 0 while moving. */
    fun stoppedMs(now: Long): Long {
        val since = stoppedSince ?: return 0
        // Without fixes, the time since the last one isn't counted.
        val until = lastFixAt?.takeIf { now - it > FIX_GAP_MS } ?: now
        return (until - since).coerceAtLeast(0)
    }

    fun state(now: Long): MotionState {
        val stopped = stoppedMs(now)
        val v = values()
        return when {
            stopped >= v.ms(StyleItem.REST_MIN) -> MotionState.REST
            stopped >= v.ms(StyleItem.SIGNAL_STOP_SEC) -> MotionState.SIGNAL_STOP
            else -> MotionState.MOVING
        }
    }

    companion object {
        /** Slower than this counts as stopped; walking the bike is faster. */
        const val STOP_MPS = 0.8f

        const val SPEED_POINTS = 3

        /** Nearer than this (or the fix's accuracy) to where the rider was last slow is still there. */
        const val STILL_M = 10.0

        /** Fixes less accurate than this aren't used (as in SanpoGuide). */
        const val MAX_ACCURACY_M = 50f

        /** No fix for this long is a gap, not a stop. */
        const val FIX_GAP_MS = 10_000L
    }
}
