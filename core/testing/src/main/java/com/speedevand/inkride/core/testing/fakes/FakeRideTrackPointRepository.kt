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

    /**
     * Differs from production: `RoomRideTrackPointRepository.savePoints` inserts with `id = 0`,
     * so Room *appends* a second call's points to the first. This fake *replaces* the stored
     * list for `rideId`. A test asserting cumulative points across two `savePoints` calls would
     * pass here and fail against Room.
     */
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

    /** Clears the points stored for one ride. Used by [FakeRideHistoryRepository] to model the FK cascade on delete. */
    fun clearFor(rideId: Long) {
        stored.remove(rideId)
    }

    /** Clears every ride's points. Used by [FakeRideHistoryRepository] to model the FK cascade on delete-all. */
    fun clearAll() {
        stored.clear()
    }
}
