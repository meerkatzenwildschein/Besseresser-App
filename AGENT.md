# AGENT.md – Besseresser (App)

## Zweck

Android-App „**Besseresser**" (`de.gurum.besseresser`): nach einmaliger Einrichtung Essen
fotografieren und pro persönlichem Ziel eine Ampel-Bewertung (🟢🟡🔴) von einer KI bekommen.
Der Name ist sprachneutral (Markwort), `app_name` in beiden Locale-Dateien „Besseresser".

## Ablauf für den Nutzer

0. **Android-Zurück-Button (`BackHandler`):** System-Zurück navigiert wie die On-Screen-Pfeile –
   Detail → Verlauf (`historyEntry = null`), Verlauf → Main (`showHistory = false`), Profil → Main
   (`showProfile = false`), Einstellungs-
   Overlay über abgeschlossenem Setup → Main (`showSetup = false`), Kamera-Overlay → schließen,
   Foto-Review → verwerfen (Foto/Note/Fehler wie TopBar-Zurück). Root-Screen (Main ohne Overlay)
   und Erst-Setup lassen Zurück = App beenden (Android-konform, kein BackHandler). Ein „Zurück
   beendet die App"-Bug existierte, weil gar keine BackHandlers gesetzt waren.

0a. **Profil-Seite (`ProfileScreen.kt`, `ProfileStore` in Store.kt):** **Geburtsdatum** (Material-
   DatePickerDialog, gespeichert als ISO yyyy-MM-dd, Anzeige dd.MM.yyyy; Alter = lokal aus Datum
   berechnet via `Profile.ageYears` – veraltet nie), Gewicht (kg, Komma erlaubt), Körpergröße (cm),
   Freitext (Anmerkungen/Vorerkrankungen) — **alles optional**, speichern auch bei völlig leerer
   Seite (dann „nicht gesetzt"). Erreichbar über Burger-Menü (Reihenfolge: **Profil, Verlauf,
   Einstellungen** – Einstellungen bewusst als LETZTES; gleiches Sperr-Enabled wie Verlauf bei
   ungespeichertem Durchlauf, alle drei Menü-Wechsel werfen sonst den MainScreen-State). Nach dem
   ERSTEN Erfassen der KI-Zugangsdaten öffnet sich das Profil einmalig automatisch
   (`onDone` → `firstRun`-Flag → `showProfile`). Persistiert in denselben SharedPreferences
   (`birthDate` ISO-String / `profileWeight/-Height/-Notes`, null/-1 = nicht gesetzt), Range-Check
   (Gewicht 1–700 kg, Größe 30–280 cm) mit Fehler-Surface (`profile_invalid_number`).
   AppScreen-Enum: `Profile(1)` (Animations-Richtung).
   **Profil fließt in die Tagesfazit-Prompts ein** (beide Calls: Verlauf-Auto/Chip UND „Dein Tag
   bisher" nach dem Speichern): `profileContextLine(ctx, profile)` baut „Nutzerprofil: N Jahre ·
   X kg · Y cm · Anmerkungen: …" (nur gesetzte Werte bei leerem Profil = keine Zeile), übergeben
   als `promptContext` an `ApiClient.dayFazit` — der sendet jetzt auch die **Ziele nummeriert**
   mit in den Prompt (Bugfix: fehlten dort komplett, obwohl intro sie ankündigt).
   Hinweis auf der Profil-Seite: `profile_usage_hint` („Diese Angaben werden bei der
   Tagesauswertung an die KI gesendet"). **Nicht** im Essens-Auswertungsprompt (eval) –
   vorgesehener nächster Schritt.

1. **Erster Start (Setup):**
   - Ziele als Freitext, **ein Ziel pro Zeile** (z. B. „Gesünder essen", „Weniger Zucker", „Blutdruck")
   - **API-URL** einer OpenAI-kompatiblen API (z. B. `https://api.openai.com/v1`)
   - **API-Key**
   - **Modellname** (mit Bildverstehen, z. B. `gpt-4o-mini`)
   - Alles wird nur lokal in SharedPreferences (`besseresser_prefs`) gespeichert, kein Key fliegt in eine Cloud.
   - Setup zeigt beim Öffnen die alten Werte vorausgefüllt (auch Ziele – Bugfix: Ziele gehen beim Bearbeiten NICHT verloren).
   - **Anbieter-Auswahl:** Textlink „Anbieter wählen" unter dem URL-Feld → Dialog mit gängigen OpenAI-kompatiblen Anbietern und deren Basis-URLs (`PROVIDERS` in SetupScreen.kt: OpenAI, OpenRouter, Mammouth, Google Gemini, Mistral, Groq, DeepSeek, xAI, Together, Fireworks, Perplexity, Anthropic-Kompatibilitätsmodus). Auswahl füllt das URL-Feld.
   - **Speichern:** Top-Bar hat NUR Zurückpfeil + Titel (keine Diskette mehr); die einzige Speicher-Aktion ist der **fixierte „Speichern & weiter"-CTA unten** (Box wie Hauptbildschirm, 90dp-Endplatzhalter, ` imePadding` für Tastatur), trägt auch den Ladezustand „KI teilt Ziele auf …".
- **Standard-Ziele:** Leeres Ziele-Feld → `DEFAULT_GOALS` (SetupScreen.kt) wird vorbefüllt im Feld und beim Speichern benutzt: „Blutdruck senken / Weniger Zucker / Gewicht verlieren / Insgesamt gesünder ernähren" (eine Zeile = ein Ziel = eine Ampel). Wegen des Defaults ist das Ziele-Feld KEIN Pflichtfeld mehr.
- **KI-Aufteilung beim Speichern:** Freitext-Ziele werden vor dem Speichern via `{baseUrl}/chat/completions` (Text-only, `ApiClient.splitGoals`, Debug-Report bei Fehler) in einzelne prägnante Ziele zerlegt und im Dialog **editierbar** zur Kontrolle vorgelegt. Buttons: „So speichern" (aufgeteilt), „Original behalten" (ganzer Freitext = ein Ziel), Schließen/Abbrechen. KI-Fail → lokaler Satzzeichen-Fallback (`splitGoalsLocally`) + Debug-Report. Leeres Feld → DEFAULT_GOALS direkt, ohne KI-Call.
- **Persistiertes Split-Flag:** `Settings.goalsWereSplit` (SharedPreferences `goalsWereSplit`). Nach bestätigter Aufteilung/„Original behalten"/DEFAULT-Speichern wird es mit den Zielen gespeichert. Beim nächsten Speichern: Feld unverändert geblieben (`goalsDirty`-Flag im SetupScreen, jede Feld-Änderung setzt es) + `goalsWereSplit=true` → **kein KI-Call**, Direkt-Übernahme. Jede manuelle Änderung der Ziele → neuer KI-Aufteilungs-Call.
- **Historie** (neu, `HistoryScreen.kt`; **UI-Label deutsch: „Verlauf", englisch: „History"**): Burger-Menü → „Historie". Liste der gespeicherten Bewertungen (Thumbnail, Datum, ScoreBadge aus allen Ampeln: grün=100/gelb=50/rot=0 gemittelt, Farbe ≥80 grün / ≥50 gelb / sonst rot), Detail-Screen mit Foto + allen Einzel-Ampeln (nutzt `GoalRow`/`TopBarRow`). Persistiert in `filesDir/history/<id>.json` + **verkleinertes Foto** `<id>.jpg` (max. 1024px, JPEG-Qualität 75→40 solange > 100 KB – typisch 50–90 KB). Achtung-Pitfalls (schon passiert): `decodeStream(inJustDecodeBounds=true)` gibt immer null zurück – Bounds-Decodes nicht per elvis abbrechen; Karten im SwipeToDismissBox brauchen deckende Farbe UND geglätteten Background (Rundung identisch zur Karte), sonst schimmert der Swipe-Hintergrund; **`AnimatedContent` + ausgeblendete Screens:** Der outgoing-Content (z. B. Detail beim Tap-Back, `historyEntry=null`) ist während der Übergangsanimation NOCH komponiert und re-composed – im Branch nie direkt `nullableState!!` lesen (NPE-Crash beim Verlassen des Detail-Screens, Fix: ein mal `remember { historyEntry }` einfrieren). **Löschen:** nach links wischen (`SwipeToDismissBox`, `HistoryStore.delete` entfernt JSON + JPG). **Zurück:** nach rechts wischen auf dem Historie-Screen → Hauptbildschirm (Schwellwert ~160px). **Löschen im Detail:** Papierkorb-Icon (Icons.Filled.Delete) oben rechts im `HistoryDetailScreen`, mit Bestätigungs-Dialog, danach zurück zur Liste (die beim Re-Compose frisch lädt). **Kein Auto-Save:** Manueller Button „In Historie speichern" unter den Ergebnissen, daneben „Ohne Speichern verwerfen" (bundelt die Lösch-Intention, nur vor dem Speichern); nach dem Speichern nur noch Bestätigung + „Neues Foto aufnehmen" (der CTA steht FIXIERT unten, ausgefüllt wie der Start-CTA, 56dp/Daumen-Ereich; Klick resetet den Durchlauf und springt direkt in die Kamera statt zum Start-Screen; Rücklösung des versehentlich Gespeicherten geht über Historie → Wisch-Löschen). **Score-Badge:** Caption „Ampelschnitt" + Long-Press-Tooltip mit Formel (🟢100 / 🟡50 / 🔴0).
**Tages-Gruppierung + KI-Tagesfazit (Liste):** Einträge nach Kalendertag gruppiert (`groupByDay`/`DayGroupHeader`, `HistoryScreen.kt`).
Gruppenkopf = Datum (dd.MM.yyyy, lokal, links) + **KI-Tagesfazit-Chip** rechts (Zustandsmaschine): ① kein Fazit → grauer
Chip „✨ Tagesfazit ermitteln" (Tap = Generate); ② Generating → grauer Chip mit Spinner „Tagesfazit …" + Ersatztextzeile
„KI bewertet den Tag …"; ③ fertig → farbiger Chip (ampelColor der KI-Ampel), der Tap klappt das Fazit als Textblock
unter dem Kopf ein/aus (Chevron rotiert); ④ veraltet → Chip „⟳ Neu bewerten" + altes Fazit gedimmt (2 Zeilen Ellipsis).
**Veraltet** = Entry-IDs des Tages stimmen nicht mehr mit dem Snapshot `entryIds` im `DayFazit` überein,
ODER der `fingerprint` weicht ab (`dayFingerprint`, HistoryStore.kt: Hash aus ID+erkannt je Eintrag) —
so erkennt auch eine erkannt-Userkorrektur den Tag als dirty; Alte Fazit-Dateien ohne `fp`-Feld gelten
bewusst erst einmal als frisch (kein false-stale nach Update).
**erkannt-Korrektur (Pencil-Feature):** Am 🍽️-Text im `CollapsibleSummaryBlock` sitzt ein Stift-Button
(`Icons.Filled.Create`, optionaler Callback `onEditErkannt`) → `ErkanntEditDialog` (AlertDialog mit
Textfeld, Strings `erkannt_edit_*`). EIN Dialog für beide Orte: Auswertung (nur `evaluation.copy`
im Speicher; wenn bereits SAVED, zusätzlich `HistoryStore.updateErkannt(ctx, savedEntryId, text)` +
`fazitEpoch++` → „Dein Tag bisher" wird neu generiert) und Historie-Detail (lokal `current`-State,
persistiert via `updateErkannt`). Nach Detail-Korrektur lädt die Liste frisch → Fingerprint-Mismatch
→ veraltet bzw. Auto-Regeneration für die 2 neuesten Gruppen. Pencil nur bei nicht-leerem erkannt.
**Prompt (`ApiClient.dayFazit`, text-only, kein Foto nötig):** Zeilen „HH:mm · erkannt mit Menge · Anmerkung" des Tages
+ Ziele → JSON {ampel, text}; Bausteine `prompt_dayfazit_*` (beide strings.xml); Kernregel: kleine Genuss-Snacks werden
in Kontext gewichtet – ein Bonbon macht einen sonst vollwertigen Tag nicht rot. Rechnerischer „Tagesscore" (Kreis +
Caption + `score_tooltip_day`/`score_caption_day`) GANZ entfernt; Eintrags-„Ampelschnitt" (ScoreBadge) bleibt.
**Persistenz:** `filesDir/history/day_<yyyyMMdd>.json` (`DayFazitStore` in HistoryStore.kt, `dayKeyOf` = lokaler
Tageschlüssel); `HistoryStore.entries` filtert `day_*`-Dateien; Eintrag-Löschen löscht das Tagesfazit gleich mit
(`HistoryStore.delete` → `DayFazitStore.deleteForEntry`). **Auto-Logik (NUR die beiden neuesten Tagesgruppen):**
kein Fazit → 1× pro Tag & App-Session automatisch (Guard `DayFazitSession.attempted`); veraltet → erneut automatisch;
ältere Gruppen nur per Chip-Tap. Debounce 1,2 s nach entries-Änderung (Mehrfach-Löschen = 1 Call).
**Hauptbildschirm:** nach dem Speichern erscheint unter dem Banner „Dein Tag bisher" (Spinner/ampel-Dot + Fazittext);
dieser Call persistiert das Fazit für heute → Verlauf sieht es als frisch. Im Eintrag steht nur noch die Uhrzeit
(HH:mm) – das Datum gehört in die Gruppenkopfzeile. Implementierung: `LazyColumn` mit `item(key="day-<dayKey>")`
zwischen je-Entry-`item(key=id)`; java.time (minSdk 26), Systemzeitzone.
2. **Hauptbildschirm:** **Fixierter Foto-CTA unten** (Box mit scrollendem Inhalt darüber, 100dp-Endplatzhalter) – „Essen fotografieren"/„Essen bewerten"/„KI bewertet …" je nach Zustand, immer im Daumen-Ereich. **Ausgeblendet, solange ein Bericht mit Ergebnissen angezeigt wird** (`reportShown`), dann sind die Inline-Aktionen (Speichern/Verwerfen bzw. „Neues Foto aufnehmen") die einzigen nächsten Schritte, Endplatzhalter nur 24dp; **während „KI bewertet …" bleibt der CTA sichtbar und zeigt Material-Muster „indeterminierter Spinner im Button"** (CircularProgressIndicator 20dp in `onPrimary` + Label, disabled) UND **über dem Normalzustand erscheint zentriert eine „KI denkt"-Karte** (Surface, 52dp-Spinner + `main_ai_working` + Subzeile `main_ai_working_sub` „Das kann einige Sekunden dauern.") – nur wenn `busy && evaluation == null` (frischer Durchlauf; Play-Protect-Optik). System-Kamera via `TakePicture` + `FileProvider`.
   **Eigene Kamera (CameraX, `CameraScreen.kt`):** CTA öffnet `CameraCaptureScreen` statt der Systemkamera — Live-Preview + Verschluss, dann **Foto + Anmerkungsfeld + „Essen bewerten"/„Foto neu aufnehmen" in einem Schritt**; beim Wiederholen bleibt der getippte Text erhalten. OK → zurück ins Hauptbild, laufende Bewertung („KI bewerted …"-Zustand), Ergebnis wie gehabt. Deps: `androidx.camera:core/camera2/lifecycle/view:1.3.4`; Manifest `CAMERA`-Permission (runtime). **Fallback:** ohne Berechtigung bzw. schierer Preview-Fehler: Dialog mit „Berechtigung erteilen" und „Systemkamera benutzen" (der Startet den alten `TakePicture`-Weg, der den beibehaltenen Review-Zweig `showReview` nutzt). Nach der Aufnahme WITHOUT "Foto neu aufnehmen"-Button — stattdessen Zurückpfeil oben links (weiß, verlässt die Kamera ohne Bewerten, Notiz bleibt erhalten). **Orientierungs-Fix:** `imageCapture.targetRotation` wird vor jeder Aufnahme aus `previewView.display.rotation` gesetzt UND die gespeicherte Datei nach dem Speichern EXIF-normalisiert (`correctOrientation`: rotate + EXIF auf NORMAL zurücksetzen) — sonst 90°-gedrehte Fotos (BitmapFactory ignoriert EXIF). **Review-Layout:** Foto-Prüf-Screen = Kopzeile (Pfeil links, NUR auf schwarz, nicht überm Foto), Foto-Box `weight(1f)` mit `ContentScale.Fit` (weicht bei Tastatur via `imePadding` an der Box), darunter Anmerkung + „Essen bewerten". Damit weiter existierende `showReview`-Branch + Systemkamera-Flow als Rückfallpfad.
   Oben rechts **Burger-Menü** (3-Linien-Icon `Icons.Filled.Menu`, DropdownMenu): „Profil"
   (leadingIcon `Person`), „Verlauf" (leadingIcon `DateRange`), „Einstellungen" (leadingIcon
   `Settings`) – Icons mit Standard-Tint (`LocalContentColor`), hell/dark-adaptiv ohne hardcoded Farbe. **Nach links wischen** auf dem Hauptbildschirm öffnet ebenfalls den Verlauf (`detectHorizontalDragGestures`, Schwellwert ~160px, mischt sich nicht mit vertikalem Scroll). **Wisch-/Menü-Sperre (Wichtig):** Solange der Durchlauf ungespeichert ist (Bericht offen `lastGoaled && !savedToHistory`, KI läuft `busy`, Foto-Review `showReview`, Kamera `showCamera`), ist der Verlauf-Wechsel gesperrt – Wisch-Geste zeigt einmalig Toast `nav_blocked_hint` („Bitte speichere oder verwerfe die Bewertung erst."), „Verlauf" im Burger-Menü via `enabled` deaktiviert; Grund: der AnimatedContent-Screen-Wechsel verwirft den ganzen MainScreen-State (Foto/Note/Auswertung). Einstellungen öffnet den Screen-Wechsel gleichfalls – bleibt bewusst offen (kein Abbruch-Dialog), Nutzer entscheidet. **Screen-Übergänge animiert:** Main/Historie/Detail laufen über `AnimatedContent` (MainActivity.kt, `AppScreen.order`-Enum steuert die Richtung): vorwärts = neuer Screen slidet von rechts (alter slided 1/3 nach links + Fade), rückwärts gespiegelt.
   Das Foto → verkleinert (max. 1280 px, JPEG q80, Base64) → an `{baseUrl}/chat/completions`
   mit Prompt, der die Ziele enthält und strikt JSON verlangt.
3. **Ergebnis:** Pro Ziel eine Ampel-Zeile (grün/gelb/rot) + kurze Begründung, plus ein
   Gesamteindruck-Satz. „Nächste Mahlzeit fotografieren" setzt Evaluierung + Foto zurück.
   **Frischer Durchlauf über den Foto-CTA:** Der fixierte Foto-CTA im Normalzustand verwirft beim
   Öffnen der Kamera ALLES vom vorigen Durchlauf (Evaluierung, Foto, Anmerkung, Historie-Status,
   Fehler) — Kamera-Note startet leer und die Ampeln stehen auf grau, wie beim Erststart, auch
   wenn der vorige Bericht noch nicht gespeichert/weggeklickt war (Fix: alt-Note + alt-Ampel
   schleppten sich sonst in den nächsten Durchlauf). „Foto neu aufnehmen" innerhalb derselben
   Aufnahme behält weiterhin den getippten Text.

## KI-Antwortformat (Prompt-Kontrakt)

```json
{
  "bewertung": [
    { "ziel": "<exaktes Ziel>", "ampel": "gruen|gelb|rot", "begruendung": "..." }
  ],
  "zusatz": "<Gesamteindruck-Satz>",
  "erkannt": "<kurzer Satz: was die KI auf dem Foto (inkl. Nutzer-Anmerkung) erkennt, MIT geschätzter Menge wie '100 g Nudeln, ein Stück Apfel'>"
}
```

Parsen ist tolerant (`results`/`summary`/`reason` als Alias, Code-Fences werden gestrippt,
Ziel-Zuordnung ignoriert Groß-/Kleinschreibung und Leerzeichen; normalisiert `green/yellow/red`
ebenso). **`erkannt`:** Alias `detected`/`food`/`meal`, leer erlaubt; Prompt verbietet Vorspann-
Floskeln („Zu sehen sind"), Parser stript sie zusätzlich weg (`stripErkanntIntro` in `ApiClient.kt`,
de/en-Muster, kapitalisiert, nie leeren). Zeile 🍽️ (`detected_prefix`) im
Zusammenfassungs-Block der Auswertung (MainActivity) und Detail-Historie; in der Listenansicht
steht sie direkt unter der Uhrzeit (max. 2 Zeilen, Ellipsis). **Zusammenfassungs-Block einklappbar**
(„CollapsibleSummaryBlock", MainActivity.kt, identisch in Auswertung und Historie-Detail):
🧠 immer sichtbar; 🍽️ Erkannt + 📝 Anmerkung eingeklappt, die ganze Karte antippbar
(`animateContentSize`, rotierender Chevron `KeyboardArrowDown`), Caption „N weitere Details"
(Plurals `details_toggle_more`) / „Details einklappen". Listenansicht ohne Toggle.
Persistiert im History-JSON unter `"erkannt"`. Prompt-Baustein `prompt_eval_recognized`
(beide `strings.xml`), Feld im JSON-Format-Beispiel in `ApiClient.analyze`. Fehlende Ziele →
Ampel „unbekannt" (grau).

## Technik-Stack

| Komponente | Wert |
|---|---|
| Sprache/UI | Kotlin 2.0.20, Jetpack Compose (BOM 2024.09.02), Material 3 |
| Android Gradle Plugin | 8.5.2 (Gradle 8.9) |
| compileSdk/targetSdk | 34, minSdk 26 |
| package/namespace | `de.gurum.besseresser` |
| Themes | `isSystemInDarkTheme()` → dunkel/hell entsprechend Systemeinstellung; `values-night/themes.xml` für Statusleisten-Icons |
| Persistenz | SharedPreferences, nur lokal (`Store`-Objekt im Quellcode); **API-Key verschlüsselt** via `KeyVault` (AES-256-GCM, Schlüssel im Android-Keystore; in Preferences liegt unter `apiKey` nur das Chiffrat; Entschlüsselung schließt fehl → Setup leer anzeigen; einmal eingegebenen alten Klartextkey gibt man einfach neu ein) |
| Kamera | `ActivityResultContracts.TakePicture`, Foto landet in `cacheDir/photos/` über `FileProvider` authority `de.gurum.besseresser.fileprovider` (paths: `cache-path photos/`) |
| Netz | Kein Zusatzlibrary – `HttpURLConnection` (OkHttp wäre ok, war nicht nötig). **Gemeinsame Call-Bausteine in ApiClient:** `postChat(url, key, body)` (Post+Status, wirft `ChatHttpException` mit Code+Roh-Body), `chatBody(model, baseUrl, temp, tokens, messages)` (Request-Kern mit Reasoning-Exclude für OpenRouter/DeepSeek), `tolerantJson(content)` (Fence-Strip+{…}+closeJson); analyze/splitGoals/dayFazit nutzen alle identisch diese drei Bausteine – keine HTTP-Verhandlungs-Duplikate mehr |
| Abhängigkeiten | Compose BOM, activity-compose, core-ktx, lifecycle, material3, material-icons-core (ArrowBack-Icon) – Absichtlich KEIN material-icons-extended (zu groß) und kein OkHttp |
| Modell-Auswahl | Setup-Textlink „Modelle laden & auswählen" → eigener Screen `ModelListScreen` (SetupScreen.kt): lädt `{baseUrl}/models` (GET, Bearer-Key), zeigt **vision-gefilterte Liste** (bevorzugt exakt per `architecture.input_modalities` enthält "image", z. B. OpenRouter; sonst Namensheuristik `VISION_PATTERNS`; wenn beides nicht greift: ungefiltert). Bei Fehlern: **Debug-Report** (Anfrage-URL, HTTP-Status, Meldung, Rohantwort-Auszug, Monospace, kopierbar) → `ApiClient.fetchModels` (`onError`-Parameter ist der Report) → `ModelsResult` |
| Build-Zeitstempel | `BuildConfig.BUILD_TIME` wird bei jedem Build in `app/build.gradle.kts` (val `buildTime`) gesetzt; daneben `BuildConfig.APP_VERSION` (val `appVersion`, derzeit 1.1.0 = `versionName`), `versionCode` je Release hochsetzen. Beide erscheinen unten im Einstellungs-Dialog als „Version 1.0.0 · Build …" (`build_label`, zwei Platzhalter) |
| Lokalisierung | Android-Standard: `values/strings.xml` (Englisch, Default) + `values-de/strings.xml` (Deutsch); Systemeinstellung entscheidet. Alle UI-Texte via `stringResource`, Standard-Ziele als `default_goals`-String-Array (lokalisiert), Anbieter-Hinweise als Ressourcen-IDs. Neue Texte: in BEIDEN Dateien pflegen. **Per-App-Sprache** (Android 13+): `res/xml/locales_config.xml` (de/en) + `android:localeConfig` im Manifest → „App-Sprache“ in den Systemeinstellungen funktioniert; unter Android <13 greift die System-Sprache. In-App-Sprachwahl via `AppCompatDelegate` wäre möglich, aber noch nicht eingebaut. **KI-Prompts lokalisiert:** Anweisungstexte in beiden `strings.xml` (`prompt_eval_*`, `prompt_split_*`); `ApiClient.analyze`/`splitGoals` bekommen sie als `PromptBundle`/Parameter von den Composable-Aufrufern (ctx.getString). JSON-Schlüssel (bewertung/ziel/ampel/begruendung/zusatz/ziele) bleiben konstant, weil der Parser sie kennt – nur die Anweisungssprache + explizite „Antworte in deutscher Sprache./Answer in English.“-Zeile variiert. Antwort- und Zielsprache folgen damit der App-Sprache; bereits gespeicherte Ziele bleiben in der Sprache, in der sie eingegeben wurden. |

## Projektstruktur

```
Gesund_Essen_App/
├── AGENT.md                      ← diese Datei├── settings.gradle.kts           rootProject "GesundEssen"
├── build.gradle.kts              Plugin-Versionen
├── gradle.properties, local.properties (sdk.dir → C:\Users\gurum\Android\Sdk)
├── app/
│   ├── build.gradle.kts          Compose-Flag, Abhängigkeiten (Compose BOM, activity-compose, material3)
│   └── src/main/
│       ├── AndroidManifest.xml   INTERNET-Permission, MainActivity (launcher), FileProvider
│       ├── java/de/gurum/gesundessen/  ← 7 Kotlin-Dateien, EIN Package (post-Refaktor):
│       │     Data.kt           Datenmodelle (Settings, GoalResult, Evaluation) + ampelColor/ampelLabel
│       │     KeyVault.kt       API-Key-Verschlüsselung (Android-Keystore)
│       │     Store.kt          SharedPreferences für Einstellungen
│       │     ApiClient.kt      OpenAI-kompatible API: analyze, splitGoals(+lokal), fetchModels, tolerantes JSON-Parsing (auch `content` als Parts-Array/`reasoning_content`, Fehler mit Rohantwort-Auszug, `closeJson`-Reparatur für abgeschnittene Antworten, max_tokens 4096) — **Reasoning-Modelle:** bei OpenRouter-DeepSeek wird `reasoning:{exclude:true}` mitgeschickt und Budget erhöht (DeepSeek v4.1 kann inzwischen Bild-Input!)
│       │     HistoryStore.kt   HistoryEntry, HistoryStore (JSON+JPG in filesDir/history), decodeThumb
│       │     MainActivity.kt   Activity, Theme, GesundEssenApp-Navigation (AppScreen-Enum, AnimatedContent, BackHandler-Setup)
│       │     MainScreen.kt     Der komplette Hauptbildschirm: Kamera-Overlay/Review-Zweig, Auswertung, CTA-Zustandsmaschinen, Tagesfazit „Dein Tag bisher"
│       │     CommonUi.kt       Geteilte Bausteine: TopBarRow, GoalRow, CollapsibleSummaryBlock, ErkanntEditDialog, ErrorBanner
│       │     SetupScreen.kt    Setup, LabelledField, ModelListScreen (TopBarRow lebt in CommonUi)
│       │     HistoryScreen.kt  HistoryScreen (Liste, Wisch-Löschen/Back), ScoreBadge, HistoryDetailScreen
│       └── res/
│           ├── values/          strings.xml, themes.xml, colors.xml
│           ├── values-night/    themes.xml (dunkles Statusbar-Icons)
│           ├── xml/file_paths.xml
│           └── mipmap-anydpi-v26 + drawable/ic_launcher_foreground.xml (Adaptive-Icon)
   Gradle-Wrapper wurde erzeugt (gradlew / gradlew.bat, Gradle 8.9).
```

## Build-Umgebung auf diesem Rechner (Windows)

- **JDK:** Temurin **21** (im PATH)
- **Android SDK:** `C:\Users\gurum\Android\Sdk` (platform-tools, platforms;android-34, build-tools;34.0.0)
- **Gradle:** `C:\Users\gurum\tools\gradle-8.9\bin\gradle.bat` (nicht global installiert)
- Kommandozeilen-Tools: `C:\Users\gurum\tools\android-sdk\`

### Befehle

```powershell
# Env nötig, weil der SDK-Pfad nicht global gesetzt ist:
$env:ANDROID_HOME = "C:\Users\gurum\Android\Sdk"

# Debug-APK bauen
C:\Users\gurum\tools\gradle-8.9\bin\gradle.bat assembleDebug --no-daemon

# oder künftig mit Wrapper:
.\gradlew.bat assembleDebug
```

## Ausgabe

- **APK:** `app\build\outputs\apk\debug\app-debug.apk` (debug-signed, direkt installierbar)
- Release-Build ist definiert (kein Minify), aber **noch nicht signiert** (kein Keystore eingerichtet).

## Bekannte Besonderheiten / Designentscheidungen

- Kein git repo (Projekt-Ordner ist nicht initialisiert) – `.gitignore` liegt trotzdem bereit.
- Tagging `android:configChanges` verhindert Neustart bei Rotation; Einstellungs-State liegt im
  Composable-Zustand, nicht ViewModel (bewusst klein gehalten).
- „Nächste Mahlzeit"-Button + „KI bewertet…"-Progress im Hauptbildschirm.
- App folgt dem System-Theme (hell/dunkel) bereits beim Start; Live-Wechsel ohne Neustart wäre via
  `AppCompatDelegate` o. ä. machbar, war nicht Ziel.
- Der Statusbar-Text/Icons: hell in `values-night`, dunkel sonst.
