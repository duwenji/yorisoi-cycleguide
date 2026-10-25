package io.github.duwenji.yorisoi.cycleguide.cue

import io.github.duwenji.yorisoi.core.format.material.MaterialType
import io.github.duwenji.yorisoi.cycleguide.guide.Narrator
import io.github.duwenji.yorisoi.cycleguide.guide.PoiSpot
import io.github.duwenji.yorisoi.cycleguide.prompt.BuiltInChannels
import io.github.duwenji.yorisoi.cycleguide.prompt.Prompts
import io.github.duwenji.yorisoi.kit.channel.material.Content
import io.github.duwenji.yorisoi.kit.channel.material.Ground
import io.github.duwenji.yorisoi.kit.channel.material.MaterialContext
import io.github.duwenji.yorisoi.kit.channel.material.MaterialRequest
import io.github.duwenji.yorisoi.kit.channel.material.MaterialResult
import io.github.duwenji.yorisoi.kit.channel.material.Subject
import io.github.duwenji.yorisoi.kit.geo.Poi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The built-in producers a channel's cue asks on a ride (requirements F-32). */
class RideCuesTest {
    private val standard = BuiltInChannels.load().first()
    private val shrine = Poi("way/1", "鶴岡八幡宮", 35.3260, 139.5565, "神社", emptyMap())
    private val asked = mutableListOf<String>()
    private val narrator = Narrator(Prompts.fromResources(), { _, system, user -> asked += system + "\n" + user; "八幡宮の話です。" }, aiName = { "運営の AI" })
    private val ground = Ground("本宮は大石段の上にあります。", "確認用の資料の会", null, "kamakura", 1, "area")

    private fun text(r: MaterialResult) = ((r as MaterialResult.Ready).material.content as Content.Text).text

    @Test
    fun `the AI narrates a cue's spot on the request's grounds, within the ride's length`() = runBlocking {
        val ride = RideContext(riding = true, maxChars = 75)
        val request = MaterialRequest(PoiSpot(shrine, 160), MaterialType.TEXT, MaterialContext(standard, ride), grounding = listOf(ground))
        val result = AiGuideProducer(narrator).produce(request)
        assertEquals("八幡宮の話です。", text(result))
        assertEquals("文: 運営の AI / 確認用の資料の会の資料をもとに作成", (result as MaterialResult.Ready).material.credit?.author)
        assertTrue(asked.single().contains("75字以内"))
        assertTrue(asked.single().contains("<資料 出典=\"確認用の資料の会\">"))
    }

    @Test
    fun `a point cue is narrated as the spot nearest its middle, or not at all`() = runBlocking {
        val point = Subject.Point(35.3258, 139.5564, 300)
        val none = MaterialRequest(point, MaterialType.TEXT, MaterialContext(standard, RideContext(true, 75)))
        assertEquals(MaterialResult.None, AiGuideProducer(narrator).produce(none))
        val near = none.copy(context = MaterialContext(standard, RideContext(false, 150, PoiSpot(shrine, 40))))
        assertEquals("八幡宮の話です。", text(AiGuideProducer(narrator).produce(near)))
        // The fixed line has no name for a point.
        assertEquals(MaterialResult.None, FallbackTextProducer().produce(none))
    }

    @Test
    fun `the fixed line names the spot, ahead while riding`() = runBlocking {
        val riding = MaterialRequest(PoiSpot(shrine, 160), MaterialType.TEXT, MaterialContext(standard, RideContext(true, 75)))
        assertEquals("この先、鶴岡八幡宮があります。", text(FallbackTextProducer().produce(riding)))
        val stopped = riding.copy(context = MaterialContext(standard, RideContext(false, 150)))
        assertEquals("近くに、鶴岡八幡宮があります。", text(FallbackTextProducer().produce(stopped)))
    }
}
