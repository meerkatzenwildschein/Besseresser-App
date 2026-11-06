# UI/UX Improvements – Besseresser

Dieses Dokument beschreibt sämtliche durchgeführten UI/UX-Verbesserungen, mit denen **Besseresser** visuell und ergonomisch auf ein modernes, konsistentes Flaggschiff-Niveau gehoben wurde.

---

## 1. Eigenständiges Farb- & Form-Designsystem (`MainActivity.kt`)
- **Frische Ernährungs-Farbpalette statt generischem Material-3-Violett:**
  - **Light-Mode (`BesseresserLightColors`):** Kräftiges Smaragdgrün (`#1B7F4C`) als Primärfarbe, sanfte Salbei-Container (`#D6F5E3`), warme, augenschonende Oberflächen (`#F6FAF7` / `#E6EFE9`) und harmonische Tertiär-Akzente (`#286375`).
  - **Dark-Mode (`BesseresserDarkColors`):** Leuchtendes Mint-Smaragd (`#63D999`) auf tiefem, kontrastreichem Natur-Dunkelgrün (`#0F1512` / `#1E2823`), optimal für OLED-Displays und abendliche Nutzung.
- **Abgestimmte Formsprache (`BesseresserShapes`):**
  - Durchgängig weiche, moderne Radien (`8dp` bis `28dp`) für Karten, Dialoge, Chips und Eingabefelder.
- **Geschmeidige Screen-Übergänge:**
  - Fein abgestimmte `FastOutSlowInEasing`-Slide- und Fade-Animationen (`280 ms` / `220 ms`) beim Wechsel zwischen Hauptansicht, Profil, Verlauf und Detailansicht.

---

## 2. Gemeinsame UI-Komponenten & Hierarchie (`CommonUi.kt`)
- **Zentrierte `TopBarRow` mit taktilen Buttons:**
  - **Bugfix:** Die Überschrift in `TopBarRow` war zuvor durch `Arrangement.End` rechtsbündig an den rechten Rand gedrückt. Sie ist nun echt zentriert (`Arrangement.Center`) mit `TextOverflow.Ellipsis`.
  - **Taktiler Zurück-Button:** Sitzt in einem sanft getönten Kreis-Container (`42dp`) für bessere Erkennbarkeit und klare Touch-Fläche.
  - **Icon-Badge:** Optionale Screen-Icons vor dem Titel erhalten einen farbigen `primaryContainer`-Kreis.
- **Neugestaltete Ziel-Bewertungskarten (`GoalRow`):**
  - **Zweistufiger Ampel-Indikator:** Äußerer Halo-Ring (`36dp`, 20 % Deckkraft) plus satter innerer Ampel-Kern (`20dp`).
  - **Farbiger Ampel-Pill-Badge:** Statt den Ampelnamen (`Grün – ...`) unstrukturiert in den Fließtext zu kleben, sitzt rechts neben dem Zielnamen ein farbig abgesetztes Pill-Badge (`Grün` / `Gelb` / `Rot`) mit feinem Rahmen.
  - **Dezente Karten-Tönung:** Bewertete Ziele erhalten eine feine Rahmenlinie in ihrer jeweiligen Ampelfarbe (`color.copy(alpha = 0.35f)`) sowie animierte Größenübergänge (`animateContentSize`).
- **Strukturierte KI-Zusammenfassungskarte (`CollapsibleSummaryBlock`):**
  - Aufgeklappte Details (`🍽️ Erkannt` und `📝 Anmerkung`) werden durch einen feinen Divider getrennt und in eigenen inneren Mini-Cards dargestellt.
  - Der Korrektur-Stift (`Icons.Filled.Create`) sitzt prominent in einem eigenen `primaryContainer`-Kreisbutton.
  - Der Auf-/Zuklapp-Hinweis („weitere Details“ / „Details einklappen“) ist als primärfarbiger Pill-Button mit animiertem Chevron gestaltet.
- **Aufgewertetes `ErrorBanner`:**
  - Mit Warn-Icon (`Icons.Filled.Warning`), feinem Fehlerrahmen und optimierter Zeilenhöhe.
- **Wiederverwendbare `SectionCard`:**
  - Gruppiert zusammengehörige Formularfelder in Setup und Profil in übersichtliche Karten mit Abschnittsüberschrift.

---

## 3. Hauptbildschirm & Auswertung (`MainScreen.kt`)
- **Markante `MainTopBar` mit Direktzugriff auf den Verlauf:**
  - Links neben dem App-Titel („Besseresser“) zeigt ein abgerundetes Marken-Badge die drei Ampelpunkte (🟢🟡🔴).
  - Rechts befindet sich neben dem Menü-Button nun ein **direkter Verlauf-Schnellzugriff-Button** (`Icons.Filled.DateRange`), sodass der Verlauf jederzeit mit einem Fingertipp erreichbar ist (zusätzlich zur Wischgeste und zum Menü).
- **Einladende Hero-Card im Leerzustand (`MainHeroCard`):**
  - Solange noch kein Foto aufgenommen wurde, begrüßt eine anklickbare Hero-Card („Bereit für deine nächste Mahlzeit?“) den Nutzer mit Icon-Badge, Erklärungstext und einem dezenten Hinweis-Chip für die Wischgeste zum Verlauf. Ein Tipp auf die Karte öffnet direkt die Kamera.
- **Randlose Foto-Darstellung (`ContentScale.Crop`) & Gesamt-Score:**
  - Mahlzeiten-Fotos in Review und Auswertung füllen ihre abgerundete Karte (`20dp` Radius) nun sauber per `ContentScale.Crop` aus (keine unschönen Balken mehr bei Hochformat-Fotos).
  - Direkt rechts neben der Überschrift „Deine Ziele“ wird nach der Auswertung der **Gesamt-Ampelschnitt (`ScoreCircle`, 0–100)** angezeigt.
- **Sanfter Farbverlauf hinter fixierten Bottom-CTA-Buttons (`MainFixedCtaButtons`):**
  - Ein vertikaler Hintergrund-Verlauf (`Brush.verticalGradient`) sorgt dafür, dass scrollende Inhalte weich hinter dem unteren Hauptbutton („📷 Essen fotografieren“ / „📷 Neues Foto aufnehmen“) ausblenden, statt hart mit ihm zu kollidieren.
- **Modale „KI bewertet …“-Karte (`MainAiBusyCard`):**
  - Mit abgedunkeltem Hintergrund-Scrim (`35 %` Schwarz), erhöhter Surface-Card, Halo-Spinner und feiner Primär-Kontur.

---

## 4. Kamera & Foto-Prüfung (`CameraScreen.kt`)
- **Live-Vorschau mit besserer Lesbarkeit & Orientierung:**
  - Der Zurück-Button oben links liegt in einem halbtransparenten dunklen Kreis (`50 %` Schwarz) und bleibt so auch vor hellen Fotomotiven jederzeit sichtbar.
  - Ein dezenter Hinweis-Chip oben mittig („Mahlzeit gut sichtbar im Bild platzieren“) unterstützt bei der Bildkomposition.
  - Verfeinerter Auslöser-Ring (`84dp` Außenring mit abgedunkeltem Innenspiegel und `64dp` Auslöserkern).
- **Kritischer Light-Mode-Kontrast-Fix nach der Aufnahme:**
  - **Vorher:** Nach dem Auslösen blieb der Hintergrund tiefschwarz (`Color.Black`), während das `OutlinedTextField` im Light-Mode dunkle Schrift verwendete – eingegebener Text war nahezu unsichtbar.
  - **Jetzt:** Die Nachprüf-Ansicht nutzt sauber `MaterialTheme.colorScheme.background`, `TopBarRow`, `ContentScale.Crop` für die Vorschau sowie einen zusätzlichen **„Foto neu aufnehmen“-Button (`OutlinedButton`)**, um direkt in der Kamera-Ansicht ein neues Bild schießen zu können.

---

## 5. Verlauf, Tages-/Wochenfazit & Nacherfassung (`HistoryListScreen.kt`, `DayGroupHeader.kt`, `DayFazitCard.kt`, `HistoryDetailScreen.kt`, `ManualEntryDialog.kt`)
- **Einladender Empty-State im Verlauf:**
  - Statt bloßem Text zeigt ein leerer Verlauf nun eine gestaltete Karte mit `72dp` Icon-Badge (`🍽️`), Titel und erklärendem Untertitel.
- **Aufgewertete Mahlzeiten-Karten (`HistoryItem`):**
  - **Quadratische Thumbnails ohne Trauerränder:** `ContentScale.Crop` mit `12dp` Radius und feiner Kontur; Einträge ohne Foto erhalten eine sauber getönte Platzhalter-Kachel (`🍽️`).
  - **Mini-Ampelpunkte auf einen Blick:** Neben der Uhrzeit zeigen kleine farbige Punkte (`7dp`) direkt die Einzel-Ampeln der Mahlzeit in der Listenansicht.
  - **Doppelring-`ScoreCircle`:** Das Score-Badge rechts besitzt nun einen farbigen Halo-Ring mit Rahmen für maximale visuelle Klarheit.
- **Tages- & Wochenfazit (`DayGroupHeader`, `DayFazitCard`):**
  - Einheitliche **40dp Höhe** für den „+“-Button und alle `HeadChip`-Zustände mit harmonischem `8dp` Abstand und vertikalem Akzentbalken vor dem Tagesdatum.
  - Aufgeklappte Fazit-Texte (`FazitExpandedCard`) besitzen einen farbigen Rahmen sowie einen vertikalen Ampel-Akzentbalken passend zur Tages-/Wochenampel und animieren das Auf-/Zuklappen weich per `animateContentSize` und rotierendem Chevron.
- **Komfortabler Nacherfassungs-Dialog (`NacherfassenChoiceDialog`):**
  - Statt „Mit Foto“ und „Ohne Foto“ in kleine Dialog-Textbuttons zu quetschen, bietet der Dialog nun zwei große, klar gestaltete Auswahlkarten (**„📷 Mit Foto“** und **„✍️ Ohne Foto“**) sowie einen separaten „Abbrechen“-Button.
  - Auch beim Nacherfassen eingegebene Notizen werden nun automatisch in `RecentNotesStore` übernommen (unter Erhalt bestehender Favoriten-Sterne).

---

## 6. Einstellungen & Profil (`SetupScreen.kt`, `ProfileScreen.kt`)
- **Lokalisierungs-Fix in `SetupScreen`:**
  - Der zuvor hartcodierte Titel `"Einstellungen"` nutzt nun korrekt `stringResource(R.string.menu_settings)` (Deutsch/Englisch synchron).
- **Karten-Gruppierung (`SectionCard`):**
  - **Setup:** Klare Trennung in die Karten *„KI-Verbindung“* (URL, Anbieter-Button, API-Key, Modell, Modell-Ladebutton) und *„Persönliche Ziele“*.
  - **Profil:** Klare Trennung in *„Körperdaten“* (Geburtsdatum, Geschlechts-Chips sowie **Gewicht & Körpergröße nebeneinander in einer 2-Spalten-Zeile**) und *„Gesundheit & Ernährung“*.
- **Anbieter- & Modell-Auswahllisten als Karten:**
  - In der Anbieter-Auswahl (`PROVIDERS`) wird der aktuell gewählte Anbieter farblich hervorgehoben.
  - In `ModelListScreen` werden Modelle als linksbündige, gut antippbare Monospace-Karten dargestellt (statt zentrierter `TextButton`-Zeilen).
