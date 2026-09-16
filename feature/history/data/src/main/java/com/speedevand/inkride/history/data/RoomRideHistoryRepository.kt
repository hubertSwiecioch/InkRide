package com.speedevand.inkride.history.data

import android.database.sqlite.SQLiteFullException
import android.util.Log
import com.speedevand.inkride.core.database.RideHistoryDao
import com.speedevand.inkride.core.database.RideHistoryEntity
import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.history.RideHistoryRepository
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.domain.settings.BikeType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val TAG = "RoomRideHistoryRepository"

class RoomRideHistoryRepository(
    private val dao: RideHistoryDao,
) : RideHistoryRepository {
    override fun observeAll(): Flow<List<RideRecord>> =
        dao.observeAll().map { list ->
            list.map { it.toRideRecord() }
        }

    override suspend fun getById(id: Long): Result<RideRecord, DataError.Local> {
        val entity = dao.getById(id)
        return if (entity != null) {
            Result.Success(entity.toRideRecord())
        } else {
            Result.Error(DataError.Local.NOT_FOUND)
        }
    }

    override suspend fun save(ride: RideRecord): Result<Long, DataError.Local> =
        try {
            Result.Success(dao.insert(ride.toEntity()))
        } catch (e: SQLiteFullException) {
            Log.e(TAG, "save failed: disk full", e)
            Result.Error(DataError.Local.DISK_FULL)
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
            Result.Error(DataError.Local.UNKNOWN)
        }

    override suspend fun startRide(startedAt: Long): Result<Long, DataError.Local> =
        try {
            Result.Success(
                dao.insert(
                    // Zeroed aggregates: nothing has been ridden yet. endTimestamp
                    // mirrors the start so the row is never ordered as if it ended
                    // at the epoch should it show up in a raw query.
                    RideHistoryEntity(
                        startTimestamp = startedAt,
                        endTimestamp = startedAt,
                        distanceKm = 0.0,
                        movingTimeSeconds = 0L,
                        elapsedTimeSeconds = 0L,
                        averageSpeedKmh = 0.0,
                        maxSpeedKmh = 0.0,
                        elevationGainM = 0.0,
                        caloriesKcal = 0.0,
                        isComplete = false,
                    ),
                ),
            )
        } catch (e: SQLiteFullException) {
            Log.e(TAG, "startRide failed: disk full", e)
            Result.Error(DataError.Local.DISK_FULL)
        } catch (e: Exception) {
            Log.e(TAG, "startRide failed", e)
            Result.Error(DataError.Local.UNKNOWN)
        }

    override suspend fun finishRide(ride: RideRecord): EmptyResult<DataError.Local> =
        try {
            dao.update(ride.toEntity().copy(isComplete = true))
            Result.Success(Unit)
        } catch (e: SQLiteFullException) {
            Log.e(TAG, "finishRide failed: disk full", e)
            Result.Error(DataError.Local.DISK_FULL)
        } catch (e: Exception) {
            Log.e(TAG, "finishRide failed for id=${ride.id}", e)
            Result.Error(DataError.Local.UNKNOWN)
        }

    override suspend fun getUnfinishedRides(): Result<List<RideRecord>, DataError.Local> =
        try {
            Result.Success(dao.getUnfinished().map { it.toRideRecord() })
        } catch (e: Exception) {
            Log.e(TAG, "getUnfinishedRides failed", e)
            Result.Error(DataError.Local.UNKNOWN)
        }

    override suspend fun deleteById(id: Long): EmptyResult<DataError.Local> =
        try {
            dao.deleteById(id)
            Result.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "deleteById failed for id=$id", e)
            Result.Error(DataError.Local.UNKNOWN)
        }

    override suspend fun deleteAll(): EmptyResult<DataError.Local> =
        try {
            dao.deleteAll()
            Result.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "deleteAll failed", e)
            Result.Error(DataError.Local.UNKNOWN)
        }
}

private fun RideHistoryEntity.toRideRecord() =
    RideRecord(
        id = id,
        startTimestamp = startTimestamp,
        endTimestamp = endTimestamp,
        distanceKm = distanceKm,
        movingTimeSeconds = movingTimeSeconds,
        elapsedTimeSeconds = elapsedTimeSeconds,
        averageSpeedKmh = averageSpeedKmh,
        maxSpeedKmh = maxSpeedKmh,
        elevationGainM = elevationGainM,
        caloriesKcal = caloriesKcal,
        averagePowerWatts = averagePowerWatts,
        bikeWeightKg = bikeWeightKg,
        bikeType =
            try {
                BikeType.valueOf(bikeType)
            } catch (e: Exception) {
                BikeType.ROAD
            },
        isComplete = isComplete,
    )

private fun RideRecord.toEntity() =
    RideHistoryEntity(
        id = id,
        startTimestamp = startTimestamp,
        endTimestamp = endTimestamp,
        distanceKm = distanceKm,
        movingTimeSeconds = movingTimeSeconds,
        elapsedTimeSeconds = elapsedTimeSeconds,
        averageSpeedKmh = averageSpeedKmh,
        maxSpeedKmh = maxSpeedKmh,
        elevationGainM = elevationGainM,
        caloriesKcal = caloriesKcal,
        averagePowerWatts = averagePowerWatts,
        bikeWeightKg = bikeWeightKg,
        bikeType = bikeType.name,
        isComplete = isComplete,
    )
