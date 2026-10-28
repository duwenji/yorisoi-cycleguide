package io.github.duwenji.yorisoi.cycleguide.history

import io.github.duwenji.yorisoi.kit.geo.LatLon
import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.kit.geo.RouteGeometry

/** What was said about a spot on an earlier ride, and where the spot was. */
data class PastTalk(val poiId: String, val name: String, val at: LatLon?, val saidAt: Long, val text: String)

/**
 * What was said about the spots on earlier rides (REQ-FREQUENT-WAYS F-9〜F-11, D-11): a spot talked
 * about less than [RECENT_MS] ago isn't talked about again; one talked about before that is, in
 * other words than last time.
 */
class PastTalks(talks: List<PastTalk>) {
    private val byId = talks.groupBy { it.poiId }.mapValues { (_, t) -> t.maxBy { it.saidAt } }
    private val byName = talks.groupBy { it.name }

    /**
     * The last time [poi] was talked about. OpenStreetMap often maps a place twice (a point and an
     * area), and which one a search returns depends on where it ran from: the same name close by
     * counts as the same place, as SanpoGuide's revisits do.
     */
    fun last(poi: Poi): PastTalk? {
        val near = byName[poi.name].orEmpty().filter { t ->
            t.at != null && RouteGeometry.distanceM(t.at, LatLon(poi.lat, poi.lon)) <= SAME_PLACE_M
        }
        return (listOfNotNull(byId[poi.id]) + near).maxByOrNull { it.saidAt }
    }

    /** Talked about less than [RECENT_MS] before [now]: not again (F-10). */
    fun recent(poi: Poi, now: Long): Boolean = last(poi)?.let { now - it.saidAt < RECENT_MS } == true

    /** What was said last time, when it was [RECENT_MS] or more before [now] (F-11). */
    fun before(poi: Poi, now: Long): String? = last(poi)?.takeIf { now - it.saidAt >= RECENT_MS }?.text

    companion object {
        const val RECENT_MS = 24L * 60 * 60 * 1000
        private const val SAME_PLACE_M = 200.0

        /** The rides read for the talks: as long as a found way is kept (REQ-FREQUENT-WAYS F-3). */
        const val WINDOW_MS = 60L * 24 * 60 * 60 * 1000

        val NONE = PastTalks(emptyList())

        /** The talks of [rides], placed where each ride kept the spot. */
        fun of(rides: List<RideRecord>): PastTalks = PastTalks(
            rides.flatMap { ride ->
                val at = ride.spots.associate { it.id to LatLon(it.lat, it.lon) }
                ride.talks.map { PastTalk(it.poiId, it.name, at[it.poiId], it.at, it.text) }
            },
        )
    }
}
