# Android Code-Review & Refactoring-Leitfaden (Best Practices)

## 1. Executive Summary & Nutzenanalyse

Dieses Dokument fasst das umfassende Code-Review der **Besseresser-App** zusammen. Ziel der Analyse ist die Maximierung des Gesamtnutzens für Endanwender (Performance, Akkulaufzeit, Stabilität, Datenschutz) sowie für das Entwicklungsteam (Wartbarkeit, Testbarkeit, Erweiterbarkeit und Minimierung technischer Schulden).

Die Codebasis zeichnet sich bereits durch eine moderne Grundlage aus (Jetpack Compose, Kotlin Coroutines & Flow, Koin Dependency Injection, Room & DataStore, Type-Safe Navigation). Dennoch wurden gezielte Optimierungspotenziale identifiziert, die durch einen hohen Return on Investment (ROI) das System robuster gegen Fehler machen und den Ressourcenverbrauch optimieren.

---

## 2. Übersicht der Findings (Prioritätenmatrix)

| ID | Kategorie | Finding | Priorität | Hebel / Nutzen | Status |
|---|---|---|---|---|---|
| **[x] PERF-01** | Performance & Memory | OOM-Gefahr durch ungepuffertes Bitmap-Dekodieren in `AndroidImagePreprocessor` | **Hoch** | Verhindert App-Crashes bei hochauflösenden Kamerafotos (12MP–108MP) | Erledigt |
| **ARCH-01** | Architektur & Threading | Synchrone Altdaten-Migration im `init`-Block von `DefaultHistoryRepository` | **Hoch** | Verhindert UI-Freezes / ANR (Application Not Responding) beim App-Start |
| **SEC-01** | Sicherheit & Robustheit | Stiller Klartext-Fallback bei Verschlüsselungsfehlern in `KeyVault` | **Mittel** | Schützt sensible API-Keys und verhindert inkonsistente Entschlüsselungszustände |
| **NET-01** | Netzwerk & Resilienz | Veraltetes `HttpURLConnection` statt modernem HTTP-Client (Ktor / OkHttp) | **Mittel** | Ermöglicht Connection Pooling, HTTP/2, sauberes Coroutine-Cancellation & Logging |
| **ARCH-02** | Architektur & Wartbarkeit | Monolithisches `HistoryViewModel` (>750 Zeilen) verletzt Single Responsibility | **Mittel** | Steigert Entwicklerproduktivität, Modularität und isolierte Testbarkeit |
| **CONC-01** | Nebenläufigkeit | Thread-unsichere `mutableMapOf` für anstehende Löschaufträge in `HistoryViewModel` | **Mittel** | Beseitigt potenzielle Race Conditions und State Inconsistencies |
| **UI-01** | Compose & UI-Performance | Fehlende `@Immutable` / `@Stable`-Stabilität auf Domänenmodellen | **Niedrig** | Reduziert unnötige Recompositions und schont CPU/Akku |
| **DATA-01** | Datenbank & Persistenz | JSON-Serialisierung von Domänenlisten in Room Entity-Spalten | **Niedrig** | Verbessert relationale Abfragefähigkeit und Datenintegrität |
| **A11Y-01** | Barrierefreiheit & UX | Fehlende `contentDescription`-Attribute und Touch-Target-Größen | **Niedrig** | Verbessert Accessibility für Screenreader und Bedienbarkeit |

---

## 3. Detaillierte Analyse & Handlungsempfehlungen

### [x] PERF-01: OOM-Prävention bei Bildverarbeitung (`AndroidImagePreprocessor`) [ERLEDIGT]
* **Datei:** `app/src/main/java/de/gurum/besseresser/data/ImagePreprocessor.kt` (Zeilen 27–38)
* **Problem:**
  In `prepareImageDataUri` wird `BitmapFactory.decodeStream(it)` aufgerufen, ohne zuvor die Bilddimensionen via `inJustDecodeBounds` zu prüfen oder `inSampleSize` anzuwenden. Bei modernen Smartphones (z. B. 48–108 MP Kamerasensoren) erzeugt dies unkomprimierte Bitmaps im dreistelligen Megabyte-Bereich im Heap, was unweigerlich zu `OutOfMemoryError` führt. Zudem wird die Ausgangsbitmap `bitmap` nicht recycelt, wenn `scaleDown()` eine neue Bitmap instanziiert.
* **Nutzen / Auswirkung:**
  Massive Reduzierung des Spitzen-Speicherverbrauchs, Schonung des Garbage Collectors und Eliminierung von Abstürzen bei Bildanalysen.
* **Empfohlene Lösung:**
  Zweistufiges Dekodieren mit `inSampleSize` (analog zu `PhotoStorage.writeScaledJpeg`) und konsequentes `recycle()` beider Bitmap-Instanzen im `finally`-Block:
  ```kotlin
  override fun prepareImageDataUri(photoUri: Uri, maxSide: Int): String {
      val resolver = context.contentResolver
      val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      resolver.openInputStream(photoUri)?.use { BitmapFactory.decodeStream(it, null, options) }
          ?: throw IllegalStateException("Foto konnte nicht gelesen werden.")

      var sample = 1
      while (maxOf(options.outWidth, options.outHeight) / sample > maxSide * 2) {
          sample *= 2
      }

      val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
      val originalBitmap = resolver.openInputStream(photoUri)?.use {
          BitmapFactory.decodeStream(it, null, decodeOptions)
      } ?: throw IllegalStateException("Foto konnte nicht decodiert werden.")

      try {
          val scaled = scaleDown(originalBitmap, maxSide)
          try {
              val jpeg = ByteArrayOutputStream().use { out ->
                  scaled.compress(Bitmap.CompressFormat.JPEG, COMPRESSION_QUALITY_JPEG, out)
                  out.toByteArray()
              }
              return "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
          } finally {
              if (scaled !== originalBitmap) scaled.recycle()
          }
      } finally {
          originalBitmap.recycle()
      }
  }
  ```

---

### ARCH-01: Blockierende I/O im Repository-Konstruktor (`DefaultHistoryRepository`)
* **Datei:** `app/src/main/java/de/gurum/besseresser/data/HistoryRepository.kt` (Zeilen 76–80)
* **Problem:**
  Im `init`-Block wird `LegacyJsonMigrator.migrateSync(...)` synchron aufgerufen. Da Koin-Singletons oft während des App-Starts oder beim ersten Screen-Zugriff auf dem Main-Thread aufgelöst werden, führt dies zu Festplatten-I/O und synchronen SQLite-Transaktionen im UI-Thread.
* **Nutzen / Auswirkung:**
  Schnellere Startzeit (Time-to-Interactive), Vermeidung von Framedrops und Beseitigung des Risikos von ANR-Dialogen auf langsameren Geräten.
* **Empfohlene Lösung:**
  Migration explizit asynchron über `withContext(dispatchers.io)` ausführen oder über einen dedizierten App-Startup-Worker / Initializer steuern.

---

### SEC-01: Stiller Fallback auf Klartext in `KeyVault`
* **Datei:** `app/src/main/java/de/gurum/besseresser/data/KeyVault.kt` (Zeilen 43–61)
* **Problem:**
  Wenn die Verschlüsselung via Android Keystore fehlschlägt, fängt `encrypt()` die Exception ab und speichert den Klartext-Key (`catch (e: Exception) { plain }`). Bei späterem `decrypt()` schlägt jedoch das Entschlüsseln des Klartexts fehl und liefert `null`. `SettingsRepository` versucht zwar einen Fallback (`KeyVault.decrypt(raw) ?: raw`), jedoch führt dies zu einem unbemerkten Sicherheitsrisiko und undurchsichtigen Fehlerzuständen.
* **Nutzen / Auswirkung:**
  Höhere Datensicherheit, Einhaltung von Compliance-Richtlinien und transparente Fehlerbehandlung für den Nutzer.
* **Empfohlene Lösung:**
  Entweder `Result<String>` zurückgeben oder bei Fehlschlag der Keystore-Initialisierung gezielt einen Fehler propagieren, anstatt Geheimnisse unbemerkt im Klartext in den Preferences zu persistieren.

---

### NET-01: Modernisierung des Netzwerk-Clients (`OpenAiChatClient` & `ApiClient`)
* **Dateien:** `app/src/main/java/de/gurum/besseresser/data/OpenAiChatClient.kt`, `ApiClient.kt`
* **Problem:**
  Für HTTP-Aufrufe wird `HttpURLConnection` eingesetzt. Dies erfordert manuelles Stream-Management, bietet kein standardmäßiges Connection-Pooling oder HTTP/2-Multiplexing und reagiert bei blockierenden Sockets nur eingeschränkt auf Coroutine-Stornierungen (Cancellation).
* **Nutzen / Auswirkung:**
  Reduzierte Latenz durch Verbindungswiederverwendung, geringerer Akkuverbrauch bei aufeinanderfolgenden API-Calls, sauberes Abbrechen von Anfragen beim Verlassen des Screens und bessere Debugging-Möglichkeiten (Interceptors/Logging).
* **Empfohlene Lösung:**
  Migration auf **Ktor Client** (bereits bestens in Kotlin Multiplatform & Serialization integriert) oder **OkHttp**:
  - Automatisches Connection Pooling und GZIP/HTTP2-Unterstützung.
  - Native Unterstützung für strukturierte Coroutine-Cancellations.
  - Auslagerung der ca. 400 Zeilen Regex- und Fallback-Parsing-Logik in einen spezialisierten, unit-testbaren `LlmResponseParser`.

---

### ARCH-02: Dekomposition des monolithischen `HistoryViewModel`
* **Datei:** `app/src/main/java/de/gurum/besseresser/ui/HistoryViewModel.kt` (>750 Zeilen)
* **Problem:**
  Das `HistoryViewModel` bündelt zu viele Verantwortlichkeiten:
  1. Laden und Filtern von Verlaufseinträgen.
  2. Berechnung, Fingerprinting und asynchrones Validieren von Tages- und Wochenfazits.
  3. Verwaltung von verzögerten Löschaufträgen (Undo-Logik) mit Timern.
  4. Aufnahme und Nacherfassung neuer Mahlzeiten (Kamera, Dialoge, Zeitauswahl).
  5. Profil- und Prompt-Generierung.
* **Nutzen / Auswirkung:**
  Deutlich gesteigerte Lesbarkeit, einfache Wartbarkeit, leichtere Einarbeitung neuer Entwickler und Vermeidung von Nebeneffekten bei Codeänderungen.
* **Empfohlene Lösung:**
  Aufteilung in fokussierte UseCases / Interactors oder Sub-ViewModels:
  - `HistoryListViewModel`: Reine Anzeige, Filterung und UI-State der Verlaufsliste.
  - `HistoryAddMealViewModel`: Steuerung der Mahlzeiten-Nacherfassung und Kamera-Interaktion.
  - `FazitManager` (Domain Use Case): Kapselung der Fazit-Berechnung und Cache-Invalidierung.

---

### CONC-01: Thread-Sicherheit bei verzögerten Löschvorgängen
* **Datei:** `app/src/main/java/de/gurum/besseresser/ui/HistoryViewModel.kt` (Zeilen 94–95, 150–190)
* **Problem:**
  `pendingDeleteJobs` und `pendingDeleteEntries` sind einfache `mutableMapOf()`, auf die aus mehreren Coroutines (z. B. UI-Klicks, Timer-Delays, `flushPendingDeletes`) zeitgleich ohne Synchronisationsmechanismus (z. B. `Mutex` oder `ConcurrentHashMap`) zugegriffen wird.
* **Nutzen / Auswirkung:**
  Vermeidung von `ConcurrentModificationException` und Dateninkonsistenzen (z. B. versehentlich nicht ausgeführte Löschungen).
* **Empfohlene Lösung:**
  Verwendung einer `Mutex` oder Verlagerung der gesamten State-Mutation in atomare StateFlow-Transformationen:
  ```kotlin
  private val deleteMutex = Mutex()
  // Zugriff absichern via deleteMutex.withLock { ... }
  ```

---

### UI-01: Recomposition-Optimierung in Jetpack Compose
* **Dateien:** `app/src/main/java/de/gurum/besseresser/model/Data.kt`, `HistoryUiState.kt`
* **Problem:**
  Klassen, die nicht explizit als `@Immutable` oder `@Stable` annotiert sind und Standard-Listen (`List<T>`) enthalten, werden vom Compose-Compiler oft als instabil (unstable) eingestuft. Dies führt dazu, dass Composables wie `HistoryItem` oder `FazitCard` bei jeder Zustandsänderung des Screens neu berechnet werden, selbst wenn sich ihre eigenen Daten nicht geändert haben.
* **Nutzen / Auswirkung:**
  Flüssigeres Scrolling mit konstanten 60/120 FPS auf der Verlaufsliste und spürbar reduzierter Akkuverbrauch.
* **Empfohlene Lösung:**
  1. Kennzeichnung von Domänen- und UI-State-Klassen mit `@Immutable`.
  2. Verwendung von `ImmutableList` (aus `kotlinx.collections.immutable`) in UI-States.
  3. Sicherstellung stabiler `key`-Parameter in allen `LazyColumn`-Items (`key = { it.id }`).

---

### DATA-01: Normalisierung komplexer Room-Entities
* **Datei:** `app/src/main/java/de/gurum/besseresser/data/AppDatabase.kt`
* **Problem:**
  `HistoryEntryEntity` speichert `goalResults: List<GoalResult>` als serialisierten JSON-String in einer Tabellenspalte ab.
* **Nutzen / Auswirkung:**
  Ermöglicht performante SQL-Abfragen (z. B. „Zeige alle Mahlzeiten mit roter Ampel für Ziel X“) direkt auf Datenbankebene, ohne alle Datensätze in den Speicher laden und im Kotlin-Code filtern zu müssen.
* **Empfohlene Lösung:**
  Für zukünftige Versionen eine 1:n-Relation via separater Tabelle `goal_results` mit Foreign Key auf `history_entries.id` evaluieren.

---

### A11Y-01: Barrierefreiheit & Touch-Target-Optimierung
* **Dateien:** `CameraScreen.kt`, `HistoryDetailScreen.kt`, `MainTopBar.kt`
* **Problem:**
  Einige Icons besitzen hardcodierte oder unvollständige `contentDescription`-Werte. Einzelne Klickflächen sind kleiner als die von Material Design empfohlenen 48x48 dp.
* **Nutzen / Auswirkung:**
  Bessere Zugänglichkeit für motorisch oder visuell eingeschränkte Nutzer (TalkBack-Unterstützung).
* **Empfohlene Lösung:**
  Lokalisierte `contentDescription`-Strings hinterlegen und bei kleinen Symbolen `Modifier.minimumInteractiveComponentSize()` nutzen.

---

## 4. Priorisierter Refactoring-Fahrplan (Roadmap)

1. **Phase 1: Sofortmaßnahmen (Stabilität & Crash-Prävention / Quick Wins)**
   - [x] Behebung des OOM-Risikos in `AndroidImagePreprocessor` (`inSampleSize` & explizites `recycle()`). [ERLEDIGT]
   - Absicherung der `pendingDeleteJobs`-Maps in `HistoryViewModel` via `Mutex`.
   - Asynchrone Kapselung der Migration in `DefaultHistoryRepository`.

2. **Phase 2: Struktur & Resilienz (Architektur & Sicherheit)**
   - Klare Fehlerbehandlung für den Android Keystore in `KeyVault`.
   - Entflechtung des `HistoryViewModel` in Use-Cases (`FazitCalculator`, `HistoryListViewModel`).
   - Umstellung des HTTP-Stacks von `HttpURLConnection` auf `Ktor` oder `OkHttp`.

3. **Phase 3: Feinpolitur & Performance (UI & Polish)**
   - Hinzufügen von `@Immutable`-Annotationen und `ImmutableList` für Compose-Stabilität.
   - Audit von `contentDescription` und Touch-Targets für Barrierefreiheit.
   - Ergänzung von Screenshot-/Compose-UI-Tests zur Absicherung von Regressionen.
