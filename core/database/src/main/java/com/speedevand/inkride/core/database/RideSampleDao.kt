package com.speedevand.inkride.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RideSampleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(samples: List<RideSampleEntity>)

    @Query("SELECT * FROM ride_sample WHERE rideId = :rideId ORDER BY timestampMs ASC")
    suspend fun getForRide(rideId: Long): List<RideSampleEntity>

    @Query("DELETE FROM ride_sample WHERE rideId = :rideId")
    suspend fun deleteForRide(rideId: Long)
}
