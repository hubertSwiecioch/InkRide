package com.speedevand.inkride.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RideSampleDaoTest : DatabaseTestBase() {
    private val dao: RideSampleDao get() = db.rideSampleDao()

    @Test
    fun samplesComeBackOrderedByTimestampRegardlessOfInsertOrder() =
        runTest {
            val rideId = db.rideHistoryDao().insert(TestEntities.ride())

            dao.insertAll(
                listOf(
                    TestEntities.rideSample(rideId, timestampMs = TestEntities.START_MS + 2_000L),
                    TestEntities.rideSample(rideId, timestampMs = TestEntities.START_MS),
                    TestEntities.rideSample(rideId, timestampMs = TestEntities.START_MS + 1_000L),
                ),
            )

            assertThat(dao.getForRide(rideId).map { it.timestampMs })
                .containsExactly(
                    TestEntities.START_MS,
                    TestEntities.START_MS + 1_000L,
                    TestEntities.START_MS + 2_000L,
                )
        }

    @Test
    fun aPositionLessSampleKeepsItsNonPositionalReadings() =
        runTest {
            val rideId = db.rideHistoryDao().insert(TestEntities.ride())

            dao.insertAll(
                listOf(
                    TestEntities.rideSample(rideId, timestampMs = TestEntities.START_MS, latitude = 52.0, longitude = 21.0),
                    // The tunnel case: no fix, but the sensors kept reporting.
                    TestEntities.rideSample(rideId, timestampMs = TestEntities.START_MS + 1_000L, heartRateBpm = 161),
                ),
            )

            val stored = dao.getForRide(rideId)

            assertThat(stored[0].latitude).isEqualTo(52.0)
            assertThat(stored[1].latitude).isNull()
            assertThat(stored[1].longitude).isNull()
            assertThat(stored[1].heartRateBpm).isEqualTo(161)
            assertThat(stored[1].powerWatts).isEqualTo(210)
            assertThat(stored[1].cadenceRpm).isEqualTo(88)
        }

    @Test
    fun samplesAreScopedToTheirOwnRide() =
        runTest {
            val rideId = db.rideHistoryDao().insert(TestEntities.ride())
            val otherRideId = db.rideHistoryDao().insert(TestEntities.ride(startTimestamp = TestEntities.START_MS + 100_000L))

            dao.insertAll(listOf(TestEntities.rideSample(rideId, timestampMs = TestEntities.START_MS)))
            dao.insertAll(listOf(TestEntities.rideSample(otherRideId, timestampMs = TestEntities.START_MS + 100_000L)))

            assertThat(dao.getForRide(rideId).map { it.rideId }).containsExactly(rideId)
            assertThat(dao.getForRide(otherRideId).map { it.rideId }).containsExactly(otherRideId)
        }

    @Test
    fun deletingARideCascadesToItsSamples() =
        runTest {
            val rideId = db.rideHistoryDao().insert(TestEntities.ride())
            val survivingRideId = db.rideHistoryDao().insert(TestEntities.ride(startTimestamp = TestEntities.START_MS + 100_000L))
            dao.insertAll(listOf(TestEntities.rideSample(rideId, timestampMs = TestEntities.START_MS)))
            dao.insertAll(listOf(TestEntities.rideSample(survivingRideId, timestampMs = TestEntities.START_MS + 100_000L)))

            db.rideHistoryDao().deleteById(rideId)

            assertThat(dao.getForRide(rideId)).isEmpty()
            // Only the deleted ride's stream goes; the cascade is not a table wipe.
            assertThat(dao.getForRide(survivingRideId).map { it.rideId }).containsExactly(survivingRideId)
        }

    @Test
    fun deleteForRideRemovesOnlyThatRidesSamples() =
        runTest {
            val rideId = db.rideHistoryDao().insert(TestEntities.ride())
            val otherRideId = db.rideHistoryDao().insert(TestEntities.ride(startTimestamp = TestEntities.START_MS + 100_000L))
            dao.insertAll(listOf(TestEntities.rideSample(rideId, timestampMs = TestEntities.START_MS)))
            dao.insertAll(listOf(TestEntities.rideSample(otherRideId, timestampMs = TestEntities.START_MS + 100_000L)))

            dao.deleteForRide(rideId)

            assertThat(dao.getForRide(rideId)).isEmpty()
            assertThat(dao.getForRide(otherRideId).map { it.rideId }).containsExactly(otherRideId)
        }
}
