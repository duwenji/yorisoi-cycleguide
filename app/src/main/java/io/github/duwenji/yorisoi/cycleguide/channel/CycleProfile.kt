package io.github.duwenji.yorisoi.cycleguide.channel

import io.github.duwenji.yorisoi.core.format.Activity
import io.github.duwenji.yorisoi.core.format.AppIds
import io.github.duwenji.yorisoi.core.format.AppProfile
import io.github.duwenji.yorisoi.core.format.Feature

/**
 * CycleGuide as the ecosystem sees it (API-003 G-4; requirements F-26, F-32): a cycling companion that
 * reads a channel's guide and companion slots and its spot and point cues, and speaks. As the channel management system lists it
 * (yorisoi-console `ecosystem.ts`).
 */
object CycleProfile {
    val FEATURES: Set<Feature> = setOf(Feature.SLOT_GUIDE, Feature.SLOT_COMPANION, Feature.TRIGGER_SPOT, Feature.TRIGGER_POINT, Feature.PRESENT_SPEAK)

    fun of(versionCode: Int) = AppProfile(AppIds.CYCLEGUIDE, Activity.CYCLE, FEATURES, versionCode)
}
