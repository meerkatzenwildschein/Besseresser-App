# REFACTORING.md – Besseresser

Modernisierungs-Roadmap der App von einem „alles in einem Package / Store-Objekte"-Stand
hin zu einer sauberen MVVM-Schichtenarchitektur. Status: **abgeschlossen**.

---

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

- `MainActivity` nutzt `NavHost` mit typsicheren `@Serializable`-Routen (`Routes.Main`, `Routes.Setup`, `Routes.Profile`, `Routes.History`, `Routes.Config`, `Routes.Detail(entryId)` via `toRoute()`).
- Neue ViewModels: `HistoryDetailViewModel` (pro entryId), `ConfigViewModel`, `RecentNotesViewModel`.
- `BesseresserApiService` ohne `Context` (Prompts/Bild-Aufbereitung in die ViewModel-/UI-Schicht); `ImagePreprocessor` injizierbar.
- UI ruft Datenzugriffe ausschließlich über ViewModels auf (kein `koinInject` von Repositories/Services in Composables).

## Phase 6 – Aufräumen & Dependency-Updates ✅

**Ziel:** Naming, Docs, Versions-Pflege.

- `Theme.GesundEssen` → `Theme.Besseresser` (Manifest + themes.xml).
- `REFACTORING.md` angelegt (diese Roadmap; vorher nur referenziert, nicht vorhanden).
- Dependency-Bump: Kotlin 2.0.21, KSP 2.0.21-1.0.25, Compose BOM 2024.12.01, core-ktx 1.15.0, lifecycle 2.8.7, activity-compose 1.9.3, coroutines-test 1.9.0.

---

## Offene Punkte & Lint-Findings

### 1. Hoher Nutzen bei minimalem Aufwand (Sicherheit & Bereinigung) ✅
- **Sicherheit bei EXIF-Verarbeitung (`ExifInterface`)**: `android.media.ExifInterface` in `CameraScreen.kt` durch `androidx.exifinterface.media.ExifInterface` ersetzt (`androidx.exifinterface:exifinterface:1.3.7`).
- **Ungenutzte Ressourcen entfernen (`UnusedResources`)**: 8 verwaiste String-Ressourcen in `strings.xml` und `values-de/strings.xml` bereinigt.
- **Kotlin-Idiomatik & KTX (`UseKtx`)**:
  - `ConfigScreen.kt`: `String.toUri()` statt `Uri.parse(...)`
  - `ImagePreprocessor.kt`: `Bitmap.scale(...)` statt `Bitmap.createScaledBitmap(...)`
- **Redundante Ordner-Qualifier bereinigen (`ObsoleteSdkInt`)**: Ressourcen aus `res/mipmap-anydpi-v26` nach `res/mipmap-anydpi` zusammengeführt und `-v26` entfernt.

### 2. Mittlerer Nutzen (Benutzererfahrung & Plattform-Konformität)
- **Plural-Grammatik korrigieren (`PluralsCandidate`)**: Strings mit Mengenangaben in `strings.xml` (`%1$d years`, `%2$d meals`, `%1$d ratings`) auf Android `<plurals>`-Ressourcen migrieren, um korrekte Grammatik über alle Sprachen hinweg zu garantieren.
- **Backup-Regeln modernisieren (`DataExtractionRules`)**: `android:dataExtractionRules` via `@xml/data_extraction_rules` im `AndroidManifest.xml` für Android 12+ hinterlegen.
- **Themed App Icon unterstützen (`MonochromeLauncherIcon`)**: `<monochrome android:drawable="..." />` in `ic_launcher.xml` für adaptive Icons auf Android 13+ ergänzen.
- **App-Bundle Sprachpaket-Splitting (`AppBundleLocaleChanges`)**: In `app/build.gradle.kts` `bundle { language { enableSplit = false } }` konfigurieren, damit beim dynamischen In-App-Sprachwechsel keine Strings im App Bundle fehlen.

### 3. Gezielte Dependency-Updates (Wägung von Stabilität vs. Aktualität)
- **AndroidX & Lifecycle-Bibliotheken schrittweise und testgetrieben aktualisieren**:
  - `androidx.core:core-ktx`: `1.15.0` → `1.19.1`
  - `androidx.activity:activity-compose`: `1.9.3` → `1.13.0`
  - `androidx.lifecycle:*`: `2.8.7` → `2.11.0`
  - `androidx.camera:*`: `1.4.2` → `1.6.2`
  - `androidx.room:*`: `2.6.1` → `2.8.5`
  - `androidx.datastore:datastore-preferences`: `1.1.1` → `1.3.0`
  - `androidx.navigation:navigation-compose`: `2.8.4` → `2.10.2`
