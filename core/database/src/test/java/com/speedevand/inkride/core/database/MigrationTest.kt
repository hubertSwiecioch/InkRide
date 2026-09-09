package com.speedevand.inkride.core.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Historical schema JSON for versions 4/5 was never exported (exportSchema
 * was false until this change), so Room's MigrationTestHelper — which
 * requires a schema snapshot of the *starting* version — can't validate
 * MIGRATION_4_5/MIGRATION_5_6 retroactively. These tests hand-build just the
 * columns each migration's SQL reads or writes and run the Migration object
 * directly against a real (Robolectric-backed) SQLite database instead.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun openHelper(
        dbName: String,
        version: Int,
        createSql: List<String>,
    ): SupportSQLiteOpenHelper {
        val configuration =
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createSql.forEach { db.execSQL(it) }
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                ).build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration)
    }

    @Test
    fun `migration 4 to 5 adds ride_lap table and paired-address columns`() {
        val db =
            openHelper(
                dbName = "migration_4_5_test",
                version = 4,
                createSql =
                    listOf(
                        "CREATE TABLE `user_settings` (`id` INTEGER PRIMARY KEY NOT NULL, `weightKg` INTEGER NOT NULL)",
                        "INSERT INTO `user_settings` (`id`, `weightKg`) VALUES (1, 75)",
                    ),
            ).writableDatabase

        MIGRATION_4_5.migrate(db)

        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='ride_lap'").use { cursor ->
            assertThat(cursor.count).isEqualTo(1)
        }
        db.query("SELECT weightKg, pairedHrmAddress, pairedCadenceAddress FROM user_settings WHERE id = 1").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(75)
            assertThat(cursor.isNull(1)).isTrue()
            assertThat(cursor.isNull(2)).isTrue()
        }
        db.close()
    }

    @Test
    fun `migration 5 to 6 seeds a default bike profile from the flat settings columns`() {
        val db =
            openHelper(
                dbName = "migration_5_6_test",
                version = 5,
                createSql =
                    listOf(
                        "CREATE TABLE `user_settings` (`id` INTEGER PRIMARY KEY NOT NULL, `bikeWeightKg` REAL NOT NULL, `bikeType` TEXT NOT NULL)",
                        "INSERT INTO `user_settings` (`id`, `bikeWeightKg`, `bikeType`) VALUES (1, 12.5, 'GRAVEL')",
                    ),
            ).writableDatabase

        MIGRATION_5_6.migrate(db)

        var seededProfileId = -1L
        db.query("SELECT id, name, weightKg, type FROM bike_profile").use { cursor ->
            assertThat(cursor.count).isEqualTo(1)
            assertThat(cursor.moveToFirst()).isTrue()
            seededProfileId = cursor.getLong(0)
            assertThat(cursor.getString(1)).isEqualTo("Default")
            assertThat(cursor.getDouble(2)).isEqualTo(12.5)
            assertThat(cursor.getString(3)).isEqualTo("GRAVEL")
        }
        db.query("SELECT activeBikeProfileId FROM user_settings WHERE id = 1").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(seededProfileId)
        }
        db.close()
    }

    // MIGRATION_6_7's seed-insert (for the zero-row grandfathering case) names
    // every NOT NULL column of `user_settings`, so unlike the other tests in
    // this file, these two need the full v6 column set rather than a
    // stripped-down couple of columns -- a SQLite INSERT naming a column the
    // table doesn't have fails outright, not just the assertions.
    private val userSettingsV6CreateSql =
        "CREATE TABLE `user_settings` (" +
            "`id` INTEGER PRIMARY KEY NOT NULL, `weightKg` INTEGER NOT NULL, `age` INTEGER NOT NULL, " +
            "`bikeWeightKg` REAL NOT NULL, `bikeType` TEXT NOT NULL, `languageCode` TEXT NOT NULL, " +
            "`units` TEXT NOT NULL, `showDistance` INTEGER NOT NULL, `showMovingTime` INTEGER NOT NULL, " +
            "`showAverageSpeed` INTEGER NOT NULL, `showMaxSpeed` INTEGER NOT NULL, " +
            "`showElevationGain` INTEGER NOT NULL, `showCalories` INTEGER NOT NULL, " +
            "`showAltitude` INTEGER NOT NULL, `showGrade` INTEGER NOT NULL, `showCompass` INTEGER NOT NULL, " +
            "`showPower` INTEGER NOT NULL, `keepScreenOn` INTEGER NOT NULL)"

    @Test
    fun `migration 6 to 7 adds hasCompletedOnboarding column defaulting existing rows to true`() {
        val db =
            openHelper(
                dbName = "migration_6_7_test",
                version = 6,
                createSql =
                    listOf(
                        userSettingsV6CreateSql,
                        "INSERT INTO `user_settings` (`id`, `weightKg`, `age`, `bikeWeightKg`, `bikeType`, " +
                            "`languageCode`, `units`, `showDistance`, `showMovingTime`, `showAverageSpeed`, " +
                            "`showMaxSpeed`, `showElevationGain`, `showCalories`, `showAltitude`, `showGrade`, " +
                            "`showCompass`, `showPower`, `keepScreenOn`) VALUES " +
                            "(1, 75, 30, 10.0, 'ROAD', 'en', 'METRIC', 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)",
                    ),
            ).writableDatabase

        MIGRATION_6_7.migrate(db)

        db.query("SELECT hasCompletedOnboarding FROM user_settings WHERE id = 1").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(1)
        }
        db.close()
    }

    @Test
    fun `migration 6 to 7 grandfathers upgrading users with zero prior settings rows`() {
        val db =
            openHelper(
                dbName = "migration_6_7_no_row_test",
                version = 6,
                createSql = listOf(userSettingsV6CreateSql),
            ).writableDatabase

        MIGRATION_6_7.migrate(db)

        db.query("SELECT hasCompletedOnboarding FROM user_settings WHERE id = 1").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(1)
        }
        db.close()
    }
}
