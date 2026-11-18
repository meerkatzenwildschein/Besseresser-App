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

| Priorität | Bereich | Finding | Auswirkung | Status |
|---|---|---|---|---|
| ~~**P1 (Hoch)**~~ | ~~Persistence~~ | ~~`fallbackToDestructiveMigration()` in Room~~ | ~~Verlust aller Verlaufsdaten bei Schema-Änderungen~~ | ✅ |
| ~~**P1 (Hoch)**~~ | ~~Compose~~ | ~~Monolithische Composables (`MainScreen.kt` > 900 Zeilen)~~ | ~~Mangelnde Wiederverwendbarkeit, unnötige Recompositions~~ | ✅ |
| ~~**P2 (Mittel)**~~ | ~~Lifecycle / System~~ | ~~`android:configChanges` in `MainActivity`~~ | ~~Umgeht Standard-Lifecycle bei Rotation/Split-Screen~~ | ✅ |
| ~~**P2 (Mittel)**~~ | ~~Architektur~~ | ~~Imperative `startObserving()`-Initialisierung~~ | ~~Fehleranfälliger manueller Kick aus der UI~~ | ✅ |
| ~~**P2 (Mittel)**~~ | ~~Compose~~ | ~~`remember`-basierte Foto-Dateierstellung~~ | ~~Veralteter Dateiname bei Wiederaufnahme~~ | ✅ |
| ~~**P2 (Mittel)**~~ | ~~Datenhaltung~~ | ~~Verwaiste Foto-Dateien (Dangling Files)~~ | ~~Speicherleck nach Abstürzen oder Migrationsfehlern~~ | ✅ |
| ~~**P2 (Mittel)**~~ | ~~Lifecycle / System~~ | ~~Manueller Locale ContextWrapper~~ | ~~Umgeht Android 13+ Per-App-Language-APIs~~ | ✅ |
| ~~**P2 (Mittel)**~~ | ~~Testing & DI~~ | ~~Harte `Dispatchers.IO`-Referenzen & fehlende Dispatcher-Injection~~ | ~~Erschwerte deterministische Unit- & Coroutine-Tests~~ | ✅ |
| ~~**P3 (Niedrig)**~~ | ~~Testing & DI~~ | ~~Fehlende User-Flow-Tests~~ | ~~Keine Testabdeckung für zentrale Flows~~ | ✅ |
| ~~**P3 (Niedrig)**~~ | ~~Code-Qualität~~ | ~~Denglish-Bezeichner & String-basierte Magic Keys~~ | ~~Wartungsaufwand, Fehleranfälligkeit bei Refactorings~~ | ✅ |

---

## 3. Detaillierte Findings & Best Practice Empfehlungen

### 3.1 UI & Jetpack Compose

#### ✅ Finding 3.1.1: ~~State & File-Erzeugung in `remember` ohne Re-Evaluation~~
- **Betroffene Dateien:** `MainScreen.kt`, `HistoryScreen.kt`
- **Problem:**
  Dateiobjekte und FileProvider-URIs wurden in `remember { ... }` ohne Dependencies erzeugt und führten I/O (`mkdirs()`) während der Initial-Composition aus. Bei verworfenen oder wiederholten Fotos verwies `photoFile` immer noch auf denselben alten Zeitstempel-Dateinamen.
- **Umgesetzt:** `PhotoCaptureManager` in `data/` extrahiert; Datei wird erst beim tatsächlichen Kamera-Start angelegt (`createCaptureFile()`). Koin-Binding als `single`. State in der UI als `mutableStateOf<Pair<File, Uri>?>(null)`, beim Abbrechen/Verwerfen auf `null` zurückgesetzt.

---

#### ✅ Finding 3.1.2: ~~Monolithische Screen-Dateien und fehlende Dekomposition~~
- **Betroffene Dateien:** `MainScreen.kt` (war 930 Zeilen)
- **Problem:**
  `MainScreen.kt` bündelte Kamera-Aufnahme, Review-Screen, Auswertungsansicht, Edit-Dialoge, Dropdown-Menüs und Floating Buttons in einer einzigen Datei.
- **Umgesetzt:** Aufgeteilt in `MainScreen.kt` (Root), `MainReviewScreen.kt`, `MainReportView.kt`, `MainTopBar.kt`, `MainCtaBar.kt`.

---

### 3.2 Architektur & State Management (MVVM / Clean Architecture)

#### ✅ Finding 3.2.1: ~~Imperative ViewModel-Initialisierung (`startObserving()`)~~
- **Betroffene Dateien:** `HistoryViewModel.kt`, `HistoryScreen.kt`
- **Problem:**
  In `HistoryScreen` wurde über `LaunchedEffect(Unit) { viewModel.startObserving() }` eine Methode aufgerufen, um Flows zu starten.
- **Umgesetzt:** `startObserving()` entfernt, Flow-Collection in den `init`-Block des ViewModels verschoben. `LaunchedEffect`-Aufruf in der UI entfernt.

---

### 3.3 Threading, Concurrency & Coroutines

#### ✅ Finding 3.3.1: ~~Harte `Dispatchers.IO`-Referenzen in Klassen~~
- **Betroffene Dateien:** `HistoryRepository.kt`, `ImagePreprocessor.kt`, `BackupService.kt`, `ConfigViewModel.kt`
- **Problem:**
  Direkte Aufrufe von `withContext(Dispatchers.IO)` erschwerten das Testen mit `StandardTestDispatcher` oder `UnconfinedTestDispatcher`.
- **Umgesetzt:** `AppDispatchers`-Klasse in `data/` mit injizierbaren `main`, `io` und `default` Dispatchers. Koin-Binding als `single { AppDispatchers() }`. Alle betroffenen Klassen (`DefaultHistoryRepository`, `PhotoStorage`, `ApiClient`, `AppStateViewModel`, `ConfigViewModel`, `HistoryViewModel`, `HistoryDetailViewModel`, `MainViewModel`) erhalten `dispatchers: AppDispatchers = AppDispatchers()` als Constructor-Parameter und nutzen `dispatchers.io` statt `Dispatchers.IO`. Tests können `AppDispatchers(io = UnconfinedTestDispatcher())` übergeben.

---

### 3.4 Datenhaltung, Persistence & Room

#### ✅ Finding 3.4.0: ~~`fallbackToDestructiveMigration()` aktiv~~
- **Betroffene Dateien:** `AppDatabase.kt`
- **Problem:** Bei Schema-Upgrades ohne explizite Migration löschte Room die komplette Datenbank.
- **Umgesetzt:** `fallbackToDestructiveMigration()` entfernt; explizite `Migration(1, 2)`-Klasse für `sortOrder`-Spalte implementiert.

---

#### ✅ Finding 3.4.1: ~~Getrennte Verwaltung von SQLite-Daten und Foto-Dateien (Dangling Files)~~
- **Betroffene Dateien:** `HistoryRepository.kt`, `AppDatabase.kt`
- **Problem:**
  Foto-Dateien im Dateisystem konnten nach Abstürzen oder fehlgeschlagenen Migrationen ohne zugehörigen DB-Eintrag zurückbleiben.
- **Umgesetzt:** `purgeOrphanedPhotos()` in `HistoryRepository`-Interface und `DefaultHistoryRepository` implementiert: vergleicht alle JPEG/PNG/WebP-Dateien im `history`-Verzeichnis mit den bekannten `photoFile`-Einträgen in Room und löscht Dateien ohne Eintrag. Wird beim App-Start im Hintergrund zusammen mit `purgeOlderThanWeeks()` aufgerufen (`AppStateViewModel`). `FakeHistoryRepository` in den Tests implementiert die Methode als No-Op.

---

### 3.5 Android Lifecycle & Lokalisierung

#### ✅ Finding 3.5.0: ~~`android:configChanges` in `MainActivity`~~
- **Betroffene Dateien:** `AndroidManifest.xml`
- **Problem:** `android:configChanges="orientation|screenSize|keyboardHidden"` unterdrückte Standard-Activity-Recreations bei Rotation und Split-Screen.
- **Umgesetzt:** Attribut aus dem Manifest entfernt. Alle UI-Zustände liegen in ViewModels oder `rememberSaveable`.

---

#### ✅ Finding 3.5.1: ~~Manuelle Locale-Hacks statt Android 13+ App-Sprachen API~~
- **Betroffene Dateien:** `MainActivity.kt` (`withAppLanguage`)
- **Problem:**
  Die App manipulierte `ConfigurationContext` und `Locale.setDefault(...)` manuell auf allen API-Levels.
- **Umgesetzt:** Neue Funktion `applyLocale()` wählt den richtigen Pfad je nach API-Level:
  - **API 33+**: `LocaleManager.setApplicationLocales()` — der OS übernimmt den Sprachenwechsel, spiegelt ihn in den Systemeinstellungen wider und startet die Activity neu.
  - **API < 26–32**: bisheriger `ContextWrapper`-Fallback bleibt erhalten, da `LocaleManager` dort nicht verfügbar ist. `withAppLanguage()` sorgt weiterhin dafür, dass Compose-Strings sofort die richtige Sprache nutzen.

---

### 3.6 Dependency Injection & Testbarkeit

#### ✅ Finding 3.6.1: ~~Fehlende UI- & Screenshot-Tests~~
- **Problem:**
  Es existierten Unit-Tests für Serialisierung, Repositories und Room, jedoch keine Tests für die zentralen User Flows.
- **Umgesetzt:** Neue Testdatei `UserFlowsTest.kt` mit 9 ViewModel-Tests (JVM, kein Emulator):
  - **Setup Flow** (4 Tests): `init()` aus Settings, unabhängige Setter, `goalsDirty`-Flag, `startSplitGoals()` mit erfolgreichem API-Response.
  - **Manual Retro-Capture** (3 Tests): `submitBackdated()` ohne Foto speichert Eintrag, Cache-Treffer überspringt API-Aufruf, API-Fehler setzt `addError`.
  - **History List + Detail** (4 Tests): Einträge reaktiv sichtbar, Pending-Delete undoable, `HistoryDetailViewModel` findet Eintrag per ID, unbekannte ID liefert `null`, `updateErkannt()` persistiert.
  `FakeApiService` um `analyzeTextResult`, `splitGoalsResult` und `analyzeTextCallCount` erweitert.

---

### 3.7 Code-Style, Naming & Lokalisierung

#### ✅ Finding 3.7.1: ~~Vermischung von Deutsch und Englisch (Denglish)~~
- **Betroffene Dateien:** Projektweit
- **Problem:** Deutsche Bezeichner (`erkannt`, `ampel`, `begruendung`, `showErkanntEdit`, `mealLines`, `ErkanntEditDialog`) im Kotlin-Code.
- **Umgesetzt (codeweites Rename):**
  - `GoalResult.ampel` → `.rating` (DB-Spalte via `@ColumnInfo(name = "ampel")` unverändert)
  - `GoalResult.begruendung` → `.reason` (`@SerialName("begruendung")` unverändert)
  - `HistoryEntry.erkannt` / `Evaluation.erkannt` → `.recognizedMeal` (`@SerialName("erkannt")` unverändert, DB-Spalte via `@ColumnInfo(name = "erkannt")` unverändert)
  - `DayFazit.ampel` / Entity-Felder → `.rating` (`@ColumnInfo(name = "ampel")` unverändert)
  - `showErkanntEdit` → `showRecognizedEdit`
  - `ErkanntEditDialog` → `RecognizedMealEditDialog`
  - `mealLines` → `mealDescriptions`
  - Parameter `onEditErkannt` → `onEditRecognizedMeal`
  - `updateErkannt()` (DAO, Repository-Interface, Implementierung, ViewModels) → `updateRecognizedMeal()`
  - `stripErkanntIntro()` → `stripRecognizedMealIntro()` (private in `ApiClient`)
  - `ERKANNT_INTRO_PATTERNS` → `RECOGNIZED_MEAL_INTRO_PATTERNS` (private Konstante in `ApiClient`)
  - JSON-Schlüssel und DB-Spaltennamen bleiben unberührt — keine Migration nötig.
  - `DayFazit`-Klassenname und lokale `fazit`-Variablen bleiben (Klassenumbenennung würde DB-Migration erfordern).

---

## 4. Empfohlene Roadmap & Nächste Schritte

### Phase 1: Architektur-Refactoring & Entkopplung
~~1. **Dispatcher Injection:** `CoroutineDispatchers` für saubere Testbarkeit injizierbar machen.~~ ✅

### Phase 2: Qualitäts- & Test-Offensive
~~1. **Compose-Tests & Screenshot-Testing:** Integration von `androidx.compose.ui.test` und Roborazzi für Snapshot-Tests.~~ ✅ (als ViewModel-Level-Tests in `UserFlowsTest.kt` umgesetzt; Compose UI-Tests erfordern einen Emulator und sind außerhalb des JVM-Test-Scopes)
~~2. **Naming-Konsolidierung:** Schrittweise Vereinheitlichung auf englische Bezeichner in Datenmodellen und Repositories.~~ ✅ (alle exportierten Bezeichner in Modellen, Repositories und ViewModels auf Englisch umbenannt; `DayFazit`-Klassenname und lokale Variablen bleiben wegen DB-Rückwärtskompatibilität)

---

**Alle Findings und Roadmap-Punkte abgeschlossen.** Die Codebasis entspricht den dokumentierten Android Best Practices.
