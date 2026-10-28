package io.github.duwenji.yorisoi.cycleguide.ways

import io.github.duwenji.yorisoi.cycleguide.history.RideRecord
import io.github.duwenji.yorisoi.cycleguide.history.SpotTalk
import io.github.duwenji.yorisoi.kit.geo.FrequentWay
import io.github.duwenji.yorisoi.kit.geo.LatLon
import io.github.duwenji.yorisoi.kit.geo.RouteGeometry

/** Parts of a way ridden, and what was said along a frequent way (REQ-FREQUENT-WAYS F-13, F-16). */
object WaySections {
    /** Left out at both ends of a ride made into a way, as when ways are found (D-5). */
    const val END_M = 200.0

    /** A spot this near a way was talked about on it. */
    private const val SPOT_NEAR_M = 100.0

    /** How far along [points] each point is. */
    fun distances(points: List<LatLon>): List<Double> =
        points.runningFold(0.0 to points.firstOrNull()) { (d, prev), p -> (d + (prev?.let { RouteGeometry.distanceM(it, p) } ?: 0.0)) to p }
            .drop(1).map { it.first }

    /** The part of [points] from [fromM] to [toM] along it. */
    fun section(points: List<LatLon>, fromM: Double, toM: Double): List<LatLon> {
        val along = distances(points)
        return points.filterIndexed { i, _ -> along[i] in fromM..toM }
    }

    /** The last thing said about each spot along [way], newest first, from [rides]. */
    fun talksOn(way: FrequentWay, rides: List<RideRecord>): List<SpotTalk> = rides
        .flatMap { ride ->
            val at = ride.spots.associate { it.id to LatLon(it.lat, it.lon) }
            ride.talks.filter { t -> at[t.poiId]?.let { RouteGeometry.distanceToLineM(it, way.points) <= SPOT_NEAR_M } == true }
        }
        .sortedByDescending { it.at }
        .distinctBy { it.name }
}
