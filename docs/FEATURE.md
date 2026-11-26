# Besseresser-App – Vollständige Funktionsdokumentation

> **Package:** `de.gurum.besseresser`  
> **Architektur:** Jetpack Compose, Material 3, Kotlin Coroutines & Flow, Koin Dependency Injection, Room SQLite, Jetpack DataStore, CameraX  
> **Prinzip:** Bring Your Own Key (BYOK), 100 % lokale Datenspeicherung, keine Drittanbieter-Cloud, kein Tracking

---

## Inhaltsverzeichnis

1. [Hauptgruppe 1: Ersteinrichtung, KI-Anbindung & Ziele-Konfiguration](#1-ersteinrichtung-ki-anbindung--ziele-konfiguration)
2. [Hauptgruppe 2: Nutzerprofil & Personalisierung](#2-nutzerprofil--personalisierung)
3. [Hauptgruppe 3: Mahlzeitenerfassung, Kamera & Notizen](#3-mahlzeitenerfassung-kamera--notizen)
4. [Hauptgruppe 4: KI-Bewertung, Ampelsystem & Auswertungsansicht](#4-ki-bewertung-ampelsystem--auswertungsansicht)
5. [Hauptgruppe 5: Verlauf, Tagesstruktur & Mahlzeiten-Verwaltung](#5-verlauf-tagesstruktur--mahlzeiten-verwaltung)
6. [Hauptgruppe 6: Mahlzeiten-Detailansicht, Duplizieren & Teilen](#6-mahlzeiten-detailansicht-duplizieren--teilen)
7. [Hauptgruppe 7: KI-Tagesfazit & KI-Wochenfazit](#7-ki-tagesfazit--ki-wochenfazit)
8. [Hauptgruppe 8: App-Konfiguration, Datensicherung & Datenschutz](#8-app-konfiguration-datensicherung--datenschutz)
9. [Hauptgruppe 9: Navigation, Wischgesten & Barrierefreiheit](#9-navigation-wischgesten--barrierefreiheit)
10. [Hauptgruppe 10: Technische KI-Engine, Datenarchitektur & Offline-Resilienz](#10-technische-ki-engine-datenarchitektur--offline-resilienz)

---

## 1. Ersteinrichtung, KI-Anbindung & Ziele-Konfiguration

### 1.1 Erststart-Assistent (Onboarding & Ersteinrichtung)
* **Willkommens-Screen & Ersteinrichtungs-Modus:** Wird die App zum ersten Mal gestartet (`Settings.isConfigured == false`), öffnet sich automatisch der Onboarding-Modus des `SetupScreen` (`isFirstRun = true`). Dieser begrüßt den Nutzer und führt ihn strukturiert durch alle erforderlichen Schritte.
* **Schrittweiser Einrichtungsablauf:**
  1. *KI-Verbindung einrichten:* Auswahl des KI-Anbieters, Eingabe des API-Keys und Wahl des Modells.
  2. *Ernährungsziele festlegen:* Vorkonfigurierte Standardziele übernehmen, anpassen oder neue Ziele erfassen und priorisieren.
  3. *Backup-Import (optional):* Ein direkter Button erlaubt es Nutzern beim Gerätewechsel, sofort ein vorhandenes JSON-Backup einzuspielen.
* **Automatischer Übergang zum Profil:** Nach erfolgreichem Abschluss des Erst-Setups wird der Nutzer unmittelbar zur optionalen Profilerfassung (`ProfileScreen`) weitergeleitet, um demografische und gesundheitliche Rahmenbedingungen festzuhalten.

### 1.2 KI-Verbindungs- & Schnittstellenkonfiguration (BYOK – Bring Your Own Key)
* **OpenAI-kompatible REST-Schnittstelle:** Die App kommuniziert über standardisierte HTTP-POST-Endpunkte (`{baseUrl}/chat/completions`) mit Vision-fähigen Sprachmodellen.
* **Sicherheitsvalidierung & HTTP/HTTPS-Regeln (`NetworkSecurity.kt`):**
  * Externe URLs müssen zwingend das sichere HTTPS-Protokoll nutzen.
  * Lokale Entwicklungs- und Self-Hosted-Server (`localhost`, `127.0.0.1`, `10.0.2.2`, LAN-IPs wie `192.168.x.x` oder `*.local`) sind für unverschlüsseltes HTTP freigegeben.
* **Keystore-Verschlüsselung (`KeyVault.kt`):** Der API-Key wird niemals im Klartext im Shared DataStore abgelegt, sondern mittels Android Keystore und AES-256-GCM hardwaregestützt verschlüsselt.
* **Modell-Auswahl:** Unterstützung moderner multimodaler Modelle (z. B. `gpt-4o-mini`, `gemini-1.5-flash`, `claude-3-5-sonnet`, `meta-llama/llama-3.2-11b-vision-instruct`).

### 1.3 Anbieter-Auswahl (Provider Picker) & Modell-Katalog (Model List)
* **Vordefinierte Anbieter (`PROVIDERS` in `SetupScreen.kt`):** Schnellauswahl vorkonfigurierter Endpunkte für:
  * Google Gemini (OpenAI-kompatibler Endpunkt)
  * OpenRouter (Zugriff auf Dutzende Modelle)
  * OpenAI (Offizielle API)
  * Mistral AI
  * Groq
  * xAI (Grok)
  * Custom / Eigener Endpunkt
* **Dynamisches Laden der Modellliste (`ApiClient.fetchAvailableModels`):** Über den Endpunkt `{baseUrl}/models` fragt die App verfügbare Modelle des konfigurierten Anbieters dynamisch ab.
* **Intelligente Vision-Filterung:** Modelle werden automatisch analysiert und nach Vision-Fähigkeit (`vision`, `image`, `flash`, `gpt-4`, `4o`, `pixtral`) vorselektiert bzw. gruppiert.
* **Fehlerdiagnose:** Tritt beim Abruf ein Fehler auf, wird ein detaillierter Debug-Bericht (inkl. HTTP-Status, Fehlermeldung und Server-Response) im UI angezeigt.

### 1.4 Persönliche Ernährungsziele (Verwaltung & Bearbeitung)
* **Standard-Ziele (`default_goals`):** Ausgewogene Voreinstellungen (z. B. *„Blutdruck senken“*, *„Wenig Zucker / Low-Carb“*, *„Ausreichend Protein“*, *„Viel Gemüse & Ballaststoffe“*, *„Intervallfasten / Kein Knabbern“*).
* **Interaktive Ziel-Chips (`GoalChipItem`):**
  * Hoch-/Runter-Pfeile zur manuellen Umsortierung der Reihenfolge.
  * Löschen-Button (X) zum Entfernen nicht mehr relevanter Ziele.
* **Intelligente Freitext-Zielaufteilung per KI (`ApiClient.splitGoals`):** Gibt der Nutzer mehrere Ziele als Fließtext ein (z. B. *„Ich möchte abnehmen, keinen raffinierten Zucker essen und mindestens 2 Liter Wasser am Tag trinken“*), zerlegt die KI den Text in prägnante, eigenständige Zielstichpunkte.
* **Bestätigungsdialog mit Fallback:** Die aufgeteilten Ziele werden in einem Review-Dialog zur Bestätigung vorgelegt. Bei Netzwerk- oder KI-Fehlern greift eine regelbasierte lokale Heuristik (Trennzeichen-Parser).

### 1.5 Priorisierung & 5-Stufen-Gewichtung persönlicher Ziele
* **5-stufige Segment-Schaltfläche:** Direkte 1-Klick-Auswahl (`1 | 2 | 3 | 4 | 5`) pro Ziel-Chip im Einstellungsbildschirm.
* **Bedeutung der Wichtungsstufen (`WeightedGoal`):**
  * `Stufe 1:` Sehr niedrig (untergeordnete Empfehlungen / Nebenziele)
  * `Stufe 2:` Niedrig
  * `Stufe 3 (Standard):` Mittel / Ausgewogen (Default für alle neuen Ziele)
  * `Stufe 4:` Hoch (wichtige persönliche Regeln)
  * `Stufe 5:` Sehr hoch (Kernziele / absolute Priorität)
* **Prompt-Integration:** Ziele werden in Prompts mit Präfix übergeben (z. B. `1. [Priorität 5/5] Blutdruck senken`). Verstöße gegen Ziele mit Priorität 4–5 prägen den Gesamteindruck und die Fazits maßgeblich, während Stufen 1–2 toleranter gewertet werden.

### 1.6 Historien-Begrenzung & automatische Bereinigung (Retention)
* **Konfigurierbare Vorhaltezeit:** Einstellung in Wochen (`retentionWeeks`: `0` = Unbegrenzt, `4`, `8`, `12`, `26`, `52` Wochen).
* **Automatischer Background-Cleaner:** Beim App-Start prüft `HistoryRepository.cleanupOldEntries` im Hintergrund, ob Einträge das Vorhaltealter überschritten haben, und löscht betroffene Datensätze inklusive zugehöriger Fotodateien vom Speicher.

---

## 2. Nutzerprofil & Personalisierung

### 2.1 Optionale Körperdaten
* **Geburtsdatum & dynamische Altersberechnung:** Auswahl über den Material 3 `DatePicker`. Das Alter (`ageYears`) wird taggenau berechnet und im UI dynamisch angezeigt (z. B. *„42 Jahre alt“*).
* **Körpergewicht:** Eingabe in Kilogramm (unterstützt Komma und Punkt). Validierung im Bereich 1–500 kg.
* **Körpergröße:** Eingabe in Zentimetern. Validierung im Bereich 30–280 cm.

### 2.2 Geschlecht & demografische Einstufung
* **Typsichere Auswahl (`Gender`):**
  * `MALE` (Männlich)
  * `FEMALE` (Weiblich)
  * `DIVERSE` (Divers)
  * `NONE` (Keine Angabe)
* Die Auswahl steuert physiologische Referenzwerte bei der KI-Bewertung des Gesamttages.

### 2.3 Gesundheits- & Ernährungskontext (Freitext)
* **Vorerkrankungen:** Freitextfeld für chronische oder akute Diagnosen (z. B. *Diabetes Typ 2*, *Hypertonie*, *Gicht*, *Zöliakie*).
* **Ernährungsform, Diäten & Unverträglichkeiten:** Erfassung von Ernährungsweisen (z. B. *Ketogen*, *Vegan*, *Low-FODMAP*) sowie bekannten Allergenen (z. B. *Laktoseintoleranz*, *Nussallergie*).
* **Medikamente & Besonderheiten:** Optionale Erfassung relevanter Medikation (z. B. *ACE-Hemmer*, *Metformin*).

### 2.4 Integration der Profildaten in KI-Prompts
* **Gezielte Kontext-Generierung (`Profile.toPromptContext` / `profileContextLine`):**
  * Werden Profildaten hinterlegt, erzeugt die App eine prägnante Kontextzeile (z. B. *„Nutzerprofil: 45 Jahre alt, weiblich, 68 kg, 168 cm, Vorerkrankungen: Bluthochdruck“*).
  * **Datensparsamkeit:** Die Profilzeile wird gezielt in das **Tages- und Wochenfazit** injiziert, wo der physiologische Gesamtkontext relevant ist. In Prompts für **Einzelmahlzeiten** wird sie bewusst weggelassen, um Token zu sparen und die Privatsphäre zu schützen.

---

## 3. Mahlzeitenerfassung, Kamera & Notizen

### 3.1 Startbildschirm & Schnellstart
* **Hero-Bereich:** Zeigt den aktuellen Status der konfigurierten KI-Verbindung sowie die aktiven Ernährungsziele in einer horizontalen Chip-Vorschau (mit `+%d`-Indikator für weitere Ziele).
* **Fixierter Haupt-Aktionsbutton (Main CTA):** Prominenter Button am unteren Bildschirmrand (*„Essen fotografieren“*), der die Kamera unmittelbar startet.
* **Wisch-Hinweis:** Dezenter visueller Hinweis, dass durch Wischen nach links direkt in den Verlauf gewechselt werden kann.

### 3.2 Integrierte Kamera (CameraX)
* **Kamera-Sucher & Ausrichtungsrahmen:** Live-Kamerafeed mit zentriertem Fokusrahmen, der dem Nutzer hilft, Teller und Speisen optimal im Bild zu positionieren.
* **Hardware-Handling:** Automatische Wahl der Rückkamera, Live-Autofokus und Belichtungsoptimierung.
* **EXIF-Orientierung:** Aufnahmen werden automatisch anhand ihrer EXIF-Metadaten verlustfrei in die korrekte Hochformat-Ausrichtung gedreht.
* **Berechtigungs-Workflow:** Integrierte Prüfung der `CAMERA`-Berechtigung mit Erklärungsdialog bei Ablehnung.

### 3.3 Fallback auf Systemkamera
* **Nahtlose Umschaltung:** Falls CameraX auf speziellen Geräten nicht initialisiert werden kann oder der Nutzer dies bevorzugt, startet die App per Intent die Standard-Kamera-App des Betriebssystems (`MediaStore.ACTION_IMAGE_CAPTURE`) und übernimmt das resultierende Foto über einen sicheren `FileProvider`-URI.

### 3.4 Foto-Prüfung & Mahlzeiten-Notizen (Review-Screen)
* **Sofortige Foto-Vorschau (`MainReviewScreen`):** Anzeige des aufgenommenen Fotos vor der Übermittlung.
* **Neuaufnahme:** Schaltfläche *„Foto neu aufnehmen“* verwirft das aktuelle Bild und öffnet sofort wieder den Kamerasucher.
* **Notizfeld:** Großzügiges Textfeld für ergänzende Hinweise (z. B. Zubereitung: *„mit Olivenöl gebraten“*, verdeckte Zutaten: *„Darunter liegt Quinoa“*, Portionsgröße: *„Große Portion / ca. 400g“*).
* **Absenden:** Tipp auf *„Essen bewerten“* startet die KI-Analyse.

### 3.5 Zuletzt verwendete Notizen (MRU-Speicher) & Notiz-Favoriten
* **MRU-Chip-Reihe (`RecentNotesBar`):** Anzeige der letzten bis zu 5 genutzten Notizen als Schnellwahl-Chips oberhalb des Eingabefelds. Ein Klick überträgt den Text direkt in das Notizfeld.
* **Favoriten-Funktion (⭐):** Notizen können per Stern-Symbol dauerhaft als Favoriten angeheftet werden. Angeheftete Favoriten bleiben dauerhaft erhalten und werden nicht durch neue Notizen verdrängt.
* **Verwaltung:** Favoriten können durch erneuten Klick auf das Sternchen gelöst oder über das Kontextmenü gelöscht werden.

### 3.6 Offline-Erfassung & Verbindungsüberwachung
* **Netzwerkerkennung (`NetworkConnectivityChecker`):** Prüft vor dem API-Aufruf die tatsächliche Internetverbindung.
* **Speichern ohne Bewertung:** Ist das Gerät offline oder die KI-Schnittstelle nicht erreichbar, kann die Mahlzeit mit Foto und Notiz als *„unbewertet“* im Verlauf gespeichert werden. Die Bewertung kann später jederzeit bei bestehender Verbindung per Klick nachgeholt werden.

---

## 4. KI-Bewertung, Ampelsystem & Auswertungsansicht

### 4.1 Visuelles Auswertungs-Feedback
* **Animierter Auswertungs-Status (`ReportLoadingCard`):** Während die KI das Foto analysiert, visualisiert eine moderne Karte mit pulsierendem Lade-Spinner den laufenden Vorgang (*„KI bewertet Mahlzeit …“*).
* **Navigations-Sperre:** Während der Bewertung sind Navigationselemente gesperrt, um Race Conditions oder Datenverlust zu verhindern.

### 4.2 Ampel-Bewertungssystem (Traffic-Light Rating) & Handlungsorientierte Optimierungstipps („Wie mache ich es grün?“)
* **Zielbezogene Ampelstufen (`AmpelColor`):**
  * 🟢 **Grün (`GRUEN`):** Speise fördert das Ziel oder ist völlig unbedenklich.
  * 🟡 **Gelb (`GELB`):** Speise ist akzeptabel, weist aber moderate Einschränkungen auf (z. B. etwas zu salzig, mäßiger Zuckergehalt).
  * 🔴 **Rot (`ROT`):** Speise steht im deutlichen Widerspruch zum gesetzten Ziel.
  * ⚪ **Unbekannt / Weiß (`UNKNOWN`):** Aus dem Foto/Text nicht bestimmbar.
* **Prägnante Begründung (`reason`):** Zu jedem Ziel liefert die KI eine ein- bis zweisätzige, leicht verständliche Erklärung.
* **💡 Handlungsorientierter Optimierungstipp (`tipp`):** Bei gelben oder roten Ampeln generiert die KI direkt an der Zielkarte genau einen kurzen, praxistauglichen Satz, wie der Nutzer das Ziel bei diesem Gericht durch Tauschen oder Ergänzen von Zutaten grün machen kann. Bei grünen Ampeln entfällt der Tipp, um die Ansicht aufgeräumt zu halten.

### 4.3 Gesamter Mahlzeiten-Score (Ampelschnitt)
* **Mathematischer Score (0–100 %):** Berechnet aus allen Zielampeln (🟢 = 100 Punkte, 🟡 = 50 Punkte, 🔴 = 0 Punkte).
* **Farbcodiertes Score-Badge:**
  * Score ≥ 75: Grün hinterlegt
  * Score 40–74: Gelb hinterlegt
  * Score < 40: Rot hinterlegt
* **Tooltip & Erläuterung:** Klick auf das Badge erläutert die genaue Zusammensetzung des Ampelschnitts.

### 4.4 Strukturierter Zusammenfassungsblock (`CollapsibleSummaryBlock`)
* **Gesamteindruck (🧠 `zusatz` / `summary`):** Synthese der Mahlzeit unter Berücksichtigung der individuellen Ziel-Wichtungen.
* **Erkannte Speisen & Mengen (🍽️ `erkannt` / `recognizedMeal`):** Konkrete Auflistung der identifizierten Lebensmittel mit geschätzten Gewichts- oder Mengenangaben (z. B. *„ca. 250g Lachsfilet mit 150g Brokkoli und 100g Wildreis“*).
* **Eingegebene Notiz (📝 `note`):** Zeigt die vom Nutzer mitgegebene Zusatznotiz an.
* **Auf-/Zuklappbarkeit:** Für maximale Übersichtlichkeit auf kleineren Displays kann der Block ein- und ausgeklappt werden.

### 4.5 Nachträgliche Korrektur erkannter Speisen (`RecognizedMealEditDialog`)
* **Stift-Icon zur Bearbeitung:** Hat die KI eine Zutat falsch erkannt (z. B. Tofu statt Hähnchen), kann der Nutzer auf das Stift-Symbol tippen und die Beschreibung korrigieren.
* **Sofortige Neubewertung:** Die App sendet einen optimierten Text-Reevaluierungs-Prompt an die KI, welche die Ampeln und Begründungen auf Basis der korrigierten Zutatenliste blitzschnell neu berechnet.

### 4.6 Behandlung unbewerteter Mahlzeiten
* **Unbewertet-Banner:** Mahlzeiten, die offline erfasst oder deren Bewertung abgebrochen wurde, erhalten ein gut sichtbares Hinweis-Banner.
* **Nachbewerten-Aktion:** Ein Klick auf *„Jetzt bewerten“* stößt die KI-Analyse an und aktualisiert den Eintrag nahtlos.

### 4.7 Speicher- & Verwerfungs-Aktionen
* **In den Verlauf speichern:** Übernimmt Foto, Bewertung, Score, Notiz und Zeitstempel dauerhaft in die lokale SQLite-Datenbank.
* **Ohne Speichern verwerfen:** Verwirft die aktuelle Aufnahme nach einer kurzen Sicherheitsabfrage und setzt das UI auf den Startzustand zurück.

---

## 5. Verlauf, Tagesstruktur & Mahlzeiten-Verwaltung

### 5.1 Tagesgruppierte Verlaufsübersicht (Historie)
* **Chronologische Gliederung:** Alle Mahlzeiten werden nach Kalendertagen gruppiert dargestellt (neuester Tag ganz oben).
* **Leerer Verlauf (Empty State):** Ist der Verlauf noch leer, wird eine motivierende Illustration mit Direktlink zur Kamera angezeigt.

### 5.2 Tagesgruppen-Header (`DayGroupHeader`)
* **Formatiertes Tagesdatum:** Anzeige im Format *„Heute, 26. November“*, *„Gestern, 25. November“* oder Wochentag mit Datum.
* **Tages-Ampelschnitt-Badge:** Durchschnittlicher Score aller Mahlzeiten des jeweiligen Tages auf einen Blick.
* **Tages-Ziele-Vorschau:** Kompakte farbige Punkte für alle bewerteten Ziele des Tages.
* **Schnell-Nacherfassungs-Button (+):** Erlaubt das direkte Nachtragen einer Mahlzeit für genau diesen Kalendertag über einen modalen Auswahldialog (Kamera, Fotogalerie oder Nur-Text).

### 5.3 Mahlzeiteneintrag in der Liste (`HistoryItem`)
* **Foto-Thumbnail:** Scharfe, speichereffizient skalierte Vorschau des Mahlzeitenfotos (oder Besteck-Icon bei Nur-Text-Einträgen).
* **Zeitangabe:** Uhrzeit der Aufnahme bzw. der gewählten Nacherfassungszeit sowie der dezente Hinweis *„(nachträglich erfasst)“*.
* **Erkannte Speise & Notiz:** Titelzeile mit den erkannten Speisen und optionaler Notiz darunter.
* **Kompakte Ampel-Punkte & Score:** Übersichtliche Reihe von farbigen Ampel-Punkten für die individuellen Ziele sowie das Gesamtergebnis.

### 5.4 Wischgeste zum Löschen (Swipe-to-Delete) & Undo
* **Wischen nach links:** Ein Wischen über ein Element in der Liste löst den Löschvorgang aus.
* **Temporärer Lösch-Zustand (`PendingDeleteHistoryItem`):** Das Element wird sofort durch eine Karte mit Timer und *„↩ Rückgängig“*-Schaltfläche ersetzt.
* **Undo-Möglichkeit:** Tippt der Nutzer innerhalb von 5 Sekunden auf *„Rückgängig“*, wird die Mahlzeit sofort wiederhergestellt. Nach Ablauf des Timers werden der Datenbankeintrag und das zugehörige Foto endgültig gelöscht.

### 5.5 Manuelle Sortierung der Mahlzeiten
* **Reordering innerhalb eines Tages (`HistoryReorderUseCase`):** Durch langes Drücken und Ziehen (Drag & Drop) oder Sortierpfeile können Mahlzeiten innerhalb eines Kalendertages beliebig verschoben werden.
* **Persistente Reihenfolge:** Das Feld `sortOrder` in `HistoryEntry` sichert die benutzerdefinierte Reihenfolge dauerhaft.

### 5.6 Nacherfassung von Mahlzeiten (+ Feature / Retro Capture)
* **Auslöser & Dialog „Essen nachtragen“ (`NacherfassenChoiceDialog` / `ManualEntryDialog`):**
  Durch Tippen auf das **Plus-Symbol (+)** im Gruppenkopf eines beliebigen Kalendertags öffnet sich ein strukturierter, modal geführter Auswahldialog mit drei flexiblen Erfassungswegen:
  * 📸 **Mit Foto (`NacherfassenChoice.CAMERA`):** Startet unmittelbar den integrierten Kamerasucher (`CameraCaptureScreen`) mit Zentrierungsrahmen, Live-Vorschau und Notizfeld (inkl. automatischem Fallback auf die Systemkamera `TakePicture`), um Speisen direkt für den Zieltag zu fotografieren.
  * 🖼️ **Aus Fotogalerie (`NacherfassenChoice.GALLERY`):** Öffnet den nativen Android Photo-Picker (`ActivityResultContracts.PickVisualMedia`) zur Auswahl eines bereits auf dem Gerät gespeicherten Mahlzeitenfotos. Das gewählte Bild wird atomar in den privaten Speicherbereich kopiert (`PhotoCaptureManager.copyToCaptureFile`), um Datenverlust bei externen Galerie-Änderungen zu verhindern, und für Analyse und Anzeige aufbereitet.
  * ✍️ **Ohne Foto / Nur Text (`NacherfassenChoice.TEXT` / `AddMealNoPhotoScreen`):** Öffnet einen dedizierten Eingabebildschirm für reine Textbeschreibungen (z. B. *„Kantine: 200g Rindergulasch mit Spätzle und grünem Beilagensalat“*). Das Textfeld ist hierbei ein Pflichtfeld – die Senden-Schaltfläche wird reaktiv erst aktiv, sobald eine aussagekräftige Beschreibung eingegeben wurde.
* **Präzise Zeitstempel-Steuerung & Uhrzeit-Auswahl (Time Picker):**
  * Handelt es sich beim Zieltag um einen vergangenen Kalendertag (`isPastDay`), öffnet sich nach der Wahl der Erfassungsmodalität automatisch ein Material 3 `TimePickerDialog` (initial mit der aktuellen Uhrzeit vorbelegt), über den der Nutzer die exakte Uhrzeit der Mahlzeit einstellen kann.
  * Bei einer Nacherfassung für den heutigen Kalendertag wird zur Beschleunigung des Workflows unmittelbar die aktuelle Systemuhrzeit (`LocalTime.now()`) übernommen.
* **Review- & Bewertungs-Overlay (`AddMealReviewScreen` & `AddMealNoPhotoScreen`):**
  * **Opakes Vollbild-Overlay:** Nacherfassungs- und Review-Screens besitzen einen vollständig deckenden Hintergrund, wodurch die darunterliegende Verlaufsliste visuell sauber verdeckt wird.
  * **Tastatur-Mitfahrverhalten (`imePadding`):** Die fixierten Aktions-Buttons (*„Jetzt bewerten“* bzw. *„Essen erfassen“*) passen sich dynamisch an geöffnete Bildschirmtastaturen an und bleiben zu jedem Zeitpunkt sichtbar und bedienbar.
  * **Notiz-Schnellzugriff & Favoriten:** Sowohl im Foto-Review als auch im Text-Screen steht die `RecentNotesButton`-Schaltfläche mit Zugriff auf MRU-Notizen und ⭐-Favoriten zur Verfügung.
  * **Fortschrittsanzeige & Sperre:** Während der KI-Analyse visualisieren ein `LinearProgressIndicator` sowie der modale `BackdatedBusyDialog` den laufenden Vorgang und sperren Fehleingaben.
* **Resilienz & Fehlerbehandlung bei Nacherfassung:**
  * **Offline- & Verbindungsresilienz:** Ist das Gerät offline oder schlägt die KI-Kommunikation fehl, speichert die App die Mahlzeit dennoch zuverlässig mit dem Status *„unbewertet“* und dem gewählten Zeitstempel im Verlauf. Ein Fehlerhinweis (`addError`) informiert den Nutzer transparent.
  * **Zero-Token-Wiederverwendung:** Bei reinen Texteingaben prüft die App, ob im lokalen Cache bereits eine identische Notiz mit gültiger Bewertung für die aktuellen Ziele vorliegt. Ist dies der Fall, wird die Bewertung sofort ohne Netzwerk- und Tokenkosten übernommen.
  * **Sichere Speicherbereinigung & Prozess-Sicherheit:** Alle Zustände (`addTargetDay`, `addTargetTime`, `addNote`, Overlays) sind über `SavedStateHandle` gegen Prozess-Terminierungen abgesichert. Temporäre Foto-Caches werden bei Abbruch (`cancelAddPhotoCapture`) oder erfolgreicher Persistierung rückstandslos gelöscht.
* **Kennzeichnung & Transparenz (`backdated`):**
  * Nachträglich erfasste Mahlzeiten werden in der Datenbank mit `backdated = true` und dem gewählten Zeitstempel markiert.
  * In der Verlaufsliste (`HistoryItem`), in der Detailansicht (`HistoryDetailScreen`) sowie in den KI-Prompts wird die Mahlzeit transparent als *„(nachträglich erfasst)“* bzw. *„nachträglich erfasst“* ausgewiesen.
* **Automatische Fazit-Invalidierung (Stale-Status):**
  * Das Hinzufügen einer nacherfassten Mahlzeit verändert die Datenbasis des Tages und der Kalenderwoche.
  * Bereits vorhandene Tages- und Wochenfazits werden über `deleteDayFazitForEntry` und `deleteWeekFazitForEntry` invalidiert und im UI sofort als veraltet (`isStale = true` / *„⟳ Neu bewerten“*) markiert, damit der Nutzer die Gesamtbewertung per Fingertipp aktualisieren kann.

---

## 6. Mahlzeiten-Detailansicht, Duplizieren & Teilen

### 6.1 Vollständige Detailansicht (`HistoryDetailScreen`)
* **Großes Mahlzeitenfoto:** Hochauflösende Darstellung des aufgenommenen Fotos mit Zoom-Möglichkeit (bzw. Besteck-Icon bei Nur-Text-Einträgen).
* **Ausführliche Ampel-Karten:** Jedes Ziel wird in einer eigenen Karte mit Ampelfarbe (🟢/🟡/🔴), Wichtungstag und der vollständigen Begründung der KI dargestellt.
* **💡 Handlungsorientierte Optimierungstipps:** Liegt bei einem Ziel eine gelbe oder rote Ampel vor, wird direkt unter der Begründung eine kursive Tippzeile mit Glühbirnen-Symbol (`💡`) angezeigt, die konkrete Zutatentausche oder -ergänzungen vorschlägt.
* **Erweiterte Metadaten:** Anzeige von exaktem Erfassungsdatum, Uhrzeit, Nacherfassungsstatus (`(nachträglich erfasst)`) und vergebenem Gesamt-Score.

### 6.2 Mahlzeit duplizieren
* **Duplizieren-Aktion:** Ermöglicht das Kopieren einer bestehenden Mahlzeit auf einen beliebigen anderen Tag (z. B. bei Mahlzeiten-Wiederholungen wie Meal-Prep).
* **Datum & Uhrzeit-Auswahl:** Über Standard-Material-Picker wählbar.
* **Vollständige Übernahme:** Foto, erkannte Speisen, Notiz und Ampelbewertungen werden kopiert und als neuer Eintrag persistiert.

### 6.3 Mahlzeit teilen (Share Sheet & Clipboard)
* **Formatierte Text-Aufbereitung:** Erzeugt eine übersichtliche Textzusammenfassung (inkl. Emojis, erkannten Speisen, Ampeln und Gesamteindruck).
* **Natives Android Share-Sheet:** Ermöglicht das direkte Senden an Messenger (WhatsApp, Signal, Telegram), E-Mail oder Notizen-Apps.
* **Automatisches Clipboard-Backup:** Der formatierte Text wird parallel in die Zwischenablage kopiert.

### 6.4 Nachträgliche Korrektur & Neubewertung im Detail
* **Korrektur-Workflow:** Direkte Bearbeitung der erkannten Speise im Detailbildschirm über das Stift-Icon.
* **KI-Erstbewertung für unbewertete Einträge:** Verfügt ein Verlaufseintrag noch über keine Bewertung, kann diese über den ✨-Button direkt im Detailbildschirm angestoßen werden.

### 6.5 Eintrag löschen
* **Lösch-Button in der Top-Bar:** Ermöglicht das gezielte Entfernen des Eintrags mit Bestätigungsdialog.

---

## 7. KI-Tagesfazit & KI-Wochenfazit

### 7.1 KI-Tagesfazit (`DayFazit`) & Zustandsmaschine
* **Interaktiver Fazit-Chip im Tages-Header:**
  * `Idle:` *„✨ Tagesfazit ermitteln“* (Startet die Generierung manuell)
  * `Loading:` *„Tagesfazit …“* (Mit animiertem Fortschritts-Spinner)
  * `Ready:` Zeigt die Gesamtampel des Tages (🟢/🟡/🔴) und öffnet bei Klick die Detailkarte
  * `Stale:` *„⟳ Neu bewerten“* (Signalisiert veraltetes Fazit nach Änderungen)
* **Tagesfazit-Karte (`FazitCard`):** Ausführliche Zusammenfassung des gesamten Ernährungstages mit Stärken, Schwächen und konkreten Verbesserungstipps.

### 7.2 Intelligenter Fingerabdruck & Veralterungserkennung (`dayFingerprint`)
* **Deterministischer Hash:** Die App berechnet aus allen Mahlzeiten-IDs und den erkannten Speisen eines Tages einen Prüf-Hash (`dayFingerprint`).
* **Automatische Veralterung:** Wird an einem Tag eine Mahlzeit hinzugefügt, gelöscht oder nachträglich korrigiert, stimmt der Fingerprint nicht mehr überein. Das Fazit wird automatisch als veraltet (`isStale = true`) markiert und fordert zur Neuberechnung auf.

### 7.3 Automatische Abend-Generierung (Auto-Fazit)
* **Trigger-Logik (`TriggerAutoFazitUseCase`):**
  * Öffnet der Nutzer ab 18:00 Uhr (`FAZIT_AUTO_HOUR = 18`) den Verlauf, prüft die App automatisch die beiden neuesten Tage (heute und gestern).
  * Liegen bewertete Mahlzeiten vor und existiert noch kein Fazit, wird die Tagesfazit-Ermittlung automatisch im Hintergrund gestartet.

### 7.4 KI-Wochenfazit (`WeekFazit`)
* **Kalenderwochen-Auswertung:** Am Ende jeder Kalenderwoche (Montag bis Sonntag) fasst die KI den gesamten Wochentrend zusammen.
* **Aktivierungs-Zeitfenster:** Das Wochenfazit wird sonntags ab 18:00 Uhr zur Generierung freigegeben.
* **Ganzheitliche Synthese:** Berücksichtigt Konstanz, Makronährstoff-Verteilung und langfristige Zielerreichung über alle 7 Tage.

### 7.5 Kontextuelle Synthese & Wichtungsberücksichtigung
* **Genuss-Snacks (Cheats & Ausnahmen):** Die Prompt-Instruktionen leiten die KI an, einzelne Snacks oder Ausnahmen nicht überzudramatisieren, wenn der restliche Tag vorbildlich war.
* **Gewichtete Bewertung:** Schwerwiegende Verstöße gegen Ziele mit hoher Priorität (`[Priorität 5/5]`) führen zu einer deutlichen Herabstufung des Fazits.
* **Profildaten-Einbezug:** Das Nutzerprofil (Alter, Geschlecht, Körpermaße, Vorerkrankungen) fließt direkt in die Bewertung des Tagesbedarfs ein.

---

## 8. App-Konfiguration, Datensicherung & Datenschutz

### 8.1 Sprache & Lokalisierung
* **Vollständige Zweisprachigkeit:** Alle Benutzeroberflächen, Fehlermeldungen, System-Dialoge und KI-Prompts sind auf Deutsch und Englisch (`values-en`) verfügbar.
* **Sprachumschaltung:** Manuelle Auswahl in den Einstellungen (`AppLanguage.DE`, `AppLanguage.EN`) sowie nahtlose Unterstützung von Android 13+ App-Spracheinstellungen (`LocaleManager`).

### 8.2 Design & Erscheinungsbild (Theme)
* **Theme-Modi (`AppThemeMode`):**
  * `SYSTEM` (Folgt dem Android-Systemdesign)
  * `LIGHT` (Helles Design)
  * `DARK` (Dunkles Design)
* **Farbschema:** Harmonische Smaragd-/Salbeitöne mit hohem Kontrast für Ampeln und Textanzeigen.

### 8.3 Vollständige Datensicherung (JSON-Export)
* **1-Klick-Export (`BackupStore.exportBackup`):** Erstellt eine strukturierte, lesbare JSON-Datei im Standard-Ordner `Downloads`.
* **Umfang der Sicherung (`AppBackup`):**
  * API-Einstellungen (inkl. entschlüsseltem API-Key)
  * Persönliche Ernährungsziele inklusive individueller Wichtungen (1–5)
  * Vollständiges Nutzerprofil (Körperdaten, Vorerkrankungen, Ernährungskontext)
  * Notiz-Favoriten und MRU-Liste
  * Alle Verlaufseinträge inklusive Base64-codierter Original-JPEG-Fotos
  * Sämtliche Tages- und Wochenfazits
* **Statusbericht (`BackupSummary`):** Detaillierte Rückmeldung über Anzahl exportierter Mahlzeiten, Fotos und Einstellungen.

### 8.4 Datensicherung wiederherstellen (JSON-Import)
* **System-Dateipicker:** Auswahl der Sicherungsdatei über den nativen Android Storage Access Framework (SAF) Dateipicker.
* **Intelligentes Merging (`BackupLogic`):**
  * Einstellungen und Profil werden aktualisiert.
  * Verlaufseinträge und Fazits werden zusammengeführt (bestehende Einträge bleiben erhalten, neue werden ergänzt).
  * Base64-Fotos werden verlustfrei decodiert und im internen App-Speicher abgelegt.
* **Abwärtskompatibilität & Altdaten-Migration:** Backups älterer Versionen (z. B. ungewichtete String-Ziellisten) werden automatisch migriert und mit Standard-Wichtungen versehen.

### 8.5 Verlaufsdaten zurücksetzen
* **Bereinigungsfunktion:** Ermöglicht das vollständige Leeren des Verlaufs, aller Fotos sowie aller Tages- und Wochenfazits.
* **Sicherheits-Dialog:** Zweistufige Bestätigung verhindert versehentlichen Datenverlust.
* **Konfigurationsschutz:** Einstellungen, API-Keys, Ernährungsziele, Nutzerprofil und Notiz-Favoriten bleiben beim Zurücksetzen des Verlaufs unberührt.

### 8.6 Datenschutz, Privatsphäre & lokale Datenspeicherung (BYOK-Prinzip)
* **100 % Lokale Datenhaltung:** Sämtliche Datenbanken (Room SQLite), Präferenzen (Jetpack DataStore) und Bilddateien verbleiben ausschließlich auf dem physischen Endgerät des Nutzers.
* **Keine Zwischenserver & Kein Tracking:** Es existieren keine Entwickler-Server, keine Benutzer-Accounts, keine Werbe-SDKs und kein Nutzungs-Tracking.
* **Direkte Punkt-zu-Punkt-Kommunikation:** Die Kommunikation erfolgt ausschließlich direkt zwischen dem Smartphone und dem vom Nutzer gewählten KI-Endpunkt.

---

## 9. Navigation, Wischgesten & Barrierefreiheit

### 9.1 App-Navigation & Hauptmenü
* **Top-App-Bar (`MainTopBar`):** Einheitliche Kopfzeile mit App-Titel, Status-Indikator und Burger-Menü.
* **Burger-Menü:** Schneller Zugriff auf:
  * 👤 *Nutzerprofil*
  * 📜 *Verlauf*
  * ⚙️ *Einstellungen (Ziele & API)*
  * 💾 *Konfiguration & Datensicherung*

### 9.2 Wischgesten-Steuerung (Swipe Navigation)
* **Horizontale Wischgeste:** Auf dem Hauptbildschirm kann durch Wischen nach links direkt in den Verlauf gewechselt werden.
* **Intelligente Wisch-Sperre:** Befindet sich der Nutzer in der Fotoaufnahme, bei der Notizeingabe oder in einem ungespeicherten Bewertungsergebnis, ist die Wischgeste deaktiviert, um versehentliches Verlassen zu verhindern.

### 9.3 Konsistente Zurück-Navigation (`BackHandler`)
* **Deterministisches Back-Stack-Verhalten:**
  * Detailansicht → Zurück zum Verlauf
  * Verlauf → Zurück zum Startbildschirm
  * Profil / Einstellungen / Datensicherung → Zurück zum Startbildschirm
  * Aktiver Kamera-Sucher → Schließen und zurück zum Startbildschirm
  * Foto-Review / Unbewertetes Bild → Verwerfen-Abfrage und zurück zum Start
  * Startbildschirm (ohne Overlays) → App beenden (Android-Standard)

### 9.4 Animierte Bildschirmübergänge
* **Weiche Richtungsanimationen (`AnimatedContent`):** Übergänge zwischen Startbildschirm, Verlauf und Detailansichten gleiten anhand ihrer logischen Bildschirmreihenfolge (`AppScreen.order`) sanft horizontal über das Display.

### 9.5 Barrierefreiheit (Accessibility) & Usability
* **Vollständige Screenreader-Unterstützung:** Sämtliche Icons, interaktiven Schaltflächen und Ampeln besitzen präzise, lokalisierte `contentDescription`-Texte.
* **Kontraststarke Farbgestaltung:** Alle Text- und Ampelfarben erfüllen die WCAG 2.1 AA Kontrastanforderungen.
* **Haptisches Feedback:** Bestätigungsvibrationen bei Foto-Auslösung und Wischaktionen.

---

## 10. Technische KI-Engine, Datenarchitektur & Offline-Resilienz

### 10.1 KI-Kommunikation & Prompt-Engineering
* **Zweisprachige Prompt-Generierung (`PromptBuilder.kt`):** Erstellt dynamisch lokalisierte System- und User-Prompts abhängig von der eingestellten App-Sprache.
* **Dedizierte Prompt-Bundles:**
  * `createSingleMealPrompt`: Multimodaler Prompt für Foto + Notiz + gewichtete Ziele.
  * `createTextOnlyPrompt`: Optimierter Prompt für reine Text-Mahlzeiten ohne Foto.
  * `createCorrectionPrompt`: Text-Prompt zur blitzschnellen Neubewertung korrigierter Speisen.
  * `createDayFazitPrompt`: Umfassender Synthese-Prompt für den gesamten Tag inkl. Profildaten.
  * `createWeekFazitPrompt`: Aggregations-Prompt für 7-Tage-Trends.
  * `createSplitGoalsPrompt`: Zerlegungs-Prompt für Fließtext-Zieleingaben.
* **JSON-Rückgabe-Kontrakt:** Erzwingt valides JSON mit festem Schema:
  ```json
  {
    "results": [
      {
        "goal": "Blutdruck senken",
        "color": "gelb",
        "reason": "Erhöhter Natriumgehalt durch Sojasoße.",
        "tipp": "Sojasoße durch natriumarme Variante ersetzen oder mit Wasser/Zitrone strecken."
      }
    ],
    "zusatz": "Ausgewogene Mahlzeit mit hohem Gemüseanteil.",
    "erkannt": "Gedämpfter Lachs mit Brokkoli"
  }
  ```

### 10.2 Fehlertolerantes JSON-Parsing & Heuristik (`LlmResponseParser.kt`)
* **Markdown-Fence-Bereinigung (`stripCodeFences`):** Entfernt zuverlässig ```json- und ```-Ummantelungen aus LLM-Antworten.
* **Reparatur unvollständiger JSON-Strings (`closeJson`):** Schließt fehlende Klammern (`}`, `]`) oder Anführungszeichen bei unvollständigen Token-Streams heuristisch ab.
* **Robuste Key-Aliase:** Erkennt alternative Schlüssel automatisch:
  * `results` | `ergebnisse` | `goals` | `ampeln`
  * `zusatz` | `summary` | `fazit` | `gesamteindruck`
  * `erkannt` | `recognizedMeal` | `food` | `speise`
  * `reason` | `begruendung` | `grund`
* **Farb-Normalisierung:** Mappt alle gängigen Schreibweisen und Emojis (`gruen`, `grün`, `green`, `🟢` → `AmpelColor.GRUEN`; `gelb`, `yellow`, `🟡` → `AmpelColor.GELB`; `rot`, `red`, `🔴` → `AmpelColor.ROT`).

### 10.3 Lokale Bildverarbeitung & Speicheroptimierung
* **Vorverarbeitung (`ImagePreprocessor.kt`):**
  * Skaliert Fotos für die KI-Übertragung auf maximal 1024×1024 Pixel herunter.
  * Komprimiert Bilder als JPEG mit hoher Qualität (85 %) und wandelt sie in Base64-Data-URIs (`data:image/jpeg;base64,...`) um.
* **Lokaler Fotospeicher (`PhotoStorage.kt`):** Speichert Originalaufnahmen im privaten App-Verzeichnis und generiert kompakte Thumbnails für die Verlaufsliste.
* **Automatische Verwaisten-Bereinigung:** Löscht physische Bilddateien automatisch, wenn ein Verlaufseintrag gelöscht oder verworfen wird.

### 10.4 Architektur & Persistenzschichten
* **Dependency Injection (`AppModule.kt`):** Schlankes, modulares Koin-Setup für Singletons, Repositories, Use Cases und ViewModels.
* **Room SQLite-Datenbank (`AppDatabase.kt`):**
  * `HistoryEntryDao`: CRUD-Operationen, Sortierung und Datumsbereichs-Abfragen für Mahlzeiten.
  * `DayFazitDao`: Persistierung von Tages- und Wochenfazits inkl. Veralterungs-Status (`isStale`).
  * TypeConverter für `Evaluation`, `AmpelColor` und Datums-Timestamps.
* **Jetpack Preferences DataStore:** Reaktive Persistenz für App-Einstellungen (`Settings`), Nutzerprofil (`Profile`), Notiz-Historie (`RecentNotes`) und UI-Präferenzen (`UiPrefs`).
* **Reaktivität:** Durchgängige Verwendung von Kotlin Coroutines und `StateFlow` für reaktive UIs ohne unnötige Recompositions.

### 10.5 Netzwerk-Sicherheit & Konnektivitätsprüfung
* **Verbindungs-Checker (`NetworkConnectivityChecker.kt`):** Nutzt Android `ConnectivityManager` für zuverlässige Online-Prüfungen.
* **Fehler-Mapping (`ErrorMapper.kt`):** Fängt HTTP-Fehlercodes (z. B. 401 Unauthorized, 404 Not Found, 429 Rate Limit, 500 Server Error) sowie Timeouts und SSL-Fehler ab und übersetzt sie in klare, verständliche Hilfetexte für den Nutzer.
