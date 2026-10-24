package io.github.duwenji.yorisoi.cycleguide.companion

import io.github.duwenji.yorisoi.cycleguide.style.StyleItem
import io.github.duwenji.yorisoi.cycleguide.style.StyleValues

/** Whether the rider is moving, waiting at a light, or resting (requirements F-3). */
enum class MotionState { MOVING, SIGNAL_STOP, REST }

/**
 * Tells moving from stopping, from the speeds the location fixes report (architecture.md「動いて
 * いるかどうか」). The speed is the median of the last [SPEED_POINTS] fixes, so a single jump of
 * the GPS doesn't count. Walking the bike (about 1.4 m/s) is still moving.
 *
 * While no fix comes (a tunnel), the state stays as it was and stopping isn't counted; after such a
 * gap, a stop is counted from the first fix (requirements AC-26).
 *
 * @param values the ride style's, read at each [state]
 */
class Motion(private val values: () -> StyleValues) {
    private val speeds = ArrayDeque<Float>()
    private var lastFixAt: Long? = null
    private var stoppedSince: Long? = null

    /** The median speed of the last fixes, or null before the first fix with a speed. */
    val speedMps: Float? get() = speeds.takeIf { it.isNotEmpty() }?.sorted()?.let { it[it.size / 2] }

    /** A location fix at [at]; [speedMps] null when the fix has none, [accuracyM] null when unknown. */
    fun onFix(at: Long, speedMps: Float?, accuracyM: Float?) {
        if (accuracyM != null && accuracyM > MAX_ACCURACY_M) return
        val previous = lastFixAt
        lastFixAt = at
        if (speedMps == null) return
        speeds.addLast(speedMps)
        while (speeds.size > SPEED_POINTS) speeds.removeFirst()
        val stopped = (this.speedMps ?: 0f) < STOP_MPS
        val afterGap = previous != null && at - previous > FIX_GAP_MS
        stoppedSince = when {
            !stopped -> null
            stoppedSince == null || afterGap -> at
            else -> stoppedSince
        }
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

        /** Fixes less accurate than this aren't used (as in SanpoGuide). */
        const val MAX_ACCURACY_M = 50f

        /** No fix for this long is a gap, not a stop. */
        const val FIX_GAP_MS = 10_000L
    }
}
