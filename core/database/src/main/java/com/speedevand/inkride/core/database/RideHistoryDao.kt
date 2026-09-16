package com.speedevand.inkride.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RideHistoryDao {
    // Only finished rides: a row for a ride still in progress exists from the
    // moment it starts, and must not show up in history until it is closed.
    @Query("SELECT * FROM ride_history WHERE isComplete = 1 ORDER BY startTimestamp DESC")
    fun observeAll(): Flow<List<RideHistoryEntity>>

    @Query("SELECT * FROM ride_history WHERE id = :id")
    suspend fun getById(id: Long): RideHistoryEntity?

    @Query(
        """
        SELECT
            COUNT(*) AS totalRides,
            COALESCE(SUM(distanceKm), 0) AS totalDistanceKm,
            COALESCE(SUM(movingTimeSeconds), 0) AS totalMovingTimeSeconds,
            COALESCE(SUM(elevationGainM), 0) AS totalElevationGainM,
            COALESCE(MAX(maxSpeedKmh), 0) AS maxSpeedKmh,
            COALESCE(SUM(caloriesKcal), 0) AS totalCaloriesKcal
        FROM ride_history
        WHERE isComplete = 1
        """,
    )
    fun observeLifetimeStats(): Flow<LifetimeStatsAggregate>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(ride: RideHistoryEntity): Long

    @Query("SELECT * FROM ride_history WHERE isComplete = 0 ORDER BY startTimestamp ASC")
    suspend fun getUnfinished(): List<RideHistoryEntity>

    @Update
    suspend fun update(ride: RideHistoryEntity)

    @Query("DELETE FROM ride_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM ride_history")
    suspend fun deleteAll()
}
