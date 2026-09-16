package com.speedevand.inkride.core.testing.fakes

import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.history.RideHistoryRepository
import com.speedevand.inkride.core.domain.history.RideRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Fake [RideHistoryRepository] backed by in-memory state.
 *
 * `RideLapEntity` and `RideTrackPointEntity` declare `onDelete = ForeignKey.CASCADE` on
 * `rideId`, so in production deleting a ride also deletes its laps and track points. This fake
 * holds its own independent state and cannot see those tables, so it does not cascade on its
 * own. Pass [lapRepository], [trackPointRepository] and [sampleRepository] to reproduce the cascade: [deleteById]
 * and [deleteAll] will clear the matching entries in those fakes too. Leaving any of them null means
 * no cascade for that child — a test relying on cascade behavior without wiring both will pass
 * against this fake and fail against Room.
 */
class FakeRideHistoryRepository(
    initial: List<RideRecord> = emptyList(),
    private val lapRepository: FakeRideLapRepository? = null,
    private val trackPointRepository: FakeRideTrackPointRepository? = null,
    private val sampleRepository: FakeRideSampleRepository? = null,
) : RideHistoryRepository {
    private val ridesFlow = MutableStateFlow(initial.sortedByDescending { it.startTimestamp })

    /** When non-null, overrides the stored lookup — use for the not-found path. */
    var getByIdResult: Result<RideRecord, DataError.Local>? = null
    var saveResult: Result<Long, DataError.Local>? = null
    var deleteResult: EmptyResult<DataError.Local> = Result.Success(Unit)
    var deleteAllResult: EmptyResult<DataError.Local> = Result.Success(Unit)

    /**
     * Every stored ride, finished or not — the fake's own window on its state,
     * with none of [observeAll]'s `isComplete` filtering.
     */
    val savedRides: List<RideRecord> get() = ridesFlow.value

    // Mirrors `RideHistoryDao.observeAll`'s `WHERE isComplete = 1`: a ride still
    // in progress has a row from the moment it starts, and must not surface in
    // history until it is closed.
    override fun observeAll(): Flow<List<RideRecord>> = ridesFlow.map { rides -> rides.filter { it.isComplete } }

    override suspend fun getById(id: Long): Result<RideRecord, DataError.Local> {
        getByIdResult?.let { return it }
        val ride = ridesFlow.value.firstOrNull { it.id == id }
        return if (ride != null) Result.Success(ride) else Result.Error(DataError.Local.NOT_FOUND)
    }

    /**
     * Assigns a new id as `max(id) + 1`. Every schema-7 table is
     * `INTEGER PRIMARY KEY AUTOINCREMENT`, so SQLite never reuses an id even after a delete;
     * this fake can, once the highest-id row is removed. A test that deletes a ride and then
     * asserts on a *specific* newly-assigned id may see a value Room would never produce.
     */
    override suspend fun save(ride: RideRecord): Result<Long, DataError.Local> {
        saveResult?.let { return it }
        // Derive the next id from current state, not a constructor-time counter:
        // `emitRides` replaces the stored list wholesale, and a stale counter
        // would re-assign an id a seeded ride already holds, silently
        // overwriting it instead of inserting.
        val id = if (ride.id == 0L) (ridesFlow.value.maxOfOrNull { it.id } ?: 0L) + 1L else ride.id
        ridesFlow.value =
            ridesFlow.value
                .filterNot { it.id == id }
                .plus(ride.copy(id = id))
                .sortedByDescending { it.startTimestamp }
        return Result.Success(id)
    }

    override suspend fun startRide(startedAt: Long): Result<Long, DataError.Local> =
        save(
            RideRecord(
                id = 0L,
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
        )

    override suspend fun finishRide(ride: RideRecord): EmptyResult<DataError.Local> {
        val stored =
            ridesFlow.value.firstOrNull { it.id == ride.id }
                ?: return Result.Error(DataError.Local.NOT_FOUND)
        ridesFlow.value =
            ridesFlow.value
                .map { if (it.id == stored.id) ride.copy(isComplete = true) else it }
                .sortedByDescending { it.startTimestamp }
        return Result.Success(Unit)
    }

    override suspend fun getUnfinishedRides(): Result<List<RideRecord>, DataError.Local> =
        Result.Success(ridesFlow.value.filterNot { it.isComplete }.sortedBy { it.startTimestamp })

    override suspend fun deleteById(id: Long): EmptyResult<DataError.Local> {
        if (deleteResult is Result.Success) {
            ridesFlow.value = ridesFlow.value.filterNot { it.id == id }
            lapRepository?.clearFor(id)
            trackPointRepository?.clearFor(id)
            sampleRepository?.clearFor(id)
        }
        return deleteResult
    }

    override suspend fun deleteAll(): EmptyResult<DataError.Local> {
        if (deleteAllResult is Result.Success) {
            ridesFlow.value = emptyList()
            lapRepository?.clearAll()
            trackPointRepository?.clearAll()
            sampleRepository?.clearAll()
        }
        return deleteAllResult
    }

    /** Replaces the stored rides, sorted to match `RideHistoryDao.observeAll`'s `ORDER BY startTimestamp DESC`. */
    fun emitRides(rides: List<RideRecord>) {
        ridesFlow.value = rides.sortedByDescending { it.startTimestamp }
    }
}
