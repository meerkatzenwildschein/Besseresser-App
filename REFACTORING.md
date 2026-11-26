# REFACTORING.md – Besseresser App

Dieses Dokument enthält eine strukturierte Analyse und einen konkreten Maßnahmenplan für Code-Verbesserungen, Architektur-Refactorings, Bibliotheks-Aktualisierungen und Android Best-Practices für die **Besseresser**-App.

Ziel ist es, die Wartbarkeit, Stabilität, Testbarkeit und Entwicklungsgeschwindigkeit mit maximalem Nutzen bei minimalem Risiko zu steigern.

---

### Priorisierungsmatrix (Nutzen vs. Aufwand)

| Bereich | Maßnahme | Nutzen (Utility) | Aufwand | Priorität |
| :--- | :--- | :--- | :--- | :--- |
| **Build & Libs** | Migration zu Gradle Version Catalogs (`libs.versions.toml`) | Sehr hoch | Gering | 🟢 Sofort |
| **Sonar & Clean Code** | Behebung von Sonar-Issues (S107 Parameter-Flut, S3776 Komplexität, S6532/S1172/S6619) | Hoch | Gering–Mittel | 🟢 Sofort |
| **DI & Architektur** | Vollständiges Koin-Binding von UseCases & Repositories (Konstruktor-Bereinigung) | Hoch | Gering | 🟢 Sofort |
| **Architektur** | Strukturierte Concurrency & Entkopplung von `LegacyJsonMigrator` in Repositories | Hoch | Gering | 🟢 Sofort |
| **Netzwerk & Core** | `OpenAiChatClient` refaktorieren (Injizierbarkeit, asynchrone Coroutine-Aufrufe) | Hoch | Mittel | 🟡 Hoch |
| **Data & DB** | Room `exportSchema = true` & automatische Migrationstests | Hoch | Gering | 🟡 Hoch |
| **Compose & UI** | String-Entkopplung in ViewModels (`UiText` flächendeckend nutzen) | Mittel | Mittel | 🟡 Mittel |
| **Compose & UI** | Immutable Collections (`ImmutableList`) für stabile Recomposition & `@Preview`s | Mittel | Mittel | 🟡 Mittel |
| **Dependencies** | Update auf neuere Compose BOM, Coil 3, Navigation / Lifecycle Libs | Mittel | Mittel | 🟡 Mittel |
| **Testing** | Einführung von *Turbine* für Flow-Tests & Compose UI-Tests | Hoch | Mittel | 🟡 Hoch |

---

### 1. Build-System & Dependency-Management

#### 1.1 Gradle Version Catalog (`gradle/libs.versions.toml`)
* **Ist-Zustand:** Versionen und Abhängigkeiten sind als Variablen manuell in `build.gradle.kts` und `app/build.gradle.kts` definiert.
* **Ziel-Zustand:** Vollständige Umstellung auf standardisierte Gradle Version Catalogs (`gradle/libs.versions.toml`).
* **Vorteile:**
  * Zentrales Dependency-Management für das gesamte Projekt.
  * Automatische Versions-Prüfungen in IDEs und CI (z. B. Dependabot / Renovate).
  * Typsichere Accessors (`libs.androidx.compose.bom`, `libs.koin.android` etc.).

#### 1.2 Aktualisierung von Bibliotheken & Plugins
* **Compose BOM:** Aktualisierung der `composeBomVersion` (`2024.12.01` ➔ aktuelle BOM 2025/2026 für Performance-Optimierungen und neue Material3-Komponenten).
* **Coil:** Aktualisierung von `io.coil-kt:coil-compose:2.7.0` auf **Coil 3** (`io.coil-kt.coil3:coil-compose`) für bessere Speicher- und Image-Pipeline-Performance.
* **Coroutines & Lifecycle:** Angleichung auf neueste `kotlinx-coroutines` (1.10+) und `androidx.lifecycle` (2.8.x / 2.9.x).
* **OkHttp:** Prüfung auf OkHttp 5 / Ktor-Client-Evaluierung bei zukünftigen Multiplatform-Bestrebungen.

---

### 2. Architektur, Dependency Injection (Koin) & Concurrency

#### 2.1 Bereinigung der ViewModel-Konstruktoren & Koin-Module
* **Ist-Zustand:** In `HistoryViewModel` und `MainViewModel` werden Hilfsklassen/UseCases wie `FazitManager`, `HistoryDeleteManager`, `HistoryReorderUseCase` und `TriggerAutoFazitUseCase` über Default-Parameter im Konstruktor mit `new/instantiation` erzeugt (`= FazitManager(...)`).
* **Problem:** Erschwert Unit-Testing, bricht strikte Dependency Injection und führt zu redundanten Instanzen (z. B. `FazitManager` existiert sowohl im Koin-Container als auch als ViewModel-Default-Instanz).
* **Ziel-Zustand:**
  1. Alle UseCases und Manager explizit in `AppModule.kt` als `single` oder `factory` deklarieren.
  2. ViewModels erhalten ausschließlich injizierte Schnittstellen/Instanzen ohne Fallback-Instantiierung im Konstruktor.

#### 2.2 Strukturierte Concurrency in Repositories
* **Ist-Zustand:** `DefaultHistoryRepository.kt` startet im `init`-Block ein unstrukturiertes `CoroutineScope(dispatchers.io).launch` für die Migration von Alt-Dateien (`LegacyJsonMigrator.migrate`).
* **Ziel-Zustand:**
  * Asynchrone Initialisierungslogik aus dem Repository-Konstruktor herauslösen.
  * Start als strukturierter Task beim App-Start (z. B. über `AppStateViewModel` oder einen dedizierten App-Startup-Initializer) mit definiertem Lifecycle und Fehler-Handling.

#### 2.3 Refactoring des `OpenAiChatClient`
* **Ist-Zustand:**
  * `OpenAiChatClient` ist ein statisches Kotlin-`object`.
  * Verwendet synchrone, blockierende OkHttp-Aufrufe (`client.newCall(request).execute()`), die erst auf Repository-Ebene in `withContext(dispatchers.io)` gepackt werden.
  * Hält statischen, globalen Zustand (`boostedBudgetModels`).
* **Ziel-Zustand:**
  * Umbau zu einem injizierbaren Interface/Service (`ChatCompletionClient`).
  * `OkHttpClient` wird über Koin injiziert (ermöglicht einfaches Mocking/Interception in Tests und zentrale Konfiguration von Timeouts / Logging).
  * Asynchrone Netzanfragen via Coroutines (z. B. OkHttp-Call Extension oder `suspendCancellableCoroutine`).
  * Zustände wie `boostedBudgetModels` in einen zuständigen DataStore oder In-Memory-Repository kapseln.

---

### 3. UI-Layer & Jetpack Compose Best-Practices

#### 3.1 Konsequente Nutzung von `UiText`
* **Ist-Zustand:** `UiText` existiert bereits (`ui/UiText.kt`), jedoch greifen einige ViewModels und `PromptRepository` weiterhin direkt auf `Context`-basierte String-Generierung zu.
* **Ziel-Zustand:**
  * ViewModels geben für Fehlermeldungen und Hinweistexte ausschließlich `UiText` (`UiText.StringResource` oder `UiText.DynamicString`) zurück.
  * ViewModels bleiben zu 100 % frei von `android.content.Context`-Abhängigkeiten.

#### 3.2 Compose Stability & Recomposition-Optimierung
* **Ist-Zustand:** UI-State-Klassen nutzen Standard-Kotlin-`List<T>`.
* **Ziel-Zustand:**
  * Verwendung von `kotlinx.collections.immutable.ImmutableList` / `ImmutableSet` für Listen in `UiState`-Klassen, um unnötige Recompositions in Compose zu vermeiden.
  * Ergänzung von `@Preview`-Funktionen mit Mock-Daten für modulare UI-Komponenten (`AmpelUi`, `FazitCard`, `DayGroupHeader`, `HistoryItem`), um schnelles UI-Feedback in Android Studio zu ermöglichen.

#### 3.3 Zerlegung großer UI-Dateien
* **Ist-Zustand:** `HistoryScreen.kt`, `CameraScreen.kt` und `MainActivity.kt` enthalten viele Bildschirmzeilen und Inline-Logiken.
* **Ziel-Zustand:**
  * Auslagern von Subkomponenten (z. B. Dialoge, BottomSheets, Menü-Elemente) in separate, modular testbare Dateien unter `ui/components/`.

---

### 4. Data Layer, Persistence & Security

#### 4.1 Room Schema-Export & Migrationstests
* **Ist-Zustand:** `AppDatabase` hat `exportSchema = false`.
* **Ziel-Zustand:**
  * `exportSchema = true` aktivieren und den Schema-Ordner in `app/build.gradle.kts` konfigurieren.
  * Automatisierte Migrationstests mit `androidx.room:room-testing` (`MigrationTestHelper`) einrichten, um sicherzustellen, dass zukünftige Schema-Updates (wie `MIGRATION_1_2`, `MIGRATION_2_3`) fehlerfrei durchlaufen.

#### 4.2 Modularisierung des `HistoryRepository` (Interface Segregation)
* **Ist-Zustand:** `HistoryRepository` vereint Mahlzeiten-CRUD, Foto-Handling, Thumbnails, Tagesfazits, Wochenfazits, Migrationen und Bereinigung (`purgeOlderThanWeeks`).
* **Ziel-Zustand:**
  * Aufteilung in fokussierte Fach-Repositories oder Delegates:
    * `MealHistoryRepository` (Mahlzeiten, Sortierung, Filter)
    * `FazitRepository` (Tages- und Wochenfazits)
    * `PhotoRepository` / `PhotoStorage` (bereits vorhanden, rein auf Dateien fokussiert)

#### 4.3 KeyVault Härtung
* **Ist-Zustand:** `KeyVault.kt` nutzt AES-256-GCM über den Android Keystore.
* **Ziel-Zustand:**
  * Robusteres Handling für seltene Keystore-Invalidierungsfälle (z. B. Änderung der Gerätesperre / Biometrie-Reset) mit klarer Signalisierung an die UI zur Neueingabe des API-Keys.

---

### 5. Testing & Code-Qualität

#### 5.1 Test-Tooling modernisieren
* **Turbine:** Einbinden von `app.cash.turbine:turbine` für sauberes, deterministisches Testen von Coroutine-`StateFlow`s und `SharedFlow`s in den ViewModel- und Repository-Tests.
* **Compose UI-Tests:** Hinzufügen von isolierten Compose-Tests für Kernabläufe (z. B. Foto-Aufnahme-Review, Schnellauswahl von Notizen, Fazit-Interaktionen).
* **MockK / Fake-Standardisierung:** Vereinheitlichung der Test-Fakes und Mocks für `BesseresserApiService` und `NetworkConnectivityChecker`.

---

### 6. SonarQube & Statische Code-Analyse (Clean Code & Komplexität)

Die statische Code-Analyse hat 15 konkrete Issues in 10 Dateien identifiziert. Ihre Behebung reduziert technische Schulden, eliminiert kognitive Überlastung und steigert die Robustheit des Codes:

#### 6.1 Parameter-Überladung & Konstruktoren (kotlin:S107 – *Functions with > 7 parameters*)
* **Betroffene Datei:** `TriggerAutoFazitUseCase.kt`
  * Zeile 28: Funktion/Konstruktor mit 13 Parametern (erlaubt: max. 7).
  * Zeile 90: Funktion mit 10 Parametern.
  * Zeile 135: Funktion mit 10 Parametern.
* **Maßnahme:**
  * Zusammengehörige Parameter in kohärente Request-/Kontext-Objekte kapseln (z. B. `AutoFazitExecutionContext` oder `FazitGenerationParams`).
  * Abhängigkeiten zu Services/Repositories über Koin-Injection bereitstellen statt durch lange Methoden-Signaturen durchzureichen.

#### 6.2 Kognitive Komplexität reduzieren (kotlin:S3776 – *Cognitive Complexity > 15/45*)
* **Betroffene Dateien & Stellen:**
  * `HistoryDetailScreen.kt` (Zeile 205: Komplexität 56 / max. 45)
  * `HistoryItem.kt` (Zeile 67: Komplexität 46 / max. 45)
  * `SetupScreen.kt` (Zeile 534: Komplexität 54 / max. 45)
  * `ImagePreprocessor.kt` (Zeile 93: Komplexität 38 / max. 15)
* **Maßnahme:**
  * UI-Screens: Verschachtelte `when`/`if`-Verzweigungen und inline definierte Komponenten in separate, wiederverwendbare Sub-Composables aufteilen (z. B. Ampel-Status-Views, Thumbnail-Loader, Einstellungs-Karten).
  * `ImagePreprocessor`: Bilddecodierung, EXIF-Auswertung, Skalierungsberechnung und Bitmap-Transformation in lineare, isolierte Einzelfunktionen mit Single-Responsibility aufgliedern.

#### 6.3 Idiomatischer Kotlin-Code & Vorbedingungen (kotlin:S6532, kotlin:S6619)
* **`ApiClient.kt` (Zeile 442, S6532):**
  * `if (!response.isSuccessful) throw ...` ersetzen durch idiomatische Standard-Funktion:
    ```kotlin
    check(response.isSuccessful) { "HTTP $httpCode" }
    ```
* **`ImagePreprocessor.kt` (Zeile 41, S6532):**
  * `if (options.outWidth <= 0 || options.outHeight <= 0) throw ...` ersetzen durch:
    ```kotlin
    check(!(options.outWidth <= 0 || options.outHeight <= 0)) { "Foto konnte nicht decodiert werden..." }
    ```
* **`UserFlowsTest.kt` (Zeilen 598, 736, 791, S6619):**
  * Überflüssige Non-Null Assertions (`!!`) entfernen, da die entsprechenden Ausdrücke vom Compiler bereits als non-null typisiert sind.

#### 6.4 Unbenutzte Parameter & Bereinigung (kotlin:S1172)
* **`HistoryScreen.kt` (Zeile 36):** Unbenutzte Funktionsparameter aus der Methodensignatur entfernen.
* **`ManualEntryDialog.kt` (Zeile 264):** Nicht verwendeten Parameter `settings` entfernen.
* **`HistoryModularUseCasesTest.kt` (Zeile 119):** Nicht referenzierte Lambda-Parameter durch `_` ersetzen, um Intention und Lesbarkeit zu schärfen.

---

### 7. Schritt-für-Schritt Roadmap

```text
[Schritt 1] ──► Gradle Version Catalog (libs.versions.toml) einführen
[Schritt 2] ──► Sonar Clean-Code Quick-Wins (ApiClient check(), unbenutzte Parameter, !! Assertions)
[Schritt 3] ──► Koin DI-Module & TriggerAutoFazitUseCase Parameter-Reduktion (S107)
[Schritt 4] ──► Kognitive Komplexität auflösen (ImagePreprocessor, HistoryDetailScreen, SetupScreen, HistoryItem)
[Schritt 5] ──► Concurrency & Unconfined Scopes im Data Layer ordnen
[Schritt 6] ──► Room Schema-Export & Migrationstests einrichten
[Schritt 7] ──► OpenAiChatClient entkoppeln & asynchronisieren
[Schritt 8] ──► Compose UI-State-Stabilität (Immutable Collections) & Previews
[Schritt 9] ──► Turbine für Flow-Tests & Compose UI-Tests ergänzen
[Schritt 10] ─► Bibliotheks-Updates (Coil 3, Compose BOM 2025+, Lifecycle)
```
