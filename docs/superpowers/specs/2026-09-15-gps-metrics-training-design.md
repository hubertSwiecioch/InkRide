# Wiarygodność pomiaru GPS i metryki treningowe

Data: 2026-09-15
Status: zatwierdzony do implementacji

## Cel

Dwa powiązane cele w jednym cyklu:

1. Naprawić sześć defektów znalezionych w audycie warstwy GPS/metryk, przez które prędkość zamraża się w tunelu, auto-pauza bywa nieosiągalna, a kompas gaśnie po dwóch próbkach.
2. Wprowadzić metryki treningowe na poziomie dedykowanych komputerów rowerowych: moc z czujnika BLE, NP/IF/TSS, hrTSS, TRIMP, strefy HR i mocy, VAM, praca, decoupling, progi FTP/LTHR z auto-detekcją, auto-lap.

## Zakres

W zakresie: podprojekt #1 (wiarygodność pomiaru), wycinek #2 (BLE Cycling Power 0x1818), podprojekt #3 (metryki treningowe).

Poza zakresem, każde do własnego cyklu spec → plan → implementacja: prędkość z koła (CSC speed), radar Varia, temperatura, ClimbPro i przeliczanie trasy, eksport FIT, obciążenie treningowe CTL/ATL/TSB, VO2max, workouty strukturalne, segmenty, edytor pól danych.

Odrzucone trwale jako sprzeczne z `project_overview.md` (privacy-first, no cloud): LiveTrack, wykrywanie wypadku z powiadomieniem, synchronizacja chmurowa, mapy Connect, powiadomienia ze smartfona.

## Decyzje projektowe

| # | Decyzja | Uzasadnienie |
|---|---|---|
| D1 | Metryki treningowe dwutorowo: hrTSS/TRIMP z pasa HR, NP/IF/TSS wyłącznie z **mierzonej** mocy | `PowerEstimator` ma udokumentowane ±30–60 %, a NP podnosi moc do czwartej potęgi — błąd rośnie nieliniowo. TSS z estymaty wyglądałby profesjonalnie i był mylący. |
| D2 | Osobny `TrainingLoadCalculator`, nie rozbudowa `RideMetricsCalculator` | Kalkulator ma już 639 linii i dwie różne odpowiedzialności; metryki treningowe mają inny cykl życia (okno 30 s, progi, strefy). |
| D3 | Nowa tabela `ride_sample` @ 1 Hz zamiast rozszerzania `ride_track_point` | `ride_track_point` wymaga pozycji, więc gubiłby HR w tunelu; osobna tabela odwzorowuje rekord FIT i nie rusza przetestowanej ścieżki GPX/mapy. |
| D4 | Progi ręcznie **i** z auto-detekcją, nigdy po cichu | Rider bez testu FTP dostaje sensowną wartość; rider z wynikiem z labu może go wpisać. Nadpisanie progu zmienia wszystkie pochodne, więc wymaga zgody. |
| D5 | Wiersz `ride_history` tworzony przy **starcie** (`isComplete = 0`) | Dziś zabicie procesu kasuje cały przejazd. Przy strumieniu 1 Hz to niedopuszczalne. |
| D6 | Znacznik czasu próbki zostaje czasem emisji (zegar ścienny) | Wariant ze znakowaniem czasem fixa mieszałby zegar GPS z `System.currentTimeMillis()`; jitter przy 1 Hz to <50 ms i nie obciąża dystansu (haversine, nie całka z prędkości). |
| D7 | FTP przy braku wartości **nie jest zgadywany** (IF/TSS pokazują `--`); LTHR domyśla się jako `0,9 × HRmax(Tanaka)` | Dla LTHR istnieje ugruntowana reguła kciuka, dla FTP nie ma takiej, która nie byłaby zmyślona. |
| D8 | `ftpAtRideWatts` / `lthrAtRideBpm` zapisywane przy przejeździe | TSS ma sens tylko względem progu obowiązującego wtedy; zmiana FTP nie może przepisać historii. |

## Architektura

```
LocationManager ─┐
Sensor PRESSURE  ├─► AndroidRideSensorDataSource ─► RideSampleAssembler ─► RideSensorSample ─┐
Sensor ROT_VEC   │        (bramka jakości)          (Kalman + kaskada kursu)                 │
GnssStatus ──────┘                                                                            ▼
                                                                          RideMetricsCalculator ─► RideMetrics
BLE 0x180D (HR)  ─┐                                                                           │  (+isMoving,
BLE 0x1816 (CSC) ─├─► AndroidBleSensorDataSource ─► BleSample ────────────────────────────────┤   isSpeedStale,
BLE 0x1818 (moc) ─┘                                      │                                    │   powerSource)
                                                          ▼                                    ▼
                                            TrainingLoadCalculator ─────► TrainingMetrics ─┐
                                                                                            ▼
                                                              RideTracker ─► TrackingState ─► UI
                                                                    │
                                                                    ├─► ride_history (przy starcie, isComplete=0)
                                                                    ├─► ride_sample (partie co 60 s, 1 Hz)
                                                                    └─► ThresholdDetector (przy zapisie)
```

Granice pozostają bez zmian: `:core:domain` nie wie nic o Androidzie, `TrainingLoadCalculator` nie wie nic o `RideMetricsCalculator` i odwrotnie — składane są dopiero w `RideTracker`.

## 1. Warstwa pomiarowa — sześć poprawek

Poprzedni cykl (`2026-07-08-gps-sensor-precision-design.md`) wprowadził filtr Kalmana, kurs z `TYPE_ROTATION_VECTOR` i odrzucanie outlierów HR. Ten rozdział domyka defekty, które w tamtej warstwie zostały.

### 1.1 Znacznik czasu próbki

**Problem.** `RideSampleAssembler.assemble()` liczy `maxOf(gpsTimestampMs, pressureTimestampMs, headingTimestampMs, nowMs)`, a wszystkie trzy znaczniki są wcześniejszymi odczytami `System.currentTimeMillis()` — `nowMs` zawsze wygrywa. Trzy parametry są martwe, a komentarz w `AndroidRideSensorDataSource.kt:99-101` obiecuje atrybucję czasu, której nie ma.

**Rozwiązanie.** Usunąć parametry `gpsTimestampMs`, `pressureTimestampMs`, `headingTimestampMs` z `assemble()`; `timestampMs = nowMs`. Usunąć pola `lastGpsTimestampMs`, `lastPressureTimestampMs`, `lastHeadingTimestampMs` oraz mylący komentarz. `lastPressureEmitTimestampMs` zostaje — realnie limituje emisję do 2 Hz.

Patrz D6 dla odrzuconego wariantu ze znakowaniem czasem fixa.

### 1.2 Wygaszanie prędkości przy dziurze GPS

**Problem.** `speedMps` startuje z `lastReportedSpeedMps` i jest nadpisywana wyłącznie na próbkach z pozycją. Gdy fixy przestają przychodzić, próbki barometryczne lecą dalej ~2 Hz i niosą ostatnią prędkość w nieskończoność: prędkościomierz kłamie, a auto-pauza nie ma jak zejść poniżej progu.

**Rozwiązanie.** `RideMetricsCalculator` dostaje pole `lastLocationSampleAtMs` i próg `speedValidityMs = 3000`. Na próbce bez pozycji, gdy `sample.timestampMs - lastLocationSampleAtMs > speedValidityMs`, `lastReportedSpeedMps` jest zerowane, a `RideMetrics` niesie `isSpeedStale: Boolean = true`. UI pokazuje wtedy `--`.

Wartości pochodne pozostają nietknięte: `maxSpeedMps` aktualizuje się tylko na próbkach lokalizacyjnych, a średnia liczy się z dystansu i czasu w ruchu.

### 1.3 `minDistance` w żądaniu lokalizacji

**Problem.** `requestLocationUpdates(..., 1000L, 2.0f, ...)` throttluje dostawy do momentu przemieszczenia o 2 m. Stojący rower może nie dostawać callbacków w ogóle, więc ochrona przed dryfem, ponowne kotwiczenie wysokości i auto-pauza tracą wejście.

**Rozwiązanie.** `minDistance` na `0f`. Kalkulator ma już bogatszą ochronę kontekstową (licznik 5 próbek, 2 potwierdzenia ruchu, próg skalowany dokładnością), więc sprzętowa bramka tylko ją dublowała, gubiąc przy tym dane. Koszt: więcej callbacków na postoju — tyle, ile robi każdy dedykowany komputer rowerowy przy zapisie 1 Hz.

### 1.4 Jedno źródło prawdy o ruchu

**Problem.** Kalkulator uznaje ruch także po dystansie (`effectiveSegmentDistanceM > combinedAccuracyM * 0.5`), ale raportuje wtedy `speedMpsFromGps`, który może wynosić np. 1,0 km/h. `RideTracker.evaluateAutoPause` patrzy wyłącznie na `currentSpeedKmh` i przy progu 1,5 km/h zacznie odliczać do auto-pauzy, mimo że dystans się nalicza.

**Rozwiązanie.** `RideMetrics` zyskuje `isMoving: Boolean` wprost z `isActuallyMoving`. `evaluateAutoPause` konsumuje `metrics.isMoving` zamiast wnioskować z prędkości. Histereza zostaje po stronie wznowienia:

- pauza: `!isMoving` utrzymane przez `autoPauseDelayMs` (3 s),
- wznowienie: `isMoving && currentSpeedKmh > autoResumeSpeedKmh` (2,5 km/h).

### 1.5 Kaskada kursu

**Problem.** `PositionKalmanFilter` liczy `bearingDegrees` z wektora prędkości i wyrzuca go — `RideSampleAssembler` czyta tylko GPS COG i kompas. W kalkulatorze fallback sięga jedną próbkę wstecz (`sample.bearingDegrees ?: previous.bearingDegrees`), więc dwie próbki bez kursu gaszą kompas.

**Rozwiązanie.** Trójstopniowa kaskada w `RideSampleAssembler`:

1. GPS course-over-ground, gdy `speedMps >= 2.0`,
2. kurs z wektora prędkości Kalmana, gdy `speedMps > 0.5`,
3. wygładzony kurs z `HeadingSmoother`.

W `RideMetricsCalculator` fallback o jedną próbkę ustępuje ostatniemu znanemu kursowi z własnym oknem ważności (`bearingValidityMs = 5000`).

### 1.6 Jednorazowy fix pozycji

**Problem.** `AndroidCurrentLocationProvider.requestFreshFix()` używa `requestSingleUpdate`, deprecated od API 30.

**Rozwiązanie.** Na API ≥ 30 `LocationManager.getCurrentLocation(provider, CancellationSignal, executor, consumer)` z natywnym anulowaniem; na 26–29 dotychczasowa ścieżka z `Handler.postDelayed`. `minSdk` projektu to 26, więc rozgałęzienie jest konieczne.

### 1.7 Auto-lap

`AutoLapConfig(mode: OFF | DISTANCE | TIME, distanceKm: Double?, intervalMinutes: Int?)` obok istniejącego `AlertConfig` w `UserSettings`. `RideTracker` po aktualizacji metryk sprawdza przekroczenie kolejnej wielokrotności progu (dystans całkowity albo czas w ruchu) i woła istniejące `recordLap()`. Logika okrążeń i ich zapisu już istnieje i jest przetestowana — dokładany jest wyłącznie wyzwalacz.

## 2. BLE Cycling Power (0x1818)

### 2.1 Parsowanie

Do `BleGatt.kt`: `CYCLING_POWER_SERVICE = 0x1818`, `CYCLING_POWER_MEASUREMENT = 0x2A63`. Obok `parseHeartRate` staje `parseCyclingPower` — wolna funkcja na `ByteArray`, testowalna bez Androida.

Format 0x2A63: `uint16 flags`, następnie **zawsze** `sint16 instantaneous power` (waty). Czytane pola opcjonalne:

- bit 0 — `uint8 pedal power balance` (procent × 2),
- bit 5 — `crank revolution data` (`uint16` obroty + `uint16` czas zdarzenia w 1/1024 s).

Crank revolution data oznacza, że **miernik mocy dostarcza kadencję bez osobnego czujnika CSC**. Dekodowanie kroku korby jest identyczne jak w `CscCadenceTracker`, więc wydzielany jest z niego `CrankRevolutionTracker` (obsługa zawijania na `0x10000`) używany przez oba parsery.

Pakiet uszkodzony, ucięty lub z nieznanymi flagami zwraca `null` — nigdy zgadniętej wartości, zgodnie z konwencją `parseHeartRate`.

### 2.2 Kontrakt źródła danych

`connect(hrmAddress, cadenceAddress)` ma dwa sloty; trzeci rozsadziłby sygnaturę. Zamiana na obiekt wartości:

```kotlin
data class PairedSensors(
    val hrmAddress: String? = null,
    val cadenceAddress: String? = null,
    val powerAddress: String? = null,
)

interface BleSensorDataSource {
    fun observeSamples(): Flow<BleSample>
    fun connect(sensors: PairedSensors)
    fun disconnect()
}
```

Idempotencja `connect` bez zmian. `BleSensorType` += `POWER`, dzięki czemu istniejący ekran parowania obsłuży nowy typ bez przebudowy.

`BleSample` += `powerWatts: Int?`, `pedalBalanceLeftPercent: Int?`, `powerUpdatedAtMs: Long?`. Ostatnie pole istnieje z tego samego powodu co `cadenceUpdatedAtMs`: miernik milknie przy zatrzymanej korbie, a zamrożone waty kłamałyby jak zamrożona prędkość z 1.2. Reużywany jest wzorzec `cadenceOrZeroIfStale` (timeout 3 s).

Zasięg zmiany sygnatury: `AndroidBleSensorDataSource`, `FakeBleSensorDataSource` (`:core:testing`), `BleSensorsViewModel`, wiązanie w `RideTracker.launchCollection()`, `UserSettings.pairedPowerAddress`.

### 2.3 Wybór źródła mocy

```kotlin
enum class PowerSource { MEASURED, ESTIMATED }
```

`RideMetricsCalculator.process()` przyjmuje opcjonalną moc zmierzoną. Gdy jest — `powerWatts` pochodzi z czujnika, a `PowerEstimator` nie jest wołany. Gdy jej nie ma — zachowanie bez zmian. `RideMetrics` niesie `powerSource`.

Moc chwilowa z miernika jest szarpana (±40 W między obrotami korby). Do wyświetlania idzie średnia 3-sekundowa (jak w dedykowanych komputerach), do NP i pracy w kJ — surowa wartość 1 Hz. Uśrednianie przed podniesieniem do czwartej potęgi zaniżyłoby NP.

## 3. `TrainingLoadCalculator`

Nowy pakiet `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/training/`. Komponent nie wie nic o Androidzie ani o `RideMetricsCalculator`.

```kotlin
class TrainingLoadCalculator {
    fun process(
        timestampMs: Long,
        powerWatts: Int?,
        powerSource: PowerSource?,
        heartRateBpm: Int?,
        altitudeM: Double?,
        isMoving: Boolean,
        thresholds: AthleteThresholds,
    ): TrainingMetrics

    fun reset()
}

data class AthleteThresholds(
    val ftpWatts: Int?,
    val lthrBpm: Int?,
    val ageForHrZones: Int,
)
```

### 3.1 Wzory

| Metryka | Wzór | Okno |
|---|---|---|
| NP | `⁴√(śr(P₃₀⁴))`, gdzie `P₃₀` = ruchoma średnia 30 s | cały czas w ruchu |
| IF | `NP / FTP` | — |
| TSS | `s × NP × IF / (FTP × 3600) × 100` | — |
| hrTSS | `(s / 3600) × HRIF² × 100`, `HRIF = śrHR_w_ruchu / LTHR` | — |
| TRIMP (Edwards) | `Σ minuty_w_strefie(i) × i`, `i = 1..5` | — |
| VAM | `przewyższenie_okna / godziny_okna` [m/h] | 60 s |
| Praca | `Σ P × Δt / 1000` [kJ] | — |
| Strefy HR | 5 stref z istniejącego `HeartRateZoneCalculator` (Tanaka, %HRmax) | — |
| Strefy mocy | 7 stref Coggana, progi %FTP: 55 / 75 / 90 / 105 / 120 / 150 | — |
| Decoupling | `((P/HR)₁ − (P/HR)₂) / (P/HR)₁ × 100` | po przejeździe |

Strumień wejściowy jest nierówny (GPS ~1 Hz, barometr ~2 Hz, BLE niezależnie), więc
`TrainingLoadCalculator` resampluje go wewnętrznie do siatki 1 Hz metodą zero-order hold, zanim
policzy jakiekolwiek okno. Bez tego 30-sekundowe okno NP zawierałoby zmienną liczbę próbek, a
wynik zależałby od częstotliwości czujników zamiast od wysiłku.

Czas akumuluje się wyłącznie przy `isMoving = true` — postój na światłach nie rozcieńcza NP ani nie dolicza minut do stref.

Decoupling wymaga **jednocześnie** mocy z `PowerSource.MEASURED` i tętna — z estymaty mocy byłby
ilorazem dwóch niepewności i nie jest liczony. Poza tym liczony jest **wyłącznie po przejeździe**,
z tabeli `ride_sample`: punkt podziału na połowy jest znany dopiero na końcu, więc na żywo nie da się go policzyć uczciwie. Zgodne z układem UI — decoupling żyje w `RideDetail`.

`hrTSS` w wariancie z LTHR wybrano zamiast TRIMP Banistera, bo ten drugi wymaga tętna spoczynkowego i współczynnika zależnego od płci — dwóch pól, których projekt nie zbiera.

### 3.2 Macierz dostępności

| Dostępne wejścia | Liczone | Pokazywane jako `--` |
|---|---|---|
| Miernik mocy + pas HR | wszystko | — |
| Tylko pas HR | hrTSS, TRIMP, strefy HR, VAM, praca* | NP, IF, TSS, strefy mocy |
| Tylko estymata mocy, bez pasa HR | VAM, praca* | NP, IF, TSS, hrTSS, TRIMP, strefy, decoupling |

\* Praca w kJ liczona z estymaty dziedziczy jej ±30–60 % i dostaje w UI znacznik szacunku. NP/IF/TSS nie powstają z estymaty nigdy (D1).

## 4. Progi FTP i LTHR

### 4.1 Przechowywanie

`UserSettings` += `ftpWatts: Int?`, `lthrBpm: Int?`, `autoDetectThresholds: Boolean = true`, `pendingFtpWatts: Int?`, `pendingLthrBpm: Int?`.

Wartości domyślne przy `null` — patrz D7: FTP nie jest zgadywany (IF i TSS pokazują `--`), LTHR domyśla się jako `0,9 × HRmax(Tanaka)` z jawnym oznaczeniem „oszacowany".

### 4.2 Auto-detekcja

`ThresholdDetector` w `:core:domain`, czysta funkcja uruchamiana raz przy zamykaniu przejazdu, na strumieniu `ride_sample`:

- **FTP** = najlepsza 20-minutowa średnia krocząca mocy × 0,95. Liczona wyłącznie z `PowerSource.MEASURED` — estymata nie ma prawa przestawiać progu.
- **LTHR** = najlepsza 20-minutowa średnia krocząca tętna.

Kandydat trafia do `pendingFtpWatts` / `pendingLthrBpm` i **nigdy nie nadpisuje wartości aktywnej po cichu** (D4). Propozycja publikuje się dopiero, gdy kandydat przebija zapisany próg o ≥ 2 % — to odsiewa szum dzień po dniu. Rider widzi ją w podsumowaniu przejazdu i w ustawieniach: przyjmij albo odrzuć. Odrzucenie czyści pole i nie wraca, dopóki nie pojawi się lepszy wynik.

Przy `autoDetectThresholds = false` detektor nie jest wołany.

## 5. Warstwa danych

### 5.1 Tabela `ride_sample`

```kotlin
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

Zapis dławiony do 1 Hz, wyłącznie w stanie `TRACKING`. `ride_track_point` pozostaje nietknięte i dalej obsługuje mapę oraz eksport GPX (D3).

Repozytorium `RideSampleRepository` w `:core:domain`, implementacja `RoomRideSampleRepository` w `:feature:history:data` — zgodnie z konwencją pozostałych repozytoriów.

### 5.2 Cykl życia przejazdu

Zmiana względem stanu obecnego (D5):

1. `RideTracker.startNewSession()` tworzy wiersz `ride_history` z `isComplete = 0` i zapamiętuje `rideId`.
2. Próbki buforowane w pamięci i zapisywane partiami co 60 s do `ride_sample` pod tym `rideId`.
3. `stop()` zapisuje ostatnią partię, uzupełnia agregaty (w tym metryki treningowe i `ftpAtRideWatts` / `lthrAtRideBpm`), ustawia `isComplete = 1`, uruchamia `ThresholdDetector`.
4. Przejazd krótszy niż `minSaveDistanceKm` jest usuwany (kaskada czyści `ride_sample` i `ride_track_point`).
5. Odzyskiwanie uruchamia `InkRideApp.onCreate()` przez jednorazowe wywołanie
   `RideTracker.recoverUnfinishedRides()` na scope trackera (nie w `init` singletonu — I/O w
   konstruktorze Koina blokowałoby start aplikacji). Metoda znajduje wiersze z `isComplete = 0`,
   domyka je z zapisanych próbek, jeśli przekroczyły `minSaveDistanceKm`, w przeciwnym razie usuwa.

Zasięg: `RoomRideHistoryRepository`, `RideHistoryDao`, `RideTracker`, `RideToHistoryE2ETest`.

Zapytania listujące historię muszą filtrować `isComplete = 1`, żeby trwający przejazd nie pojawiał się na liście.

### 5.3 Migracja 7 → 8

- `CREATE TABLE ride_sample` + indeks na `rideId`.
- `ride_history` += `normalizedPowerWatts INTEGER`, `intensityFactor REAL`, `trainingStressScore REAL`, `hrTss REAL`, `trimp REAL`, `workKj REAL`, `decouplingPercent REAL`, `avgHeartRateBpm INTEGER`, `maxHeartRateBpm INTEGER`, `avgCadenceRpm INTEGER`, `maxPowerWatts INTEGER`, `powerSource TEXT`, `ftpAtRideWatts INTEGER`, `lthrAtRideBpm INTEGER`, `isComplete INTEGER NOT NULL DEFAULT 1`.
- `user_settings` += `ftpWatts INTEGER`, `lthrBpm INTEGER`, `autoDetectThresholds INTEGER NOT NULL DEFAULT 1`, `pendingFtpWatts INTEGER`, `pendingLthrBpm INTEGER`, `pairedPowerAddress TEXT`, `autoLapMode TEXT NOT NULL DEFAULT 'OFF'`, `autoLapDistanceKm REAL`, `autoLapIntervalMinutes INTEGER`.

`isComplete` z domyślną `1` grandfather'uje istniejące przejazdy jako zamknięte — ten sam wzorzec co `hasCompletedOnboarding` w `MIGRATION_6_7`.

**Danych historycznych nie da się uzupełnić.** Strumień HR i mocy nigdy nie był zapisywany, więc starsze przejazdy zachowują `null` i pokazują `--` w sekcji treningowej. Migracja niczego nie zmyśla.

## 6. Prezentacja

### 6.1 Dashboard

Jedna nowa strona `MetricsPager` („Trening"), układ sześciopolowy: NP, IF, strefa, VAM, praca, TSS. Pola przełączalne w `UserSettings`, tak jak istniejące `showPower` / `showGrade`.

Zgodność z E-Ink wynika z istniejącego wzorca: `RideMetricsUi` trzyma **sformatowane stringi**, nie liczby, więc rekompozycja zachodzi dopiero przy zmianie wyświetlanej wartości. Zaokrąglenia: NP do wata, IF do 0,01, TSS do jedności. Komponenty MMD, `snap()`, bez overscrollu — zgodnie z `project_overview.md`.

Nowe stany wizualne:
- `--` przy `isSpeedStale` (1.2),
- znacznik szacunku przy metrykach z `PowerSource.ESTIMATED`.

### 6.2 RideDetail

Sekcja treningowa: kafle TSS / IF / NP, paski czasu w strefach HR i mocy (wzorzec `ElevationChart` — `Canvas` + `DesignConstants`), decoupling, karta propozycji progu z 4.2 (przyjmij / odrzuć).

### 6.3 Ustawienia

Pola FTP i LTHR, przełącznik auto-detekcji, konfiguracja auto-lapu; wpis parowania miernika mocy na istniejącym ekranie BLE.

## 7. Obsługa błędów

| Sytuacja | Zachowanie |
|---|---|
| Uszkodzony pakiet 0x2A63 | `parseCyclingPower` zwraca `null`; ostatnia wartość nie jest podmieniana |
| Miernik mocy milknie (>3 s) | `powerWatts` → 0 (wzorzec `cadenceOrZeroIfStale`) |
| Dziura GPS > 3 s | `isSpeedStale = true`, prędkość 0, UI `--`, auto-pauza wchodzi normalnie |
| Brak FTP | IF i TSS niedostępne, pokazywane `--`; reszta metryk liczy się dalej |
| Brak pasa HR | hrTSS, TRIMP i strefy HR niedostępne; NP/IF/TSS liczą się, jeśli jest miernik mocy |
| Zabicie procesu w trakcie jazdy | Przejazd domykany przy starcie z zapisanych próbek (5.2) |
| Migracja nie powiedzie się | Test instrumentalny 7 → 8 musi przejść przed mergem; brak fallbacku na `fallbackToDestructiveMigration` |

## 8. Testy

Zgodnie z `CLAUDE.md` każda funkcja dostaje oba rodzaje pokrycia. Fake'i lądują w `:core:testing`.

### 8.1 Jednostkowe (JVM)

- `parseCyclingPower` — pakiety wzorcowe, ucięte, z nieznanymi flagami, z balansem i bez, z crank data i bez.
- `CrankRevolutionTracker` — zawijanie licznika obrotów i czasu zdarzenia na `0x10000`.
- `TrainingLoadCalculator` — każdy wzór z tabeli 3.1 na ręcznie policzonych szeregach; NP przeciw znanej serii referencyjnej; zachowanie przy `isMoving = false`.
- `ThresholdDetector` — wykrycie najlepszego okna 20 min, próg 2 %, ignorowanie `PowerSource.ESTIMATED`.
- `RideMetricsCalculator` — regresje dla `isMoving`, `isSpeedStale`, okna ważności kursu, wyboru źródła mocy.
- `RideTracker` — auto-pauza na `isMoving`, wyzwalanie auto-lapu (dystans i czas), domykanie niedokończonego przejazdu.

### 8.2 Symulacje pełnego przejazdu

Do `RideSimulationBuilder` dochodzą dwa scenariusze zamykające poprawki 1.2 i 1.3:

- przejazd z kilkuminutową dziurą GPS (tunel) — prędkość ma zgasnąć do `--`, auto-pauza ma wejść,
  a dystans ma stać w miejscu **na czas trwania dziury**. Po powrocie fixa przemieszczenie w linii
  prostej jest doliczane i test musi tego oczekiwać: to udokumentowane, zamierzone zachowanie
  `RideMetricsCalculator` (najlepsza estymata przez lukę, spójna z czasem w ruchu, żeby średnia
  nie była zawyżana);
- dłuższy postój na światłach — brak fałszywego dystansu, brak fabrykowanego przewyższenia, auto-pauza i wznowienie.

Oba wchodzą do `RideMetricsCalculatorFullRideSimulationTest`.

### 8.3 Instrumentalne

- Migracja 7 → 8 wzorem `AppDatabaseMigrationTest`.
- `RideSampleDao` — zapis partiami, kaskada usunięcia, kolejność po `timestampMs`.
- Render nowej strony dashboardu (`:feature:dashboard:presentation/androidTest`, `KoinTestRule` + fake'i).
- Sekcja treningowa w `RideDetail`, w tym karta propozycji progu.
- Nowe pola ustawień.
- `:app/androidTest` — jeden test przecinający moduły: przejazd → zapis strumienia → odzyskanie niedokończonego przejazdu po symulowanej śmierci procesu.

### 8.4 Styl

Po każdej zmianie w Kotlinie: `./gradlew ktlintFormat`, następnie `./gradlew ktlintCheck`.

## 9. Kolejność realizacji

1. Poprawki 1.1 → 1.4 → 1.2 → 1.5 → 1.6 (najpierw usunięcie martwego kodu, potem kontrakt `isMoving`, dopiero na nim wygaszanie prędkości — inaczej te same warunki w kalkulatorze dotykane są dwa razy).
2. Migracja 7 → 8 i tabela `ride_sample`.
3. Cykl życia przejazdu z 5.2 (wiersz przy starcie, odzyskiwanie).
4. BLE Cycling Power (2.1 → 2.3).
5. `TrainingLoadCalculator` i progi (3, 4).
6. Auto-lap (1.7).
7. Prezentacja (6).

Etapy 1–3 są niezależne od 4–5 i mogą być mergowane osobno.
