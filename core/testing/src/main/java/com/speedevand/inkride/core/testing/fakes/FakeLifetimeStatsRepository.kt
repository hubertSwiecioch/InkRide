package com.speedevand.inkride.core.testing.fakes

import com.speedevand.inkride.core.domain.history.LifetimeStats
import com.speedevand.inkride.core.domain.history.LifetimeStatsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeLifetimeStatsRepository(
    initial: LifetimeStats = LifetimeStats(),
) : LifetimeStatsRepository {
    private val statsFlow = MutableStateFlow(initial)

    override fun observeLifetimeStats(): Flow<LifetimeStats> = statsFlow

    fun emit(stats: LifetimeStats) {
        statsFlow.value = stats
    }
}
