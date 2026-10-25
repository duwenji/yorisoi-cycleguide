package io.github.duwenji.yorisoi.cycleguide.prompt

import io.github.duwenji.yorisoi.kit.channel.Station
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every prompt renders with every built-in channel, and the parts no channel can change are there. */
class PromptsTest {
    private val prompts = Prompts.fromResources()
    private val stations: List<Station> = BuiltInChannels.load()

    private val situation = mapOf(
        "now" to "10月25日（土）14:05", "time_of_day" to "昼", "season" to "秋", "style" to "ポタリング",
        "weather" to "晴れ、気温19℃",
        "ride" to mapOf("minutes" to 30, "km" to "5.2", "spots" to "大巧寺、教恩寺"),
        "recent" to mapOf("lines" to listOf("この先、大巧寺があります。")),
    )

    @Test
    fun `the guide's prompts render for every channel, with the length the ride allows`() {
        for (station in stations) {
            val system = prompts.render(Prompts.Guide.SYSTEM, Prompts.guideSystemVars(station::slot, 90))
            assertTrue(station.id, system.contains("90字以内"))
            assertTrue(station.id, system.contains("交通の決まりに反すること"))
            assertTrue(station.id, system.contains("歩く人向けの言葉は使わない"))
            assertTrue(station.id, system.contains("見たかのように話さない"))
            assertTrue(station.id, system.contains("タグにないことは、有名な場所でも言わない"))
            assertFalse(station.id, system.contains("{{"))
        }
        val riding = prompts.render(
            Prompts.Guide.USER,
            mapOf("name" to "大巧寺", "category" to "寺院", "riding" to true, "distance_m" to 160, "passed" to false, "tags" to listOf("religion=buddhist")),
        )
        assertTrue(riding.contains("約160m"))
        val passed = prompts.render(
            Prompts.Guide.USER,
            mapOf("name" to "教恩寺", "category" to "寺院", "riding" to false, "distance_m" to null, "passed" to true, "tags" to emptyList<String>()),
        )
        assertTrue(passed.contains("さきほど通り過ぎた"))
        assertTrue(passed.contains("謝ったり残念がったりせず"))
        assertTrue(passed.contains("# 書かないこと"))
        assertTrue(passed.contains("- なし"))
    }

    @Test
    fun `the companion's prompts render for every channel and event`() {
        for (station in stations) {
            val system = prompts.render(Prompts.Talk.SYSTEM, Prompts.companionSystemVars(station::slot, 90))
            assertTrue(station.id, system.contains("90字以内"))
            for (event in Prompts.Event.entries) {
                val vars = mapOf("minutes" to 4, "km" to "5.2", "spots" to "大巧寺")
                val user = Prompts.companionUser(prompts, situation, event, vars, station::slot)
                assertTrue("${station.id} ${event.name}", user.contains("# 出来事"))
                assertFalse("${station.id} ${event.name}", user.contains("{{"))
            }
        }
    }
}
