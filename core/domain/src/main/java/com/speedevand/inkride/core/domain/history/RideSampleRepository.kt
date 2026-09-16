package com.speedevand.inkride.core.domain.history

import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result

/**
 * Persistence for the 1 Hz ride sample stream. Written in batches while a ride
 * is in progress (not only at the end), read back for post-ride analysis.
 * Samples are tied to a ride row by a cascading foreign key.
 */
interface RideSampleRepository {
    suspend fun saveSamples(
        rideId: Long,
        samples: List<RideSample>,
    ): EmptyResult<DataError.Local>

    suspend fun getSamples(rideId: Long): Result<List<RideSample>, DataError.Local>
}
