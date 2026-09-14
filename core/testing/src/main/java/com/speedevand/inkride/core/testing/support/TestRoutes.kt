package com.speedevand.inkride.core.testing.support

import com.speedevand.inkride.core.domain.tracking.PlannedRoute
import com.speedevand.inkride.core.domain.tracking.RoutePoint
import com.speedevand.inkride.core.domain.tracking.RouteWaypoint

object TestRoutes {
    /**
     * A short straight line running north from Warsaw (52.2297, 21.0122), one
     * point per ~11m so [RouteFollower] has real polyline segments to snap and
     * project against, plus one named waypoint partway along so a "next turn"
     * readout has something to show.
     */
    fun straightLine(): PlannedRoute =
        PlannedRoute(
            name = "Test Route",
            points =
                (0..4).map { i ->
                    RoutePoint(latitude = 52.2297 + i * 0.0001, longitude = 21.0122)
                },
            waypoints =
                listOf(
                    RouteWaypoint(latitude = 52.2297 + 2 * 0.0001, longitude = 21.0122, name = "Waypoint"),
                ),
        )
}
