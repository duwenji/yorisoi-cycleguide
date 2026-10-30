package io.github.duwenji.yorisoi.cycleguide.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotFactsTest {
    @Test
    fun `a shrine's tags become lines to read`() {
        val facts = SpotFacts.of(
            mapOf(
                "name" to "鶴岡八幡宮", "name:ja-Hira" to "つるがおかはちまんぐう", "name:en" to "Tsurugaoka Hachimangu",
                "amenity" to "place_of_worship", "religion" to "shinto", "start_date" to "1063",
                "heritage" to "2", "wikipedia" to "ja:鶴岡八幡宮", "website" to "https://www.hachimangu.or.jp/",
            ),
        )
        assertEquals(
            listOf(
                SpotFact("よみ", "つるがおかはちまんぐう"),
                SpotFact("英語名", "Tsurugaoka Hachimangu"),
                SpotFact("宗教", "神道"),
                SpotFact("創建・開設", "1063"),
                SpotFact("文化財", "指定あり"),
                SpotFact("Wikipedia", "鶴岡八幡宮", "https://ja.wikipedia.org/wiki/%E9%B6%B4%E5%B2%A1%E5%85%AB%E5%B9%A1%E5%AE%AE"),
                SpotFact("ウェブサイト", "hachimangu.or.jp", "https://www.hachimangu.or.jp/"),
            ),
            facts,
        )
    }

    @Test
    fun `a known denomination is named, an unknown one left out`() {
        assertEquals(SpotFact("宗教", "仏教（臨済宗）"), SpotFacts.of(mapOf("religion" to "buddhist", "denomination" to "rinzai")).single())
        assertEquals(SpotFact("宗教", "仏教"), SpotFacts.of(mapOf("religion" to "buddhist", "denomination" to "kegon_x")).single())
    }

    @Test
    fun `a wikipedia title in another language opens that wikipedia`() {
        val fact = SpotFacts.of(mapOf("wikipedia" to "en:Kamakura Daibutsu")).single()
        assertEquals("Kamakura Daibutsu", fact.value)
        assertEquals("https://en.wikipedia.org/wiki/Kamakura_Daibutsu", fact.url)
    }

    @Test
    fun `nothing but http pages is opened from map data`() {
        assertTrue(SpotFacts.of(mapOf("website" to "javascript:alert(1)")).isEmpty())
        assertTrue(SpotFacts.of(mapOf("amenity" to "bench", "operator" to "鎌倉市")).isEmpty())
    }
}
