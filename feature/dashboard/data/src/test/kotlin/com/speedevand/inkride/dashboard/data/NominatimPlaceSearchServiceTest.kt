package com.speedevand.inkride.dashboard.data

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.PlaceSearchError
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

class NominatimPlaceSearchServiceTest {
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
            // Mirrors NominatimPlaceSearchService's own default client config, so
            // this test's fixtures behave the same as the real one under a real
            // (much larger) Nominatim response.
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

    @Test
    fun `search maps a successful response into PlaceResult`() =
        runTest {
            val json = """[{"lat":"52.2297","lon":"21.0122","display_name":"Warsaw, Poland"}]"""
            val service = NominatimPlaceSearchService("InkRide-test", clientReturning(HttpStatusCode.OK, json))

            val result = service.search("Warsaw")

            assertThat(result).isEqualTo(
                Result.Success(
                    listOf(
                        com.speedevand.inkride.core.domain.tracking
                            .PlaceResult("Warsaw, Poland", 52.2297, 21.0122),
                    ),
                ),
            )
        }

    @Test
    fun `search returns NO_RESULTS for an empty match list`() =
        runTest {
            val service = NominatimPlaceSearchService("InkRide-test", clientReturning(HttpStatusCode.OK, "[]"))

            val result = service.search("zzzzzznotaplace")

            assertThat(result).isEqualTo(Result.Error(PlaceSearchError.NO_RESULTS))
        }

    @Test
    fun `search returns NETWORK_FAILED on a server error`() =
        runTest {
            val service = NominatimPlaceSearchService("InkRide-test", clientReturning(HttpStatusCode.InternalServerError, ""))

            val result = service.search("Warsaw")

            assertThat(result).isEqualTo(Result.Error(PlaceSearchError.NETWORK_FAILED))
        }
}
