package io.github.duwenji.yorisoi.cycleguide.companion

import io.github.duwenji.yorisoi.core.format.TalkEventKind
import io.github.duwenji.yorisoi.core.format.TalkLevel
import io.github.duwenji.yorisoi.cycleguide.style.RideStyles
import io.github.duwenji.yorisoi.cycleguide.style.StyleValues
import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.kit.geo.weather.ForecastSlot
import io.github.duwenji.yorisoi.kit.geo.weather.Weather
import io.github.duwenji.yorisoi.kit.geo.weather.WeatherChangeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scenes of a ride and what the companion does in each (architecture.md 図 2): every rule once
 * where it speaks and once where it holds back, and the clashes between them.
 */
class RideDeciderTest {
    private val now = 10_000_000_000L
    private val minute = 60_000L
    private val pottering = StyleValues(RideStyles.POTTERING)

    /** Riding at 15 km/h for a while, nothing around, nothing due. */
    private val calm = RideSituation(
        now = now, values = pottering, level = TalkLevel.NORMAL, events = TalkEventKind.entries.toSet(), skippedCategories = emptySet(),
        state = MotionState.MOVING, speedMps = 15 / 3.6f, stoppedMs = 0, quiet = false,
        ahead = emptyList(), deferred = emptyList(), weather = null,
        distanceM = 1000.0, lastSpokeAt = now - 10 * minute, lastSpotTalkAt = now - 10 * minute, lastMilestoneDistanceM = 0.0,
        restRemarked = false, sunsetWarned = false, weatherWarnedAt = emptyMap(), pendingGreeting = null, justSwitched = false,
    )

    private fun poi(name: String, category: String = "神社", tags: Map<String, String> = emptyMap()) =
        Poi(name, name, 35.0, 139.0, category, tags)

    private val shrine = poi("鶴岡八幡宮")
    private val thunderComing = Weather("晴れ", 20.0, 0.0, code = 0, forecast = listOf(ForecastSlot(now + 15 * minute, 95, 0.0)))
    private fun sunsetIn(minutes: Int) = Weather("晴れ", 20.0, 0.0, sunsetAt = now + minutes * minute, code = 0)

    private fun decide(s: RideSituation) = RideDecider.decide(s)

    @Test
    fun `nothing due, nothing said`() {
        assertEquals(RideDecision.Nothing, decide(calm))
    }

    @Test
    fun `a spot ahead is talked about in time to finish before it, within the riding length`() {
        assertEquals(RideDecision.Nothing, decide(calm.copy(ahead = listOf(AheadSpot(shrine, 290.0)))))
        val due = decide(calm.copy(ahead = listOf(AheadSpot(shrine, 160.0))))
        assertEquals(RideDecision.Spot(shrine, 160, 15 * SpeechBudget.CHARS_PER_SEC), due)
    }

    @Test
    fun `a spot seen too late is kept for the next stop, and said there`() {
        assertEquals(RideDecision.Defer(shrine), decide(calm.copy(ahead = listOf(AheadSpot(shrine, 70.0)))))
        val kept = DeferredTalk(shrine, null, now - minute)
        val atLight = calm.copy(state = MotionState.SIGNAL_STOP, speedMps = 0f, stoppedMs = 12_000, deferred = listOf(kept))
        assertEquals(RideDecision.Deferred(kept, 30 * SpeechBudget.CHARS_PER_SEC), decide(atLight))
        // Not while riding.
        assertEquals(RideDecision.Nothing, decide(calm.copy(deferred = listOf(kept))))
    }

    @Test
    fun `a kept line too long for a light waits for a longer stop`() {
        val long = DeferredTalk(shrine, "あ".repeat(200), now - minute)
        val atLight = calm.copy(state = MotionState.SIGNAL_STOP, speedMps = 0f, stoppedMs = 12_000, deferred = listOf(long))
        assertEquals(RideDecision.Nothing, decide(atLight))
        val resting = atLight.copy(state = MotionState.REST, stoppedMs = 4 * minute, restRemarked = true)
        assertEquals(RideDecision.Deferred(long, 60 * SpeechBudget.CHARS_PER_SEC), decide(resting))
    }

    @Test
    fun `near a light or at speed only safety speaks`() {
        val quiet = calm.copy(quiet = true, ahead = listOf(AheadSpot(shrine, 160.0)), pendingGreeting = "こんにちは")
        assertEquals(RideDecision.Nothing, decide(quiet))
        assertTrue(decide(quiet.copy(weather = thunderComing)) is RideDecision.WeatherTurn)
        assertEquals(RideDecision.Sunset(30), decide(quiet.copy(weather = sunsetIn(30))))
    }

    @Test
    fun `the weather turning and the sunset come first, whatever the channel`() {
        val busy = calm.copy(ahead = listOf(AheadSpot(shrine, 160.0)), pendingGreeting = "こんにちは", justSwitched = true, events = emptySet())
        val turn = decide(busy.copy(weather = thunderComing)) as RideDecision.WeatherTurn
        assertEquals(WeatherChangeKind.THUNDER, turn.change.kind)
        assertEquals(RideDecision.Sunset(30), decide(busy.copy(weather = sunsetIn(30), level = TalkLevel.QUIET)))
        // Warned already.
        assertEquals(RideDecision.Greeting("こんにちは"), decide(busy.copy(weather = sunsetIn(30), sunsetWarned = true)))
        assertEquals(
            RideDecision.Greeting("こんにちは"),
            decide(busy.copy(weather = thunderComing, weatherWarnedAt = mapOf(WeatherChangeKind.THUNDER to now - 10 * minute))),
        )
    }

    @Test
    fun `the sunset is told from the style's minutes before`() {
        assertEquals(RideDecision.Nothing, decide(calm.copy(weather = sunsetIn(31))))
        assertEquals(RideDecision.Nothing, decide(calm.copy(weather = sunsetIn(4))))
        val longRide = calm.copy(values = StyleValues(RideStyles.LONG_RIDE))
        assertEquals(RideDecision.Sunset(60), decide(longRide.copy(weather = sunsetIn(60))))
    }

    @Test
    fun `spots keep their gap, longer on a quieter channel`() {
        val ahead = calm.copy(ahead = listOf(AheadSpot(shrine, 160.0)), lastSpotTalkAt = now - 2 * minute)
        assertEquals(RideDecision.Nothing, decide(ahead))
        assertTrue(decide(ahead.copy(lastSpotTalkAt = now - 3 * minute)) is RideDecision.Spot)
        assertEquals(RideDecision.Nothing, decide(ahead.copy(lastSpotTalkAt = now - 5 * minute, level = TalkLevel.QUIET)))
        assertTrue(decide(ahead.copy(lastSpotTalkAt = now - 2 * minute, level = TalkLevel.CHATTY)) is RideDecision.Spot)
    }

    @Test
    fun `spots the channel skips, talked about, or too small for the style are passed by`() {
        val small = AheadSpot(shrine, 160.0)
        assertEquals(RideDecision.Nothing, decide(calm.copy(ahead = listOf(small), skippedCategories = setOf("神社"))))
        assertEquals(RideDecision.Nothing, decide(calm.copy(ahead = listOf(small.copy(alreadyThisRide = true)))))
        assertEquals(RideDecision.Nothing, decide(calm.copy(ahead = listOf(small), events = TalkEventKind.entries.toSet() - TalkEventKind.SPOT)))
        val longRide = calm.copy(values = StyleValues(RideStyles.LONG_RIDE), speedMps = 25 / 3.6f)
        assertEquals(RideDecision.Nothing, decide(longRide.copy(ahead = listOf(AheadSpot(shrine, 250.0)))))
        val famous = poi("鎌倉大仏", "寺", mapOf("wikipedia" to "ja:高徳院"))
        assertTrue(decide(longRide.copy(ahead = listOf(AheadSpot(famous, 250.0)))) is RideDecision.Spot)
    }

    @Test
    fun `the nearest spot ahead comes first`() {
        val far = poi("遠いお寺", "寺")
        val due = decide(calm.copy(ahead = listOf(AheadSpot(far, 170.0), AheadSpot(shrine, 160.0))))
        assertEquals(shrine, (due as RideDecision.Spot).poi)
    }

    @Test
    fun `a rest is remarked on once`() {
        val resting = calm.copy(state = MotionState.REST, speedMps = 0f, stoppedMs = 4 * minute)
        assertEquals(RideDecision.Rest(4, 60 * SpeechBudget.CHARS_PER_SEC), decide(resting))
        assertEquals(RideDecision.Nothing, decide(resting.copy(restRemarked = true)))
        assertEquals(RideDecision.Nothing, decide(resting.copy(lastSpokeAt = now - minute)))
        assertEquals(RideDecision.Nothing, decide(calm.copy(state = MotionState.SIGNAL_STOP, speedMps = 0f, stoppedMs = 12_000)))
    }

    @Test
    fun `a milestone every few kilometres, as the style says`() {
        assertEquals(RideDecision.Milestone(15 * SpeechBudget.CHARS_PER_SEC), decide(calm.copy(distanceM = 3200.0)))
        assertEquals(RideDecision.Nothing, decide(calm.copy(distanceM = 3200.0, lastMilestoneDistanceM = 1000.0)))
        assertEquals(RideDecision.Nothing, decide(calm.copy(distanceM = 3200.0, values = StyleValues(RideStyles.LONG_RIDE))))
    }

    @Test
    fun `right after a switch the greeting has the floor`() {
        val switched = calm.copy(pendingGreeting = "歴史の道を走りましょう", justSwitched = true, ahead = listOf(AheadSpot(shrine, 160.0)))
        assertEquals(RideDecision.Greeting("歴史の道を走りましょう"), decide(switched))
        assertEquals(RideDecision.Nothing, decide(switched.copy(pendingGreeting = null)))
    }
}
