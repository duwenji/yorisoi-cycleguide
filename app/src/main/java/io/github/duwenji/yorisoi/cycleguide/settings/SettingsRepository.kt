package io.github.duwenji.yorisoi.cycleguide.settings

import android.content.Context
import androidx.core.content.edit
import io.github.duwenji.yorisoi.cycleguide.prompt.BuiltInChannels
import io.github.duwenji.yorisoi.cycleguide.style.RideStyles
import io.github.duwenji.yorisoi.cycleguide.style.StyleItem
import io.github.duwenji.yorisoi.cycleguide.style.StyleValues
import io.github.duwenji.yorisoi.kit.channel.ChannelChoice
import io.github.duwenji.yorisoi.kit.llm.AiConnection
import io.github.duwenji.yorisoi.kit.llm.AiRoute
import io.github.duwenji.yorisoi.kit.llm.KeyCipher
import io.github.duwenji.yorisoi.kit.llm.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the rider chose: the ride style, their changes to each style's values (kept per style, so
 * switching back finds them again), the channel, and how the AI is reached (per-provider keys and
 * models, so switching back keeps them).
 */
data class RideSettings(
    val styleId: String = RideStyles.DEFAULT.id,
    val styleOverrides: Map<String, Map<StyleItem, Int>> = emptyMap(),
    val stationId: String = BuiltInChannels.STANDARD,
    /** Whether the reminder to keep the ears free was shown before a first ride. */
    val earphoneNoticeShown: Boolean = false,
    val aiRoute: AiRoute = AiRoute.OPERATOR,
    val provider: Provider = Provider.CLAUDE,
    val apiKeys: Map<Provider, String> = emptyMap(),
    val models: Map<Provider, String> = emptyMap(),
    val customBaseUrl: String = "",
    /** How many times a day the user's own key may be called; null for no limit (platform ADR-AI-ROLES H-9). */
    val dailyAiLimit: Int? = 100,
) {
    fun apiKey(p: Provider = provider): String = apiKeys[p].orEmpty()
    fun model(p: Provider = provider): String = models[p]?.takeIf { it.isNotBlank() } ?: p.defaultModel
    fun baseUrl(p: Provider = provider): String = if (p == Provider.CUSTOM) customBaseUrl else p.defaultBaseUrl

    /** The AI settings as platform kit-llm's AiGateway reads them. */
    val connection: AiConnection get() = AiConnection(aiRoute, provider, apiKey(), model(), baseUrl(), dailyAiLimit)

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

    companion object {
        /** The daily limits offered on the settings screen; null is「上限なし」. */
        val DAILY_AI_LIMITS: List<Int?> = listOf(30, 60, 100, 200, null)
    }
}

/**
 * [RideSettings] in the app's preferences (kept out of backups, as every preference); API keys are
 * stored encrypted with [KeyCipher] (requirements P-2).
 *
 * @param operatorAvailable whether the build has the operator's relay: the AI route starts there if so
 */
class SettingsRepository(context: Context, private val operatorAvailable: Boolean) {
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
            aiRoute = prefs.getString(KEY_AI_ROUTE, null)?.let { n -> AiRoute.entries.firstOrNull { it.name == n } }
                ?: if (operatorAvailable) AiRoute.OPERATOR else AiRoute.OWN_KEY,
            provider = prefs.getString(KEY_PROVIDER, null)?.let { n -> Provider.entries.firstOrNull { it.name == n } } ?: Provider.CLAUDE,
            apiKeys = Provider.entries.mapNotNull { p -> prefs.getString(keyPref(p), null)?.let(KeyCipher::decrypt)?.let { p to it } }.toMap(),
            models = Provider.entries.mapNotNull { p -> prefs.getString(modelPref(p), null)?.let { p to it } }.toMap(),
            customBaseUrl = prefs.getString(KEY_CUSTOM_BASE_URL, "").orEmpty(),
            dailyAiLimit = if (prefs.contains(KEY_DAILY_AI_LIMIT)) prefs.getInt(KEY_DAILY_AI_LIMIT, NO_LIMIT).takeIf { it != NO_LIMIT } else 100,
        )
    }

    private fun save(s: RideSettings) = prefs.edit {
        putString(KEY_STYLE, s.styleId)
        putString(KEY_STATION, s.stationId)
        putBoolean(KEY_EARPHONE_NOTICE, s.earphoneNoticeShown)
        putString(KEY_AI_ROUTE, s.aiRoute.name)
        putString(KEY_PROVIDER, s.provider.name)
        putString(KEY_CUSTOM_BASE_URL, s.customBaseUrl.trim())
        putInt(KEY_DAILY_AI_LIMIT, s.dailyAiLimit ?: NO_LIMIT)
        for (p in Provider.entries) {
            val key = s.apiKeys[p]?.trim().orEmpty()
            if (key.isEmpty()) remove(keyPref(p)) else putString(keyPref(p), KeyCipher.encrypt(key))
            val model = s.models[p]?.trim().orEmpty()
            if (model.isEmpty()) remove(modelPref(p)) else putString(modelPref(p), model)
        }
        for (style in RideStyles.ALL) for (item in StyleItem.entries) {
            val value = s.styleOverrides[style.id]?.get(item)
            if (value == null) remove(key(style.id, item)) else putInt(key(style.id, item), value)
        }
    }

    private fun key(styleId: String, item: StyleItem) = "style_${styleId}_${item.name}"
    private fun keyPref(p: Provider) = "api_key_${p.name}"
    private fun modelPref(p: Provider) = "model_${p.name}"

    private companion object {
        const val KEY_STYLE = "style"
        const val KEY_STATION = "station"
        const val KEY_EARPHONE_NOTICE = "earphone_notice_shown"
        const val KEY_AI_ROUTE = "ai_route"
        const val KEY_PROVIDER = "provider"
        const val KEY_CUSTOM_BASE_URL = "custom_base_url"
        const val KEY_DAILY_AI_LIMIT = "daily_ai_limit"
        const val NO_LIMIT = -1
    }
}
