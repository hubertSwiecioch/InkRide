package com.speedevand.inkride.tracking

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.history.RideHistoryRepository
import com.speedevand.inkride.core.domain.history.RideRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.core.context.GlobalContext

/**
 * The process-death half of the tracking → Room → history boundary: a ride that
 * is never stopped, only killed. Everything but the GPS/BLE sources is real, so
 * what survives the kill is genuinely what reached the database.
 *
 * [RideTrackingE2ETestBase.killTrackerWithoutStopping] deliberately never calls
 * `stop()` — calling it would exercise the ordinary finish path and prove
 * nothing about recovery.
 */
class RideRecoveryE2ETest : RideTrackingE2ETestBase() {
    private val historyRepository: RideHistoryRepository
        get() = GlobalContext.get().get()

    /** Room is real and shared across test classes, so start from a known-empty table. */
    @Before
    fun clearHistoryBefore() {
        runBlocking { historyRepository.deleteAll() }
    }

    @Test
    fun aRideInterruptedByProcessDeathIsRecoveredIntoHistory() {
        startRideAndSettle()
        // Long enough to cross the base class's 5s flush interval several times,
        // so sample batches genuinely reach disk before the kill.
        feedMovingSteps(count = 16, speedKmh = 20.0)

        // The in-progress row exists but is deliberately invisible to history.
        assertThat(ridesInHistory()).isEmpty()

        killTrackerWithoutStopping()

        val recovered = restartTracker()
        recovered.recoverUnfinishedRides()

        composeTestRule.waitUntil(timeoutMillis = 10_000L) { ridesInHistory().isNotEmpty() }

        val ride = ridesInHistory().single()
        assertThat(ride.isComplete).isTrue()
        // Rebuilt from the samples that survived, not from in-memory metrics
        // that died with the process.
        assertThat(ride.distanceKm).isGreaterThan(0.0)
        assertThat(ride.movingTimeSeconds).isGreaterThan(0L)
    }

    @Test
    fun aRideKilledBeforeItWentAnywhereIsDiscardedRatherThanRecovered() {
        startRideAndSettle()
        // No samples at all: the row exists, but nothing was ever ridden.

        killTrackerWithoutStopping()

        val recovered = restartTracker()
        recovered.recoverUnfinishedRides()

        // Give recovery the same window the successful case gets, then confirm
        // it deleted the row instead of promoting an empty ride into history.
        Thread.sleep(2_000L)
        assertThat(ridesInHistory()).isEmpty()
        assertThat(
            runBlocking { historyRepository.getUnfinishedRides() }.let {
                it is com.speedevand.inkride.core.domain.Result.Success &&
                    it.data.isEmpty()
            },
        ).isTrue()
    }

    private fun ridesInHistory(): List<RideRecord> = runBlocking { historyRepository.observeAll().first() }

    private fun unfinishedRides(): List<RideRecord> =
        runBlocking {
            when (val result = historyRepository.getUnfinishedRides()) {
                is Result.Success -> result.data
                is Result.Error -> error("getUnfinishedRides failed: ${result.error}")
            }
        }

    /** Runs before the base class's teardown, so the rows go before the tracker stops. */
    @After
    fun clearHistory() {
        runBlocking { historyRepository.deleteAll() }
    }
}
