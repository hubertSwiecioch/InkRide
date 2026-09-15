package com.speedevand.inkride.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RideHistoryDaoTest : DatabaseTestBase() {
    private val dao: RideHistoryDao get() = db.rideHistoryDao()

    @Test
    fun insertReturnsGeneratedIdAndGetByIdReadsTheRowBack() =
        runTest {
            val inserted = TestEntities.ride(distanceKm = 12.5)
            val id = dao.insert(inserted)

            val stored = dao.getById(id)

            assertThat(stored?.id).isEqualTo(id)
            assertThat(stored?.distanceKm).isEqualTo(12.5)
            // Whole-entity comparison: catches a transposition among any of the
            // other columns (e.g. averageSpeedKmh/maxSpeedKmh swapped) that the
            // single-field assertion above would miss.
            assertThat(stored).isEqualTo(inserted.copy(id = id))
        }

    @Test
    fun getByIdReturnsNullForAnUnknownId() =
        runTest {
            assertThat(dao.getById(id = 999L)).isNull()
        }

    @Test
    fun observeAllEmitsNewestRideFirst() =
        runTest {
            // Insert the newer ride first, so insertion order, id order and
            // startTimestamp order all disagree. With id order and timestamp
            // order correlated, `containsExactly(newerId, olderId)` would be
            // equally satisfied by `ORDER BY id DESC`, which is not what the
            // real query (`ORDER BY startTimestamp DESC`) does.
            val newerId =
                dao.insert(TestEntities.ride(startTimestamp = TestEntities.START_MS + 86_400_000L))
            val olderId = dao.insert(TestEntities.ride(startTimestamp = TestEntities.START_MS))

            dao.observeAll().test {
                assertThat(awaitItem().map { it.id }).containsExactly(newerId, olderId)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun observeAllReEmitsAfterAnInsert() =
        runTest {
            dao.observeAll().test {
                assertThat(awaitItem()).hasSize(0)

                dao.insert(TestEntities.ride())

                assertThat(awaitItem()).hasSize(1)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun deleteByIdRemovesOnlyThatRide() =
        runTest {
            val keptId = dao.insert(TestEntities.ride(startTimestamp = TestEntities.START_MS))
            val doomedId =
                dao.insert(TestEntities.ride(startTimestamp = TestEntities.START_MS + 1_000L))

            dao.deleteById(doomedId)

            assertThat(dao.getById(doomedId)).isNull()
            assertThat(dao.getById(keptId)?.id).isEqualTo(keptId)
        }

    @Test
    fun deleteAllEmptiesTheTable() =
        runTest {
            dao.insert(TestEntities.ride())
            dao.insert(TestEntities.ride(startTimestamp = TestEntities.START_MS + 1_000L))

            dao.deleteAll()

            dao.observeAll().test {
                assertThat(awaitItem()).hasSize(0)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun lifetimeStatsSumTotalsAndTakeTheMaximumSpeed() =
        runTest {
            dao.insert(
                TestEntities.ride(
                    distanceKm = 10.0,
                    movingTimeSeconds = 1_200L,
                    elevationGainM = 100.0,
                    maxSpeedKmh = 40.0,
                    caloriesKcal = 300.0,
                ),
            )
            dao.insert(
                TestEntities.ride(
                    startTimestamp = TestEntities.START_MS + 1_000L,
                    distanceKm = 15.0,
                    movingTimeSeconds = 1_800L,
                    elevationGainM = 250.0,
                    maxSpeedKmh = 55.0,
                    caloriesKcal = 450.0,
                ),
            )

            dao.observeLifetimeStats().test {
                val stats = awaitItem()
                assertThat(stats.totalRides).isEqualTo(2)
                assertThat(stats.totalDistanceKm).isCloseTo(25.0, 0.001)
                assertThat(stats.totalMovingTimeSeconds).isEqualTo(3_000L)
                assertThat(stats.totalElevationGainM).isCloseTo(350.0, 0.001)
                assertThat(stats.maxSpeedKmh).isCloseTo(55.0, 0.001)
                assertThat(stats.totalCaloriesKcal).isCloseTo(750.0, 0.001)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun lifetimeStatsAreZeroOnAnEmptyTable() =
        runTest {
            dao.observeLifetimeStats().test {
                val stats = awaitItem()
                assertThat(stats.totalRides).isEqualTo(0)
                assertThat(stats.totalDistanceKm).isCloseTo(0.0, 0.001)
                assertThat(stats.totalMovingTimeSeconds).isEqualTo(0L)
                assertThat(stats.totalElevationGainM).isCloseTo(0.0, 0.001)
                assertThat(stats.maxSpeedKmh).isCloseTo(0.0, 0.001)
                assertThat(stats.totalCaloriesKcal).isCloseTo(0.0, 0.001)
                cancelAndIgnoreRemainingEvents()
            }
        }
}
