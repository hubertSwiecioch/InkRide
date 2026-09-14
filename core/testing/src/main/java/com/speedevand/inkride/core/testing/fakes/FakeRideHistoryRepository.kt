package com.speedevand.inkride.core.testing.fakes

import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.history.RideHistoryRepository
import com.speedevand.inkride.core.domain.history.RideRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fake [RideHistoryRepository] backed by in-memory state.
 *
 * `RideLapEntity` and `RideTrackPointEntity` declare `onDelete = ForeignKey.CASCADE` on
 * `rideId`, so in production deleting a ride also deletes its laps and track points. This fake
 * holds its own independent state and cannot see those tables, so it does not cascade on its
 * own. Pass [lapRepository] and [trackPointRepository] to reproduce the cascade: [deleteById]
 * and [deleteAll] will clear the matching entries in those fakes too. Leaving either null means
 * no cascade for that child — a test relying on cascade behavior without wiring both will pass
 * against this fake and fail against Room.
 */
class FakeRideHistoryRepository(
    initial: List<RideRecord> = emptyList(),
    private val lapRepository: FakeRideLapRepository? = null,
    private val trackPointRepository: FakeRideTrackPointRepository? = null,
) : RideHistoryRepository {
    private val ridesFlow = MutableStateFlow(initial.sortedByDescending { it.startTimestamp })

    /** When non-null, overrides the stored lookup — use for the not-found path. */
    var getByIdResult: Result<RideRecord, DataError.Local>? = null
    var saveResult: Result<Long, DataError.Local>? = null
    var deleteResult: EmptyResult<DataError.Local> = Result.Success(Unit)
    var deleteAllResult: EmptyResult<DataError.Local> = Result.Success(Unit)

    override fun observeAll(): Flow<List<RideRecord>> = ridesFlow

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

    override suspend fun deleteById(id: Long): EmptyResult<DataError.Local> {
        if (deleteResult is Result.Success) {
            ridesFlow.value = ridesFlow.value.filterNot { it.id == id }
            lapRepository?.clearFor(id)
            trackPointRepository?.clearFor(id)
        }
        return deleteResult
    }

    override suspend fun deleteAll(): EmptyResult<DataError.Local> {
        if (deleteAllResult is Result.Success) {
            ridesFlow.value = emptyList()
            lapRepository?.clearAll()
            trackPointRepository?.clearAll()
        }
        return deleteAllResult
    }

    /** Replaces the stored rides, sorted to match `RideHistoryDao.observeAll`'s `ORDER BY startTimestamp DESC`. */
    fun emitRides(rides: List<RideRecord>) {
        ridesFlow.value = rides.sortedByDescending { it.startTimestamp }
    }
}
