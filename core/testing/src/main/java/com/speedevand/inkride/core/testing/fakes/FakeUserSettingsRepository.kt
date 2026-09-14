package com.speedevand.inkride.core.testing.fakes

import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeUserSettingsRepository(
    initial: UserSettings = UserSettings(weightKg = 75, age = 30),
) : UserSettingsRepository {
    private val settingsFlow = MutableStateFlow(initial)

    var lastSaved: UserSettings? = null
        private set

    /** Set to a `Result.Error` to drive the repository-failure path. */
    var saveResult: EmptyResult<DataError.Local> = Result.Success(Unit)

    override fun observeSettings(): Flow<UserSettings> = settingsFlow

    override suspend fun save(settings: UserSettings): EmptyResult<DataError.Local> {
        lastSaved = settings
        // A failed save must not change observable state, or a test asserting
        // the error path would still see the UI update.
        if (saveResult is Result.Success) {
            settingsFlow.value = settings
        }
        return saveResult
    }

    fun emitSettings(settings: UserSettings) {
        settingsFlow.value = settings
    }
}
