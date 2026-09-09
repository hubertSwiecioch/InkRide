package com.speedevand.inkride.core.domain.tracking

import com.speedevand.inkride.core.domain.Error
import com.speedevand.inkride.core.domain.Result

/** Computes a cycling route between two points as a [PlannedRoute]. */
interface RoutingService {
    suspend fun route(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
    ): Result<PlannedRoute, RoutingError>
}

enum class RoutingError : Error {
    /** The request failed: no connectivity, timeout, or a non-2xx response. */
    NETWORK_FAILED,

    /** No cycling route exists between the two points. */
    NO_ROUTE_FOUND,
}
