package io.github.duwenji.yorisoi.cycleguide.companion

import io.github.duwenji.yorisoi.cycleguide.style.RideStyles
import io.github.duwenji.yorisoi.cycleguide.style.StyleItem
import io.github.duwenji.yorisoi.cycleguide.style.StyleValues
import io.github.duwenji.yorisoi.kit.geo.LatLon
import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.kit.geo.RouteGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How long to talk (F-4, D-6), where to keep quiet (F-5, AC-4) and which kept talks still count. */
class SpeechAndQuietTest {
    private val pottering = StyleValues(RideStyles.POTTERING)
    private val here = LatLon(35.0, 139.0)

    @Test
    fun `short while riding, longer at a light, longest at a rest`() {
        assertEquals(15 * 6, SpeechBudget.maxChars(pottering, MotionState.MOVING))
        assertEquals(30 * 6, SpeechBudget.maxChars(pottering, MotionState.SIGNAL_STOP))
        assertEquals(60 * 6, SpeechBudget.maxChars(pottering, MotionState.REST))
        assertEquals(10 * 6, SpeechBudget.maxChars(StyleValues(RideStyles.LONG_RIDE), MotionState.MOVING))
        assertEquals(5, SpeechBudget.chars("鳥居 が\n見え"))
        assertTrue(SpeechBudget.fits("あ".repeat(90), 90))
        assertFalse(SpeechBudget.fits("あ".repeat(91), 90))
    }

    @Test
    fun `quiet near a light and at speed, only while moving`() {
        val signal = RouteGeometry.destination(here, 0.0, 20.0)
        val quietKmh = pottering[StyleItem.QUIET_SPEED_KMH]
        assertTrue(QuietZones.isQuiet(MotionState.MOVING, here, 4f, listOf(signal), quietKmh))
        assertFalse(QuietZones.isQuiet(MotionState.MOVING, here, 4f, listOf(RouteGeometry.destination(here, 0.0, 40.0)), quietKmh))
        assertTrue(QuietZones.isQuiet(MotionState.MOVING, here, 25 / 3.6f, emptyList(), quietKmh))
        assertFalse(QuietZones.isQuiet(MotionState.MOVING, here, 24 / 3.6f, emptyList(), quietKmh))
        // Waiting at the light itself.
        assertFalse(QuietZones.isQuiet(MotionState.SIGNAL_STOP, here, 0f, listOf(signal), quietKmh))
    }

    @Test
    fun `kept talks expire, and are dropped once far behind`() {
        val poi = Poi("n/1", "鶴岡八幡宮", 35.0, 139.0, "神社", emptyMap())
        val kept = DeferredTalk(poi, null, at = 0)
        assertEquals(listOf(kept), DeferredTalk.prune(listOf(kept), 9 * 60_000L, here))
        assertTrue(DeferredTalk.prune(listOf(kept), 11 * 60_000L, here).isEmpty())
        assertTrue(DeferredTalk.prune(listOf(kept), 60_000L, RouteGeometry.destination(here, 0.0, 600.0)).isEmpty())
    }
}
