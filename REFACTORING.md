# Code-Review & Best-Practice-Analyse (Android)

Dieses Dokument fasst die Ergebnisse des umfassenden Code-Reviews der **Besseresser-App** zusammen. Der Fokus liegt auf Android-spezifischen Best Practices, Architekturprinzipien (Modern Android Architecture, MVI/MVVM), Jetpack Compose, Performance, Threading, Datenhaltung, Sicherheit und Testbarkeit.

---

## Inhaltsverzeichnis
1. [Übersicht & Zusammenfassung](#1-übersicht--zusammenfassung)
2. [Priorisierte Findings](#2-priorisierte-findings)
   - [A. Threading & Performance](#a-threading--performance)
   - [B. Architektur, State-Management & Navigation](#b-architektur-state-management--navigation)
   - [C. Jetpack Compose & UI-Performance](#c-jetpack-compose--ui-performance)
   - [D. Datenhaltung & Offline-First (Room, DataStore)](#d-datenhaltung--offline-first-room-datastore)
   - [E. Kamera, I/O & Ressourcen-Management](#e-kamera-io--ressourcen-management)
   - [F. Sicherheit & Privatsphäre](#f-sicherheit--privatsphäre)
   - [G. Testbarkeit & Dependency Injection (Koin)](#g-testbarkeit--dependency-injection-koin)
3. [Konkrete Refactoring-Roadmap & Empfehlungen](#3-konkrete-refactoring-roadmap--empfehlungen)

---

## 1. Übersicht & Zusammenfassung

Die Besseresser-App besitzt eine moderne technologische Basis mit **Kotlin**, **Jetpack Compose (Material 3)**, **Coroutines/Flow**, **Room**, **DataStore**, **Koin** und **CameraX**.

Gleichzeitig weist die Codebasis an mehreren Stellen Abweichungen von etablierten Android- und Compose-Best-Practices auf:
* **UI-Thread-Blockaden:** Bildverarbeitungen (EXIF-Korrektur und Bitmap-Decodierung) laufen teilweise direkt auf dem Main-Thread.
* **Recomposition-Overhead:** Unnötige Datenumwandlungen (`groupBy`, Datumsformatierungen mit `SimpleDateFormat`) finden innerhalb des Render-Trees ohne `remember` statt.
* **Architektur-Inkonsistenzen:** Vermischung von Navigation im State und direkten Callbacks, unvollständige reaktive Anbindung von Datenströmen (One-Shot-Reads vs. Flow-Observation).
* **Sicherheit:** Sensible Daten (API-Keys) werden im Klartext gespeichert.

---

## 2. Priorisierte Findings

Die Findings sind nach Schweregrad klassifiziert:
* 🔴 **HOCH:** Führt zu UI-Rucklern (Jank), ANRs (Application Not Responding), Dateninkonsistenzen oder Sicherheitsrisiken.
* 🟡 **MITTEL:** Verstößt gegen Architektur-Standards, erschwert Wartung, Testbarkeit oder führt zu unnötigem Ressourcenverbrauch.
* 🟢 **NIEDRIG:** Code-Smells, Modernisierungspotenzial, Accessibility- oder Stilfragen.

---

### A. Threading & Performance

#### 🔴 Finding A.1: Schwere Bildverarbeitung und EXIF-Korrektur auf dem Main-Thread (✅ Erledigt)
* **Ort:** `app/src/main/java/de/gurum\besseresser/ui/CameraScreen.kt` (Zeilen 69–96, 161–180)
* **Problem:** Nach dem Auslösen der Kamera wird `correctOrientation(outputFile)` im Callback `onImageSaved` aufgerufen, der über `ContextCompat.getMainExecutor(context)` auf dem UI-Thread läuft. `correctOrientation` decodiert ein volles Kamera-JPEG (`BitmapFactory.decodeFile`), erzeugt ein neues Bitmap via Matrix-Rotation und komprimiert es wieder als JPEG auf die Festplatte.
* **Auswirkung:** Blockiert den UI-Thread bei modernen Smartphone-Kameras für 200–800 ms. Führt zu sichtbaren Rucklern, Frame-Drops und potenziellen ANRs.
* **Empfehlung:** Bildrotation und I/O konsequent in `Dispatchers.IO` auslagern (`withContext(dispatchers.io)`), idealerweise gekapselt im `ImagePreprocessor` oder `PhotoStorage`.

#### 🔴 Finding A.2: Synchrone Bitmap-Decodierung im ViewModel-Call (✅ Erledigt)
* **Ort:** `app/src/main/java/de/gurum/besseresser/ui/HistoryDetailViewModel.kt` (Zeile 60–61)
* **Problem:** Die Methode `fun thumbnail(target: Int = 1280): Bitmap?` führt `photoStorage.decodeThumb(...)` synchron aus, wenn sie von der UI aufgerufen wird.
* **Auswirkung:** Synchrone Festplatten-I/O und Bitmap-Decodierung auf dem Thread des Aufrufers.
* **Empfehlung:** Bilder in Compose ausschließlich asynchron über Coil (`AsyncImage`) laden oder einen asynchronen State/Flow bereitstellen.
* **Status:** ✅ Erledigt – `HistoryDetailViewModel.thumbnail()`, `HistoryViewModel.decodeThumbnail()`, `HistoryRepository.decodeThumbnail()` und `PhotoStorage.decodeThumb()` wurden auf asynchrone Coroutine-Ausführung (`suspend fun` mit `Dispatchers.IO`) umgestellt.

#### 🟡 Finding A.3: Blockierendes `runBlocking` in Repositories (✅ Erledigt)
* **Ort:** `app/src/main/java/de/gurum/besseresser/data/Repositories.kt` (Zeilen 50–60, 230–240)
* **Problem:** In `SettingsRepositoryImpl` und `HistoryRepositoryImpl` wird `runBlocking` in synchronen Fallback-Methoden genutzt (z. B. `currentSettings()`), obwohl suspendierende Alternativen existieren.
* **Auswirkung:** Risiko von Deadlocks und UI-Blockaden beim Zugriff von außerhalb von Coroutinen.
* **Empfehlung:** `runBlocking` vollständig aus der Datenschicht entfernen. Aufrufer müssen Coroutinen (`viewModelScope`) oder reaktive Streams (`StateFlow`/`Flow`) nutzen.
* **Status:** ✅ Erledigt – Alle Repositories (`DefaultSettingsRepository`, `DefaultProfileRepository`, `DefaultUiPrefsRepository`, `DefaultHistoryRepository`) wurden vollständig auf nicht-blockierende Coroutinen (`suspend fun` und reaktive `Flow`s) umgestellt; `runBlocking` wurde restlos aus der Datenschicht entfernt und durch entsprechende Unit-Tests verifiziert.

---

### B. Architektur, State-Management & Navigation

#### 🟡 Finding B.1: Fehlende State-Reaktivität in `RecentNotesViewModel` (✅ Erledigt)
* **Ort:** `app/src/main/java/de/gurum/besseresser/ui/RecentNotesViewModel.kt` (Zeilen 15–40)
* **Problem:** `RecentNotesViewModel` lädt Notizen im `init`-Block einmalig via `loadRecentNotes(...)` in ein `MutableStateFlow` und mutiert dieses lokal. Der vorhandene `recentNotesFlow()` des Repositories wird nicht beobachtet.
* **Auswirkung:** Wenn Notizen an anderer Stelle (z. B. im `MainViewModel` oder `HistoryViewModel`) hinzugefügt oder modifiziert werden, bleibt der State in `RecentNotesViewModel` veraltet.
* **Empfehlung:** State reaktiv ableiten:
  ```kotlin
  val notes: StateFlow<List<RecentNote>> = settingsRepository.recentNotesFlow(sortStarredFirst = true)
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
  ```
* **Status:** ✅ Erledigt – `RecentNotesViewModel` leitet `notes` nun reaktiv über `settingsRepository.recentNotesFlow(sortStarredFirst = true).stateIn(...)` ab; Notiz-Aktionen (`toggleStar`, `push`) delegieren direkt an das Repository und der Zustand wird durch Unit-Tests verifiziert.

#### 🟡 Finding B.2: Unnötige Vollabfrage aller Historien-Einträge für Detailansicht (✅ Erledigt)
* **Ort:** `app/src/main/java/de/gurum/besseresser/ui/HistoryDetailViewModel.kt` (Zeilen 47–55)
* **Problem:** Um einen einzelnen Eintrag anzuzeigen, sammelt das ViewModel `historyRepository.getAllFlow()` (alle Einträge der Datenbank) und filtert im Speicher mit `list.find { it.id == entryId }`.
* **Auswirkung:** Unnötige Reaktivierung und Speicherallokation bei jeder Änderung an beliebigen Historien-Einträgen.
* **Empfehlung:** In `HistoryDao` und `HistoryRepository` eine Methode `fun getByIdFlow(id: Long): Flow<HistoryEntryEntity?>` ergänzen und direkt beobachten.
* **Status:** ✅ Erledigt – `HistoryDao` und `HistoryRepository` stellen nun `getByIdFlow(id)` bereit; `HistoryDetailViewModel` beobachtet gezielt nur den angefragten Eintrag reaktiv, wodurch unnötige Vollabfragen und Reaktivierungen vermieden werden. Abgedeckt durch entsprechende Unit-Tests.

#### 🟡 Finding B.3: Exponierung von Repositories über ViewModels (✅ Erledigt)
* **Ort:** `app/src/main/java/de/gurum/besseresser/ui/HistoryDetailViewModel.kt` (Zeile 29)
* **Problem:** `val historyRepository: HistoryRepository` ist als öffentliche Eigenschaft deklariert.
* **Auswirkung:** UI-Schichten können an der ViewModel-Kapselung vorbei direkt auf das Repository zugreifen (Verletzung des Schichtenmodells / Separation of Concerns).
* **Empfehlung:** Repository als `private val` deklarieren; nur benötigte Aktionen als ViewModel-Methoden anbieten.
* **Status:** ✅ Erledigt – `HistoryDetailViewModel` deklariert `historyRepository` nun als `private val`. Sämtliche UI-Interaktionen sind über ViewModel-Methoden gekapselt.

#### 🟡 Finding B.4: Doppelte und unkoordinierte Zustandsspeicherung (✅ Erledigt)
* **Ort:** `app/src/main/java/de/gurum/besseresser/ui/ProfileViewModel.kt` (Zeilen 70–96) und `MainActivity.kt`
* **Problem:** Beim Speichern des Profils triggert `ProfileViewModel.save()` eigenständig ein `profileRepository.save()`. Gleichzeitig ruft die `MainActivity` im Callback `appStateViewModel.saveProfile(p)` auf.
* **Auswirkung:** Doppelte Datenbank-Schreibvorgänge und unklarer Single Source of Truth für Profil-Zustände.
* **Empfehlung:** Speicherung zentral im `ProfileViewModel` belassen und den globalen `AppStateViewModel` rein reaktiv über Flow-Updates informieren.
* **Status:** ✅ Erledigt – Die Profil-Speicherung erfolgt nun zentral und ausschließlich über `ProfileViewModel.save()`. `MainActivity` navigiert nach dem Speichern ohne redundanten Aufruf zurück; die redundante Methode `saveProfile` wurde aus `AppStateViewModel` entfernt und `AppStateViewModel` empfängt Profilaktualisierungen rein reaktiv über den `ProfileRepository`-Flow. Verifiziert durch Unit-Tests.

#### 🟡 Finding B.5: Fehlende `SavedStateHandle`-Integration (✅ Erledigt)
* **Ort:** `app/src/main/java/de/gurum/besseresser/ui/MainViewModel.kt`, `HistoryViewModel.kt`, `SetupViewModel.kt`
* **Problem:** ViewModels nutzen kein `SavedStateHandle` zur Zustandssicherung bei Prozess-Beendigung im Hintergrund (System-Initiated Process Death).
* **Auswirkung:** Temporäre Notizeingaben oder Filtereinstellungen gehen verloren, wenn Android die App im Hintergrund beendet.
* **Empfehlung:** Wichtige UI-Zustände (z. B. eingegebener Text, ausgewählte Datumsfilter) an `SavedStateHandle` koppeln.
* **Status:** ✅ Erledigt – `MainViewModel`, `HistoryViewModel` und `SetupViewModel` sichern ihre flüchtigen UI-Zustände (Notizeingaben, Datums- und Zeitfilter, Dialog-Zustände, UI-Eingaben) persistent in `SavedStateHandle`. Bei Prozess-Wiederherstellung werden die Werte nahtlos rekonstruiert und Re-Initialisierungen überschreiben ungespeicherte Benutzereingaben nicht. Über umfassende Unit-Tests verifiziert.

---

### C. Jetpack Compose & UI-Performance

#### 🔴 Finding C.1: Teure Berechnungen und Allokationen im Composable-Body (✅ Erledigt)
* **Ort:** `app/src/main/java/de/gurum/besseresser/ui/HistoryListScreen.kt` (Zeilen 137–143, 376–377)
* **Problem:**
  1. `val rawGroups = groupByDay(entries)` und `val displayGroups = ...` werden bei jedem Recompose direkt im Funktionskörper ohne `remember(entries)` ausgeführt.
  2. In jedem Listenelement wird `SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(entry.dateMillis))` aufgerufen. `SimpleDateFormat` ist nicht thread-safe und erzeugt bei jedem Scroll-Frame neue Formatierer- und Date-Instanzen.
* **Auswirkung:** Erhöhte GC-Aktivität und Recomposition-Lags beim Scrollen langer Verlaufslisten.
* **Empfehlung:**
  - Gruppierung im ViewModel vorbereiten oder in `remember(entries) { ... }` kapseln.
  - Vorkonfigurierte, statische `DateTimeFormatter` (aus `java.time`) wiederverwenden.
* **Status:** ✅ Erledigt – `displayGroups` und `weeksWithEntries` werden per `remember(entries, today)` stabil gecacht. `SimpleDateFormat` und flüchtige `Date`-Allokationen wurden projektweit in den UI-Screens und Cards durch wiederverwendbare, thread-sichere `DateTimeFormatter`-Helfer (`formatTime`, `formatDate`, `formatDateTime`, `DATE_FORMATTER`, `TIME_FORMATTER`, `DATE_TIME_FORMATTER`) ersetzt. Durch Unit-Tests abgesichert.

#### 🟡 Finding C.2: Nebeneffekte in der LazyColumn-Konstruktion (✅ Erledigt)
* **Ort:** `app/src/main/java/de/gurum/besseresser/ui/HistoryListScreen.kt` (Zeilen 158–178)
* **Problem:** Ein lokales `val renderedWeeks = mutableSetOf<String>()` wird während der `LazyColumn`-DSL-Ausführung mutiert (`renderedWeeks.add(weekKey)`), um Wochenfazits nur einmal einzufügen.
* **Auswirkung:** Compose Lazy-Layouts können Elemente in beliebiger Reihenfolge und mehrfach evaluieren. Zustandskompensierende Mutationen während des Layout-Passes verletzen die funktionale Reinheit von Composables.
* **Empfehlung:** Die Liste der anzuzeigenden Elemente vorab im ViewModel als flache `List<HistoryUiItem>` (mit Subtypen `DayHeader`, `WeekFazitItem`, `MealEntryItem`) modellieren und als stabilen State an die `LazyColumn` übergeben.
* **Status:** ✅ Erledigt – Die Verlaufsliste wurde auf das flache, funktionale Modell `HistoryUiItem` (`WeekFazitItem`, `DayHeaderItem`, `MealEntryItem`) und die reine Konstruktionsfunktion `buildHistoryUiItems` umgestellt. `LazyColumn` iteriert nun seiteneffektfrei über stabile Items mit eindeutigen Schlüsseln. Durch Unit-Tests in `DomainLogicAndFingerprintTest` abgesichert.

#### 🟡 Finding C.3: Direkte Injektion von Domänenkomponenten in UI-Screens (✅ Erledigt)
* **Ort:** `app/src/main/java/de/gurum/besseresser/ui/MainScreen.kt` (Zeile 42), `HistoryScreen.kt` (Zeile 50)
* **Problem:** `val captureManager: PhotoCaptureManager = koinInject()` wurde direkt innerhalb der Composable-Funktionen injiziert, um temporäre Kamera-Dateien zu erzeugen.
* **Auswirkung:** Erschwert UI-Vorschauen (`@Preview`), erhöht die Kopplung der UI an Koin und umgeht das ViewModel.
* **Empfehlung:** Datei-Initialisierung und Kamera-Zustände über das `MainViewModel` bzw. `HistoryViewModel` steuern.
* **Status:** ✅ Erledigt – `PhotoCaptureManager` wurde als Interface mit `DefaultPhotoCaptureManager` abstrahiert und in `MainViewModel` sowie `HistoryViewModel` über Dependency Injection bereitgestellt. `MainScreen` und `HistoryScreen` beziehen Capture-Dateien nun direkt über `viewModel.createCaptureFile()`. Alle `koinInject()`-Aufrufe wurden aus der UI entfernt. Durch Unit-Tests in `RepositoriesAndFakesTest` und `KoinDiTest` verifiziert.

---

### D. Datenhaltung & Offline-First (Room, DataStore)

#### 🟡 Finding D.1: Fehlende Indizes auf Fremdschlüssel- und Datumsspalten in Room (✅ Erledigt)
* **Ort:** `app/src/main/java/de/gurum/besseresser/data/AppDatabase.kt`
* **Problem:** Die Tabelle `history_entries` führt Abfragen wie zeitbasierte Sortierungen und Filterungen aus, es war jedoch kein Index auf `dateMillis` bzw. `sortOrder` definiert.
* **Auswirkung:** Full Table Scans bei zeitbasierten Filterungen und bei der Retention-Bereinigung (`purgeOldEntries`).
* **Empfehlung:** Index auf `dateMillis` und `sortOrder` in `HistoryEntryEntity` hinzufügen und DB-Version migrieren.
* **Status:** ✅ Erledigt – `HistoryEntryEntity` deklariert nun Indizes auf `dateMillis` und `sortOrder`. Die Room-Datenbank wurde auf Version 3 angehoben und die Migration `MIGRATION_2_3` (`CREATE INDEX IF NOT EXISTS ...`) implementiert und in `AppDatabase.getInstance` eingebunden. Durch Unit-Tests in `RoomDatabaseAndViewModelTest` verifiziert.

#### 🟡 Finding D.2: Datenbank-Migrationen mit synchroner Dateibereinigung (✅ Erledigt)
* **Ort:** `app/src/main/java/de/gurum/besseresser/data/Repositories.kt` (Zeilen 390–440)
* **Problem:** Die Methode `ensureMigrated()` wurde in `HistoryViewModel.init` per `viewModelScope.launch` aufgerufen. Wenn gleichzeitig andere Operationen starteten, war die Migration eventuell noch nicht abgeschlossen.
* **Auswirkung:** Potenzielle Race Conditions beim App-Start und unsaubere Vermischung von Migrationslogik in allen Repository-Methoden.
* **Empfehlung:** Migrationen strukturiert über Room `Migration`-Objekte oder einen dedizierten Migrator vor der Bereitstellung der Repositories abschließen.
* **Status:** ✅ Erledigt – Die JSON-Alt-Migration wurde in das eigenständige `LegacyJsonMigrator`-Objekt ausgelagert. `DefaultHistoryRepository` führt die Migration deterministisch und thread-sicher beim Erstellen aus, bevor DAOs angesprochen werden. `HistoryRepository` und `HistoryViewModel.init` wurden von `ensureMigrated()` befreit, und alle redundanten Aufrufe aus den Repository-Methoden wurden entfernt. Umfassende Unit-Tests in `RepositoriesAndFakesTest` sichern das Verhalten ab.

---

### E. Kamera, I/O & Ressourcen-Management

#### 🟡 Finding E.1: Erzeugung von `PreviewView` außerhalb der AndroidView-Factory (✅ Erledigt)
* **Ort:** `app/src/main/java/de/gurum/besseresser/ui/CameraScreen.kt`
* **Problem:** `val previewView = remember { PreviewView(context) }` wurde im Composable-Scope erzeugt und an `AndroidView(factory = { previewView })` übergeben.
* **Auswirkung:** Kann bei Konfigurationsänderungen oder Wiederverwendung von Views zu Memory Leaks oder veralteten Kontext-Referenzen führen.
* **Empfehlung:** Idiomatische Nutzung der `AndroidView`-Factory:
  ```kotlin
  AndroidView(
      factory = { ctx ->
          PreviewView(ctx).apply {
              implementationMode = PreviewView.ImplementationMode.COMPATIBLE
          }
      },
      modifier = Modifier.fillMaxSize()
  )
  ```
* **Status:** ✅ Erledigt – `PreviewView` wird nun idiomatisch innerhalb der `AndroidView`-Factory mit `implementationMode = PreviewView.ImplementationMode.COMPATIBLE` erzeugt. Die Lifecycle-Bindung von CameraX sowie das saubere Ressourcen-Management bei Lifecycle-/Recomposition-Wechseln sind direkt an die View gekoppelt.

#### 🟡 Finding E.2: Verwaiste temporäre Fotodateien im Cache (✅ Erledigt)
* **Ort:** `app/src/main/java/de/gurum/besseresser/data/PhotoCaptureManager.kt`
* **Problem:** Beim Öffnen der Kamera erzeugte Zwischendateien (`photos/photo_*.jpg`) wurden bei Abbruch der Aufnahme nicht automatisch gelöscht.
* **Auswirkung:** Unnötiges Anwachsen des Cache-Speichers auf Nutzergeräten.
* **Empfehlung:** Lifecycle-gebundene Bereinigung nicht bestätigter Aufnahme-Dateien einführen.
* **Status:** ✅ Erledigt – `PhotoCaptureManager` wurde um Lifecycle-Methoden (`deleteCaptureFile(file)`, `deleteCaptureFile(uri)`, `cleanupOrphanedFiles(maxAgeMillis)`) erweitert. Bei Kamera-Abbrüchen (`onCancel`, `onBack`, `onRetake`, `onDismissCamera`, `onDismissReview`, Abbruch des System-Kamera-Intents) sowie beim `reset()` in `MainViewModel` und nach dem Speichern in Room werden temporäre Cache-Dateien sofort gelöscht. Zusätzlich bereinigt `DefaultPhotoCaptureManager` beim Start automatisch verwaiste und leere Cache-Dateien. Durch Unit-Tests in `RepositoriesAndFakesTest` und `UserFlowsTest` abgesichert.

---

### F. Sicherheit & Privatsphäre

#### 🔴 Finding F.1: Speicherung des KI-API-Keys im Klartext (✅ Erledigt)
* **Ort:** `app/src/main/java/de/gurum/besseresser/data/KeyVault.kt`, `Repositories.kt`
* **Problem:** Der API-Key (`Settings.apiKey`) wurde unverschlüsselt in Room/DataStore gespeichert. Bei gerooteten Geräten oder unverschlüsselten Backups war der Schlüssel im Klartext lesbar.
* **Auswirkung:** Sicherheitsrisiko für Nutzerkonten (OpenAI/Anthropic/Custom API Keys).
* **Empfehlung:** Sensible Zugangsdaten über `EncryptedSharedPreferences` oder den Android Keystore (z. B. via `KeyVault` mit AES-GCM / 256-Bit KeyGenParameterSpec) sichern.
* **Status:** ✅ Erledigt – Der API-Key wird hardwaregestützt über `KeyVault` mit AES-GCM (256-Bit-Schlüssel im Android Keystore) verschlüsselt und sicher in den Preferences persistiert. Die Ver- und Entschlüsselung wurde gehärtet (Thread-Sicherheit via `@Synchronized`, sichere Puffer- und Header-Validierung, Null-Handling bei ungültigem Chiffrat, Rückwärtskompatibilität für Klartext-Migration). Durch Unit-Tests in `RepositoriesAndFakesTest` abgesichert.

#### 🟡 Finding F.2: Fehlende TLS/HTTPS-Erzwingung bei benutzerdefinierten Endpunkten (✅ Erledigt)
* **Ort:** `app/src/main/java/de/gurum/besseresser/data/ApiClient.kt`, `NetworkSecurity.kt`, `SetupScreen.kt`, `SetupViewModel.kt`
* **Problem:** `baseUrl` kann vom Nutzer frei eingegeben werden. Es fand keine Prüfung statt, ob unverschlüsseltes HTTP verwendet wird.
* **Auswirkung:** Gefahr von Man-in-the-Middle-Angriffen (Abfangen von Essensfotos und API-Keys).
* **Empfehlung:** HTTPS für externe API-Endpunkte vorschreiben bzw. Warnung anzeigen, wenn Cleartext-Traffic konfiguriert wird.
* **Status:** ✅ Erledigt – `NetworkSecurity` erzwingt TLS/HTTPS für alle externen KI- und API-Endpunkte und wirft bei unverschlüsseltem Remote-HTTP eine `InsecureEndpointException`. Lokale Entwicklungs- und Test-Hosts (`localhost`, `127.0.0.1`, `10.0.2.2`, `[::1]`) sind analog zur `network_security_config.xml` weiterhin per Cleartext-HTTP zulässig. In `SetupScreen` und `SetupViewModel` wird der Sicherheitsstatus direkt validiert, mit Fehlermeldungen bzw. Hinweisen versehen und das Speichern unsicherer Endpunkte unterbunden. Umfassende Unit-Tests in `NetworkSecurityTest` sichern alle Pfade ab.

---

### G. Testbarkeit & Dependency Injection (Koin)

#### 🟡 Finding G.1: Geringe Testabdeckung in der Kernlogik
* **Ort:** `app/src/test/java/de/gurum/besseresser/`
* **Problem:** Es existieren lediglich Unit-Tests für `PromptBuilderTest` und `ModelUtilsTest`. ViewModels (`MainViewModel`, `HistoryViewModel`, `SetupViewModel`), Repositories und Daos sind ungetestet.
* **Auswirkung:** Hohes Regressionsrisiko bei Refactorings oder Funktionserweiterungen.
* **Empfehlung:** Unit-Tests für alle ViewModels unter Verwendung von `TestDispatcher` / `StandardTestDispatcher` und gefakten Repositories aufbauen.

#### 🟢 Finding G.2: Große Quelldateien mit vielen Verantwortlichkeiten
* **Ort:**
  - `HistoryListScreen.kt` (732 Zeilen – UI, Gesten, Sortierung, Drag&Drop)
  - `HistoryViewModel.kt` (620 Zeilen – State, Fazit-Validierung, Undo-Delete, KI-Anbindung)
  - `Repositories.kt` (578 Zeilen – Interfaces und mehrere Implementierungen in einer Datei)
* **Problem:** Monolithische Klassen erschweren die Wartung und Navigation im Code.
* **Empfehlung:** Aufteilung in kleinere, spezialisierte Dateien (z. B. separate `HistoryRepositoryImpl.kt`, `SettingsRepositoryImpl.kt` und modulare UI-Komponenten).

---

## 3. Konkrete Refactoring-Roadmap & Empfehlungen

| Phase | Handlungsfeld | Konkrete Maßnahmen | Aufwand |
|---|---|---|---|
| **Phase 1: Performance & Stabilität** | Threading & I/O | • `correctOrientation` in `Dispatchers.IO` verschieben.<br>• `groupByDay` und `SimpleDateFormat` in Listen eliminieren.<br>• `HistoryDetailViewModel.thumbnail()` auf asynchrones Laden umstellen. (✅ Erledigt) | Gering |
| **Phase 2: Sicherheit & Datenintegrität** | Security & DB | • API-Key mit Android Security Crypto verschlüsseln.<br>• DB-Indizes für `dateMillis` anlegen.<br>• `getByIdFlow` in Room-DAO implementieren. | Mittel |
| **Phase 3: Architektur & Reaktivität** | State & ViewModels | • `RecentNotesViewModel` auf echten Flow umstellen.<br>• Flache UI-Modelle (`HistoryUiItem`) für `LazyColumn` einführen.<br>• `HistoryRepository` aus ViewModel-Signaturen entfernen. | Mittel |
| **Phase 4: Testbarkeit & Clean Code** | Testing & Modularisierung | • Unit-Tests für ViewModels (`MainViewModelTest`, `HistoryViewModelTest`) schreiben.<br>• Große Dateien (`Repositories.kt`, `HistoryListScreen.kt`) in fokussierte Module zerlegen. | Mittel |
