package io.github.duwenji.yorisoi.cycleguide.companion

import io.github.duwenji.yorisoi.cycleguide.style.RideStyles
import io.github.duwenji.yorisoi.cycleguide.style.StyleValues
import io.github.duwenji.yorisoi.kit.geo.LatLon
import io.github.duwenji.yorisoi.kit.geo.RouteGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fan ahead, where to search, and when to start (requirements F-2, AC-1, AC-3). */
class AheadTest {
    private val here = LatLon(35.0, 139.0)
    private fun at(bearing: Double, m: Double) = RouteGeometry.destination(here, bearing, m)

    @Test
    fun `the way comes from the fix when moving, else from the track`() {
        assertEquals(90.0, Ahead.heading(90f, 4f, emptyList())!!, 0.01)
        val track = listOf(at(180.0, 50.0), at(180.0, 20.0), here)
        assertEquals(0.0, Ahead.heading(90f, 0.3f, track)!!, 0.5)
        assertNull(Ahead.heading(null, null, listOf(at(180.0, 10.0), here)))
    }

    @Test
    fun `only what is in the fan in front, and near enough`() {
        assertTrue(Ahead.isAhead(here, 0.0, at(30.0, 200.0), 300.0))
        assertTrue(Ahead.isAhead(here, 350.0, at(20.0, 200.0), 300.0))
        assertFalse(Ahead.isAhead(here, 0.0, at(60.0, 200.0), 300.0))
        assertFalse(Ahead.isAhead(here, 0.0, at(180.0, 100.0), 300.0))
        assertFalse(Ahead.isAhead(here, 0.0, at(0.0, 400.0), 300.0))
    }

    @Test
    fun `the search is half the way ahead`() {
        val center = Ahead.searchCenter(here, 90.0, 300)
        assertEquals(150.0, RouteGeometry.distanceM(here, center), 0.5)
        assertEquals(here, Ahead.searchCenter(here, null, 300))
    }

    @Test
    fun `at 15 km per hour a line about a spot 300m ahead ends at least 50m before it`() {
        val speed = 15 / 3.6f
        val talkSec = SpeechBudget.seconds(StyleValues(RideStyles.POTTERING), MotionState.MOVING)
        assertEquals(Timing.WAIT, Ahead.timing(300.0, speed, talkSec))
        // Ride towards it in 2-second steps until it is time.
        var d = 300.0
        while (Ahead.timing(d, speed, talkSec) == Timing.WAIT) d -= speed * 2
        assertEquals(Timing.NOW, Ahead.timing(d, speed, talkSec))
        val endsAt = d - speed * (Ahead.COMPOSE_SEC + talkSec)
        assertTrue("ends ${endsAt}m before", endsAt >= Ahead.MARGIN_M)
    }

    @Test
    fun `the line is waited for until it can only just end before the spot`() {
        // 300 m at 5 m/s: 50 s to the margin, less 15 s of talk.
        assertEquals(35.0, Ahead.answerSec(300.0, 5f, 15)!!, 0.01)
        // Started late: still the time to write it.
        assertEquals(Ahead.COMPOSE_SEC, Ahead.answerSec(120.0, 5f, 15)!!, 0.01)
        assertNull(Ahead.answerSec(300.0, 0f, 15))
    }

    @Test
    fun `a spot seen too late is kept for later`() {
        assertEquals(Timing.TOO_LATE, Ahead.timing(80.0, 15 / 3.6f, 15))
        assertEquals(Timing.NOW, Ahead.timing(80.0, 0f, 15))
    }
}
