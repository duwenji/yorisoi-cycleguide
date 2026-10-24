package io.github.duwenji.yorisoi.cycleguide.companion

import io.github.duwenji.yorisoi.kit.geo.LatLon
import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.kit.geo.RouteGeometry

/**
 * A spot talk kept for the next stop (requirements F-4): too late to finish before the spot, or
 * too long for riding. [text] is the line if it was written already; null to write it then.
 */
data class DeferredTalk(val poi: Poi, val text: String?, val at: Long) {
    companion object {
        /** Kept talks older than this, or about a spot this far behind, aren't worth it any more. */
        const val MAX_AGE_MS = 10 * 60_000L
        const val MAX_DISTANCE_M = 500.0

        /** [talks] still worth saying at [now], at [here]. */
        fun prune(talks: List<DeferredTalk>, now: Long, here: LatLon?): List<DeferredTalk> = talks.filter {
            now - it.at <= MAX_AGE_MS && (here == null || RouteGeometry.distanceM(here, LatLon(it.poi.lat, it.poi.lon)) <= MAX_DISTANCE_M)
        }
    }
}
