# Refactoring-Fahrplan: Besseresser-App

Dieses Dokument bündelt die identifizierten Refactoring-Maßnahmen zur Steigerung der Wartbarkeit, Testbarkeit und Robustheit der Besseresser-App. Die Punkte können schrittweise und nacheinander abgearbeitet werden.

---

## Übersicht & ROI

| Refactoring-Bereich | Erwarteter Nutzen | Umsetzungsaufwand | Status |
| :--- | :--- | :--- | :--- |
| **1. Enums & `kotlinx.serialization`** | Beseitigt Parsing-Bugs und stille Laufzeitfehler | Gering (1–2 Tage) | [x] Erledigt |
| **2. Coroutines in `ApiClient`** | Keine blockierenden Requests mehr, sauberes Lifecycle-Handling | Gering–Mittel (2 Tage) | [x] Erledigt |
| **3. Monolithen aufteilen** | Schnelleres Zurechtfinden im Code, weniger Merge-Konflikte | Mittel (2–3 Tage) | [x] Erledigt |
| **4. ViewModel & Room DB** | Keine Datenverluste bei UI-Events, flüssige Historie auch bei vielen Einträgen | Mittel–Hoch (3–5 Tage) | [x] Erledigt |
| **5. Unit-Tests für Domänenlogik** | Hohe Sicherheit bei Änderungen an Prompts und Auswertungen | Kontinuierlich | [ ] Offen |

---

## Detaillierte Maßnahmen & Arbeitspakete

### 1. Typensicherheit & Serialisierung (Quick Wins)
- [x] **1.1 Ampel & Domänen-Enums einführen**
  - Ersetzen der losen String-Werte (`"gruen"`, `"gelb"`, `"rot"`, `"unbekannt"`) durch ein typsicheres `enum class TrafficLight` (bzw. `Ampel`).
  - Enums für Geschlecht (`Gender`), Meal-Typen etc. definieren.
  - Hilfsmethoden für UI-Farben, Icons und Lokalisierung anbinden.
- [x] **1.2 Umstellung auf `kotlinx.serialization`**
  - Ersetzen der manuellen `org.json.JSONObject` / `JSONArray` Konstruktionen und Parser in `Data.kt`, `Store.kt` und `ApiClient.kt`.
  - Robuste Deserialisierung für API-Responses, Einstellungen und Verlaufsdaten definieren.

---

### 2. Netzwerk- & Concurrency-Modernisierung (`ApiClient`)
- [x] **2.1 Umstellung auf Kotlin Coroutines & `suspend fun`**
  - Ablösung von `Executors.newSingleThreadExecutor()`, `Handler(Looper.getMainLooper())` und Callback-Lambdas (`onResult`, `onError`).
  - Definition eines sauberen API-Service-Interfaces (`BesseresserApiService` mit `suspend fun analyze(...)`, `suspend fun analyzeText(...)`, `suspend fun dayFazit(...)`, `suspend fun splitGoals(...)`, `suspend fun fetchModels(...)`).
  - Rückgabe über `Result<T>` und typsichere Domain-Modelle (`DayFazitResult`, `ApiException`).
- [x] **2.2 Lifecycle & Cancellation**
  - Saubere Unterstützung von Request-Abbrüchen beim Verlassen des Screens oder bei neuen Fotoaufnahmen (Job Cancellation).
  - Entkopplung paralleler Anfragen via `withContext(Dispatchers.IO)` (z. B. Modellabruf blockiert nicht mehr die Analyse).

---

### 3. Aufteilung von Monolithen & Belangsentflechtung (Clean Code)
- [x] **3.1 Zerlegung von `HistoryScreen.kt` (>1.200 Zeilen)**
  - `HistoryListScreen.kt`: Reines Listen-UI mit Scroll-Handling.
  - `HistoryDetailScreen.kt`: Detailansicht einer Mahlzeit / Auswertung.
  - `DayGroupHeader.kt` & `DayFazitCard.kt`: Wiederverwendbare Karten- und Header-Komponenten.
  - `ManualEntryDialog.kt`: Dialoge für manuelle Nacherfassung.
- [x] **3.2 Modularisierung von `ApiClient.kt` (~700 Zeilen)**
  - `OpenAiChatClient.kt`: Reiner HTTP-Netzwerktransport.
  - `PromptBuilder.kt`: Generierung und Zusammenstellung lokalisierter Prompt-Templates.
  - `ImagePreprocessor.kt`: Bildskalierung, Orientierungs-Korrektur und Komprimierung.

---

### 4. Architektur: MVVM & Persistenz
- [x] **4.1 Jetpack ViewModel & UI-State etablieren**
  - Zustand (`StateFlow<UiState>`) und Logik aus den Composables (`MainScreen`, `HistoryScreen`, `ProfileScreen`, `SetupScreen`) in ViewModels (`MainViewModel`, `HistoryViewModel`, `ProfileViewModel`, `SetupViewModel`) ausgelagert.
  - Überstehen von Konfigurationsänderungen (z. B. Bildschirmdrehung) ohne Datenverlust via `collectAsStateWithLifecycle`.
- [x] **4.2 Migration auf Room Database**
  - Ablösung der dateibasierten JSON-Speicherung (`filesDir/history/*.json`) durch SQLite/Room (`AppDatabase`, `HistoryDao`, `DayFazitDao`, `WeekFazitDao`).
  - Tabellen/Entities für Mahlzeiten (`HistoryEntryEntity`), `DayFazitEntity` und `WeekFazitEntity`.
  - Reaktive Datenströme via `Flow<List<HistoryEntryEntity>>` und automatische, transparente Migration bestehender JSON-Dateien.

---

### 5. Absicherung & Testbarkeit
- [ ] **5.1 Repository-Pattern & Abstraktion**
  - Kapselung von Datenspeicher und Netzwerk hinter Interfaces (`HistoryRepository`, `SettingsRepository`, `ProfileRepository`).
  - Ermöglicht isolierte Fakes und Mocks für Tests.
- [ ] **5.2 Unit-Test-Suite aufbauen**
  - Unit-Tests für Score-Berechnung und Aggregationen.
  - Tests für Prompt-Erstellung und robuste JSON-Reparatur (`closeJson` / Fallbacks).
  - Tests für Datums- und Fingerprint-Berechnungen (Tages-/Wochenfazit).
