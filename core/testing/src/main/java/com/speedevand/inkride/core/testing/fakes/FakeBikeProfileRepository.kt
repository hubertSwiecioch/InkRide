package com.speedevand.inkride.core.testing.fakes

import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.settings.BikeProfile
import com.speedevand.inkride.core.domain.settings.BikeProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeBikeProfileRepository(
    initial: List<BikeProfile> = emptyList(),
) : BikeProfileRepository {
    private val profilesFlow = MutableStateFlow(initial)

    private val _deletedIds = mutableListOf<Long>()
    val deletedIds: List<Long> get() = _deletedIds

    /** When non-null, `upsert` returns this instead of storing the profile. */
    var upsertResult: Result<Long, DataError.Local>? = null
    var deleteResult: EmptyResult<DataError.Local> = Result.Success(Unit)

    override fun observeProfiles(): Flow<List<BikeProfile>> = profilesFlow

    override suspend fun upsert(profile: BikeProfile): Result<Long, DataError.Local> {
        upsertResult?.let { return it }
        // Derive the next id from current state, not a constructor-time counter:
        // `emitProfiles` replaces the stored list wholesale, and a stale counter
        // would re-assign an id that a seeded profile already holds, silently
        // overwriting it instead of inserting.
        val id = if (profile.id == 0L) (profilesFlow.value.maxOfOrNull { it.id } ?: 0L) + 1L else profile.id
        val stored = profile.copy(id = id)
        profilesFlow.value =
            profilesFlow.value
                .filterNot { it.id == id }
                .plus(stored)
                .sortedBy { it.id }
        return Result.Success(id)
    }

    override suspend fun delete(id: Long): EmptyResult<DataError.Local> {
        if (deleteResult is Result.Success) {
            _deletedIds += id
            profilesFlow.value = profilesFlow.value.filterNot { it.id == id }
        }
        return deleteResult
    }

    fun emitProfiles(profiles: List<BikeProfile>) {
        profilesFlow.value = profiles
    }
}
