package com.speedevand.inkride.history.data

import android.database.sqlite.SQLiteFullException
import com.speedevand.inkride.core.database.RideSampleDao
import com.speedevand.inkride.core.database.RideSampleEntity
import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.history.RideSample
import com.speedevand.inkride.core.domain.history.RideSampleRepository
import com.speedevand.inkride.core.domain.tracking.PowerSource

class RoomRideSampleRepository(
    private val dao: RideSampleDao,
) : RideSampleRepository {
    override suspend fun saveSamples(
        rideId: Long,
        samples: List<RideSample>,
    ): EmptyResult<DataError.Local> =
        try {
            dao.insertAll(samples.map { it.toEntity(rideId) })
            Result.Success(Unit)
        } catch (e: SQLiteFullException) {
            Result.Error(DataError.Local.DISK_FULL)
        } catch (e: Exception) {
            Result.Error(DataError.Local.UNKNOWN)
        }

    override suspend fun getSamples(rideId: Long): Result<List<RideSample>, DataError.Local> =
        try {
            Result.Success(dao.getForRide(rideId).map { it.toDomain() })
        } catch (e: Exception) {
            Result.Error(DataError.Local.UNKNOWN)
        }
}

private fun RideSample.toEntity(rideId: Long) =
    RideSampleEntity(
        rideId = rideId,
        timestampMs = timestampMs,
        latitude = latitude,
        longitude = longitude,
        altitudeM = altitudeM,
        speedKmh = speedKmh,
        gradePercent = gradePercent,
        powerWatts = powerWatts,
        powerSource = powerSource?.name,
        heartRateBpm = heartRateBpm,
        cadenceRpm = cadenceRpm,
    )

private fun RideSampleEntity.toDomain() =
    RideSample(
        timestampMs = timestampMs,
        latitude = latitude,
        longitude = longitude,
        altitudeM = altitudeM,
        speedKmh = speedKmh,
        gradePercent = gradePercent,
        powerWatts = powerWatts,
        // An unrecognised stored value degrades to "unknown source" rather than
        // taking down the whole read of a ride.
        powerSource = powerSource?.let { runCatching { PowerSource.valueOf(it) }.getOrNull() },
        heartRateBpm = heartRateBpm,
        cadenceRpm = cadenceRpm,
    )
