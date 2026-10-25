package io.github.duwenji.yorisoi.cycleguide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.duwenji.yorisoi.cycleguide.CycleApp
import io.github.duwenji.yorisoi.cycleguide.history.RideRecord
import io.github.duwenji.yorisoi.cycleguide.ride.RideService
import io.github.duwenji.yorisoi.kit.geo.map.FacilityItem
import io.github.duwenji.yorisoi.kit.geo.map.MapStyle
import io.github.duwenji.yorisoi.kit.geo.map.MapTiles
import io.github.duwenji.yorisoi.kit.geo.map.SpotItem
import io.github.duwenji.yorisoi.kit.geo.map.SpotMap
import io.github.duwenji.yorisoi.kit.geo.map.rememberHeading

/**
 * The map while stopped on a ride (architecture.md 図 1): where the rider is and faces, the spots
 * and the style's amenities found, and the way ridden. Never while moving (requirements S-1), and not outside a ride, when the
 * app doesn't use the location (privacy policy 3); [LastRideMap] is shown then. The pale GSI map, so
 * spots and the way stand out.
 */
@Composable
fun RideMap(app: CycleApp, modifier: Modifier = Modifier) {
    val location by app.spots.location.collectAsStateWithLifecycle()
    val spots by app.spots.spots.collectAsStateWithLifecycle()
    val walked by RideService.walked.collectAsStateWithLifecycle()
    val facilities by app.spots.facilities.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    // The style's amenities only, as the companion points out (requirements F-7).
    val kinds = settings.values.style.amenities.flatMap { it.kinds }.toSet()
    SpotMap(
        tiles = MapTiles.Gsi(MapStyle.GSI_PALE),
        location = location,
        heading = rememberHeading(location),
        spots = spots.map { poi -> SpotItem(poi, location?.let { poi.distanceFrom(it).toInt() }, visitCount = 0) },
        facilities = facilities.filter { it.kind in kinds }.map { f -> FacilityItem(f, location?.let { f.distanceFrom(it).toInt() }) },
        walked = walked,
        route = null,
        focus = null,
        onSpotClick = {},
        onViewportChanged = { _, _, _, _, _ -> },
        // The map view draws past its bounds otherwise, over the buttons above it.
        modifier = modifier.clipToBounds(),
    )
}

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

/** A finished ride's whole way and the spots talked about, with no position. */
@Composable
fun PastRideMap(ride: RideRecord, modifier: Modifier = Modifier) {
    SpotMap(
        tiles = MapTiles.Gsi(MapStyle.GSI_PALE),
        location = null,
        heading = null,
        spots = ride.spots.map { SpotItem(it, distanceM = null, visitCount = 0) },
        facilities = emptyList(),
        walked = ride.route,
        route = null,
        focus = null,
        onSpotClick = {},
        onViewportChanged = { _, _, _, _, _ -> },
        modifier = modifier.clipToBounds(),
        fitWalked = true,
    )
}
