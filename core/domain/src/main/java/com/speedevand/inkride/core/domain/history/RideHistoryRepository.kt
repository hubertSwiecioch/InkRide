package com.speedevand.inkride.core.domain.history

import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import kotlinx.coroutines.flow.Flow

interface RideHistoryRepository {
    fun observeAll(): Flow<List<RideRecord>>

    suspend fun getById(id: Long): Result<RideRecord, DataError.Local>

    /** Persists the ride and returns the generated row id on success. */
    suspend fun save(ride: RideRecord): Result<Long, DataError.Local>

    /**
     * Inserts an in-progress ride row (`isComplete = false`) and returns its id.
     * Called at ride start so samples have somewhere to go immediately and a
     * killed process leaves a recoverable row behind rather than nothing.
     */
    suspend fun startRide(startedAt: Long): Result<Long, DataError.Local>

    /** Writes the final aggregates and marks the ride complete. */
    suspend fun finishRide(ride: RideRecord): EmptyResult<DataError.Local>

    /** Rides left `isComplete = false` by a previous process. */
    suspend fun getUnfinishedRides(): Result<List<RideRecord>, DataError.Local>

    suspend fun deleteById(id: Long): EmptyResult<DataError.Local>

    suspend fun deleteAll(): EmptyResult<DataError.Local>
}
