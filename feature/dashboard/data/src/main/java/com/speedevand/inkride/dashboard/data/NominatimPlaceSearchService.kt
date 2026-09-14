package com.speedevand.inkride.dashboard.data

import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.PlaceResult
import com.speedevand.inkride.core.domain.tracking.PlaceSearchError
import com.speedevand.inkride.core.domain.tracking.PlaceSearchService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Geocodes a free-text query via the OSM Foundation's public Nominatim search
 * API (nominatim.openstreetmap.org), keyless. Its usage policy requires a
 * non-default User-Agent identifying the calling app, and explicitly permits
 * this shape of use: one search directly triggered by the end user typing a
 * query, not bulk/scripted lookups.
 */
class NominatimPlaceSearchService(
    private val userAgent: String,
    private val httpClient: HttpClient =
        HttpClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        },
) : PlaceSearchService {
    override suspend fun search(query: String): Result<List<PlaceResult>, PlaceSearchError> {
        val httpResponse =
            try {
                httpClient.get("https://nominatim.openstreetmap.org/search") {
                    header(HttpHeaders.UserAgent, userAgent)
                    parameter("q", query)
                    parameter("format", "json")
                    parameter("limit", "8")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Result.Error(PlaceSearchError.NETWORK_FAILED)
            }

        if (!httpResponse.status.isSuccess()) {
            return Result.Error(PlaceSearchError.NETWORK_FAILED)
        }

        val results =
            try {
                httpResponse.body<List<NominatimResult>>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Result.Error(PlaceSearchError.NETWORK_FAILED)
            }

        if (results.isEmpty()) return Result.Error(PlaceSearchError.NO_RESULTS)

        return Result.Success(
            results.mapNotNull { result ->
                val lat = result.lat.toDoubleOrNull() ?: return@mapNotNull null
                val lon = result.lon.toDoubleOrNull() ?: return@mapNotNull null
                PlaceResult(displayName = result.displayName, latitude = lat, longitude = lon)
            },
        )
    }
}

@Serializable
private data class NominatimResult(
    val lat: String,
    val lon: String,
    @SerialName("display_name") val displayName: String,
)
