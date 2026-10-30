package io.github.duwenji.yorisoi.cycleguide.guide

import java.net.URI
import java.net.URLEncoder

/** One line of what OpenStreetMap says about a spot; [url] when the line opens a page. */
data class SpotFact(val label: String, val value: String, val url: String? = null)

/**
 * What a spot's OpenStreetMap tags say, for its card on the map: there before anything is said
 * about it, and without the AI. Only tags a rider can read as they are; the rest (ids, operators,
 * addresses) is left out.
 */
object SpotFacts {
    fun of(tags: Map<String, String>): List<SpotFact> = buildList {
        reading(tags)?.let { add(SpotFact("よみ", it)) }
        tags["name:en"]?.let { add(SpotFact("英語名", it)) }
        tags["description"]?.let { add(SpotFact("説明", it)) }
        religion(tags)?.let { add(SpotFact("宗教", it)) }
        tags["start_date"]?.let { add(SpotFact("創建・開設", it)) }
        tags["architect"]?.let { add(SpotFact("設計", it)) }
        if ("heritage" in tags) add(SpotFact("文化財", "指定あり"))
        tags["inscription"]?.let { add(SpotFact("碑文", it)) }
        tags["opening_hours"]?.let { add(SpotFact("営業時間", it)) }
        wikipedia(tags["wikipedia"])?.let(::add)
        website(tags["website"] ?: tags["contact:website"])?.let(::add)
    }

    /** The tags [of] reads; a ride's history keeps only these of each spot. */
    val KEYS = setOf(
        "name:ja-Hira", "name:ja_kana", "name:ja-Kana", "name:en", "description", "religion", "denomination",
        "start_date", "architect", "heritage", "inscription", "opening_hours", "wikipedia", "website", "contact:website",
    )

    private fun reading(tags: Map<String, String>): String? =
        tags["name:ja-Hira"] ?: tags["name:ja_kana"] ?: tags["name:ja-Kana"]

    private fun religion(tags: Map<String, String>): String? {
        val religion = RELIGIONS[tags["religion"]] ?: return null
        val denomination = DENOMINATIONS[tags["denomination"]] ?: return religion
        return "$religion（$denomination）"
    }

    /** `ja:鶴岡八幡宮` opens that article; a title with no language is taken as Japanese. */
    private fun wikipedia(tag: String?): SpotFact? {
        if (tag.isNullOrBlank()) return null
        val (lang, title) = tag.split(":", limit = 2).takeIf { it.size == 2 && it[0].length in 2..3 }
            ?.let { it[0] to it[1] } ?: ("ja" to tag)
        val page = URLEncoder.encode(title.trim().replace(' ', '_'), "UTF-8").replace("+", "_")
        return SpotFact("Wikipedia", title.trim(), "https://$lang.wikipedia.org/wiki/$page")
    }

    /** Shown by its host; only http(s), so nothing else is opened from map data. */
    private fun website(tag: String?): SpotFact? {
        val url = tag?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return null
        val host = runCatching { URI(url).host }.getOrNull()?.removePrefix("www.") ?: return null
        return SpotFact("ウェブサイト", host, url)
    }

    private val RELIGIONS = mapOf(
        "shinto" to "神道", "buddhist" to "仏教", "christian" to "キリスト教",
    )
    private val DENOMINATIONS = mapOf(
        "jodo_shinshu" to "浄土真宗", "jodo" to "浄土宗", "nichiren" to "日蓮宗", "shingon" to "真言宗",
        "tendai" to "天台宗", "zen" to "禅宗", "soto" to "曹洞宗", "rinzai" to "臨済宗", "obaku" to "黄檗宗",
        "ji" to "時宗", "catholic" to "カトリック", "roman_catholic" to "カトリック", "protestant" to "プロテスタント",
        "anglican" to "聖公会", "orthodox" to "正教会",
    )
}
