# Besseresser-App – Bug- & Randfall-Analyse (BUG.md)

Diese Dokumentation fasst alle identifizierten Fehlerquellen, unberücksichtigten Randfälle und potenziellen Laufzeitrisiken in der aktuellen Codebasis zusammen. Die Befunde sind nach Schweregrad und Funktionsbereich gegliedert.

---

## 1. Übersicht & Risikomatrix

| ID | Bereich | Schweregrad | Kurzbeschreibung |
|---|---|---|---|
| **BUG-01** | UI / ViewModel | **Hoch** | `HistoryDetailViewModel` behält gelöschten Eintrag dauerhaft im UI-State |
| **BUG-02** | Fazit-Engine | **Hoch** | Mögliche Endlosschleife / API-Flooding bei fehlerhafter Berechnung veralteter Fazits |
| **BUG-03** | History / ViewModel | **Mittel** | `saveBackdatedEntry` setzt `backdated = false` (Nacherfassungs-Badge fehlt) |
| **BUG-04** | Main ViewModel | **Mittel** | Fehlerbehandlung in `MainViewModel` nutzt rohe Exception-Messages statt `ErrorMapper` |
| **BUG-05** | Domain / Caching | **Mittel** | `dayFingerprint` berücksichtigt nur `id` und `recognizedMeal` (Re-Evaluation-Inkonsistenz) |
| **BUG-06** | Backup / Import | **Mittel** | Kaskadierende Löschung von Fazits & geteilten Fotos bei Duplikat-Bereinigung |
| **BUG-07** | Data / Retention | **Niedrig** | `purgeOlderThanWeeks` hinterlässt verwaiste Tages- und Wochenfazits |
| **BUG-08** | File Sharing | **Niedrig** | Harte FileProvider-Authority vs. dynamische `applicationIdSuffix` |

---

## 2. Detaillierte Fehlerbeschreibungen

### BUG-01: `HistoryDetailViewModel` behält gelöschten Eintrag dauerhaft im State
- **Betroffene Datei:** `app/src/main/java/de/gurum/besseresser/ui/HistoryDetailViewModel.kt` (Zeile 53–60)
- **Problem:**
  Im Initializer wird der reaktive Room-Flow für die ID beobachtet:
  ```kotlin
  historyRepository.getByIdFlow(entryId).collect { entry ->
      _uiState.update { it.copy(
          entry = entry ?: it.entry,
          loaded = true
      ) }
  }
  ```
- **Randfall:**
  Wird ein Eintrag in der Datenbank gelöscht (z. B. durch parallele Retention-Bereinigung, Backup-Wiederherstellung oder „Verlauf leeren“), emittiert `getByIdFlow` den Wert `null`. Durch den Elvis-Operator `entry ?: it.entry` wird der State jedoch **nicht** auf `null` gesetzt, sondern der alte, gelöschte Eintrag bleibt im State eingefroren. Der Screen erkennt den gelöschten Zustand nicht und triggert nicht das automatische Schließen (`LaunchedEffect(Unit) { onBack() }`).
- **Lösung:**
  ```kotlin
  historyRepository.getByIdFlow(entryId).collect { entry ->
      _uiState.update { it.copy(
          entry = entry,
          loaded = true
      ) }
  }
  ```

---

### BUG-02: Mögliche Endlosschleife / API-Flooding bei veralteten Fazits
- **Betroffene Dateien:**
  - `app/src/main/java/de/gurum/besseresser/data/TriggerAutoFazitUseCase.kt` (Zeile 151–160)
  - `app/src/main/java/de/gurum/besseresser/ui/HistoryViewModel.kt` (Zeile 520–549, 613–635)
- **Problem:**
  In `TriggerAutoFazitUseCase.kt` wird bei `isFazitOutdated(fazit, ratedEntries)` die Generierung ausgelöst:
  ```kotlin
  } else if (isFazitOutdated(fazit, ratedEntries)) {
      onGenerateDayFazit(...)
  }
  ```
  Im Gegensatz zu neuen Fazits (`fazit == null`) wird für veraltete Fazits **nicht** geprüft oder vermerkt, ob der Versuch in `DayFazitSession.attempted` bereits registriert wurde.
- **Randfall:**
  Schlägt der API-Aufruf zur Fazit-Generierung fehl (z. B. bei Offline-Zustand, ungültigem API-Key oder Server-Fehler), entfernt `HistoryViewModel` den Tag aus `loadingFazits`. Da das Fazit in Room weiterhin veraltet bleibt und nicht in `DayFazitSession.attempted` geführt wird, stößt der nächste Flow-Trigger oder State-Update (`triggerAutoFazits()`) den Generierungsversuch nach dem Debounce-Delay sofort wieder an. Dies führt zu einer wiederholten Abfrageschleife im Hintergrund.
- **Lösung:**
  Auch für veraltete Fazits sollte eine Sitzungs-/Attempt-Sperre oder ein Backoff implementiert werden, damit nach einem Fehler nicht im Sekundentakt neue Anfragen abgesetzt werden.

---

### BUG-03: `saveBackdatedEntry` setzt `backdated = false`
- **Betroffene Datei:** `app/src/main/java/de/gurum/besseresser/ui/HistoryViewModel.kt` (Zeile 393–396)
- **Problem:**
  Beim manuellen Hinzufügen eines Eintrags über den Nacherfassungs-Dialog der Verlaufsansicht ruft `HistoryViewModel` folgendes auf:
  ```kotlin
  val entry = historyRepository.add(
      photoUri = photoUriIn, ev = ev,
      note = note, dateOverride = millis, backdated = false
  )
  ```
- **Randfall:**
  Obwohl die Methode explizit `saveBackdatedEntry` heißt und für manuelle Nacherfassungen zu einem frei gewählten Datum/Uhrzeit gedacht ist, wird `backdated` fest auf `false` gesetzt.
- **Auswirkung:**
  In der UI (`HistoryItem.kt`, `HistoryDetailScreen.kt`) und in den Prompts (`PromptRepository.kt`) wird der Eintrag als regulär zur Uhrzeit fotografiert dargestellt statt mit dem Badge/Kennzeichen „Nacherfasst“.
- **Lösung:**
  `backdated = true` übergeben (oder `dateOverride != null` prüfen).

---

### BUG-04: Inkonsistente Fehlerbehandlung in `MainViewModel`
- **Betroffene Datei:** `app/src/main/java/de/gurum/besseresser/ui/MainViewModel.kt` (Zeile 255, 277)
- **Problem:**
  Im Fehlerfall der Bild- oder KI-Analyse setzt `MainViewModel` die Fehlermeldung direkt als:
  ```kotlin
  error = e.message ?: "Analyse fehlgeschlagen."
  ```
- **Randfall:**
  Bei HTTP-Fehlern (z. B. 401 Unauthorized, 429 Rate Limit, SocketTimeout) werden dem Nutzer rohe technische Fehlermeldungen (wie `HTTP 401 null` oder englische Coroutine-Exceptions) angezeigt, während `HistoryDetailViewModel` und `HistoryViewModel` den zentralen `ErrorMapper.toUserMessage(e)` verwenden, der lokalisierte und verständliche Texte erzeugt.
- **Lösung:**
  `ErrorMapper.toUserMessage(e)` einheitlich auch in `MainViewModel` verwenden.

---

### BUG-05: Unvollständiger Fingerprint in `dayFingerprint`
- **Betroffene Datei:** `app/src/main/java/de/gurum/besseresser/model/HistoryEntryLogic.kt` (Zeile 109–110)
- **Problem:**
  Die Prüfsumme für Tages- und Wochenfazits wird wie folgt generiert:
  ```kotlin
  fun dayFingerprint(entries: List<HistoryEntry>): String =
      entries.filter { it.isRated }.sortedBy { it.id }.joinToString("|") { "${it.id}:${it.recognizedMeal}" }.hashCode().toString(16)
  ```
- **Randfall:**
  Wenn ein Eintrag erneut bewertet wird (`reEvaluate`) oder ein Ziel manuell korrigiert wird, ohne dass sich `recognizedMeal` oder die `id` ändert, bleibt der berechnete Fingerprint identisch.
- **Auswirkung:**
  `isFazitOutdated` erkennt nicht, dass sich die Zielbewertungen (Ampelfarben) oder Anmerkungen eines Eintrags geändert haben, wenn das Fazit nicht zuvor manuell gelöscht wurde.
- **Lösung:**
  Den Fingerprint um die Zielbewertungen erweitern, z. B.:
  `${it.id}:${it.recognizedMeal}:${it.goalResults.joinToString(",") { g -> "${g.goal}:${g.rating}" }}`.

---

### BUG-06: Duplikat-Bereinigung beim Backup-Import löscht Fazits & Shared Photos
- **Betroffene Datei:** `app/src/main/java/de/gurum/besseresser/data/BackupStore.kt` (Zeile 326–333)
- **Problem:**
  Beim Importieren eines Backups werden vorhandene Duplikate mit `historyRepository.delete(duplicate)` gelöscht:
  ```kotlin
  existingEntries.filter { existing ->
      existing.id != rawEntry.id &&
      existing.dateMillis == rawEntry.dateMillis &&
      existing.recognizedMeal == rawEntry.recognizedMeal &&
      existing.note == rawEntry.note
  }.forEach { duplicate ->
      historyRepository.delete(duplicate)
  }
  ```
- **Randfall:**
  `historyRepository.delete()` löscht automatisch das zugehörige `DayFazit` und `WeekFazit` des jeweiligen Tages sowie die physische Fotodatei. Wenn das importierte Backup nicht für jeden dieser Tage ein neues Fazit enthält oder wenn Duplikate denselben Dateinamen referenzieren, gehen zuvor gültige Fazits oder geteilte Bilddateien auf dem Speicher verloren.
- **Lösung:**
  Beim Import-Abgleich gezielter `historyDao.deleteById(duplicate.id)` aufrufen bzw. die Fazit- und Fotodatei-Löschung nur dann durchführen, wenn die Datei nicht mehr anderweitig referenziert wird.

---

### BUG-07: Verwaiste Fazits bei automatischer Verlaufs-Bereinigung (`purgeOlderThanWeeks`)
- **Betroffene Datei:** `app/src/main/java/de/gurum/besseresser/data/HistoryRepository.kt` (Zeile 216–224)
- **Problem:**
  `purgeOlderThanWeeks(weeks)` löscht Mahlzeit-Einträge älter als `X` Wochen aus der `history_entries`-Tabelle und entfernt deren Fotos.
- **Randfall:**
  Die Tabellen `day_fazits` und `week_fazits` werden dabei nicht bereinigt.
- **Auswirkung:**
  Über längere Nutzungszeiträume sammeln sich verwaiste Datensätze in den Fazit-Tabellen an, für die keine Mahlzeiten mehr existieren.
- **Lösung:**
  In `purgeOlderThanWeeks` auch `dayFazitDao.deleteOlderThan(cutoffMillis)` und `weekFazitDao.deleteOlderThan(cutoffWeekKey)` ausführen.

---

### BUG-08: FileProvider Authority vs. Build-Flavors / ApplicationIdSuffix
- **Betroffene Dateien:**
  - `app/src/main/AndroidManifest.xml` (Zeile 39)
  - `app/src/main/java/de/gurum/besseresser/data/PhotoCaptureManager.kt`
  - `app/src/main/java/de/gurum/besseresser/ui/HistoryDetailScreen.kt` (Zeile 180)
- **Problem:**
  Im Manifest ist die Authority fest als `"de.gurum.besseresser.fileprovider"` verdrahtet, in `HistoryDetailScreen.kt` wird jedoch `"${ctx.packageName}.fileprovider"` verwendet.
- **Randfall:**
  Sobald in Gradle ein `applicationIdSuffix` (z. B. `.debug` oder `.staging`) genutzt wird, weicht `ctx.packageName` (`de.gurum.besseresser.debug`) von der im Manifest deklarierten Authority ab, was beim Teilen eines Fotos zu einer `IllegalArgumentException` führt.
- **Lösung:**
  Im `AndroidManifest.xml` die Authority als `${applicationId}.fileprovider` deklarieren und in `PhotoCaptureManager` dynamisch über `context.packageName` auflösen.
