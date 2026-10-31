package io.github.duwenji.yorisoi.cycleguide.guide

import io.github.duwenji.yorisoi.core.prompt.PromptTemplates
import io.github.duwenji.yorisoi.cycleguide.prompt.Prompts
import io.github.duwenji.yorisoi.kit.channel.Station
import io.github.duwenji.yorisoi.kit.channel.material.Ground
import io.github.duwenji.yorisoi.kit.channel.material.Grounding
import io.github.duwenji.yorisoi.kit.channel.material.Subject
import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.kit.geo.weather.Weather
import io.github.duwenji.yorisoi.kit.llm.OutputCheck
import io.github.duwenji.yorisoi.kit.llm.Purpose
import io.github.duwenji.yorisoi.kit.studio.Reading
import java.util.Calendar
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * A line to say, and whose words it is: the AI's carry a credit (platform ADR-AI-ROLES H-15), the
 * app's fixed ones none, and the studio's are a note or placement ([studio], platform kit-studio).
 */
data class Line(val text: String, val credit: String? = null, val studio: Reading? = null)

/** A spot as kit-channel matches a collection's texts to it (API-004 `about`). */
data class PoiSpot(val poi: Poi, override val distanceM: Int?) : Subject.Spot {
    override val id: String get() = poi.id
    override val name: String get() = poi.name
    override val lat: Double get() = poi.lat
    override val lon: Double get() = poi.lon
    override val category: String get() = poi.category
}

/**
 * The AI's lines on a ride: a spot ahead or passed, and the companion's remarks, written from the
 * prompt pack with the channel's slots and the length the ride allows now. A spot's request carries
 * the texts about it that the channel's collections let the AI take as fact (API-004「AI の根拠」),
 * and the line then names whose they were. Null when the AI can't
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
    /**
     * [grounds]: the texts to take as fact, when a cue's request carries them; otherwise gathered here.
     * [before]: what was said about the spot on an earlier ride, not to be said again (REQ-FREQUENT-WAYS F-11).
     */
    suspend fun spot(
        poi: Poi,
        distanceM: Int?,
        riding: Boolean,
        station: Station,
        maxChars: Int,
        grounds: List<Ground>? = null,
        before: String? = null,
    ): Line? {
        val facts = grounds ?: Grounding.collect(PoiSpot(poi, distanceM), station)
        return ask(
            Purpose.GUIDE,
            prompts.render(Prompts.Guide.SYSTEM, Prompts.guideSystemVars(station::slot, maxChars)),
            prompts.render(Prompts.Guide.USER, spotVars(poi, distanceM, riding, facts, before)),
            basedOn = facts.map { it.producerName }.distinct(),
        )
    }

    suspend fun talk(event: Prompts.Event, eventVars: Map<String, Any?>, situation: Map<String, Any?>, station: Station, maxChars: Int): Line? = ask(
        Purpose.CHAT,
        prompts.render(Prompts.Talk.SYSTEM, Prompts.companionSystemVars(station::slot, maxChars)),
        Prompts.companionUser(prompts, situation, event, eventVars, station::slot),
    )

    private suspend fun ask(purpose: Purpose, system: String, user: String, basedOn: List<String> = emptyList()): Line? {
        val text = try {
            ai(purpose, system, user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
            return null
        }
        if (text.isBlank() || OutputCheck.violation(text) != null) return null
        return Line(text, credit(aiName(), basedOn))
    }

    companion object {
        /** OSM tags worth showing the model; the rest (addresses, operators, ids...) is noise. */
        private val USEFUL_TAGS = setOf(
            "historic", "tourism", "amenity", "religion", "denomination", "leisure", "natural",
            "heritage", "start_date", "architect", "description", "inscription", "wikipedia", "wikidata",
        )

        /** Whose words a line is: the AI's, and the producers whose texts it was written from (API-004 `basedOn`). */
        fun credit(aiName: String, basedOn: List<String> = emptyList()): String =
            listOfNotNull("文: $aiName", basedOn.takeIf { it.isNotEmpty() }?.let { "${it.joinToString("・")}の資料をもとに作成" }).joinToString(" / ")

        /**
         * Values for `guide/user`: [distanceM] null for a spot already passed; [grounds] the texts the AI
         * may take as fact; [before] what was said about it on an earlier ride.
         */
        fun spotVars(poi: Poi, distanceM: Int?, riding: Boolean, grounds: List<Ground> = emptyList(), before: String? = null): Map<String, Any?> = mapOf(
            "name" to poi.name,
            "category" to poi.category,
            "riding" to riding,
            "distance_m" to distanceM,
            "passed" to (distanceM == null),
            "tags" to poi.tags.filterKeys { it in USEFUL_TAGS || it.startsWith("name:") }.map { (k, v) -> "$k=$v" },
            "grounded" to grounds.isNotEmpty(),
            // A ground can't close its own tag and pass for the prompt (API-004 I-15).
            "grounds" to grounds.map { mapOf("text" to fence(it.text), "source" to fence(it.producerName)) }.ifEmpty { null },
            "before" to before?.let(::fence),
        )

        private fun fence(text: String) = text.replace('<', '＜').replace('>', '＞')

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
