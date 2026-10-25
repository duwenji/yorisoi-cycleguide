package io.github.duwenji.yorisoi.cycleguide.companion

import io.github.duwenji.yorisoi.core.format.TalkEventKind
import io.github.duwenji.yorisoi.core.format.TalkLevel
import io.github.duwenji.yorisoi.cycleguide.style.Amenity
import io.github.duwenji.yorisoi.cycleguide.style.RideStyles
import io.github.duwenji.yorisoi.cycleguide.style.StyleValues
import io.github.duwenji.yorisoi.kit.geo.Facility
import io.github.duwenji.yorisoi.kit.geo.FacilityKind
import io.github.duwenji.yorisoi.kit.geo.weather.Weather
import org.junit.Assert.assertEquals
import org.junit.Test

/** Scenes of a ride and the amenity pointed out in each (requirements F-7, AC-14; architecture.md「施設の判定」). */
class AmenityTest {
    private val now = 10_000_000_000L
    private val minute = 60_000L
    private val pottering = StyleValues(RideStyles.POTTERING)
    private val longRide = StyleValues(RideStyles.LONG_RIDE)

    /** An hour into a ride at 15 km/h, nothing else due. */
    private val calm = RideSituation(
        now = now, values = pottering, level = TalkLevel.NORMAL, events = TalkEventKind.entries.toSet(), skippedCategories = emptySet(),
        state = MotionState.MOVING, speedMps = 15 / 3.6f, stoppedMs = 0, quiet = false,
        ahead = emptyList(), deferred = emptyList(), weather = null,
        distanceM = 1000.0, lastSpokeAt = now - 10 * minute, lastSpotTalkAt = now - 10 * minute, lastMilestoneDistanceM = 0.0,
        restRemarked = false, sunsetWarned = false, weatherWarnedAt = emptyMap(), pendingGreeting = null, justSwitched = false,
        startedAt = now - 60 * minute,
    )

    private fun facility(kind: FacilityKind, id: String = kind.name, name: String? = null) = Facility(id, kind, 35.0, 139.0, name)
    private fun ahead(f: Facility, m: Double) = NearFacility(f, m, ahead = true, side = "前方")
    private fun behind(f: Facility, m: Double) = NearFacility(f, m, ahead = false, side = "右手")

    private val toilet = facility(FacilityKind.TOILETS)
    private val parking = facility(FacilityKind.BICYCLE_PARKING)
    private val shop = facility(FacilityKind.BICYCLE_SHOP, name = "鎌倉サイクル")
    private val hot = Weather("晴れ", 30.0, 0.0, code = 0)

    private fun decide(s: RideSituation) = RideDecider.decide(s)
    private fun advice(s: RideSituation) = (decide(s) as? RideDecision.AmenityNear)?.advice

    @Test
    fun `a toilet ahead is pointed out after a while on the bike, and again only after the style's gap`() {
        val s = calm.copy(facilities = listOf(ahead(toilet, 200.0)))
        assertEquals(AmenityAdvice(Amenity.TOILET, toilet, 200, "前方"), advice(s))
        // Too soon after setting off.
        assertEquals(RideDecision.Nothing, decide(s.copy(startedAt = now - 20 * minute)))
        val other = facility(FacilityKind.TOILETS, "t2")
        val again = s.copy(facilities = listOf(ahead(other, 200.0)), mentionedFacilities = setOf(toilet.id))
        assertEquals(RideDecision.Nothing, decide(again.copy(amenityMentionedAt = mapOf(Amenity.TOILET to now - 30 * minute))))
        assertEquals(other, advice(again.copy(amenityMentionedAt = mapOf(Amenity.TOILET to now - 50 * minute)))?.facility)
    }

    @Test
    fun `one already pointed out, behind, too close or past the distance ahead is passed by`() {
        assertEquals(RideDecision.Nothing, decide(calm.copy(facilities = listOf(ahead(toilet, 200.0)), mentionedFacilities = setOf(toilet.id))))
        assertEquals(RideDecision.Nothing, decide(calm.copy(facilities = listOf(behind(toilet, 200.0)))))
        assertEquals(RideDecision.Nothing, decide(calm.copy(facilities = listOf(ahead(toilet, 30.0)))))
        assertEquals(RideDecision.Nothing, decide(calm.copy(facilities = listOf(ahead(toilet, 400.0)))))
    }

    @Test
    fun `each style points out only its own amenities`() {
        // Pottering: parking while resting, never a bicycle shop.
        val resting = calm.copy(state = MotionState.REST, speedMps = 0f, stoppedMs = 4 * minute, restRemarked = true)
        assertEquals(Amenity.BICYCLE_PARKING, advice(resting.copy(facilities = listOf(behind(parking, 120.0))))?.amenity)
        assertEquals(RideDecision.Nothing, decide(calm.copy(facilities = listOf(ahead(shop, 200.0)))))
        // A long ride: a bicycle shop, never parking.
        assertEquals(Amenity.BICYCLE_SHOP, advice(calm.copy(values = longRide, facilities = listOf(ahead(shop, 600.0))))?.amenity)
        assertEquals(RideDecision.Nothing, decide(resting.copy(values = longRide, facilities = listOf(behind(parking, 120.0)))))
    }

    @Test
    fun `parking only while resting, a bicycle shop once a ride`() {
        assertEquals(RideDecision.Nothing, decide(calm.copy(facilities = listOf(behind(parking, 120.0)))))
        val ride = calm.copy(values = longRide, facilities = listOf(ahead(shop, 600.0)))
        assertEquals(RideDecision.Nothing, decide(ride.copy(amenityMentionedAt = mapOf(Amenity.BICYCLE_SHOP to now - 300 * minute))))
        // Right after setting off too.
        assertEquals(Amenity.BICYCLE_SHOP, advice(ride.copy(startedAt = now - minute))?.amenity)
    }

    @Test
    fun `water only when it's hot`() {
        val water = facility(FacilityKind.DRINKING_WATER)
        val s = calm.copy(startedAt = now - 10 * minute, facilities = listOf(ahead(water, 150.0)))
        assertEquals(RideDecision.Nothing, decide(s))
        assertEquals(Amenity.WATER, advice(s.copy(weather = hot))?.amenity)
    }

    @Test
    fun `near a light, at speed, or right after another line, nothing`() {
        val s = calm.copy(facilities = listOf(ahead(toilet, 200.0)))
        assertEquals(RideDecision.Nothing, decide(s.copy(quiet = true)))
        assertEquals(RideDecision.Nothing, decide(s.copy(lastSpokeAt = now - minute)))
        assertEquals(RideDecision.Nothing, decide(s.copy(justSwitched = true)))
    }

    @Test
    fun `the line says how far, by 50m, and the name when there is one`() {
        assertEquals("200メートル先に、トイレがあります。", Lines.amenity(AmenityAdvice(Amenity.TOILET, toilet, 213, "前方")))
        assertEquals("50メートル先に、自転車店「鎌倉サイクル」があります。", Lines.amenity(AmenityAdvice(Amenity.BICYCLE_SHOP, shop, 52, "前方")))
        assertEquals("近くの右手100メートルに、駐輪場があります。", Lines.amenity(AmenityAdvice(Amenity.BICYCLE_PARKING, parking, 120, "右手")))
    }

    @Test
    fun `every style's amenities are searched`() {
        val kinds = RideStyles.ALL.flatMap { style -> style.amenities.flatMap { it.kinds } }.toSet()
        assertEquals(kinds, kinds intersect Amenity.SEARCHED)
    }
}
