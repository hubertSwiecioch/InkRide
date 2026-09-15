# GPS Measurement Reliability & Ride Durability — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the six defects found in the GPS/metrics audit and make an in-progress ride survive process death, so no ride is ever lost and no readout ever lies.

**Architecture:** Changes stay inside the existing pipeline — `AndroidRideSensorDataSource` → `RideSampleAssembler` → `RideMetricsCalculator` → `RideTracker`. No new modules. One new Room table (`ride_sample`) plus a schema migration, and the ride row moves from being created at `stop()` to being created at `start()`.

**Tech Stack:** Kotlin, Room 7→8 migration, Koin, JUnit 5 (Jupiter) + assertk for JVM tests, AndroidJUnit4 + Room testing for instrumented tests, ktlint.

**Spec:** `docs/superpowers/specs/2026-09-15-gps-metrics-training-design.md`

## Global Constraints

- Pure-Kotlin modules (`:core:domain`) run tests via `./gradlew :core:domain:test`; Android modules run via `./gradlew <module>:testDebugUnitTest`.
- JVM tests use JUnit 5 (`org.junit.jupiter.api.Test`) with assertk assertions and backtick-quoted test names, matching every existing test in `:core:domain`.
- After every Kotlin change: `./gradlew ktlintFormat` then `./gradlew ktlintCheck`. ktlintCheck must pass before any commit.
- `minSdk` is 26, `compileSdk` 36. Any API above 26 needs a version guard.
- New fakes belong in `:core:testing`, never inside a feature module (CLAUDE.md).
- De-googled: no Firebase, no GMS, no Play Services Location. Location comes from `android.location.LocationManager` only.
- Commit after each task with the trailer lines used in this repo.

---

### Task 1: Remove the dead sample-timestamp parameters

Spec 1.1. `RideSampleAssembler.assemble()` computes `maxOf(gpsTimestampMs, pressureTimestampMs, headingTimestampMs, nowMs)`, but all three are earlier reads of `System.currentTimeMillis()`, so `nowMs` always wins. The parameters are dead and the comment promises time attribution that does not happen.

**Files:**
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideSampleAssembler.kt`
- Modify: `feature/tracking/data/src/main/java/com/speedevand/inkride/tracking/data/AndroidRideSensorDataSource.kt`
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideSampleAssemblerTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `RideSampleAssembler.assemble(rawFix: RawGpsFix?, pressureHpa: Double?, altitudeFromBarometerM: Double?, smoothedHeadingDeg: Float?, nowMs: Long): RideSensorSample` — three fewer parameters than today.

- [ ] **Step 1: Update the existing tests to the new signature**

Every call in `RideSampleAssemblerTest.kt` passes `gpsTimestampMs`, `pressureTimestampMs` and `headingTimestampMs`. Delete those three arguments from every `assemble(...)` call in the file. Then add this test, which pins the new contract:

```kotlin
@Test
fun `sample timestamp is the emit time`() {
    val assembler = RideSampleAssembler()

    val sample =
        assembler.assemble(
            rawFix = null,
            pressureHpa = 1013.25,
            altitudeFromBarometerM = 100.0,
            smoothedHeadingDeg = 42f,
            nowMs = 7_500L,
        )

    assertThat(sample.timestampMs).isEqualTo(7_500L)
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideSampleAssemblerTest"`
Expected: FAIL to compile — "too many arguments" is gone but the 5-parameter overload does not exist yet.

- [ ] **Step 3: Drop the parameters from the assembler**

In `RideSampleAssembler.assemble`, delete the `gpsTimestampMs`, `pressureTimestampMs` and `headingTimestampMs` parameters, and replace the timestamp expression:

```kotlin
    fun assemble(
        rawFix: RawGpsFix?,
        pressureHpa: Double?,
        altitudeFromBarometerM: Double?,
        smoothedHeadingDeg: Float?,
        nowMs: Long,
    ): RideSensorSample {
```

```kotlin
        return RideSensorSample(
            // Emit time. Every sensor branch stamps its reading the moment it
            // fires and emits from the same call, so a separate per-sensor
            // timestamp would always equal this value.
            timestampMs = nowMs,
```

- [ ] **Step 4: Drop the dead fields from the data source**

In `AndroidRideSensorDataSource.kt`, delete the three fields and the comment block above them:

```kotlin
    // Individual sensor timestamps for accurate time attribution.
    // When emitSample() fires, the sample timestamp reflects the most recent
    // sensor event rather than the emit call time.
    private var lastGpsTimestampMs: Long = 0L
    private var lastPressureTimestampMs: Long = 0L
    private var lastHeadingTimestampMs: Long = 0L
```

Delete the three assignments to them (`lastPressureTimestampMs = System.currentTimeMillis()` in the pressure listener, `lastHeadingTimestampMs = System.currentTimeMillis()` in the orientation listener, `lastGpsTimestampMs = System.currentTimeMillis()` in the location listener), the three arguments in the `sampleAssembler.assemble(...)` call, and the three resets in `stop()`.

Keep `lastPressureEmitTimestampMs` — it really does rate-limit emission to ~2 Hz.

- [ ] **Step 5: Run the full affected test set**

Run: `./gradlew :core:domain:test :feature:tracking:data:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Lint and commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add core/domain/src feature/tracking/data/src
git commit -m "refactor: drop dead per-sensor timestamps from sample assembly"
```

---

### Task 2: Single source of truth for "is the rider moving"

Spec 1.4. The calculator can declare movement from displacement alone while reporting a Doppler speed below the auto-pause threshold, so a very slow climb accrues distance and simultaneously drops into `AUTO_PAUSED`.

**Files:**
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideMetrics.kt`
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideMetricsCalculator.kt`
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideTracker.kt`
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideMetricsCalculatorTest.kt`
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideTrackerTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `RideMetrics.isMoving: Boolean` (default `false`); `RideTracker.evaluateAutoPause(current: TrackingStatus, isMoving: Boolean, speedKmh: Double, nowMs: Long): TrackingStatus`.

- [ ] **Step 1: Write the failing calculator test**

Add to `RideMetricsCalculatorTest.kt`:

```kotlin
@Test
fun `isMoving is true when displacement confirms movement despite a low Doppler speed`() {
    val calculator = RideMetricsCalculator()
    val settings = UserSettings(weightKg = 75, age = 30)

    // Two fixes 8 m apart over 1 s with a tight accuracy: displacement is far
    // above the accuracy-scaled threshold, so the rider is unambiguously moving
    // even though the chipset reports a near-zero Doppler speed.
    calculator.process(
        RideSensorSample(timestampMs = 0L, latitude = 52.0, longitude = 0.0, speedFromGpsMps = 0.2, accuracyM = 4.0f),
        settings,
    )
    var metrics = RideMetrics()
    repeat(4) { step ->
        metrics =
            calculator.process(
                RideSensorSample(
                    timestampMs = 1_000L * (step + 1),
                    latitude = 52.0 + 0.000072 * (step + 1),
                    longitude = 0.0,
                    speedFromGpsMps = 0.2,
                    accuracyM = 4.0f,
                ),
                settings,
            )
    }

    assertThat(metrics.isMoving).isTrue()
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideMetricsCalculatorTest"`
Expected: FAIL — "Unresolved reference: isMoving".

- [ ] **Step 3: Add the field and populate it**

In `RideMetrics.kt`, add after `gpsQuality`:

```kotlin
    // True while the calculator considers the rider to be moving. The single
    // source of truth for movement: RideTracker's auto-pause consumes this
    // instead of re-deriving movement from currentSpeedKmh, which disagreed
    // with the calculator whenever displacement confirmed movement that the
    // Doppler speed did not.
    val isMoving: Boolean = false,
```

In `RideMetricsCalculator.process()`, add `isMoving = isActuallyMoving,` to the returned `RideMetrics(...)` (the full return near the end of the method, not the `previous == null` early return).

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideMetricsCalculatorTest"`
Expected: PASS.

- [ ] **Step 5: Write the failing tracker test**

Add to `RideTrackerTest.kt`:

```kotlin
@Test
fun `auto-pause does not engage while the calculator still reports movement`() = runTest {
    // A crawl: below autoPauseSpeedKmh but with isMoving = true. Before the
    // isMoving contract this silently auto-paused mid-climb.
    val metrics = RideMetrics(currentSpeedKmh = 1.0, isMoving = true)

    val next = tracker.evaluateAutoPauseForTest(TrackingStatus.TRACKING, metrics, nowMs = 10_000L)

    assertThat(next).isEqualTo(TrackingStatus.TRACKING)
}
```

If `RideTrackerTest` has no such seam, drive it through the public surface instead: start the tracker on a `FakeRideSensorDataSource`, emit the crawl samples, advance the test clock past `autoPauseDelayMs`, and assert `tracker.state.value.status` is still `TRACKING`. Prefer the public-surface version — do not add a test-only method to production code.

- [ ] **Step 6: Run it to verify it fails**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideTrackerTest"`
Expected: FAIL — the status is `AUTO_PAUSED`.

- [ ] **Step 7: Switch auto-pause onto isMoving**

In `RideTracker.kt`, change the call site inside the sample collector:

```kotlin
                        val autoStatus =
                            evaluateAutoPause(
                                statusBefore,
                                baseMetrics.isMoving,
                                baseMetrics.currentSpeedKmh,
                                sample.timestampMs,
                            )
```

and the method:

```kotlin
    private fun evaluateAutoPause(
        current: TrackingStatus,
        isMoving: Boolean,
        speedKmh: Double,
        nowMs: Long,
    ): TrackingStatus =
        when (current) {
            TrackingStatus.TRACKING -> {
                if (!isMoving) {
                    val since = lowSpeedSinceMs ?: nowMs.also { lowSpeedSinceMs = it }
                    if (nowMs - since >= autoPauseDelayMs) {
                        lowSpeedSinceMs = null
                        TrackingStatus.AUTO_PAUSED
                    } else {
                        current
                    }
                } else {
                    lowSpeedSinceMs = null
                    current
                }
            }

            // Resume keeps its own, higher threshold: hysteresis lives here so
            // speed wobble at a stop can't flip the state back and forth.
            TrackingStatus.AUTO_PAUSED -> {
                if (isMoving && speedKmh > autoResumeSpeedKmh) TrackingStatus.TRACKING else current
            }

            else -> {
                current
            }
        }
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew :core:domain:test`
Expected: PASS.

- [ ] **Step 9: Lint and commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add core/domain/src
git commit -m "fix: drive auto-pause from the calculator's isMoving, not speed"
```

---

### Task 3: Decay the speed readout during a GPS dropout

Spec 1.2. `speedMps` starts from `lastReportedSpeedMps` and is only overwritten on samples that carry a position. When fixes stop arriving, barometer samples keep flowing at ~2 Hz and carry the last speed forever: the speedometer lies and auto-pause can never engage.

**Files:**
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideMetrics.kt`
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideMetricsCalculator.kt`
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideMetricsCalculatorTest.kt`

**Interfaces:**
- Consumes: `RideMetrics.isMoving` from Task 2.
- Produces: `RideMetrics.isSpeedStale: Boolean` (default `false`); `RideMetricsCalculator(speedValidityMs: Long = 3_000L)` constructor parameter.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `speed decays to zero and is flagged stale after the GPS fix window lapses`() {
    val calculator = RideMetricsCalculator()
    val settings = UserSettings(weightKg = 75, age = 30)

    calculator.process(
        RideSensorSample(timestampMs = 0L, latitude = 52.0, longitude = 0.0, speedFromGpsMps = 8.0, accuracyM = 4.0f),
        settings,
    )
    repeat(4) { step ->
        calculator.process(
            RideSensorSample(
                timestampMs = 1_000L * (step + 1),
                latitude = 52.0 + 0.000072 * (step + 1),
                longitude = 0.0,
                speedFromGpsMps = 8.0,
                accuracyM = 4.0f,
            ),
            settings,
        )
    }

    // Barometer-only samples: no position, so nothing refreshes the speed.
    val justInsideWindow =
        calculator.process(RideSensorSample(timestampMs = 6_500L, altitudeFromBarometerM = 100.0), settings)
    val pastWindow =
        calculator.process(RideSensorSample(timestampMs = 9_000L, altitudeFromBarometerM = 100.0), settings)

    assertThat(justInsideWindow.currentSpeedKmh).isGreaterThan(0.0)
    assertThat(justInsideWindow.isSpeedStale).isFalse()
    assertThat(pastWindow.currentSpeedKmh).isEqualTo(0.0)
    assertThat(pastWindow.isSpeedStale).isTrue()
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideMetricsCalculatorTest"`
Expected: FAIL — "Unresolved reference: isSpeedStale".

- [ ] **Step 3: Add the field**

In `RideMetrics.kt`, after `isMoving`:

```kotlin
    // True when no GPS fix has arrived recently enough to trust the speed
    // readout. The UI shows "--" rather than a frozen number: during a dropout
    // (tunnel, dense cover) the last known speed is not evidence of anything.
    val isSpeedStale: Boolean = false,
```

- [ ] **Step 4: Add the validity window to the calculator**

Add the constructor parameter after `warmupMaxDurationMs`:

```kotlin
    // How long the last GPS-derived speed stays valid on samples that carry no
    // position. Past this, the readout decays to zero instead of freezing.
    private val speedValidityMs: Long = 3_000L,
```

Add the field next to `lastReportedSpeedMps`:

```kotlin
    // Timestamp of the most recent sample that carried a position, used to age
    // out lastReportedSpeedMps.
    private var lastLocationSampleAtMs: Long? = null
```

Reset it in `reset()`, alongside `lastReportedSpeedMps = 0.0`:

```kotlin
        lastLocationSampleAtMs = null
```

- [ ] **Step 5: Apply the decay in process()**

Replace the block that currently reads:

```kotlin
        var speedMps = lastReportedSpeedMps
        var isActuallyMoving = false
```

with:

```kotlin
        // A position-less sample (barometer/heading) carries the last GPS-derived
        // speed forward so the E-Ink readout doesn't flicker to 0 between the
        // ~1 Hz fixes — but only for speedValidityMs. Past that the fix is gone,
        // not merely late, and a frozen number would both lie to the rider and
        // keep auto-pause from ever engaging.
        val isSpeedStale =
            !isLocationSample &&
                lastLocationSampleAtMs?.let { sample.timestampMs - it > speedValidityMs } == true
        if (isSpeedStale) lastReportedSpeedMps = 0.0
        var speedMps = lastReportedSpeedMps
        var isActuallyMoving = false
```

Inside `if (isLocationSample) { ... }`, record the fix time as the first statement of the block:

```kotlin
            lastLocationSampleAtMs = sample.timestampMs
```

Add `isSpeedStale = isSpeedStale,` to the returned `RideMetrics(...)`.

- [ ] **Step 6: Run it to verify it passes**

Run: `./gradlew :core:domain:test`
Expected: PASS.

- [ ] **Step 7: Lint and commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add core/domain/src
git commit -m "fix: decay speed to zero once the GPS fix window lapses"
```

---

### Task 4: Stop starving the stationary detector

Spec 1.3. `requestLocationUpdates(..., 1000L, 2.0f, ...)` throttles delivery until the device has moved 2 m, so a stationary bike can stop receiving callbacks entirely — starving the drift protection, the elevation re-anchor and auto-pause of input.

**Files:**
- Modify: `feature/tracking/data/src/main/java/com/speedevand/inkride/tracking/data/AndroidRideSensorDataSource.kt:242-248`
- Test: `feature/tracking/data/src/test/kotlin/com/speedevand/inkride/tracking/data/AndroidRideSensorDataSourceStartTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing new; behaviour change only.

- [ ] **Step 1: Write the failing test**

The existing test file already builds a Robolectric/shadow `LocationManager`. Add:

```kotlin
@Test
fun `location updates are requested with no minimum distance so stationary fixes keep arriving`() {
    dataSource.start()

    val request = shadowOf(locationManager).lastRequestedLocationRequest
    assertThat(request.minUpdateDistanceMeters).isEqualTo(0.0f)
}
```

If the shadow in use exposes the legacy triple instead, assert on it the same way the neighbouring `start succeeds and is idempotent...` test inspects the manager. The assertion must pin the minimum distance to `0f` — the exact accessor follows whatever that file already uses.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :feature:tracking:data:testDebugUnitTest`
Expected: FAIL — the requested minimum distance is `2.0`.

- [ ] **Step 3: Change the request**

```kotlin
        try {
            // 1-second interval, no minimum distance. The hardware distance gate
            // duplicated — and starved — the calculator's own, richer stationary
            // protection (5-sample counter, 2 movement confirmations, threshold
            // scaled by reported accuracy), which cannot run without samples.
            // A stationary bike must keep receiving fixes so auto-pause engages,
            // the GPS-quality readout stays live, and the elevation baseline can
            // re-anchor. 1 Hz while stopped is what a dedicated bike computer does.
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1_000L,
                0f,
                localLocationListener,
                callbackHandler.looper,
            )
        } catch (e: SecurityException) {
            return Result.Error(SensorError.Permission.LOCATION_DENIED)
        }
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :feature:tracking:data:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Lint and commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add feature/tracking/data/src
git commit -m "fix: request GPS updates with no minimum distance"
```

---

### Task 5: Three-tier bearing cascade

Spec 1.5. `PositionKalmanFilter` computes a velocity-derived bearing and throws it away, and the calculator's fallback reaches back exactly one sample, so two bearing-less samples blank the compass.

**Files:**
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideSampleAssembler.kt`
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideMetricsCalculator.kt`
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideSampleAssemblerTest.kt`

**Interfaces:**
- Consumes: `FilteredPosition.bearingDegrees: Float?` and `FilteredPosition.speedMps: Double` (both already exist).
- Produces: `RideSampleAssembler(gpsBearingMinSpeedMps: Float = 2.0f, kalmanBearingMinSpeedMps: Double = 0.5, positionKalmanFilter: PositionKalmanFilter = PositionKalmanFilter())`; `RideMetricsCalculator(bearingValidityMs: Long = 5_000L)`.

- [ ] **Step 1: Write the failing assembler test**

```kotlin
@Test
fun `bearing falls back to the Kalman velocity heading when GPS course is untrustworthy`() {
    val assembler = RideSampleAssembler()

    // Seed the filter with two northbound fixes so it builds a velocity estimate.
    assembler.assemble(
        rawFix = RawGpsFix(52.0, 0.0, accuracyM = 4f, fixTimeMs = 1_000L, speedMps = 1.0f),
        pressureHpa = null,
        altitudeFromBarometerM = null,
        smoothedHeadingDeg = null,
        nowMs = 1_000L,
    )
    val sample =
        assembler.assemble(
            // speedMps below gpsBearingMinSpeedMps, so GPS course-over-ground is
            // rejected; no compass heading either. The Kalman velocity bearing
            // is the only source left and must be used.
            rawFix = RawGpsFix(52.00018, 0.0, accuracyM = 4f, fixTimeMs = 2_000L, speedMps = 1.0f, bearingDeg = 270f),
            pressureHpa = null,
            altitudeFromBarometerM = null,
            smoothedHeadingDeg = null,
            nowMs = 2_000L,
        )

    assertThat(sample.bearingDegrees).isNotNull()
    // Travelling due north: bearing near 0/360, definitely not the rejected 270.
    assertThat(sample.bearingDegrees!!).isLessThan(45f)
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideSampleAssemblerTest"`
Expected: FAIL — `bearingDegrees` is null, because the assembler only reads GPS course and the compass.

- [ ] **Step 3: Add the Kalman tier to the assembler**

Add the constructor parameter:

```kotlin
class RideSampleAssembler(
    private val gpsBearingMinSpeedMps: Float = 2.0f,
    // Below this the filter's velocity estimate is mostly noise and its bearing
    // would spin; above it, it is a better heading than a magnetometer sitting
    // inside a steel bike frame.
    private val kalmanBearingMinSpeedMps: Double = 0.5,
    private val positionKalmanFilter: PositionKalmanFilter = PositionKalmanFilter(),
) {
```

Replace the bearing block in `assemble`:

```kotlin
        val gpsBearing =
            rawFix
                ?.takeIf { it.speedMps != null && it.speedMps >= gpsBearingMinSpeedMps }
                ?.bearingDeg
        val kalmanBearing =
            filteredPosition
                ?.takeIf { it.speedMps > kalmanBearingMinSpeedMps }
                ?.bearingDegrees
        val bearing =
            (gpsBearing ?: kalmanBearing ?: smoothedHeadingDeg)
                ?.takeIf { it.isFinite() }
                ?.let { ((it % 360f) + 360f) % 360f }
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideSampleAssemblerTest"`
Expected: PASS.

- [ ] **Step 5: Write the failing calculator test**

```kotlin
@Test
fun `bearing survives more than one consecutive sample without a heading`() {
    val calculator = RideMetricsCalculator()
    val settings = UserSettings(weightKg = 75, age = 30)

    calculator.process(
        RideSensorSample(timestampMs = 0L, latitude = 52.0, longitude = 0.0, bearingDegrees = 90f, accuracyM = 4.0f),
        settings,
    )

    calculator.process(RideSensorSample(timestampMs = 500L, altitudeFromBarometerM = 100.0), settings)
    val second = calculator.process(RideSensorSample(timestampMs = 1_000L, altitudeFromBarometerM = 100.0), settings)
    val pastWindow = calculator.process(RideSensorSample(timestampMs = 9_000L, altitudeFromBarometerM = 100.0), settings)

    assertThat(second.bearingDegrees).isEqualTo(90f)
    assertThat(pastWindow.bearingDegrees).isNull()
}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideMetricsCalculatorTest"`
Expected: FAIL — `second.bearingDegrees` is null; the one-sample fallback has already run out.

- [ ] **Step 7: Hold the last bearing with a validity window**

Add the constructor parameter after `speedValidityMs`:

```kotlin
    // How long the last known bearing stays on screen without a fresh reading.
    // Longer than speedValidityMs: a stale compass needle is far less misleading
    // than a stale speed, and blanking it on every brief gap makes it unusable.
    private val bearingValidityMs: Long = 5_000L,
```

Add the fields next to `lastLocationSampleAtMs`:

```kotlin
    private var lastKnownBearingDeg: Float? = null
    private var lastKnownBearingAtMs: Long? = null
```

Reset both in `reset()`:

```kotlin
        lastKnownBearingDeg = null
        lastKnownBearingAtMs = null
```

Just before the final `return RideMetrics(...)`, resolve the bearing:

```kotlin
        sample.bearingDegrees?.let {
            lastKnownBearingDeg = it
            lastKnownBearingAtMs = sample.timestampMs
        }
        val resolvedBearing =
            lastKnownBearingAtMs
                ?.takeIf { sample.timestampMs - it <= bearingValidityMs }
                ?.let { lastKnownBearingDeg }
```

and replace `bearingDegrees = sample.bearingDegrees ?: previous.bearingDegrees,` with:

```kotlin
            bearingDegrees = resolvedBearing,
```

- [ ] **Step 8: Run the whole module's tests**

Run: `./gradlew :core:domain:test`
Expected: PASS.

- [ ] **Step 9: Lint and commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add core/domain/src
git commit -m "fix: three-tier bearing cascade with a validity window"
```

---

### Task 6: Replace the deprecated single-fix request

Spec 1.6. `requestSingleUpdate` is deprecated as of API 30.

**Files:**
- Modify: `feature/dashboard/data/src/main/java/com/speedevand/inkride/dashboard/data/AndroidCurrentLocationProvider.kt:87-116`
- Test: `feature/dashboard/data/src/test/kotlin/com/speedevand/inkride/dashboard/data/AndroidCurrentLocationProviderTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing new; `getCurrentLocation()` keeps its signature.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `on API 30 and above a fresh fix is requested through getCurrentLocation`() = runTest {
    // No cached fix, no active ride: the provider must fall through to a fresh request.
    val result = provider.getCurrentLocation()

    assertThat(shadowOf(locationManager).lastRequestedSingleUpdateProvider).isNull()
    assertThat(result).isInstanceOf(Result.Success::class)
}
```

Follow whatever shadow/fake the existing tests in this file already install; the assertion that matters is that the deprecated path is not taken on API 30+.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :feature:dashboard:data:testDebugUnitTest`
Expected: FAIL — the provider still calls `requestSingleUpdate`.

- [ ] **Step 3: Branch on the API level**

Replace `requestFreshFix()` with:

```kotlin
    @SuppressLint("MissingPermission")
    private suspend fun requestFreshFix(): Result<LocationFix, LocationError> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestFreshFixModern()
        } else {
            requestFreshFixLegacy()
        }

    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("MissingPermission")
    private suspend fun requestFreshFixModern(): Result<LocationFix, LocationError> =
        suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            continuation.invokeOnCancellation { cancellationSignal.cancel() }
            locationManager.getCurrentLocation(
                LocationManager.GPS_PROVIDER,
                cancellationSignal,
                ContextCompat.getMainExecutor(context),
            ) { location ->
                if (!continuation.isActive) return@getCurrentLocation
                val result =
                    if (location == null) {
                        // The platform resolves the consumer with null on timeout
                        // or when the provider can't produce a fix.
                        Result.Error(LocationError.TIMED_OUT)
                    } else {
                        Result.Success(LocationFix(location.latitude, location.longitude))
                    }
                continuation.resumeWith(kotlin.Result.success(result))
            }
        }
```

Rename the existing body to `requestFreshFixLegacy()` and leave it otherwise untouched — it still serves API 26–29.

Add the imports: `android.os.Build`, `android.os.CancellationSignal`, `androidx.annotation.RequiresApi`.

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :feature:dashboard:data:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Lint and commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add feature/dashboard/data/src
git commit -m "fix: use getCurrentLocation on API 30+ for the single-fix lookup"
```

---

### Task 7: Lock the dropout and stop behaviour into the ride simulation

Spec 8.2. `RideSimulationBuilder` already supports `gpsDropout` and `SimTerrain.STOP`, so these are new cases, not new machinery.

**Files:**
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideMetricsCalculatorFullRideSimulationTest.kt`

**Interfaces:**
- Consumes: `RideMetrics.isMoving` (Task 2), `RideMetrics.isSpeedStale` (Task 3), `SimPhase(gpsDropout = true)`, `SimTerrain.STOP`.
- Produces: nothing.

- [ ] **Step 1: Write the tunnel test**

```kotlin
@Test
fun `a GPS dropout freezes distance during the gap and credits the straight line on return`() {
    val calculator = RideMetricsCalculator()
    val settings = UserSettings(weightKg = 75, age = 30)
    val ride =
        RideSimulationBuilder.build(
            listOf(
                SimPhase("approach", SimTerrain.FLAT, speedKmh = 25.0, durationMs = 60_000L),
                SimPhase("tunnel", SimTerrain.FLAT, speedKmh = 25.0, durationMs = 120_000L, gpsDropout = true),
                SimPhase("exit", SimTerrain.FLAT, speedKmh = 25.0, durationMs = 60_000L),
            ),
        )

    val tunnel = ride.phases.first { it.name == "tunnel" }
    var distanceAtTunnelStartKm = 0.0
    var duringTunnel: RideMetrics? = null
    var last = RideMetrics()

    ride.samples.forEachIndexed { index, sample ->
        last = calculator.process(sample, settings)
        if (index == tunnel.startSampleIndex) distanceAtTunnelStartKm = last.distanceKm
        // Well past speedValidityMs into the gap.
        if (index == tunnel.endSampleIndex) duringTunnel = last
    }

    // Inside the gap: speed blanked, movement withdrawn, distance parked.
    assertThat(duringTunnel!!.isSpeedStale).isTrue()
    assertThat(duringTunnel!!.currentSpeedKmh).isEqualTo(0.0)
    assertThat(duringTunnel!!.isMoving).isFalse()
    assertThat(duringTunnel!!.distanceKm).isCloseTo(distanceAtTunnelStartKm, 0.001)

    // On return the straight-line displacement across the gap IS credited. This is
    // deliberate: it is the best estimate across the gap, and moving time is
    // credited to match so the moving average is not inflated.
    assertThat(last.distanceKm).isGreaterThan(duringTunnel!!.distanceKm)
}
```

- [ ] **Step 2: Write the traffic-light test**

```kotlin
@Test
fun `a long stop adds no distance and fabricates no elevation`() {
    val calculator = RideMetricsCalculator()
    val settings = UserSettings(weightKg = 75, age = 30)
    val ride =
        RideSimulationBuilder.build(
            listOf(
                SimPhase("roll up", SimTerrain.FLAT, speedKmh = 20.0, durationMs = 60_000L),
                SimPhase("red light", SimTerrain.STOP, durationMs = 90_000L),
                SimPhase("pull away", SimTerrain.FLAT, speedKmh = 20.0, durationMs = 60_000L),
            ),
        )

    val stop = ride.phases.first { it.name == "red light" }
    var beforeStop = RideMetrics()
    var endOfStop = RideMetrics()

    ride.samples.forEachIndexed { index, sample ->
        val metrics = calculator.process(sample, settings)
        if (index == stop.startSampleIndex - 1) beforeStop = metrics
        if (index == stop.endSampleIndex) endOfStop = metrics
    }

    assertThat(endOfStop.distanceKm).isCloseTo(beforeStop.distanceKm, 0.005)
    assertThat(endOfStop.elevationGainM).isCloseTo(beforeStop.elevationGainM, 0.5)
    assertThat(endOfStop.isMoving).isFalse()
}
```

- [ ] **Step 3: Run both tests**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideMetricsCalculatorFullRideSimulationTest"`
Expected: PASS. If the tunnel case fails on `isMoving`, confirm Task 3 landed — a stale sample must withdraw movement, not just zero the speed.

- [ ] **Step 4: Lint and commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add core/domain/src/test
git commit -m "test: cover GPS dropout and long-stop behaviour in the ride simulation"
```

---

### Task 8: `ride_sample` table and migration 7 → 8

Spec 5.1 and 5.3. The 1 Hz stream that training metrics and post-ride analysis need has nowhere to live: `ride_track_point` requires a position, so it would drop heart rate for the whole of a tunnel.

**Files:**
- Create: `core/database/src/main/java/com/speedevand/inkride/core/database/RideSampleEntity.kt`
- Create: `core/database/src/main/java/com/speedevand/inkride/core/database/RideSampleDao.kt`
- Create: `core/domain/src/main/java/com/speedevand/inkride/core/domain/history/RideSample.kt`
- Create: `core/domain/src/main/java/com/speedevand/inkride/core/domain/history/RideSampleRepository.kt`
- Create: `feature/history/data/src/main/java/com/speedevand/inkride/history/data/RoomRideSampleRepository.kt`
- Modify: `core/database/src/main/java/com/speedevand/inkride/core/database/AppDatabase.kt`
- Modify: `core/database/src/main/java/com/speedevand/inkride/core/database/DatabaseModule.kt`
- Modify: `feature/history/data/src/main/java/com/speedevand/inkride/history/data/HistoryDataModule.kt`
- Test: `core/database/src/androidTest/kotlin/com/speedevand/inkride/core/database/AppDatabaseMigrationTest.kt`
- Test: `core/database/src/androidTest/kotlin/com/speedevand/inkride/core/database/RideSampleDaoTest.kt`

**Interfaces:**
- Consumes: `RideHistoryEntity`.
- Produces: `RideSample` domain model; `RideSampleRepository.saveSamples(rideId: Long, samples: List<RideSample>): EmptyResult<DataError.Local>` and `.getSamples(rideId: Long): Result<List<RideSample>, DataError.Local>`; `AppDatabase.rideSampleDao()`; `MIGRATION_7_8`.

- [ ] **Step 1: Write the failing migration test**

Add to `AppDatabaseMigrationTest.kt`, following the shape of the existing 6→7 case:

```kotlin
@Test
fun migrate7To8_createsRideSampleTableAndKeepsExistingRides() {
    helper.createDatabase(TEST_DB, 7).apply {
        execSQL(
            "INSERT INTO ride_history (" +
                "id, startTimestamp, endTimestamp, distanceKm, movingTimeSeconds, elapsedTimeSeconds, " +
                "averageSpeedKmh, maxSpeedKmh, elevationGainM, caloriesKcal, averagePowerWatts, " +
                "bikeWeightKg, bikeType" +
                ") VALUES (1, 1000, 2000, 12.5, 1800, 2000, 25.0, 42.0, 120.0, 400.0, 150, 10.0, 'ROAD')",
        )
        close()
    }

    val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, MIGRATION_7_8)

    db.query("SELECT isComplete, trainingStressScore FROM ride_history WHERE id = 1").use { cursor ->
        assertThat(cursor.moveToFirst()).isTrue()
        // Existing rides are grandfathered as finished; training columns stay null.
        assertThat(cursor.getInt(0)).isEqualTo(1)
        assertThat(cursor.isNull(1)).isTrue()
    }
    db.query("SELECT COUNT(*) FROM ride_sample").use { cursor ->
        assertThat(cursor.moveToFirst()).isTrue()
        assertThat(cursor.getInt(0)).isEqualTo(0)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core:database:connectedDebugAndroidTest --tests "*AppDatabaseMigrationTest*"`
Expected: FAIL — `MIGRATION_7_8` does not exist.

- [ ] **Step 3: Add the entity**

```kotlin
package com.speedevand.inkride.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per second of a ride, written while TRACKING. Distinct from
 * [RideTrackPointEntity], which is the GPS trace for the map and GPX export and
 * therefore requires a position: this stream keeps flowing through a GPS
 * dropout so heart rate, power and cadence are not lost in a tunnel. Every
 * measurement column is nullable because any sensor may be absent or silent.
 */
@Entity(
    tableName = "ride_sample",
    foreignKeys = [
        ForeignKey(
            entity = RideHistoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["rideId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("rideId")],
)
data class RideSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rideId: Long,
    val timestampMs: Long,
    val latitude: Double?,
    val longitude: Double?,
    val altitudeM: Double?,
    val speedKmh: Double?,
    val gradePercent: Double?,
    val powerWatts: Int?,
    val powerSource: String?,
    val heartRateBpm: Int?,
    val cadenceRpm: Int?,
)
```

- [ ] **Step 4: Add the DAO**

```kotlin
package com.speedevand.inkride.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RideSampleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(samples: List<RideSampleEntity>)

    @Query("SELECT * FROM ride_sample WHERE rideId = :rideId ORDER BY timestampMs ASC")
    suspend fun getForRide(rideId: Long): List<RideSampleEntity>

    @Query("DELETE FROM ride_sample WHERE rideId = :rideId")
    suspend fun deleteForRide(rideId: Long)
}
```

- [ ] **Step 5: Register the entity and DAO**

In `AppDatabase.kt`, add `RideSampleEntity::class,` to `entities`, bump `version = 7` to `version = 8`, and add:

```kotlin
    abstract fun rideSampleDao(): RideSampleDao
```

- [ ] **Step 6: Write the migration**

In `DatabaseModule.kt`, after `MIGRATION_6_7`:

```kotlin
/**
 * Adds the 1 Hz `ride_sample` stream plus the per-ride training aggregates that
 * summarise it, and the `isComplete` flag that lets an interrupted ride be
 * recovered on next launch.
 *
 * `isComplete` defaults to 1 so every pre-existing ride is grandfathered as
 * finished — the same treatment `hasCompletedOnboarding` gets in MIGRATION_6_7.
 * `ftpAtRideWatts` / `lthrAtRideBpm` record the thresholds in force at the time,
 * so raising FTP later never rewrites the training load of past rides. All
 * training columns stay null on existing rides: the heart-rate and power stream
 * was never recorded, so there is nothing to backfill and nothing is invented.
 */
val MIGRATION_7_8 =
    object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `ride_sample` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`rideId` INTEGER NOT NULL, " +
                    "`timestampMs` INTEGER NOT NULL, " +
                    "`latitude` REAL, `longitude` REAL, `altitudeM` REAL, " +
                    "`speedKmh` REAL, `gradePercent` REAL, " +
                    "`powerWatts` INTEGER, `powerSource` TEXT, " +
                    "`heartRateBpm` INTEGER, `cadenceRpm` INTEGER, " +
                    "FOREIGN KEY(`rideId`) REFERENCES `ride_history`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ride_sample_rideId` ON `ride_sample` (`rideId`)")

            listOf(
                "`normalizedPowerWatts` INTEGER",
                "`intensityFactor` REAL",
                "`trainingStressScore` REAL",
                "`hrTss` REAL",
                "`trimp` REAL",
                "`workKj` REAL",
                "`decouplingPercent` REAL",
                "`avgHeartRateBpm` INTEGER",
                "`maxHeartRateBpm` INTEGER",
                "`avgCadenceRpm` INTEGER",
                "`maxPowerWatts` INTEGER",
                "`powerSource` TEXT",
                "`ftpAtRideWatts` INTEGER",
                "`lthrAtRideBpm` INTEGER",
                "`isComplete` INTEGER NOT NULL DEFAULT 1",
            ).forEach { column ->
                db.execSQL("ALTER TABLE `ride_history` ADD COLUMN $column")
            }

            listOf(
                "`ftpWatts` INTEGER",
                "`lthrBpm` INTEGER",
                "`autoDetectThresholds` INTEGER NOT NULL DEFAULT 1",
                "`pendingFtpWatts` INTEGER",
                "`pendingLthrBpm` INTEGER",
                "`pairedPowerAddress` TEXT",
                "`autoLapMode` TEXT NOT NULL DEFAULT 'OFF'",
                "`autoLapDistanceKm` REAL",
                "`autoLapIntervalMinutes` INTEGER",
            ).forEach { column ->
                db.execSQL("ALTER TABLE `user_settings` ADD COLUMN $column")
            }
        }
    }
```

Register it and drop the destructive fallback:

```kotlin
                ).addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .build()
```

Removing `fallbackToDestructiveMigration()` is deliberate and required by the spec: it silently wipes every recorded ride when a migration is missing. Losing a rider's history is a worse outcome than failing loudly, and the migration test below is what keeps the loud failure from ever reaching a device.

- [ ] **Step 7: Add the domain model and repository**

`core/domain/.../history/RideSample.kt`:

```kotlin
package com.speedevand.inkride.core.domain.history

import com.speedevand.inkride.core.domain.tracking.PowerSource

/**
 * One second of a ride. Every field is nullable: any sensor may be absent, and
 * the stream deliberately continues through a GPS dropout so non-positional
 * readings are not lost with the fix.
 */
data class RideSample(
    val timestampMs: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeM: Double? = null,
    val speedKmh: Double? = null,
    val gradePercent: Double? = null,
    val powerWatts: Int? = null,
    val powerSource: PowerSource? = null,
    val heartRateBpm: Int? = null,
    val cadenceRpm: Int? = null,
)
```

`PowerSource` does not exist yet — it arrives in plan 2, Task 5. Until then, define it in `:core:domain` alongside `RideMetrics.kt` as part of this task so the model compiles:

```kotlin
/** Whether a power reading came from a meter or from PowerEstimator's model. */
enum class PowerSource {
    MEASURED,
    ESTIMATED,
}
```

`core/domain/.../history/RideSampleRepository.kt`:

```kotlin
package com.speedevand.inkride.core.domain.history

import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result

/**
 * Persistence for the 1 Hz ride sample stream. Written in batches while a ride
 * is in progress (not only at the end), read back for post-ride analysis.
 * Samples are tied to a ride row by a cascading foreign key.
 */
interface RideSampleRepository {
    suspend fun saveSamples(
        rideId: Long,
        samples: List<RideSample>,
    ): EmptyResult<DataError.Local>

    suspend fun getSamples(rideId: Long): Result<List<RideSample>, DataError.Local>
}
```

`feature/history/data/.../RoomRideSampleRepository.kt` mirrors `RoomRideTrackPointRepository` exactly, including its `SQLiteFullException` → `DISK_FULL` handling:

```kotlin
package com.speedevand.inkride.history.data

import android.database.sqlite.SQLiteFullException
import com.speedevand.inkride.core.database.RideSampleDao
import com.speedevand.inkride.core.database.RideSampleEntity
import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.history.RideSample
import com.speedevand.inkride.core.domain.history.RideSampleRepository
import com.speedevand.inkride.core.domain.tracking.PowerSource

class RoomRideSampleRepository(
    private val dao: RideSampleDao,
) : RideSampleRepository {
    override suspend fun saveSamples(
        rideId: Long,
        samples: List<RideSample>,
    ): EmptyResult<DataError.Local> =
        try {
            dao.insertAll(samples.map { it.toEntity(rideId) })
            Result.Success(Unit)
        } catch (e: SQLiteFullException) {
            Result.Error(DataError.Local.DISK_FULL)
        } catch (e: Exception) {
            Result.Error(DataError.Local.UNKNOWN)
        }

    override suspend fun getSamples(rideId: Long): Result<List<RideSample>, DataError.Local> =
        try {
            Result.Success(dao.getForRide(rideId).map { it.toDomain() })
        } catch (e: Exception) {
            Result.Error(DataError.Local.UNKNOWN)
        }
}

private fun RideSample.toEntity(rideId: Long) =
    RideSampleEntity(
        rideId = rideId,
        timestampMs = timestampMs,
        latitude = latitude,
        longitude = longitude,
        altitudeM = altitudeM,
        speedKmh = speedKmh,
        gradePercent = gradePercent,
        powerWatts = powerWatts,
        powerSource = powerSource?.name,
        heartRateBpm = heartRateBpm,
        cadenceRpm = cadenceRpm,
    )

private fun RideSampleEntity.toDomain() =
    RideSample(
        timestampMs = timestampMs,
        latitude = latitude,
        longitude = longitude,
        altitudeM = altitudeM,
        speedKmh = speedKmh,
        gradePercent = gradePercent,
        powerWatts = powerWatts,
        powerSource = powerSource?.let { runCatching { PowerSource.valueOf(it) }.getOrNull() },
        heartRateBpm = heartRateBpm,
        cadenceRpm = cadenceRpm,
    )
```

- [ ] **Step 8: Wire Koin**

In `DatabaseModule.kt`, beside the other DAO singles:

```kotlin
        single { get<AppDatabase>().rideSampleDao() }
```

In `HistoryDataModule.kt`, beside the other repositories:

```kotlin
        single<RideSampleRepository> { RoomRideSampleRepository(get()) }
```

- [ ] **Step 9: Write the DAO test**

`RideSampleDaoTest.kt`, extending `DatabaseTestBase` like the neighbouring DAO tests:

```kotlin
@Test
fun samplesRoundTripInTimestampOrderAndCascadeOnRideDelete() = runTest {
    val rideId = db.rideHistoryDao().insert(testRideEntity())

    db.rideSampleDao().insertAll(
        listOf(
            RideSampleEntity(rideId = rideId, timestampMs = 2_000L, latitude = null, longitude = null, altitudeM = null, speedKmh = 20.0, gradePercent = null, powerWatts = 210, powerSource = "MEASURED", heartRateBpm = 150, cadenceRpm = 88),
            RideSampleEntity(rideId = rideId, timestampMs = 1_000L, latitude = 52.0, longitude = 0.0, altitudeM = 100.0, speedKmh = 18.0, gradePercent = 1.5, powerWatts = null, powerSource = null, heartRateBpm = 148, cadenceRpm = null),
        ),
    )

    val stored = db.rideSampleDao().getForRide(rideId)
    assertThat(stored.map { it.timestampMs }).isEqualTo(listOf(1_000L, 2_000L))
    // A position-less sample keeps its non-positional readings.
    assertThat(stored[1].latitude).isNull()
    assertThat(stored[1].heartRateBpm).isEqualTo(150)

    db.rideHistoryDao().deleteById(rideId)
    assertThat(db.rideSampleDao().getForRide(rideId)).isEmpty()
}
```

Use whatever ride-entity builder `TestEntities.kt` already exposes in place of `testRideEntity()`.

- [ ] **Step 10: Run the instrumented tests**

Run: `./gradlew :core:database:connectedDebugAndroidTest`
Expected: PASS. Requires a connected device or running emulator.

- [ ] **Step 11: Lint and commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add core/database/src core/domain/src feature/history/data/src
git commit -m "feat: add the 1 Hz ride_sample stream and migration 7 to 8"
```

---

### Task 9: Create the ride row at start and recover interrupted rides

Spec 5.2. Today the whole ride lives in memory until `stop()`, so killing the process loses it entirely. With a 1 Hz stream that is untenable.

**Files:**
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/history/RideHistoryRepository.kt`
- Modify: `core/database/src/main/java/com/speedevand/inkride/core/database/RideHistoryDao.kt`
- Modify: `core/database/src/main/java/com/speedevand/inkride/core/database/RideHistoryEntity.kt`
- Modify: `feature/history/data/src/main/java/com/speedevand/inkride/history/data/RoomRideHistoryRepository.kt`
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideTracker.kt`
- Modify: `app/src/main/java/com/speedevand/inkride/InkRideApp.kt`
- Modify: `core/testing/src/main/java/com/speedevand/inkride/core/testing/fakes/FakeRideHistoryRepository.kt`
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideTrackerTest.kt`

**Interfaces:**
- Consumes: `RideSampleRepository` (Task 8).
- Produces: `RideHistoryRepository.startRide(startedAt: Long): Result<Long, DataError.Local>`, `.finishRide(ride: RideRecord): EmptyResult<DataError.Local>`, `.getUnfinishedRides(): Result<List<RideRecord>, DataError.Local>`; `RideRecord.isComplete: Boolean`; `RideTracker.recoverUnfinishedRides()`.

- [ ] **Step 1: Write the failing tracker test**

```kotlin
@Test
fun `a ride row is created at start so an interrupted ride survives`() = runTest {
    tracker.start()

    // The row must exist before any sample arrives — that is the whole point.
    assertThat(historyRepository.savedRides).hasSize(1)
    assertThat(historyRepository.savedRides.first().isComplete).isFalse()
}

@Test
fun `recovery finishes an interrupted ride that covered enough distance`() = runTest {
    val rideId = historyRepository.startRide(startedAt = 1_000L).let { (it as Result.Success).data }
    sampleRepository.saveSamples(
        rideId,
        listOf(
            RideSample(timestampMs = 1_000L, latitude = 52.0, longitude = 0.0, speedKmh = 20.0),
            RideSample(timestampMs = 61_000L, latitude = 52.003, longitude = 0.0, speedKmh = 20.0),
        ),
    )

    tracker.recoverUnfinishedRides()

    val recovered = historyRepository.savedRides.single()
    assertThat(recovered.isComplete).isTrue()
    assertThat(recovered.distanceKm).isGreaterThan(0.0)
}

@Test
fun `recovery deletes an interrupted ride that never went anywhere`() = runTest {
    historyRepository.startRide(startedAt = 1_000L)

    tracker.recoverUnfinishedRides()

    assertThat(historyRepository.savedRides).isEmpty()
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideTrackerTest"`
Expected: FAIL — `startRide`, `recoverUnfinishedRides` and `isComplete` do not exist.

- [ ] **Step 3: Extend the entity and DAO**

In `RideHistoryEntity.kt`, add as the last field:

```kotlin
    val isComplete: Boolean = true,
```

In `RideHistoryDao.kt`, filter the listing and the lifetime aggregate to finished rides, and add the two new queries. Change the first query to:

```kotlin
    @Query("SELECT * FROM ride_history WHERE isComplete = 1 ORDER BY startTimestamp DESC")
    fun observeAll(): Flow<List<RideHistoryEntity>>
```

Add `WHERE isComplete = 1` to the `observeLifetimeStats` query's `FROM ride_history` clause, so an in-progress ride never appears in history or lifetime totals. Then add:

```kotlin
    @Query("SELECT * FROM ride_history WHERE isComplete = 0 ORDER BY startTimestamp ASC")
    suspend fun getUnfinished(): List<RideHistoryEntity>

    @androidx.room.Update
    suspend fun update(ride: RideHistoryEntity)
```

- [ ] **Step 4: Extend the repository contract**

In `RideHistoryRepository.kt`, add:

```kotlin
    /**
     * Inserts an in-progress ride row (`isComplete = false`) and returns its id.
     * Called at ride start so samples have somewhere to go immediately and a
     * killed process leaves a recoverable row behind rather than nothing.
     */
    suspend fun startRide(startedAt: Long): Result<Long, DataError.Local>

    /** Writes the final aggregates and marks the ride complete. */
    suspend fun finishRide(ride: RideRecord): EmptyResult<DataError.Local>

    /** Rides left `isComplete = false` by a previous process. */
    suspend fun getUnfinishedRides(): Result<List<RideRecord>, DataError.Local>
```

Add `val isComplete: Boolean = true` to `RideRecord`, and carry it through the existing mappers in `RoomRideHistoryRepository.kt`. Implement the three methods there with the same `try`/`SQLiteFullException`/`Exception` shape the file already uses; `startRide` inserts a `RideHistoryEntity(startTimestamp = startedAt, endTimestamp = startedAt, isComplete = false)` with zeroed aggregates, and `finishRide` calls `dao.update(ride.toEntity().copy(isComplete = true))`.

- [ ] **Step 5: Rework the tracker lifecycle**

In `RideTracker`, add the constructor parameter `private val sampleRepository: RideSampleRepository,` after `trackPointRepository`, and the fields:

```kotlin
    // Row id of the in-progress ride, allocated at start so samples can be
    // flushed during the ride instead of being held hostage until stop().
    @Volatile
    private var activeRideId: Long? = null

    private val pendingSamples = mutableListOf<RideSample>()
    private val pendingSamplesLock = Any()
    private var lastSampleStoredAtMs: Long = 0L
    private var lastSampleFlushAtMs: Long = 0L
    private val sampleIntervalMs: Long = 1_000L
    private val sampleFlushIntervalMs: Long = 60_000L
```

In `startNewSession()`, inside `.onSuccess { ... }`, before `launchCollection()`:

```kotlin
                scope.launch {
                    historyRepository.startRide(sessionStartMs).onSuccess { rideId ->
                        activeRideId = rideId
                    }
                }
```

In the sample collector, after `recordTrackPoint(...)`, add the throttled buffer and periodic flush:

```kotlin
                        recordRideSample(newState.status, sample, newState.metrics)
```

and the method:

```kotlin
    /**
     * Buffers one sample per [sampleIntervalMs] while TRACKING and flushes the
     * buffer to the database every [sampleFlushIntervalMs], so a killed process
     * loses at most a minute of a ride rather than all of it.
     */
    private fun recordRideSample(
        status: TrackingStatus,
        sample: RideSensorSample,
        metrics: RideMetrics,
    ) {
        if (status != TrackingStatus.TRACKING) return
        val rideId = activeRideId ?: return
        if (sample.timestampMs - lastSampleStoredAtMs < sampleIntervalMs) return
        lastSampleStoredAtMs = sample.timestampMs

        val batch =
            synchronized(pendingSamplesLock) {
                pendingSamples +=
                    RideSample(
                        timestampMs = sample.timestampMs,
                        latitude = sample.latitude,
                        longitude = sample.longitude,
                        altitudeM = metrics.altitudeM,
                        speedKmh = metrics.currentSpeedKmh,
                        gradePercent = metrics.gradePercent,
                        powerWatts = metrics.powerWatts,
                        powerSource = null,
                        heartRateBpm = metrics.heartRateBpm,
                        cadenceRpm = metrics.cadenceRpm,
                    )
                if (sample.timestampMs - lastSampleFlushAtMs < sampleFlushIntervalMs) {
                    null
                } else {
                    lastSampleFlushAtMs = sample.timestampMs
                    ArrayList(pendingSamples).also { pendingSamples.clear() }
                }
            }

        if (batch != null) {
            scope.launch { sampleRepository.saveSamples(rideId, batch) }
        }
    }
```

`powerSource` stays `null` here; plan 2 Task 5 fills it once `RideMetrics.powerSource` exists.

In `stop()`, replace the `saveRide(...)` call with a version that flushes the tail batch, calls `finishRide` on the existing `activeRideId`, and deletes the row when `metrics.distanceKm < minSaveDistanceKm`. Clear `activeRideId`, `pendingSamples`, `lastSampleStoredAtMs` and `lastSampleFlushAtMs` alongside the other reset state.

- [ ] **Step 6: Add recovery**

```kotlin
    /**
     * Closes rides a previous process left open. Called once at app start —
     * deliberately not from `init`, because Koin constructs this singleton on the
     * main thread and database I/O there would block launch.
     */
    fun recoverUnfinishedRides() {
        scope.launch {
            historyRepository.getUnfinishedRides().onSuccess { rides ->
                rides.forEach { ride ->
                    val samples =
                        sampleRepository
                            .getSamples(ride.id)
                            .let { if (it is Result.Success) it.data else emptyList() }
                    val distanceKm = distanceFromSamplesKm(samples)
                    if (distanceKm >= minSaveDistanceKm) {
                        historyRepository.finishRide(rideFromSamples(ride, samples, distanceKm))
                    } else {
                        historyRepository.deleteById(ride.id)
                    }
                }
            }
        }
    }

    /** Straight-line distance along the recovered sample positions. */
    private fun distanceFromSamplesKm(samples: List<RideSample>): Double {
        var meters = 0.0
        var previous: RideSample? = null
        for (sample in samples) {
            if (sample.latitude == null || sample.longitude == null) continue
            previous?.let {
                meters += haversineMeters(it.latitude!!, it.longitude!!, sample.latitude, sample.longitude)
            }
            previous = sample
        }
        return meters / 1000.0
    }
```

Write `rideFromSamples(ride, samples, distanceKm)` as a private helper that rebuilds the aggregates the metrics calculator would have produced — elapsed time from the first and last sample timestamp, moving time from samples whose `speedKmh` is above `autoPauseSpeedKmh`, max speed from the sample maximum, average speed from distance over moving time — and returns `ride.copy(...)` with `isComplete = true`. Elevation gain and calories stay at whatever the row already holds; a recovered ride is explicitly a best-effort reconstruction, not a claim of full fidelity.

- [ ] **Step 7: Call recovery at app start**

In `InkRideApp.onCreate()`, after `startKoin { ... }`:

```kotlin
        // Close any ride a previous process left open. Fire-and-forget: it runs
        // on the tracker's own scope and must not delay startup.
        get<RideTracker>().recoverUnfinishedRides()
```

- [ ] **Step 8: Update the fakes and Koin wiring**

Add `startRide`, `finishRide` and `getUnfinishedRides` to `FakeRideHistoryRepository`, backed by its existing in-memory list, and expose `savedRides` so the tests above can read it. Create `FakeRideSampleRepository` in the same package. In `trackingDataModule`, extend the `RideTracker` construction to pass the new dependency:

```kotlin
        // (sensorDataSource, metricsCalculator, historyRepository, trackPointRepository,
        //  sampleRepository, lapRepository, bleSensorDataSource, userSettingsRepository)
        single { RideTracker(get(), get(), get(), get(), get(), get(), get(), get()) }
```

- [ ] **Step 9: Run everything**

Run: `./gradlew :core:domain:test :feature:history:data:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 10: Run the instrumented cross-module test**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "*RideToHistoryE2ETest*"`
Expected: PASS. This test asserts the ride reaches history; it must keep passing now that the row is created earlier and `observeAll()` filters on `isComplete`.

- [ ] **Step 11: Write the cross-module recovery test**

Spec 8.3 requires one `:app/androidTest` case that crosses module boundaries. Create
`app/src/androidTest/java/com/speedevand/inkride/tracking/RideRecoveryE2ETest.kt`, following the
harness in `RideTrackingE2ETestBase`:

```kotlin
@Test
fun aRideInterruptedByProcessDeathIsRecoveredIntoHistory() {
    startRide()
    feedSamples(RideSamples.steadyRide(durationSeconds = 120))

    // Simulate process death: drop the tracker without ever calling stop(), so
    // no finish path runs — exactly what an OOM kill leaves behind.
    killTrackerWithoutStopping()

    // A fresh tracker over the same database is what the next launch sees.
    val recovered = restartTracker()
    recovered.recoverUnfinishedRides()

    composeRule.waitUntil(timeoutMillis = 5_000) {
        runBlocking { historyRepository.observeAll().first() }.isNotEmpty()
    }
    val ride = runBlocking { historyRepository.observeAll().first() }.single()
    assertThat(ride.isComplete).isTrue()
    assertThat(ride.distanceKm).isGreaterThan(0.0)
}
```

`killTrackerWithoutStopping()` and `restartTracker()` are helpers this test adds to the base class:
the first cancels the tracker's scope and drops the Koin instance without invoking `stop()`; the
second resolves a fresh `RideTracker` against the same in-test database. Do not call `stop()` in
this test — calling it would exercise the normal finish path and prove nothing about recovery.

- [ ] **Step 12: Run it**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "*RideRecoveryE2ETest*"`
Expected: PASS.

- [ ] **Step 13: Lint and commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add core/domain/src core/database/src core/testing/src feature/history/data/src feature/tracking/data/src app/src
git commit -m "feat: create the ride row at start and recover interrupted rides"
```

---

## Done when

- All six audit defects have a regression test that fails without the fix.
- `./gradlew testDebugUnitTest :core:domain:test` is green.
- `./gradlew :core:database:connectedDebugAndroidTest :app:connectedDebugAndroidTest` is green.
- `./gradlew ktlintCheck` is green.
- Killing the app mid-ride and relaunching leaves a finished ride in history rather than nothing.
