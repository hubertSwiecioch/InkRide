package com.speedevand.inkride.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserSettingsDaoTest : DatabaseTestBase() {
    private val dao: UserSettingsDao get() = db.userSettingsDao()

    @Test
    fun observeEmitsNullBeforeAnythingIsWritten() =
        runTest {
            dao.observe().test {
                assertThat(awaitItem()).isNull()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun upsertWritesTheSingletonRowAndObserveEmitsIt() =
        runTest {
            dao.observe().test {
                assertThat(awaitItem()).isNull()

                dao.upsert(TestEntities.userSettings(weightKg = 82, age = 41))

                val stored = awaitItem()
                assertThat(stored).isNotNull()
                assertThat(stored?.weightKg).isEqualTo(82)
                assertThat(stored?.age).isEqualTo(41)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aSecondUpsertReplacesTheSingletonRatherThanAddingARow() =
        runTest {
            dao.upsert(TestEntities.userSettings(weightKg = 75))
            dao.upsert(TestEntities.userSettings(weightKg = 90))

            // `observe()` filters on `id = 1`, so it would look identical whether the
            // table holds one row or two. Count the rows directly to make the "rather
            // than adding a row" half of this test's name mean something.
            db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM user_settings").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getInt(0)).isEqualTo(1)
            }

            dao.observe().test {
                val stored = awaitItem()
                assertThat(stored?.id).isEqualTo(1)
                assertThat(stored?.weightKg).isEqualTo(90)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun nullableAlertAndPairingColumnsSurviveARoundTrip() =
        runTest {
            dao.upsert(
                TestEntities.userSettings().copy(
                    pairedHrmAddress = "AA:BB:CC:DD:EE:01",
                    pairedCadenceAddress = null,
                    maxSpeedAlertKmh = 45.0,
                    hrZoneMinBpm = null,
                    hrZoneMaxBpm = 180,
                    activeBikeProfileId = 7L,
                ),
            )

            dao.observe().test {
                val stored = awaitItem()
                assertThat(stored?.pairedHrmAddress).isEqualTo("AA:BB:CC:DD:EE:01")
                assertThat(stored?.pairedCadenceAddress).isNull()
                assertThat(stored?.maxSpeedAlertKmh).isEqualTo(45.0)
                assertThat(stored?.hrZoneMinBpm).isNull()
                assertThat(stored?.hrZoneMaxBpm).isEqualTo(180)
                assertThat(stored?.activeBikeProfileId).isEqualTo(7L)
                cancelAndIgnoreRemainingEvents()
            }
        }
}
