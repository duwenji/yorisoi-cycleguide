package io.github.duwenji.yorisoi.cycleguide.channel

import io.github.duwenji.yorisoi.core.format.Activity
import io.github.duwenji.yorisoi.core.format.Compatibility
import io.github.duwenji.yorisoi.core.format.Feature
import io.github.duwenji.yorisoi.cycleguide.prompt.BuiltInChannels
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which channels CycleGuide can use (requirements F-26, AC-9, AC-19). */
class CycleProfileTest {
    private val profile = CycleProfile.of(5)
    private val standard = BuiltInChannels.standard().manifest

    @Test
    fun `the built-in channels are all usable`() {
        BuiltInChannels.load().forEach { assertTrue(it.id, Compatibility.reasons(it.manifest, profile).isEmpty()) }
    }

    @Test
    fun `a channel not for cycling, or asking for what the app doesn't show, is not`() {
        assertTrue(Compatibility.reasons(standard.copy(activities = setOf(Activity.WALK)), profile).isNotEmpty())
        assertTrue(Compatibility.reasons(standard.copy(requires = setOf(Feature.PRESENT_SHEET)), profile).isNotEmpty())
        assertTrue(Compatibility.reasons(standard.copy(activities = setOf(Activity.WALK, Activity.CYCLE)), profile).isEmpty())
    }

    @Test
    fun `spot and point cues are read out, the rest is not offered (F-32)`() {
        assertTrue(Compatibility.reasons(standard.copy(requires = setOf(Feature.TRIGGER_SPOT, Feature.TRIGGER_POINT, Feature.PRESENT_SPEAK)), profile).isEmpty())
        assertTrue(Compatibility.reasons(standard.copy(requires = setOf(Feature.TRIGGER_EVENT)), profile).isNotEmpty())
        assertTrue(Compatibility.reasons(standard.copy(requires = setOf(Feature.PRESENT_CARD)), profile).isNotEmpty())
    }
}
