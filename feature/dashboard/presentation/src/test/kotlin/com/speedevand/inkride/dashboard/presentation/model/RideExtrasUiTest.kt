package com.speedevand.inkride.dashboard.presentation.model

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.domain.tracking.PlannedRoute
import com.speedevand.inkride.core.domain.tracking.RouteProgress
import com.speedevand.inkride.core.domain.tracking.TurnDirection
import org.junit.jupiter.api.Test

class RideExtrasUiTest {
    private val route = PlannedRoute(name = "Loop")

    @Test
    fun `routeProgressUi carries the next turn direction from progress`() {
        val progress =
            RouteProgress(
                distanceToRouteM = 0.0,
                isOffRoute = false,
                distanceToNextWaypointM = 120.0,
                nextWaypointName = "Corner",
                nextTurnDirection = TurnDirection.RIGHT,
            )

        val ui = routeProgressUi(route, progress, MeasurementUnits.METRIC)

        assertThat(ui.nextTurnDirection).isEqualTo(TurnDirection.RIGHT)
    }

    @Test
    fun `routeProgressUi has no turn direction when there is no progress`() {
        val ui = routeProgressUi(route, progress = null, MeasurementUnits.METRIC)

        assertThat(ui.nextTurnDirection).isNull()
    }
}
