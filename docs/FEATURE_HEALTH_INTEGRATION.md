# Feature-Konzept: Health Connect Integration

> **Status:** Konzept / In Planung  
> **Ziel:** Nahtlose, datenschutzfreundliche Verknüpfung von Ernährung mit Aktivitäts-, Körper- und Vitaldaten zur Maximierung des persönlichen Nutzens und der Treffsicherheit von KI-Analysen.  
> **Technologie:** Android Health Connect API (`androidx.health.connect:connect-client`)  
> **Architektur-Prinzip:** 100 % lokal, opt-in, granulare Rechte, maximale Privatsphäre.

---

## 1. Vision & Mehrwert

Die Qualität von Ernährungsempfehlungen und -bewertungen hängt stark vom Gesamtkontext des Menschen ab. Besseresser erfasst heute die **Input-Seite** (Mahlzeiten, Fotos, Zutaten, Notizen). 

**Health Connect schließt den Regelkreis:**
* **Ganzheitlicher Kontext:** Die KI versteht das Zusammenspiel zwischen dem, was gegessen wird, und wie sich der Körper verhält (Bewegung, Schlaf, Vitalwerte, Gewicht).
* **Fundiertere Tages- & Wochenfazits:** Aus simplen Ernährungsstatistiken werden präzise Ursache-Wirkungs-Zusammenhänge (z. B. *"Dein besserer Schlaf korreliert mit dem früheren Abendessen"* oder *"An Trainingstagen war die Extra-Portion Eiweiß optimal verwertet"*).
* **Zwei-Wege-Nutzen (Geben & Nehmen):**
  * **Nehmen (Lesen):** Besseresser holt Aktivitäts- und Vitaldaten als Kontext für KI-Prompts.
  * **Geben (Schreiben):** Besseresser speichert erkannte Mahlzeiten und Nährwerte zentral auf dem Gerät, damit andere Gesundheits- und Analyse-Apps darauf aufbauen können.

---

## 2. Architektur & Verbindungskonzept

### 2.1 Technische Grundlagen
* **Schnittstelle:** Offizielle Android Health Connect API (`androidx.health.connect:connect-client`).
* **Verfügbarkeit:**
  * **Android 14+:** Nativ im Betriebssystem integriert (`Settings -> Security & Privacy -> Health Connect`).
  * **Android 9 bis 13:** Über das offizielle System-Modul / Health Connect App von Google.
* **Keine Cloud:** Alle Daten verbleiben lokal auf dem Gerät. Health Connect agiert als geschützter lokaler IPC-Datenspeicher (Inter-Process Communication).

### 2.2 Verbindung & Berechtigungs-Workflow
1. **Zentraler Schalter in den Einstellungen:**  
   Unter `Einstellungen -> Integrationen -> Health Connect` befindet sich der Hauptschalter *„Mit Health Connect verbinden“*.
2. **Granulare Systemfreigabe (Android Permission Contract):**  
   Über `PermissionController.createRequestPermissionResultContract()` öffnet die App den nativen Freigabedialog des Systems.
3. **Getrennte Lese- und Schreib-Berechtigungen:**  
   Der Nutzer entscheidet differenziert, welche Datentypen freigegeben werden:
   * **Lesen (Kontext):** Schritte, Aktive Kalorien, Training/Sport, Schlaf, Gewicht, Blutzucker, Blutdruck.
   * **Schreiben (Export):** Ernährung (`NutritionRecord`), optional Flüssigkeit (`HydrationRecord`).
4. **Status- & Fehler-Handling:**
   * Anzeige des aktuellen Verbindungsstatus (`Verbunden`, `Nicht eingerichtet`, `Health Connect nicht verfügbar / Update erforderlich`).
   * Direkter Absprunglink in die Android-Systemverwaltung für Health Connect zur Rechteverwaltung.

---

## 3. Bereitstellen von Daten (Export von Besseresser nach Health Connect)

Wenn eine Mahlzeit bewertet und gespeichert wird, kann Besseresser diese als strukturierte Ernährungsdaten an Health Connect übergeben.

### 3.1 Zu schreibende Datentypen

| Datentyp in Health Connect | Inhalt & Herkunft in Besseresser | Nutzen |
| :--- | :--- | :--- |
| **`NutritionRecord`** | • Name / Beschreibung des Gerichts<br>• Zeitstempel der Mahlzeit<br>• Mahlzeitenkategorie (Frühstück, Mittagessen, Abendessen, Snack)<br>• Geschätzte Makronährstoffe (Kalorien, Protein, Kohlenhydrate, Fett, Ballaststoffe, Zucker, Natrium) | Standardisierter Export aller Mahlzeiten für Auswertungen in externen Dashboards, Arztberichten oder Dritt-Apps. |
| **`HydrationRecord`** *(optional)* | • Getrunkene Wassermenge (falls als Getränk erfasst) | Erfassung des Flüssigkeitshaushalts. |

### 3.2 Ablauf beim Speichern
* Nach Bestätigung/Speichern der Mahlzeit generiert die KI neben der Textbewertung eine optionale strukturierte Nährwertschätzung.
* Besseresser schreibt den `NutritionRecord` asynchron im Hintergrund in Health Connect.
* Wird eine Mahlzeit in Besseresser nachträglich gelöscht oder angepasst, wird auch der verknüpfte Health-Connect-Eintrag aktualisiert oder entfernt (ID-Mapping in lokaler Room-DB).

---

## 4. Einbindung von Daten (Lesen für KI-Kontext)

Welche Daten sollten gelesen werden, und wie bereichern sie die KI-Bewertung?

### 4.1 Relevante Datentypen & ihr Mehrwert

#### A. Aktivität & Bewegung (Täglicher Energieverbrauch)
* **`StepsRecord` (Schrittzahl):** Basisaktivität des Tages (z. B. 3.000 vs. 15.000 Schritte).
* **`ActiveCaloriesBurnedRecord` (Aktiver Kalorienverbrauch):** Durch Bewegung zusätzlich verbrannte Energie.
* **`ExerciseSessionRecord` (Workouts & Sport):** Art des Trainings (z. B. *Krafttraining*, *Joggen*, *Radsport*), Dauer, Uhrzeit und Intensität.
* **Nutzen im KI-Prompt:**
  * Kohlenhydrat- und Portionsbewertung: Größere Portionen nach dem Sport werden positiv eingeordnet (Glykogenspeicher auffüllen).
  * Proteinbedarf: Erhöhter Proteinbedarf an Krafttrainingstagen wird berücksichtigt.

#### B. Schlaf & Erholung
* **`SleepSessionRecord` (Schlafdauer & Schlafphasen):** Dauer und Zeitpunkt des Schlafs der vorangegangenen Nacht.
* **Nutzen im KI-Prompt:**
  * Schlafmangel führt zu Heißhunger und veränderter Insulinsensitivität. Die KI kann Heißhungerattacken verständnisvoll und physiologisch fundiert einordnen.
  * Timing-Feedback: Wie wirkte sich ein spätes, schweres Abendessen auf die Einschlafzeit oder Schlafdauer aus?

#### C. Körpermaße
* **`WeightRecord` (Körpergewicht):** Aktuelle Messwerte von smarten Waagen oder manuellen Einträgen.
* **`BodyFatRecord` (Körperfettanteil) & `LeanBodyMassRecord` (Muskelmasse):**
* **Nutzen im KI-Prompt & Profil:**
  * Automatisches Update des Gewichts im Besseresser-Profil (ohne manuelles Nachtragen).
  * Im Wochenfazit: Korrelation zwischen Ernährungstreue (Ampel-Scores) und realem Gewichtstrend über 7–30 Tage.

#### D. Spezifische Vitalwerte (Zielorientiert)
* **`BloodGlucoseRecord` (Blutzuckerspiegel / CGM):**
  * Hochrelevant für Ziele wie *„Wenig Zucker / Low Carb“* oder Nutzer mit *Diabetes Typ 2*.
  * Erkennen von Blutzuckerspitzen nach bestimmten Mahlzeiten.
* **`BloodPressureRecord` (Blutdruck):**
  * Hochrelevant für das Ziel *„Blutdruck senken“* (z. B. DASH-Diät, Salzkonsum, Kaliumzufuhr).
* **`HeartRateRecord` / `RestingHeartRateRecord` (Ruhepuls):**
  * Indikator für Erholungsstatus und metabolischen Stress.

---

## 5. Konkrete Einbindung in KI-Prompts

Die gelesenen Daten werden zeitraumbezogen aggregiert und in kompakter, token-optimierter Form in die Prompts integriert.

### 5.1 Tagesfazit-Prompt (Daily Summary)

#### Aggregation für den aktuellen Tag:
* Zeitraum: 00:00 Uhr bis 23:59 Uhr des jeweiligen Tages (Schlaf: vorangegangene Nacht).
* Nur vorhandene / freigegebene Werte werden übergeben.

#### Prompt-Baustein:
```text
[Gesundheits- & Aktivitätsdaten des Tages (Health Connect)]
- Bewegung & Sport: 9.420 Schritte, 420 kcal aktiv verbrannt
- Training: 45 Min. Krafttraining (17:30 - 18:15 Uhr)
- Schlaf vorangehende Nacht: 7 Std. 35 Min. (gute Erholung)
- Vitalwerte: Ruhepuls 58 bpm, Gewicht: 78.4 kg
```

#### KI-Verarbeitungsanweisung:
> *„Setze die erfassten Mahlzeiten in Relation zur körperlichen Aktivität und Erholung des Tages. Berücksichtige z. B. erhöhten Energie- oder Proteinbedarf durch Workouts oder den Einfluss von Mahlzeiten auf das Wohlbefinden.“*

---

### 5.2 Wochenfazit-Prompt (Weekly Summary)

#### Aggregation für die 7-Tage-Periode:
* Wöchentliche Durchschnitte und Summen.
* Gewichtstrend (Differenz Beginn vs. Ende der Woche).

#### Prompt-Baustein:
```text
[Wochenüberblick Aktivität & Körper (Health Connect)]
- Durchschnittliche Schritte: 8.650 Schritte/Tag
- Sporteinheiten: 3x (insgesamt 2 Std. 40 Min., vorwiegend Laufen & Kraftsport)
- Schlafdurchschnitt: 6 Std. 50 Min./Nacht
- Gewichtstrend: 78.8 kg -> 78.3 kg (-0.5 kg im 7-Tage-Verlauf)
```

#### KI-Verarbeitungsanweisung:
> *„Analysiere Muster und Trends über die gesamte Woche. Zeige auf, wie die Ernährung mit der Trainingsintensität, dem Schlafpensum und der Gewichtsentwicklung harmoniert hat, und gib 1–2 gezielte Impulse für die kommende Woche.“*

---

### 5.3 Einzelmahlzeiten-Bewertung (Optional / Punktuell)

* **Prinzip der Datensparsamkeit:** Standardmäßig benötigt eine schnelle Foto-Bewertung keine Vitaldaten (spart Latenz und Tokens).
* **Ausnahme (Pre-/Post-Workout Kontext):** Liegt ein Training unmittelbar vor (< 2 Stunden) oder nach der Mahlzeit, kann ein kurzer Hinweis injiziert werden:  
  `[Kontext: Training vor 45 Minuten beendet (Kraftsport, 50 Min.)]`  
  Dadurch bewertet die KI eine größere Portion Kohlenhydrate oder Proteine direkt situativ richtig.

---

## 6. UI / UX & Interaktion in der App

### 6.1 Einstellungsansicht (`SettingsScreen`)
* **Neuer Bereich „Integrationen“:**
  * Health Connect Kachel mit Status-Badge (`Verbunden 🟢` / `Getrennt ⚪`).
  * Schalter für *„Gesundheitsdaten in Fazits einbeziehen“* (Lesen).
  * Schalter für *„Mahlzeiten an Health Connect senden“* (Schreiben).
  * Button *„Berechtigungen im System verwalten“*.

### 6.2 Verlaufsansicht (`HistoryScreen` & `DayHeader`)
* **Kompakte Aktivitätsleiste im Tagesheader:**
  * Kleine Infobadges direkt unter dem Datum:  
    `👟 9.420 Schritte` · `🏃 45m Sport` · `💤 7h 35m` · `⚖️ 78.4 kg`
  * Tippen auf die Leiste zeigt ein übersichtliches Bottom-Sheet mit Details zu den Tagesaktivitäten.

### 6.3 Profilansicht (`ProfileScreen`)
* **Gewichts-Synchronisation:**
  * Neben dem Gewichts-Eingabefeld: Ein kleiner Indikator *„Automatisch synchronisiert mit Health Connect (Stand: Heute, 07:15)“*.

---

## 7. Datenschutz, Caching & Ausfallsicherheit

1. **Vollständige Datenhoheit:**
   * Es werden keine Health-Connect-Daten an Server von Besseresser übertragen (da Besseresser keinen eigenen Server besitzt).
   * Nur die für den generierten Prompt relevanten aggregierten Eckdaten (z. B. Schrittsumme, Schlafdauer) werden bei der Fazit-Erstellung an das vom Nutzer gewählte KI-Modell übermittelt.
2. **Lokaler Cache (`HealthDataCache`):**
   * Zur Vermeidung von UI-Rucklern und API-Latenzen werden Tageswerte lokal in Room gecacht und bei Bedarf asynchron aktualisiert.
3. **Graceful Degradation (Robustheit):**
   * Ist Health Connect nicht installiert, nicht autorisiert oder liefert keine Daten (z. B. weil der Nutzer keine Tracker trägt), funktionieren Tages- und Wochenfazits exakt wie bisher rein auf Basis der erfassten Mahlzeiten.

---

## 8. Stufenweiser Implementierungs-Fahrplan (Roadmap)

### Phase 1: Fundament & Verbindung
* Gradle-Dependency `androidx.health.connect:connect-client` einbinden.
* `HealthConnectManager` als Koin-Singleton implementieren (Verfügbarkeitsprüfung, Permission-Contract, Verbindungsstatus).
* UI-Integration in `SettingsScreen` (Verbinden-Schalter, Permission-Handling).

### Phase 2: Kontext-Lesen für Tages- & Wochenfazit
* Auslesen von Schritten (`StepsRecord`), Workouts (`ExerciseSessionRecord`), Schlaf (`SleepSessionRecord`) und Gewicht (`WeightRecord`).
* Formatierungs-Helfer für Prompt-Kontexte (`toDailyPromptContext()`, `toWeeklyPromptContext()`).
* Injektion in `PromptBuilder.kt` für Tages- und Wochenzusammenfassungen.
* Anzeige von Aktivitäts-Badges im Tagesheader des `HistoryScreen`.

### Phase 3: Export von Mahlzeiten (Schreiben)
* Erzeugen von `NutritionRecord`-Objekten beim Speichern von Mahlzeiten in `HistoryRepository`.
* Asynchrones Schreiben in Health Connect mit Fehlerbehandlung.
* Synchronisation bei Bearbeitung/Löschung von Mahlzeiten.

### Phase 4: Spezial-Vitalwerte & Tiefenanalyse (Optional)
* Unterstützung von Blutzucker (`BloodGlucoseRecord`) und Blutdruck (`BloodPressureRecord`) für Nutzer mit spezifischen chronischen Zielen.
* Vertiefte KI-Korrelationsanalyse im Wochenbericht (z. B. Erkennung von Schlaf-Ernährungs-Zusammenhängen).
