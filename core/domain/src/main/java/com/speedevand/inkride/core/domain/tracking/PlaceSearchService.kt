package com.speedevand.inkride.core.domain.tracking

import com.speedevand.inkride.core.domain.Error
import com.speedevand.inkride.core.domain.Result

/** Finds candidate places matching a free-text query (a place name or address). */
interface PlaceSearchService {
    suspend fun search(query: String): Result<List<PlaceResult>, PlaceSearchError>
}

/** A single geocoded search match. */
data class PlaceResult(
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
)

enum class PlaceSearchError : Error {
    /** The request failed: no connectivity, timeout, or a non-2xx response. */
    NETWORK_FAILED,

    /** The query matched no places. */
    NO_RESULTS,
}
