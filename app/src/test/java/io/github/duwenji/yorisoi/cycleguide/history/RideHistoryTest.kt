package io.github.duwenji.yorisoi.cycleguide.history

import io.github.duwenji.yorisoi.cycleguide.companion.DeferredTalk
import io.github.duwenji.yorisoi.cycleguide.companion.RideDecision
import io.github.duwenji.yorisoi.cycleguide.companion.RideSession
import io.github.duwenji.yorisoi.kit.geo.LatLon
import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.kit.geo.RouteGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.TimeZone

/** The rides finished (requirements F-28, AC-21), the newest on the start screen (F-31, AC-30). */
class RideHistoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val start = 1_761_350_400_000L // 2025-10-25 09:00 JST
    private val here = LatLon(35.0, 139.0)
    private val shrine = Poi("n/1", "鶴岡八幡宮", 35.0, 139.0, "神社", mapOf("wikipedia" to "ja:鶴岡八幡宮"))

    private fun session(startedAt: Long = start): RideSession = RideSession(startedAt).apply {
        useChannel("builtin:cycle-history@1")
        onLocation(here)
        onLocation(RouteGeometry.destination(here, 0.0, 500.0))
        carriedOut(RideDecision.Spot(shrine, 150, 90), startedAt)
        said(shrine, "この先に鶴岡八幡宮があります。", startedAt)
        carriedOut(RideDecision.Deferred(DeferredTalk(shrine, null, startedAt), 300), startedAt)
    }

    private fun record(startedAt: Long = start, recap: String? = null) = RideRecord.of(session(startedAt), startedAt + 60_000, recap)!!

    @Test
    fun `a ride keeps its way, each spot once, what was said and the channel`() {
        val ride = RideRecord.of(session(), start + 65 * 60_000, "おつかれさまでした。")!!
        assertEquals(2, ride.route.size)
        assertEquals(listOf(shrine), ride.spots)
        assertEquals(500.0, ride.distanceM, 0.5)
        assertEquals(listOf(SpotTalk("n/1", "鶴岡八幡宮", start, "この先に鶴岡八幡宮があります。")), ride.talks)
        assertEquals(listOf("builtin:cycle-history@1"), ride.channels)
        assertEquals("おつかれさまでした。", ride.recap)
    }

    @Test
    fun `a ride keeps the frequent ways gone along, and reads them back (REQ-FREQUENT-WAYS F-12)`() {
        val ways = listOf(RideWay("w1", "川沿いの道", 5, new = false), RideWay("w2", "曳舟川親水公園のあたりの道", 3, new = true))
        val ride = record().copy(ways = ways)
        assertEquals(ways, RideHistory.fromJson(RideHistory.toJson(ride)).ways)
        // Rides kept before there were ways.
        assertTrue(RideHistory.fromJson(RideHistory.toJson(record()).apply { remove("ways") }).ways.isEmpty())
    }

    @Test
    fun `the rides since a time are read from their files`() {
        val history = RideHistory(tmp.newFolder("rides"))
        history.add(record(start))
        history.add(record(start + 86_400_000))
        assertEquals(listOf(start + 86_400_000), history.since(start + 86_400_000).map { it.startedAt })
        assertEquals(2, history.since(0).size)
    }

    @Test
    fun `a ride where the rider never moved isn't kept`() {
        assertNull(RideRecord.of(RideSession(start).apply { onLocation(here) }, start + 60_000))
    }

    @Test
    fun `a long ride's way is thinned`() {
        val s = RideSession(start)
        repeat(RideSession.MAX_ROUTE_POINTS) { s.onLocation(RouteGeometry.destination(here, 0.0, it * 10.0)) }
        assertEquals(RideRecord.MAX_POINTS, RideRecord.of(s, start)!!.route.size)
    }

    @Test
    fun `the summary says when, how far and how long`() {
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
        val summary = RideSummary(start, start + 65 * 60_000, 12_345.0, 0, null)
        assertEquals("10月25日 9:00 から・12.3 km・1時間5分", summary.text(tokyo))
        assertEquals("10月25日 9:00 から・12.3 km・25分", summary.copy(endedAt = start + 25 * 60_000).text(tokyo))
    }

    @Test
    fun `rides come back newest first after a restart, and are gone once cleared`() {
        val dir = File(tmp.root, "rides")
        val first = record(start)
        val second = record(start + 86_400_000, recap = "今日もよく走りました。")
        RideHistory(dir).apply {
            add(first)
            add(second)
        }

        val again = RideHistory(dir)
        assertEquals(listOf(second.startedAt, first.startedAt), again.rides.value.map { it.startedAt })
        assertEquals("今日もよく走りました。", again.rides.value.first().recap)
        // Only what the map draws a spot with.
        assertEquals(second.copy(spots = listOf(shrine.copy(tags = emptyMap()))), again.latest.value)
        assertEquals(first.startedAt, again.ride(first.startedAt)!!.startedAt)

        again.clear()
        assertTrue(again.rides.value.isEmpty())
        assertNull(again.latest.value)
        assertTrue(RideHistory(dir).rides.value.isEmpty())
    }

    @Test
    fun `only the newest rides are kept`() {
        val dir = File(tmp.root, "rides")
        val history = RideHistory(dir)
        repeat(RideHistory.MAX_RIDES + 2) { history.add(record(start + it * 60_000L)) }
        assertEquals(RideHistory.MAX_RIDES, history.rides.value.size)
        assertEquals(RideHistory.MAX_RIDES + 1, dir.listFiles()!!.size) // the rides and the list
        assertNull(history.ride(start))
    }

    @Test
    fun `the last ride kept before there was a history is taken in`() {
        val legacy = File(tmp.root, "last-ride.json")
        val old = record().copy(talks = emptyList(), channels = emptyList())
        legacy.writeText(
            RideHistory.toJson(old).apply {
                remove("talks")
                remove("channels")
                remove("recap")
            }.toString(),
        )
        val history = RideHistory(File(tmp.root, "rides"), legacy)
        assertEquals(listOf(start), history.rides.value.map { it.startedAt })
        assertFalse(legacy.exists())
    }

    @Test
    fun `a broken list is no history`() {
        val dir = File(tmp.root, "rides").apply { mkdirs() }
        File(dir, "index.json").writeText("[")
        var errors = 0
        assertTrue(RideHistory(dir) { errors++ }.rides.value.isEmpty())
        assertEquals(1, errors)
    }
}
