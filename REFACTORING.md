# Code-Review & Refactoring-Empfehlungen: Besseresser-App

Dieses Dokument fasst die Ergebnisse des umfassenden Code-Reviews der **Besseresser Android App** zusammen. Der Fokus liegt auf **Android-spezifischen Best Practices**, moderner Android-Entwicklung (MAD - Modern Android Development), Architektur-Richtlinien von Google, Jetpack Compose Best Practices, Nebenläufigkeit, Sicherheit und Wartbarkeit.

---

## 1. Executive Summary & Gesamtbewertung

Die Besseresser-App weist bereits eine solide moderne Basis auf:
- Nutzung von **Jetpack Compose** mit Material 3 und Type-Safe Navigation Compose.
- **Koin** als leichtgewichtiges Dependency-Injection-Framework.
- **Ktor Client** und **KotlinX Serialization** für API-Kommunikation.
- **Room Database** und **Preferences DataStore** für lokale Persistenz.
- Eigener **AppDispatchers**-Wrapper für testbare Coroutine-Dispatchers.

Gleichzeitig wurden im Rahmen des Reviews mehrere **Architektur-, Lifecycle-, Performance- und Sicherheits-Potenziale** identifiziert, die die Stabilität, Recomposition-Effizienz und Wartbarkeit der App deutlich verbessern können.

### Übersicht der Findings nach Schweregrad

| Kategorie | High | Medium | Low / Best Practice | Gesamt |
| :--- | :---: | :---: | :---: | :---: |
| **Architektur & State Management** | 1 | 3 | 2 | **6** |
| **UI & Jetpack Compose** | 1 | 3 | 2 | **6** |
| **Data Layer & Persistenz** | 1 | 2 | 2 | **5** |
| **Security & Privacy** | 1 | 2 | 1 | **4** |
| **Concurrency & Coroutines** | 0 | 2 | 1 | **3** |
| **Netzwerk & Error Handling** | 0 | 2 | 1 | **3** |
| **Kamera & Medienverarbeitung** | 0 | 2 | 1 | **3** |
| **Lokalisierung & i18n** | 0 | 1 | 2 | **3** |
| **Testbarkeit & DI** | 0 | 2 | 1 | **3** |
| **Gesamt** | **4** | **19** | **13** | **36** |

---

## 2. Detaillierte Findings nach Themenbereichen

### A. Architektur & State Management

#### [HIGH] [ERLEDIGT] A.1: Nebeneffekt-gesteuerte Geschäftslogik in Composables (`LaunchedEffect` in `HistoryScreen`)
- **Ort:** `app/src/main/java/de/gurum/besseresser/ui/HistoryScreen.kt` (Zeilen 87–96)
- **Problem:**
  In `HistoryScreen` wird über einen `LaunchedEffect`, der auf mehrere State-Eigenschaften (`uiState.entries`, `uiState.fazitMap`, `uiState.weekFazitMap`, `settings`, `profile`) hört, automatisiert die KI-Fazit-Generierung (`viewModel.checkAndTriggerAutoFazits`) angestoßen.
  - Dies koppelt kritische Geschäftslogik und Netzwerkaufrufe an den UI-Kompositions-Lifecycle.
  - Bei Konfigurationsänderungen oder Navigation können unnötige Trigger oder abgebrochene Coroutines auftreten.
- **Best Practice:**
  Geschäftslogik gehört in den ViewModel- oder Domain-/UseCase-Layer. Das `HistoryViewModel` sollte reaktiv auf Änderungen aus den Repositories lauschen (z. B. via Flows) und Fazit-Prüfungen selbstständig koordinieren, ohne dass die UI dies per `LaunchedEffect` anstoßen muss.

#### [MEDIUM] [ERLEDIGT] A.2: Getrennte Coroutine-Collections für UI-State in ViewModels
- **Ort:** `app/src/main/java/de/gurum/besseresser/ui/HistoryViewModel.kt` (Zeilen 91–115)
- **Problem:**
  Im `init`-Block von `HistoryViewModel` werden drei separate Coroutines gestartet, die unabhängig voneinander `repo.getAllFlow()`, `repo.getDayFazitsFlow()` und `repo.getWeekFazitsFlow()` sammeln und `_uiState` aktualisieren.
  - Dies kann zu inkonsistenten Zwischenzuständen (Tearing) führen, wenn Datenströme asynchron eintreffen.
- **Best Practice:**
  Nutzung von `combine` oder `stateIn`:
  ```kotlin
  val uiState: StateFlow<HistoryUiState> = combine(
      historyRepository.getAllFlow(),
      historyRepository.getDayFazitsFlow(),
      historyRepository.getWeekFazitsFlow(),
      _additionalUiState
  ) { entries, dayFazits, weekFazits, additional ->
      buildHistoryUiState(entries, dayFazits, weekFazits, additional)
  }.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = HistoryUiState()
  )
  ```

#### [MEDIUM] [ERLEDIGT] A.3: Kontextabhängige Prompt-Generierung in der UI-Schicht
- **Ort:** `app/src/main/java/de/gurum/besseresser/ui/HistoryScreen.kt`, `PromptBuilder.kt`
- **Problem:**
  Prompt-Erzeugungsfunktionen (wie `createDayFazitPrompts(ctx)`) verlangen einen Android `Context` und werden von UI-Composables instanziiert und als Callbacks/Lambdas an ViewModels übergeben.
  - Das bricht die Trennung zwischen UI und ViewModel/Domain.
- **Best Practice:**
  Einführung eines `PromptRepository` oder `PromptUseCase`, der über `ApplicationContext` oder lokalisierte String-Provider (`UiText` / `StringProvider`) verfügt. ViewModels fordern Prompts direkt aus dem Domain-Layer an.

#### [MEDIUM] [ERLEDIGT] A.4: Unvollständige Wiederherstellung über `SavedStateHandle`
- **Ort:** `app/src/main/java/de/gurum/besseresser/ui/MainViewModel.kt`
- **Problem:**
  Während einfache String- und Boolean-Felder in `SavedStateHandle` gesichert werden, wird das aktuelle `Evaluation`-Objekt bei Process Death nicht persistiert. Führt das System einen Process-Kill im Hintergrund aus, geht die noch ungespeicherte KI-Bewertung verloren.
- **Best Practice:**
  Da `Evaluation` mit `@Serializable` annotiert ist, kann es als JSON-String im `SavedStateHandle` gesichert und bei Prozesswiederherstellung deserialisiert werden.

#### [LOW] A.5: UI-Transformationslogik innerhalb von Composables
- **Ort:** `app/src/main/java/de/gurum/besseresser/ui/HistoryListScreen.kt` (Zeilen 72–76)
- **Problem:**
  In `HistoryListContent` wird `buildHistoryUiItems(entries, today, nowHour)` innerhalb von `remember` berechnet.
- **Best Practice:**
  Die Umwandlung von Roh-Entitäten in Anzeige-Items (`HistoryUiItem`) sollte direkt im ViewModel oder in einem UI-State-Mapper auf Hintergrund-Threads stattfinden.

#### [LOW] A.6: Duplizierte ViewModel-Delegationen für Datei-Operationen
- **Ort:** `MainViewModel.kt` & `HistoryViewModel.kt`
- **Problem:**
  Beide ViewModels leiten Methoden wie `createCaptureFile()`, `deleteCaptureFile()` 1:1 an `PhotoCaptureManager` weiter.
- **Best Practice:**
  UI-Komponenten können direkt auf den über Koin injizierten `PhotoCaptureManager` zugreifen oder über ein gemeinsames Camera-Management-UseCase/Delegate operieren.

---

### B. UI & Jetpack Compose

#### [HIGH] B.1: Unremembered Lambda in CameraCaptureScreen führt zu Re-Bind-Risiko
- **Ort:** `app/src/main/java/de/gurum/besseresser/ui/CameraScreen.kt` (Zeilen 106–131)
- **Problem:**
  In `CameraCaptureScreen` wird `val bindCamera: (PreviewView) -> Unit = { ... }` bei jeder Rekomposition neu instanziiert, da sie nicht mit `remember` umschlossen ist.
  - Wenn `CameraCaptureScreen` rekomponiert wird (z. B. durch Tastatur-Einblendung oder Textfeld-Änderung), wird die Lambda-Referenz neu erzeugt.
- **Best Practice:**
  Wickeln der Lambda-Erstellung in `remember(lifecycleOwner, context) { ... }`.

#### [MEDIUM] B.2: Verwendung von dynamischen Datums-/Zeitabfragen in Composition
- **Ort:** `app/src/main/java/de/gurum/besseresser/ui/HistoryListScreen.kt`
- **Problem:**
  `LocalDate.now()` und `LocalTime.now().hour` werden direkt im Composable ausgewertet. Bei jeder Rekomposition entstehen neue Objektinstanzen.
- **Best Practice:**
  Zeit- und Datumsbezüge als State aus dem ViewModel bereitstellen.

#### [MEDIUM] B.3: Swipe-to-Dismiss / Gesture-Handler außerhalb von Foundation-Standards
- **Ort:** `app/src/main/java/de/gurum/besseresser/ui/HistoryItem.kt`, `HistoryScreen.kt`
- **Problem:**
  Eigene Gestenverarbeitung (`pointerInput` mit `detectHorizontalDragGestures`) wird für Swipe-Gesten genutzt, statt die offiziellen Material 3 / Compose Foundation APIs (`SwipeToDismissBox`) zu verwenden.
- **Best Practice:**
  Nutzung von `androidx.compose.material3.SwipeToDismissBox` für konsistentes Animations-, Velocity- und Barrierefreiheitsverhalten.

#### [MEDIUM] B.4: Fehlende Stabilitäts-Annotationen für UI-State-Klassen
- **Ort:** `MainUiState.kt`, `HistoryUiState.kt`, `DetailUiState.kt`
- **Problem:**
  Klassen, die Standard-Collections (`List<T>`, `Set<T>`, `Map<K, V>`) enthalten, gelten im Compose Compiler standardmäßig als unbeständig (unstable), was zu unnötigen Rekompositionen von Subtrees führen kann.
- **Best Practice:**
  Verwendung von `@Immutable` / `@Stable` Annotationen oder Immutable Collections (`kotlinx.collections.immutable.ImmutableList`).

#### [LOW] B.5: Hardcodierte Alphawerte und Farben statt Theme-Tokens
- **Ort:** `CameraScreen.kt`, `CommonUi.kt`, `AmpelUi.kt`
- **Problem:**
  Verwendung von `Color.Black.copy(alpha = 0.5f)` oder spezifischen Hex-Werten (`0xFF1B7F4C`) direkt in Composables anstelle von `MaterialTheme.colorScheme` Rollen.
- **Best Practice:**
  Definition von Semantic Custom Color Extensions (z. B. `TrafficLightColors`) auf dem MaterialTheme.

#### [LOW] B.6: ContentDescriptions bei rein dekorativen Elementen
- **Ort:** Diverse Icons in `AmpelUi.kt` und `DayGroupHeader.kt`
- **Problem:**
  Einige Icons haben keinen oder redundanten `contentDescription`-Text, was Screenreader verwirren kann.
- **Best Practice:**
  Rein dekorative Icons explizit mit `contentDescription = null` versehen; interaktive Elemente mit präzisen, lokalisierten Strings beschreiben.

---

### C. Data Layer & Persistenz

#### [HIGH] C.1: FallbackToDestructiveMigration ohne Versionierungsstrategie
- **Ort:** `app/src/main/java/de/gurum/besseresser/data/BesseresserDatabase.kt` (Zeile 42)
- **Problem:**
  Die Room-Datenbank ist mit `.fallbackToDestructiveMigration()` konfiguriert.
  - Bei künftigen Schema-Änderungen (z. B. neue Tabellen oder veränderte Spalten) führt dies zum vollständigen Datenverlust der Nutzerhistorie ohne Warnung.
- **Best Practice:**
  Implementierung expliziter Room-Migrationen (`Migration(1, 2)`) und automatisierter Migrationstests (`MigrationTestHelper`).

#### [MEDIUM] C.2: Synchroner Cache vs. Single Source of Truth in `SettingsRepository`
- **Ort:** `app/src/main/java/de/gurum/besseresser/data/SettingsRepository.kt`
- **Problem:**
  `SettingsRepository` hält lokale In-Memory-Kopien von `Settings`, `Profile`, etc. und aktualisiert diese parallel zu den DataStore-Writes.
  - Bei parallelen Schreibvorgängen oder externen Änderungen besteht das Risiko von Desynchronisation.
- **Best Practice:**
  DataStore `data`-Flows als alleinige Single Source of Truth (SSOT) verwenden und mit `stateIn(Eagerly)` als Cache exponieren.

#### [MEDIUM] C.3: Große JSON-Blobs in SharedPreferences/DataStore für MRU-Notizen
- **Ort:** `app/src/main/java/de/gurum/besseresser/model/Store.kt`, `SettingsRepository.kt`
- **Problem:**
  `RecentNotes` (inklusive kompletter gecachter `Evaluation`-Objekte) werden als serialisierter JSON-String in einer DataStore-Preference gespeichert.
  - Das Parsing und Re-Serialisieren der gesamten Liste bei jedem Notiz-Update ist ineffizient und skaliert schlecht bei vielen Einträgen.
- **Best Practice:**
  Auslagerung strukturierter Daten wie `RecentNotes` mit gecachten Bewertungen in eine dedizierte Room-Tabelle (`recent_notes`).

#### [LOW] C.4: Fehlende Transaktionskapselung bei kombinierten Room-Operationen
- **Ort:** `app/src/main/java/de/gurum/besseresser/data/HistoryRepository.kt`
- **Problem:**
  Methoden wie `duplicateToDate` führen mehrere Einzelschritte (Eintrag einfügen, Foto kopieren) ohne `@Transaction`-Klammerung aus.
- **Best Practice:**
  Nutzung von `@Transaction` auf DAO-Ebene bzw. `database.withTransaction { ... }`.

#### [LOW] C.5: Unused Foreign Keys / Indices
- **Ort:** `app/src/main/java/de/gurum/besseresser/model/Data.kt` (`HistoryEntity`)
- **Problem:**
  Tages- und Wochenschlüssel (`dateMillis`) werden häufig für Gruppierungen und Filter abgefragt, verfügen aber über keinen expliziten Index.
- **Best Practice:**
  Hinzufügen von `@Entity(indices = [Index("dateMillis")])` zur Beschleunigung von Abfragen.

---

### D. Security & Privacy

#### [HIGH] D.1: Unverschlüsselte Speicherung des API-Keys in Preferences DataStore
- **Ort:** `app/src/main/java/de/gurum/besseresser/model/Data.kt` (`Settings.apiKey`)
- **Problem:**
  Der OpenAI/LLM-API-Key wird im Klartext in der unverschlüsselten DataStore-Datei (`besseresser_prefs.preferences_pb`) auf dem Dateisystem abgelegt.
  - Auf gerooteten Geräten oder bei unverschlüsselten Backups ist der Schlüssel im Klartext lesbar.
- **Best Practice:**
  Speicherung des API-Keys in `EncryptedSharedPreferences` (Jetpack Security) oder Verschlüsselung des Wertes mittels des **Android KeyStore Providers** (AES-256-GCM).

#### [MEDIUM] D.2: Backup-Export enthält sensible API-Schlüssel
- **Ort:** `app/src/main/java/de/gurum/besseresser/data/BackupManager.kt`
- **Problem:**
  Beim JSON-Backup-Export wird das gesamte `Settings`-Objekt inklusive `apiKey` exportiert.
  - Teilt der Nutzer ein Backup oder speichert es unverschlüsselt ab, wird der API-Schlüssel preisgegeben.
- **Best Practice:**
  Optionale Checkbox beim Export ("API-Schlüssel in Backup einschließen").

#### [MEDIUM] D.3: `allowBackup="true"` im AndroidManifest ohne differenzierte Backup-Regeln
- **Ort:** `app/src/main/AndroidManifest.xml`
- **Problem:**
  `android:allowBackup="true"` ist ohne `android:fullBackupContent` oder `android:dataExtractionRules` konfiguriert.
  - Bei ADB-Backups werden unverschlüsselte Zugangsdaten extrahiert.
- **Best Practice:**
  Definition von `res/xml/data_extraction_rules.xml` und `res/xml/backup_rules.xml`, um sensible Einstellungsdateien vom Cloud-/ADB-Backup auszuschließen.

#### [LOW] D.4: Exception-Logging bei Netzwerkfehlern
- **Ort:** `app/src/main/java/de/gurum/besseresser/data/BesseresserApiService.kt`
- **Problem:**
  Fehlermeldungen aus HTTP-Antworten könnten potenziell sensible Header oder Tokens im `e.message` enthalten.
- **Best Practice:**
  Sanitization von Fehlermeldungen vor der UI-Präsentation.

---

### E. Concurrency & Coroutines

#### [MEDIUM] E.1: Unnötiges `Dispatchers.IO`-Wrapping für suspendierende Room- und Ktor-Aufrufe
- **Ort:** Diverse Stellen in `HistoryRepository.kt` und `BesseresserApiService.kt`
- **Problem:**
  Room und Ktor Client sind intern vollständig asynchron und "Main-Safe". Das manuelle Kapseln von `dao.insert()` oder `client.post()` in `withContext(dispatchers.io)` ist redundant.
- **Best Practice:**
  `Dispatchers.IO` gezielt nur für blockierende Datei- und Bitmap-I/O-Operationen (`PhotoStorage`, `BitmapFactory`) reservieren.

#### [MEDIUM] E.2: Fehlende Kooperation bei Job-Cancellation in Datei-Bereinigungen
- **Ort:** `app/src/main/java/de/gurum/besseresser/data/HistoryRepository.kt` (`cleanupOldData`)
- **Problem:**
  In Schleifen, die alte Fotos und Einträge löschen, fehlt `ensureActive()` / `yield()`.
- **Best Practice:**
  Einfügen von `ensureActive()` bei iterativen I/O-Löschoperationen.

#### [LOW] E.3: `runCatching` schluckt `CancellationException`
- **Ort:** `app/src/main/java/de/gurum/besseresser/data/ImagePreprocessor.kt`, `PhotoStorage.kt`
- **Problem:**
  Standard-`runCatching` fängt auch `CancellationException` ab, was die Structured Concurrency unterbrechen kann.
- **Best Practice:**
  Nutzung von `runCatchingCancelable` oder explizites Re-Throw von `CancellationException`:
  ```kotlin
  inline fun <T, R> T.runSuspendCatching(block: T.() -> R): Result<R> {
      return try {
          Result.success(block())
      } catch (c: CancellationException) {
          throw c
      } catch (e: Throwable) {
          Result.failure(e)
      }
  }
  ```

---

### F. Netzwerk & Error Handling

#### [MEDIUM] F.1: Grobes Error-Mapping auf generische Exception-Strings
- **Ort:** `app/src/main/java/de/gurum/besseresser/data/BesseresserApiService.kt`
- **Problem:**
  HTTP 401 (Ungültiger API-Key), HTTP 429 (Rate Limit / Quota), HTTP 5xx (Server-Fehler) und Timeouts werden als generischer String `Result.failure(Exception("..."))` zurückgegeben.
- **Best Practice:**
  Einführung einer typsicheren Fehlerhierarchie:
  ```kotlin
  sealed interface ApiError {
      data object InvalidApiKey : ApiError
      data object QuotaExceeded : ApiError
      data object NetworkTimeout : ApiError
      data class ServerError(val code: Int, val message: String?) : ApiError
      data class Unknown(val throwable: Throwable) : ApiError
  }
  ```

#### [MEDIUM] F.2: Fehlende Verbindungs-Timeouts und Retry-Strategien in Ktor
- **Ort:** `app/src/main/java/de/gurum/besseresser/data/KoinModule.kt`
- **Problem:**
  Der Ktor `HttpClient` verfügt über keine explizit konfigurierten `HttpTimeout`-Plugins (Connect Timeout, Socket Timeout, Request Timeout).
- **Best Practice:**
  Konfiguration des `HttpTimeout`-Plugins in Ktor mit realistischen Timeouts (z. B. 30s Connect, 60s Socket Timeout für Bild-Analysen).

#### [LOW] F.3: Unbegrenzter Request-Body-Buffer bei Base64-Encodierung
- **Ort:** `app/src/main/java/de/gurum/besseresser/data/ImagePreprocessor.kt`
- **Problem:**
  Bilder werden komplett in Base64-Strings im Arbeitsspeicher konvertiert, bevor sie in das JSON-Payload eingebettet werden.
- **Best Practice:**
  Maximalgröße von komprimierten Bildern vor der Encodierung streng auf z. B. 1024x1024 px begrenzen (bereits teilweise implementiert).

---

### G. Kamera & Medienverarbeitung

#### [MEDIUM] G.1: Bitmap-Decodierung ohne `inBitmap`-Wiederverwendung
- **Ort:** `app/src/main/java/de/gurum/besseresser/data/PhotoStorage.kt`
- **Problem:**
  Beim Erstellen von Thumbnails und bei der EXIF-Korrektur werden wiederholt neue Bitmaps allokiert. Bei schnellen Bilderserien kann dies Garbage-Collection-Pausen verursachen.
- **Best Practice:**
  Verwendung von `BitmapFactory.Options.inSampleSize` und Kapselung der Ladevorgänge über **Coil** (das bereits im Projekt vorhanden ist) anstelle manueller Bitmap-Decodierung.

#### [MEDIUM] G.2: Datei-Bereinigung bei unvollständigen Aufnahmen
- **Ort:** `app/src/main/java/de/gurum/besseresser/ui/HistoryScreen.kt`, `MainScreen.kt`
- **Problem:**
  Temporäre Foto-Dateien werden bei Abbruch teilweise manuell gelöscht, können aber bei unerwartetem Schließen der App im Cache verbleiben.
- **Best Practice:**
  Periodische Bereinigung des Capture-Verzeichnisses beim App-Start im `PhotoCaptureManager`.

#### [LOW] G.3: Hardcodierte JPEG-Kompressionsqualität
- **Ort:** `app/src/main/java/de/gurum/besseresser/data/ImagePreprocessor.kt`
- **Problem:**
  Qualitätsstufe `82` ist als Magic Number hardcodiert.
- **Best Practice:**
  Als benannte Konstante (`COMPRESSION_QUALITY_JPEG = 82`) definieren.

---

### H. Lokalisierung & Internationalisierung (i18n)

#### [MEDIUM] H.1: Formatierung von Zahlen und Datumsangaben ohne explizite User-Locale
- **Ort:** `app/src/main/java/de/gurum/besseresser/ui/CommonUi.kt`, `HistoryItem.kt`
- **Problem:**
  Datums- und Zeitformatierer (`DateTimeFormatter`) nutzen teilweise die JVM-Default-Locale anstelle der in den App-Einstellungen gewählten Sprache (`appState.language`).
- **Best Practice:**
  Explizite Übergabe der konfigurierten `appLanguage.locale` an alle Formatierer.

#### [LOW] H.2: Verstreute String-Resource-Auflösungen über Context
- **Ort:** `PromptBuilder.kt`, `HistoryViewModel.kt`
- **Problem:**
  Direkte Aufrufe von `context.getString(...)` in Helper-Dateien.
- **Best Practice:**
  Konsequente Nutzung der im Projekt bereits angelegten `UiText`-Abstraktion.

#### [LOW] H.3: Fehlende Plural-Ressourcen in Teilbereichen
- **Ort:** `app/src/main/res/values/strings.xml`
- **Problem:**
  Einige Strings mit Zahlenangaben (z. B. "X Wochen") nutzen normale String-Templates statt `<plurals>`.
- **Best Practice:**
  Nutzung von `res/values/plurals.xml` für korrekte grammatikalische Formen (Einzahl/Mehrzahl).

---

### I. Testbarkeit & Dependency Injection

#### [MEDIUM] I.1: ViewModel-Konstruktoren mit Default-Werten statt sauberer DI-Injektion
- **Ort:** `MainViewModel.kt`, `HistoryViewModel.kt`
- **Problem:**
  `dispatchers: AppDispatchers = AppDispatchers()` und `savedStateHandle: SavedStateHandle = SavedStateHandle()` sind mit Default-Parametern belegt.
  - In Koin sollte `get()` für Dispatcher und `get()` / `SavedStateHandle` verwendet werden, um Fakes in Tests deterministisch injizieren zu können.
- **Best Practice:**
  Injektion aller Abhängigkeiten über das Koin-Modul ohne Fallback-Instanziierung im Konstruktor.

#### [MEDIUM] I.2: Fehlende Schnittstellen (Interfaces) für Repositories und Services
- **Ort:** `HistoryRepository`, `SettingsRepository`, `BesseresserApiService`
- **Problem:**
  Repositories sind als konkrete Klassen ohne Interfaces implementiert.
  - Tests müssen auf In-Memory-Datenbanken oder Vererbung zurückgreifen, statt leichtgewichtige Mock/Fake-Implementierungen nutzen zu können.
- **Best Practice:**
  Einführung von Interfaces (`interface HistoryRepository`, `class DefaultHistoryRepository : HistoryRepository`).

#### [LOW] I.3: Android-spezifische TestUri-Hilfsklasse
- **Ort:** `app/src/test/java/android/net/TestUri.kt`
- **Problem:**
  Um `android.net.Uri` in JVM-Unit-Tests ohne Robolectric zu nutzen, wurde ein Package-Mocking verwendet.
- **Best Practice:**
  Kapselung von `Uri` in Domain-Modellen oder Verwendung von `Robolectric` für testsichere Android-Klassen.

---

## 3. Priorisierte Refactoring-Roadmap

### Phase 1: Stabilität & Sicherheit (Sofortmaßnahmen)
1. **API-Key Verschlüsselung:** Migration der `Settings.apiKey`-Persistenz auf `EncryptedSharedPreferences` / Android KeyStore.
2. **Room Migrationen:** Entfernen von `.fallbackToDestructiveMigration()` und Anlegen von `MIGRATION_1_2`.
3. **LaunchedEffect-Entkoppelung:** Verlagern der Auto-Fazit-Logik von `HistoryScreen.kt` in `HistoryViewModel.kt`.
4. **CameraX Lifecycle:** Absicherung von `bindCamera` via `remember` in `CameraScreen.kt`.

### Phase 2: Architektur & State Flow (Mittelfristig)
1. **StateFlow-Harmonisierung:** Zusammenführung getrennter Coroutine-Flows in `HistoryViewModel` mittels `combine`.
2. **SavedStateHandle-Vervollständigung:** Persistierung von ungespeicherten `Evaluation`-Objekten in `MainViewModel`.
3. **Repository-Interfaces:** Abstraktion von `HistoryRepository` und `SettingsRepository` für optimierte Testbarkeit.
4. **Timeouts & Fehlerhierarchie:** Ktor `HttpTimeout`-Plugin integrieren und `ApiError`-Sealed-Types einführen.

### Phase 3: UI-Optimierung & Cleanup (Langfristig)
1. **Material 3 SwipeToDismissBox:** Migration der benutzerdefinierten Swipe-Logik auf Compose Standard-Komponenten.
2. **RecentNotes Room-Tabelle:** Auslagerung der MRU-Notizen aus DataStore in eine eigene Room-Entität.
3. **Konsequente UiText-Nutzung:** Beseitigung aller verbleibenden `Context.getString()`-Aufrufe außerhalb von UI-Composables.
4. **Compose Compiler Metrics:** Prüfung und Optimierung von Recomposition-Hotspots mit Compose Compiler Metrics.

---

## 4. Fazit

Die Besseresser-App verfügt über eine moderne, saubere Architektur mit Compose und Koin. Durch die Umsetzung der in diesem Dokument beschriebenen Best Practices wird die App noch robuster gegen Datenverlust, sicherer im Umgang mit API-Schlüsseln, flüssiger in der UI-Rendern und langfristig einfacher zu warten und zu testen.
