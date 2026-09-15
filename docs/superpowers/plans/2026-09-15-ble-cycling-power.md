# BLE Cycling Power — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pair a Bluetooth cycling power meter (GATT 0x1818) and feed its measured watts through the metrics pipeline in place of `PowerEstimator`'s ±30–60 % physical model.

**Architecture:** Extends the existing GATT client rather than adding a new one. Parsing stays a free function on `ByteArray` next to `parseHeartRate`; the two-slot `connect(hrm, cadence)` becomes a `PairedSensors` value object; `RideMetricsCalculator` gains an optional measured-power input that short-circuits the estimator.

**Tech Stack:** Kotlin, Android BLE GATT, Koin, JUnit 5 + assertk, Compose + MMD for the pairing screen, ktlint.

**Spec:** `docs/superpowers/specs/2026-09-15-gps-metrics-training-design.md` (section 2)

**Depends on:** `2026-09-15-gps-measurement-reliability.md` — Task 8 of that plan introduces `PowerSource`, which this plan consumes.

## Global Constraints

- Pure-Kotlin modules (`:core:domain`) test via `./gradlew :core:domain:test`; Android modules via `./gradlew <module>:testDebugUnitTest`.
- JVM tests use JUnit 5 (`org.junit.jupiter.api.Test`) + assertk, backtick test names.
- After every Kotlin change: `./gradlew ktlintFormat` then `./gradlew ktlintCheck`.
- `minSdk` 26, `compileSdk` 36.
- New fakes belong in `:core:testing`.
- Use MMD components (`TextMMD`, `ButtonMMD`, …) before writing custom UI; E-Ink rules apply — no fluid animation, `snap()` specs.
- A malformed or truncated BLE packet yields `null`, never a guessed value. This matches `parseHeartRate`.

---

### Task 1: Parse Cycling Power Measurement (0x2A63)

**Files:**
- Modify: `feature/ble/data/src/main/java/com/speedevand/inkride/ble/data/BleGatt.kt`
- Test: `feature/ble/data/src/test/kotlin/com/speedevand/inkride/ble/data/BleGattTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `BleGatt.CYCLING_POWER_SERVICE`, `BleGatt.CYCLING_POWER_MEASUREMENT`; `parseCyclingPower(data: ByteArray): CyclingPowerResult?`; `data class CyclingPowerResult(val powerWatts: Int, val pedalBalanceLeftPercent: Int?, val crankRevolutions: Int?, val crankEventTime: Int?)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `parses instantaneous power from a minimal packet`() {
    // flags = 0x0000 (no optional fields), power = 250 W little-endian.
    val packet = byteArrayOf(0x00, 0x00, 0xFA.toByte(), 0x00)

    val result = parseCyclingPower(packet)

    assertThat(result).isNotNull()
    assertThat(result!!.powerWatts).isEqualTo(250)
    assertThat(result.pedalBalanceLeftPercent).isNull()
    assertThat(result.crankRevolutions).isNull()
}

@Test
fun `parses pedal balance when the flag is set`() {
    // flags bit 0 set, power = 200 W, balance = 0x64 (100 half-percent = 50%).
    val packet = byteArrayOf(0x01, 0x00, 0xC8.toByte(), 0x00, 0x64)

    val result = parseCyclingPower(packet)

    assertThat(result!!.pedalBalanceLeftPercent).isEqualTo(50)
}

@Test
fun `parses crank revolution data when the flag is set`() {
    // flags bit 5 set (0x0020), power = 200 W, revs = 1000, event time = 2048.
    val packet = byteArrayOf(0x20, 0x00, 0xC8.toByte(), 0x00, 0xE8.toByte(), 0x03, 0x00, 0x08)

    val result = parseCyclingPower(packet)

    assertThat(result!!.crankRevolutions).isEqualTo(1000)
    assertThat(result.crankEventTime).isEqualTo(2048)
}

@Test
fun `negative power from a coasting sensor is preserved as signed`() {
    // power = -5 W (0xFFFB), which some meters report while coasting.
    val packet = byteArrayOf(0x00, 0x00, 0xFB.toByte(), 0xFF.toByte())

    assertThat(parseCyclingPower(packet)!!.powerWatts).isEqualTo(-5)
}

@Test
fun `returns null for packets too short to carry power`() {
    assertThat(parseCyclingPower(byteArrayOf())).isNull()
    assertThat(parseCyclingPower(byteArrayOf(0x00, 0x00, 0x10))).isNull()
}

@Test
fun `returns power but no crank data when the packet is truncated mid-field`() {
    // Crank flag set but only two of the four crank bytes present.
    val packet = byteArrayOf(0x20, 0x00, 0xC8.toByte(), 0x00, 0xE8.toByte(), 0x03)

    val result = parseCyclingPower(packet)

    assertThat(result!!.powerWatts).isEqualTo(200)
    assertThat(result.crankRevolutions).isNull()
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :feature:ble:data:testDebugUnitTest --tests "*BleGattTest*"`
Expected: FAIL — "Unresolved reference: parseCyclingPower".

- [ ] **Step 3: Add the UUIDs and the parser**

In `BleGatt.kt`, add to the object:

```kotlin
    val CYCLING_POWER_SERVICE: UUID = sig("1818")
    val CYCLING_POWER_MEASUREMENT: UUID = sig("2a63")
```

Then, after `parseHeartRate`:

```kotlin
/** Decoded Cycling Power Measurement (0x2A63) fields InkRide consumes. */
internal data class CyclingPowerResult(
    val powerWatts: Int,
    val pedalBalanceLeftPercent: Int?,
    val crankRevolutions: Int?,
    val crankEventTime: Int?,
)

/**
 * Parses a Cycling Power Measurement (0x2A63) value.
 *
 * Layout: `uint16 flags`, then a mandatory `sint16` instantaneous power in
 * watts, then optional fields in flag order. Two are read here: pedal power
 * balance (bit 0, uint8 in half-percent units) and crank revolution data
 * (bit 5, uint16 cumulative revolutions + uint16 event time in 1/1024 s) —
 * the latter is why a power meter can supply cadence with no separate CSC
 * sensor. Fields between them that InkRide ignores must still be skipped, or
 * the crank offset lands on the wrong bytes.
 *
 * Power may legitimately be negative (some meters report a small negative
 * value while coasting), so it is read signed. Returns null on a packet too
 * short to carry power; a packet truncated inside an optional field yields
 * power with that field null, never a guessed value.
 */
internal fun parseCyclingPower(data: ByteArray): CyclingPowerResult? {
    if (data.size < 4) return null
    val flags = readUint16(data, 0)
    val powerWatts = readSint16(data, 2)

    var offset = 4
    val hasPedalBalance = (flags and 0x0001) != 0
    val pedalBalanceLeftPercent =
        if (hasPedalBalance) {
            if (data.size < offset + 1) return CyclingPowerResult(powerWatts, null, null, null)
            val raw = data[offset].toInt() and 0xFF
            offset += 1
            raw / 2
        } else {
            null
        }

    // Skip the optional fields between pedal balance and crank data, in flag
    // order, so the crank offset stays correct on meters that report them.
    if ((flags and 0x0004) != 0) offset += 2 // accumulated torque (uint16)
    if ((flags and 0x0010) != 0) offset += 6 // wheel revolution data (uint32 + uint16)

    val hasCrankData = (flags and 0x0020) != 0
    if (!hasCrankData || data.size < offset + 4) {
        return CyclingPowerResult(powerWatts, pedalBalanceLeftPercent, null, null)
    }
    return CyclingPowerResult(
        powerWatts = powerWatts,
        pedalBalanceLeftPercent = pedalBalanceLeftPercent,
        crankRevolutions = readUint16(data, offset),
        crankEventTime = readUint16(data, offset + 2),
    )
}
```

`readUint16` is currently a private member of `CscCadenceTracker`. Lift it (and add `readSint16`) to file-level private functions so both parsers share them:

```kotlin
private fun readUint16(
    data: ByteArray,
    offset: Int,
): Int = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

private fun readSint16(
    data: ByteArray,
    offset: Int,
): Int = readUint16(data, offset).toShort().toInt()
```

Update `CscCadenceTracker` to call the file-level helpers and delete its own copies.

- [ ] **Step 4: Run them to verify they pass**

Run: `./gradlew :feature:ble:data:testDebugUnitTest --tests "*BleGattTest*"`
Expected: PASS.

- [ ] **Step 5: Lint and commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add feature/ble/data/src
git commit -m "feat: parse Cycling Power Measurement packets"
```

---

### Task 2: Share crank decoding between the CSC and power parsers

Both profiles carry the same cumulative-revolutions/event-time pair with the same 0x10000 wrap, and duplicating that arithmetic is how the two drift apart.

**Files:**
- Modify: `feature/ble/data/src/main/java/com/speedevand/inkride/ble/data/BleGatt.kt`
- Test: `feature/ble/data/src/test/kotlin/com/speedevand/inkride/ble/data/BleGattTest.kt`

**Interfaces:**
- Consumes: `parseCyclingPower` (Task 1).
- Produces: `internal class CrankRevolutionTracker { fun cadenceFrom(revolutions: Int, eventTime: Int): Int?; fun reset() }`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `crank tracker needs a baseline and then derives rpm across the counter wrap`() {
    val tracker = CrankRevolutionTracker()

    // First reading establishes the baseline and yields nothing.
    assertThat(tracker.cadenceFrom(revolutions = 65_530, eventTime = 64_000)).isNull()

    // 6 revolutions later, 6144/1024 s = 6 s elapsed -> 60 rpm. Both counters wrap.
    val cadence = tracker.cadenceFrom(revolutions = 4, eventTime = 4_608)

    assertThat(cadence).isEqualTo(60)
}

@Test
fun `crank tracker returns null when no time has elapsed`() {
    val tracker = CrankRevolutionTracker()
    tracker.cadenceFrom(revolutions = 10, eventTime = 1_024)

    assertThat(tracker.cadenceFrom(revolutions = 12, eventTime = 1_024)).isNull()
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :feature:ble:data:testDebugUnitTest --tests "*BleGattTest*"`
Expected: FAIL — "Unresolved reference: CrankRevolutionTracker".

- [ ] **Step 3: Extract the tracker**

```kotlin
/**
 * Turns successive cumulative crank readings into an instantaneous cadence.
 * Shared by the CSC (0x2A5B) and Cycling Power (0x2A63) parsers, which carry
 * the identical field pair: cumulative revolutions and an event time in
 * 1/1024 s, both wrapping at 65536. Returns null until a baseline exists and
 * whenever no time has elapsed between readings.
 */
internal class CrankRevolutionTracker {
    private var lastRevolutions: Int? = null
    private var lastEventTime: Int? = null

    fun cadenceFrom(
        revolutions: Int,
        eventTime: Int,
    ): Int? {
        val previousRevolutions = lastRevolutions
        val previousEventTime = lastEventTime
        lastRevolutions = revolutions
        lastEventTime = eventTime
        if (previousRevolutions == null || previousEventTime == null) return null

        val deltaRevolutions = (revolutions - previousRevolutions + 0x10000) % 0x10000
        val deltaTime = (eventTime - previousEventTime + 0x10000) % 0x10000
        if (deltaTime <= 0) return null
        return (deltaRevolutions.toDouble() * 1024.0 * 60.0 / deltaTime.toDouble()).toInt()
    }

    fun reset() {
        lastRevolutions = null
        lastEventTime = null
    }
}
```

Rewrite `CscCadenceTracker.update` to delegate its crank arithmetic to an internal `CrankRevolutionTracker` instance instead of holding `lastCrankRevs` / `lastCrankEventTime` itself. Its public behaviour and return type must not change — the existing `BleGattTest` cases for CSC are the guard.

- [ ] **Step 4: Run the whole BLE test suite**

Run: `./gradlew :feature:ble:data:testDebugUnitTest`
Expected: PASS, including the pre-existing CSC cases.

- [ ] **Step 5: Lint and commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add feature/ble/data/src
git commit -m "refactor: share crank revolution decoding between CSC and power"
```

---

### Task 3: Replace the two-slot connect contract

**Files:**
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/ble/BleSensorDataSource.kt`
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/ble/BleSample.kt`
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/ble/BleSensorType.kt`
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/settings/UserSettings.kt`
- Modify: `feature/ble/data/src/main/java/com/speedevand/inkride/ble/data/AndroidBleSensorDataSource.kt`
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideTracker.kt`
- Modify: `core/testing/src/main/java/com/speedevand/inkride/core/testing/fakes/FakeBleSensorDataSource.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `data class PairedSensors(hrmAddress: String?, cadenceAddress: String?, powerAddress: String?)`; `BleSensorDataSource.connect(sensors: PairedSensors)`; `BleSample.powerWatts: Int?`, `.pedalBalanceLeftPercent: Int?`, `.powerUpdatedAtMs: Long?`; `BleSensorType.POWER`; `UserSettings.pairedPowerAddress: String?`.

- [ ] **Step 1: Add the value object and widen the sample**

In `BleSensorDataSource.kt`:

```kotlin
/**
 * The sensors the rider has paired. A value object rather than a parameter per
 * sensor: the contract gained a third slot with power and would gain more
 * (speed, radar) if every kind needed its own parameter.
 */
data class PairedSensors(
    val hrmAddress: String? = null,
    val cadenceAddress: String? = null,
    val powerAddress: String? = null,
) {
    val addresses: Set<String> get() = setOfNotNull(hrmAddress, cadenceAddress, powerAddress)
}

interface BleSensorDataSource {
    fun observeSamples(): Flow<BleSample>

    fun connect(sensors: PairedSensors)

    fun disconnect()
}
```

In `BleSample.kt`, add:

```kotlin
    val powerWatts: Int? = null,
    val pedalBalanceLeftPercent: Int? = null,
    // Wall-clock time of the last packet that actually carried power. Same role
    // as [cadenceUpdatedAtMs]: a meter goes quiet when the crank stops, and
    // frozen watts would lie exactly the way a frozen speed does.
    val powerUpdatedAtMs: Long? = null,
```

In `BleSensorType.kt`, add:

```kotlin
    /** Cycling Power meter — GATT service 0x1818. */
    POWER,
```

In `UserSettings.kt`, beside the other paired addresses:

```kotlin
    val pairedPowerAddress: String? = null,
```

- [ ] **Step 2: Update the implementation and every caller**

In `AndroidBleSensorDataSource`, change the signature to `override fun connect(sensors: PairedSensors)` and replace `val desired = setOfNotNull(hrmAddress, cadenceAddress)` with `val desired = sensors.addresses`. Keep the idempotence check unchanged.

In `RideTracker.launchCollection()`, replace the paired-address mapping:

```kotlin
                val bleConnectJob =
                    launch {
                        userSettingsRepository
                            .observeSettings()
                            .map {
                                PairedSensors(
                                    hrmAddress = it.pairedHrmAddress,
                                    cadenceAddress = it.pairedCadenceAddress,
                                    powerAddress = it.pairedPowerAddress,
                                )
                            }.distinctUntilChanged()
                            .collect { bleSensorDataSource.connect(it) }
                    }
```

Update `FakeBleSensorDataSource.connect` to the new signature, recording the whole `PairedSensors` so tests can assert on it.

- [ ] **Step 3: Compile and run the affected suites**

Run: `./gradlew :core:domain:test :feature:ble:data:testDebugUnitTest :feature:ble:presentation:testDebugUnitTest`
Expected: PASS. Fix any remaining call sites the compiler flags — `BleSensorsViewModel` is the likely one.

- [ ] **Step 4: Lint and commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add core/domain/src core/testing/src feature/ble/src feature/ble/data/src feature/ble/presentation/src
git commit -m "refactor: replace two-slot BLE connect with a PairedSensors object"
```

---

### Task 4: Stream measured power from the GATT client

**Files:**
- Modify: `feature/ble/data/src/main/java/com/speedevand/inkride/ble/data/AndroidBleSensorDataSource.kt`
- Test: `feature/ble/data/src/test/kotlin/com/speedevand/inkride/ble/data/AndroidBleSensorDataSourceConnectTest.kt`

**Interfaces:**
- Consumes: `parseCyclingPower`, `CrankRevolutionTracker` (Tasks 1–2), `PairedSensors`, `BleSample.powerWatts` (Task 3).
- Produces: power and pedal balance on the emitted `BleSample`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `a cycling power notification reaches the emitted sample`() = runTest {
    dataSource.connect(PairedSensors(powerAddress = POWER_ADDRESS))

    // flags = 0, power = 243 W.
    dataSource.deliverCharacteristicForTest(
        address = POWER_ADDRESS,
        uuid = BleGatt.CYCLING_POWER_MEASUREMENT,
        value = byteArrayOf(0x00, 0x00, 0xF3.toByte(), 0x00),
    )

    val sample = dataSource.observeSamples().first()
    assertThat(sample.powerWatts).isEqualTo(243)
    assertThat(sample.powerUpdatedAtMs).isNotNull()
}
```

Drive it through whatever seam the existing `AndroidBleSensorDataSourceConnectTest` already uses to deliver a characteristic (it tests HR and CSC the same way); do not add a new test-only method if one is already there.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :feature:ble:data:testDebugUnitTest`
Expected: FAIL — power is null; the power characteristic is not handled.

- [ ] **Step 3: Handle the characteristic**

Add the per-address tracker map beside `cadenceTrackers`:

```kotlin
    private val powerCrankTrackers = ConcurrentHashMap<String, CrankRevolutionTracker>()
```

Add the latest-value fields beside the existing ones:

```kotlin
    @Volatile
    private var latestPowerWatts: Int? = null

    @Volatile
    private var latestPedalBalanceLeftPercent: Int? = null

    @Volatile
    private var lastPowerUpdateAtMs: Long? = null
```

In `connect`, create a tracker per address alongside `cadenceTrackers[address] = CscCadenceTracker()`:

```kotlin
            powerCrankTrackers[address] = CrankRevolutionTracker()
```

In `disconnect`, clear it alongside the others, and null the three fields so a dropped meter never leaves stale watts behind.

In `handleCharacteristic`, add the branch:

```kotlin
                BleGatt.CYCLING_POWER_MEASUREMENT -> {
                    val result = parseCyclingPower(value) ?: return
                    latestPowerWatts = result.powerWatts
                    latestPedalBalanceLeftPercent = result.pedalBalanceLeftPercent
                    lastPowerUpdateAtMs = System.currentTimeMillis()
                    // A power meter that reports crank data supplies cadence too,
                    // so a rider with a meter needs no separate CSC sensor.
                    if (result.crankRevolutions != null && result.crankEventTime != null) {
                        val tracker = address?.let { powerCrankTrackers[it] }
                        tracker?.cadenceFrom(result.crankRevolutions, result.crankEventTime)?.let {
                            latestCadence = it
                            lastCadenceUpdateAtMs = System.currentTimeMillis()
                        }
                    }
                    emit()
                }
```

In `emit()`, add the three fields to the constructed `BleSample`.

Add `BleGatt.CYCLING_POWER_SERVICE` / `CYCLING_POWER_MEASUREMENT` to whatever list `enableNextNotification` walks, so notifications are actually subscribed.

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :feature:ble:data:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Lint and commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add feature/ble/data/src
git commit -m "feat: stream measured power and meter-derived cadence over GATT"
```

---

### Task 5: Prefer measured power over the estimate

**Files:**
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideMetrics.kt`
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideMetricsCalculator.kt`
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RideTracker.kt`
- Test: `core/domain/src/test/kotlin/com/speedevand/inkride/core/domain/tracking/RideMetricsCalculatorTest.kt`

**Interfaces:**
- Consumes: `PowerSource` (reliability plan, Task 8); `BleSample.powerWatts` (Task 4).
- Produces: `RideMetrics.powerSource: PowerSource`; `RideMetricsCalculator.process(sample, userSettings, isPaused, measuredPowerWatts: Int? = null)`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `measured power replaces the estimate and is reported as MEASURED`() {
    val calculator = RideMetricsCalculator()
    val settings = UserSettings(weightKg = 75, age = 30)

    calculator.process(
        RideSensorSample(timestampMs = 0L, latitude = 52.0, longitude = 0.0, speedFromGpsMps = 8.0, accuracyM = 4.0f),
        settings,
    )
    var metrics = RideMetrics()
    repeat(4) { step ->
        metrics =
            calculator.process(
                sample =
                    RideSensorSample(
                        timestampMs = 1_000L * (step + 1),
                        latitude = 52.0 + 0.000072 * (step + 1),
                        longitude = 0.0,
                        speedFromGpsMps = 8.0,
                        accuracyM = 4.0f,
                    ),
                userSettings = settings,
                measuredPowerWatts = 243,
            )
    }

    assertThat(metrics.powerWatts).isEqualTo(243)
    assertThat(metrics.powerSource).isEqualTo(PowerSource.MEASURED)
}

@Test
fun `power falls back to the estimator and is reported as ESTIMATED`() {
    val calculator = RideMetricsCalculator()
    val settings = UserSettings(weightKg = 75, age = 30)

    calculator.process(
        RideSensorSample(timestampMs = 0L, latitude = 52.0, longitude = 0.0, speedFromGpsMps = 8.0, accuracyM = 4.0f),
        settings,
    )
    val metrics =
        calculator.process(
            RideSensorSample(timestampMs = 1_000L, latitude = 52.000072, longitude = 0.0, speedFromGpsMps = 8.0, accuracyM = 4.0f),
            settings,
        )

    assertThat(metrics.powerSource).isEqualTo(PowerSource.ESTIMATED)
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.tracking.RideMetricsCalculatorTest"`
Expected: FAIL — no `measuredPowerWatts` parameter, no `powerSource`.

- [ ] **Step 3: Add the field and the input**

In `RideMetrics.kt`, after `averagePowerWatts`:

```kotlin
    // Where powerWatts came from. The UI marks estimated power as a model
    // output, and training load refuses to build NP/IF/TSS on top of it.
    val powerSource: PowerSource = PowerSource.ESTIMATED,
```

In `RideMetricsCalculator.process`, add the parameter:

```kotlin
    fun process(
        sample: RideSensorSample,
        userSettings: UserSettings,
        isPaused: Boolean = false,
        // Watts from a paired power meter. When present the physical model is
        // not consulted at all: a real measurement always beats a ±30-60 %
        // estimate dominated by unmeasured wind.
        measuredPowerWatts: Int? = null,
    ): RideMetrics {
```

Add the field beside `currentPowerWatts`:

```kotlin
    private var currentPowerSource: PowerSource = PowerSource.ESTIMATED
```

Reset it in `reset()` to `PowerSource.ESTIMATED`. Replace the `currentPowerWatts = powerEstimator.estimateWatts(...)` assignment with:

```kotlin
                if (measuredPowerWatts != null) {
                    currentPowerWatts = measuredPowerWatts.coerceAtLeast(0)
                    currentPowerSource = PowerSource.MEASURED
                } else {
                    currentPowerWatts =
                        powerEstimator.estimateWatts(
                            speedMps = speedMps,
                            accelerationMps2 = smoothedAccelMps2,
                            gradePercent = currentGrade,
                            userSettings = userSettings,
                            altitudeM = smoothedAltitudeM,
                        )
                    currentPowerSource = PowerSource.ESTIMATED
                }
```

Add `powerSource = currentPowerSource,` to the returned `RideMetrics(...)`.

- [ ] **Step 4: Feed it from the tracker**

In `RideTracker`, hold the latest measured watts from the BLE collector:

```kotlin
    @Volatile
    private var latestMeasuredPowerWatts: Int? = null
```

Set it inside the BLE collector (`bleJob`) from `ble.powerWatts`, applying the same staleness rule cadence uses — reuse `cadenceOrZeroIfStale`'s shape with `ble.powerUpdatedAtMs`. Pass it into the calculator:

```kotlin
                        val baseMetrics =
                            metricsCalculator.process(
                                sample = sample,
                                userSettings = latestSettings,
                                isPaused = isPaused,
                                measuredPowerWatts = latestMeasuredPowerWatts,
                            )
```

In `recordRideSample` (reliability plan, Task 9), replace `powerSource = null` with `powerSource = metrics.powerSource`.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :core:domain:test`
Expected: PASS.

- [ ] **Step 6: Lint and commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add core/domain/src
git commit -m "feat: prefer measured power over the physical estimate"
```

---

### Task 6: Pair a power meter from the sensors screen

**Files:**
- Modify: `feature/ble/presentation/src/main/java/com/speedevand/inkride/ble/presentation/BleSensorsContract.kt`
- Modify: `feature/ble/presentation/src/main/java/com/speedevand/inkride/ble/presentation/BleSensorsViewModel.kt`
- Modify: `feature/ble/presentation/src/main/java/com/speedevand/inkride/ble/presentation/BleSensorsScreen.kt`
- Modify: `feature/ble/presentation/src/main/java/com/speedevand/inkride/ble/presentation/BleSensorsTestTags.kt`
- Test: `feature/ble/presentation/src/androidTest/kotlin/com/speedevand/inkride/ble/presentation/BleSensorsScreenTest.kt`

**Interfaces:**
- Consumes: `BleSensorType.POWER`, `UserSettings.pairedPowerAddress` (Task 3).
- Produces: a power row on the sensors screen persisting to `pairedPowerAddress`.

- [ ] **Step 1: Write the failing screen test**

```kotlin
@Test
fun pairingAPowerMeterPersistsItsAddress() {
    fakeScanner.emit(BleDevice(name = "Assioma DUO", address = POWER_ADDRESS, type = BleSensorType.POWER))

    composeRule.onNodeWithTag(BleSensorsTestTags.deviceRow(POWER_ADDRESS)).performClick()

    composeRule.waitUntil {
        fakeSettingsRepository.current().pairedPowerAddress == POWER_ADDRESS
    }
}
```

Match the harness the existing tests in this file use (`KoinTestRule` plus the fakes from `:core:testing`); reuse their device-row tag helper rather than inventing a new one.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :feature:ble:presentation:connectedDebugAndroidTest`
Expected: FAIL — `BleSensorType.POWER` devices are filtered out of the list.

- [ ] **Step 3: Extend state, actions and screen**

Follow the exact pattern the screen already uses for `HEART_RATE` and `CADENCE`: add a `pairedPowerAddress` field to the state, a `PairPower` / `UnpairPower` action (named to match the existing action naming), a `POWER` section in the screen, and persist through `UserSettingsRepository` the same way the other two do. `BleSensorType.POWER` is already scanned for because `AndroidBleScanner` filters on the service UUIDs that `BleGatt` exposes — confirm the power service UUID is in that filter list and add it if not.

- [ ] **Step 4: Run the screen test and the unit tests**

Run: `./gradlew :feature:ble:presentation:testDebugUnitTest :feature:ble:presentation:connectedDebugAndroidTest`
Expected: PASS.

- [ ] **Step 5: Lint and commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add feature/ble/presentation/src feature/ble/data/src
git commit -m "feat: pair a cycling power meter from the sensors screen"
```

---

## Done when

- A paired power meter's watts appear on the dashboard and `powerSource` reads `MEASURED`.
- Unpairing the meter falls back to `PowerEstimator` with `powerSource` back to `ESTIMATED`.
- A meter that reports crank data supplies cadence with no CSC sensor paired.
- `./gradlew testDebugUnitTest :core:domain:test` and `./gradlew ktlintCheck` are green.
