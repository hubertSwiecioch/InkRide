# Training Load Metrics — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the training metrics a dedicated bike computer provides — NP, IF, TSS from measured power; hrTSS and TRIMP from heart rate; time in HR and power zones; VAM; work; decoupling — plus FTP/LTHR thresholds with opt-in auto-detection and auto-lap.

**Architecture:** A new `TrainingLoadCalculator` in `:core:domain/tracking/training/` runs beside `RideMetricsCalculator` on the same sample stream and knows nothing about it; `RideTracker` composes both into `TrackingState`. Live metrics come from the calculator, post-ride analysis is recomputed from the persisted 1 Hz `ride_sample` stream.

**Tech Stack:** Kotlin, Room, Koin, JUnit 5 + assertk, Compose + MMD, ktlint.

**Spec:** `docs/superpowers/specs/2026-09-15-gps-metrics-training-design.md` (sections 3, 4, 6 and 1.7)

**Depends on:** `2026-09-15-gps-measurement-reliability.md` (the `ride_sample` stream, migration 7→8, `RideMetrics.isMoving`) and `2026-09-15-ble-cycling-power.md` (`PowerSource.MEASURED`).

## Global Constraints

- Pure-Kotlin modules (`:core:domain`) test via `./gradlew :core:domain:test`; Android modules via `./gradlew <module>:testDebugUnitTest`.
- JVM tests use JUnit 5 + assertk with backtick test names.
- After every Kotlin change: `./gradlew ktlintFormat` then `./gradlew ktlintCheck`.
- NP, IF and TSS are **never** computed from `PowerSource.ESTIMATED`. A metric without its inputs is absent, not approximated — the UI shows `--`.
- FTP is never invented. `ftpWatts == null` means IF and TSS are unavailable.
- Use MMD components before custom UI; E-Ink rules apply (`snap()`, no overscroll, no fluid animation).
- New fakes belong in `:core:testing`.

---

### Task 1: Training metrics model and the 1 Hz resampler

The input stream is uneven — GPS ~1 Hz, barometer ~2 Hz, BLE on its own schedule. Every window in this plan is defined in seconds, so the stream is normalised before any window runs.

**Files:**
- Create: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/training/TrainingMetrics.kt`
- Create: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/training/SampleResampler.kt`
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/training/SampleResamplerTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `TrainingMetrics`; `AthleteThresholds`; `SampleResampler.accept(timestampMs: Long, powerWatts: Int?, heartRateBpm: Int?, altitudeM: Double?, isMoving: Boolean): List<ResampledSecond>`; `data class ResampledSecond(timestampMs: Long, powerWatts: Int?, heartRateBpm: Int?, altitudeM: Double?, isMoving: Boolean)`.

- [ ] **Step 1: Write the failing test**

```kotlin
class SampleResamplerTest {
    @Test
    fun `emits exactly one second per elapsed second regardless of input rate`() {
        val resampler = SampleResampler()

        // Four inputs inside one second must not produce four seconds.
        assertThat(resampler.accept(0L, 200, 140, 100.0, true)).hasSize(1)
        assertThat(resampler.accept(250L, 210, 141, 100.0, true)).isEmpty()
        assertThat(resampler.accept(500L, 220, 142, 100.0, true)).isEmpty()
        assertThat(resampler.accept(750L, 230, 143, 100.0, true)).isEmpty()

        assertThat(resampler.accept(1_000L, 240, 144, 101.0, true)).hasSize(1)
    }

    @Test
    fun `holds the last reading across a multi-second gap`() {
        val resampler = SampleResampler()
        resampler.accept(0L, 200, 140, 100.0, true)

        // A 4 s gap: the three missing seconds are filled with the held value,
        // so a window never silently shortens because a sensor went quiet.
        val filled = resampler.accept(4_000L, 260, 150, 104.0, true)

        assertThat(filled).hasSize(4)
        assertThat(filled.map { it.powerWatts }).isEqualTo(listOf(200, 200, 200, 260))
        assertThat(filled.last().timestampMs).isEqualTo(4_000L)
    }

    @Test
    fun `reset clears the held reading`() {
        val resampler = SampleResampler()
        resampler.accept(0L, 200, 140, 100.0, true)
        resampler.reset()

        assertThat(resampler.accept(5_000L, 100, 120, 100.0, true)).hasSize(1)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core:domain:test --tests "*SampleResamplerTest*"`
Expected: FAIL — "Unresolved reference: SampleResampler".

- [ ] **Step 3: Write the model**

`TrainingMetrics.kt`:

```kotlin
package com.speedevand.inkride.core.domain.tracking.training

/**
 * Training load for the ride so far. Every field is null when its inputs are
 * absent rather than approximated: NP, IF and TSS require power from a meter
 * (never PowerEstimator's model), IF and TSS additionally require an FTP, and
 * hrTSS and TRIMP require a heart-rate strap. The UI renders null as "--".
 */
data class TrainingMetrics(
    val normalizedPowerWatts: Int? = null,
    val intensityFactor: Double? = null,
    val trainingStressScore: Double? = null,
    val hrTss: Double? = null,
    val trimp: Double? = null,
    val vamMetersPerHour: Double? = null,
    val workKj: Double = 0.0,
    val currentHrZone: Int? = null,
    val currentPowerZone: Int? = null,
    val secondsInHrZone: Map<Int, Long> = emptyMap(),
    val secondsInPowerZone: Map<Int, Long> = emptyMap(),
)

/**
 * The rider's thresholds. [ftpWatts] null means IF and TSS cannot be computed
 * and must not be guessed; [lthrBpm] falls back to 0.9 x age-predicted HRmax,
 * which is an established rule of thumb, unlike any FTP default would be.
 */
data class AthleteThresholds(
    val ftpWatts: Int?,
    val lthrBpm: Int?,
    val ageForHrZones: Int,
)
```

- [ ] **Step 4: Write the resampler**

```kotlin
package com.speedevand.inkride.core.domain.tracking.training

/** One normalised second of a ride. */
data class ResampledSecond(
    val timestampMs: Long,
    val powerWatts: Int?,
    val heartRateBpm: Int?,
    val altitudeM: Double?,
    val isMoving: Boolean,
)

/**
 * Normalises an uneven sensor stream onto a 1 Hz grid with zero-order hold.
 *
 * Every window in [TrainingLoadCalculator] is defined in seconds. Without this,
 * a 30-second NP window would contain a variable number of samples and the
 * result would track sensor rates instead of the rider's effort. Holding the
 * last reading across a gap is deliberate: a quiet sensor means "unchanged",
 * not "shorter ride".
 */
class SampleResampler(
    private val maxHoldSeconds: Int = 30,
) {
    private var lastEmittedSecond: Long? = null
    private var held: ResampledSecond? = null

    fun accept(
        timestampMs: Long,
        powerWatts: Int?,
        heartRateBpm: Int?,
        altitudeM: Double?,
        isMoving: Boolean,
    ): List<ResampledSecond> {
        val second = timestampMs / 1000L
        val previous = lastEmittedSecond
        val current =
            ResampledSecond(
                timestampMs = second * 1000L,
                powerWatts = powerWatts,
                heartRateBpm = heartRateBpm,
                altitudeM = altitudeM,
                isMoving = isMoving,
            )

        if (previous == null) {
            lastEmittedSecond = second
            held = current
            return listOf(current.copy(timestampMs = timestampMs))
        }
        if (second <= previous) {
            // Sub-second update: refresh what a later fill would hold, emit nothing.
            held = current
            return emptyList()
        }

        val gapSeconds = (second - previous).toInt()
        val holdFrom = held
        val filled = mutableListOf<ResampledSecond>()
        // Cap the fill so a long dropout can't manufacture minutes of held data.
        val fillCount = (gapSeconds - 1).coerceAtMost(maxHoldSeconds)
        for (offset in 1..fillCount) {
            filled +=
                (holdFrom ?: current).copy(timestampMs = (previous + offset) * 1000L)
        }
        filled += current

        lastEmittedSecond = second
        held = current
        return filled
    }

    fun reset() {
        lastEmittedSecond = null
        held = null
    }
}
```

- [ ] **Step 5: Run it to verify it passes**

Run: `./gradlew :core:domain:test --tests "*SampleResamplerTest*"`
Expected: PASS.

- [ ] **Step 6: Lint and commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add core/domain/src
git commit -m "feat: add training metrics model and 1 Hz sample resampler"
```

---

### Task 2: Normalized Power, Intensity Factor, TSS

**Files:**
- Create: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/training/TrainingLoadCalculator.kt`
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/training/TrainingLoadCalculatorTest.kt`

**Interfaces:**
- Consumes: `SampleResampler`, `TrainingMetrics`, `AthleteThresholds` (Task 1); `PowerSource`.
- Produces: `TrainingLoadCalculator.process(timestampMs: Long, powerWatts: Int?, powerSource: PowerSource?, heartRateBpm: Int?, altitudeM: Double?, isMoving: Boolean, thresholds: AthleteThresholds): TrainingMetrics` and `.reset()`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `normalized power equals average power for a perfectly steady effort`() {
    val calculator = TrainingLoadCalculator()
    val thresholds = AthleteThresholds(ftpWatts = 250, lthrBpm = 165, ageForHrZones = 30)
    var metrics = TrainingMetrics()

    // 10 minutes at a flat 200 W. With no variability, NP collapses to the mean.
    for (second in 0..600) {
        metrics = calculator.process(second * 1000L, 200, PowerSource.MEASURED, null, null, true, thresholds)
    }

    assertThat(metrics.normalizedPowerWatts!!).isBetween(198, 202)
}

@Test
fun `normalized power exceeds average power for a variable effort`() {
    val calculator = TrainingLoadCalculator()
    val thresholds = AthleteThresholds(ftpWatts = 250, lthrBpm = 165, ageForHrZones = 30)
    var metrics = TrainingMetrics()

    // Alternating 100/300 W minutes: same 200 W mean, higher physiological cost.
    for (second in 0..1_200) {
        val watts = if ((second / 60) % 2 == 0) 100 else 300
        metrics = calculator.process(second * 1000L, watts, PowerSource.MEASURED, null, null, true, thresholds)
    }

    assertThat(metrics.normalizedPowerWatts!!).isGreaterThan(210)
}

@Test
fun `IF and TSS follow from NP and FTP`() {
    val calculator = TrainingLoadCalculator()
    val thresholds = AthleteThresholds(ftpWatts = 200, lthrBpm = 165, ageForHrZones = 30)
    var metrics = TrainingMetrics()

    // Exactly one hour at exactly FTP: IF = 1.0 and TSS = 100 by definition.
    for (second in 0..3_600) {
        metrics = calculator.process(second * 1000L, 200, PowerSource.MEASURED, null, null, true, thresholds)
    }

    assertThat(metrics.intensityFactor!!).isCloseTo(1.0, 0.02)
    assertThat(metrics.trainingStressScore!!).isCloseTo(100.0, 2.0)
}

@Test
fun `estimated power never produces NP, IF or TSS`() {
    val calculator = TrainingLoadCalculator()
    val thresholds = AthleteThresholds(ftpWatts = 250, lthrBpm = 165, ageForHrZones = 30)
    var metrics = TrainingMetrics()

    for (second in 0..600) {
        metrics = calculator.process(second * 1000L, 200, PowerSource.ESTIMATED, null, null, true, thresholds)
    }

    assertThat(metrics.normalizedPowerWatts).isNull()
    assertThat(metrics.intensityFactor).isNull()
    assertThat(metrics.trainingStressScore).isNull()
}

@Test
fun `IF and TSS are absent without an FTP rather than guessed`() {
    val calculator = TrainingLoadCalculator()
    val thresholds = AthleteThresholds(ftpWatts = null, lthrBpm = 165, ageForHrZones = 30)
    var metrics = TrainingMetrics()

    for (second in 0..600) {
        metrics = calculator.process(second * 1000L, 200, PowerSource.MEASURED, null, null, true, thresholds)
    }

    assertThat(metrics.normalizedPowerWatts).isNotNull()
    assertThat(metrics.intensityFactor).isNull()
    assertThat(metrics.trainingStressScore).isNull()
}

@Test
fun `stopped seconds do not dilute normalized power`() {
    val calculator = TrainingLoadCalculator()
    val thresholds = AthleteThresholds(ftpWatts = 250, lthrBpm = 165, ageForHrZones = 30)
    var metrics = TrainingMetrics()

    for (second in 0..600) {
        metrics = calculator.process(second * 1000L, 200, PowerSource.MEASURED, null, null, true, thresholds)
    }
    // Five minutes at a red light, zero watts, not moving.
    for (second in 601..900) {
        metrics = calculator.process(second * 1000L, 0, PowerSource.MEASURED, null, null, false, thresholds)
    }

    assertThat(metrics.normalizedPowerWatts!!).isBetween(198, 202)
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:domain:test --tests "*TrainingLoadCalculatorTest*"`
Expected: FAIL — "Unresolved reference: TrainingLoadCalculator".

- [ ] **Step 3: Write the calculator**

```kotlin
package com.speedevand.inkride.core.domain.tracking.training

import com.speedevand.inkride.core.domain.tracking.PowerSource
import kotlin.collections.ArrayDeque
import kotlin.math.pow

/**
 * Training load for an in-progress ride, computed once per elapsed second.
 *
 * Deliberately independent of `RideMetricsCalculator`: that class owns the
 * physics of riding (speed, distance, altitude, grade), this one owns the
 * physiology of the effort. They share only the sample stream and are composed
 * in `RideTracker`.
 *
 * Only seconds where the rider is moving are accumulated, so a traffic light
 * neither dilutes NP nor adds minutes to a zone.
 */
class TrainingLoadCalculator(
    private val resampler: SampleResampler = SampleResampler(),
    private val normalizedPowerWindowSeconds: Int = 30,
) {
    private val powerWindow = ArrayDeque<Int>()
    private val rollingFourthPowers = mutableListOf<Double>()
    private var movingSeconds: Long = 0L
    private var workJoules: Double = 0.0

    fun reset() {
        resampler.reset()
        powerWindow.clear()
        rollingFourthPowers.clear()
        movingSeconds = 0L
        workJoules = 0.0
    }

    fun process(
        timestampMs: Long,
        powerWatts: Int?,
        powerSource: PowerSource?,
        heartRateBpm: Int?,
        altitudeM: Double?,
        isMoving: Boolean,
        thresholds: AthleteThresholds,
    ): TrainingMetrics {
        resampler
            .accept(timestampMs, powerWatts, heartRateBpm, altitudeM, isMoving)
            .forEach { second -> accumulate(second, powerSource) }

        val normalizedPower = normalizedPower(powerSource)
        val intensityFactor =
            normalizedPower
                ?.let { np -> thresholds.ftpWatts?.takeIf { it > 0 }?.let { np.toDouble() / it } }
        val trainingStressScore =
            if (normalizedPower != null && intensityFactor != null && thresholds.ftpWatts != null) {
                movingSeconds * normalizedPower * intensityFactor / (thresholds.ftpWatts * 3600.0) * 100.0
            } else {
                null
            }

        return TrainingMetrics(
            normalizedPowerWatts = normalizedPower,
            intensityFactor = intensityFactor,
            trainingStressScore = trainingStressScore,
            workKj = workJoules / 1000.0,
        )
    }

    private fun accumulate(
        second: ResampledSecond,
        powerSource: PowerSource?,
    ) {
        if (!second.isMoving) return
        movingSeconds++

        val watts = second.powerWatts ?: return
        workJoules += watts.coerceAtLeast(0).toDouble()

        // NP is defined on measured power only. Building it on PowerEstimator's
        // +-30-60 % model would compound that error to the fourth power.
        if (powerSource != PowerSource.MEASURED) return
        powerWindow.addLast(watts.coerceAtLeast(0))
        while (powerWindow.size > normalizedPowerWindowSeconds) {
            powerWindow.removeFirst()
        }
        if (powerWindow.size == normalizedPowerWindowSeconds) {
            rollingFourthPowers += powerWindow.average().pow(4)
        }
    }

    /**
     * Coggan's Normalized Power: the fourth root of the mean of the fourth
     * powers of a 30-second rolling average. The exponent is what makes a
     * surging ride cost more than a steady one at the same mean watts.
     */
    private fun normalizedPower(powerSource: PowerSource?): Int? {
        if (powerSource != PowerSource.MEASURED) return null
        if (rollingFourthPowers.isEmpty()) return null
        return rollingFourthPowers.average().pow(0.25).toInt()
    }
}
```

- [ ] **Step 4: Run them to verify they pass**

Run: `./gradlew :core:domain:test --tests "*TrainingLoadCalculatorTest*"`
Expected: PASS.

- [ ] **Step 5: Lint and commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add core/domain/src
git commit -m "feat: compute normalized power, intensity factor and TSS"
```

---

### Task 3: Heart-rate load and time in zones

**Files:**
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/training/TrainingLoadCalculator.kt`
- Create: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/training/PowerZoneCalculator.kt`
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/training/TrainingLoadCalculatorTest.kt`
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/training/PowerZoneCalculatorTest.kt`

**Interfaces:**
- Consumes: the existing `HeartRateZoneCalculator` (`zoneFor(heartRateBpm, age)`, `maxHeartRateBpm(age)`).
- Produces: `PowerZoneCalculator.zoneFor(powerWatts: Int, ftpWatts: Int): Int`; `TrainingMetrics.hrTss`, `.trimp`, `.currentHrZone`, `.currentPowerZone`, `.secondsInHrZone`, `.secondsInPowerZone` populated.

- [ ] **Step 1: Write the failing zone test**

```kotlin
class PowerZoneCalculatorTest {
    private val calculator = PowerZoneCalculator()

    @Test
    fun `maps power onto the seven Coggan zones by percentage of FTP`() {
        // FTP 200 W: boundaries at 55/75/90/105/120/150 % = 110/150/180/210/240/300 W.
        assertThat(calculator.zoneFor(100, ftpWatts = 200)).isEqualTo(1)
        assertThat(calculator.zoneFor(140, ftpWatts = 200)).isEqualTo(2)
        assertThat(calculator.zoneFor(170, ftpWatts = 200)).isEqualTo(3)
        assertThat(calculator.zoneFor(200, ftpWatts = 200)).isEqualTo(4)
        assertThat(calculator.zoneFor(230, ftpWatts = 200)).isEqualTo(5)
        assertThat(calculator.zoneFor(280, ftpWatts = 200)).isEqualTo(6)
        assertThat(calculator.zoneFor(400, ftpWatts = 200)).isEqualTo(7)
    }
}
```

- [ ] **Step 2: Write the failing load tests**

```kotlin
@Test
fun `an hour at LTHR scores 100 hrTSS`() {
    val calculator = TrainingLoadCalculator()
    val thresholds = AthleteThresholds(ftpWatts = null, lthrBpm = 160, ageForHrZones = 30)
    var metrics = TrainingMetrics()

    for (second in 0..3_600) {
        metrics = calculator.process(second * 1000L, null, null, 160, null, true, thresholds)
    }

    assertThat(metrics.hrTss!!).isCloseTo(100.0, 2.0)
}

@Test
fun `time accumulates into the heart-rate zone the rider is in`() {
    val calculator = TrainingLoadCalculator()
    val thresholds = AthleteThresholds(ftpWatts = null, lthrBpm = 160, ageForHrZones = 30)
    var metrics = TrainingMetrics()

    // HRmax(Tanaka, 30) = 187. 120 bpm is 64 % -> zone 2.
    for (second in 0..600) {
        metrics = calculator.process(second * 1000L, null, null, 120, null, true, thresholds)
    }

    assertThat(metrics.currentHrZone).isEqualTo(2)
    assertThat(metrics.secondsInHrZone[2]!!).isBetween(595L, 605L)
}

@Test
fun `hrTSS and TRIMP are absent without a heart-rate reading`() {
    val calculator = TrainingLoadCalculator()
    val thresholds = AthleteThresholds(ftpWatts = 250, lthrBpm = 160, ageForHrZones = 30)
    var metrics = TrainingMetrics()

    for (second in 0..600) {
        metrics = calculator.process(second * 1000L, 200, PowerSource.MEASURED, null, null, true, thresholds)
    }

    assertThat(metrics.hrTss).isNull()
    assertThat(metrics.trimp).isNull()
}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./gradlew :core:domain:test --tests "*training*"`
Expected: FAIL — `PowerZoneCalculator` missing; `hrTss`, `trimp` and the zone maps stay at their defaults.

- [ ] **Step 4: Write the power zone calculator**

```kotlin
package com.speedevand.inkride.core.domain.tracking.training

/**
 * Coggan's seven power training zones as a percentage of FTP. Zone 1 is active
 * recovery, zone 7 neuromuscular. Boundaries: 55, 75, 90, 105, 120 and 150 %.
 */
class PowerZoneCalculator {
    fun zoneFor(
        powerWatts: Int,
        ftpWatts: Int,
    ): Int {
        if (ftpWatts <= 0) return 1
        val percent = powerWatts.toDouble() / ftpWatts * 100.0
        return when {
            percent < 55.0 -> 1
            percent < 75.0 -> 2
            percent < 90.0 -> 3
            percent < 105.0 -> 4
            percent < 120.0 -> 5
            percent < 150.0 -> 6
            else -> 7
        }
    }
}
```

- [ ] **Step 5: Extend the load calculator**

Add the collaborators and accumulators:

```kotlin
    private val heartRateZoneCalculator: HeartRateZoneCalculator = HeartRateZoneCalculator(),
    private val powerZoneCalculator: PowerZoneCalculator = PowerZoneCalculator(),
```

```kotlin
    private val secondsInHrZone = mutableMapOf<Int, Long>()
    private val secondsInPowerZone = mutableMapOf<Int, Long>()
    private var heartRateSum: Long = 0L
    private var heartRateSeconds: Long = 0L
    private var currentHrZone: Int? = null
    private var currentPowerZone: Int? = null
    private var trimpAccumulator: Double = 0.0
```

Clear all of them in `reset()`. In `accumulate`, after the moving check:

```kotlin
        second.heartRateBpm?.let { bpm ->
            heartRateSum += bpm
            heartRateSeconds++
            val zone = heartRateZoneCalculator.zoneFor(bpm, thresholds.ageForHrZones)
            currentHrZone = zone
            secondsInHrZone[zone] = (secondsInHrZone[zone] ?: 0L) + 1L
            // Edwards TRIMP: each minute counts as many times as its zone number.
            trimpAccumulator += zone / 60.0
        }
```

Both the age and the FTP are needed inside `accumulate`, so widen its signature from
`accumulate(second, powerSource)` to `accumulate(second, powerSource, thresholds)` and pass
`thresholds` through from `process` — do not stash it in a field, which would leave the
calculator holding stale thresholds after a settings change mid-ride. In the same method, once
power has been accepted:

```kotlin
        thresholds.ftpWatts?.takeIf { it > 0 }?.let { ftp ->
            val zone = powerZoneCalculator.zoneFor(watts, ftp)
            currentPowerZone = zone
            secondsInPowerZone[zone] = (secondsInPowerZone[zone] ?: 0L) + 1L
        }
```

In `process`, compute the heart-rate load and add the new fields to the returned `TrainingMetrics`:

```kotlin
        // hrTSS mirrors TSS's shape against the lactate-threshold heart rate, so
        // an hour at LTHR scores 100 exactly as an hour at FTP does. Chosen over
        // Banister's TRIMP, which needs resting heart rate and a sex coefficient
        // this app deliberately does not collect.
        val averageHeartRate =
            if (heartRateSeconds > 0L) heartRateSum.toDouble() / heartRateSeconds else null
        val hrTss =
            averageHeartRate
                ?.let { avg -> thresholds.lthrBpm?.takeIf { it > 0 }?.let { avg / it } }
                ?.let { hrIf -> movingSeconds / 3600.0 * hrIf * hrIf * 100.0 }
```

Then return every new field, so nothing accumulated is silently dropped:

```kotlin
        return TrainingMetrics(
            normalizedPowerWatts = normalizedPower,
            intensityFactor = intensityFactor,
            trainingStressScore = trainingStressScore,
            hrTss = hrTss,
            // TRIMP only exists if a strap actually reported something.
            trimp = trimpAccumulator.takeIf { heartRateSeconds > 0L },
            workKj = workJoules / 1000.0,
            currentHrZone = currentHrZone,
            currentPowerZone = currentPowerZone,
            secondsInHrZone = secondsInHrZone.toMap(),
            secondsInPowerZone = secondsInPowerZone.toMap(),
        )
```

- [ ] **Step 6: Run them to verify they pass**

Run: `./gradlew :core:domain:test --tests "*training*"`
Expected: PASS.

- [ ] **Step 7: Lint and commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add core/domain/src
git commit -m "feat: add hrTSS, TRIMP and time in heart-rate and power zones"
```

---

### Task 4: VAM and work

**Files:**
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/training/TrainingLoadCalculator.kt`
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/training/TrainingLoadCalculatorTest.kt`

**Interfaces:**
- Consumes: Task 1's resampled altitude.
- Produces: `TrainingMetrics.vamMetersPerHour` populated. `workKj` already lands in Task 2; this task only pins it with a test.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `VAM reports vertical metres per hour over the recent window`() {
    val calculator = TrainingLoadCalculator()
    val thresholds = AthleteThresholds(ftpWatts = 250, lthrBpm = 160, ageForHrZones = 30)
    var metrics = TrainingMetrics()

    // Climbing 0.25 m/s = 900 m/h, for two full windows.
    for (second in 0..180) {
        val altitude = 100.0 + second * 0.25
        metrics = calculator.process(second * 1000L, 250, PowerSource.MEASURED, null, altitude, true, thresholds)
    }

    assertThat(metrics.vamMetersPerHour!!).isCloseTo(900.0, 50.0)
}

@Test
fun `work in kilojoules is the integral of watts over moving time`() {
    val calculator = TrainingLoadCalculator()
    val thresholds = AthleteThresholds(ftpWatts = 250, lthrBpm = 160, ageForHrZones = 30)
    var metrics = TrainingMetrics()

    // 200 W for 600 s = 120 kJ.
    for (second in 1..600) {
        metrics = calculator.process(second * 1000L, 200, PowerSource.MEASURED, null, null, true, thresholds)
    }

    assertThat(metrics.workKj).isCloseTo(120.0, 1.0)
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:domain:test --tests "*TrainingLoadCalculatorTest*"`
Expected: FAIL — `vamMetersPerHour` is null.

- [ ] **Step 3: Add the VAM window**

```kotlin
    // (timestampMs, altitudeM) for the trailing VAM window, oldest first.
    private val altitudeWindow = ArrayDeque<Pair<Long, Double>>()
    private val vamWindowMs: Long = 60_000L
```

Clear it in `reset()`. In `accumulate`, after the moving check:

```kotlin
        second.altitudeM?.let { altitude ->
            altitudeWindow.addLast(second.timestampMs to altitude)
            while (altitudeWindow.size > 1 && second.timestampMs - altitudeWindow.first().first > vamWindowMs) {
                altitudeWindow.removeFirst()
            }
        }
```

In `process`:

```kotlin
        // VAM: vertical metres per hour over the trailing window. A short window
        // keeps it responsive on a climb; a longer one would lag the gradient.
        val vam =
            altitudeWindow
                .takeIf { it.size >= 2 }
                ?.let { window ->
                    val elapsedHours = (window.last().first - window.first().first) / 3_600_000.0
                    if (elapsedHours <= 0.0) null else (window.last().second - window.first().second) / elapsedHours
                }
```

Add `vamMetersPerHour = vam,` to the returned `TrainingMetrics`.

- [ ] **Step 4: Run them to verify they pass**

Run: `./gradlew :core:domain:test --tests "*TrainingLoadCalculatorTest*"`
Expected: PASS.

- [ ] **Step 5: Lint and commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add core/domain/src
git commit -m "feat: add VAM and cover work accumulation"
```

---

### Task 5: Thresholds in settings

**Files:**
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/settings/UserSettings.kt`
- Modify: `feature/settings/data/src/main/java/com/speedevand/inkride/settings/data/RoomUserSettingsRepository.kt`
- Modify: `feature/settings/presentation/src/main/java/com/speedevand/inkride/settings/presentation/SettingsContract.kt`
- Modify: `feature/settings/presentation/src/main/java/com/speedevand/inkride/settings/presentation/SettingsViewModel.kt`
- Modify: `feature/settings/presentation/src/main/java/com/speedevand/inkride/settings/presentation/SettingsScreen.kt`
- Modify: `feature/settings/presentation/src/main/java/com/speedevand/inkride/settings/presentation/SettingsTestTags.kt`
- Test: `feature/settings/presentation/src/test/kotlin/com/speedevand/inkride/settings/presentation/SettingsViewModelTest.kt`
- Test: `feature/settings/presentation/src/androidTest/kotlin/com/speedevand/inkride/settings/presentation/SettingsScreenTrainingTest.kt`

**Interfaces:**
- Consumes: the columns added by `MIGRATION_7_8`.
- Produces: `UserSettings.ftpWatts: Int?`, `.lthrBpm: Int?`, `.autoDetectThresholds: Boolean`, `.pendingFtpWatts: Int?`, `.pendingLthrBpm: Int?`; `AthleteThresholds.from(settings: UserSettings): AthleteThresholds`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `LTHR falls back to 90 percent of age-predicted HRmax but FTP is never guessed`() {
    val settings = UserSettings(weightKg = 75, age = 30, ftpWatts = null, lthrBpm = null)

    val thresholds = AthleteThresholds.from(settings)

    // Tanaka HRmax(30) = 187; 90 % = 168.
    assertThat(thresholds.lthrBpm).isEqualTo(168)
    assertThat(thresholds.ftpWatts).isNull()
}

@Test
fun `explicit thresholds win over the fallback`() {
    val settings = UserSettings(weightKg = 75, age = 30, ftpWatts = 265, lthrBpm = 172)

    val thresholds = AthleteThresholds.from(settings)

    assertThat(thresholds.ftpWatts).isEqualTo(265)
    assertThat(thresholds.lthrBpm).isEqualTo(172)
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core:domain:test`
Expected: FAIL — the fields and `AthleteThresholds.from` do not exist.

- [ ] **Step 3: Add the settings fields**

In `UserSettings.kt`, beside the alert config:

```kotlin
    // Functional threshold power, in watts. Null means the rider has not set one
    // and none has been detected: IF and TSS are then unavailable rather than
    // computed against an invented number.
    val ftpWatts: Int? = null,
    // Lactate threshold heart rate. Null falls back to 0.9 x age-predicted HRmax.
    val lthrBpm: Int? = null,
    val autoDetectThresholds: Boolean = true,
    // Detected candidates awaiting the rider's yes or no. Never applied silently.
    val pendingFtpWatts: Int? = null,
    val pendingLthrBpm: Int? = null,
```

Add the companion factory in `TrainingMetrics.kt`:

```kotlin
    companion object {
        /**
         * Thresholds for a rider. FTP has no defensible default, so it stays
         * null; LTHR falls back to 90 % of Tanaka's age-predicted HRmax, an
         * established rule of thumb.
         */
        fun from(settings: UserSettings): AthleteThresholds =
            AthleteThresholds(
                ftpWatts = settings.ftpWatts,
                lthrBpm =
                    settings.lthrBpm
                        ?: (HeartRateZoneCalculator().maxHeartRateBpm(settings.age) * 0.9).toInt(),
                ageForHrZones = settings.age,
            )
    }
```

- [ ] **Step 4: Persist them**

Extend `UserSettingsEntity` with the five columns (matching the names in `MIGRATION_7_8`), and carry them through the mappers in `RoomUserSettingsRepository` exactly as the neighbouring nullable fields are carried.

- [ ] **Step 5: Surface them in settings**

Add `SettingsAction.SetFtp(watts: Int?)`, `SetLthr(bpm: Int?)` and `ToggleAutoDetectThresholds` following the naming of the existing actions in `SettingsContract.kt`; handle them in `SettingsViewModel` the way the existing numeric fields (weight, age) are handled; render a "Training" section in `SettingsScreen` with two numeric inputs and one `SwitchMMD`, reusing the MMD components already used for weight and age. Add the test tags alongside the existing ones.

- [ ] **Step 6: Run the tests**

Run: `./gradlew :core:domain:test :feature:settings:presentation:testDebugUnitTest :feature:settings:data:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Write and run the screen test**

Mirror `SettingsScreenProfileTest` to assert that typing an FTP persists it through the fake repository, then run:

Run: `./gradlew :feature:settings:presentation:connectedDebugAndroidTest`
Expected: PASS.

- [ ] **Step 8: Lint and commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add core/domain/src core/database/src feature/settings/src feature/settings/data/src feature/settings/presentation/src
git commit -m "feat: add FTP and LTHR thresholds to settings"
```

---

### Task 6: Threshold auto-detection

**Files:**
- Create: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/training/ThresholdDetector.kt`
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/training/ThresholdDetectorTest.kt`

**Interfaces:**
- Consumes: `RideSample` (reliability plan, Task 8), `UserSettings` thresholds (Task 5).
- Produces: `ThresholdDetector.detect(samples: List<RideSample>, current: AthleteThresholds): ThresholdProposal`; `data class ThresholdProposal(val ftpWatts: Int?, val lthrBpm: Int?)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `proposes FTP as 95 percent of the best twenty minute measured power`() {
    // 30 min at 300 W measured: best 20-min average is 300, so FTP = 285.
    val samples =
        (0 until 1_800).map { second ->
            RideSample(
                timestampMs = second * 1000L,
                powerWatts = 300,
                powerSource = PowerSource.MEASURED,
            )
        }

    val proposal = ThresholdDetector().detect(samples, AthleteThresholds(ftpWatts = 200, lthrBpm = 160, ageForHrZones = 30))

    assertThat(proposal.ftpWatts).isEqualTo(285)
}

@Test
fun `ignores estimated power entirely`() {
    val samples =
        (0 until 1_800).map { second ->
            RideSample(timestampMs = second * 1000L, powerWatts = 400, powerSource = PowerSource.ESTIMATED)
        }

    val proposal = ThresholdDetector().detect(samples, AthleteThresholds(ftpWatts = 200, lthrBpm = 160, ageForHrZones = 30))

    assertThat(proposal.ftpWatts).isNull()
}

@Test
fun `proposes nothing when the candidate does not beat the current threshold by two percent`() {
    // Best 20-min 210 W -> candidate 199.5 -> 199, below the current 200.
    val samples =
        (0 until 1_800).map { second ->
            RideSample(timestampMs = second * 1000L, powerWatts = 210, powerSource = PowerSource.MEASURED)
        }

    val proposal = ThresholdDetector().detect(samples, AthleteThresholds(ftpWatts = 200, lthrBpm = 160, ageForHrZones = 30))

    assertThat(proposal.ftpWatts).isNull()
}

@Test
fun `proposes nothing for a ride shorter than the twenty minute window`() {
    val samples =
        (0 until 600).map { second ->
            RideSample(timestampMs = second * 1000L, powerWatts = 400, powerSource = PowerSource.MEASURED)
        }

    val proposal = ThresholdDetector().detect(samples, AthleteThresholds(ftpWatts = null, lthrBpm = null, ageForHrZones = 30))

    assertThat(proposal.ftpWatts).isNull()
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:domain:test --tests "*ThresholdDetectorTest*"`
Expected: FAIL — "Unresolved reference: ThresholdDetector".

- [ ] **Step 3: Write the detector**

```kotlin
package com.speedevand.inkride.core.domain.tracking.training

import com.speedevand.inkride.core.domain.history.RideSample
import com.speedevand.inkride.core.domain.tracking.PowerSource

/** A threshold the detector believes has been beaten. Null means no proposal. */
data class ThresholdProposal(
    val ftpWatts: Int? = null,
    val lthrBpm: Int? = null,
)

/**
 * Looks for a new FTP or LTHR in a finished ride.
 *
 * FTP is the best 20-minute rolling average power x 0.95, the standard field
 * estimate. Only `PowerSource.MEASURED` samples count: letting a modelled
 * estimate move the threshold would let wind rewrite the rider's physiology.
 *
 * A proposal is only raised when it beats the standing threshold by
 * [minimprovementRatio], which keeps day-to-day noise from nudging the number,
 * and it is never applied automatically — the rider accepts or rejects it.
 */
class ThresholdDetector(
    private val windowSeconds: Int = 20 * 60,
    private val ftpFactor: Double = 0.95,
    private val minImprovementRatio: Double = 1.02,
) {
    fun detect(
        samples: List<RideSample>,
        current: AthleteThresholds,
    ): ThresholdProposal {
        val bestPower =
            bestRollingAverage(
                samples.map { sample ->
                    sample.powerWatts?.takeIf { sample.powerSource == PowerSource.MEASURED }?.toDouble()
                },
            )
        val ftpCandidate = bestPower?.let { (it * ftpFactor).toInt() }
        val lthrCandidate = bestRollingAverage(samples.map { it.heartRateBpm?.toDouble() })?.toInt()

        return ThresholdProposal(
            ftpWatts = ftpCandidate?.takeIf { beats(it, current.ftpWatts) },
            lthrBpm = lthrCandidate?.takeIf { beats(it, current.lthrBpm) },
        )
    }

    private fun beats(
        candidate: Int,
        currentValue: Int?,
    ): Boolean = currentValue == null || candidate >= currentValue * minImprovementRatio

    /**
     * Best [windowSeconds]-long average over a 1 Hz series, treating null as a
     * break: a window containing a gap is not a sustained effort.
     */
    private fun bestRollingAverage(series: List<Double?>): Double? {
        if (series.size < windowSeconds) return null
        var best: Double? = null
        var windowSum = 0.0
        var validCount = 0

        for (index in series.indices) {
            series[index]?.let {
                windowSum += it
                validCount++
            }
            if (index >= windowSeconds) {
                series[index - windowSeconds]?.let {
                    windowSum -= it
                    validCount--
                }
            }
            if (index >= windowSeconds - 1 && validCount == windowSeconds) {
                val average = windowSum / windowSeconds
                if (best == null || average > best!!) best = average
            }
        }
        return best
    }
}
```

- [ ] **Step 4: Run them to verify they pass**

Run: `./gradlew :core:domain:test --tests "*ThresholdDetectorTest*"`
Expected: PASS.

- [ ] **Step 5: Lint and commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add core/domain/src
git commit -m "feat: detect FTP and LTHR candidates from a finished ride"
```

---

### Task 7: Compose training load into the tracker and persist it

**Files:**
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideTracker.kt`
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/history/RideRecord.kt`
- Modify: `core/database/src/main/java/com/speedevand/inkride/core/database/RideHistoryEntity.kt`
- Modify: `feature/history/data/src/main/java/com/speedevand/inkride/history/data/RoomRideHistoryRepository.kt`
- Modify: `feature/tracking/data/src/main/java/com/speedevand/inkride/tracking/data/TrackingDataModule.kt`
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideTrackerTest.kt`

**Interfaces:**
- Consumes: `TrainingLoadCalculator`, `ThresholdDetector`, `AthleteThresholds.from`.
- Produces: `TrackingState.trainingMetrics: TrainingMetrics`; `RideRecord` training columns populated at finish.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `training load rides along in the tracking state`() = runTest {
    settingsRepository.set(UserSettings(weightKg = 75, age = 30, ftpWatts = 200))
    tracker.start()

    repeat(120) { second ->
        sensorDataSource.emit(
            RideSensorSample(
                timestampMs = second * 1000L,
                latitude = 52.0 + 0.000072 * second,
                longitude = 0.0,
                speedFromGpsMps = 8.0,
                accuracyM = 4.0f,
            ),
        )
    }

    assertThat(tracker.state.value.trainingMetrics.workKj).isGreaterThan(0.0)
}

@Test
fun `finishing a ride writes the training aggregates and the thresholds in force`() = runTest {
    settingsRepository.set(UserSettings(weightKg = 75, age = 30, ftpWatts = 210, lthrBpm = 168))
    tracker.start()
    repeat(120) { second ->
        sensorDataSource.emit(
            RideSensorSample(
                timestampMs = second * 1000L,
                latitude = 52.0 + 0.000072 * second,
                longitude = 0.0,
                speedFromGpsMps = 8.0,
                accuracyM = 4.0f,
            ),
        )
    }
    tracker.stop()

    val saved = historyRepository.savedRides.single()
    // Pinned at ride time so a later FTP change cannot rewrite this ride's load.
    assertThat(saved.ftpAtRideWatts).isEqualTo(210)
    assertThat(saved.lthrAtRideBpm).isEqualTo(168)
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideTrackerTest"`
Expected: FAIL — `trainingMetrics` and the record fields do not exist.

- [ ] **Step 3: Extend the state and the record**

Add to `TrackingState`:

```kotlin
    // Training load for the ride so far. Fields inside are null when their
    // inputs are absent; see TrainingMetrics.
    val trainingMetrics: TrainingMetrics = TrainingMetrics(),
```

Add to `RideRecord` (all nullable, defaulting to null, plus `isComplete` from the reliability plan):

```kotlin
    val normalizedPowerWatts: Int? = null,
    val intensityFactor: Double? = null,
    val trainingStressScore: Double? = null,
    val hrTss: Double? = null,
    val trimp: Double? = null,
    val workKj: Double? = null,
    val decouplingPercent: Double? = null,
    val avgHeartRateBpm: Int? = null,
    val maxHeartRateBpm: Int? = null,
    val avgCadenceRpm: Int? = null,
    val maxPowerWatts: Int? = null,
    val powerSource: PowerSource? = null,
    // The thresholds in force when this ride happened. TSS is only meaningful
    // against them, so raising FTP later must not rewrite past training load.
    val ftpAtRideWatts: Int? = null,
    val lthrAtRideBpm: Int? = null,
```

Mirror them on `RideHistoryEntity` (the columns already exist from `MIGRATION_7_8`) and carry them through both mappers in `RoomRideHistoryRepository`.

- [ ] **Step 4: Drive the calculator from the tracker**

Add the constructor parameters after `heartRateFilter`:

```kotlin
    private val trainingLoadCalculator: TrainingLoadCalculator = TrainingLoadCalculator(),
    private val thresholdDetector: ThresholdDetector = ThresholdDetector(),
```

In the sample collector, right after `baseMetrics` is computed:

```kotlin
                        val thresholds = AthleteThresholds.from(latestSettings)
                        val training =
                            trainingLoadCalculator.process(
                                timestampMs = sample.timestampMs,
                                powerWatts = baseMetrics.powerWatts,
                                powerSource = baseMetrics.powerSource,
                                heartRateBpm = _state.value.metrics.heartRateBpm,
                                altitudeM = baseMetrics.altitudeM,
                                isMoving = baseMetrics.isMoving && !isPaused,
                                thresholds = thresholds,
                            )
```

and add `trainingMetrics = training` to the `current.copy(...)` inside `updateAndGet`.

Call `trainingLoadCalculator.reset()` everywhere `metricsCalculator.reset()` is already called.

In the finish path, populate the new `RideRecord` fields from `state.trainingMetrics` and from `AthleteThresholds.from(latestSettings)`, then run the detector when `latestSettings.autoDetectThresholds` is true, persisting any proposal to `pendingFtpWatts` / `pendingLthrBpm` through `userSettingsRepository`.

- [ ] **Step 5: Update Koin**

```kotlin
        singleOf(::TrainingLoadCalculator)
        singleOf(::ThresholdDetector)
```

and extend the `RideTracker` single with the two new `get()` arguments, updating the parameter-order comment above it.

- [ ] **Step 6: Run the tests**

Run: `./gradlew :core:domain:test :feature:history:data:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Lint and commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add core/domain/src core/database/src feature/history/data/src feature/tracking/data/src
git commit -m "feat: compose training load into tracking state and ride records"
```

---

### Task 8: Aerobic decoupling after the ride

**Files:**
- Create: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/training/DecouplingCalculator.kt`
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/training/DecouplingCalculatorTest.kt`

**Interfaces:**
- Consumes: `RideSample`.
- Produces: `DecouplingCalculator.calculate(samples: List<RideSample>): Double?`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `a steady ride decouples by roughly nothing`() {
    val samples =
        (0 until 3_600).map { second ->
            RideSample(timestampMs = second * 1000L, powerWatts = 200, powerSource = PowerSource.MEASURED, heartRateBpm = 150)
        }

    assertThat(DecouplingCalculator().calculate(samples)!!).isCloseTo(0.0, 0.5)
}

@Test
fun `drifting heart rate at constant power shows positive decoupling`() {
    val samples =
        (0 until 3_600).map { second ->
            // Same watts, heart rate climbing 150 -> 165 across the ride.
            RideSample(
                timestampMs = second * 1000L,
                powerWatts = 200,
                powerSource = PowerSource.MEASURED,
                heartRateBpm = 150 + second / 240,
            )
        }

    assertThat(DecouplingCalculator().calculate(samples)!!).isGreaterThan(3.0)
}

@Test
fun `estimated power yields no decoupling`() {
    val samples =
        (0 until 3_600).map { second ->
            RideSample(timestampMs = second * 1000L, powerWatts = 200, powerSource = PowerSource.ESTIMATED, heartRateBpm = 150)
        }

    assertThat(DecouplingCalculator().calculate(samples)).isNull()
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:domain:test --tests "*DecouplingCalculatorTest*"`
Expected: FAIL — "Unresolved reference: DecouplingCalculator".

- [ ] **Step 3: Write the calculator**

```kotlin
package com.speedevand.inkride.core.domain.tracking.training

import com.speedevand.inkride.core.domain.history.RideSample
import com.speedevand.inkride.core.domain.tracking.PowerSource

/**
 * Aerobic decoupling (Pw:Hr): how much the power-to-heart-rate ratio fades from
 * the first half of a ride to the second. A low number means the rider held
 * their aerobic efficiency; a high one means they drifted.
 *
 * Computed after the ride, never live: the halfway point is only known once the
 * ride ends. Requires measured power and heart rate together — from an
 * estimated power this would be the quotient of two uncertainties.
 */
class DecouplingCalculator(
    private val minSamplesPerHalf: Int = 300,
) {
    fun calculate(samples: List<RideSample>): Double? {
        val usable =
            samples.filter {
                it.powerSource == PowerSource.MEASURED && it.powerWatts != null && it.heartRateBpm != null
            }
        if (usable.size < minSamplesPerHalf * 2) return null

        val midpoint = usable.size / 2
        val firstRatio = ratio(usable.subList(0, midpoint)) ?: return null
        val secondRatio = ratio(usable.subList(midpoint, usable.size)) ?: return null
        if (firstRatio == 0.0) return null

        return (firstRatio - secondRatio) / firstRatio * 100.0
    }

    private fun ratio(half: List<RideSample>): Double? {
        if (half.isEmpty()) return null
        val averagePower = half.mapNotNull { it.powerWatts }.average()
        val averageHeartRate = half.mapNotNull { it.heartRateBpm }.average()
        if (averageHeartRate <= 0.0) return null
        return averagePower / averageHeartRate
    }
}
```

- [ ] **Step 4: Run them to verify they pass, then wire it in**

Run: `./gradlew :core:domain:test --tests "*DecouplingCalculatorTest*"`
Expected: PASS.

Then call it in the tracker's finish path alongside the threshold detector, on the same recovered sample list, and store the result in `RideRecord.decouplingPercent`.

- [ ] **Step 5: Lint and commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add core/domain/src
git commit -m "feat: compute aerobic decoupling after a ride"
```

---

### Task 9: Auto-lap

**Files:**
- Create: `core/domain/src/main/java/com/speedevand/inkride/core/domain/settings/AutoLapConfig.kt`
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/settings/UserSettings.kt`
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideTracker.kt`
- Modify: `feature/settings/presentation/src/main/java/com/speedevand/inkride/settings/presentation/SettingsScreen.kt`
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideTrackerTest.kt`

**Interfaces:**
- Consumes: the existing `RideTracker.recordLap()`.
- Produces: `AutoLapConfig(mode: AutoLapMode, distanceKm: Double?, intervalMinutes: Int?)`; `enum class AutoLapMode { OFF, DISTANCE, TIME }`; `UserSettings.autoLap: AutoLapConfig`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `a lap closes every whole kilometre when auto-lap is set to distance`() = runTest {
    settingsRepository.set(
        UserSettings(weightKg = 75, age = 30, autoLap = AutoLapConfig(AutoLapMode.DISTANCE, distanceKm = 1.0)),
    )
    tracker.start()

    // ~2.4 km at 8 m/s over 300 s.
    repeat(300) { second ->
        sensorDataSource.emit(
            RideSensorSample(
                timestampMs = second * 1000L,
                latitude = 52.0 + 0.000072 * second,
                longitude = 0.0,
                speedFromGpsMps = 8.0,
                accuracyM = 4.0f,
            ),
        )
    }

    assertThat(tracker.state.value.laps).hasSize(2)
}

@Test
fun `no laps are recorded when auto-lap is off`() = runTest {
    settingsRepository.set(UserSettings(weightKg = 75, age = 30))
    tracker.start()
    repeat(300) { second ->
        sensorDataSource.emit(
            RideSensorSample(
                timestampMs = second * 1000L,
                latitude = 52.0 + 0.000072 * second,
                longitude = 0.0,
                speedFromGpsMps = 8.0,
                accuracyM = 4.0f,
            ),
        )
    }

    assertThat(tracker.state.value.laps).isEmpty()
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideTrackerTest"`
Expected: FAIL — "Unresolved reference: AutoLapConfig".

- [ ] **Step 3: Add the config**

```kotlin
package com.speedevand.inkride.core.domain.settings

enum class AutoLapMode {
    OFF,
    DISTANCE,
    TIME,
}

/**
 * Automatic lap marking. [distanceKm] applies in DISTANCE mode, [intervalMinutes]
 * in TIME mode; the unused one is ignored rather than validated away, so
 * switching modes keeps the other value for when the rider switches back.
 */
data class AutoLapConfig(
    val mode: AutoLapMode = AutoLapMode.OFF,
    val distanceKm: Double? = null,
    val intervalMinutes: Int? = null,
)
```

Add `val autoLap: AutoLapConfig = AutoLapConfig(),` to `UserSettings`, and carry the three columns already added by `MIGRATION_7_8` through `UserSettingsEntity` and its mappers.

- [ ] **Step 4: Trigger it from the tracker**

Add the field:

```kotlin
    // Ride-total distance / moving time at which the next automatic lap is due.
    private var nextAutoLapDistanceKm: Double? = null
    private var nextAutoLapMovingSeconds: Long? = null
```

Reset both in `startNewSession()` and `stop()`. After `evaluateAlerts(...)` in the sample collector:

```kotlin
                        evaluateAutoLap(newState.status, newState.metrics)
```

```kotlin
    /**
     * Closes a lap each time the ride crosses the next auto-lap boundary. Uses
     * the existing recordLap(), so an automatic lap is indistinguishable from a
     * manual one in the breakdown — which is what a rider expects.
     */
    private fun evaluateAutoLap(
        status: TrackingStatus,
        metrics: RideMetrics,
    ) {
        if (status != TrackingStatus.TRACKING) return
        when (latestSettings.autoLap.mode) {
            AutoLapMode.OFF -> return

            AutoLapMode.DISTANCE -> {
                val step = latestSettings.autoLap.distanceKm?.takeIf { it > 0.0 } ?: return
                val due = nextAutoLapDistanceKm ?: step.also { nextAutoLapDistanceKm = it }
                if (metrics.distanceKm >= due) {
                    recordLap()
                    nextAutoLapDistanceKm = due + step
                }
            }

            AutoLapMode.TIME -> {
                val step = latestSettings.autoLap.intervalMinutes?.takeIf { it > 0 }?.times(60L) ?: return
                val due = nextAutoLapMovingSeconds ?: step.also { nextAutoLapMovingSeconds = it }
                if (metrics.movingTimeSeconds >= due) {
                    recordLap()
                    nextAutoLapMovingSeconds = due + step
                }
            }
        }
    }
```

- [ ] **Step 5: Add the settings control**

Add a "Auto lap" section to `SettingsScreen` with a mode selector and the matching numeric input, following the existing alert-threshold controls in the same file.

- [ ] **Step 6: Run the tests**

Run: `./gradlew :core:domain:test :feature:settings:presentation:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Lint and commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add core/domain/src core/database/src feature/settings/src feature/settings/data/src feature/settings/presentation/src
git commit -m "feat: add automatic lap marking by distance or time"
```

---

### Task 10: Training page on the dashboard

**Files:**
- Modify: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/components/MetricsPager.kt`
- Modify: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/model/RideMetricsUi.kt`
- Modify: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DashboardContract.kt`
- Modify: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DashboardViewModel.kt`
- Modify: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DashboardTestTags.kt`
- Test: `feature/dashboard/presentation/src/test/kotlin/com/speedevand/inkride/dashboard/presentation/model/TrainingMetricsUiTest.kt`
- Test: `feature/dashboard/presentation/src/androidTest/kotlin/com/speedevand/inkride/dashboard/presentation/TrainingPageTest.kt`

**Interfaces:**
- Consumes: `TrackingState.trainingMetrics`, `RideMetrics.isSpeedStale`, `RideMetrics.powerSource`.
- Produces: `DashboardPage.TRAINING`; `TrainingMetricsUi` with pre-formatted strings.

- [ ] **Step 1: Write the failing formatter test**

```kotlin
@Test
fun `absent training metrics render as double dashes rather than zeros`() {
    val ui = TrainingMetricsUi.from(TrainingMetrics(), MeasurementUnits.METRIC)

    assertThat(ui.normalizedPower).isEqualTo("--")
    assertThat(ui.intensityFactor).isEqualTo("--")
    assertThat(ui.trainingStressScore).isEqualTo("--")
}

@Test
fun `present training metrics are rounded for a slow display`() {
    val ui =
        TrainingMetricsUi.from(
            TrainingMetrics(
                normalizedPowerWatts = 243,
                intensityFactor = 0.7843,
                trainingStressScore = 64.4,
                vamMetersPerHour = 842.7,
                workKj = 612.3,
            ),
            MeasurementUnits.METRIC,
        )

    assertThat(ui.normalizedPower).isEqualTo("243")
    assertThat(ui.intensityFactor).isEqualTo("0.78")
    assertThat(ui.trainingStressScore).isEqualTo("64")
    assertThat(ui.vam).isEqualTo("843")
    assertThat(ui.work).isEqualTo("612")
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :feature:dashboard:presentation:testDebugUnitTest`
Expected: FAIL — "Unresolved reference: TrainingMetricsUi".

- [ ] **Step 3: Add the UI model**

Create `TrainingMetricsUi` beside `RideMetricsUi`, holding **pre-formatted strings**, matching the existing file's conventions. This is what keeps E-Ink redraws down: recomposition happens when the rendered text changes, not when a double wobbles in the sixth decimal. Round NP to the watt, IF to two decimals, TSS, VAM and work to the unit. Every null renders as `"--"`.

- [ ] **Step 4: Add the page**

In `MetricsPager.kt`, add `TRAINING` to the `DashboardPage` enum and to `visibleDashboardPages(settings)`, gated on a new `showTrainingMetrics` setting. Add a `TrainingMetricsPage` composable laying out six `MetricItem`s — NP, IF, zone, VAM, work, TSS — following `SecondaryMetricsPage`'s structure exactly.

Mark estimated power in the UI: when `metrics.powerSource == PowerSource.ESTIMATED`, the power readout carries the estimate marker the spec requires, so a modelled number is never mistaken for a measured one. Render `isSpeedStale` as `"--"` on the speed hero.

- [ ] **Step 5: Write and run the screen test**

Mirror the existing dashboard screen tests: render the root over fakes bound with `KoinTestRule`, swipe to the training page, assert NP shows `"--"` with no power meter and a number with one.

Run: `./gradlew :feature:dashboard:presentation:testDebugUnitTest :feature:dashboard:presentation:connectedDebugAndroidTest`
Expected: PASS.

- [ ] **Step 6: Lint and commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add feature/dashboard/presentation/src core/domain/src
git commit -m "feat: add the training metrics page to the dashboard"
```

---

### Task 11: Training section on the ride detail screen

**Files:**
- Modify: `feature/history/presentation/src/main/java/com/speedevand/inkride/history/presentation/RideDetailContract.kt`
- Modify: `feature/history/presentation/src/main/java/com/speedevand/inkride/history/presentation/RideDetailViewModel.kt`
- Modify: `feature/history/presentation/src/main/java/com/speedevand/inkride/history/presentation/RideDetailScreen.kt`
- Create: `feature/history/presentation/src/main/java/com/speedevand/inkride/history/presentation/ZoneBars.kt`
- Modify: `feature/history/presentation/src/main/java/com/speedevand/inkride/history/presentation/RideDetailTestTags.kt`
- Test: `feature/history/presentation/src/test/kotlin/com/speedevand/inkride/history/presentation/RideDetailViewModelTest.kt`
- Test: `feature/history/presentation/src/androidTest/kotlin/com/speedevand/inkride/history/presentation/RideDetailTrainingTest.kt`

**Interfaces:**
- Consumes: `RideRecord` training fields (Task 7), `RideSampleRepository`, `DecouplingCalculator`.
- Produces: a training section and a threshold-proposal card on ride detail.

- [ ] **Step 1: Write the failing ViewModel test**

```kotlin
@Test
fun `a ride recorded before training metrics existed shows them as unavailable`() = runTest {
    historyRepository.set(testRide(id = 1L, trainingStressScore = null, normalizedPowerWatts = null))

    viewModel.state.test {
        val state = awaitItem()
        // Nothing to backfill: the stream was never recorded. Absent, not zero.
        assertThat(state.training.trainingStressScore).isEqualTo("--")
        assertThat(state.training.hasAnyTrainingData).isFalse()
    }
}

@Test
fun `zone durations are read back from the sample stream`() = runTest {
    historyRepository.set(testRide(id = 1L, lthrAtRideBpm = 160))
    sampleRepository.set(
        rideId = 1L,
        samples = (0 until 600).map { RideSample(timestampMs = it * 1000L, heartRateBpm = 120) },
    )

    viewModel.state.test {
        val state = awaitItem()
        assertThat(state.training.secondsInHrZone[2]!!).isGreaterThan(0L)
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :feature:history:presentation:testDebugUnitTest`
Expected: FAIL — the state has no `training` block.

- [ ] **Step 3: Extend the ViewModel**

Add a `RideDetailTrainingUi` to the state carrying pre-formatted strings plus the zone maps. Load the ride's samples through `RideSampleRepository`, recompute the zone breakdown and decoupling from them, and fall back to `"--"` for every field the record has as null. Include a `hasAnyTrainingData: Boolean` so the screen can hide the whole section for rides that predate the feature instead of showing a wall of dashes.

Add a proposal card path: when `UserSettings.pendingFtpWatts` or `pendingLthrBpm` is set, expose it in the state with `AcceptFtpProposal` / `RejectFtpProposal` actions that write through `UserSettingsRepository` — accepting moves the pending value into `ftpWatts` and clears pending; rejecting clears pending only.

- [ ] **Step 4: Render the section**

Create `ZoneBars.kt` as a `Canvas`-based horizontal bar per zone, following `ElevationChart.kt`'s structure and using `DesignConstants` for sizing and the monochrome palette. In `RideDetailScreen`, add the tile row (TSS / IF / NP), the HR and power zone bars, the decoupling line and the proposal card, all in MMD components.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :feature:history:presentation:testDebugUnitTest :feature:history:presentation:connectedDebugAndroidTest`
Expected: PASS.

- [ ] **Step 6: Lint and commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add feature/history/presentation/src
git commit -m "feat: add the training breakdown to ride detail"
```

---

## Done when

- With a power meter paired, the dashboard shows live NP, IF and TSS; without one, those three read `--` while VAM, work and heart-rate zones still populate.
- An hour at FTP scores TSS 100; an hour at LTHR scores hrTSS 100.
- A detected FTP appears as a proposal the rider accepts or rejects, and never applies itself.
- Auto-lap closes laps at the configured distance or interval.
- Rides recorded before this feature show the training section as unavailable rather than as zeros.
- `./gradlew testDebugUnitTest :core:domain:test` and `./gradlew ktlintCheck` are green.
