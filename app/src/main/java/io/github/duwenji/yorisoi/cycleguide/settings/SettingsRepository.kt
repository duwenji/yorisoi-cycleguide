package io.github.duwenji.yorisoi.cycleguide.settings

import android.content.Context
import androidx.core.content.edit
import io.github.duwenji.yorisoi.cycleguide.prompt.BuiltInChannels
import io.github.duwenji.yorisoi.cycleguide.style.RideStyles
import io.github.duwenji.yorisoi.cycleguide.style.StyleItem
import io.github.duwenji.yorisoi.cycleguide.style.StyleValues
import io.github.duwenji.yorisoi.kit.channel.ChannelChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the rider chose: the ride style, their changes to each style's values (kept per style, so
 * switching back finds them again), and the channel.
 */
data class RideSettings(
    val styleId: String = RideStyles.DEFAULT.id,
    val styleOverrides: Map<String, Map<StyleItem, Int>> = emptyMap(),
    val stationId: String = BuiltInChannels.STANDARD,
    /** Whether the reminder to keep the ears free was shown before a first ride. */
    val earphoneNoticeShown: Boolean = false,
) {
    val values: StyleValues
        get() = RideStyles.byId(styleId).let { StyleValues(it, styleOverrides[it.id].orEmpty()) }

    val channelChoice: ChannelChoice get() = ChannelChoice(stationId)

    fun withValue(item: StyleItem, value: Int): RideSettings {
        val id = RideStyles.byId(styleId).id
        val changed = styleOverrides[id].orEmpty() + (item to value.coerceIn(item.min, item.max))
        return copy(styleOverrides = styleOverrides + (id to changed))
    }

    /** The current style back to its own values. */
    fun resetStyle(): RideSettings = copy(styleOverrides = styleOverrides - RideStyles.byId(styleId).id)
}

/** [RideSettings] in the app's preferences (kept out of backups, as every preference). */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<RideSettings> = _settings.asStateFlow()

    fun update(change: (RideSettings) -> RideSettings) {
        val s = change(_settings.value)
        save(s)
        _settings.value = s
    }

    private fun load(): RideSettings {
        val all = prefs.all
        val overrides = RideStyles.ALL.associate { style ->
            style.id to StyleItem.entries.mapNotNull { item -> (all[key(style.id, item)] as? Int)?.let { item to it } }.toMap()
        }.filterValues { it.isNotEmpty() }
        return RideSettings(
            styleId = prefs.getString(KEY_STYLE, null) ?: RideStyles.DEFAULT.id,
            styleOverrides = overrides,
            stationId = prefs.getString(KEY_STATION, null) ?: BuiltInChannels.STANDARD,
            earphoneNoticeShown = prefs.getBoolean(KEY_EARPHONE_NOTICE, false),
        )
    }

    private fun save(s: RideSettings) = prefs.edit {
        putString(KEY_STYLE, s.styleId)
        putString(KEY_STATION, s.stationId)
        putBoolean(KEY_EARPHONE_NOTICE, s.earphoneNoticeShown)
        for (style in RideStyles.ALL) for (item in StyleItem.entries) {
            val value = s.styleOverrides[style.id]?.get(item)
            if (value == null) remove(key(style.id, item)) else putInt(key(style.id, item), value)
        }
    }

    private fun key(styleId: String, item: StyleItem) = "style_${styleId}_${item.name}"

    private companion object {
        const val KEY_STYLE = "style"
        const val KEY_STATION = "station"
        const val KEY_EARPHONE_NOTICE = "earphone_notice_shown"
    }
}
