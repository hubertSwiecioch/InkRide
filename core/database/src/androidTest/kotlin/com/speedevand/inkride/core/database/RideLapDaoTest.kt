package com.speedevand.inkride.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RideLapDaoTest : DatabaseTestBase() {
    private val dao: RideLapDao get() = db.rideLapDao()

    @Test
    fun lapsAreReadBackForTheirOwnRideOnly() =
        runTest {
            val rideA = db.rideHistoryDao().insert(TestEntities.ride())
            val rideB =
                db.rideHistoryDao().insert(
                    TestEntities.ride(startTimestamp = TestEntities.START_MS + 1_000L),
                )
            dao.insertAll(listOf(TestEntities.lap(rideA, 1), TestEntities.lap(rideA, 2)))
            dao.insertAll(listOf(TestEntities.lap(rideB, 1)))

            assertThat(dao.getForRide(rideA)).hasSize(2)
            assertThat(dao.getForRide(rideB)).hasSize(1)
        }

    @Test
    fun lapsComeBackOrderedByLapNumberRegardlessOfInsertOrder() =
        runTest {
            val rideId = db.rideHistoryDao().insert(TestEntities.ride())

            dao.insertAll(
                listOf(
                    TestEntities.lap(rideId, lapNumber = 3),
                    TestEntities.lap(rideId, lapNumber = 1),
                    TestEntities.lap(rideId, lapNumber = 2),
                ),
            )

            assertThat(dao.getForRide(rideId).map { it.lapNumber }).containsExactly(1, 2, 3)
        }

    @Test
    fun deletingARideCascadesToItsLaps() =
        runTest {
            val rideId = db.rideHistoryDao().insert(TestEntities.ride())
            dao.insertAll(listOf(TestEntities.lap(rideId, 1), TestEntities.lap(rideId, 2)))

            db.rideHistoryDao().deleteById(rideId)

            assertThat(dao.getForRide(rideId)).isEmpty()
        }

    @Test
    fun deletingEveryRideCascadesToEveryLap() =
        runTest {
            val rideId = db.rideHistoryDao().insert(TestEntities.ride())
            dao.insertAll(listOf(TestEntities.lap(rideId, 1)))

            db.rideHistoryDao().deleteAll()

            assertThat(dao.getForRide(rideId)).isEmpty()
        }

    @Test
    fun readingLapsForARideWithNoneReturnsEmpty() =
        runTest {
            val rideId = db.rideHistoryDao().insert(TestEntities.ride())

            assertThat(dao.getForRide(rideId)).isEmpty()
        }
}
