package com.speedevand.inkride.core.testing.fakes

import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.history.RideHistoryRepository
import com.speedevand.inkride.core.domain.history.RideRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeRideHistoryRepository(
    initial: List<RideRecord> = emptyList(),
) : RideHistoryRepository {
    private val ridesFlow = MutableStateFlow(initial)

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
        }
        return deleteResult
    }

    override suspend fun deleteAll(): EmptyResult<DataError.Local> {
        if (deleteAllResult is Result.Success) {
            ridesFlow.value = emptyList()
        }
        return deleteAllResult
    }

    fun emitRides(rides: List<RideRecord>) {
        ridesFlow.value = rides
    }
}
