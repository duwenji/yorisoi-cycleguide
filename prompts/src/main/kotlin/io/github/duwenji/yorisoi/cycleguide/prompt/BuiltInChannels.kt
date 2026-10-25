package io.github.duwenji.yorisoi.cycleguide.prompt

import io.github.duwenji.yorisoi.kit.channel.ResourceChannels

/**
 * The channels that ship with the app, as resources under `cycleguide/channels/{id}/` in the
 * same layout as a third-party package (read and checked by platform kit-channel). The index of
 * their files is written by the build (`channelIndex` in prompts/build.gradle.kts).
 */
object BuiltInChannels : ResourceChannels(
    resourceDir = "cycleguide/channels",
    standardId = "standard",
    ids = listOf("standard", "history", "quiet"),
) {
    const val STANDARD = "standard"
}
