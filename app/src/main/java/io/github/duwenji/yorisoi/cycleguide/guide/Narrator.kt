package io.github.duwenji.yorisoi.cycleguide.guide

import io.github.duwenji.yorisoi.core.prompt.PromptTemplates
import io.github.duwenji.yorisoi.cycleguide.prompt.Prompts
import io.github.duwenji.yorisoi.kit.channel.Station
import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.kit.geo.weather.Weather
import io.github.duwenji.yorisoi.kit.llm.OutputCheck
import io.github.duwenji.yorisoi.kit.llm.Purpose
import java.util.Calendar
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/** A line to say, and whose words it is: the AI's carry a credit (platform ADR-AI-ROLES H-15), the app's fixed ones none. */
data class Line(val text: String, val credit: String? = null)

/**
 * The AI's lines on a ride: a spot ahead or passed, and the companion's remarks, written from the
 * prompt pack with the channel's slots and the length the ride allows now. Null when the AI can't
 * be had (none set up, the day's limit, a failure) or its reply can't be read out (contact details);
 * the ride service then says the fixed line.
 *
 * @param ai the AI gateway's `generate`
 * @param aiName how the AI is named beside its lines
 */
class Narrator(
    private val prompts: PromptTemplates,
    private val ai: suspend (Purpose, String, String) -> String,
    private val aiName: () -> String,
    private val onError: (Exception) -> Unit = {},
) {
    suspend fun spot(poi: Poi, distanceM: Int?, riding: Boolean, station: Station, maxChars: Int): Line? = ask(
        Purpose.GUIDE,
        prompts.render(Prompts.Guide.SYSTEM, Prompts.guideSystemVars(station::slot, maxChars)),
        prompts.render(Prompts.Guide.USER, spotVars(poi, distanceM, riding)),
    )

    suspend fun talk(event: Prompts.Event, eventVars: Map<String, Any?>, situation: Map<String, Any?>, station: Station, maxChars: Int): Line? = ask(
        Purpose.CHAT,
        prompts.render(Prompts.Talk.SYSTEM, Prompts.companionSystemVars(station::slot, maxChars)),
        Prompts.companionUser(prompts, situation, event, eventVars, station::slot),
    )

    private suspend fun ask(purpose: Purpose, system: String, user: String): Line? {
        val text = try {
            ai(purpose, system, user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
            return null
        }
        if (text.isBlank() || OutputCheck.violation(text) != null) return null
        return Line(text, "文: ${aiName()}")
    }

    companion object {
        /** OSM tags worth showing the model; the rest (addresses, operators, ids...) is noise. */
        private val USEFUL_TAGS = setOf(
            "historic", "tourism", "amenity", "religion", "denomination", "leisure", "natural",
            "heritage", "start_date", "architect", "description", "inscription", "wikipedia", "wikidata",
        )

        /** Values for `guide/user`: [distanceM] null for a spot already passed. */
        fun spotVars(poi: Poi, distanceM: Int?, riding: Boolean): Map<String, Any?> = mapOf(
            "name" to poi.name,
            "category" to poi.category,
            "riding" to riding,
            "distance_m" to distanceM,
            "passed" to (distanceM == null),
            "tags" to poi.tags.filterKeys { it in USEFUL_TAGS || it.startsWith("name:") }.map { (k, v) -> "$k=$v" },
        )

        /** Values for `companion/situation`. [rideMinutes] null at the start (no ride to speak of yet). */
        fun situationVars(
            now: Long,
            style: String,
            weather: Weather?,
            rideMinutes: Int?,
            distanceM: Double,
            spots: List<String>,
            recent: List<String>,
        ): Map<String, Any?> {
            val c = Calendar.getInstance().apply { timeInMillis = now }
            val hour = c.get(Calendar.HOUR_OF_DAY)
            val month = c.get(Calendar.MONTH) + 1
            val week = "日月火水木金土"[c.get(Calendar.DAY_OF_WEEK) - 1]
            return mapOf(
                "now" to String.format(Locale.ROOT, "%d月%d日（%s）%d:%02d", month, c.get(Calendar.DAY_OF_MONTH), week, hour, c.get(Calendar.MINUTE)),
                "time_of_day" to when (hour) {
                    in 5..9 -> "朝"
                    in 10..15 -> "昼"
                    in 16..18 -> "夕方"
                    in 19..22 -> "夜"
                    else -> "深夜"
                },
                "season" to when (month) {
                    in 3..5 -> "春"
                    in 6..8 -> "夏"
                    in 9..11 -> "秋"
                    else -> "冬"
                },
                "style" to style,
                "weather" to weather?.let { "${it.description}、気温${it.temperatureC.toInt()}℃" },
                "ride" to rideMinutes?.let {
                    mapOf(
                        "minutes" to it,
                        "km" to String.format(Locale.ROOT, "%.1f", distanceM / 1000),
                        "spots" to spots.joinToString("、").ifEmpty { null },
                    )
                },
                "recent" to recent.takeLast(4).ifEmpty { null }?.let { mapOf("lines" to it) },
            )
        }
    }
}
