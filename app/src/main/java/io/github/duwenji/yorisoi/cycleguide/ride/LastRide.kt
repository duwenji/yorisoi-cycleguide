package io.github.duwenji.yorisoi.cycleguide.ride

import io.github.duwenji.yorisoi.cycleguide.companion.RideSession
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

/**
 * The ride last finished, shown on the start screen until the next one (requirements F-31): the
 * way ridden and the spots talked about. Nothing new about where the rider is.
 */
data class LastRide(
    val startedAt: Long,
    val endedAt: Long,
    val distanceM: Double,
    val route: List<LatLon>,
    val spots: List<Poi>,
) {
    /** When it began, how far and how long, as the start screen says it. */
    fun summary(zone: TimeZone = TimeZone.getDefault()): String {
        val began = SimpleDateFormat("M月d日 H:mm", Locale.JAPAN).apply { timeZone = zone }.format(startedAt)
        val minutes = ((endedAt - startedAt) / 60_000).toInt()
        val took = if (minutes >= 60) "${minutes / 60}時間${minutes % 60}分" else "${minutes}分"
        return "前回の走行: $began から・${"%.1f".format(Locale.JAPAN, distanceM / 1000)} km・$took"
    }

    companion object {
        /** Enough for the map of a long ride; the file stays small. */
        const val MAX_POINTS = 1000

        /** The ride [session] as it ended at [endedAt]; null when the rider never moved. */
        fun of(session: RideSession, endedAt: Long): LastRide? {
            if (session.route.size < 2) return null
            return LastRide(
                session.startedAt, endedAt, session.distanceM,
                RouteGeometry.thin(session.route, MAX_POINTS), session.spots.distinctBy { it.id },
            )
        }
    }
}

/** Keeps the [LastRide] in [file], on this device only; each ride replaces the one before. */
class LastRideStore(private val file: File, private val onError: (Exception) -> Unit = {}) {
    private val _ride = MutableStateFlow(read())

    /** The ride last finished; null when there is none, or it was cleared. */
    val ride: StateFlow<LastRide?> = _ride.asStateFlow()

    fun save(ride: LastRide) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(toJson(ride).toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw IOException("Could not replace $file")
            }
        } catch (e: IOException) {
            onError(e)
        }
        _ride.value = ride
    }

    fun clear() {
        file.delete()
        _ride.value = null
    }

    private fun read(): LastRide? {
        if (!file.exists()) return null
        return try {
            fromJson(JSONObject(file.readText()))
        } catch (e: IOException) {
            onError(e)
            null
        } catch (e: JSONException) {
            onError(e)
            null
        }
    }

    companion object {
        fun toJson(ride: LastRide): JSONObject = JSONObject()
            .put("startedAt", ride.startedAt)
            .put("endedAt", ride.endedAt)
            .put("distanceM", ride.distanceM)
            .put("route", JSONArray().apply { ride.route.forEach { put(JSONArray().put(it.lat).put(it.lon)) } })
            // Only what the map draws a spot with.
            .put("spots", JSONArray().apply {
                ride.spots.forEach {
                    put(JSONObject().put("id", it.id).put("name", it.name).put("lat", it.lat).put("lon", it.lon).put("category", it.category))
                }
            })

        fun fromJson(json: JSONObject): LastRide {
            val route = json.getJSONArray("route")
            val spots = json.getJSONArray("spots")
            return LastRide(
                startedAt = json.getLong("startedAt"),
                endedAt = json.getLong("endedAt"),
                distanceM = json.getDouble("distanceM"),
                route = List(route.length()) { i -> route.getJSONArray(i).let { LatLon(it.getDouble(0), it.getDouble(1)) } },
                spots = List(spots.length()) { i ->
                    spots.getJSONObject(i).let {
                        Poi(it.getString("id"), it.getString("name"), it.getDouble("lat"), it.getDouble("lon"), it.getString("category"), emptyMap())
                    }
                },
            )
        }
    }
}
