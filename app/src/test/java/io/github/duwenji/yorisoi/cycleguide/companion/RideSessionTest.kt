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
    fun `the way ridden is kept for the map, thinned on a long ride`() {
        val s = RideSession(start)
        repeat(RideSession.MAX_ROUTE_POINTS + 1) { i -> s.onLocation(RouteGeometry.destination(here, 0.0, i * 10.0)) }
        assertTrue(s.route.size <= RideSession.MAX_ROUTE_POINTS)
        assertEquals(here, s.route.first())
        assertEquals(RideSession.MAX_ROUTE_POINTS * 10.0, s.distanceM, 1.0)
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
    fun `a point cue's line kept for a stop is said there, and isn't a spot talked about (F-32)`() {
        val s = RideSession(start)
        s.keepPoint("資料で話す", 35.0, 139.0, "地点の話", "文: 運営の AI", start)
        val kept = s.deferred.single()
        assertEquals(null, kept.poi)
        assertEquals(LatLon(35.0, 139.0), LatLon(kept.lat, kept.lon))
        s.carriedOut(RideDecision.Deferred(kept, 150), start + 10_000)
        assertTrue(s.deferred.isEmpty())
        assertTrue(s.spotNames.isEmpty())
        assertEquals(start + 10_000, s.lastSpotTalkAt)
    }

    @Test
    fun `the last four lines and the spots talked about are remembered`() {
        val s = RideSession(start)
        listOf("一", "二", "三", "四", "五").forEach(s::remember)
        assertEquals(listOf("二", "三", "四", "五"), s.recentLines)
        s.carriedOut(RideDecision.Spot(shrine, 150, 90), start)
        assertEquals(listOf("鶴岡八幡宮"), s.spotNames)
        assertEquals(listOf(shrine), s.spots)
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
    fun `the notice before the first ride tells the rules on phones and earphones`() {
        assertTrue(Lines.SAFETY_NOTICE.contains("道路交通法"))
        assertTrue(Lines.SAFETY_NOTICE.contains("12,000 円"))
        assertTrue(Lines.SAFETY_NOTICE.contains("周りの音が聞こえる状態で使ってください"))
    }

    @Test
    fun `the earphone reminder comes once a ride, when stopped`() {
        assertTrue(EarphoneRule.shouldNotice(connected = true, state = MotionState.SIGNAL_STOP, noticedThisRide = false))
        assertFalse(EarphoneRule.shouldNotice(connected = true, state = MotionState.MOVING, noticedThisRide = false))
        assertFalse(EarphoneRule.shouldNotice(connected = true, state = MotionState.REST, noticedThisRide = true))
        assertFalse(EarphoneRule.shouldNotice(connected = false, state = MotionState.REST, noticedThisRide = false))
    }
}
