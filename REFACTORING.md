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
   - [3.5 Sicherheit & Datenschutz](#35-sicherheit--datenschutz)
   - [3.6 Android Lifecycle & Lokalisierung](#36-android-lifecycle--lokalisierung)
   - [3.7 Dependency Injection & Testbarkeit](#37-dependency-injection--testbarkeit)
   - [3.8 Code-Style, Naming & Lokalisierung](#38-code-style-naming--lokalisierung)
4. [Empfohlene Roadmap & Nächste Schritte](#4-empfohlene-roadmap--nächste-schritte)

---

## 1. Management Summary

Die Besseresser-App verfügt über eine moderne technische Basis (Kotlin 2.0+, Jetpack Compose, Material 3, Type-Safe Navigation Compose, Room, DataStore, Koin). Die bestehende Unit-Test-Abdeckung für Serialisierung, Repositories und Fingerprint-Berechnungen ist solide.

Gleichzeitig weisen zentrale Bereiche der Codebasis deutliche Abweichungen von etablierten Android Best Practices auf:
- **Performance / UI-Jank:** Synchrone Bilddecodierung hochauflösender Fotos auf dem Main-Thread innerhalb von Composables (`remember { BitmapFactory.decodeStream(...) }`) ohne Downsampling oder Image-Loader (wie Coil).
- **Architektur & Separation of Concerns:** Direkte Übergabe von `android.content.Context` an ViewModels und UI-gesteuerte Business-Logik (z. B. Triggerung von KI-Fazits aus `LaunchedEffect`).
- **Threading:** Verwendung von blockierendem `runBlocking` auf dem Main-Thread während des Activity-Starts (`attachBaseContext`).
- **Sicherheit:** `cleartextTrafficPermitted="true"` global im gesamten Netzwerk-Manifest aktiv und API-Keys unverschlüsselt im Standard-DataStore.
- **Resilienz & Datenhaltung:** `fallbackToDestructiveMigration()` bei Room-Datenbanken aktiv (Gefahr des kompletten Datenverlusts bei Updates) und `configChanges` in `MainActivity` deaktiviert Standard-Activity-Recreations.

---

## 2. Prioritäten-Matrix

| Priorität | Bereich | Finding | Auswirkung |
|---|---|---|---|
| **P0 (Kritisch)** | Performance / Memory | Main-Thread Bitmap Decoding & fehlende Downsampling-Strategie | UI-Ruckler (Jank), Frame-Drops, `OutOfMemoryError` (OOM) |
| **P0 (Kritisch)** | Threading | `runBlocking` in `MainActivity.attachBaseContext` | Längere Startzeiten, Risiko von Application Not Responding (ANR) |
| **P0 (Kritisch)** | Sicherheit | Globales `cleartextTrafficPermitted="true"` & Klartext-API-Keys | Sicherheitsrisiko für sensible Nutzerdaten & API-Zugänge |
| **P1 (Hoch)** | Architektur | `Context` in ViewModels & UI-getriebene Side-Effects | Speicherlecks, schlechte Testbarkeit, instabile State-Machines |
| **P1 (Hoch)** | Persistence | `fallbackToDestructiveMigration()` in Room | Verlust aller Verlaufsdaten bei Schema-Änderungen |
| **P1 (Hoch)** | Compose | Monolithische Composables (z. B. `MainScreen.kt` > 900 Zeilen) | Mangelnde Wiederverwendbarkeit, unnötige Recompositions |
| **P2 (Mittel)** | Lifecycle / System | `android:configChanges` & manueller Locale ContextWrapper | Umgeht Standard-Lifecycle, ignoriert Android 13+ Per-App-Language-APIs |
| **P2 (Mittel)** | Testing & DI | Harte `Dispatchers.IO`-Referenzen & fehlende Dispatcher-Injection | Erschwerte deterministische Unit- & Coroutine-Tests |
| **P3 (Niedrig)** | Code-Qualität | Denglish-Bezeichner & String-basierte Magic Keys | Wartungsaufwand, Fehleranfälligkeit bei Refactorings |

---

## 3. Detaillierte Findings & Best Practice Empfehlungen

### 3.1 UI & Jetpack Compose

#### Finding 3.1.1: Synchrones Decodieren von Bitmaps auf dem Main-Thread
- **Betroffene Dateien:** `MainScreen.kt` (Z. 275, Z. 419), `CameraScreen.kt` (Z. 309), `ManualEntryDialog.kt` (Z. 371)
- **Problem:**
  Bitmaps werden direkt während der Composition bzw. im Main-Thread synchron über `BitmapFactory.decodeStream(ctx.contentResolver.openInputStream(uri))` oder `BitmapFactory.decodeFile(...)` geladen:
  ```kotlin
  // Anti-Pattern: Blockiert UI-Thread und lädt volle Sensorauflösung (z.B. 48 MP) in den RAM
  val bmp = remember(uri) {
      try {
          ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
      } catch (e: Exception) { null }
  }
  ```
- **Risiko:** Starkes Ruckeln beim Öffnen von Reviews/Dialogen, UI-Blockaden und `OutOfMemoryError` (OOM) auf Geräten mit begrenztem Arbeitsspeicher.
- **Empfohlene Best Practice:**
  1. Integration von **Coil** (`io.coil-kt:coil-compose`): Coil lädt Bilder asynchron im Hintergrund, nutzt Memory- und Disk-Caches und passt die Bitmap-Größe automatisch an die Containergröße (`AsyncImage`) an.
  ```kotlin
  // Best Practice mit Coil
  AsyncImage(
      model = ImageRequest.Builder(LocalContext.current)
          .data(photoUri)
          .crossfade(true)
          .build(),
      contentDescription = stringResource(R.string.food_photo_desc),
      modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
      contentScale = ContentScale.Crop
  )
  ```

---

#### Finding 3.1.2: State & File-Erzeugung in `remember` ohne Re-Evaluation
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

#### Finding 3.1.3: Monolithische Screen-Dateien und fehlende Dekomposition
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

#### Finding 3.2.1: Android `Context` in ViewModels und Domain-Klassen
- **Betroffene Dateien:** `ProfileViewModel.kt` (Z. 70), `SetupViewModel.kt` (Z. 108), `PromptBuilder.kt` (Z. 62)
- **Problem:**
  Methoden wie `ProfileViewModel.save(ctx: Context?, ...)` oder `SetupViewModel.startSplitGoals(ctx: Context?, ...)` nehmen `Context`-Referenzen entgegen, um Fehlermeldungen oder Strings aus Ressourcen zu lesen.
- **Risiko:** Bruch der Schichtentrennung, Gefahr von Memory Leaks und Verunmöglichung reiner JVM-Unit-Tests ohne Robolectric/Mocks.
- **Empfohlene Best Practice:**
  ViewModels dürfen **keine** `Context`-Instanzen halten oder als Parameter annehmen. Für lokalisierte Fehlermeldungen sollte ein `UiText`-Wrapper (Sealed Interface) verwendet werden:
  ```kotlin
  sealed interface UiText {
      data class DynamicString(val value: String) : UiText
      class StringResource(@StringRes val resId: Int, vararg val args: Any) : UiText
      
      @Composable
      fun asString(): String = when (this) {
          is DynamicString -> value
          is StringResource -> stringResource(resId, *args)
      }
  }
  ```

---

#### Finding 3.2.2: UI-getriebene Geschäftslogik und Seiteneffekte
- **Betroffene Dateien:** `HistoryScreen.kt` (`HistoryAutoFazitEffect`), `MainScreen.kt` (`createAnalyzePrompts`)
- **Problem:**
  In `HistoryScreen.kt` entscheidet ein Composable (`HistoryAutoFazitEffect` mit `LaunchedEffect`), ob ein Tages- oder Wochenfazit fällig ist, baut Strings und Prompts mithilfe von `LocalContext` zusammen und ruft `viewModel.generateFazit(...)` auf.
- **Risiko:** Wird der Screen verlassen oder re-composed, können Side-Effects unterbrochen oder mehrfach ausgeführt werden. Die Logik existiert nicht auf ViewModel-/Domain-Ebene.
- **Empfohlene Best Practice:**
  Auslagerung in einen **Use Case** / Interactor (z. B. `EvaluateDayFazitUseCase`), der reaktiv auf Datenänderungen im `HistoryRepository` hört oder im ViewModel-Scope ausgeführt wird. Die UI beobachtet ausschließlich den resultierenden Zustand (`UiState`).

---

#### Finding 3.2.3: Imperative ViewModel-Initialisierung (`startObserving()`)
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

#### Finding 3.3.1: Blockierendes `runBlocking` im Main-Thread während des App-Starts
- **Betroffene Dateien:** `MainActivity.kt` (Z. 154–160)
- **Problem:**
  ```kotlin
  override fun attachBaseContext(newBase: Context) {
      val lang = runBlocking { uiPrefsRepository.currentLanguage() }
      super.attachBaseContext(newBase.withAppLanguage(lang))
  }
  ```
  `runBlocking` zwingt den UI-Thread zum Warten auf DataStore I/O beim Kaltstart der Activity.
- **Risiko:** Verlängerte App-Startzeit (Startup Latency), Warnungen im Android Vitals Dashboard, ANR-Gefahr bei I/O-Lags.
- **Empfohlene Best Practice:**
  Nutzung der offiziellen Android 13+ App-Sprachen-API (siehe Abschnitt 3.6). DataStore-Werte sollten rein asynchron über StateFlow konsumiert werden.

---

#### Finding 3.3.2: Harte `Dispatchers.IO`-Referenzen in Klassen
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

### 3.5 Sicherheit & Datenschutz

#### Finding 3.5.1: Global aktivierter Klartext-Netzwerkverkehr (`cleartextTrafficPermitted`)
- **Betroffene Dateien:** `app/src/main/res/xml/network_security_config.xml` (Z. 6)
- **Problem:**
  ```xml
  <base-config cleartextTrafficPermitted="true">
      <trust-anchors>
          <certificates src="system" />
      </trust-anchors>
  </base-config>
  ```
- **Risiko:** Die App erlaubt unverschlüsselte HTTP-Verbindungen für alle Domains. Man-in-the-Middle-Angriffe (MitM) können API-Keys und private Ernährungsfotos/Gesundheitsdaten abfangen.
- **Empfohlene Best Practice:**
  `cleartextTrafficPermitted="false"` als Default setzen. Falls lokale Entwicklungs-Server (z. B. lokales Ollama auf `http://192.168.x.x`) unterstützt werden müssen, sollte dies ausschließlich über explizite `<domain-config>` für lokale IP-Bereiche im Debug-Build freigeschaltet werden:
  ```xml
  <network-security-config>
      <base-config cleartextTrafficPermitted="false" />
      <!-- Nur für lokale Entwicklung / Debugging -->
      <domain-config cleartextTrafficPermitted="true">
          <domain includeSubdomains="true">localhost</domain>
          <domain includeSubdomains="true">10.0.2.2</domain>
      </domain-config>
  </network-security-config>
  ```

---

#### Finding 3.5.2: Unverschlüsselte Speicherung sensibler API-Keys
- **Betroffene Dateien:** `SettingsRepository.kt`, `UiPrefsRepository.kt`
- **Problem:**
  API-Keys (OpenAI, OpenRouter etc.) werden im Standard-Jetpack-Preferences-DataStore als Klartext gespeichert.
- **Empfohlene Best Practice:**
  Nutzung von **EncryptedSharedPreferences** oder `EncryptedDataStore` in Verbindung mit dem Android Keystore für sensible Schlüssel und Credentials.

---

### 3.6 Android Lifecycle & Lokalisierung

#### Finding 3.6.1: `android:configChanges` in `MainActivity`
- **Betroffene Dateien:** `app/src/main/AndroidManifest.xml` (Z. 31)
- **Problem:**
  ```xml
  android:configChanges="orientation|screenSize|keyboardHidden"
  ```
- **Risiko:** Das Deaktivieren der Activity-Recreation bei Orientierungswechseln und Größenänderungen (z. B. Split-Screen, Foldables) umgeht Standard-Lifecycle-Mechanismen. UI-Zustände, die nicht in Compose `rememberSaveable` oder ViewModels liegen, können asynchron inkonsistent werden.
- **Empfohlene Best Practice:**
  Entfernen von `android:configChanges` und Sicherstellen, dass alle Screens und Dialoge robust gegen Recreations sind (getestet mit "Don't keep activities" / Developer Options).

---

#### Finding 3.6.2: Manuelle Locale-Hacks statt Android 13+ App-Sprachen API
- **Betroffene Dateien:** `MainActivity.kt` (`withAppLanguage`), `MainActivity.attachBaseContext`
- **Problem:**
  Die App manipuliert `ConfigurationContext` und `Locale.setDefault(...)` manuell in `attachBaseContext` und `CompositionLocalProvider`, obwohl `locales_config.xml` bereits vorhanden ist.
- **Empfohlene Best Practice:**
  Migration auf `AppCompatDelegate.setApplicationLocales()` bzw. `android.app.LocaleManager` (ab Android 13, API 33). Dies wird vom Betriebssystem nativ unterstützt, funktioniert ohne `runBlocking` und integriert sich in die Systemeinstellungen von Android.

---

### 3.7 Dependency Injection & Testbarkeit

#### Finding 3.7.1: Fehlende UI- & Screenshot-Tests
- **Problem:**
  Es existieren derzeit Unit-Tests für Serialisierung, Repositories und Room, jedoch keine Compose-UI-Tests (`createComposeRule`) oder Screenshot-Regressionstests (z. B. mit Roborazzi oder Paparazzi).
- **Empfohlene Best Practice:**
  Hinzufügen von UI-Tests für zentrale User Flows:
  - Setup Flow (Ersteinrichtung der API)
  - Manuelle Nach-Erfassung (`ManualEntryDialog`)
  - Historien-Filterung und Detailansicht

---

### 3.8 Code-Style, Naming & Lokalisierung

#### Finding 3.8.1: Vermischung von Deutsch und Englisch (Denglish)
- **Betroffene Dateien:** Projektweit (z. B. `TrafficLight` vs. `erkannt`, `MainViewModel.showErkanntEdit`, `DayFazit`, `mealLines`)
- **Empfohlene Best Practice:**
  Einheitliche Naming Convention im Code: **Englisch für allen Code** (Klassen, Variablen, Methoden, Kommentare, DB-Tabellen), Lokalisierung (Deutsch/Englisch) ausschließlich in `strings.xml`.
  - Beispiel: `erkannt` -> `recognizedMeal`, `DayFazit` -> `DailySummary`, `TrafficLight` -> `TrafficLight`.

---

## 4. Empfohlene Roadmap & Nächste Schritte

### Phase 1: Stabilität, Performance & Sicherheit (Sofortmaßnahmen)
1. **Bildverarbeitung optimieren:** Coil einbinden und synchrone `BitmapFactory`-Aufrufe aus Composables eliminieren.
2. **Sicherheitslücke schließen:** `cleartextTrafficPermitted="false"` in `network_security_config.xml` umstellen.
3. **Room-Sicherheit:** `fallbackToDestructiveMigration()` entfernen und Migrationsstrategie etablieren.
4. **Main-Thread Entlastung:** `runBlocking` in `attachBaseContext` durch native Locale-APIs ersetzen.

### Phase 2: Architektur-Refactoring & Entkopplung (Mittelfristig)
1. **Clean ViewModels:** Alle `Context`-Parameter aus ViewModels (`ProfileViewModel`, `SetupViewModel`) entfernen und auf `UiText` / Resource-IDs umstellen.
2. **Decomposition:** `MainScreen.kt` in fokussierte Sub-Composables zerlegen.
3. **Domain Layer einführen:** `GenerateFazitUseCase` und `AnalyzeMealUseCase` erstellen, um Business-Logik aus `HistoryScreen` / `MainScreen` zu extrahieren.
4. **Dispatcher Injection:** `CoroutineDispatchers` für saubere Testbarkeit injizierbar machen.

### Phase 3: Qualitäts- & Test-Offensive (Langfristig)
1. **Compose-Tests & Screenshot-Testing:** Integration von `androidx.compose.ui.test` und Roborazzi für Snapshot-Tests.
2. **Naming-Konsolidierung:** Schrittweise Vereinheitlichung auf englische Bezeichner in Datenmodellen und Repositories.
3. **Encrypted Storage:** Umstellung der API-Key-Speicherung auf EncryptedSharedPreferences / Keystore.
