package com.speedevand.inkride.core.testing.fakes

import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.PlannedRoute
import com.speedevand.inkride.core.domain.tracking.RoutingError
import com.speedevand.inkride.core.domain.tracking.RoutingService
import com.speedevand.inkride.core.testing.support.TestRoutes

class FakeRoutingService(
    defaultRoute: PlannedRoute = TestRoutes.straightLine(),
) : RoutingService {
    var callCount: Int = 0
        private set

    var result: Result<PlannedRoute, RoutingError> = Result.Success(defaultRoute)

    override suspend fun route(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
    ): Result<PlannedRoute, RoutingError> {
        callCount++
        return result
    }
}
