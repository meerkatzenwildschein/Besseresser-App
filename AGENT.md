# AGENT.md – Besseresser (App)

## Zweck

Android-App „**Besseresser**" (`de.gurum.besseresser`): Nach einmaliger Einrichtung Essen fotografieren und pro persönlichem Ziel eine Ampel-Bewertung (🟢🟡🔴) von einer KI erhalten.
Der Name ist sprachneutral (Markenwort), `app_name` in beiden Locale-Dateien ist „Besseresser".

---

## Ablauf & Screen-Architektur

### 1. Navigation & System-Zurück (`BackHandler`)
- **System-Zurück navigiert konsistent zu On-Screen-Pfeilen:**
  - Detail → Verlauf (`historyEntry = null`)
  - Verlauf → Main (`showHistory = false`)
  - Profil → Main (`showProfile = false`)
  - Einstellungs-Overlay über abgeschlossenem Setup → Main (`showSetup = false`)
  - Kamera-Overlay → schließen
  - Foto-Review → verwerfen (Foto/Note/Fehler zurücksetzen)
- **Root-Screen:** Main (ohne Overlays) und Erst-Setup beenden die App bei System-Zurück (Android-Standard).
- **Animierte Übergänge:** Main/Historie/Detail laufen über `AnimatedContent` (`MainActivity.kt`, `AppScreen.order`-Enum steuert Slide-/Fade-Richtung).

### 2. Profil (`ProfileScreen.kt`, `ProfileStore` in `Store.kt`)
- **Optionale Angaben:**
  - **Geburtsdatum:** Material-DatePickerDialog (ISO `yyyy-MM-dd`, Anzeige `dd.MM.yyyy`; Alter wird dynamisch lokal berechnet via `Profile.ageYears`).
  - **Gewicht & Größe:** Gewicht in kg (Komma erlaubt, 1–700 kg), Größe in cm (30–280 cm).
  - **Geschlecht:** Typsicheres Enum `Gender` (`MALE`, `FEMALE`, `DIVERSE`, `NONE`), Chip-Reihe im UI.
  - **Freitext:** Anmerkungen / Vorerkrankungen / Diäten.
- **Speichern:** Auch komplett leer möglich („nicht gesetzt"). Persistiert in `besseresser_prefs`.
- **Automatischer Erststart:** Öffnet sich nach dem ersten erfolgreichen Setup automatisch (`firstRun`-Flag).
- **Verwendung in KI-Prompts:** Fließt als Kontextzeile in das Tagesfazit ein (`profileContextLine`), nicht jedoch in die Einzelmahlzeiten-Auswertung.

### 3. Setup & Einstellungen (`SetupScreen.kt`, `Store.kt`, `KeyVault.kt`)
- **Konfiguration:**
  - **Ziele:** Freitext, ein Ziel pro Zeile (Default: `DEFAULT_GOALS` aus `SetupScreen.kt`).
  - **API-URL:** Basis-URL einer OpenAI-kompatiblen Schnittstelle (z. B. `https://api.openai.com/v1`).
  - **API-Key:** AES-256-GCM-verschlüsselt via Android-Keystore (`KeyVault.kt`), im SharedPreference-Storage liegt nur das Chiffrat.
  - **Modell:** Name des Vision-fähigen Modells (z. B. `gpt-4o-mini`).
- **Anbieter-Auswahl & Modell-Liste:**
  - Dialog zur schnellen Auswahl bekannter Provider (`PROVIDERS`).
  - `ModelListScreen`: Ruft `{baseUrl}/models` ab und filtert primär nach Vision-fähigen Modellen (inkl. Debug-Report bei Fehlern).
- **Intelligente Zielaufteilung:**
  - Freitext-Ziele können beim Speichern per KI (`ApiClient.splitGoals`) in prägnante Einzelziele zerlegt werden.
  - `Settings.goalsWereSplit`-Flag verhindert redundante KI-Calls bei unveränderten Zielen.

### 4. Hauptbildschirm & Kamera (`MainScreen.kt`, `CameraScreen.kt`)
- **Hauptansicht:**
  - Burger-Menü oben rechts (Profil, Verlauf, Einstellungen).
  - Horizontale Wischgeste nach links öffnet den Verlauf (gesperrt bei laufender Auswertung oder ungespeichertem Zustand).
  - Fixierter Foto-CTA am unteren Bildschirmrand („Essen fotografieren" / „Essen bewerten" / Lade-Spinner).
  - Zentrierte „KI denkt"-Karte mit Spinner während der Analyse.
- **Eigene Kamera (CameraX, `CameraScreen.kt`):**
  - Live-Preview, Auslöser, direkte Foto-Prüfung und Anmerkungsfeld in einem Schritt.
  - EXIF-Orientierungskorrektur (`correctOrientation`).
  - Fallback auf Systemkamera (`TakePicture`), falls Kamera-Berechtigung fehlt oder Fehler auftreten.

### 5. Ergebnis & Auswertung (`MainScreen.kt`, `CommonUi.kt`)
- Pro Ziel eine Ampel-Zeile (🟢 Grün / 🟡 Gelb / 🔴 Rot / ⚪ Unbekannt) mit kurzer Begründung.
- `CollapsibleSummaryBlock`: Gesamteindruck-Satz, erkannte Mahlzeit mit Portionsschätzung (🍽️) und Nutzer-Notiz (📝).
- `ErkanntEditDialog`: Nachträgliche Korrektur des erkannten Essens per Stift-Icon.

### 6. Verlauf & Historie (`HistoryScreen.kt`, `HistoryStore.kt`)
- **Tages-Gruppierung:** Mahlzeiten nach Kalendertag gruppiert (`DayGroupHeader`).
- **KI-Tagesfazit:**
  - Tagesfazit-Chip mit Zustandsmaschine (Neu ermitteln, Lädt, Bereit, Veraltet).
  - Veraltet automatisch bei Änderungen an Einträgen oder Fingerprint-Mismatch (`dayFingerprint`).
  - Automatische Generierung für die beiden neuesten Tage ab 18:00 Uhr (`FAZIT_AUTO_HOUR`).
- **KI-Wochenfazit:**
  - Sonntags ab 18:00 Uhr am Ende der Liste sichtbar; fasst die gesamte Woche zusammen.
- **Nacherfassung:**
  - Mahlzeiten können nachträglich mit oder ohne Foto hinzugefügt werden (`AddMealNoPhotoScreen` / `ApiClient.analyzeText`).
  - Werden als `backdated = true` markiert (keine fingierte Uhrzeit, Kennzeichnung „nachträglich erfasst").
- **Notiz-Schnellauswahl (`RecentNotesStore`):**
  - MRU-Speicher (max. 5 unmarkierte Einträge) plus dauerhafte Favoriten mit Sternchen (`RecentNote`).

---

## KI-Schnittstelle & Prompt-Kontrakt

### JSON-Rückgabeformat (`ApiClient.kt`)

```json
{
  "bewertung": [
    { "ziel": "<Zieltext>", "ampel": "gruen|gelb|rot", "begruendung": "..." }
  ],
  "zusatz": "<Gesamteindruck-Satz>",
  "erkannt": "<Was die KI erkannt hat mit Mengenschätzung>"
}
```

- **Tolerantes Parsing:**
  - Unterstützt Alias-Keys (`results`, `summary`, `reason`, `detected`, `food`, `meal`).
  - Bereinigt Markdown-Codeblöcke (`stripCodeFences`), repariert unvollständiges JSON heuristisch (`closeJson`).
  - Normalisiert Ampelfarben tolerant (`gruen`, `grün`, `green`, `gelb`, `yellow`, `rot`, `red`, Emojis).
- **Lokalisierung:**
  - Prompts werden aus `strings.xml` (Deutsch/Englisch) dynamisch übergeben (`PromptBundle`).

---

## Datenmodelle & Serialisierung (`Data.kt`)

Das Projekt nutzt `kotlinx.serialization` mit einer zentralen `AppJson`-Instanz (`ignoreUnknownKeys = true`, `isLenient = true`, `coerceInputValues = true`).

| Klasse / Enum | Beschreibung | Besonderheit |
|---|---|---|
| `TrafficLight` | Enum (`GREEN`, `YELLOW`, `RED`, `UNKNOWN`) | Eigener `TrafficLightSerializer`, Helfer für UI-Farben (`color()`) und Labels (`label()`) |
| `Gender` | Enum (`MALE`, `FEMALE`, `DIVERSE`, `NONE`) | `GenderSerializer` mit String-Fallback |
| `GoalResult` | Ziel-Bewertung | Serialisiert mit `@SerialName("ziel")`, `@SerialName("ampel")`, `@SerialName("begruendung")` |
| `Evaluation` | Gesamtbewertung einer Mahlzeit | Liste von `GoalResult`, `summary`, `erkannt` |
| `Settings` | Setup-Einstellungen | URL, verschlüsselter Key, Modell, Ziele-Liste, `goalsWereSplit` |
| `Profile` | Nutzerdaten | `birthDate` (ISO), `weightKg`, `heightCm`, `notes`, `gender`, dynamisches `ageYears` |
| `HistoryEntry` | Gespeicherte Mahlzeit | ID, Datum, Foto-Pfad, Zielergebnisse, Notiz, Score-Berechnung (0..100) |
| `DayFazit` | Tagesbewertung | Ampel, Fazit-Text, Entry-IDs und `fingerprint` |
| `DayFazitResult` | KI-Ergebnis Tages-/Wochenfazit | Typsicheres Paar aus `TrafficLight` und Fazit-Text (`text`) |
| `ApiException` | API-Fehlermodell | Fehlertext, optionaler Monospace-`debugReport` und Root-Cause |
| `RecentNote` | Notiz-Schnellauswahl | Text (`t`) und Sternchen-Favoriten-Status (`s`) |

---

## Technik-Stack & Bibliotheken

| Komponente | Version / Detail |
|---|---|
| Sprache & Compiler | Kotlin 2.0.20 |
| UI-Framework | Jetpack Compose (BOM 2024.09.02), Material 3 |
| Android Gradle Plugin | 8.5.2 (Gradle 8.9) |
| SDK-Versionen | `compileSdk` / `targetSdk` = 34, `minSdk` = 26 |
| Serialisierung | `kotlinx-serialization-json:1.7.3` |
| Kamera | CameraX 1.3.4 (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`) |
| Sicherheit | Android Keystore / AES-256-GCM (`KeyVault.kt`) |
| Tests | JUnit 4 (`DataSerializationTest.kt`, `ApiClientTest.kt`) |

---

## Projektstruktur

```
Besseresser-App/
├── AGENT.md                      ← Diese Architektur- & Kontextdatei
├── README.md                     ← Allgemeine Projektübersicht
├── REFACTORING.md                ← Refactoring-Roadmap & Fortschrittstracking
├── build.gradle.kts              ← Root-Build-Skript mit Plugin-Definitionen
├── settings.gradle.kts           ← Modul- & Repository-Konfiguration
├── gradle.properties
├── app/
│   ├── build.gradle.kts          ← App-Dependencies (Compose, CameraX, Serialization, Tests)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/de/gurum/besseresser/
│       │   │   ├── ApiClient.kt            ← OpenAI-Netzwerk-Koordinator, BesseresserApiService
│       │   │   ├── CameraScreen.kt         ← CameraX-Live-Preview & Foto-Review
│       │   │   ├── CommonUi.kt             ← Wiederverwendbare UI-Elemente (TopBars, Dialoge, Badges)
│       │   │   ├── Data.kt                 ← Domänenmodelle, Enums (TrafficLight, Gender), AppJson
│       │   │   ├── DayFazitCard.kt         ← Tages- und Wochenfazit-Karten, Auto-Fazit-Trigger
│       │   │   ├── DayGroupHeader.kt       ← Tagesgruppen-Header, Score-Badges, Ziele-Preview
│       │   │   ├── HistoryDetailScreen.kt  ← Detailansicht einzelner Mahlzeiten, Re-Evaluation
│       │   │   ├── HistoryListScreen.kt    ← Verlaufslisten-UI, Swipe-To-Dismiss, HistoryItem
│       │   │   ├── HistoryScreen.kt        ← Haupt-Verlaufsansicht & Navigation
│       │   │   ├── HistoryStore.kt         ← Persistenz für Mahlzeiten, Tages- und Wochenfazits
│       │   │   ├── ImagePreprocessor.kt    ← Bildskalierung & Base64-Data-URI-Aufbereitung
│       │   │   ├── KeyVault.kt             ← Keystore-Verschlüsselung für API-Keys
│       │   │   ├── MainActivity.kt         ← App-Einstiegspunkt, Theme, Root-Navigation
│       │   │   ├── MainScreen.kt           ← Hauptbildschirm, CTA-Handling, Auswertungsansicht
│       │   │   ├── ManualEntryDialog.kt    ← Dialoge & Overlays zur Nacherfassung (mit/ohne Foto)
│       │   │   ├── OpenAiChatClient.kt     ← Reiner HTTP-Netzwerktransport & tolerantes JSON-Parsing
│       │   │   ├── ProfileScreen.kt        ← Nutzerprofil-Verwaltung
│       │   │   ├── PromptBuilder.kt        ← Dynamische Prompt-Generierung (Analyse, Fazit, Ziele)
│       │   │   ├── SetupScreen.kt          ← Ersteinrichtung, Provider- & Modell-Auswahl
│       │   │   └── Store.kt                ← SharedPreferences-Speicher (Settings, Profile, RecentNotes)
│       │   └── res/
│       │       ├── values/             ← strings.xml (EN Default), themes.xml, colors.xml
│       │       ├── values-de/          ← strings.xml (DE Übersetzung)
│       │       ├── values-night/       ← themes.xml (Dark-Mode)
│       │       └── xml/                ← file_paths.xml, locales_config.xml
│       └── test/
│           └── java/de/gurum/besseresser/
│               ├── ApiClientTest.kt         ← Unit-Tests für ApiClient-Hilfsmethoden & Fehlermodelle
│               └── DataSerializationTest.kt ← Unit-Tests für Serialisierung, Enums und Scores
```

---

## Build & Testen

```powershell
# Unit-Tests ausführen
.\gradlew.bat testDebugUnitTest

# Debug-APK kompilieren
.\gradlew.bat assembleDebug
```

---

## Besonderheiten & Guidelines für Agenten

1. **Kein `org.json` mehr verwenden:** Alle neuen Datenstrukturen und Konvertierungen müssen über `@Serializable` und `AppJson` abgewickelt werden.
2. **Typsichere Enums bevorzugen:** Ampel-Werte (`TrafficLight`) und Geschlechter (`Gender`) immer als Enum übergeben; UI-Farben über `TrafficLight.color()` bzw. `ampelColor()` beziehen.
3. **Strings zweisprachig pflegen:** Neue UI-Texte, Fehlermeldungen oder Prompt-Bestandteile immer in beiden Ressourcen-Dateien anlegen: `res/values/strings.xml` und `res/values-de/strings.xml`.
4. **Refactoring-Plan beachten:** Geplante Modernisierungen (Coroutines, Room, ViewModels) sind in `REFACTORING.md` priorisiert dokumentiert.
