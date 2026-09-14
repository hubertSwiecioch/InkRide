package com.speedevand.inkride.core.testing.fakes

import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.PlannedRoute
import com.speedevand.inkride.core.domain.tracking.RoutingError
import com.speedevand.inkride.core.domain.tracking.RoutingService
import com.speedevand.inkride.core.testing.support.TestRoutes

/** The coordinates one `route()` call was made with. */
data class RouteRequest(
    val originLatitude: Double,
    val originLongitude: Double,
    val destinationLatitude: Double,
    val destinationLongitude: Double,
)

class FakeRoutingService(
    defaultRoute: PlannedRoute = TestRoutes.straightLine(),
) : RoutingService {
    var callCount: Int = 0
        private set

    /**
     * The arguments of the most recent call, so a test can assert the origin is
     * the rider's current position and the destination the selected place —
     * a call count alone would not catch the two being transposed.
     */
    var lastRequest: RouteRequest? = null
        private set

    var result: Result<PlannedRoute, RoutingError> = Result.Success(defaultRoute)

    override suspend fun route(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
    ): Result<PlannedRoute, RoutingError> {
        callCount++
        lastRequest = RouteRequest(originLatitude, originLongitude, destinationLatitude, destinationLongitude)
        return result
    }
}
