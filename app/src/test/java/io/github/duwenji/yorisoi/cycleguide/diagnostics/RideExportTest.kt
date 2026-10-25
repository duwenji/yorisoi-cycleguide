package io.github.duwenji.yorisoi.cycleguide.diagnostics

import io.github.duwenji.yorisoi.kit.companion.SessionRecorder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The developer's export of the rides recorded (requirements F-29, AC-22). */
class RideExportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `every ride recorded is exported with its events, newest first`() {
        val recorder = SessionRecorder(File(tmp.root, "diagnostics"), startType = "ride_start", endType = "ride_end")
        recorder.start(1L, listOf("style" to "pottering"))
        recorder.event("said", "text" to "この先、大巧寺があります。")
        recorder.finish(1L)
        recorder.start(2L, emptyList())
        recorder.finish(2L)

        val json = JSONObject(RideExport.toJson(recorder.ids(), recorder::read, "0.1.0", 100L).toString())

        assertEquals(RideExport.FORMAT, json.getString("format"))
        assertEquals(RideExport.VERSION, json.getInt("formatVersion"))
        val rides = json.getJSONArray("rides")
        assertEquals(listOf(2L, 1L), (0 until rides.length()).map { rides.getJSONObject(it).getLong("id") })
        val events = rides.getJSONObject(1).getJSONArray("events")
        assertEquals(listOf("ride_start", "said", "ride_end"), (0 until events.length()).map { events.getJSONObject(it).getString("type") })
        assertEquals("pottering", events.getJSONObject(0).getString("style"))
        assertEquals("cycleguide-rides-20261025-1530.json", RideExport.fileName("20261025-1530"))
    }
}
