package com.speedevand.inkride.core.testing.fakes

import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.PlaceResult
import com.speedevand.inkride.core.domain.tracking.PlaceSearchError
import com.speedevand.inkride.core.domain.tracking.PlaceSearchService

class FakePlaceSearchService : PlaceSearchService {
    private val _queries = mutableListOf<String>()

    /** Every query the ViewModel actually sent, in order — lets a test prove
     *  that a too-short query never reached the service at all. */
    val queries: List<String> get() = _queries

    var result: Result<List<PlaceResult>, PlaceSearchError> =
        Result.Success(
            listOf(PlaceResult(displayName = "Warsaw, Poland", latitude = 52.2297, longitude = 21.0122)),
        )

    override suspend fun search(query: String): Result<List<PlaceResult>, PlaceSearchError> {
        _queries += query
        return result
    }
}
