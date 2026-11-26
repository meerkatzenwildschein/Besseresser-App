# Feature-Dokumentation: Priorisierung & Gewichtung persönlicher Ernährungsziele

## 1. Übersicht & Zielsetzung
In den Einstellungen der Besseresser-App können persönliche Ernährungsziele (Ampel-Stichpunkte) erfasst und verwaltet werden. Um Nutzern eine individuelle Fokussierung auf die für sie wichtigsten Ernährungsregeln zu ermöglichen, können alle Ziel-Chips auf einer 5-stufigen Skala (1 bis 5) priorisiert werden.

Die Gewichtung wird in allen KI-Prompts (Einzelmahlzeiten-Bewertung, Text-Nacherfassung sowie Tages- und Wochenfazit) berücksichtigt, sodass übergeordnete Hauptziele die Gesamtfazite maßgeblich prägen und Verstöße gegen Kernziele strenger beurteilt werden.

---

## 2. Architekturentscheidungen & Datenmodell

### 2.1 Modellklasse `WeightedGoal`
Ziele werden durch die unveränderliche, serialisierbare Datenklasse `WeightedGoal` in `de.gurum.besseresser.model.Data.kt` repräsentiert:

```kotlin
@Immutable
@Serializable(with = WeightedGoalSerializer::class)
data class WeightedGoal(
    val text: String,
    val weight: Int = DEFAULT_WEIGHT
) {
    init {
        require(weight in MIN_WEIGHT..MAX_WEIGHT) {
            "Weight must be between $MIN_WEIGHT and $MAX_WEIGHT, but was $weight"
        }
    }

    companion object {
        const val MIN_WEIGHT = 1
        const val MAX_WEIGHT = 5
        const val DEFAULT_WEIGHT = 3
    }
}
```

### 2.2 Gewichtungsstufen
* **Stufe 1:** Sehr niedrig (untergeordnete Empfehlungen / Nebenziele)
* **Stufe 2:** Niedrig
* **Stufe 3 (Standard):** Mittel / Ausgewogen (Default für alle neuen und Standard-Ziele)
* **Stufe 4:** Hoch (wichtige persönliche Regeln)
* **Stufe 5:** Sehr hoch (absolute Priorität / Kernziele)

### 2.3 Persistenz & Abwärtskompatibilität
1. **DataStore (`SettingsRepository.kt`):**
   * **Neues Format:** Verlustfreies JSON-Array von `WeightedGoal`-Objekten (`[{"text":"...","weight":3}, ...]`). Dadurch bleiben Zeilenumbrüche, Sonderzeichen und Doppelpunkte im Zieltext vollständig erhalten.
   * **Legacy-Fallback:** Wurden Daten zuvor im reinen Textformat (zeilenweise getrennt) gespeichert, erkennt der Parser dies und weist allen Zeilen automatisch das Standard-Gewicht `3` zu.
2. **Datensicherung & Import (`BackupStore.kt`):**
   * Alte Backups mit reinen String-Listen (`goals: List<String>`) werden über den toleranten `WeightedGoalSerializer` nahtlos eingelesen und mit Gewicht 3 initialisiert.
   * Neue Backups speichern und laden die individuellen Wichtungen vollständig.
   * Beim Merge importierter Einstellungen werden leere/ungültige Ziele gefiltert; ein Backup mit rein leeren Zielen überschreibt vorhandene Ziele nicht.

---

## 3. UI-Konzept & Interaktion im Einstellungsbildschirm

### 3.1 Segment-Schaltfläche im `GoalChipItem` (`SetupScreen.kt`)
* Jeder Ziel-Chip in der Liste verfügt über eine integrierte 5-stufige Segment-Schaltfläche (`1 | 2 | 3 | 4 | 5`).
* **1-Klick-Bedienung:** Ein einzelner Tipp auf eine Zahl ändert die Priorität unmittelbar ohne Untermenüs oder Dialoge.
* **Visuelles Feedback:** Die aktive Wichtungsstufe wird mit der Akzentfarbe (`primary`) und kontrastierendem Text hervorgehoben.
* **Barrierefreiheit:** Jedes Segment und jede Statusanzeige verfügt über lokalisierte Barrierefreiheits-Labels (z. B. „Priorität auf 4 von 5 setzen“).

### 3.2 Sortierung, Löschen & inkrementelles Hinzufügen
* **Sortierung & Löschen:** Das Verschieben von Zielen per Pfeil-Buttons (Hoch/Runter) sowie das Löschen einzelner Chips erhält die Wichtungen aller übrigen Ziele unverändert bei.
* **Inkrementelles Hinzufügen per KI:** Über das Freitextfeld eingegebene und von der KI zerlegte Stichpunkte werden automatisch mit Standard-Wichtung 3 am Ende der Liste angehängt, ohne bestehende Wichtungen zu verändern.
* **Fallback auf Standard-Ziele:** Bei leerer Zielliste werden die Standard-Ziele (`default_goals`) mit Wichtung 3 initialisiert und gespeichert.

---

## 4. Prompt-Integration & KI-Steuerung

### 4.1 Prioritäts-Tags im Prompt (`PromptBuilder.kt`)
Ziele werden im Prompt mit einem strukturierten Präfix übergeben:
```text
1. [Priorität 5/5] Blutdruck senken
2. [Priorität 3/5] Weniger Zucker
3. [Priorität 1/5] 2l Wasser trinken
```

* **Exakte Zielzuordnung:** Der Ziel-Wortlaut für das Antwort-JSON bleibt identisch zum reinen Zieltext (`goal.text`), sodass das Antwort-Parsing und die Zuordnung von `GoalResult` robust und unverändert funktionieren.

### 4.2 LLM-Instruktionen & Bewertungsregeln (`strings.xml`)
Die Prompt-Instruktionen fordern die KI explizit dazu auf, die Priorität bei der Synthese zu berücksichtigen:
* **Einzelmahlzeiten-Bewertung (`prompt_eval_ampel_meaning`):**
  > „Die Prioritätsangabe [Priorität X/5] (1=sehr niedrig bis 5=sehr hoch) bestimmt, wie stark ein Ziel den Gesamteindruck (zusatz) prägt: Verstöße gegen Ziele mit hoher Priorität (4–5) wiegen schwer, Ziele mit niedriger Priorität (1–2) werden toleranter gewertet.“
* **Tages- und Wochenfazit (`prompt_dayfazit_rules`):**
  > „Ziele mit hoher Priorität ([Priorität 4/5] oder [Priorität 5/5]) prägen das Gesamtfazit maßgeblich, während Ziele mit niedriger Priorität ([Priorität 1/5] oder [Priorität 2/5]) toleranter bewertet werden.“

---

## 5. Getestete Edge Cases & Validierung

* **Wertebereich:** Validierung von `weight in 1..5` im Konstruktor; automatisches Clamping im Deserialisierer und ViewModel bei fehlerhaften Eingaben.
* **Altdaten-Migration:** Vollständige Unterstützung von Altdaten in DataStore und Backups ohne Datenverlust.
* **Sonderzeichen:** Zieltexte mit Zeilenumbrüchen, Doppelpunkten (`:::`) oder JSON-Zeichen werden sicher und verlustfrei serialisiert.
* **Filterung leerer Einträge:** Leere Ziele werden beim Speichern und Mergen verworfen.
* **State-Preservation:** Erhalt ungespeicherter Gewichtungsanpassungen bei ViewModel-Reinitialisierung und Konfigurationsänderungen via `SavedStateHandle`.
