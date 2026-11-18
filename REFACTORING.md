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

Gleichzeitig weisen zentrale Bereiche der Codebasis deutliche Abweichungen von etablierten Android Best Practices auf:
- **Resilienz & Datenhaltung:** `fallbackToDestructiveMigration()` bei Room-Datenbanken aktiv (Gefahr des kompletten Datenverlusts bei Updates) und `configChanges` in `MainActivity` deaktiviert Standard-Activity-Recreations.

---

## 2. Prioritäten-Matrix

| Priorität | Bereich | Finding | Auswirkung |
|---|---|---|---|
| **P1 (Hoch)** | Persistence | `fallbackToDestructiveMigration()` in Room | Verlust aller Verlaufsdaten bei Schema-Änderungen |
| **P1 (Hoch)** | Compose | Monolithische Composables (z. B. `MainScreen.kt` > 900 Zeilen) | Mangelnde Wiederverwendbarkeit, unnötige Recompositions |
| **P2 (Mittel)** | Lifecycle / System | `android:configChanges` & manueller Locale ContextWrapper | Umgeht Standard-Lifecycle, ignoriert Android 13+ Per-App-Language-APIs |
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

#### Finding 3.1.2: Monolithische Screen-Dateien und fehlende Dekomposition
- **Betroffene Dateien:** `MainScreen.kt` (930 Zeilen), `HistoryScreen.kt` (276 Zeilen)
- **Problem:**
  `MainScreen.kt` bündelt Kamera-Aufnahme, Review-Screen, Auswertungsansicht, Edit-Dialoge, Dropdown-Menüs und Floating Buttons in einer einzigen Datei. Sub-Composables erhalten bis zu 15–20 Callback- und State-Parameter.
- **Empfohlene Best Practice:**
  Aufteilung in kohärente, wiederverwendbare Teil-Composables in separate Dateien:
  - `ui/main/MainScreen.kt` (Root-Container & Event-Verteilung)
  - `ui/main/MainReviewView.kt`
  - `ui/main/MainEvaluationView.kt`
  - `ui/main/MainCtaBar.kt`

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

#### Finding 3.4.1: Destruktive Migration als Fallback
- **Betroffene Dateien:** `KoinModules.kt` (Z. 49)
- **Problem:**
  ```kotlin
  Room.databaseBuilder(androidContext(), AppDatabase::class.java, "besseresser.db")
      .fallbackToDestructiveMigration()
      .build()
  ```
- **Risiko:** Bei jedem Schema-Upgrade (z. B. Hinzufügen von Feldern in `HistoryEntity`) ohne explizite Migration löscht Room stillschweigend die komplette SQLite-Datenbank. Alle Mahlzeiten und Auswertungen der Nutzer gehen unwiderruflich verloren!
- **Empfohlene Best Practice:**
  1. `fallbackToDestructiveMigration()` in Production-Builds entfernen (`.fallbackToDestructiveMigration(false)`).
  2. Saubere `Migration(from, to)`-Klassen oder automatische Room-Migrationen (`AutoMigration`) definieren.
  3. Migrationstests mit `MigrationTestHelper` schreiben.

---

#### Finding 3.4.2: Getrennte Verwaltung von SQLite-Daten und Foto-Dateien (Dangling Files)
- **Betroffene Dateien:** `HistoryRepository.kt`, `RoomDatabase.kt`
- **Problem:**
  Metadaten liegen in Room, Foto-Dateien im Dateisystem (`filesDir/history`). Beim Löschen, Bereinigen (`purgeOlderThanWeeks`) oder Importieren von Backups muss das Löschen von Dateien manuell synchron gehalten werden.
- **Empfohlene Best Practice:**
  Transaktionssichere Bereinigung:
  1. Room-Entity um Statusflags erweitern oder Bereinigungs-Job als periodischen `WorkManager`-Worker implementieren, der verwaiste Dateien (Dateien im Ordner ohne entsprechenden DB-Eintrag) automatisch löscht.

---

### 3.5 Android Lifecycle & Lokalisierung

#### Finding 3.5.1: `android:configChanges` in `MainActivity`
- **Betroffene Dateien:** `app/src/main/AndroidManifest.xml` (Z. 31)
- **Problem:**
  ```xml
  android:configChanges="orientation|screenSize|keyboardHidden"
  ```
- **Risiko:** Das Deaktivieren der Activity-Recreation bei Orientierungswechseln und Größenänderungen (z. B. Split-Screen, Foldables) umgeht Standard-Lifecycle-Mechanismen. UI-Zustände, die nicht in Compose `rememberSaveable` oder ViewModels liegen, können asynchron inkonsistent werden.
- **Empfohlene Best Practice:**
  Entfernen von `android:configChanges` und Sicherstellen, dass alle Screens und Dialoge robust gegen Recreations sind (getestet mit "Don't keep activities" / Developer Options).

---

#### Finding 3.5.2: Manuelle Locale-Hacks statt Android 13+ App-Sprachen API
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

### Phase 1: Stabilität & Performance (Sofortmaßnahmen)
1. **Room-Sicherheit:** `fallbackToDestructiveMigration()` entfernen und Migrationsstrategie etablieren.

### Phase 2: Architektur-Refactoring & Entkopplung (Mittelfristig)
1. **Decomposition:** `MainScreen.kt` in fokussierte Sub-Composables zerlegen.
2. **Reaktive State-Pipelines:** `startObserving()` durch deklarative `stateIn`-Pipelines ersetzen.
3. **Dispatcher Injection:** `CoroutineDispatchers` für saubere Testbarkeit injizierbar machen.

### Phase 3: Qualitäts- & Test-Offensive (Langfristig)
1. **Compose-Tests & Screenshot-Testing:** Integration von `androidx.compose.ui.test` und Roborazzi für Snapshot-Tests.
2. **Naming-Konsolidierung:** Schrittweise Vereinheitlichung auf englische Bezeichner in Datenmodellen und Repositories.
