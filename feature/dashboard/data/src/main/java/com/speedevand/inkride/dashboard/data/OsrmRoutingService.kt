package com.speedevand.inkride.dashboard.data

import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.PlannedRoute
import com.speedevand.inkride.core.domain.tracking.RoutePoint
import com.speedevand.inkride.core.domain.tracking.RouteWaypoint
import com.speedevand.inkride.core.domain.tracking.RoutingError
import com.speedevand.inkride.core.domain.tracking.RoutingService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Computes a bike route between two points via the public OSRM demo server
 * (router.project-osrm.org), keyless, "reasonable, non-commercial use" only,
 * no uptime SLA. The natural upgrade path if reliability becomes a problem is
 * a self-hosted OSRM instance behind this same interface.
 */
class OsrmRoutingService(
    private val httpClient: HttpClient =
        HttpClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        },
) : RoutingService {
    override suspend fun route(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
    ): Result<PlannedRoute, RoutingError> {
        val coordinates =
            String.format(
                Locale.US,
                "%.6f,%.6f;%.6f,%.6f",
                originLongitude,
                originLatitude,
                destinationLongitude,
                destinationLatitude,
            )

        val httpResponse =
            try {
                httpClient.get("https://router.project-osrm.org/route/v1/bike/$coordinates") {
                    parameter("geometries", "geojson")
                    parameter("steps", "true")
                    parameter("overview", "full")
                }
            } catch (e: Exception) {
                return Result.Error(RoutingError.NETWORK_FAILED)
            }

        if (!httpResponse.status.isSuccess()) {
            return Result.Error(RoutingError.NETWORK_FAILED)
        }

        val parsed =
            try {
                httpResponse.body<OsrmRouteResponse>()
            } catch (e: Exception) {
                return Result.Error(RoutingError.NETWORK_FAILED)
            }

        val route = parsed.routes.firstOrNull()
        if (parsed.code != "Ok" || route == null) {
            return Result.Error(RoutingError.NO_ROUTE_FOUND)
        }

        val points =
            route.geometry.coordinates.map { coordinate ->
                RoutePoint(latitude = coordinate[1], longitude = coordinate[0])
            }
        val waypoints =
            route.legs
                .flatMap { it.steps }
                .filter { it.maneuver.type != "depart" && it.maneuver.type != "arrive" }
                .map { step ->
                    RouteWaypoint(
                        latitude = step.maneuver.location[1],
                        longitude = step.maneuver.location[0],
                        name = step.name.ifBlank { null },
                    )
                }

        return Result.Success(PlannedRoute(name = null, points = points, waypoints = waypoints))
    }
}

@Serializable
private data class OsrmRouteResponse(
    val code: String,
    val routes: List<OsrmRoute> = emptyList(),
)

@Serializable
private data class OsrmRoute(
    val geometry: OsrmGeometry,
    val legs: List<OsrmLeg>,
)

@Serializable
private data class OsrmGeometry(
    val coordinates: List<List<Double>>,
)

@Serializable
private data class OsrmLeg(
    val steps: List<OsrmStep>,
)

@Serializable
private data class OsrmStep(
    val maneuver: OsrmManeuver,
    val name: String,
)

@Serializable
private data class OsrmManeuver(
    val location: List<Double>,
    val type: String,
)
