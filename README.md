# 🍽️ Besseresser

**Besseresser** ist eine Android-App, die Essen fotografiert und es per KI gegen deine persönlichen Ziele bewertet – als einfache Ampel: 🟢🟡🔴.

Ob „Gesünder essen", „Weniger Zucker" oder „Blutdruck senken": Du definierst deine Ziele, fotografierst dein Essen, und die KI sagt dir für jedes Ziel individuell, wie gut die Mahlzeit passt – mit kurzer Begründung, statt komplizierter Nährwerttabellen.

## ✨ Features

- **Eigene Ziele als Freitext** – die KI zerlegt deine Wünsche beim Setup automatisch in einzelne, prägnante Ziele (die du vor dem Speichern noch bearbeiten kannst)
- **Foto direkt in der App** – eingebaute Kamera (CameraX) mit Anmerkungsfeld; Fallback auf die Systemkamera
- **Ampel-Bewertung pro Ziel** – grün/gelb/rot plus Begründung und ein Gesamteindruck-Satz
- **Historie** – gespeicherte Bewertungen mit Thumbnails, Details pro Ziel, wischbar zum Löschen
- **Beliebige OpenAI-kompatible APIs** – OpenAI, OpenRouter, Gemini, Mistral, Groq, xAI, Anthropic u. v. m.; Modellliste wird direkt aus der API geladen und auf Bildverstehen gefiltert
- **Datenschutz zuerst** – alle Einstellungen (inkl. API-Key, verschlüsselt via Android-Keystore) bleiben **lokal** auf dem Gerät; die Fotos gehen nur an den API-Anbieter deiner Wahl
- **Hell & dunkel**, Deutsch & Englisch (auf Android 13+ auch als App-Sprache wählbar)

## 🚀 Schnellstart

1. App auf ein Android-Gerät mit **Android 8.0 (API 26)+** installieren (siehe [Build](#🔨-bauen))
2. Beim ersten Start einmal einrichten:
   - Ziele als Freitext eintragen (eine Zeile = ein Ziel, oder die Vorschläge nutzen)
   - API-URL und API-Key einer OpenAI-kompatiblen API angeben (z. B. `https://api.openai.com/v1` – Anbieter-Vorschläge sind per Link im Setup verlinkt)
   - Modell mit Bildverstehen wählen (z. B. `gpt-4o-mini`) – direkt aus der Modellliste der API
3. Essen fotografieren → Ampel-Bewertung erhalten 🟢

## 🔄 So funktioniert es

1. **Foto** wird in der App aufgenommen (max. 1280 px, JPEG) und Base64-kodiert an die konfigurierte API geschickt
2. Der **Prompt** enthält deine Ziele und verlangt strikt ein JSON-Ergebnis:

   ```json
   {
     "bewertung": [
       { "ziel": "Weniger Zucker", "ampel": "gelb", "begruendung": "..." }
     ],
     "zusatz": "<Gesamteindruck-Satz>"
   }
   ```

3. Das **Parsing ist tolerant** (Alias-Schlüssel, Code-Fences, Groß-/Kleinschreibung) – fehlende Ziele erscheinen als graue Ampel

Die Bewertung wird **nicht** automatisch gespeichert: nur was du mit „In Historie speichern" bestätigst, landet in der Historie (lokal als JSON + verkleinertes Foto).

## 🔨 Bauen

```bash
./gradlew installDebug
```

Oder das Projekt in Android Studio öffnen und auf das Gerät deployen. Benötigt wird ein JDK 17 und Android SDK 34.

## 🛠️ Tech-Stack

| | |
|---|---|
| Sprache / UI | Kotlin 2.0, Jetpack Compose (Material 3) |
| Android | minSdk 26, targetSdk 34 (Gradle 8.9, AGP 8.5.2) |
| Kamera | CameraX |
| Netz | `HttpURLConnection` (keine Netz-Bibliothek nötig) |
| Persistenz | SharedPreferences + eigene Dateien (nur lokal) |
| Verschlüsselung | AES-256-GCM, Schlüssel im Android-Keystore |

## 📁 Struktur

```
app/src/main/java/de/gurum/besseresser/
├── MainActivity.kt     Screens, Navigation, Animationen
├── SetupScreen.kt      Setup, Anbieter & Modell-Auswahl
├── CameraScreen.kt     Eigene Kamera + Foto-Review
├── HistoryScreen.kt    Historie & Detail-Ansicht
├── ApiClient.kt        OpenAI-kompatible API (KI-Calls, Parsing)
├── Data.kt             Datenmodelle (Ziele, Bewertung, Ampel)
├── Store.kt            Lokale Einstellungen
├── KeyVault.kt         Verschlüsselung des API-Keys
└── HistoryStore.kt     Lokale Historie (JSON + Fotos)
```

Hinweise für Entwicklung und Werkzeuge (KI-Assistenten) liegen in [`AGENT.md`](AGENT.md).

## 📄 Lizenz

[GPL-3.0](LICENSE)
