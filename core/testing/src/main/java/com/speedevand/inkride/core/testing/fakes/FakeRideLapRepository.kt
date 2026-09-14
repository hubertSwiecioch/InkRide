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

    /**
     * Differs from production: [RoomRideLapRepository.saveLaps][com.speedevand.inkride.history.data.RoomRideLapRepository.saveLaps]
     * inserts with `id = 0`, so Room *appends* a second call's laps to the first. This fake
     * *replaces* the stored list for `rideId`. A test asserting cumulative laps across two
     * `saveLaps` calls would pass here and fail against Room.
     */
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

    /** Clears the laps stored for one ride. Used by [FakeRideHistoryRepository] to model the FK cascade on delete. */
    fun clearFor(rideId: Long) {
        stored.remove(rideId)
    }

    /** Clears every ride's laps. Used by [FakeRideHistoryRepository] to model the FK cascade on delete-all. */
    fun clearAll() {
        stored.clear()
    }
}
