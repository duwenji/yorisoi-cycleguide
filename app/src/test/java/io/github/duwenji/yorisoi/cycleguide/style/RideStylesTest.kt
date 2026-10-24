package io.github.duwenji.yorisoi.cycleguide.style

import io.github.duwenji.yorisoi.kit.geo.Poi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ride styles and the user's changes to them (requirements D-5, F-10〜F-12, AC-6, AC-15). */
class RideStylesTest {
    @Test
    fun `pottering is the default, and every style sets every value within its range`() {
        assertEquals(RideStyles.POTTERING, RideStyles.DEFAULT)
        assertEquals(RideStyles.ALL.size, RideStyles.ALL.map { it.id }.toSet().size)
        for (style in RideStyles.ALL) {
            assertEquals(style.id, StyleItem.entries.toSet(), style.defaults.keys)
            for ((item, value) in style.defaults) assertTrue("${style.id} ${item.name}", value in item.min..item.max)
            assertTrue(style.id, StyleValues(style).conflicts().isEmpty())
        }
    }

    @Test
    fun `the values follow the style chosen`() {
        assertEquals(300, StyleValues(RideStyles.POTTERING)[StyleItem.AHEAD_M])
        assertEquals(800, StyleValues(RideStyles.LONG_RIDE)[StyleItem.AHEAD_M])
        assertEquals(15, StyleValues(RideStyles.POTTERING)[StyleItem.SPEECH_MOVING_SEC])
        assertEquals(10, StyleValues(RideStyles.LONG_RIDE)[StyleItem.SPEECH_MOVING_SEC])
        assertEquals(RideStyles.DEFAULT, RideStyles.byId("retired"))
        assertEquals(RideStyles.LONG_RIDE, RideStyles.byId("long_ride"))
    }

    @Test
    fun `a change keeps to the value's range`() {
        val v = StyleValues(RideStyles.POTTERING).with(StyleItem.AHEAD_M, 5000).with(StyleItem.REST_MIN, 4)
        assertEquals(1500, v[StyleItem.AHEAD_M])
        assertEquals(4 * 60_000L, v.ms(StyleItem.REST_MIN))
        assertEquals(10_000L, v.ms(StyleItem.SIGNAL_STOP_SEC))
    }

    @Test
    fun `combinations that never speak are pointed out`() {
        val crossed = StyleValues(RideStyles.POTTERING).with(StyleItem.SUNSET_LATEST_MIN, 40)
        assertEquals(1, crossed.conflicts().size)
        val noSignal = StyleValues(RideStyles.POTTERING).with(StyleItem.REST_MIN, 1).with(StyleItem.SIGNAL_STOP_SEC, 60)
        assertEquals(1, noSignal.conflicts().size)
    }

    @Test
    fun `a style added as data works without other changes`() {
        val commute = RideStyle("commute", "通勤", "決まった道を急ぐ", SpotScale.MAJOR, emptySet(), RideStyles.POTTERING.defaults + (StyleItem.AHEAD_M to 500))
        assertEquals(500, StyleValues(commute)[StyleItem.AHEAD_M])
        assertTrue(StyleValues(commute).conflicts().isEmpty())
    }

    @Test
    fun `a long ride talks only about well-known spots`() {
        fun poi(tags: Map<String, String>) = Poi("n/1", "どこか", 35.0, 139.0, "神社", tags)
        assertTrue(SpotScale.SMALL.accepts(poi(emptyMap())))
        assertFalse(SpotScale.MAJOR.accepts(poi(emptyMap())))
        assertTrue(SpotScale.MAJOR.accepts(poi(mapOf("wikipedia" to "ja:鶴岡八幡宮"))))
        assertTrue(SpotScale.MAJOR.accepts(poi(mapOf("tourism" to "viewpoint"))))
    }
}
