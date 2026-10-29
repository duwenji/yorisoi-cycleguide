package io.github.duwenji.yorisoi.cycleguide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import io.github.duwenji.yorisoi.cycleguide.history.RideRecord
import io.github.duwenji.yorisoi.cycleguide.history.SpotTalk
import io.github.duwenji.yorisoi.cycleguide.ride.RideService
import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.kit.geo.map.FacilityItem
import io.github.duwenji.yorisoi.kit.geo.map.MapStyle
import io.github.duwenji.yorisoi.kit.geo.map.MapTiles
import io.github.duwenji.yorisoi.kit.geo.map.SpotItem
import io.github.duwenji.yorisoi.kit.geo.map.SpotMap
import io.github.duwenji.yorisoi.kit.geo.map.rememberHeading
import kotlin.math.roundToInt

/**
 * The map while stopped on a ride (architecture.md 図 1), the way ridden at the top: where the rider is and faces, the spots
 * near and those talked about on this ride, the style's amenities found, and the way ridden. A spot pressed shows what it is
 * and what was said there, under the map so the spot stays in sight. Never while moving (requirements S-1), and not outside a ride, when the
 * app doesn't use the location (privacy policy 3); [LastRideMap] is shown then. The pale GSI map, so
 * spots and the way stand out.
 */
@Composable
fun RideMap(app: CycleApp, modifier: Modifier = Modifier) {
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
        SpotMap(
            tiles = MapTiles.Gsi(MapStyle.GSI_PALE),
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

/**
 * The ride last finished, outside a ride (requirements F-31): the whole way ridden and the spots
 * talked about, with no position, since the app doesn't use the location then.
 */
@Composable
fun LastRideMap(ride: RideRecord, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("前回の走行: ${ride.summary.text()}", style = MaterialTheme.typography.bodyMedium)
        PastRideMap(ride, Modifier.fillMaxWidth().weight(1f))
    }
}

/** A finished ride's whole way and the spots talked about, with no position; a spot pressed shows what was said there. */
@Composable
fun PastRideMap(ride: RideRecord, modifier: Modifier = Modifier) {
    var picked by remember(ride.startedAt) { mutableStateOf<Poi?>(null) }
    Column(modifier) {
        SpotMap(
            tiles = MapTiles.Gsi(MapStyle.GSI_PALE),
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

/** The spot pressed on a map: its name, kind and distance, and what was said there on the ride. */
@Composable
private fun SpotInfo(item: SpotItem, talks: List<SpotTalk>, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val poi = item.poi
    Surface(modifier.fillMaxWidth().padding(top = 8.dp), shape = MaterialTheme.shapes.medium, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(poi.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
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
                if (talks.isEmpty()) Text("この走行ではまだ話していません", style = MaterialTheme.typography.bodyMedium)
                talks.forEach { talk ->
                    Text("${clock.format(talk.at)}　${talk.text}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun distanceText(m: Int) = if (m < 1000) "$m m" else "%.1f km".format(m / 1000.0)
