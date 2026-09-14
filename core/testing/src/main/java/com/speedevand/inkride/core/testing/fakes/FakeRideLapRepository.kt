package com.speedevand.inkride.core.testing.fakes

import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.history.RideLapRepository
import com.speedevand.inkride.core.domain.tracking.LapRecord

class FakeRideLapRepository : RideLapRepository {
    private val stored = mutableMapOf<Long, List<LapRecord>>()

    var saveResult: EmptyResult<DataError.Local> = Result.Success(Unit)

    /** When non-null, overrides the stored lookup for every ride id. */
    var getResult: Result<List<LapRecord>, DataError.Local>? = null

    override suspend fun saveLaps(
        rideId: Long,
        laps: List<LapRecord>,
    ): EmptyResult<DataError.Local> {
        if (saveResult is Result.Success) {
            stored[rideId] = laps
        }
        return saveResult
    }

    override suspend fun getLaps(rideId: Long): Result<List<LapRecord>, DataError.Local> =
        getResult ?: Result.Success(stored[rideId].orEmpty())

    fun setLaps(
        rideId: Long,
        laps: List<LapRecord>,
    ) {
        stored[rideId] = laps
    }
}
