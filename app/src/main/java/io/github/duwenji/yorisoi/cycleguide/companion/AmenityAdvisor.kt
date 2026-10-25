package io.github.duwenji.yorisoi.cycleguide.companion

import io.github.duwenji.yorisoi.cycleguide.style.Amenity
import io.github.duwenji.yorisoi.cycleguide.style.StyleItem
import io.github.duwenji.yorisoi.kit.geo.Facility

/**
 * A facility around the rider, as the ride service sees it.
 *
 * @param ahead in the fan ahead ([Ahead.isAhead])
 * @param side where it lies from the way the rider goes (kit-geo `relativeDirection`)
 */
data class NearFacility(val facility: Facility, val distanceM: Double, val ahead: Boolean, val side: String)

/** A facility worth pointing out now, and why ([amenity]). */
data class AmenityAdvice(val amenity: Amenity, val facility: Facility, val distanceM: Int, val side: String)

/**
 * Picks the facility to point out now, if any (requirements F-7, architecture.md「施設の判定」).
 * Only the style's amenities, each when the ride calls for it: toilets, a rest and a shop to stock
 * up after a while on the bike, water when it's hot, parking when the rider stops for a rest, and
 * a bicycle shop or a pump once a ride. Ahead of the rider only, but for parking. Each facility
 * once a ride. Pure logic, no Android calls.
 */
object AmenityAdvisor {
    /** Water is pointed out from this temperature. */
    const val HOT_C = 25.0

    /** Parking this close, in any direction, while resting. */
    const val PARKING_RADIUS_M = 300.0

    /** Closer than this, a facility ahead is passed before the line is over. */
    const val MIN_AHEAD_M = 50.0

    fun pick(s: RideSituation): AmenityAdvice? {
        for (amenity in Amenity.entries) {
            if (amenity !in s.values.style.amenities || !due(amenity, s)) continue
            val near = s.facilities
                .filter { it.facility.kind in amenity.kinds && it.facility.id !in s.mentionedFacilities }
                .filter {
                    if (amenity == Amenity.BICYCLE_PARKING) it.distanceM <= PARKING_RADIUS_M
                    else it.ahead && it.distanceM in MIN_AHEAD_M..s.values[StyleItem.AHEAD_M].toDouble()
                }
                .minByOrNull { it.distanceM } ?: continue
            return AmenityAdvice(amenity, near.facility, near.distanceM.toInt(), near.side)
        }
        return null
    }

    private fun due(amenity: Amenity, s: RideSituation): Boolean {
        val last = s.amenityMentionedAt[amenity]
        val gapPassed = s.now - (last ?: s.startedAt) >= s.values.ms(StyleItem.AMENITY_GAP_MIN)
        return when (amenity) {
            Amenity.TOILET, Amenity.REST, Amenity.CONVENIENCE_STORE -> gapPassed
            Amenity.WATER -> (s.weather?.temperatureC ?: Double.NEGATIVE_INFINITY) >= HOT_C && (last == null || gapPassed)
            Amenity.BICYCLE_PARKING -> s.state == MotionState.REST && (last == null || gapPassed)
            Amenity.BICYCLE_SHOP, Amenity.PUMP -> last == null
        }
    }
}
