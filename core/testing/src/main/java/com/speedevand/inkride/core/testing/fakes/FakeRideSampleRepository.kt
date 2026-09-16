package com.speedevand.inkride.core.testing.fakes

import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.history.RideSample
import com.speedevand.inkride.core.domain.history.RideSampleRepository

/**
 * Fake [RideSampleRepository] backed by in-memory state.
 *
 * Unlike [FakeRideTrackPointRepository], this one *appends* on each
 * `saveSamples` call, matching `RoomRideSampleRepository`: the stream is
 * flushed in batches during a ride, so replacing rather than appending would
 * hide a dropped batch.
 *
 * `RideSampleEntity` declares `onDelete = ForeignKey.CASCADE` on `rideId`, so in
 * production deleting a ride also deletes its samples. This fake holds its own
 * state and cannot see that table, so pass it to [FakeRideHistoryRepository] to
 * reproduce the cascade.
 */
class FakeRideSampleRepository : RideSampleRepository {
    private val stored = mutableMapOf<Long, MutableList<RideSample>>()

    var saveResult: EmptyResult<DataError.Local> = Result.Success(Unit)
    var getResult: Result<List<RideSample>, DataError.Local>? = null

    override suspend fun saveSamples(
        rideId: Long,
        samples: List<RideSample>,
    ): EmptyResult<DataError.Local> {
        if (saveResult is Result.Success) {
            stored.getOrPut(rideId) { mutableListOf() } += samples
        }
        return saveResult
    }

    override suspend fun getSamples(rideId: Long): Result<List<RideSample>, DataError.Local> =
        getResult ?: Result.Success(stored[rideId].orEmpty().sortedBy { it.timestampMs })

    fun setSamples(
        rideId: Long,
        samples: List<RideSample>,
    ) {
        stored[rideId] = samples.toMutableList()
    }

    /** Clears the samples stored for one ride. Used by [FakeRideHistoryRepository] to model the FK cascade on delete. */
    fun clearFor(rideId: Long) {
        stored.remove(rideId)
    }

    /** Clears every ride's samples. Used by [FakeRideHistoryRepository] to model the FK cascade on delete-all. */
    fun clearAll() {
        stored.clear()
    }
}
