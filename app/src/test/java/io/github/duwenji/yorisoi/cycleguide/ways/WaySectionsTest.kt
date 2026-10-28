package io.github.duwenji.yorisoi.cycleguide.ways

import io.github.duwenji.yorisoi.cycleguide.companion.Lines
import io.github.duwenji.yorisoi.cycleguide.history.RideRecord
import io.github.duwenji.yorisoi.cycleguide.history.SpotTalk
import io.github.duwenji.yorisoi.kit.geo.FrequentWay
import io.github.duwenji.yorisoi.kit.geo.LatLon
import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.kit.geo.RouteGeometry
import io.github.duwenji.yorisoi.kit.geo.WayOrigin
import org.junit.Assert.assertEquals
import org.junit.Test

/** A way made from part of a ride, and what was said along a way (REQ-FREQUENT-WAYS F-11, F-13, F-16). */
class WaySectionsTest {
    private val start = LatLon(35.70, 139.80)
    private val line = (0..20).map { RouteGeometry.destination(start, 90.0, it * 100.0) }

    @Test
    fun `a part of a ride is the points between two distances along it`() {
        assertEquals(2000.0, WaySections.distances(line).last(), 1.0)
        val part = WaySections.section(line, 150.0, 1850.0)
        assertEquals(17, part.size)
        assertEquals(line[2], part.first())
    }

    @Test
    fun `the talks along a way are the spots near it, the last of each`() {
        val near = Poi("1", "曳舟川親水公園", line[5].lat + 0.0005, line[5].lon, "公園", emptyMap())
        val far = Poi("2", "遠くの寺", line[5].lat + 0.01, line[5].lon, "寺院", emptyMap())
        fun ride(at: Long, text: String) = RideRecord(
            at - 1000, at, 2000.0, line, listOf(near, far),
            talks = listOf(SpotTalk(near.id, near.name, at, text), SpotTalk(far.id, far.name, at, "寺です。")),
        )
        val way = FrequentWay("w", "道", line, 2000.0, WayOrigin.FOUND, false, 3, 0, 0)
        val talks = WaySections.talksOn(way, listOf(ride(1_000, "前の話。"), ride(2_000, "新しい話。")))
        assertEquals(listOf("新しい話。"), talks.map { it.text })
    }

    @Test
    fun `a spot talked about on an earlier ride is said to be passed again`() {
        val park = Poi("1", "東和親水公園", 35.0, 139.0, "公園", emptyMap())
        assertEquals("また東和親水公園の横を通ります。", Lines.again(park))
        assertEquals("さきほどは、また東和親水公園の横を通りました。", Lines.again(park, passed = true))
    }
}
