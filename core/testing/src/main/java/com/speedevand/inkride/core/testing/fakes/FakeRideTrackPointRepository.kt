package com.speedevand.inkride.core.testing.fakes

import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.history.RideTrackPoint
import com.speedevand.inkride.core.domain.history.RideTrackPointRepository

class FakeRideTrackPointRepository : RideTrackPointRepository {
    private val stored = mutableMapOf<Long, List<RideTrackPoint>>()

    var saveResult: EmptyResult<DataError.Local> = Result.Success(Unit)
    var getResult: Result<List<RideTrackPoint>, DataError.Local>? = null

    override suspend fun savePoints(
        rideId: Long,
        points: List<RideTrackPoint>,
    ): EmptyResult<DataError.Local> {
        if (saveResult is Result.Success) {
            stored[rideId] = points
        }
        return saveResult
    }

    override suspend fun getPoints(rideId: Long): Result<List<RideTrackPoint>, DataError.Local> =
        getResult ?: Result.Success(stored[rideId].orEmpty())

    fun setPoints(
        rideId: Long,
        points: List<RideTrackPoint>,
    ) {
        stored[rideId] = points
    }
}
