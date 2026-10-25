package io.github.duwenji.yorisoi.cycleguide.diagnostics

import org.json.JSONArray
import org.json.JSONObject

/**
 * The file the developer exports from the settings: every ride recorded in developer mode, with its
 * events (platform kit-companion's SessionRecorder). The field names are what analysis scripts read;
 * [VERSION] goes up when one changes meaning.
 */
object RideExport {
    const val FORMAT = "cycleguide-ride-export"
    const val VERSION = 1

    fun toJson(ids: List<Long>, events: (Long) -> JSONArray, appVersion: String, exportedAt: Long): JSONObject =
        JSONObject()
            .put("format", FORMAT)
            .put("formatVersion", VERSION)
            .put("app", appVersion)
            .put("exportedAt", exportedAt)
            .put("rides", JSONArray().apply { ids.forEach { id -> put(JSONObject().put("id", id).put("events", events(id))) } })

    /** A file name that sorts by date, e.g. `cycleguide-rides-20261025-1530.json`. */
    fun fileName(stamp: String) = "cycleguide-rides-$stamp.json"
}
