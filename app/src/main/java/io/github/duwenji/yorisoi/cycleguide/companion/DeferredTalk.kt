package io.github.duwenji.yorisoi.cycleguide.companion

import io.github.duwenji.yorisoi.kit.geo.LatLon
import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.kit.geo.RouteGeometry

/**
 * A talk kept for the next stop (requirements F-4): too late to finish before the spot, or too
 * long for riding. It is about a spot ([poi]) or a channel's point cue (F-32: [poi] null, the
 * place by [name], [lat] and [lon]). [text] is the line if it was written already, with its
 * [credit]; null to write it then (spots only).
 */
data class DeferredTalk(
    val poi: Poi?,
    val text: String?,
    val at: Long,
    val credit: String? = null,
    val name: String = poi?.name.orEmpty(),
    val lat: Double = poi?.lat ?: 0.0,
    val lon: Double = poi?.lon ?: 0.0,
) {
    companion object {
        /** Kept talks older than this, or about a spot this far behind, aren't worth it any more. */
        const val MAX_AGE_MS = 10 * 60_000L
        const val MAX_DISTANCE_M = 500.0

        /** [talks] still worth saying at [now], at [here]. */
        fun prune(talks: List<DeferredTalk>, now: Long, here: LatLon?): List<DeferredTalk> = talks.filter {
            now - it.at <= MAX_AGE_MS && (here == null || RouteGeometry.distanceM(here, LatLon(it.lat, it.lon)) <= MAX_DISTANCE_M)
        }
    }
}
