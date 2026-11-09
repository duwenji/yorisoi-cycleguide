package io.github.duwenji.yorisoi.cycleguide.companion

import io.github.duwenji.yorisoi.kit.geo.weather.Weather
import java.util.Calendar

/**
 * When a season's song may come up on a ride (platform REQ-NEARBY-TALK F-23, F-27, R-5), and the
 * words the song catalog uses for now (platform API-SONGS `timesOfDay`, `weather`).
 */
object SongMoment {
    /** Not in the first minutes of a ride: music out of nowhere would startle (R-5). */
    const val NOT_BEFORE_MS = 10 * 60_000L

    /** At most this many songs a ride, this far apart (F-23). */
    const val MAX_A_RIDE = 2
    const val GAP_MS = 20 * 60_000L

    /** Whether the ride lets a song come up now, by its length and the songs played so far. */
    fun allowed(elapsedMs: Long, played: Int, lastAt: Long, now: Long): Boolean =
        elapsedMs >= NOT_BEFORE_MS && played < MAX_A_RIDE && (played == 0 || now - lastAt >= GAP_MS)

    /** The catalog's time of day at [time]. */
    fun timeOfDay(time: Long): String = when (Calendar.getInstance().apply { timeInMillis = time }.get(Calendar.HOUR_OF_DAY)) {
        in 5..9 -> "morning"
        in 10..15 -> "day"
        in 16..18 -> "evening"
        else -> "night"
    }

    /** The catalog's weather for [weather] (WMO codes), or null when not known. */
    fun weather(weather: Weather?): String? = when (val code = weather?.code) {
        null -> null
        0, 1 -> "clear"
        in 71..77, 85, 86 -> "snow"
        in 51..67, in 80..82, in 95..99 -> "rain"
        else -> "cloudy"
    }

    /** The season's word in Japanese, for the fixed line before a song. */
    fun seasonWord(season: String): String = mapOf("spring" to "春", "summer" to "夏", "autumn" to "秋", "winter" to "冬")[season] ?: "季節"
}
