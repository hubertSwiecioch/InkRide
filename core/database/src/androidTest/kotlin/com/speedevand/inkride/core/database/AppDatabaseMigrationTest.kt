package com.speedevand.inkride.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates MIGRATION_6_7 and MIGRATION_7_8 against the exported schema
 * snapshots. Only 6 → 7 onwards can be checked this way: `exportSchema` was
 * switched on at version 6, so no snapshot exists for versions 4 or 5.
 * Migrations 4 → 5 and 5 → 6 stay covered by the hand-built Robolectric
 * `MigrationTest` in `src/test`.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private companion object {
        const val TEST_DB = "migration-test.db"
    }

    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    @Test
    fun migrate6To7PreservesExistingSettingsAndAddsTheOnboardingFlag() {
        helper.createDatabase(TEST_DB, 6).use { db ->
            db.execSQL(
                """
                INSERT INTO user_settings (
                    id, weightKg, age, bikeWeightKg, bikeType, languageCode, units,
                    showDistance, showMovingTime, showAverageSpeed, showMaxSpeed,
                    showElevationGain, showCalories, showAltitude, showGrade,
                    showCompass, showPower, keepScreenOn
                ) VALUES (1, 82, 41, 9.5, 'ROAD', 'pl', 'METRIC', 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
                """.trimIndent(),
            )
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7)

        migrated.query("SELECT weightKg, age, languageCode, hasCompletedOnboarding FROM user_settings WHERE id = 1").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(82)
            assertThat(cursor.getInt(1)).isEqualTo(41)
            assertThat(cursor.getString(2)).isEqualTo("pl")
            // An upgrading user is grandfathered past onboarding, so the new
            // column must land as 1 for a row that already existed.
            assertThat(cursor.getInt(3)).isEqualTo(1)
        }
    }

    @Test
    fun migrate7To8CreatesTheRideSampleTableAndGrandfathersExistingRidesAsComplete() {
        helper.createDatabase(TEST_DB, 7).use { db ->
            db.execSQL(
                """
                INSERT INTO ride_history (
                    id, startTimestamp, endTimestamp, distanceKm, movingTimeSeconds, elapsedTimeSeconds,
                    averageSpeedKmh, maxSpeedKmh, elevationGainM, caloriesKcal, averagePowerWatts,
                    bikeWeightKg, bikeType
                ) VALUES (1, 1000, 2000, 12.5, 1800, 2000, 25.0, 42.0, 120.0, 400.0, 150, 10.0, 'ROAD')
                """.trimIndent(),
            )
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 8, true, MIGRATION_7_8)

        migrated.query("SELECT distanceKm, isComplete FROM ride_history WHERE id = 1").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            // The ride itself must survive untouched...
            assertThat(cursor.getDouble(0)).isEqualTo(12.5)
            // ...and be grandfathered as finished, so it keeps appearing in
            // history rather than looking like an interrupted ride to recover.
            assertThat(cursor.getInt(1)).isEqualTo(1)
        }
        // The new stream starts empty: nothing is backfilled, because the 1 Hz
        // sample stream was never recorded for a pre-existing ride.
        migrated.query("SELECT COUNT(*) FROM ride_sample").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
    }

    @Test
    fun migrate7To8CascadesSampleDeletionFromTheOwningRide() {
        helper.createDatabase(TEST_DB, 7).close()

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 8, true, MIGRATION_7_8)

        migrated.execSQL("PRAGMA foreign_keys = ON")
        migrated.execSQL(
            """
            INSERT INTO ride_history (
                id, startTimestamp, endTimestamp, distanceKm, movingTimeSeconds, elapsedTimeSeconds,
                averageSpeedKmh, maxSpeedKmh, elevationGainM, caloriesKcal, averagePowerWatts,
                bikeWeightKg, bikeType, isComplete
            ) VALUES (7, 1000, 2000, 1.0, 60, 60, 20.0, 20.0, 0.0, 10.0, 100, 10.0, 'ROAD', 0)
            """.trimIndent(),
        )
        migrated.execSQL(
            "INSERT INTO ride_sample (rideId, timestampMs, heartRateBpm) VALUES (7, 1000, 150)",
        )

        migrated.execSQL("DELETE FROM ride_history WHERE id = 7")

        // The foreign key declared by the migration is what keeps orphaned
        // samples from outliving the ride they belong to.
        migrated.query("SELECT COUNT(*) FROM ride_sample WHERE rideId = 7").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
    }

    @Test
    fun migrate6To7SeedsADefaultSettingsRowWhenNoneExisted() {
        helper.createDatabase(TEST_DB, 6).close()

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7)

        // MIGRATION_6_7's second statement is an INSERT OR IGNORE that seeds the
        // singleton settings row, so a database that never had one comes out of
        // the migration with defaults rather than empty.
        migrated.query("SELECT weightKg, age, hasCompletedOnboarding FROM user_settings WHERE id = 1").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(75)
            assertThat(cursor.getInt(1)).isEqualTo(30)
            assertThat(cursor.getInt(2)).isEqualTo(1)
        }
    }
}
