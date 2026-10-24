package io.github.duwenji.yorisoi.cycleguide.style

import io.github.duwenji.yorisoi.kit.geo.Poi

/**
 * One value a ride style sets, shown in the settings grouped by [group]. Values are clamped to
 * [min]..[max] when read (requirements F-11).
 */
enum class StyleItem(val group: String, val label: String, val unit: String, val min: Int, val max: Int) {
    AHEAD_M("見どころ", "前方を探す距離", "m", 100, 1500),
    SPOT_GAP_MIN("見どころ", "見どころの話の間隔", "分", 1, 30),
    CHAT_GAP_MIN("話の間隔", "ひと言の間隔", "分", 1, 60),
    MILESTONE_KM("話の間隔", "距離の区切り", "km", 1, 50),
    SPEECH_MOVING_SEC("話の長さ", "走っているとき", "秒", 5, 30),
    SPEECH_SIGNAL_SEC("話の長さ", "信号待ちのとき", "秒", 10, 60),
    SPEECH_REST_SEC("話の長さ", "休憩のとき", "秒", 20, 120),
    SIGNAL_STOP_SEC("止まったとき", "信号待ちとみなす長さ", "秒", 5, 60),
    REST_MIN("止まったとき", "休憩とみなす長さ", "分", 1, 15),
    QUIET_SPEED_KMH("話さない場面", "これより速いときは話さない", "km/h", 10, 60),
    WEATHER_LOOKAHEAD_MIN("天気の急変", "何分先までの予報で知らせるか", "分", 15, 180),
    WEATHER_REPEAT_MIN("天気の急変", "同じ種類の予告の間隔", "分", 10, 240),
    HEAVY_RAIN_MM_PER_H("天気の急変", "「強い雨」とする雨量", "mm/h", 1, 50),
    SUNSET_NOTICE_MIN("日の入り", "何分前から知らせるか", "分", 5, 120),
    SUNSET_LATEST_MIN("日の入り", "何分前を過ぎたら知らせないか", "分", 0, 60),
}

/** Which spots a style talks about. */
enum class SpotScale {
    /** Small ones too: a shrine on a back street. */
    SMALL,

    /** Only well-known ones (with a Wikipedia article, or a museum, an attraction or a viewpoint). */
    MAJOR;

    fun accepts(poi: Poi): Boolean = when (this) {
        SMALL -> true
        MAJOR -> "wikipedia" in poi.tags || "wikidata" in poi.tags || poi.tags["tourism"] in MAJOR_TOURISM
    }

    private companion object {
        val MAJOR_TOURISM = setOf("attraction", "museum", "viewpoint")
    }
}

/** The amenities a style points out (requirements F-7, app stage 2). */
enum class Amenity { TOILET, WATER, BICYCLE_PARKING, REST, CONVENIENCE_STORE, BICYCLE_SHOP, PUMP }

/**
 * A way of riding: the values the companion decides with (requirements D-5). The screens and the
 * decision read [RideStyles.ALL] only, so a style is added there and nowhere else (F-12).
 *
 * @param defaults a value for every [StyleItem]
 */
data class RideStyle(
    val id: String,
    val label: String,
    val summary: String,
    val spotScale: SpotScale,
    val amenities: Set<Amenity>,
    val defaults: Map<StyleItem, Int>,
)

object RideStyles {
    val POTTERING = RideStyle(
        id = "pottering",
        label = "ポタリング",
        summary = "街中をゆっくり走り、寄り道を楽しむ",
        spotScale = SpotScale.SMALL,
        amenities = setOf(Amenity.TOILET, Amenity.WATER, Amenity.BICYCLE_PARKING, Amenity.REST),
        defaults = mapOf(
            StyleItem.AHEAD_M to 300,
            StyleItem.SPOT_GAP_MIN to 3,
            StyleItem.CHAT_GAP_MIN to 5,
            StyleItem.MILESTONE_KM to 3,
            StyleItem.SPEECH_MOVING_SEC to 15,
            StyleItem.SPEECH_SIGNAL_SEC to 30,
            StyleItem.SPEECH_REST_SEC to 60,
            StyleItem.SIGNAL_STOP_SEC to 10,
            StyleItem.REST_MIN to 3,
            StyleItem.QUIET_SPEED_KMH to 25,
            StyleItem.WEATHER_LOOKAHEAD_MIN to 60,
            StyleItem.WEATHER_REPEAT_MIN to 60,
            StyleItem.HEAVY_RAIN_MM_PER_H to 8,
            StyleItem.SUNSET_NOTICE_MIN to 30,
            StyleItem.SUNSET_LATEST_MIN to 5,
        ),
    )

    val LONG_RIDE = RideStyle(
        id = "long_ride",
        label = "ロングライド",
        summary = "長い距離を一定の速さで走る",
        spotScale = SpotScale.MAJOR,
        amenities = setOf(Amenity.TOILET, Amenity.CONVENIENCE_STORE, Amenity.BICYCLE_SHOP, Amenity.PUMP),
        defaults = mapOf(
            StyleItem.AHEAD_M to 800,
            StyleItem.SPOT_GAP_MIN to 10,
            StyleItem.CHAT_GAP_MIN to 10,
            StyleItem.MILESTONE_KM to 10,
            StyleItem.SPEECH_MOVING_SEC to 10,
            StyleItem.SPEECH_SIGNAL_SEC to 20,
            StyleItem.SPEECH_REST_SEC to 60,
            StyleItem.SIGNAL_STOP_SEC to 10,
            StyleItem.REST_MIN to 5,
            StyleItem.QUIET_SPEED_KMH to 40,
            StyleItem.WEATHER_LOOKAHEAD_MIN to 120,
            StyleItem.WEATHER_REPEAT_MIN to 60,
            StyleItem.HEAVY_RAIN_MM_PER_H to 8,
            StyleItem.SUNSET_NOTICE_MIN to 60,
            StyleItem.SUNSET_LATEST_MIN to 5,
        ),
    )

    /** In the order the settings list them; the first is the default. */
    val ALL: List<RideStyle> = listOf(POTTERING, LONG_RIDE)

    val DEFAULT: RideStyle get() = ALL.first()

    /** The style with [id], or the default one if there is none (removed in an update). */
    fun byId(id: String?): RideStyle = ALL.firstOrNull { it.id == id } ?: DEFAULT
}

/** A style with the user's changes; anything not changed is the style's own value. */
data class StyleValues(val style: RideStyle, val overrides: Map<StyleItem, Int> = emptyMap()) {
    operator fun get(item: StyleItem): Int = (overrides[item] ?: style.defaults.getValue(item)).coerceIn(item.min, item.max)

    /** In milliseconds, for values measured in minutes or seconds. */
    fun ms(item: StyleItem): Long = when (item.unit) {
        "分" -> get(item) * 60_000L
        "秒" -> get(item) * 1_000L
        else -> error("${item.name} is not a time")
    }

    fun with(item: StyleItem, value: Int) = copy(overrides = overrides + (item to value))

    /** Combinations under which a remark can never be made, as messages for the settings screen. */
    fun conflicts(): List<String> = buildList {
        if (get(StyleItem.SUNSET_LATEST_MIN) > get(StyleItem.SUNSET_NOTICE_MIN)) {
            add("日の入り: 「何分前を過ぎたら知らせないか」を「何分前から知らせるか」以下にしてください（今のままでは知らせません）")
        }
        if (ms(StyleItem.REST_MIN) <= ms(StyleItem.SIGNAL_STOP_SEC)) {
            add("止まったとき: 「休憩とみなす長さ」を「信号待ちとみなす長さ」より長くしてください（今のままでは信号待ちになりません）")
        }
    }
}
