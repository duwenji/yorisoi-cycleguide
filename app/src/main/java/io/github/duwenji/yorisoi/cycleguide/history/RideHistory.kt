package io.github.duwenji.yorisoi.cycleguide.history

import io.github.duwenji.yorisoi.cycleguide.companion.RideSession
import io.github.duwenji.yorisoi.cycleguide.guide.SpotFacts
import io.github.duwenji.yorisoi.kit.geo.LatLon
import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.kit.geo.RouteGeometry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** A frequent way gone along on a ride, and how many times that made (REQ-FREQUENT-WAYS F-12); [new] when the ride made it one. */
data class RideWay(val id: String, val name: String, val passes: Int, val new: Boolean)

/** What the companion said about a spot, and when (requirements F-28). */
data class SpotTalk(val poiId: String, val name: String, val at: Long, val text: String)

/**
 * A ride once it's over (requirements F-28): the way ridden, the spots talked about and what was
 * said there, and the look back at the end. [startedAt] is its id.
 */
data class RideRecord(
    val startedAt: Long,
    val endedAt: Long,
    val distanceM: Double,
    val route: List<LatLon>,
    val spots: List<Poi>,
    val talks: List<SpotTalk> = emptyList(),
    /** The channels used, in order (`Station.key`). */
    val channels: List<String> = emptyList(),
    /** The look back said at the end; null when there was none. */
    val recap: String? = null,
    /** The frequent ways gone along (REQ-FREQUENT-WAYS F-12). */
    val ways: List<RideWay> = emptyList(),
) {
    val summary: RideSummary get() = RideSummary(startedAt, endedAt, distanceM, spots.size, recap)

    companion object {
        /** Enough for the map of a long ride; the file stays small. */
        const val MAX_POINTS = 1000

        /** The ride [session] as it ended at [endedAt]; null when the rider never moved. */
        fun of(session: RideSession, endedAt: Long, recap: String? = null): RideRecord? {
            if (session.route.size < 2) return null
            return RideRecord(
                session.startedAt, endedAt, session.distanceM,
                RouteGeometry.thin(session.route, MAX_POINTS), session.spots.distinctBy { it.id },
                session.talks, session.channels, recap,
            )
        }
    }
}

/** A ride as the list of rides shows it. */
data class RideSummary(val startedAt: Long, val endedAt: Long, val distanceM: Double, val spotCount: Int, val recap: String?) {
    /** When it began, how far and how long: "10月25日 9:00 から・12.3 km・1時間5分". */
    fun text(zone: TimeZone = TimeZone.getDefault()): String {
        val began = SimpleDateFormat("M月d日 H:mm", Locale.JAPAN).apply { timeZone = zone }.format(startedAt)
        val minutes = ((endedAt - startedAt) / 60_000).toInt()
        val took = if (minutes >= 60) "${minutes / 60}時間${minutes % 60}分" else "${minutes}分"
        return "$began から・${"%.1f".format(Locale.JAPAN, distanceM / 1000)} km・$took"
    }
}

/**
 * The rides finished, newest first, kept in [dir] on this device only (requirements F-28, P-1):
 * a list of the rides (`index.json`) and one file each for its way and talks, so the list stays
 * quick to read. The newest ride is shown on the start screen (F-31).
 *
 * @param legacy where the single last ride was kept before there was a history; taken in once
 */
class RideHistory(private val dir: File, legacy: File? = null, private val onError: (Exception) -> Unit = {}) {
    private val index = File(dir, "index.json")

    private val _rides = MutableStateFlow(readIndex())

    /** Every ride kept, newest first. */
    val rides: StateFlow<List<RideSummary>> = _rides.asStateFlow()

    private val _latest = MutableStateFlow(_rides.value.firstOrNull()?.let { ride(it.startedAt) })

    /** The ride last finished; null when there is none, or the history was cleared. */
    val latest: StateFlow<RideRecord?> = _latest.asStateFlow()

    init {
        if (legacy != null && legacy.exists()) {
            if (_rides.value.isEmpty()) (readJson(legacy) as? JSONObject)?.let { json ->
                try {
                    add(fromJson(json))
                } catch (e: JSONException) {
                    onError(e)
                }
            }
            legacy.delete()
        }
    }

    fun add(ride: RideRecord) {
        val older = _rides.value.filter { it.startedAt != ride.startedAt }
        val rides = (listOf(ride.summary) + older).take(MAX_RIDES)
        try {
            write(File(dir, "${ride.startedAt}.json"), toJson(ride))
            write(index, JSONArray().apply { rides.forEach { put(toJson(it)) } })
            older.drop(MAX_RIDES - 1).forEach { File(dir, "${it.startedAt}.json").delete() }
        } catch (e: IOException) {
            onError(e)
        }
        _rides.value = rides
        _latest.value = ride
    }

    /** The ride that began at [startedAt], read from its file; null when it can't be read. */
    fun ride(startedAt: Long): RideRecord? = (readJson(File(dir, "$startedAt.json")) as? JSONObject)?.let { json ->
        try {
            fromJson(json)
        } catch (e: JSONException) {
            onError(e)
            null
        }
    }

    /** The rides ended at [from] or later, read from their files, newest first. */
    fun since(from: Long): List<RideRecord> = _rides.value.filter { it.endedAt >= from }.mapNotNull { ride(it.startedAt) }

    fun clear() {
        dir.deleteRecursively()
        _rides.value = emptyList()
        _latest.value = null
    }

    private fun readIndex(): List<RideSummary> {
        val rides = readJson(index) as? JSONArray ?: return emptyList()
        return try {
            List(rides.length()) { i -> summaryFromJson(rides.getJSONObject(i)) }
        } catch (e: JSONException) {
            onError(e)
            emptyList()
        }
    }

    private fun readJson(file: File): Any? {
        if (!file.exists()) return null
        return try {
            val text = file.readText()
            if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
        } catch (e: IOException) {
            onError(e)
            null
        } catch (e: JSONException) {
            onError(e)
            null
        }
    }

    /** Through a temporary file, so a ride is never half written. */
    private fun write(file: File, json: Any) {
        dir.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Could not replace $file")
        }
    }

    companion object {
        /** As many as SanpoGuide keeps walks. */
        const val MAX_RIDES = 500

        fun toJson(ride: RideRecord): JSONObject = JSONObject()
            .put("startedAt", ride.startedAt)
            .put("endedAt", ride.endedAt)
            .put("distanceM", ride.distanceM)
            .put("route", JSONArray().apply { ride.route.forEach { put(JSONArray().put(it.lat).put(it.lon)) } })
            // Only what the map draws a spot with, and the tags its card reads (SpotFacts).
            .put("spots", JSONArray().apply {
                ride.spots.forEach {
                    val tags = it.tags.filterKeys { k -> k in SpotFacts.KEYS }
                    put(
                        JSONObject().put("id", it.id).put("name", it.name).put("lat", it.lat).put("lon", it.lon).put("category", it.category)
                            .apply { if (tags.isNotEmpty()) put("tags", JSONObject(tags)) },
                    )
                }
            })
            .put("talks", JSONArray().apply {
                ride.talks.forEach { put(JSONObject().put("poiId", it.poiId).put("name", it.name).put("at", it.at).put("text", it.text)) }
            })
            .put("channels", JSONArray(ride.channels))
            .put("recap", ride.recap)
            .put("ways", JSONArray().apply {
                ride.ways.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("passes", it.passes).put("new", it.new)) }
            })

        fun fromJson(json: JSONObject): RideRecord {
            val route = json.getJSONArray("route")
            val spots = json.getJSONArray("spots")
            val talks = json.optJSONArray("talks") ?: JSONArray()
            val channels = json.optJSONArray("channels") ?: JSONArray()
            val ways = json.optJSONArray("ways") ?: JSONArray()
            return RideRecord(
                startedAt = json.getLong("startedAt"),
                endedAt = json.getLong("endedAt"),
                distanceM = json.getDouble("distanceM"),
                route = List(route.length()) { i -> route.getJSONArray(i).let { LatLon(it.getDouble(0), it.getDouble(1)) } },
                spots = List(spots.length()) { i ->
                    spots.getJSONObject(i).let {
                        val tags = it.optJSONObject("tags")?.let { t -> t.keys().asSequence().associateWith(t::getString) }.orEmpty()
                        Poi(it.getString("id"), it.getString("name"), it.getDouble("lat"), it.getDouble("lon"), it.getString("category"), tags)
                    }
                },
                talks = List(talks.length()) { i ->
                    talks.getJSONObject(i).let { SpotTalk(it.getString("poiId"), it.getString("name"), it.getLong("at"), it.getString("text")) }
                },
                channels = List(channels.length()) { channels.getString(it) },
                recap = json.optString("recap").ifEmpty { null },
                ways = List(ways.length()) { i ->
                    ways.getJSONObject(i).let { RideWay(it.getString("id"), it.getString("name"), it.getInt("passes"), it.getBoolean("new")) }
                },
            )
        }

        private fun toJson(s: RideSummary): JSONObject = JSONObject()
            .put("startedAt", s.startedAt).put("endedAt", s.endedAt).put("distanceM", s.distanceM)
            .put("spotCount", s.spotCount).put("recap", s.recap)

        private fun summaryFromJson(json: JSONObject) = RideSummary(
            json.getLong("startedAt"), json.getLong("endedAt"), json.getDouble("distanceM"),
            json.getInt("spotCount"), json.optString("recap").ifEmpty { null },
        )
    }
}
