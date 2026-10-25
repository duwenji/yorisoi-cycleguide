package io.github.duwenji.yorisoi.cycleguide.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.duwenji.yorisoi.cycleguide.CycleApp
import io.github.duwenji.yorisoi.cycleguide.ride.RideService
import io.github.duwenji.yorisoi.kit.geo.map.MapStyle
import io.github.duwenji.yorisoi.kit.geo.map.MapTiles
import io.github.duwenji.yorisoi.kit.geo.map.SpotItem
import io.github.duwenji.yorisoi.kit.geo.map.SpotMap
import io.github.duwenji.yorisoi.kit.geo.map.rememberHeading

/**
 * The map while stopped on a ride (architecture.md 図 1): where the rider is and faces, the spots
 * found, and the way ridden. Never while moving (requirements S-1), and not outside a ride, when the
 * app doesn't use the location (privacy policy 3). The pale GSI map, so spots and the way stand out.
 */
@Composable
fun RideMap(app: CycleApp, modifier: Modifier = Modifier) {
    val location by app.spots.location.collectAsStateWithLifecycle()
    val spots by app.spots.spots.collectAsStateWithLifecycle()
    val walked by RideService.walked.collectAsStateWithLifecycle()
    SpotMap(
        tiles = MapTiles.Gsi(MapStyle.GSI_PALE),
        location = location,
        heading = rememberHeading(location),
        spots = spots.map { poi -> SpotItem(poi, location?.let { poi.distanceFrom(it).toInt() }, visitCount = 0) },
        facilities = emptyList(),
        walked = walked,
        route = null,
        focus = null,
        onSpotClick = {},
        onViewportChanged = { _, _, _, _, _ -> },
        // The map view draws past its bounds otherwise, over the buttons above it.
        modifier = modifier.clipToBounds(),
    )
}
