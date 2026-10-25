package io.github.duwenji.yorisoi.cycleguide.ride

import io.github.duwenji.yorisoi.cycleguide.companion.DeferredTalk
import io.github.duwenji.yorisoi.cycleguide.companion.RideDecision
import io.github.duwenji.yorisoi.cycleguide.companion.RideSession
import io.github.duwenji.yorisoi.kit.geo.LatLon
import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.kit.geo.RouteGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.TimeZone

/** The ride last finished, kept for the start screen (requirements F-31, AC-30). */
class LastRideTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val start = 1_761_350_400_000L // 2025-10-25 09:00 JST
    private val here = LatLon(35.0, 139.0)
    private val shrine = Poi("n/1", "鶴岡八幡宮", 35.0, 139.0, "神社", mapOf("wikipedia" to "ja:鶴岡八幡宮"))

    private fun ride(): RideSession = RideSession(start).apply {
        onLocation(here)
        onLocation(RouteGeometry.destination(here, 0.0, 500.0))
        carriedOut(RideDecision.Spot(shrine, 150, 90), start)
        carriedOut(RideDecision.Deferred(DeferredTalk(shrine, null, start), 300), start)
    }

    @Test
    fun `a ride keeps its way and each spot talked about once`() {
        val last = LastRide.of(ride(), start + 65 * 60_000)!!
        assertEquals(2, last.route.size)
        assertEquals(listOf(shrine), last.spots)
        assertEquals(500.0, last.distanceM, 0.5)
    }

    @Test
    fun `a ride where the rider never moved isn't kept`() {
        assertNull(LastRide.of(RideSession(start).apply { onLocation(here) }, start + 60_000))
    }

    @Test
    fun `a long ride's way is thinned`() {
        val s = RideSession(start)
        repeat(RideSession.MAX_ROUTE_POINTS) { s.onLocation(RouteGeometry.destination(here, 0.0, it * 10.0)) }
        assertEquals(LastRide.MAX_POINTS, LastRide.of(s, start)!!.route.size)
    }

    @Test
    fun `the summary says when, how far and how long`() {
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
        val last = LastRide(start, start + 65 * 60_000, 12_345.0, emptyList(), emptyList())
        assertEquals("前回の走行: 10月25日 9:00 から・12.3 km・1時間5分", last.summary(tokyo))
        assertEquals("前回の走行: 10月25日 9:00 から・12.3 km・25分", last.copy(endedAt = start + 25 * 60_000).summary(tokyo))
    }

    @Test
    fun `the last ride comes back after a restart, and is gone once cleared`() {
        val file = File(tmp.root, "last-ride.json")
        val last = LastRide.of(ride(), start + 60_000)!!
        LastRideStore(file).save(last)

        val again = LastRideStore(file)
        val read = again.ride.value!!
        assertEquals(last.copy(spots = emptyList()), read.copy(spots = emptyList()))
        // Only what the map draws a spot with.
        assertEquals(listOf(shrine.copy(tags = emptyMap())), read.spots)

        again.clear()
        assertNull(again.ride.value)
        assertNull(LastRideStore(file).ride.value)
    }

    @Test
    fun `a broken file is no last ride`() {
        val file = File(tmp.root, "last-ride.json").apply { writeText("{") }
        var errors = 0
        assertNull(LastRideStore(file) { errors++ }.ride.value)
        assertEquals(1, errors)
    }
}
