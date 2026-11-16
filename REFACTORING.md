# REFACTORING.md – Besseresser

Modernisierungs-Roadmap der App von einem „alles in einem Package / Store-Objekte"-Stand
hin zu einer sauberen MVVM-Schichtenarchitektur. Status: **abgeschlossen**.

---

## Phase 1 – Dependency Injection (Koin) ✅

**Ziel:** Direkt-Instanziierung und Context-Fallbacks durch Koin ersetzen.

- Koin 4.0.4 (`koin-android`, `koin-androidx-compose`) eingeführt; `BesseresserApp` startet den Container, `di/AppModule.kt` registriert DB, Repositories, ApiService, ViewModels.
- Handgeschriebener `RepositoryProvider` entfernt; ViewModels erhalten ihre Abhängigkeiten per Konstruktor (kein `ctx`-Fallback mehr).
- Ungenutzte Parameter entfernt (`SetupViewModel.settingsRepository`, `MainViewModel.saveToHistory(settings)`).

## Phase 2 – Persistenzschicht entflechten ✅

**Ziel:** Store-Objekte auflösen, `runBlocking` beseitigen, Foto-Ablage kapseln.

- `Store`/`ProfileStore`/`RecentNotesStore`/`HistoryStore`/`DayFazitStore`/`WeekFazitStore` in die Repository-Implementierungen (`data/Repositories.kt`) überführt.
- Reine Logik in `RecentNotesLogic`, `HistoryEntryLogic`, `BackupLogic` extrahiert (direkt testbar).
- `BackupStore` → injizierbarer `BackupManager` (+ `BackupService`-Interface).
- `runBlocking` entfernt; Repositories sind suspend/Flow (Room-DAOs direkt).
- `PhotoStorage` als eigene Komponente für die Foto-Dateiablage.

## Phase 3 – DataStore + AppStateViewModel ✅

**Ziel:** SharedPreferences ablösen, Settings/Profil/Theme/Sprache reaktiv machen.

- Migration von SharedPreferences auf Preferences DataStore (mit `SharedPreferencesMigration`).
- Repositories exponieren `Flow<Settings>`/`Flow<Profile>`/`Flow<AppThemeMode>`/`Flow<AppLanguage>` + suspend-APIs.
- Neuer `AppStateViewModel` streamt den App-Zustand in die MainActivity; manuelles State-Syncing nach Setup/Backup-Import entfällt.
- `UiPrefsStore` entfernt; tote `MainUiState.recentNotes`-Plumbing entfernt.

## Phase 4 – Package-Struktur ✅

**Ziel:** Klare Schichtentrennung (model / data / ui), Domänenmodelle compose-frei.

- `model/`: reine Domänenmodelle & Logik (`Data.kt`, `Store.kt`, `HistoryStore.kt`) – kein Android-/Compose-Import.
- `data/`: `AppDatabase`, `Repositories`, `PhotoStorage`, `BackupStore`, `KeyVault`, `ApiClient`, `OpenAiChatClient`, `ImagePreprocessor`, `PromptBuilder`.
- `ui/`: alle Screens + ViewModels; `TrafficLight.color()/label()` als Compose-Extensions in `ui/AmpelUi.kt`; `withAppLanguage` in `MainActivity`.
- Manifest zeigt auf `.ui.MainActivity`.

## Phase 5 – Navigation Compose + ViewModels ✅

**Ziel:** Boolesche Screen-Navigation durch Navigation Compose ersetzen; UI-Direktzugriffe auf Repositories in ViewModels verschieben.

- `MainActivity` nutzt `NavHost` mit typisierten Routen (`main`, `setup`, `profile`, `history`, `config`, `detail/{entryId}`).
- Neue ViewModels: `HistoryDetailViewModel` (pro entryId), `ConfigViewModel`, `RecentNotesViewModel`.
- `BesseresserApiService` ohne `Context` (Prompts/Bild-Aufbereitung in die ViewModel-/UI-Schicht); `ImagePreprocessor` injizierbar.
- UI ruft Datenzugriffe ausschließlich über ViewModels auf (kein `koinInject` von Repositories/Services in Composables).

## Phase 6 – Aufräumen & Dependency-Updates ✅

**Ziel:** Naming, Docs, Versions-Pflege.

- `Theme.GesundEssen` → `Theme.Besseresser` (Manifest + themes.xml).
- `REFACTORING.md` angelegt (diese Roadmap; vorher nur referenziert, nicht vorhanden).
- Dependency-Bump: Kotlin 2.0.21, KSP 2.0.21-1.0.25, Compose BOM 2024.12.01, core-ktx 1.15.0, lifecycle 2.8.7, activity-compose 1.9.3, coroutines-test 1.9.0.

---

## Offene Punkte (nicht begonnen)

- **Settings/Profil/Theme/Sprache-State vollständig in den `AppStateViewModel` heben** (aktuell noch teilweise `remember`-State in `MainActivity`).
- **Navigation-Backstack-Verhalten bei Setup-Overlay** ggf. weiter glätten (aktuell `popUpTo`-Logik).
- **Abhängigkeiten weiter aktualisieren** (Room 2.7.x, CameraX, Kotlin 2.1) — bewusst konservativ zurückgestellt.
