package com.speedevand.inkride.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per second of a ride, written while TRACKING. Distinct from
 * [RideTrackPointEntity], which is the GPS trace for the map and GPX export and
 * therefore requires a position: this stream keeps flowing through a GPS
 * dropout so heart rate, power and cadence are not lost in a tunnel. Every
 * measurement column is nullable because any sensor may be absent or silent.
 */
@Entity(
    tableName = "ride_sample",
    foreignKeys = [
        ForeignKey(
            entity = RideHistoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["rideId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("rideId")],
)
data class RideSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rideId: Long,
    val timestampMs: Long,
    val latitude: Double?,
    val longitude: Double?,
    val altitudeM: Double?,
    val speedKmh: Double?,
    val gradePercent: Double?,
    val powerWatts: Int?,
    val powerSource: String?,
    val heartRateBpm: Int?,
    val cadenceRpm: Int?,
)
