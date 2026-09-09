package com.speedevand.inkride.dashboard.data

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.PlannedRoute
import com.speedevand.inkride.core.domain.tracking.RoutePoint
import com.speedevand.inkride.core.domain.tracking.RouteWaypoint
import com.speedevand.inkride.core.domain.tracking.RoutingError
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class OsrmRoutingServiceTest {
    private fun clientReturning(
        status: HttpStatusCode,
        body: String,
    ): HttpClient =
        HttpClient(
            MockEngine { _ ->
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            // Mirrors OsrmRoutingService's own default client config, so this
            // test's fixtures behave the same as the real one under a real
            // (much larger) OSRM response.
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

    // A straight-then-right-turn route: depart -> Corner (turn) -> arrive.
    // depart/arrive must NOT become waypoints; only the "turn" step should.
    private val successBody =
        """
        {
          "code": "Ok",
          "routes": [
            {
              "geometry": { "coordinates": [[21.0, 52.0], [21.01, 52.0], [21.01, 51.99]] },
              "legs": [
                {
                  "steps": [
                    { "maneuver": { "location": [21.0, 52.0], "type": "depart" }, "name": "" },
                    { "maneuver": { "location": [21.01, 52.0], "type": "turn" }, "name": "Corner" },
                    { "maneuver": { "location": [21.01, 51.99], "type": "arrive" }, "name": "" }
                  ]
                }
              ]
            }
          ]
        }
        """.trimIndent()

    @Test
    fun `route maps geometry to points and non-depart-non-arrive steps to waypoints`() =
        runTest {
            val service = OsrmRoutingService(clientReturning(HttpStatusCode.OK, successBody))

            val result = service.route(52.0, 21.0, 51.99, 21.01)

            assertThat(result).isEqualTo(
                Result.Success(
                    PlannedRoute(
                        name = null,
                        points =
                            listOf(
                                RoutePoint(52.0, 21.0),
                                RoutePoint(52.0, 21.01),
                                RoutePoint(51.99, 21.01),
                            ),
                        waypoints = listOf(RouteWaypoint(52.0, 21.01, "Corner")),
                    ),
                ),
            )
        }

    @Test
    fun `route returns NO_ROUTE_FOUND when the routes list is empty`() =
        runTest {
            val service = OsrmRoutingService(clientReturning(HttpStatusCode.OK, """{"code":"NoRoute","routes":[]}"""))

            val result = service.route(52.0, 21.0, 51.99, 21.01)

            assertThat(result).isEqualTo(Result.Error(RoutingError.NO_ROUTE_FOUND))
        }

    @Test
    fun `route returns NETWORK_FAILED on a server error`() =
        runTest {
            val service = OsrmRoutingService(clientReturning(HttpStatusCode.InternalServerError, ""))

            val result = service.route(52.0, 21.0, 51.99, 21.01)

            assertThat(result).isEqualTo(Result.Error(RoutingError.NETWORK_FAILED))
        }
}
