package io.github.duwenji.yorisoi.cycleguide.companion

import io.github.duwenji.yorisoi.cycleguide.style.RideStyles
import io.github.duwenji.yorisoi.cycleguide.style.StyleValues
import org.junit.Assert.assertEquals
import org.junit.Test

/** Moving, waiting at a light, resting (requirements F-3, AC-2, AC-26). */
class MotionTest {
    private val start = 1_000_000L
    private val motion = Motion { StyleValues(RideStyles.POTTERING) }

    /** Fixes every 2 seconds from [from] for [seconds], all at [speedMps]. */
    private fun ride(from: Long, seconds: Int, speedMps: Float): Long {
        var t = from
        repeat(seconds / 2) {
            t += 2_000
            motion.onFix(t, speedMps, 5f)
        }
        return t
    }

    @Test
    fun `stopped for 10 seconds is a light, for 3 minutes a rest`() {
        var t = ride(start, 20, 4.2f)
        assertEquals(MotionState.MOVING, motion.state(t))
        t = ride(t, 6, 0f)
        assertEquals(MotionState.MOVING, motion.state(t))
        t = ride(t, 10, 0f)
        assertEquals(MotionState.SIGNAL_STOP, motion.state(t))
        t = ride(t, 180, 0f)
        assertEquals(MotionState.REST, motion.state(t))
        t = ride(t, 6, 4.2f)
        assertEquals(MotionState.MOVING, motion.state(t))
    }

    @Test
    fun `walking the bike is moving`() {
        val t = ride(start, 60, 1.4f)
        assertEquals(MotionState.MOVING, motion.state(t))
    }

    @Test
    fun `one jump of the GPS doesn't stop the ride`() {
        var t = ride(start, 10, 4.2f)
        t += 2_000
        motion.onFix(t, 0f, 5f)
        t = ride(t, 20, 4.2f)
        assertEquals(MotionState.MOVING, motion.state(t))
        assertEquals(4.2f, motion.speedMps)
    }

    @Test
    fun `no fix for 30 seconds (a tunnel) is no stop`() {
        val t = ride(start, 10, 4.2f)
        assertEquals(MotionState.MOVING, motion.state(t + 30_000))
        // Stopped just before the tunnel: the time without fixes isn't counted.
        val stopped = ride(t, 8, 0f)
        assertEquals(MotionState.MOVING, motion.state(stopped + 60_000))
        assertEquals(stopped - (t + 4_000), motion.stoppedMs(stopped + 60_000))
    }

    @Test
    fun `after a gap a stop is counted from the first fix`() {
        var t = ride(start, 10, 0f)
        t += 60_000
        motion.onFix(t, 0f, 5f)
        assertEquals(0L, motion.stoppedMs(t))
    }

    @Test
    fun `inaccurate fixes are not used`() {
        var t = ride(start, 10, 4.2f)
        repeat(10) {
            t += 2_000
            motion.onFix(t, 0f, 80f)
        }
        assertEquals(4.2f, motion.speedMps)
        assertEquals(MotionState.MOVING, motion.state(t))
    }
}
