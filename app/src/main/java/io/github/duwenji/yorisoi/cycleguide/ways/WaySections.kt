package io.github.duwenji.yorisoi.cycleguide.ways

import io.github.duwenji.yorisoi.cycleguide.history.RideRecord
import io.github.duwenji.yorisoi.kit.geo.FrequentWay
import io.github.duwenji.yorisoi.kit.geo.LatLon
import io.github.duwenji.yorisoi.kit.geo.RouteGeometry
import io.github.duwenji.yorisoi.kit.geo.map.WayTalk

/** What was said along a frequent way (REQ-FREQUENT-WAYS F-13). */
object WaySections {
    /** A spot this near a way was talked about on it. */
    private const val SPOT_NEAR_M = 100.0

    /** The last thing said about each spot along [way], newest first, from [rides]. */
    fun talksOn(way: FrequentWay, rides: List<RideRecord>): List<WayTalk> = rides
        .flatMap { ride ->
            val at = ride.spots.associate { it.id to LatLon(it.lat, it.lon) }
            ride.talks.filter { t -> at[t.poiId]?.let { RouteGeometry.distanceToLineM(it, way.points) <= SPOT_NEAR_M } == true }
        }
        .sortedByDescending { it.at }
        .distinctBy { it.name }
        .map { WayTalk(it.name, it.at, it.text) }
}
