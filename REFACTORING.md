# Code-Review & Refactoring-Leitfaden: Besseresser-App

Dieses Dokument fasst die Ergebnisse des umfassenden Code-Reviews der **Besseresser-App** zusammen. Der Fokus liegt auf **Android-spezifischen Best Practices**, moderner Architektur (**Jetpack Compose**, **Kotlin Coroutines & Flow**, **Room**, **MVVM/MVI**, **Material 3**) sowie **Performance, Sicherheit und Wartbarkeit**.

---

## Inhaltsverzeichnis
1. [Management Summary](#1-management-summary)
2. [Prioritäten-Matrix](#2-prioritäten-matrix)
3. [Detaillierte Findings & Best Practice Empfehlungen](#3-detaillierte-findings--best-practice-empfehlungen)
   - [3.1 UI & Jetpack Compose](#31-ui--jetpack-compose)
   - [3.2 Architektur & State Management (MVVM / Clean Architecture)](#32-architektur--state-management-mvvm--clean-architecture)
   - [3.3 Threading, Concurrency & Coroutines](#33-threading-concurrency--coroutines)
   - [3.4 Datenhaltung, Persistence & Room](#34-datenhaltung-persistence--room)
   - [3.5 Android Lifecycle & Lokalisierung](#35-android-lifecycle--lokalisierung)
   - [3.6 Dependency Injection & Testbarkeit](#36-dependency-injection--testbarkeit)
   - [3.7 Code-Style, Naming & Lokalisierung](#37-code-style-naming--lokalisierung)
4. [Empfohlene Roadmap & Nächste Schritte](#4-empfohlene-roadmap--nächste-schritte)

---

## 1. Management Summary

Die Besseresser-App verfügt über eine moderne technische Basis (Kotlin 2.0+, Jetpack Compose, Material 3, Type-Safe Navigation Compose, Room, DataStore, Koin). Die bestehende Unit-Test-Abdeckung für Serialisierung, Repositories und Fingerprint-Berechnungen ist solide.

Gleichzeitig weisen zentrale Bereiche der Codebasis deutliche Abweichungen von etablierten Android Best Practices auf.

---

## 2. Prioritäten-Matrix

| Priorität | Bereich | Finding | Auswirkung |
|---|---|---|---|
| **P2 (Mittel)** | Lifecycle / System | Manueller Locale ContextWrapper | Umgeht Android 13+ Per-App-Language-APIs |
| **P2 (Mittel)** | Testing & DI | Harte `Dispatchers.IO`-Referenzen & fehlende Dispatcher-Injection | Erschwerte deterministische Unit- & Coroutine-Tests |
| **P3 (Niedrig)** | Code-Qualität | Denglish-Bezeichner & String-basierte Magic Keys | Wartungsaufwand, Fehleranfälligkeit bei Refactorings |

---

## 3. Detaillierte Findings & Best Practice Empfehlungen

### 3.1 UI & Jetpack Compose

#### Finding 3.1.1: State & File-Erzeugung in `remember` ohne Re-Evaluation
- **Betroffene Dateien:** `MainScreen.kt` (Z. 101–109), `HistoryScreen.kt` (Z. 61–67)
- **Problem:**
  Dateiobjekte und FileProvider-URIs werden in `remember { ... }` ohne Dependencies erzeugt und führen I/O (`mkdirs()`) während der Initial-Composition aus:
  ```kotlin
  // Anti-Pattern
  val photoFile = remember {
      File(ctx.cacheDir, "photos").also { it.mkdirs() }
          .let { d -> File(d, "essen_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg") }
  }
  ```
  Wird ein Foto aufgenommen und verworfen oder ein zweites Foto gemacht, verweist `photoFile` immer noch auf denselben Zeitstempel-Dateinamen.
- **Empfohlene Best Practice:**
  Dateierstellung und Temp-File-Management sollten in eine dedizierte Helper-Klasse (`FileManager` / `PhotoCaptureManager`) ausgelagert werden, die erst bei tatsächlichem Auslösen des Kamera-Events eine neue Datei anlegt.

---

### 3.2 Architektur & State Management (MVVM / Clean Architecture)

#### Finding 3.2.1: Imperative ViewModel-Initialisierung (`startObserving()`)
- **Betroffene Dateien:** `HistoryViewModel.kt` (Z. 71), `HistoryScreen.kt` (Z. 51–53)
- **Problem:**
  In `HistoryScreen` wird über `LaunchedEffect(Unit) { viewModel.startObserving() }` eine Methode aufgerufen, um Flows zu starten.
- **Empfohlene Best Practice:**
  Nutzung von reaktiven State-Pipelines mit `stateIn`:
  ```kotlin
  val uiState: StateFlow<HistoryUiState> = combine(
      historyRepository.getAllFlow(),
      historyRepository.getDayFazitsFlow(),
      historyRepository.getWeekFazitsFlow(),
      _internalState
  ) { entries, dayFazits, weekFazits, internal ->
      // Transformationslogik
  }.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = HistoryUiState()
  )
  ```

---

### 3.3 Threading, Concurrency & Coroutines

#### Finding 3.3.1: Harte `Dispatchers.IO`-Referenzen in Klassen
- **Betroffene Dateien:** `HistoryRepository.kt`, `ImagePreprocessor.kt`, `BackupService.kt`, `ConfigViewModel.kt`
- **Problem:**
  Direkte Aufrufe von `withContext(Dispatchers.IO)` erschweren das Testen mit `StandardTestDispatcher` oder `UnconfinedTestDispatcher`.
- **Empfohlene Best Practice:**
  Definition eines injizierbaren `CoroutineDispatchers`-Providers oder Koin-Bindings:
  ```kotlin
  class CoroutineDispatchers(
      val main: CoroutineDispatcher = Dispatchers.Main,
      val io: CoroutineDispatcher = Dispatchers.IO,
      val default: CoroutineDispatcher = Dispatchers.Default
  )
  ```

---

### 3.4 Datenhaltung, Persistence & Room

#### Finding 3.4.1: Getrennte Verwaltung von SQLite-Daten und Foto-Dateien (Dangling Files)
- **Betroffene Dateien:** `HistoryRepository.kt`, `RoomDatabase.kt`
- **Problem:**
  Metadaten liegen in Room, Foto-Dateien im Dateisystem (`filesDir/history`). Beim Löschen, Bereinigen (`purgeOlderThanWeeks`) oder Importieren von Backups muss das Löschen von Dateien manuell synchron gehalten werden.
- **Empfohlene Best Practice:**
  Transaktionssichere Bereinigung:
  1. Room-Entity um Statusflags erweitern oder Bereinigungs-Job als periodischen `WorkManager`-Worker implementieren, der verwaiste Dateien (Dateien im Ordner ohne entsprechenden DB-Eintrag) automatisch löscht.

---

### 3.5 Android Lifecycle & Lokalisierung

#### Finding 3.5.1: Manuelle Locale-Hacks statt Android 13+ App-Sprachen API
- **Betroffene Dateien:** `MainActivity.kt` (`withAppLanguage`)
- **Problem:**
  Die App manipuliert `ConfigurationContext` und `Locale.setDefault(...)` manuell im `CompositionLocalProvider`, obwohl `locales_config.xml` bereits vorhanden ist.
- **Empfohlene Best Practice:**
  Migration auf `AppCompatDelegate.setApplicationLocales()` bzw. `android.app.LocaleManager` (ab Android 13, API 33). Dies wird vom Betriebssystem nativ unterstützt und integriert sich in die Systemeinstellungen von Android.

---

### 3.6 Dependency Injection & Testbarkeit

#### Finding 3.6.1: Fehlende UI- & Screenshot-Tests
- **Problem:**
  Es existieren derzeit Unit-Tests für Serialisierung, Repositories und Room, jedoch keine Compose-UI-Tests (`createComposeRule`) oder Screenshot-Regressionstests (z. B. mit Roborazzi oder Paparazzi).
- **Empfohlene Best Practice:**
  Hinzufügen von UI-Tests für zentrale User Flows:
  - Setup Flow (Ersteinrichtung der API)
  - Manuelle Nach-Erfassung (`ManualEntryDialog`)
  - Historien-Filterung und Detailansicht

---

### 3.7 Code-Style, Naming & Lokalisierung

#### Finding 3.7.1: Vermischung von Deutsch und Englisch (Denglish)
- **Betroffene Dateien:** Projektweit (z. B. `TrafficLight` vs. `erkannt`, `MainViewModel.showErkanntEdit`, `DayFazit`, `mealLines`)
- **Empfohlene Best Practice:**
  Einheitliche Naming Convention im Code: **Englisch für allen Code** (Klassen, Variablen, Methoden, Kommentare, DB-Tabellen), Lokalisierung (Deutsch/Englisch) ausschließlich in `strings.xml`.
  - Beispiel: `erkannt` -> `recognizedMeal`, `DayFazit` -> `DailySummary`, `TrafficLight` -> `TrafficLight`.

---

## 4. Empfohlene Roadmap & Nächste Schritte

### Phase 1: Architektur-Refactoring & Entkopplung (Mittelfristig)
1. **Reaktive State-Pipelines:** `startObserving()` durch deklarative `stateIn`-Pipelines ersetzen.
2. **Dispatcher Injection:** `CoroutineDispatchers` für saubere Testbarkeit injizierbar machen.

### Phase 2: Qualitäts- & Test-Offensive (Langfristig)
1. **Compose-Tests & Screenshot-Testing:** Integration von `androidx.compose.ui.test` und Roborazzi für Snapshot-Tests.
2. **Naming-Konsolidierung:** Schrittweise Vereinheitlichung auf englische Bezeichner in Datenmodellen und Repositories.
