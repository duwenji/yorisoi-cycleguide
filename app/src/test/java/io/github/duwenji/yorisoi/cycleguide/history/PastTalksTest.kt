package io.github.duwenji.yorisoi.cycleguide.history

import io.github.duwenji.yorisoi.kit.geo.LatLon
import io.github.duwenji.yorisoi.kit.geo.Poi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What was said on earlier rides (REQ-FREQUENT-WAYS F-9〜F-11). */
class PastTalksTest {
    private val day = PastTalks.RECENT_MS
    private val now = 1_800_000_000_000L
    private val park = Poi("w/1", "東和親水公園", 35.77, 139.85, "公園", emptyMap())

    private fun ride(endedAt: Long, poi: Poi, text: String) = RideRecord(
        startedAt = endedAt - 3_600_000, endedAt = endedAt, distanceM = 10_000.0, route = listOf(LatLon(35.77, 139.85), LatLon(35.78, 139.85)),
        spots = listOf(poi), talks = listOf(SpotTalk(poi.id, poi.name, endedAt - 60_000, text)),
    )

    @Test
    fun `a spot talked about less than a day ago is not talked about again`() {
        val past = PastTalks.of(listOf(ride(now - day / 2, park, "公園です。")))
        assertTrue(past.recent(park, now))
        assertNull(past.before(park, now))
    }

    @Test
    fun `a spot talked about before that is, with what was said last time`() {
        val past = PastTalks.of(listOf(ride(now - 3 * day, park, "前の話。"), ride(now - 2 * day, park, "この前の話。")))
        assertFalse(past.recent(park, now))
        assertEquals("この前の話。", past.before(park, now))
    }

    @Test
    fun `the same name close by is the same place, far away it is not`() {
        val past = PastTalks.of(listOf(ride(now - 2 * day, park, "公園です。")))
        assertEquals("公園です。", past.before(park.copy(id = "n/9", lat = 35.771), now))
        assertNull(past.last(park.copy(id = "n/9", lat = 35.9)))
        assertNull(PastTalks.NONE.last(park))
    }
}
