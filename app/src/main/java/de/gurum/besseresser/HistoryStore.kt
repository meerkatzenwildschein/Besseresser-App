package de.gurum.besseresser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Eine gespeicherte Foto-Bewertung. */
@Serializable
data class HistoryEntry(
    val id: Long = 0L,
    @SerialName("datum")
    val dateMillis: Long = 0L,
    @SerialName("foto")
    val photoFile: String = "",          // Dateiname in filesDir/history ("" = kein Foto)
    @SerialName("ziele")
    val goalResults: List<GoalResult> = emptyList(),
    @SerialName("zusatz")
    val summary: String = "",
    @SerialName("anmerkung")
    val note: String = "",          // Nutzer-Anmerkung zum Foto (WhatsApp-Stil)
    @SerialName("erkannt")
    val erkannt: String = "",       // Was die KI auf dem Foto erkannt zu haben meint
    @SerialName("nacherfasst")
    val backdated: Boolean = false, // nachträglich erfasst (keine echte Uhrzeit bekannt)
    @SerialName("sortOrder")
    val sortOrder: Long = dateMillis // Sortierposition (getrennt von dateMillis, damit die echte Uhrzeit beim Verschieben erhalten bleibt)
) {
    /** Effektiver Sortierschlüssel (Fallback auf dateMillis für ältere Backups/Einträge mit sortOrder == 0). */
    val effectiveSortOrder: Long
        get() = if (sortOrder != 0L) sortOrder else dateMillis
    /** Ampel-Score 0..100 (gruen=100, gelb=50, rot=0, grau ausgeschlossen); -1 = nicht bewertbar. */
    val score: Int
        get() {
            val known = goalResults.filter { it.ampel != TrafficLight.UNKNOWN }
            if (known.isEmpty()) return -1
            val avg = known.sumOf {
                when (it.ampel) {
                    TrafficLight.GREEN -> 100.0
                    TrafficLight.YELLOW -> 50.0
                    else -> 0.0
                }
            } / known.size
            return avg.toInt()
        }
}

/** Persistenz der Historie: Room SQLite-Datenbank + <id>.jpg in filesDir/history. */
object HistoryStore {
    internal fun dir(ctx: Context) =
        File(ctx.filesDir, "history").apply { mkdirs() }

    private var migrationChecked = false

    fun ensureMigrated(ctx: Context) {
        if (migrationChecked) return
        migrationChecked = true
        val d = dir(ctx)
        val db = AppDatabase.getInstance(ctx)
        val jsonFiles = d.listFiles { f ->
            f.extension == "json" && !f.name.startsWith("day_") && !f.name.startsWith("week_")
        } ?: emptyArray()

        if (jsonFiles.isNotEmpty()) {
            kotlinx.coroutines.runBlocking {
                jsonFiles.forEach { f ->
                    runCatching {
                        val entry = AppJson.decodeFromString<HistoryEntry>(f.readText())
                        val e = if (entry.id == 0L) entry.copy(id = f.nameWithoutExtension.toLongOrNull() ?: 0L) else entry
                        db.historyDao().insert(e.toEntity())
                        f.delete()
                    }
                }
            }
        }
    }

    /** Vollständiger Pfad zum gespeicherten Foto (oder null). */
    fun photoFileOf(ctx: Context, entry: HistoryEntry): File? =
        entry.photoFile.takeIf { it.isNotBlank() }
            ?.let { File(dir(ctx), it) }
            ?.takeIf { it.exists() }

    /** Speichert Bewertung + verkleinertes Foto (typisch < 100 KB). Foto-Verarbeitung darf scheitern (dann ohne Thumbnail).
     *  photoUri = null → Eintrag OHNE Foto (Nacherfassung „ohne Foto“).
     *  dateOverride + backdated: nachträgliche Erfassung für ein bestimmtes Tagesdatum. */
    fun add(
        ctx: Context,
        photoUri: Uri?,
        ev: Evaluation,
        note: String = "",
        dateOverride: Long? = null,
        backdated: Boolean = false
    ): HistoryEntry {
        ensureMigrated(ctx)
        val id = System.currentTimeMillis()
        val date = dateOverride ?: id
        var photoFile = ""
        if (photoUri != null) {
            try {
                val f = File(dir(ctx), "$id.jpg")
                saveScaledJpeg(ctx, photoUri, f)
                photoFile = if (f.exists() && f.length() > 0) f.name else ""
            } catch (e: Exception) {
                photoFile = ""
            }
        }
        val entry = HistoryEntry(
            id = id, dateMillis = date, photoFile = photoFile,
            goalResults = ev.goalResults, summary = ev.summary,
            note = note, erkannt = ev.erkannt, backdated = backdated,
            sortOrder = date
        )
        kotlinx.coroutines.runBlocking {
            val dao = AppDatabase.getInstance(ctx).historyDao()
            val targetDay = Instant.ofEpochMilli(date).atZone(ZoneId.systemDefault()).toLocalDate()
            val existingSameDay = dao.getAll().map { it.toDomain() }.filter {
                Instant.ofEpochMilli(it.dateMillis).atZone(ZoneId.systemDefault()).toLocalDate() == targetDay
            }
            val synced = computeChronologicalSortOrders(existingSameDay, entry)
            dao.insertAll(synced.map { it.toEntity() })
        }
        return entry
    }

    fun entries(ctx: Context): List<HistoryEntry> {
        ensureMigrated(ctx)
        return kotlinx.coroutines.runBlocking {
            AppDatabase.getInstance(ctx).historyDao().getAll().map { it.toDomain() }
        }
    }

    /** Eintrag inkl. Foto loeschen. Löscht Tages-/Wochenfazit des Tages gleich mit. */
    fun delete(ctx: Context, entry: HistoryEntry) {
        val d = dir(ctx)
        File(d, "${entry.id}.json").delete()
        File(d, "${entry.id}.jpg").delete()
        kotlinx.coroutines.runBlocking {
            AppDatabase.getInstance(ctx).historyDao().deleteById(entry.id)
        }
        DayFazitStore.deleteForEntry(ctx, entry)
        WeekFazitStore.deleteForEntry(ctx, entry)
    }

    /** erkannt-Text eines gespeicherten Eintrags userkorrigieren (in Room DB aktualisieren).
     *  Das Tagesfazit bleibt in der DB, wird aber über den Fingerprint als veraltet erkannt. */
    fun updateErkannt(ctx: Context, id: Long, newText: String) {
        kotlinx.coroutines.runBlocking {
            AppDatabase.getInstance(ctx).historyDao().updateErkannt(id, newText.trim())
        }
    }

    /** Ziele + Gesamteindruck eines Eintrags neu schreiben (KI-Re-Bewertung nach Korrektur). */
    fun updateEvaluation(ctx: Context, id: Long, goalResults: List<GoalResult>, summary: String) {
        kotlinx.coroutines.runBlocking {
            AppDatabase.getInstance(ctx).historyDao().updateEvaluation(id, goalResults, summary)
        }
    }

    /** Erzeugt ein Duplikat-Domänenobjekt für das gewählte Zieldatum und die gewählte Uhrzeit. */
    internal fun createDuplicatedEntry(
        source: HistoryEntry,
        targetDay: LocalDate,
        targetTime: java.time.LocalTime = java.time.LocalTime.now(),
        newId: Long = System.currentTimeMillis(),
        newPhotoFile: String = ""
    ): HistoryEntry {
        val targetMillis = targetDay
            .atTime(targetTime.hour, targetTime.minute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return source.copy(
            id = newId,
            dateMillis = targetMillis,
            photoFile = newPhotoFile,
            backdated = false,
            sortOrder = targetMillis
        )
    }

    /** Berechnet beim Einfügen eines neuen Eintrags (mit konkreter Uhrzeit) in einen Tag konsistente
     *  `sortOrder`-Werte für alle Einträge dieses Tages:
     *  Der neue Eintrag wird anhand seiner echten Uhrzeit (`dateMillis`) an der chronologisch richtigen
     *  Stelle zwischen den bestehenden Einträgen des Tages eingereiht, auch wenn bestehende Einträge
     *  zuvor manuell verschoben wurden oder als altes `backdated` (23:59) gespeichert waren. */
    internal fun computeChronologicalSortOrders(
        existingDayEntries: List<HistoryEntry>,
        newEntry: HistoryEntry
    ): List<HistoryEntry> {
        if (existingDayEntries.isEmpty()) return listOf(newEntry)
        // Bestehende Einträge in ihrer aktuellen Anzeigereihenfolge (oben = Index 0 = späteste Position)
        val ordered = existingDayEntries.sortedByDescending { it.effectiveSortOrder }.toMutableList()

        // Suche die chronologisch passende Einfügeposition anhand der echten Uhrzeiten (!backdated):
        // - Vor dem ersten Eintrag mit echter Uhrzeit <= newEntry.dateMillis
        // - Falls es Einträge mit echter Uhrzeit > newEntry.dateMillis gibt, direkt hinter dem letzten davon
        // - Falls der Tag bisher nur alte backdated-Einträge (ohne Uhrzeit) hat, kommt der neue Eintrag mit Uhrzeit nach oben (Index 0)
        val firstEarlierTimedIdx = ordered.indexOfFirst { !it.backdated && it.dateMillis <= newEntry.dateMillis }
        val lastLaterTimedIdx = ordered.indexOfLast { !it.backdated && it.dateMillis > newEntry.dateMillis }
        val insertIdx = when {
            firstEarlierTimedIdx != -1 -> firstEarlierTimedIdx
            lastLaterTimedIdx != -1 -> lastLaterTimedIdx + 1
            else -> 0
        }
        ordered.add(insertIdx, newEntry)

        // Allen Einträgen streng absteigende sortOrder-Werte zuweisen (Basis: absteigend sortierte Zeitstempel)
        val baseSortOrders = ordered.map { maxOf(it.effectiveSortOrder, it.dateMillis) }
            .sortedDescending()
            .toMutableList()
        for (i in 1 until baseSortOrders.size) {
            if (baseSortOrders[i] >= baseSortOrders[i - 1]) {
                baseSortOrders[i] = baseSortOrders[i - 1] - 1000L
            }
        }
        return ordered.mapIndexed { idx, e -> e.copy(sortOrder = baseSortOrders[idx]) }
    }

    /** Dupliziert einen bestehenden Eintrag komplett (inkl. Foto, Ziele, Texte, Notiz) ohne erneute KI-Bewertung
     *  auf das gewählte Zieldatum und die gewählte Uhrzeit.
     *  Ein bestehendes Tagesfazit des Zieltags bleibt in der DB, damit es über den Entry-ID-/Fingerprint-Mismatch
     *  genau wie bei einer Eintrags-Bearbeitung als geändert/veraltet („⟳ Neu bewerten“) getrackt wird. */
    fun duplicateToDate(
        ctx: Context,
        source: HistoryEntry,
        targetDay: LocalDate,
        targetTime: java.time.LocalTime = java.time.LocalTime.now()
    ): HistoryEntry {
        ensureMigrated(ctx)
        val id = System.currentTimeMillis()
        var copiedPhotoName = ""
        val existingPhoto = photoFileOf(ctx, source)
        if (existingPhoto != null) {
            try {
                val targetFile = File(dir(ctx), "$id.jpg")
                existingPhoto.copyTo(targetFile, overwrite = true)
                if (targetFile.exists() && targetFile.length() > 0) {
                    copiedPhotoName = targetFile.name
                }
            } catch (e: Exception) {
                copiedPhotoName = ""
            }
        }
        val duplicated = createDuplicatedEntry(
            source = source,
            targetDay = targetDay,
            targetTime = targetTime,
            newId = id,
            newPhotoFile = copiedPhotoName
        )
        var finalDuplicated = duplicated
        kotlinx.coroutines.runBlocking {
            val dao = AppDatabase.getInstance(ctx).historyDao()
            val existingSameDay = dao.getAll().map { it.toDomain() }.filter {
                Instant.ofEpochMilli(it.dateMillis).atZone(ZoneId.systemDefault()).toLocalDate() == targetDay
            }
            val synced = computeChronologicalSortOrders(existingSameDay, duplicated)
            finalDuplicated = synced.first { it.id == duplicated.id }
            dao.insertAll(synced.map { it.toEntity() })
        }
        // Wochenfazit der Zielwoche invalidieren; Tagesfazit bleibt stehen und wird durch die neue
        // Entry-ID automatisch als veraltet (stale = true / „⟳ Neu bewerten“) markiert.
        WeekFazitStore.deleteForEntry(ctx, finalDuplicated)
        return finalDuplicated
    }

    /** Aktualisiert die Zeitstempel der übergebenen Einträge (für Umsortierung innerhalb eines Tages).
     *  Bestehende Tages- und Wochenfazits bleiben unverändert gültig (keine Neuberechnung nötig). */
    fun reorderEntries(ctx: Context, reordered: List<HistoryEntry>) {
        if (reordered.isEmpty()) return
        ensureMigrated(ctx)
        val entities = reordered.map { it.toEntity() }
        kotlinx.coroutines.runBlocking {
            AppDatabase.getInstance(ctx).historyDao().insertAll(entities)
        }
    }

    /** Speichert/überschreibt mehrere Verlaufseinträge (z. B. beim Backup-Import). */
    fun saveAll(ctx: Context, entries: List<HistoryEntry>) {
        if (entries.isEmpty()) return
        ensureMigrated(ctx)
        val entities = entries.map { it.toEntity() }
        kotlinx.coroutines.runBlocking {
            AppDatabase.getInstance(ctx).historyDao().insertAll(entities)
        }
    }

    /** Löscht alle Verlaufsdaten (Einträge, Fotos, Tages- und Wochenfazits) komplett. */
    fun clearAll(ctx: Context) {
        ensureMigrated(ctx)
        DayFazitStore.ensureMigrated(ctx)
        WeekFazitStore.ensureMigrated(ctx)
        val d = dir(ctx)
        d.listFiles()?.forEach { runCatching { it.delete() } }
        val db = AppDatabase.getInstance(ctx)
        kotlinx.coroutines.runBlocking {
            db.historyDao().deleteAll()
            db.dayFazitDao().deleteAll()
            db.weekFazitDao().deleteAll()
        }
        DayFazitSession.attempted.clear()
        DayFazitSession.attemptedWeekly.clear()
    }

    /** Foto downgesampelt (<1024px) speichern, JPEG-Qualitaet Schritt fuer Schritt bis < 100 KB. */
    private fun saveScaledJpeg(ctx: Context, uri: Uri, out: File) {
        val res = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        res.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        val bmp = res.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return
        var quality = 75
        while (quality >= 40) {
            out.outputStream().use { fos -> bmp.compress(Bitmap.CompressFormat.JPEG, quality, fos) }
            if (out.length() <= 100_000) break
            quality -= 15
        }
        bmp.recycle()
    }
}

/** Kleines Thumbnail decodieren (inSampleSize, damit die Liste flink bleibt). */
fun decodeThumb(file: File, target: Int = 200): Bitmap? {
    return try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > target) sample *= 2
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    } catch (e: Exception) {
        null
    }
}

// ----------------------------------------------------------------- Tagesfazit

/** Lokaler Tagesschlüssel (yyyyyyyyMMdd, Systemzeitzone) – Basis für Tagesgruppen und Fazit-Dateien. */
private val DAY_KEY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")

fun dayKeyOf(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DAY_KEY_FORMAT)

fun dayKeyOf(date: LocalDate): String = date.format(DAY_KEY_FORMAT)

/** Wochen-Key = ISO-Montag der Woche, in der das Datum liegt (Format yyyyMMdd). */
fun weekKeyOf(millis: Long): String {
    val day = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    return weekKeyOf(day)
}

fun weekKeyOf(day: LocalDate): String {
    val monday = day.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
    return monday.format(DAY_KEY_FORMAT)
}

fun weekMondayOfKey(weekKey: String): LocalDate? = runCatching {
    LocalDate.parse(weekKey.removePrefix("week_"), DAY_KEY_FORMAT)
}.getOrNull()

/** ISO-Kalenderwoche (1..53) für ein gegebenes Datum. */
fun calendarWeekOf(date: LocalDate = LocalDate.now()): Int =
    date.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())

/** ISO-Kalenderwoche (1..53) zu einem Wochen-Key (yyyyMMdd). */
fun calendarWeekOfKey(weekKey: String): Int =
    weekMondayOfKey(weekKey)?.let { calendarWeekOf(it) } ?: calendarWeekOf()

/** KI-generiertes Fazit eines Tages (Text-Ampel statt rechnerischem Tagesscore). */
@Serializable
data class DayFazit(
    @SerialName("tag")
    val dayKey: String = "",
    @SerialName("ampel")
    val ampel: TrafficLight = TrafficLight.UNKNOWN,              // GREEN | YELLOW | RED
    @SerialName("text")
    val text: String = "",
    @SerialName("eintrage")
    val entryIds: List<Long> = emptyList(),       // Snapshot der zugrunde liegenden Einträge (= Veraltetscheck)
    @SerialName("erstellt")
    val createdAt: Long = 0L,
    @SerialName("fp")
    val fingerprint: String = ""    // Hash-Mix aus ID+erkannt je Eintrag (Text-Korrekturen → veraltet)
)

/** Persistenz von DayFazit in Room SQLite-Datenbank. */
object DayFazitStore {
    private var migrationChecked = false

    fun ensureMigrated(ctx: Context) {
        if (migrationChecked) return
        migrationChecked = true
        val dir = File(ctx.filesDir, "history")
        val files = dir.listFiles { f -> f.name.startsWith("day_") && f.extension == "json" } ?: return
        val db = AppDatabase.getInstance(ctx)
        kotlinx.coroutines.runBlocking {
            files.forEach { f ->
                runCatching {
                    val fazit = AppJson.decodeFromString<DayFazit>(f.readText())
                    db.dayFazitDao().insert(fazit.toEntity())
                    f.delete()
                }
            }
        }
    }

    fun load(ctx: Context, dayKey: String): DayFazit? {
        ensureMigrated(ctx)
        return kotlinx.coroutines.runBlocking {
            AppDatabase.getInstance(ctx).dayFazitDao().getByKey(dayKey)?.toDomain()
        }
    }

    fun loadAll(ctx: Context): List<DayFazit> {
        ensureMigrated(ctx)
        return kotlinx.coroutines.runBlocking {
            AppDatabase.getInstance(ctx).dayFazitDao().getAll().map { it.toDomain() }
        }
    }

    fun save(ctx: Context, fazit: DayFazit) {
        ensureMigrated(ctx)
        kotlinx.coroutines.runBlocking {
            AppDatabase.getInstance(ctx).dayFazitDao().insert(fazit.toEntity())
        }
    }

    fun saveAll(ctx: Context, fazits: List<DayFazit>) {
        if (fazits.isEmpty()) return
        ensureMigrated(ctx)
        kotlinx.coroutines.runBlocking {
            AppDatabase.getInstance(ctx).dayFazitDao().insertAll(fazits.map { it.toEntity() })
        }
    }

    /** Fazit eines Tags löschen (z. B. weil ein Eintrag des Tages gelöscht wurde). */
    fun deleteForEntry(ctx: Context, entry: HistoryEntry) {
        val key = dayKeyOf(entry.dateMillis)
        kotlinx.coroutines.runBlocking {
            AppDatabase.getInstance(ctx).dayFazitDao().delete(key)
        }
    }
}

/** Fazit-Speicher für die WOCHE in Room SQLite-Datenbank.
 *  Invalidierung: Bei jeder Änderung der Wochen-Einträge (Fingerprint) wird der Eintrag GELÖSCHT –
 *  kein veraltet-Dimmen wie beim Tagesfazit (Req: „gelöscht … erneut anfordern"). */
object WeekFazitStore {
    private var migrationChecked = false

    fun ensureMigrated(ctx: Context) {
        if (migrationChecked) return
        migrationChecked = true
        val dir = File(ctx.filesDir, "history")
        val files = dir.listFiles { f -> f.name.startsWith("week_") && f.extension == "json" } ?: return
        val db = AppDatabase.getInstance(ctx)
        kotlinx.coroutines.runBlocking {
            files.forEach { f ->
                runCatching {
                    val fazit = AppJson.decodeFromString<DayFazit>(f.readText())
                    db.weekFazitDao().insert(fazit.toWeekEntity())
                    f.delete()
                }
            }
        }
    }

    fun load(ctx: Context, weekKey: String): DayFazit? {
        ensureMigrated(ctx)
        return kotlinx.coroutines.runBlocking {
            AppDatabase.getInstance(ctx).weekFazitDao().getByKey(weekKey)?.toDomain()
        }
    }

    fun loadAll(ctx: Context): List<DayFazit> {
        ensureMigrated(ctx)
        return kotlinx.coroutines.runBlocking {
            AppDatabase.getInstance(ctx).weekFazitDao().getAll().map { it.toDomain() }
        }
    }

    fun save(ctx: Context, fazit: DayFazit) {
        ensureMigrated(ctx)
        kotlinx.coroutines.runBlocking {
            AppDatabase.getInstance(ctx).weekFazitDao().insert(fazit.toWeekEntity())
        }
    }

    fun saveAll(ctx: Context, fazits: List<DayFazit>) {
        if (fazits.isEmpty()) return
        ensureMigrated(ctx)
        kotlinx.coroutines.runBlocking {
            AppDatabase.getInstance(ctx).weekFazitDao().insertAll(fazits.map { it.toWeekEntity() })
        }
    }

    /** Wochen-Fazit löschen (nachträglicher Eintrag in derselben Woche). */
    fun deleteForEntry(ctx: Context, entry: HistoryEntry) {
        val key = weekKeyOf(entry.dateMillis)
        kotlinx.coroutines.runBlocking {
            AppDatabase.getInstance(ctx).weekFazitDao().delete(key)
        }
    }

    fun delete(ctx: Context, weekKey: String) {
        val key = weekKey.removePrefix("week_")
        kotlinx.coroutines.runBlocking {
            AppDatabase.getInstance(ctx).weekFazitDao().delete(key)
        }
    }
}

/** In-Memory-Sitzungsguard: Auto-Bewertung „kein Fazit" max. 1× pro Tag und App-Session. */
object DayFazitSession {
    val attempted = mutableSetOf<String>()
    val attemptedWeekly = mutableSetOf<String>()
}

/** „Quellen-Fingerprint" eines Tages: ID+erkannt je Eintrag. Bearbeitete erkannt-Texte
 *  oder gelöschte/ergänzte Einträge machen jedes Tagesfazit auf dieser Basis KEINEN gültigen Basis mehr. */
fun dayFingerprint(entries: List<HistoryEntry>): String =
    entries.sortedBy { it.id }.joinToString("|") { "${it.id}:${it.erkannt}" }.hashCode().toString(16)
