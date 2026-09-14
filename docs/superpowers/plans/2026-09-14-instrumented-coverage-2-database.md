# Instrumented Coverage, Plan 2: Database Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cover all five DAOs and the 6 → 7 schema migration with instrumented tests running against real Android SQLite.

**Architecture:** Each test class builds an in-memory `AppDatabase` with `Room.inMemoryDatabaseBuilder`, exercises one DAO, and closes it in `@After`. Foreign-key cascades and `ORDER BY` behaviour are asserted directly, since those are exactly the behaviours Robolectric's SQLite only approximates. The migration test uses Room's `MigrationTestHelper` against the exported schema JSON.

**Tech Stack:** Room 2.8.4 with `room-testing`, JUnit4 + `AndroidJUnit4` runner, assertk, `kotlinx-coroutines-test` (`runTest`), Turbine for Flow assertions.

**Spec:** `docs/superpowers/specs/2026-09-14-full-instrumented-test-coverage-design.md`

**Depends on:** Plan 1 (`2026-09-14-instrumented-coverage-1-foundation.md`) — Task 1 only, for the `room-testing` catalog entry.

## Global Constraints

- Kotlin JVM target 11; `compileSdk = 36`, `minSdk = 26`.
- `./gradlew ktlintCheck` must pass; run `./gradlew ktlintFormat` before every commit, including on `androidTest` sources.
- Instrumented tests run on JUnit4 with `androidx.test.runner.AndroidJUnitRunner`. `:core:database`'s JVM `test` source set runs JUnit5 plus a Vintage engine — do not let the two source sets share imports.
- The existing Robolectric tests in `core/database/src/test/` stay and stay green. This plan adds coverage; it removes none.
- `AppDatabase` is at version 7, with entities `UserSettingsEntity`, `RideHistoryEntity`, `RideTrackPointEntity`, `RideLapEntity`, `BikeProfileEntity`.
- `exportSchema` was only enabled at version 6, so `core/database/schemas/com.speedevand.inkride.core.database.AppDatabase/` holds only `6.json` and `7.json`. `MigrationTestHelper` needs a snapshot of the starting version, so only 6 → 7 can be validated this way. Migrations 4 → 5 and 5 → 6 remain covered by the hand-built Robolectric `MigrationTest`.

---

### Task 1: Instrumented test scaffolding for `:core:database`

**Files:**
- Modify: `core/database/build.gradle.kts`
- Create: `core/database/src/androidTest/kotlin/com/speedevand/inkride/core/database/DatabaseTestBase.kt`
- Create: `core/database/src/androidTest/kotlin/com/speedevand/inkride/core/database/TestEntities.kt`

**Interfaces:**
- Consumes: the `androidx-room-testing` catalog entry added in Plan 1 Task 1.
- Produces:
  - `abstract class DatabaseTestBase` exposing `val db: AppDatabase`, created in `@Before` and closed in `@After`.
  - `object TestEntities` with `fun ride(id: Long = 0L, startTimestamp: Long = …, …): RideHistoryEntity`, `fun lap(rideId: Long, lapNumber: Int): RideLapEntity`, `fun trackPoint(rideId: Long, timestampMs: Long, latitude: Double = …): RideTrackPointEntity`, `fun bikeProfile(id: Long = 0L, name: String = …): BikeProfileEntity`, `fun userSettings(weightKg: Int = 75, age: Int = 30): UserSettingsEntity`.

---

- [ ] **Step 1: Add the instrumented test dependencies**

`:core:database` uses `inkride.android.library`, not `inkride.android.feature`, so it does not receive the shared `androidTest` set. Add to its `dependencies` block in `core/database/build.gradle.kts`:

```kotlin
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.assertk)
    androidTestImplementation(libs.turbine)
    androidTestImplementation(libs.kotlinx.coroutines.test)
```

Room's generated schema JSON must be visible to `MigrationTestHelper` at runtime, so also add, inside the existing `android { }` block:

```kotlin
    sourceSets {
        getByName("androidTest") {
            assets.srcDirs("$projectDir/schemas")
        }
    }
```

- [ ] **Step 2: Write the test base**

Create `core/database/src/androidTest/kotlin/com/speedevand/inkride/core/database/DatabaseTestBase.kt`:

```kotlin
package com.speedevand.inkride.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before

/**
 * An in-memory [AppDatabase] per test. In-memory rather than on-disk so tests
 * cannot leak state into each other through a file left on the device, and so
 * nothing has to be deleted between runs.
 *
 * Room turns foreign-key enforcement on for databases it opens, which is what
 * makes the cascade-delete assertions in the lap and track-point tests
 * meaningful.
 */
abstract class DatabaseTestBase {
    protected lateinit var db: AppDatabase
        private set

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .build()
    }

    @After
    fun closeDatabase() {
        db.close()
    }
}
```

- [ ] **Step 3: Write the entity builders**

Create `core/database/src/androidTest/kotlin/com/speedevand/inkride/core/database/TestEntities.kt`:

```kotlin
package com.speedevand.inkride.core.database

object TestEntities {
    /** A fixed wall-clock start so ordering assertions are reproducible. */
    const val START_MS = 1_750_000_000_000L

    fun ride(
        id: Long = 0L,
        startTimestamp: Long = START_MS,
        endTimestamp: Long = START_MS + 3_600_000L,
        distanceKm: Double = 25.0,
        movingTimeSeconds: Long = 3_000L,
        elapsedTimeSeconds: Long = 3_600L,
        averageSpeedKmh: Double = 30.0,
        maxSpeedKmh: Double = 45.5,
        elevationGainM: Double = 320.0,
        caloriesKcal: Double = 780.0,
    ): RideHistoryEntity =
        RideHistoryEntity(
            id = id,
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp,
            distanceKm = distanceKm,
            movingTimeSeconds = movingTimeSeconds,
            elapsedTimeSeconds = elapsedTimeSeconds,
            averageSpeedKmh = averageSpeedKmh,
            maxSpeedKmh = maxSpeedKmh,
            elevationGainM = elevationGainM,
            caloriesKcal = caloriesKcal,
            averagePowerWatts = 165,
            bikeWeightKg = 9.0,
            bikeType = "ROAD",
        )

    fun lap(
        rideId: Long,
        lapNumber: Int,
        distanceKm: Double = 5.0,
    ): RideLapEntity =
        RideLapEntity(
            rideId = rideId,
            lapNumber = lapNumber,
            distanceKm = distanceKm,
            movingTimeSeconds = 600L,
            averageSpeedKmh = 30.0,
            elevationGainM = 60.0,
        )

    fun trackPoint(
        rideId: Long,
        timestampMs: Long,
        latitude: Double = 52.2297,
        longitude: Double = 21.0122,
    ): RideTrackPointEntity =
        RideTrackPointEntity(
            rideId = rideId,
            timestampMs = timestampMs,
            latitude = latitude,
            longitude = longitude,
            altitudeM = 100.0,
            accuracyM = 5f,
        )

    fun bikeProfile(
        id: Long = 0L,
        name: String = "Road bike",
        weightKg: Double = 8.5,
        type: String = "ROAD",
    ): BikeProfileEntity = BikeProfileEntity(id = id, name = name, weightKg = weightKg, type = type)

    fun userSettings(
        weightKg: Int = 75,
        age: Int = 30,
    ): UserSettingsEntity =
        UserSettingsEntity(
            weightKg = weightKg,
            age = age,
            bikeWeightKg = 10.0,
            bikeType = "ROAD",
            languageCode = "en",
            units = "METRIC",
            showDistance = true,
            showMovingTime = true,
            showAverageSpeed = true,
            showMaxSpeed = true,
            showElevationGain = true,
            showCalories = true,
            showAltitude = true,
            showGrade = true,
            showCompass = true,
            showPower = true,
        )
}
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :core:database:assembleDebugAndroidTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Format, verify style, commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add core/database
git commit -m "test: add instrumented test scaffolding for :core:database"
```

---

### Task 2: `RideHistoryDao` instrumented tests

**Files:**
- Create: `core/database/src/androidTest/kotlin/com/speedevand/inkride/core/database/RideHistoryDaoTest.kt`

**Interfaces:**
- Consumes: `DatabaseTestBase`, `TestEntities` (Task 1).
- Produces: nothing other tasks depend on.

---

- [ ] **Step 1: Write the failing tests**

```kotlin
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
            val id = dao.insert(TestEntities.ride(distanceKm = 12.5))

            val stored = dao.getById(id)

            assertThat(stored?.id).isEqualTo(id)
            assertThat(stored?.distanceKm).isEqualTo(12.5)
        }

    @Test
    fun getByIdReturnsNullForAnUnknownId() =
        runTest {
            assertThat(dao.getById(id = 999L)).isNull()
        }

    @Test
    fun observeAllEmitsNewestRideFirst() =
        runTest {
            val olderId = dao.insert(TestEntities.ride(startTimestamp = TestEntities.START_MS))
            val newerId =
                dao.insert(TestEntities.ride(startTimestamp = TestEntities.START_MS + 86_400_000L))

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
                assertThat(stats.maxSpeedKmh).isCloseTo(0.0, 0.001)
                cancelAndIgnoreRemainingEvents()
            }
        }
}
```

`lifetimeStatsAreZeroOnAnEmptyTable` is the test that justifies the `COALESCE` wrappers in the DAO query — without them `SUM` over no rows returns `NULL` and Room fails to map the row.

- [ ] **Step 2: Run them**

Run (emulator running): `./gradlew :core:database:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.speedevand.inkride.core.database.RideHistoryDaoTest`
Expected: PASS, 8 tests. These exercise existing production code, so they should pass on first run; a failure here is a real defect, not a missing feature — investigate it rather than adjusting the assertion.

- [ ] **Step 3: Format, verify style, commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add core/database/src/androidTest
git commit -m "test: add instrumented RideHistoryDao tests"
```

---

### Task 3: Lap and track-point DAO tests, including cascade delete

**Files:**
- Create: `core/database/src/androidTest/kotlin/com/speedevand/inkride/core/database/RideLapDaoTest.kt`
- Create: `core/database/src/androidTest/kotlin/com/speedevand/inkride/core/database/RideTrackPointDaoTest.kt`

**Interfaces:**
- Consumes: `DatabaseTestBase`, `TestEntities` (Task 1).
- Produces: nothing other tasks depend on.

---

- [ ] **Step 1: Write `RideLapDaoTest`**

```kotlin
package com.speedevand.inkride.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.hasSize
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
```

The two cascade tests are the reason this suite exists on a device: `ride_lap` declares `onDelete = CASCADE`, and cascade enforcement depends on the SQLite build actually honouring `PRAGMA foreign_keys`.

- [ ] **Step 2: Write `RideTrackPointDaoTest`**

```kotlin
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
```

- [ ] **Step 3: Run both classes**

Run: `./gradlew :core:database:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.speedevand.inkride.core.database.RideLapDaoTest,com.speedevand.inkride.core.database.RideTrackPointDaoTest`
Expected: PASS, 9 tests.

If a cascade test fails, do not add `PRAGMA foreign_keys` to the test setup — that would hide the finding. Room enables foreign keys for databases it opens, so a failure means production deletes are leaving orphan rows behind, which is a bug to report.

- [ ] **Step 4: Format, verify style, commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add core/database/src/androidTest
git commit -m "test: add instrumented lap and track-point DAO tests with cascade coverage"
```

---

### Task 4: Bike profile and user settings DAO tests

**Files:**
- Create: `core/database/src/androidTest/kotlin/com/speedevand/inkride/core/database/BikeProfileDaoTest.kt`
- Create: `core/database/src/androidTest/kotlin/com/speedevand/inkride/core/database/UserSettingsDaoTest.kt`

**Interfaces:**
- Consumes: `DatabaseTestBase`, `TestEntities` (Task 1).
- Produces: nothing other tasks depend on.

---

- [ ] **Step 1: Write `BikeProfileDaoTest`**

The interesting behaviour here is that `upsert` is an `@Insert(onConflict = REPLACE)` doing double duty: a zero id inserts, a non-zero id replaces in place.

```kotlin
package com.speedevand.inkride.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BikeProfileDaoTest : DatabaseTestBase() {
    private val dao: BikeProfileDao get() = db.bikeProfileDao()

    @Test
    fun upsertWithZeroIdInsertsAndReturnsTheGeneratedId() =
        runTest {
            val id = dao.upsert(TestEntities.bikeProfile(name = "Road bike"))

            assertThat(id).isGreaterThan(0L)
            dao.observeAll().test {
                val stored = awaitItem().single()
                assertThat(stored.id).isEqualTo(id)
                assertThat(stored.name).isEqualTo("Road bike")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun upsertWithAnExistingIdReplacesInPlaceRatherThanAddingARow() =
        runTest {
            val id = dao.upsert(TestEntities.bikeProfile(name = "Road bike"))

            val returnedId =
                dao.upsert(TestEntities.bikeProfile(id = id, name = "Road bike v2", weightKg = 7.9))

            assertThat(returnedId).isEqualTo(id)
            dao.observeAll().test {
                val all = awaitItem()
                assertThat(all).hasSize(1)
                assertThat(all.single().name).isEqualTo("Road bike v2")
                assertThat(all.single().weightKg).isEqualTo(7.9)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun observeAllEmitsProfilesOrderedByIdAscending() =
        runTest {
            val first = dao.upsert(TestEntities.bikeProfile(name = "Road bike"))
            val second = dao.upsert(TestEntities.bikeProfile(name = "MTB", type = "MTB"))

            dao.observeAll().test {
                assertThat(awaitItem().map { it.id }).containsExactly(first, second)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun observeAllReEmitsAfterADelete() =
        runTest {
            val id = dao.upsert(TestEntities.bikeProfile())

            dao.observeAll().test {
                assertThat(awaitItem()).hasSize(1)

                dao.deleteById(id)

                assertThat(awaitItem()).hasSize(0)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun deleteByIdRemovesOnlyThatProfile() =
        runTest {
            val keptId = dao.upsert(TestEntities.bikeProfile(name = "Road bike"))
            val doomedId = dao.upsert(TestEntities.bikeProfile(name = "MTB", type = "MTB"))

            dao.deleteById(doomedId)

            dao.observeAll().test {
                assertThat(awaitItem().map { it.id }).containsExactly(keptId)
                cancelAndIgnoreRemainingEvents()
            }
        }
}
```

- [ ] **Step 2: Write `UserSettingsDaoTest`**

```kotlin
package com.speedevand.inkride.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
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
```

- [ ] **Step 3: Run both classes**

Run: `./gradlew :core:database:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.speedevand.inkride.core.database.BikeProfileDaoTest,com.speedevand.inkride.core.database.UserSettingsDaoTest`
Expected: PASS, 9 tests.

- [ ] **Step 4: Format, verify style, commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add core/database/src/androidTest
git commit -m "test: add instrumented bike profile and user settings DAO tests"
```

---

### Task 5: Instrumented 6 → 7 migration test

**Files:**
- Create: `core/database/src/androidTest/kotlin/com/speedevand/inkride/core/database/AppDatabaseMigrationTest.kt`

**Interfaces:**
- Consumes: the `androidTest` assets source dir configured in Task 1 Step 1; `MIGRATION_6_7` from `core/database/src/main/java/com/speedevand/inkride/core/database/DatabaseModule.kt`.
- Produces: nothing other tasks depend on.

---

- [ ] **Step 1: Confirm the exported schemas are where the test expects them**

Run:
```bash
ls core/database/schemas/com.speedevand.inkride.core.database.AppDatabase/
```
Expected: `6.json` and `7.json`. If the directory name differs, use the actual path in the next step's `MigrationTestHelper` construction and in Task 1's `assets.srcDirs`.

- [ ] **Step 2: Write the migration test**

Named `AppDatabaseMigrationTest` rather than `MigrationTest` so it does not collide with the existing Robolectric class of that name in the JVM source set.

```kotlin
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
 * Validates MIGRATION_6_7 against the exported schema snapshots. Only 6 → 7 can
 * be checked this way: `exportSchema` was switched on at version 6, so no
 * snapshot exists for versions 4 or 5. Migrations 4 → 5 and 5 → 6 stay covered
 * by the hand-built Robolectric `MigrationTest` in `src/test`.
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
```

The `INSERT` column list above is the complete set of version 6's `NOT NULL` `user_settings`
columns, verified against `6.json`: `id`, `weightKg`, `age`, `bikeWeightKg`, `bikeType`,
`languageCode`, `units`, the ten `show*` flags, and `keepScreenOn`. The six nullable columns
(`pairedHrmAddress`, `pairedCadenceAddress`, `maxSpeedAlertKmh`, `hrZoneMinBpm`, `hrZoneMaxBpm`,
`activeBikeProfileId`) are deliberately omitted.

**The `MigrationTestHelper` constructor shape is not settled by this plan.** Room 2.7 introduced a
driver-based API alongside the older `SupportSQLiteOpenHelper.Factory` one, and which overloads are
current versus deprecated in 2.8.4 could not be confirmed from documentation. Use whichever the
compiler accepts **without emitting a deprecation warning** — warnings count as unclean output — and
say in your report which constructor and which `createDatabase` / `runMigrationsAndValidate`
overloads you used. If the current API returns an `SQLiteConnection` rather than a
`SupportSQLiteDatabase`, the assertions become `prepare(...)`/`step()`/`getInt(...)` instead of
`query(...)`/`moveToFirst()`/`getInt(...)`; adapt them and keep what they assert identical.

- [ ] **Step 3: Run it**

Run: `./gradlew :core:database:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.speedevand.inkride.core.database.AppDatabaseMigrationTest`
Expected: PASS, 2 tests.

A failure of the form "Migration didn't properly handle …" means the migration SQL and the version-7 schema disagree — read the diff the helper prints; it names the table and column.

- [ ] **Step 4: Run the whole module's instrumented suite**

Run: `./gradlew :core:database:connectedDebugAndroidTest`
Expected: PASS, 28 tests across six classes.

- [ ] **Step 5: Confirm the Robolectric suite still passes**

Run: `./gradlew :core:database:testDebugUnitTest`
Expected: PASS — unchanged.

- [ ] **Step 6: Format, verify style, commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add core/database/src/androidTest
git commit -m "test: add instrumented 6->7 migration test"
```

---

## Done when

- `./gradlew :core:database:connectedDebugAndroidTest` is green — 28 tests.
- `./gradlew :core:database:testDebugUnitTest` is green and unchanged.
- Every DAO method in all five DAOs is exercised by at least one instrumented test.
- `./gradlew ktlintCheck` is green.
