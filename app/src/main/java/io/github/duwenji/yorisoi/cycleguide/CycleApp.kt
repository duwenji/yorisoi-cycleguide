package io.github.duwenji.yorisoi.cycleguide

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import io.github.duwenji.yorisoi.cycleguide.companion.Ahead
import io.github.duwenji.yorisoi.cycleguide.guide.Narrator
import io.github.duwenji.yorisoi.cycleguide.prompt.Prompts
import io.github.duwenji.yorisoi.cycleguide.prompt.BuiltInChannels
import io.github.duwenji.yorisoi.cycleguide.settings.SettingsRepository
import io.github.duwenji.yorisoi.cycleguide.style.StyleItem
import io.github.duwenji.yorisoi.kit.channel.StationRepository
import io.github.duwenji.yorisoi.kit.geo.LatLon
import io.github.duwenji.yorisoi.kit.geo.OverpassClient
import io.github.duwenji.yorisoi.kit.geo.SpotCache
import io.github.duwenji.yorisoi.kit.geo.SpotRepository
import io.github.duwenji.yorisoi.kit.geo.weather.WeatherClient
import io.github.duwenji.yorisoi.kit.llm.AiGateway
import io.github.duwenji.yorisoi.kit.llm.AiUsage
import io.github.duwenji.yorisoi.kit.llm.OperatorAi
import io.github.duwenji.yorisoi.kit.voice.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File

/**
 * Builds the parts the screens and the ride service share, one by one (no DI library, as in
 * SanpoGuide; docs/architecture.md 図 1).
 */
class CycleApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var settings: SettingsRepository
        private set
    lateinit var stations: StationRepository
        private set
    lateinit var spots: SpotRepository
        private set
    lateinit var speaker: Speaker
        private set
    lateinit var ai: AiGateway
        private set
    lateinit var narrator: Narrator
        private set
    val weather = WeatherClient()

    /** The way the rider goes, set by the ride service; the spot search looks ahead along it. */
    @Volatile
    var heading: Double? = null

    override fun onCreate() {
        super.onCreate()
        val relay = OperatorAi.create(this, BuildConfig.AI_RELAY_URL, BuildConfig.PLAY_CLOUD_PROJECT_NUMBER)
        settings = SettingsRepository(this, operatorAvailable = relay != null)
        ai = AiGateway({ settings.settings.value.connection }, AiUsage.of(this), relay)
        ai.onCall = { call -> Log.i("AiGateway", "${call.kind} ${call.model} ${call.ms}ms error=${call.error} violation=${call.violation} reply=${call.reply}") }
        narrator = Narrator(
            Prompts.fromResources(), ai::generate, aiName = { settings.settings.value.connection.name },
            onError = { Log.w("Narrator", "No line from the AI", it) },
        )
        val choice = settings.settings.map { it.channelChoice }.stateIn(appScope, SharingStarted.Eagerly, settings.settings.value.channelChoice)
        stations = StationRepository(BuiltInChannels.load(), BuiltInChannels.STANDARD, choice, appScope)
        spots = SpotRepository(
            OverpassClient(),
            shops = { stations.current.value.searchedKinds },
            cache = SpotCache(File(cacheDir, "spots")),
            // Ahead of the rider, half the style's distance (architecture.md「前方」).
            searchCenter = { location ->
                Ahead.searchCenter(LatLon(location.latitude, location.longitude), heading, settings.settings.value.values[StyleItem.AHEAD_M])
            },
            signals = true,
        )
        speaker = Speaker(this)
        getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(NotificationChannel(CHANNEL_RIDE, "走行中", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        const val CHANNEL_RIDE = "ride"
    }
}
