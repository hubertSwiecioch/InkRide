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

            // Two points: one with both nullable columns null, one with distinct
            // non-null values. A DAO/entity change that made altitudeM/accuracyM
            // always read back null would still pass if only the null point were
            // checked, so the non-null point closes that gap.
            val withNulls =
                TestEntities
                    .trackPoint(rideId, timestampMs = TestEntities.START_MS)
                    .copy(altitudeM = null, accuracyM = null)
            val withValues =
                TestEntities
                    .trackPoint(rideId, timestampMs = TestEntities.START_MS + 1_000L)
                    .copy(altitudeM = 612.4, accuracyM = 4.2f)

            dao.insertAll(listOf(withNulls, withValues))

            val stored = dao.getForRide(rideId)
            val storedNulls = stored.single { it.timestampMs == TestEntities.START_MS }
            val storedValues = stored.single { it.timestampMs == TestEntities.START_MS + 1_000L }

            assertThat(storedNulls.altitudeM).isEqualTo(null)
            assertThat(storedNulls.accuracyM).isEqualTo(null)
            assertThat(storedValues.altitudeM).isEqualTo(612.4)
            assertThat(storedValues.accuracyM).isEqualTo(4.2f)
            // Whole-entity comparison: also catches a latitude/longitude
            // transposition, which no other test in this suite would notice.
            assertThat(storedValues).isEqualTo(withValues.copy(id = storedValues.id))
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
            // Prove the rows are there first: without this the test passes
            // unchanged against a getForRide that always returns empty.
            assertThat(dao.getForRide(rideId)).hasSize(1)

            db.rideHistoryDao().deleteById(rideId)

            assertThat(dao.getForRide(rideId)).isEmpty()
        }

    @Test
    fun deletingEveryRideCascadesToEveryTrackPoint() =
        runTest {
            val rideId = db.rideHistoryDao().insert(TestEntities.ride())
            dao.insertAll(listOf(TestEntities.trackPoint(rideId, TestEntities.START_MS)))
            assertThat(dao.getForRide(rideId)).hasSize(1)

            db.rideHistoryDao().deleteAll()

            assertThat(dao.getForRide(rideId)).isEmpty()
        }
}
