package com.speedevand.inkride.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RideTrackPointDaoTest : DatabaseTestBase() {
    private val dao: RideTrackPointDao get() = db.rideTrackPointDao()

    @Test
    fun trackPointsComeBackOrderedByTimestampRegardlessOfInsertOrder() =
        runTest {
            val rideId = db.rideHistoryDao().insert(TestEntities.ride())

            dao.insertAll(
                listOf(
                    TestEntities.trackPoint(rideId, timestampMs = TestEntities.START_MS + 2_000L),
                    TestEntities.trackPoint(rideId, timestampMs = TestEntities.START_MS),
                    TestEntities.trackPoint(rideId, timestampMs = TestEntities.START_MS + 1_000L),
                ),
            )

            assertThat(dao.getForRide(rideId).map { it.timestampMs }).containsExactly(
                TestEntities.START_MS,
                TestEntities.START_MS + 1_000L,
                TestEntities.START_MS + 2_000L,
            )
        }

    @Test
    fun nullableAltitudeAndAccuracySurviveARoundTrip() =
        runTest {
            val rideId = db.rideHistoryDao().insert(TestEntities.ride())

            dao.insertAll(
                listOf(
                    TestEntities
                        .trackPoint(rideId, timestampMs = TestEntities.START_MS)
                        .copy(altitudeM = null, accuracyM = null),
                ),
            )

            val stored = dao.getForRide(rideId).single()
            assertThat(stored.altitudeM).isEqualTo(null)
            assertThat(stored.accuracyM).isEqualTo(null)
        }

    @Test
    fun trackPointsAreScopedToTheirOwnRide() =
        runTest {
            val rideA = db.rideHistoryDao().insert(TestEntities.ride())
            val rideB =
                db.rideHistoryDao().insert(
                    TestEntities.ride(startTimestamp = TestEntities.START_MS + 1_000L),
                )
            dao.insertAll(listOf(TestEntities.trackPoint(rideA, TestEntities.START_MS)))
            dao.insertAll(
                listOf(
                    TestEntities.trackPoint(rideB, TestEntities.START_MS),
                    TestEntities.trackPoint(rideB, TestEntities.START_MS + 1_000L),
                ),
            )

            assertThat(dao.getForRide(rideA)).hasSize(1)
            assertThat(dao.getForRide(rideB)).hasSize(2)
        }

    @Test
    fun deletingARideCascadesToItsTrackPoints() =
        runTest {
            val rideId = db.rideHistoryDao().insert(TestEntities.ride())
            dao.insertAll(listOf(TestEntities.trackPoint(rideId, TestEntities.START_MS)))

            db.rideHistoryDao().deleteById(rideId)

            assertThat(dao.getForRide(rideId)).isEmpty()
        }
}
