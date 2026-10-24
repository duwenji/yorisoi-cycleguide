package io.github.duwenji.yorisoi.cycleguide

import android.app.Application
import io.github.duwenji.yorisoi.cycleguide.prompt.BuiltInChannels
import io.github.duwenji.yorisoi.kit.channel.Station

/**
 * Builds the parts the screens and the ride service share, one by one (no DI library, as in
 * SanpoGuide; docs/architecture.md 図 1).
 */
class CycleApp : Application() {
    /** The channels that ship with the app, checked as a third party's would be. */
    lateinit var builtInChannels: List<Station>
        private set

    override fun onCreate() {
        super.onCreate()
        builtInChannels = BuiltInChannels.load()
    }
}
