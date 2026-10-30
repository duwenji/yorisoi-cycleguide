package io.github.duwenji.yorisoi.cycleguide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.duwenji.yorisoi.cycleguide.CycleApp
import io.github.duwenji.yorisoi.kit.geo.SpotFacts
import io.github.duwenji.yorisoi.cycleguide.history.RideRecord
import io.github.duwenji.yorisoi.cycleguide.history.SpotTalk
import io.github.duwenji.yorisoi.cycleguide.ride.RideService
import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.kit.geo.map.FacilityItem
import io.github.duwenji.yorisoi.kit.geo.map.MapStyle
import io.github.duwenji.yorisoi.kit.geo.map.MapTiles
import io.github.duwenji.yorisoi.kit.geo.map.OsmTiles
import io.github.duwenji.yorisoi.kit.geo.map.OsmTilesException
import io.github.duwenji.yorisoi.kit.geo.map.SpotItem
import io.github.duwenji.yorisoi.kit.geo.map.SpotFactsView
import io.github.duwenji.yorisoi.kit.geo.map.SpotMap
import io.github.duwenji.yorisoi.kit.geo.map.rememberHeading
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

/**
 * What the maps show: the rider's choice, or pale GSI with [notice] saying why OpenStreetMap isn't
 * shown. Its server has no SLA and may refuse the app (OSMF tile usage policy).
 * [onTilesFailed] is for [SpotMap]'s report of OpenStreetMap tiles failing while shown.
 */
class MapChoice(val tiles: MapTiles, val notice: String?, val onTilesFailed: () -> Unit)

/** The map for [style]: OpenStreetMap after one tile came through ([OsmTiles.check]), checked again when [retry] changes. */
@Composable
fun rememberMapChoice(style: MapStyle, osm: OsmTiles, retry: Any?): MapChoice {
    // null until checked; then the reason it failed, or "" when it came through.
    var failure by remember(style, retry) { mutableStateOf<String?>(null) }
    LaunchedEffect(style, retry) {
        if (style != MapStyle.OSM_STANDARD) return@LaunchedEffect
        failure = try {
            osm.check()
            ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: OsmTilesException) {
            e.message
        } catch (e: Exception) {
            Log.w("RideMap", "OpenStreetMap tile check failed", e)
            "OpenStreetMap の地図に接続できませんでした"
        }
    }
    val tiles = when {
        style != MapStyle.OSM_STANDARD -> MapTiles.Gsi(style)
        failure == "" -> MapTiles.Osm
        else -> MapTiles.Gsi(MapStyle.GSI_PALE)
    }
    val notice = failure?.takeIf { it.isNotEmpty() }?.let { "$it。地理院の淡色地図で表示しています" }
    return MapChoice(tiles, notice) {
        if (failure == "") failure = "OpenStreetMap の地図を取得できなくなりました"
    }
}

/**
 * The map while stopped on a ride (architecture.md 図 1), the way ridden at the top: where the rider is and faces, the spots
 * near and those talked about on this ride, the style's amenities found, and the way ridden. A spot pressed shows what it is
 * and what was said there, under the map so the spot stays in sight. Never while moving (requirements S-1), and not outside a ride, when the
 * app doesn't use the location (privacy policy 3); [LastRideMap] is shown then. On the [map] the rider
 * chose (pale GSI unless changed, so spots and the way stand out).
 */
@Composable
fun RideMap(app: CycleApp, map: MapChoice, modifier: Modifier = Modifier) {
    val location by app.spots.location.collectAsStateWithLifecycle()
    val spots by app.spots.spots.collectAsStateWithLifecycle()
    val talkedSpots by RideService.talkedSpots.collectAsStateWithLifecycle()
    val talks by RideService.talks.collectAsStateWithLifecycle()
    val walked by RideService.walked.collectAsStateWithLifecycle()
    val way by RideService.heading.collectAsStateWithLifecycle()
    val facilities by app.spots.facilities.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    var picked by remember { mutableStateOf<Poi?>(null) }
    // The style's amenities only, as the companion points out (requirements F-7).
    val kinds = settings.values.style.amenities.flatMap { it.kinds }.toSet()
    // The spots passed stay on the map once the search has moved on from them.
    val items = (talkedSpots + spots).distinctBy { it.id }
        .map { poi -> SpotItem(poi, location?.let { poi.distanceFrom(it).toInt() }, visitCount = 0) }
    Column(modifier) {
        map.notice?.let { MapNotice(it) }
        SpotMap(
            tiles = map.tiles,
            location = location,
            heading = rememberHeading(location),
            spots = items,
            facilities = facilities.filter { it.kind in kinds }.map { f -> FacilityItem(f, location?.let { f.distanceFrom(it).toInt() }) },
            walked = walked,
            route = null,
            focus = null,
            onSpotClick = { picked = it.poi },
            onViewportChanged = { _, _, _, _, _ -> },
            // The map view draws past its bounds otherwise, over the buttons above it.
            modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds(),
            selected = picked,
            // The way ridden at the top, as the companion's "ahead" and "on the right" (Ahead);
            // in steps, so the GPS's drift while stopped doesn't keep turning it.
            up = way?.let { (it / UP_STEP_DEG).roundToInt() * UP_STEP_DEG % 360f },
            onTilesFailed = map.onTilesFailed,
        )
        picked?.let { poi ->
            SpotInfo(
                items.firstOrNull { it.poi.id == poi.id } ?: SpotItem(poi, null, visitCount = 0),
                talks.filter { it.poiId == poi.id },
                onClose = { picked = null },
            )
        }
    }
}

private const val UP_STEP_DEG = 10f

@Composable
private fun MapNotice(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 4.dp))
}

/**
 * The ride last finished, outside a ride (requirements F-31): the whole way ridden and the spots
 * talked about, with no position, since the app doesn't use the location then.
 */
@Composable
fun LastRideMap(ride: RideRecord, map: MapChoice, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("前回の走行: ${ride.summary.text()}", style = MaterialTheme.typography.bodyMedium)
        PastRideMap(ride, map, Modifier.fillMaxWidth().weight(1f))
    }
}

/** A finished ride's whole way and the spots talked about, with no position; a spot pressed shows what was said there. */
@Composable
fun PastRideMap(ride: RideRecord, map: MapChoice, modifier: Modifier = Modifier) {
    var picked by remember(ride.startedAt) { mutableStateOf<Poi?>(null) }
    Column(modifier) {
        map.notice?.let { MapNotice(it) }
        SpotMap(
            tiles = map.tiles,
            location = null,
            heading = null,
            spots = ride.spots.distinctBy { it.id }.map { SpotItem(it, distanceM = null, visitCount = 0) },
            facilities = emptyList(),
            walked = ride.route,
            route = null,
            focus = null,
            onSpotClick = { picked = it.poi },
            onViewportChanged = { _, _, _, _, _ -> },
            modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds(),
            selected = picked,
            fitWalked = true,
            onTilesFailed = map.onTilesFailed,
        )
        picked?.let { poi ->
            SpotInfo(
                SpotItem(poi, distanceM = null, visitCount = 0),
                ride.talks.filter { it.poiId == poi.id },
                onClose = { picked = null },
            )
        }
    }
}

/**
 * The spot pressed on a map: its name, kind and distance, what OpenStreetMap says about it
 * (kit-geo [SpotFacts], there before anything is said), and what was said there on the ride.
 */
@Composable
private fun SpotInfo(item: SpotItem, talks: List<SpotTalk>, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val poi = item.poi
    Surface(modifier.fillMaxWidth().padding(top = 8.dp), shape = MaterialTheme.shapes.medium, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 12.dp)) {
            val facts = remember(poi.id) { SpotFacts.of(poi.tags) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(poi.name, style = MaterialTheme.typography.titleMedium)
                    facts.reading?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                TextButton(onClick = onClose) { Text("閉じる") }
            }
            Text(
                listOfNotNull(poi.category, item.distanceM?.let(::distanceText)).joinToString("・"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                Modifier.padding(top = 8.dp, end = 12.dp).heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SpotFactsView(facts)
                if (!facts.isEmpty) HorizontalDivider(Modifier.padding(vertical = 2.dp))
                if (talks.isEmpty()) Text("この走行ではまだ話していません", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                talks.forEach { talk ->
                    Text("${clock.format(talk.at)}　${talk.text}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun distanceText(m: Int) = if (m < 1000) "$m m" else "%.1f km".format(m / 1000.0)
