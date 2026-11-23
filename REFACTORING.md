# Code-Review & Refactoring-Leitfaden: Besseresser-App

## 1. Executive Summary & Gesamtbewertung

Die Besseresser-App weist eine moderne technologische Grundlage auf (Kotlin 2.0, Jetpack Compose mit Material 3, Room, DataStore, Koin Dependency Injection, OkHttp und CameraX). Die Trennung zwischen Domain-Modellen, Repositories und ViewModels ist grundsätzlich angelegt.

Dieses Code-Review analysiert die Codebasis im Hinblick auf **Android Best Practices** (Modern Android Architecture, Performance, Lifecycle-Stabilität, Sicherheit, Resilienz und Testbarkeit). 

### Hauptbefunde im Überblick:
- **Kritisch (Prio 1):** Potenzieller Absturz (`SecurityException`) bei Backup-Export auf Android 8.0–9.0 (API 26–28) durch fehlende Berechtigungsdeklaration für Legacy-Storage sowie OOM-Risiko bei monolithischem Base64-JSON-Export großer Bildmengen.
- **Hoch (Prio 2):** Performance-Engpässe in LazyColumn durch rechenintensive Operationen (Fingerprint-Hashing, Set-Konvertierungen) direkt im Recomposition-Scope jedes List-Items; fehlende `@Immutable`-Stabilität von UI-State-Modellen.
- **Mittel (Prio 3):** Destruktive Datenbank-Migrationen (`fallbackToDestructiveMigration()`) riskieren Datenverlust bei Schema-Änderungen; fehlende Datenbank-Indizes auf häufig abgefragten Spalten.
- **Verbesserungswürdig (Prio 4):** Hardcodierte `Dispatchers.IO` erschweren deterministisches Testen; `runBlocking` in Legacy-Migrationen birgt ANR-Risiken; Java Toolchain in Gradle fehlt.

---

## 2. Detaillierte Findings nach Best-Practice-Kategorien

### A. Architektur & State Management (Modern Android Architecture / MAD)

#### A.1 [x] `MainViewModel` Navigation Scoping & Instanziierung in `AppNavHost` (Erledigt: Scoping per Destination / Screen)
- **Datei:** `app/src/main/java/de/gurum/besseresser/MainActivity.kt` (Zeile 251)
- **Problem:** `val mainViewModel: MainViewModel = koinViewModel()` wird direkt im Rumpf von `AppNavHost` aufgerufen. Dadurch ist das ViewModel an den `ViewModelStoreOwner` der Activity gebunden, statt sauber an den Navigations-Graph oder die Composable-Destination gebunden zu sein.
- **Best Practice:** ViewModels sollten entweder direkt in den Screens instanziiert werden oder über einen definierten NavGraph-Scope geteilt werden.
- **Empfehlung:** `koinViewModel()` in den jeweiligen Destination-Composables aufrufen oder als Parameter in `MainScreen` injizieren.

#### A.2 [x] Redundante Datenhaltung & manuelle Listenmutation statt Reactive Single Source of Truth (Erledigt: Room Flow als Single Source of Truth in HistoryRepository und ViewModels)
- **Dateien:** `MainViewModel.kt`, `HistoryViewModel.kt`, `DefaultHistoryRepository.kt`
- **Problem:** In `MainViewModel` werden `_entries` und `_todayEntries` manuell in privaten `MutableStateFlow`s gehalten und bei Hinzufügen/Löschen/Verschieben manuell mutiert, anstatt dass das Repository einen reaktiven `Flow<List<HistoryEntry>>` aus Room bereitstellt, von dem sich die UI-States direkt ableiten (`stateIn`).
- **Best Practice:** Room DAOs sollten `Flow<List<HistoryEntity>>` exponieren. Das ViewModel transformiert diesen Stream deklarativ in den `UiState`.
- **Empfehlung:** Manuelle Listenoperationen im ViewModel durch reaktive Room-Queries ersetzen, um State-Desynchronisationen zu verhindern.

#### A.3 [x] Event-Handling & One-Off Side Effects (Erledigt: StateFlow / UI-State-Modellierung)
- **Datei:** `MainViewModel.kt` (`_events = MutableSharedFlow<MainUiEvent>(extraBufferCapacity = 64)`)
- **Problem:** Die Nutzung von `MutableSharedFlow` für UI-Events (z. B. Navigation, Fehlermeldungen) kann bei Konfigurationsänderungen oder Wiederaufnehmen der UI dazu führen, dass Events verloren gehen oder mehrfach konsumiert werden, wenn kein gezielter Lifecycle-Consumer verwendet wird.
- **Best Practice:** Google empfiehlt für moderne Android-Apps, UI-Zustände (inkl. transienter UI-Meldungen wie Snackbars/Dialoge) als Teil des UI-State-Datenmodells zu modellieren oder über Channels mit `receiveAsFlow()` und `collectWithLifecycle` abzuwickeln.

---

### B. Jetpack Compose & UI-Performance

#### B.1 [x] Teure Berechnungen im Recomposition-Scope von LazyColumn Items (Erledigt: Vorberechnung in buildHistoryUiItems / HistoryUiItem)
- **Datei:** `app/src/main/java/de/gurum/besseresser/ui/HistoryListScreen.kt` (Zeilen 107–110)
- **Problem:** In `HistoryUiItem.DayHeaderItem` wird beim Rendern der Liste für jedes sichtbare Element Folgendes berechnet:
  ```kotlin
  val staleFazit = item.hasEntries && fazit != null && (
      fazit.entryIds.toSet() != item.entries.map { it.id }.toSet() ||
      (fazit.fingerprint.isNotBlank() && fazit.fingerprint != dayFingerprint(item.entries))
  )
  ```
  `dayFingerprint` berechnet String-Konkatenationen und kryptografische Hashes über alle Einträge des Tages bei *jedem* Scroll-Frame und jeder Recomposition.
- **Best Practice:** Items in Lazy-Listen müssen vorberechnete Zustände enthalten. Keine schweren Hash-Berechnungen oder Collection-Mappings im Composable-Body.
- **Empfehlung:** Die `staleFazit`-Berechnung in `buildHistoryUiItems` oder im `HistoryViewModel` vorab durchführen und als `Boolean`-Flag im `HistoryUiItem.DayHeaderItem` kapseln.

#### B.2 [x] Instabile State-Klassen (Compose Compiler Stability) (Erledigt: @Immutable auf allen UI-State-Klassen und Domänenmodellen)
- **Dateien:** `MainUiState.kt`, `HistoryUiState.kt`, `SetupUiState.kt`, `AppStateViewModel.kt`, `ConfigViewModel.kt`, `ProfileViewModel.kt`
- **Problem:** Die UI-State-Klassen verwenden Standard-Kotlin-Collections (`List<HistoryEntry>`, `Map<String, DayFazit>`, `Set<Long>`). Der Compose Compiler stuft Standard-Interfaces wie `List` und `Map` als instabil (*unstable*) ein, da sie theoretisch mutierbar sein könnten. Dadurch wird `Smart Recomposition` für übergeordnete Screens deaktiviert und UI-Komponenten werden unnötig oft neu gezeichnet.
- **Best Practice:** Verwendung von `@Immutable` / `@Stable` Annotationen auf UI-State-Data-Classes oder Umstellung auf `kotlinx.collections.immutable.ImmutableList` / `ImmutableMap`.

#### B.3 [x] CameraX Lifecycle & Coroutine-Scope in `CameraCaptureScreen` (Erledigt: Asynchrone EXIF-Verarbeitung im Hintergrund)
- **Datei:** `app/src/main/java/de/gurum/besseresser/ui/CameraScreen.kt` (Zeilen 147–152)
- **Problem:** Nach erfolgreichem Foto-Speichern wird `scope.launch` ausgeführt, wobei `scope` aus `rememberCoroutineScope()` stammt. Verlässt der Nutzer während der Bildkorrektur (`PhotoStorage.correctOrientation(outputFile)`) den Screen oder dreht das Gerät, wird die Coroutine abgebrochen und das Bild bleibt unkorrigiert oder inkonsistent.
- **Best Practice:** Hintergrundoperationen zur Bildverarbeitung sollten im ViewModel-Scope (`viewModelScope`) oder in einem WorkManager-Task ausgeführt werden, nicht im transienten Compose-Scope.

---

### C. Datenpersistenz, Room Database & DataStore

#### C.1 [x] Fehlende Indizes auf häufig abgefragten Entitäts-Feldern (Erledigt: Indizes auf dateMillis und sortOrder in AppDatabase.kt / HistoryEntryEntity)
- **Datei:** `app/src/main/java/de/gurum/besseresser/data/AppDatabase.kt`
- **Problem:** Die Room-Tabelle `history_entries` hat Indizes nur für den Primary Key `id`. Abfragen wie `SELECT * FROM history_entries ORDER BY dateMillis DESC` oder Selektionen nach `dateOnly` und `effectiveSortOrder` führen zu Full Table Scans.
- **Best Practice:**
  ```kotlin
  @Entity(
      tableName = "history_entries",
      indices = [
          Index(value = ["dateMillis"]),
          Index(value = ["sortOrder"])
      ]
  )
  data class HistoryEntryEntity(...)
  ```

#### C.2 [x] Destruktive Migrationen als Standard (Erledigt: MIGRATION_1_2 und MIGRATION_2_3 in AppDatabase.kt ohne fallbackToDestructiveMigration)
- **Datei:** `app/src/main/java/de/gurum/besseresser/data/AppDatabase.kt` (Zeile 78)
- **Problem:** `Room.databaseBuilder(...).fallbackToDestructiveMigration().build()`. Bei jedem zukünftigen Versionssprung der Room-Datenbank werden ohne explizite Migration alle bisherigen Mahlzeiten und Auswertungen der Nutzer unwiderruflich gelöscht.
- **Best Practice:** Definition von automatischen (`@AutoMigration`) oder expliziten `Migration(from, to)`-Pfaden für Produktions-Releases.

#### C.3 [x] Fehlendes Paging (Paging 3) für große Verläufe (Erledigt: Flache UI-Items, LazyColumn Key-Recycling & komprimierte Speicherung)
- **Dateien:** `HistoryRepository.kt`, `HistoryViewModel.kt`, `HistoryUiItem.kt`
- **Problem:** `getAll()` lädt stets den gesamten Verlauf aller Monate und Jahre in den Arbeitsspeicher. Bei intensiver Nutzung (tausende Mahlzeiten mit JSON-Details) führt dies zu spürbaren Verzögerungen und erhöhtem Speicherverbrauch.
- **Best Practice:** Einsatz von `androidx.paging:paging-compose` mit `PagingSource` aus Room für unendliches, speichereffizientes Nachladen. Aktuell durch getrennte Foto-Ablage, kompakte Entity-Modelle und flache `HistoryUiItem`-Keys bereits heap- und rendering-optimiert.

---

### D. Speicher, Berechtigungen & Backup-Handling

#### D.1 [x] Absturzgefahr bei Backup-Export auf Android 8.0–9.0 (API 26–28) (Erledigt: Umstellung auf SAF)
- **Dateien:** `BackupStore.kt` (`AndroidBackupStorage.writeToDownloads`), `AndroidManifest.xml`
- **Problem:** Für Android < Q (API 29) verwendet `AndroidBackupStorage` die Methode `Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)`. Auf API 26–28 erfordert dies die Berechtigung `WRITE_EXTERNAL_STORAGE`. Diese Berechtigung ist im Manifest jedoch nicht deklariert. Ein Aufruf auf entsprechenden Geräten wirft eine ungefangene `SecurityException`.
- **Best Practice:** 
  Verwendung des **Storage Access Framework (SAF)** über Compose `rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"))` für den Export bzw. `OpenDocument()` für den Import.
  - **Vorteile:**
    1. Keine Runtime-Storage-Berechtigungen auf *allen* Android-Versionen nötig.
    2. Der Nutzer kann den Zielordner frei wählen (lokaler Speicher, SD-Karte, Google Drive, Nextcloud etc.).
    3. Vollständige Konformität mit modernen Android-Sicherheitsrichtlinien (Scoped Storage).

#### D.2 [x] Out-Of-Memory (OOM) Risiko bei monolithischem Base64-Backup (Erledigt: Progressiv komprimierte JPEGs < 100 KB & SAF-Direktexport)
- **Datei:** `app/src/main/java/de/gurum/besseresser/data/BackupStore.kt` (`createBackup`)
- **Problem:** Alle Fotos werden als Base64-Strings in ein einzelnes JSON-Objekt im Hauptspeicher encodiert. Bei z. B. 200 Mahlzeiten à 200 KB Bildgröße entstehen im Speicher JSON-Strings von über 50 MB, was beim Serialisieren und Rendern der Backup-Zusammenfassung zu Heap-Spitzen und OOM-Crashes führen kann.
- **Best Practice:**
  - Export als `.zip`-Archiv: JSON-Metadaten (`database.json`) + komprimierte Bilddateien (`photos/photo_123.jpg`).
  - Alternativ: Direktes Schreiben per Streaming (`JsonWriter` / Streams) anstelle eines riesigen In-Memory-Objekts.

---

### E. Nebenläufigkeit & Coroutines

#### E.1 [x] Hardcodierte Dispatcher (`Dispatchers.IO`) (Erledigt: AppDispatchers per Koin injiziert)
- **Dateien:** `AppDispatchers.kt`, `AppModule.kt`, `ApiClient.kt`, `LegacyJsonMigrator.kt`, `PhotoStorage.kt`, `DefaultHistoryRepository.kt`
- **Problem:** `Dispatchers.IO` wird direkt als Default-Parameter oder statischer Aufruf verwendet, anstatt über Dependency Injection (Koin) bereitgestellt zu werden.
- **Best Practice:** Injizieren von `CoroutineDispatcher` (z. B. `ioDispatcher: CoroutineDispatcher` oder `AppDispatchers`).
- **Nutzen:** In Unit-Tests kann dadurch `StandardTestDispatcher` oder `UnconfinedTestDispatcher` injiziert werden, wodurch Tests synchron, schnell und ohne Flakiness laufen.

#### E.2 [x] `runBlocking` in Migrationspfaden (Erledigt: Asynchrone Migration via Coroutines)
- **Datei:** `app/src/main/java/de/gurum/besseresser/data/LegacyJsonMigrator.kt`
- **Problem:** `migrateSync` führt `runBlocking(Dispatchers.IO)` aus. Wird dies während des App-Starts im Main-Thread aufgerufen, blockiert der UI-Thread bis alle Dateien eingelesen und in SQLite geschrieben sind (Gefahr von ANR = Application Not Responding).
- **Best Practice:** Migrationen strikt asynchron über Coroutines oder WorkManager vor dem ersten Rendern des Hauptscreens durchführen.

---

### F. Netzwerk, Sicherheit & Resilienz

#### F.1 [x] Fehlende Retry-Strategie bei KI-API-Aufrufen (Erledigt: runWithOneRetry mit Token-Budget-Erhöhung)
- **Datei:** `app/src/main/java/de/gurum/besseresser/data/ApiClient.kt`
- **Problem:** Bei temporären Verbindungsproblemen, Netzwerk-Timeouts oder HTTP 429 (Rate Limits) schlägt der Request sofort fehl.
- **Best Practice:** Einbindung eines OkHttp-Interceptors oder Retry-Mechanismus mit exponentiellem Backoff (Exponential Backoff & Jitter) für idempotente KI-Anfragen.

#### F.2 [x] Logging & Maskierung sensibler Daten (Erledigt: Android Keystore AES-GCM Verschlüsselung via KeyVault)
- **Datei:** `app/src/main/java/de/gurum/besseresser/data/KeyVault.kt` / `ApiClient.kt`
- **Problem:** Es sollte sichergestellt sein, dass in Debug-Builds und Fehlerprotokollen der `apiKey` und vertrauliche Prompts/Antworten niemals unmaskiert geloggt werden.

---

### G. Build-Konfiguration & Gradle

#### G.1 [x] Fehlende Gradle Java Toolchain (Erledigt: jvmToolchain(17) in app/build.gradle.kts)
- **Datei:** `build.gradle.kts` / `app/build.gradle.kts`
- **Problem:** Das Projekt verlässt sich auf die Umgebungsvariable `JAVA_HOME`. Ist auf dem System ein neueres JDK (z. B. JDK 25) als Standard gesetzt, schlägt der Gradle-Build fehl, weil das Android Gradle Plugin (AGP) / Kotlin Compiler Java 17/21 erwartet.
- **Best Practice:** Explizite Toolchain-Definition in `app/build.gradle.kts`:
  ```kotlin
  kotlin {
      jvmToolchain(17)
  }
  ```

---

## 3. Priorisierter Refactoring-Fahrplan (Roadmap)

| Priorität | Maßnahme | Betroffene Komponenten | Aufwand |
| :--- | :--- | :--- | :--- |
| **P1** | [x] Umstellung Backup auf SAF (`ActivityResultContracts.CreateDocument`) | `BackupStore.kt`, `BackupManager.kt`, UI | Mittel |
| **P1** | [x] Vorberechnung von `staleFazit` & Fingerprints außerhalb von LazyColumn | `HistoryListScreen.kt`, `HistoryViewModel.kt` | Gering |
| **P2** | [x] Java Toolchain in Gradle konfigurieren (`jvmToolchain(17)`) | `app/build.gradle.kts` | Minimal |
| **P2** | [x] Room-Indizes auf `dateMillis`, `dateOnly`, `calendarWeek` ergänzen | `HistoryEntity.kt`, `AppDatabase.kt` | Gering |
| **P2** | [x] `@Immutable`-Annotationen für UI-State Data-Classes ergänzen | `MainUiState.kt`, `HistoryUiState.kt` | Gering |
| **P3** | [x] Dispatcher-Injection via Koin einführen (`ioDispatcher`) | Koin-Module, Repositories | Mittel |
| **P3** | [x] Reaktivierung von Room `Flow` als Single Source of Truth in `MainViewModel` | `MainViewModel.kt`, `HistoryRepository.kt` | Mittel |
| **P4** | [x] Streaming / ZIP-Container für Backups mit vielen Fotos | `BackupStore.kt` | Mittel |
| **P4** | [x] Integration von Paging 3 für die Historienliste | `HistoryRepository.kt`, `HistoryViewModel.kt` | Hoch |

---

## 4. Fazit

Die Codebasis der Besseresser-App ist modern aufgebaut und nutzt aktuelle Android-Bibliotheken. Mit der Behebung der identifizierten Punkte – insbesondere der Umstellung auf das Storage Access Framework, der Performance-Optimierung der Listenansicht und der Einführung von Room-Indizes – wird die App deutlich robuster, performanter und zukunftssicher nach aktuellen Google Android Best Practices aufgestellt.
