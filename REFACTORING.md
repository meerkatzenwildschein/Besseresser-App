# Refactoring- und Code-Review-Bericht (Android Best Practices)

Dieses Dokument enthält die Ergebnisse eines umfassenden Code-Reviews der **Besseresser-App** mit Fokus auf moderne **Android- und Kotlin-Best-Practices**, **Jetpack Compose Guidelines**, **Architektur- und Schichtenarchitektur (Clean Architecture / MVI/MVVM)**, **Ressourcen-/Lifecycle-Management** sowie **Stabilität und Performance**.

---

## Inhaltsverzeichnis
1. [Zusammenfassung & Architekturüberblick](#1-zusammenfassung--architekturüberblick)
2. [Kritische Findings (Hohe Priorität)](#2-kritische-findings-hohe-priorität)
3. [Wichtige Findings (Mittlere Priorität)](#3-wichtige-findings-mittlere-priorität)
4. [Optimierungspotenziale & Code Smells (Niedrige Priorität)](#4-optimierungspotenziale--code-smells-niedrige-priorität)
5. [Positiv hervorzuhebende Best Practices](#5-positiv-hervorzuhebende-best-practices)
6. [Empfohlener Aktionsplan](#6-empfohlener-aktionsplan)

---

## 1. Zusammenfassung & Architekturüberblick

Die Codebasis ist modern strukturiert (Kotlin, Jetpack Compose, Material 3, Coroutines/Flow, Koin DI, CameraX, Room/DataStore). Viele Best Practices wie asynchrone Bildvorverarbeitung (`ImagePreprocessor`), Entprellung von Re-Evaluierungen, `collectAsStateWithLifecycle` und saubere Model-Abstraktionen sind bereits vorbildlich implementiert.

Dennoch gibt es einige typische Android-/Compose-Stellen, an denen Optimierungsbedarf besteht:
- **Compose State Hoisting & DI-Kopplung**: Untergeordnete/wiederverwendbare UI-Elemente instanziieren vereinzelt direkt ViewModels über `koinViewModel()`.
- **Navigation & Routing**: Die Navigation wird über State-Booleans in `MainActivity` / `MainScreen` gesteuert anstelle des offiziellen `Navigation Compose` (Navigation Component / Type-Safe Routes).
- **Stabilität & Recomposition**: Instabile Typen (z. B. `List<T>`, `Set<T>`, `Map<K, V>`) in UI-States ohne Immutable-Wrapper / `kotlinx.collections.immutable` können unnötige Recompositions verursachen.
- **Fehlerbehandlung & String-Hardcoding**: In Einzelfällen werden Fehlertexte hardcodiert bzw. Exceptions nicht einheitlich auf `UiText` / String-Ressourcen abgebildet.
- **ViewModel-Verantwortung**: Einzelne ViewModels (`HistoryViewModel`, `SetupViewModel`) haben eine relativ hohe Verantwortung und bündeln mehrere Workflows.

---

## 2. Kritische Findings (Hohe Priorität)

### 2.1 [x] Direkte ViewModel-Instanziierung in Sub-Composables (Verletzung von State Hoisting)
- **Ort**: `de/gurum/besseresser/ui/CommonUi.kt` (`RecentNotesButton`, Zeile 413)
- **Problem**: 
  In der wiederverwendbaren Komponente `RecentNotesButton` wird `val viewModel: RecentNotesViewModel = koinViewModel()` direkt aufgerufen. 
  Dies koppelt ein kleines UI-Widget fest an das Koin-Framework und erschwert:
  - Compose-Previews (`@Preview`)
  - Isolierte UI-Tests (Robolectric / Compose Test)
  - Wiederverwendbarkeit in verschiedenen Kontexten mit unterschiedlichen Datenquellen.
- **Empfehlung**: 
  State Hoisting anwenden. `RecentNotesButton` sollte `notes: List<RecentNote>`, `onPick: (String) -> Unit`, `onToggleStar: (RecentNote) -> Unit` etc. als Parameter erhalten. Die ViewModel-Anbindung erfolgt auf Screen-Ebene.

```kotlin
// Empfohlen:
@Composable
fun RecentNotesButton(
    notes: List<RecentNote>,
    onPick: (String) -> Unit,
    onToggleStar: (RecentNote) -> Unit
) { ... }
```

---

### 2.2 [x] Fehlendes Type-Safe Navigation Compose
- **Ort**: `MainActivity.kt`, `MainScreen.kt`, `HistoryScreen.kt`
- **Problem**: 
  Die Navigation zwischen Screens (Setup, Profil, Konfiguration, Verlauf, Detail, Kamera) erfolgt primär über Zustandsvariablen (`currentScreen`, `showCamera`, `showReview`, `addDialogForDay`).
  - Back-Stack-Verwaltung muss manuell via `BackHandler` gepflegt werden.
  - Deep Links und Transitions zwischen Screens sind schwerer erweiterbar.
  - State-Restoration bei Process Death erfordert manuelles Mapping in `SavedStateHandle`.
- **Empfehlung**: 
  Migration auf **Jetpack Navigation Compose** (ab Navigation 2.8+ mit type-safe Kotlin Serialization Routes / `@Serializable data object SetupRoute`).

---

### 2.3 [x] `HistoryViewModel` bündelt zu viele Verantwortlichkeiten (Large ViewModel)
- **Ort**: `de/gurum/besseresser/ui/HistoryViewModel.kt` (> 800 Zeilen)
- **Problem**: 
  Das `HistoryViewModel` verwaltet gleichzeitig:
  1. Verlaufs-Listenanzeige & Reordering
  2. Nacherfassung von Mahlzeiten (Kamera, Review, Foto-Capture-Lifecycle, KI-Analyse)
  3. Lösch-Vormerkungen mit Undo-Timer (`requestDeleteEntry`, `undoDeleteEntry`, `flushPendingDeletes`)
  4. Manuelle & automatische Tages- und Wochen-Fazits
  5. UI-State für Expansions, Dialoge und Fehler.
- **Empfehlung**: 
  Aufteilen nach Use-Cases oder Sub-ViewModels:
  - `HistoryListViewModel` (nur Liste, Filter, Löschen/Undo, Reordering)
  - `BackdatedEntryViewModel` oder Wiederverwendung von `MainViewModel`/Shared Screen für Nacherfassung
  - Auslagerung der Auto-Fazit-Logik in einen dedizierten Domain Use-Case (`TriggerAutoFazitUseCase`).

---

## 3. Wichtige Findings (Mittlere Priorität)

### 3.1 [x] Compose-Recomposition-Optimierung mit `ImmutableList` / `@Immutable`
- **Ort**: 
  - `HistoryUiState` (`entries: List<HistoryEntry>`, `fazitMap: Map<...>`, `loadingWeeks: Set<String>`)
  - `HistoryDetailViewModel.DetailUiState`
  - `HistoryListContent`
- **Problem**: 
  Standard-Kotlin-Collections (`List`, `Set`, `Map`) gelten im Compose Compiler standardmäßig als **instabil** (`unstable`), da es sich um Interfaces handelt, deren Implementierung veränderlich sein könnte (`java.util.ArrayList`). Dadurch können Composables, die diese Parameter entgegennehmen, nicht übersprungen werden (*skipping*).
- **Empfehlung**: 
  - Verwendung von `kotlinx.collections.immutable` (`ImmutableList<HistoryEntry>`, `ImmutableSet<Long>`, `PersistentMap<...>`) oder
  - Kapselung in `@Immutable` / `@Stable` Data Classes.

---

### 3.2 [x] Unbehandelte Ausnahmen / Hardcodierte Fehlerstrings in UI-Schicht
- **Ort**: 
  - `HistoryDetailViewModel.kt` (Zeile 101: Fallback-String `"Keine Internetverbindung – KI-Bewertung ist aktuell nicht möglich."` direkt im Code)
  - `HistoryViewModel.kt` (`addError = e.message ?: e.javaClass.simpleName`)
- **Problem**: 
  - Direkte Zuweisung technischer Exception-Namen (`e.javaClass.simpleName`) an den UI-State führt zu unschönen Meldungen für Endnutzer (z. B. `SocketTimeoutException`).
  - Fehlende Lokalisierung bei Fallback-Strings.
- **Empfehlung**: 
  Einheitliches `UiText`-Pattern nutzen (wie in `ProfileViewModel` bereits vorbildlich mit `UiText.StringResource` umgesetzt). Technische Fehler über einen `ErrorMapper` in nutzerfreundliche, lokalisierte Meldungen überführen.

---

### 3.3 [x] Scope & Dispatcher-Hygiene bei Bilddecodierung und IO
- **Ort**: `HistoryViewModel.kt`, `HistoryDetailViewModel.kt`, `PhotoStorage.kt`
- **Problem**: 
  Einige Methoden deklarieren `suspend fun` ohne internen `withContext(dispatchers.io)`, sodass der Aufrufer den Dispatcher steuern muss (z. B. `decodeThumbnail` in `HistoryViewModel` ruft `historyRepository.decodeThumbnail` auf, welches sicherstellen muss, niemals den Main-Thread zu blockieren).
- **Empfehlung**: 
  Jede Suspend-Funktion in Data-/Repository-Klassen muss "main-safe" sein (d. h. `withContext(Dispatchers.IO)` intern kapseln).

---

### 3.4 [x] Kamera-Ressourcenfreigabe im `DisposableEffect` absichern
- **Ort**: `CameraCaptureScreen.kt` (Zeilen 209–214)
- **Problem**: 
  Die Kamera-Bindung erfolgt über `ProcessCameraProvider`. Im `DisposableEffect(Unit)` wird `providerHolder.value?.unbindAll()` aufgerufen. Bei schnellen Konfigurationsänderungen oder Navigationswechseln sollte geprüft werden, ob `ProcessCameraProvider` immer rechtzeitig gebunden bzw. entbunden wird.
- **Empfehlung**: 
  Sicherstellen, dass `LifecycleOwner` und Lifecycle-Events (`ON_PAUSE`, `ON_STOP`) die Vorschau pausieren, um Akkuverbrauch im Hintergrund zuverlässig zu unterbinden.

---

## 4. Optimierungspotenziale & Code Smells (Niedrige Priorität)

### 4.1 [x] String-Formatierung & Plural-Ressourcen
- **Befund**: In `SummaryFooterRow` (`CommonUi.kt`) wird `LocalContext.current.resources.getQuantityString(...)` genutzt, während an anderen Stellen `pluralStringResource(...)` aus Compose verwendet wird.
- **Empfehlung**: Einheitlich die Compose-Funktion `pluralStringResource(R.plurals.details_toggle_more, extraCount, extraCount)` einsetzen.

### 4.2 [x] Sealed Interface für UI-Events / Single Live Events
- **Befund**: Toasts und Fehlermeldungen werden teils über `error: String?` im UI-State gehalten und erfordern manuelle `clearError()`-Aufrufe nach dem Rendern.
- **Empfehlung**: Verwendung von Kotlin `Channel<UiEvent>` / `SharedFlow` für "One-Shot Events" (z. B. `ShowToast`, `NavigateBack`, `RequestPermission`), um State-Clear-Boilerplate zu vermeiden.

### 4.3 [x] Koin Modul-Modularisierung
- **Befund**: `appModule` in `BesseresserApp.kt` definiert alle Singletons, Repositories und ViewModels in einer Datei.
- **Empfehlung**: Aufteilung in logische Module:
  - `networkModule` (OkHttp, ApiClient, ApiService, NetworkChecker)
  - `databaseModule` (Room, DAOs, DataStore)
  - `repositoryModule` (SettingsRepo, HistoryRepo, ProfileRepo)
  - `viewModelModule` (alle `viewModelOf(...)` Definitionen)

---

## 5. Positiv hervorzuhebende Best Practices

1. **StateFlow & UI-State-Kapselung**: Alle Screens nutzen unidirektionalen Datenfluss (`UDF`) mit `@Immutable` UI-States und `collectAsStateWithLifecycle()`.
2. **Hintergrund-Sicherheit & Image-Pipeline**: Bildkompression und EXIF-Korrektur erfolgen sicher im Hintergrund (`ImagePreprocessor`, `PhotoStorage`), ohne den UI-Thread zu blockieren.
3. **Optimistisches UI-Update & Undo-Pattern**: Das Löschen von Verlaufseinträgen ist mit einem durchdachten 5-Sekunden-Timer und Undo-Mechanismus ausgestattet.
4. **Offline- & Fehlertoleranz**: Intelligente Menü-/Bewertungs-Caches fangen fehlende Internetverbindungen elegant ab.
5. **Dark Mode & Dynamic Color**: Saubere Theme-Integration mit Material 3 und voller Unterstützung für System-/Hell-/Dunkel-Modi sowie In-App-Sprachwechsel (`AppLanguage`).

---

## 6. Empfohlener Aktionsplan

| Phase | Maßnahme | Aufwand | Nutzen |
|---|---|---|---|
| **Phase 1** | Refactoring von `RecentNotesButton` (State Hoisting, Entfernung von `koinViewModel()`) | Gering | Hohe Testbarkeit & previewbare UI |
| **Phase 2** | `UiText`-Pattern flächendeckend einführen & technische Fehlermeldungen kapseln | Gering | Bessere UX & saubere Lokalisierung |
| **Phase 3** | Koin-Module modularisieren (`networkModule`, `dataModule`, `uiModule`) | Gering | Bessere Wartbarkeit & Übersicht |
| **Phase 4** | Aufteilung von `HistoryViewModel` in fokussierte ViewModels / UseCases | Mittel | Höhere Code-Qualität & geringere Komplexität |
| **Phase 5** | Umstellung auf type-safe Navigation Compose | Mittel | Standardisiertes Routing & Back-Stack-Handling |
