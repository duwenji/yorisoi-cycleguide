package io.github.duwenji.yorisoi.cycleguide.companion

import io.github.duwenji.yorisoi.cycleguide.ride.EarphoneRule
import io.github.duwenji.yorisoi.cycleguide.style.RideStyles
import io.github.duwenji.yorisoi.cycleguide.style.StyleValues
import io.github.duwenji.yorisoi.kit.geo.LatLon
import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.kit.geo.RouteGeometry
import io.github.duwenji.yorisoi.kit.geo.weather.WeatherChange
import io.github.duwenji.yorisoi.kit.geo.weather.WeatherChangeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One ride's state as decisions are carried out, the fixed lines, and the earphone reminder. */
class RideSessionTest {
    private val start = 1_000_000L
    private val here = LatLon(35.0, 139.0)
    private val shrine = Poi("n/1", "鶴岡八幡宮", 35.0, 139.0, "神社", emptyMap())

    @Test
    fun `the distance adds up, without the jitter of standing still`() {
        val s = RideSession(start)
        s.onLocation(here)
        s.onLocation(RouteGeometry.destination(here, 0.0, 1.0))
        s.onLocation(RouteGeometry.destination(here, 0.0, 100.0))
        assertEquals(100.0, s.distanceM, 0.5)
        assertEquals(2, s.track.size)
    }

    @Test
    fun `a spot kept for later is remembered, and said once at a stop`() {
        val s = RideSession(start)
        s.carriedOut(RideDecision.Defer(shrine), start + 1_000)
        assertTrue(s.alreadyTalked(shrine))
        assertEquals(1, s.deferred.size)
        // Keeping it says nothing.
        assertEquals(0L, s.lastSpokeAt)
        s.carriedOut(RideDecision.Deferred(s.deferred.first(), 180), start + 60_000)
        assertTrue(s.deferred.isEmpty())
        assertEquals(start + 60_000, s.lastSpotTalkAt)
        assertEquals(start + 60_000, s.lastSpokeAt)
    }

    @Test
    fun `a line too long for riding is kept, written, with its credit`() {
        val s = RideSession(start)
        s.keepLonger(shrine, "長い話", "文: 運営の AI", start)
        assertTrue(s.alreadyTalked(shrine))
        assertEquals(DeferredTalk(shrine, "長い話", start, "文: 運営の AI"), s.deferred.single())
        assertEquals(0L, s.lastSpokeAt)
    }

    @Test
    fun `the last four lines and the spots talked about are remembered`() {
        val s = RideSession(start)
        listOf("一", "二", "三", "四", "五").forEach(s::remember)
        assertEquals(listOf("二", "三", "四", "五"), s.recentLines)
        s.carriedOut(RideDecision.Spot(shrine, 150, 90), start)
        assertEquals(listOf("鶴岡八幡宮"), s.spotNames)
    }

    @Test
    fun `kept talks far behind are dropped`() {
        val s = RideSession(start)
        s.carriedOut(RideDecision.Defer(shrine), start)
        s.prune(start + 60_000, RouteGeometry.destination(here, 0.0, 600.0))
        assertTrue(s.deferred.isEmpty())
    }

    @Test
    fun `warnings, the greeting, rests and milestones are noted`() {
        val s = RideSession(start)
        s.switchedChannel(start, "よろしく")
        assertTrue(s.justSwitched(start + 30_000))
        assertFalse(s.justSwitched(start + 61_000))
        s.carriedOut(RideDecision.Greeting("よろしく"), start)
        assertNull(s.pendingGreeting)
        s.carriedOut(RideDecision.WeatherTurn(WeatherChange(WeatherChangeKind.RAIN, 20, "雨")), start + 1)
        assertEquals(start + 1, s.weatherWarnedAt[WeatherChangeKind.RAIN])
        s.carriedOut(RideDecision.Sunset(20), start + 2)
        assertTrue(s.sunsetWarned)
        s.carriedOut(RideDecision.Rest(4, 360), start + 3)
        assertTrue(s.restRemarked)
        s.onMoving()
        assertFalse(s.restRemarked)
        s.onLocation(here)
        s.onLocation(RouteGeometry.destination(here, 0.0, 3000.0))
        s.carriedOut(RideDecision.Milestone(90), start + 4)
        assertEquals(s.distanceM, s.lastMilestoneDistanceM, 0.0)
    }

    @Test
    fun `every fixed line fits the shortest riding length`() {
        val shortest = SpeechBudget.maxChars(StyleValues(RideStyles.LONG_RIDE), MotionState.MOVING)
        val decisions = listOf(
            RideDecision.WeatherTurn(WeatherChange(WeatherChangeKind.THUNDER, 30, "雷雨")),
            RideDecision.WeatherTurn(WeatherChange(WeatherChangeKind.HEAVY_RAIN, 0, "強い雨")),
            RideDecision.WeatherTurn(WeatherChange(WeatherChangeKind.RAIN, 15, "雨")),
            RideDecision.Sunset(30),
            RideDecision.Spot(shrine, 150, 90),
            RideDecision.Deferred(DeferredTalk(shrine, null, start), 180),
            RideDecision.Rest(4, 360),
            RideDecision.Milestone(90),
        )
        for (d in decisions) {
            val line = Lines.of(d)!!
            assertTrue(line, SpeechBudget.fits(line, shortest))
        }
        assertTrue(Lines.of(RideDecision.Sunset(30))!!.contains("ライト"))
        assertNull(Lines.of(RideDecision.Defer(shrine)))
        assertNull(Lines.of(RideDecision.Nothing))
    }

    @Test
    fun `the earphone reminder comes once a ride, when stopped`() {
        assertTrue(EarphoneRule.shouldNotice(connected = true, state = MotionState.SIGNAL_STOP, noticedThisRide = false))
        assertFalse(EarphoneRule.shouldNotice(connected = true, state = MotionState.MOVING, noticedThisRide = false))
        assertFalse(EarphoneRule.shouldNotice(connected = true, state = MotionState.REST, noticedThisRide = true))
        assertFalse(EarphoneRule.shouldNotice(connected = false, state = MotionState.REST, noticedThisRide = false))
    }
}
