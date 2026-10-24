package io.github.duwenji.yorisoi.cycleguide.companion

import io.github.duwenji.yorisoi.kit.geo.LatLon
import io.github.duwenji.yorisoi.kit.geo.RouteGeometry

/**
 * Where the companion keeps quiet but for safety (requirements F-5): while moving, near a traffic
 * light or faster than the style's quiet speed (going downhill). Stopped, at a light too, it may talk.
 */
object QuietZones {
    const val SIGNAL_RADIUS_M = 30.0

    fun isQuiet(state: MotionState, here: LatLon?, speedMps: Float?, signals: List<LatLon>, quietSpeedKmh: Int): Boolean {
        if (state != MotionState.MOVING) return false
        if (speedMps != null && speedMps * 3.6 >= quietSpeedKmh) return true
        return here != null && signals.any { RouteGeometry.distanceM(it, here) <= SIGNAL_RADIUS_M }
    }
}
