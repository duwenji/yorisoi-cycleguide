package io.github.duwenji.yorisoi.cycleguide.prompt

import io.github.duwenji.yorisoi.core.format.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The built-in channels pass the channel format's checks and are for cycling (requirements AC-19). */
class BuiltInChannelsTest {
    private val stations = BuiltInChannels.load()

    @Test
    fun `every built-in channel is read and checked, in the app's order`() {
        assertEquals(BuiltInChannels.ids, stations.map { it.id })
        assertEquals(BuiltInChannels.STANDARD, stations.first().id)
    }

    @Test
    fun `every built-in channel is for cycling`() {
        assertTrue(stations.all { Activity.CYCLE in it.manifest.activities })
    }
}
