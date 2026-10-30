package io.github.duwenji.yorisoi.cycleguide

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.ConnectivityManager
import android.util.Log
import io.github.duwenji.yorisoi.core.format.material.Regions
import io.github.duwenji.yorisoi.cycleguide.channel.CycleProfile
import io.github.duwenji.yorisoi.cycleguide.companion.Ahead
import io.github.duwenji.yorisoi.cycleguide.cue.RideCues
import io.github.duwenji.yorisoi.cycleguide.guide.Narrator
import io.github.duwenji.yorisoi.cycleguide.prompt.Prompts
import io.github.duwenji.yorisoi.cycleguide.prompt.BuiltInChannels
import io.github.duwenji.yorisoi.cycleguide.history.RideHistory
import io.github.duwenji.yorisoi.cycleguide.history.PastTalks
import io.github.duwenji.yorisoi.cycleguide.history.RideRecord
import io.github.duwenji.yorisoi.cycleguide.history.RideWay
import io.github.duwenji.yorisoi.cycleguide.ways.WayPrefetchWorker
import io.github.duwenji.yorisoi.kit.geo.FrequentWayFinder
import io.github.duwenji.yorisoi.kit.geo.FrequentWayStore
import io.github.duwenji.yorisoi.kit.geo.Trip
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.duwenji.yorisoi.cycleguide.settings.SettingsRepository
import io.github.duwenji.yorisoi.cycleguide.style.Amenity
import io.github.duwenji.yorisoi.cycleguide.style.StyleItem
import io.github.duwenji.yorisoi.kit.channel.StationRepository
import io.github.duwenji.yorisoi.kit.channel.material.CueFinder
import io.github.duwenji.yorisoi.kit.channel.material.FileCueMemory
import io.github.duwenji.yorisoi.kit.channel.remote.OkHttpProviderHttp
import io.github.duwenji.yorisoi.kit.channel.remote.ThirdPartyChannels
import io.github.duwenji.yorisoi.kit.channel.remote.TinkEd25519
import io.github.duwenji.yorisoi.kit.companion.SessionRecorder
import io.github.duwenji.yorisoi.kit.geo.LatLon
import io.github.duwenji.yorisoi.kit.geo.OverpassClient
import io.github.duwenji.yorisoi.kit.geo.SpotCache
import io.github.duwenji.yorisoi.kit.geo.SpotRepository
import io.github.duwenji.yorisoi.kit.geo.SpotTiles
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
import io.github.duwenji.yorisoi.kit.geo.map.OsmTiles
import org.osmdroid.config.Configuration
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

    /** Channels from the lists the user added (requirements F-26). */
    lateinit var channels: ThirdPartyChannels
        private set
    lateinit var spots: SpotRepository
        private set
    lateinit var speaker: Speaker
        private set
    lateinit var ai: AiGateway
        private set
    lateinit var narrator: Narrator
        private set

    /** The channel's spot and point cues (requirements F-32). */
    lateinit var cues: RideCues
        private set

    /** The developer's record of each ride taken in developer mode (requirements F-29). */
    lateinit var diagnostics: SessionRecorder
        private set

    /** The rides finished (requirements F-28); the newest is shown on the start screen (F-31). */
    lateinit var history: RideHistory
        private set

    /** The ways ridden again and again, and the ones the rider made (REQ-FREQUENT-WAYS). */
    lateinit var ways: FrequentWayStore
        private set
    val weather = WeatherClient()
    val osmTiles = OsmTiles()

    /** The way the rider goes, set by the ride service; the spot search looks ahead along it. */
    @Volatile
    var heading: Double? = null

    override fun onCreate() {
        super.onCreate()
        // The map's tile requests name the app (osmdroid's user agent).
        Configuration.getInstance().userAgentValue = "CycleGuide/${BuildConfig.VERSION_NAME} (Android)"
        val relay = OperatorAi.create(this, BuildConfig.AI_RELAY_URL, BuildConfig.PLAY_CLOUD_PROJECT_NUMBER)
        settings = SettingsRepository(this, operatorAvailable = relay != null)
        ai = AiGateway({ settings.settings.value.connection }, AiUsage.of(this), relay)
        diagnostics = SessionRecorder(File(filesDir, "diagnostics"), startType = "ride_start", endType = "ride_end") {
            Log.w("SessionRecorder", "Could not write a diagnostics line", it)
        }
        history = RideHistory(File(filesDir, "rides"), legacy = File(filesDir, "last-ride.json")) { Log.w("RideHistory", "Could not keep a ride", it) }
        ways = FrequentWayStore(File(filesDir, "ways.json"), WAY_FINDER) { Log.w("FrequentWayStore", "Could not keep the ways", it) }
        ai.onCall = { call ->
            Log.i("AiGateway", "${call.kind} ${call.model} ${call.ms}ms error=${call.error} violation=${call.violation}")
            diagnostics.event(
                "ai", "kind" to call.kind, "model" to call.model, "ms" to call.ms,
                "system" to call.system, "user" to call.user, "reply" to call.reply,
                "error" to call.error, "violation" to call.violation,
            )
        }
        narrator = Narrator(
            Prompts.fromResources(), ai::generate, aiName = { settings.settings.value.connection.name },
            onError = { Log.w("Narrator", "No line from the AI", it) },
        )
        cues = RideCues(narrator, CueFinder(FileCueMemory(File(filesDir, "cues.json")))) { message, e ->
            Log.w("RideCues", message, e)
            diagnostics.event("producer", "message" to message, "error" to e?.toString())
        }
        val developerMode = settings.settings.map { it.developerMode }.stateIn(appScope, SharingStarted.Eagerly, settings.settings.value.developerMode)
        val connectivity = getSystemService(ConnectivityManager::class.java)
        channels = ThirdPartyChannels(
            dir = File(filesDir, "channels"),
            http = OkHttpProviderHttp("CycleGuide/${BuildConfig.VERSION_NAME}"),
            verifier = TinkEd25519,
            profile = CycleProfile.of(BuildConfig.VERSION_CODE),
            allowLocalHttp = BuildConfig.DEBUG,
            unmetered = { connectivity?.isActiveNetworkMetered == false },
            standard = BuiltInChannels.standard(),
            scope = appScope,
            developerMode = developerMode,
        ) {
            // Only the coarse region (3-letter geohashes, about 150km) leaves the location (API-004 P-6),
            // and only while riding: the app doesn't use the location otherwise.
            spots.location.value?.let { Regions.around(it.latitude, it.longitude) }
        }
        val choice = settings.settings.map { it.channelChoice }.stateIn(appScope, SharingStarted.Eagerly, settings.settings.value.channelChoice)
        stations = StationRepository(BuiltInChannels.load(), BuiltInChannels.STANDARD, choice, appScope, channels.stations)
        channels.refreshIfDue()
        spots = SpotRepository(
            OverpassClient(),
            shops = { stations.current.value.searchedKinds },
            cache = SpotCache(File(cacheDir, "spots")),
            // Ahead of the rider, half the style's distance (architecture.md「前方」).
            searchCenter = { location ->
                Ahead.searchCenter(LatLon(location.latitude, location.longitude), heading, settings.settings.value.values[StyleItem.AHEAD_M])
            },
            signals = true,
            facilityKinds = Amenity.SEARCHED,
            // A rider soon leaves the kept answers behind: ask again within a minute (architecture.md「前方」).
            retryDelaysMs = SPOT_RETRY_DELAYS_MS,
            tiles = SpotTiles(BuildConfig.SPOT_TILES_URL, "CycleGuide/${BuildConfig.VERSION_NAME}"),
        )
        WayPrefetchWorker.scheduleDaily(this)
        speaker = Speaker(this)
        getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(NotificationChannel(CHANNEL_RIDE, "走行中", NotificationManager.IMPORTANCE_LOW))
        }
    }

    /**
     * Keeps a ride that ended: first the frequent ways it went along, or made (REQ-FREQUENT-WAYS
     * F-3, F-4, F-12), among the rides of the last 60 days; then a search ahead along the ways,
     * when the network allows (D-9). Off the main thread: the rides are read from their files.
     */
    fun keepRide(ride: RideRecord) = appScope.launch(Dispatchers.IO) {
        val earlier = history.since(ride.endedAt - FrequentWayStore.WINDOW_MS).filter { it.startedAt != ride.startedAt }
        val passed = ways.afterTrip(trip(ride), earlier.map(::trip), find = settings.settings.value.findWays) { points ->
            FrequentWayFinder.nameFrom(points, ride.spots)
        }
        history.add(ride.copy(ways = passed.map { RideWay(it.way.id, it.way.name, it.passes, it.new) }))
        WayPrefetchWorker.afterRide(this@CycleApp)
    }

    /** What was said on the rides of the last 60 days (REQ-FREQUENT-WAYS F-9). */
    suspend fun pastTalks(now: Long): PastTalks = withContext(Dispatchers.IO) { PastTalks.of(history.since(now - PastTalks.WINDOW_MS)) }

    /** The rides finished and the frequent ways found in them forgotten (F-2). */
    fun clearHistory() {
        history.clear()
        ways.clearFound()
    }

    companion object {
        const val CHANNEL_RIDE = "ride"

        /** A rider's ways (REQ-FREQUENT-WAYS D-8). */
        val WAY_FINDER = FrequentWayFinder(minLengthM = 500.0)

        private fun trip(ride: RideRecord) = Trip(ride.endedAt, ride.route)

        /** How long to wait before asking Overpass again, after one failed search, two, and more. */
        val SPOT_RETRY_DELAYS_MS = longArrayOf(20_000, 30_000, 60_000)
    }
}
