package de.gurum.besseresser.data

import android.graphics.Bitmap
import android.net.Uri
import de.gurum.besseresser.model.DayFazit
import de.gurum.besseresser.model.DayFazitSession
import de.gurum.besseresser.model.Evaluation
import de.gurum.besseresser.model.GoalResult
import de.gurum.besseresser.model.HistoryEntry
import de.gurum.besseresser.model.HistoryEntryLogic
import de.gurum.besseresser.model.dayKeyOf
import de.gurum.besseresser.model.retentionCutoffMillis
import de.gurum.besseresser.model.weekKeyOf
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// ----------------------------------------------------------------- Interface

interface HistoryRepository {
    fun getAllFlow(): Flow<List<HistoryEntry>>
    fun getByIdFlow(id: Long): Flow<HistoryEntry?>
    fun getDayFazitsFlow(): Flow<Map<String, DayFazit>>
    fun getWeekFazitsFlow(): Flow<Map<String, DayFazit>>
    suspend fun getAll(): List<HistoryEntry>
    suspend fun add(photoUri: Uri?, ev: Evaluation, note: String = "", dateOverride: Long? = null, backdated: Boolean = false): HistoryEntry
    suspend fun updateRecognizedMeal(id: Long, newText: String)
    suspend fun updateEvaluation(id: Long, goalResults: List<GoalResult>, summary: String)
    suspend fun reorderEntries(reordered: List<HistoryEntry>)
    suspend fun delete(entry: HistoryEntry)
    suspend fun saveAll(entries: List<HistoryEntry>)
    suspend fun duplicateToDate(
        source: HistoryEntry,
        targetDay: LocalDate,
        targetTime: java.time.LocalTime = java.time.LocalTime.now()
    ): HistoryEntry
    suspend fun clearAll()
    suspend fun saveDayFazit(fazit: DayFazit)
    suspend fun loadDayFazit(dayKey: String): DayFazit?
    suspend fun loadAllDayFazits(): List<DayFazit>
    suspend fun saveAllDayFazits(fazits: List<DayFazit>)
    suspend fun deleteDayFazitForEntry(entry: HistoryEntry)
    suspend fun saveWeekFazit(fazit: DayFazit)
    suspend fun loadWeekFazit(weekKey: String): DayFazit?
    suspend fun loadAllWeekFazits(): List<DayFazit>
    suspend fun saveAllWeekFazits(fazits: List<DayFazit>)
    suspend fun deleteWeekFazit(weekKey: String)
    suspend fun deleteWeekFazitForEntry(entry: HistoryEntry)
    /** Löscht alle Einträge (inkl. Fotos) sowie Tages-/Wochenfazits, die älter als [weeks]
     *  volle Wochen sind. 0/negativ = nichts löschen. Liefert die Anzahl gelöschter Einträge. */
    suspend fun purgeOlderThanWeeks(weeks: Int): Int
    /** Löscht Foto-Dateien im Speicherverzeichnis, denen kein DB-Eintrag mehr entspricht.
     *  Schützt vor verwaisten Dateien nach Abstürzen oder fehlgeschlagenen Migrationen. */
    suspend fun purgeOrphanedPhotos(): Int
    fun photoFileOf(entry: HistoryEntry): File?
    suspend fun decodeThumbnail(entry: HistoryEntry, target: Int = 200): Bitmap?
}

// ----------------------------------------------------------------- Implementation

class DefaultHistoryRepository(
    private val photoStorage: PhotoStorage,
    private val historyDao: HistoryDao,
    private val dayFazitDao: DayFazitDao,
    private val weekFazitDao: WeekFazitDao,
    private val dispatchers: AppDispatchers = AppDispatchers(),
    autoMigrateLegacyData: Boolean = true
) : HistoryRepository {

    init {
        if (autoMigrateLegacyData) {
            LegacyJsonMigrator.migrateSync(photoStorage.dir(), historyDao, dayFazitDao, weekFazitDao)
        }
    }

    override fun getAllFlow(): Flow<List<HistoryEntry>> =
        historyDao.getAllFlow().map { list -> list.map { it.toDomain() } }

    override fun getByIdFlow(id: Long): Flow<HistoryEntry?> =
        historyDao.getByIdFlow(id).map { it?.toDomain() }

    override fun getDayFazitsFlow(): Flow<Map<String, DayFazit>> =
        dayFazitDao.getAllFlow().map { list -> list.map { it.toDomain() }.associateBy { it.dayKey } }

    override fun getWeekFazitsFlow(): Flow<Map<String, DayFazit>> =
        weekFazitDao.getAllFlow().map { list -> list.map { it.toDomain() }.associateBy { it.dayKey } }

    override suspend fun getAll(): List<HistoryEntry> =
        historyDao.getAll().map { it.toDomain() }

    /** Speichert Bewertung + verkleinertes Foto (typisch < 100 KB). Foto-Verarbeitung darf scheitern (dann ohne Thumbnail).
     *  photoUri = null → Eintrag OHNE Foto (Nacherfassung „ohne Foto“).
     *  dateOverride + backdated: nachträgliche Erfassung für ein bestimmtes Tagesdatum. */
    override suspend fun add(
        photoUri: Uri?,
        ev: Evaluation,
        note: String,
        dateOverride: Long?,
        backdated: Boolean
    ): HistoryEntry {
        val id = System.currentTimeMillis()
        val date = dateOverride ?: id
        val photoFile = if (photoUri != null) {
            photoStorage.saveScaledJpeg(photoUri, "$id.jpg") ?: ""
        } else ""
        val entry = HistoryEntry(
            id = id, dateMillis = date, photoFile = photoFile,
            goalResults = ev.goalResults, summary = ev.summary,
            note = note, recognizedMeal = ev.recognizedMeal, backdated = backdated,
            sortOrder = date
        )
        val dao = historyDao
        val targetDay = Instant.ofEpochMilli(date).atZone(ZoneId.systemDefault()).toLocalDate()
        val existingSameDay = dao.getAll().map { it.toDomain() }.filter {
            Instant.ofEpochMilli(it.dateMillis).atZone(ZoneId.systemDefault()).toLocalDate() == targetDay
        }
        val synced = HistoryEntryLogic.computeChronologicalSortOrders(existingSameDay, entry)
        dao.insertAll(synced.map { it.toEntity() })
        return entry
    }

    /** Eintrag inkl. Foto loeschen. Löscht Tages-/Wochenfazit des Tages gleich mit. */
    override suspend fun delete(entry: HistoryEntry) {
        photoStorage.deletePhoto(entry.photoFile)
        historyDao.deleteById(entry.id)
        deleteDayFazitForEntry(entry)
        deleteWeekFazitForEntry(entry)
    }

    /** recognizedMeal-Text eines gespeicherten Eintrags userkorrigieren (in Room DB aktualisieren).
     *  Das Tagesfazit bleibt in der DB, wird aber über den Fingerprint als veraltet recognizedMeal. */
    override suspend fun updateRecognizedMeal(id: Long, newText: String) {
        historyDao.updateRecognizedMeal(id, newText.trim())
    }

    /** Ziele + Gesamteindruck eines Eintrags neu schreiben (KI-Re-Bewertung nach Korrektur). */
    override suspend fun updateEvaluation(id: Long, goalResults: List<GoalResult>, summary: String) {
        historyDao.updateEvaluation(id, goalResults, summary)
    }

    /** Aktualisiert die Zeitstempel der übergebenen Einträge (für Umsortierung innerhalb eines Tages).
     *  Bestehende Tages- und Wochenfazits bleiben unverändert gültig (keine Neuberechnung nötig). */
    override suspend fun reorderEntries(reordered: List<HistoryEntry>) {
        if (reordered.isEmpty()) return
        historyDao.insertAll(reordered.map { it.toEntity() })
    }

    /** Speichert/überschreibt mehrere Verlaufseinträge (z. B. beim Backup-Import). */
    override suspend fun saveAll(entries: List<HistoryEntry>) {
        if (entries.isEmpty()) return
        historyDao.insertAll(entries.map { it.toEntity() })
    }

    /** Dupliziert einen bestehenden Eintrag komplett (inkl. Foto, Ziele, Texte, Notiz) ohne erneute KI-Bewertung
     *  auf das gewählte Zieldatum und die gewählte Uhrzeit.
     *  Ein bestehendes Tagesfazit des Zieltags bleibt in der DB, damit es über den Entry-ID-/Fingerprint-Mismatch
     *  genau wie bei einer Eintrags-Bearbeitung als geändert/veraltet („⟳ Neu bewerten“) getrackt wird. */
    override suspend fun duplicateToDate(
        source: HistoryEntry,
        targetDay: LocalDate,
        targetTime: java.time.LocalTime
    ): HistoryEntry {
        val id = System.currentTimeMillis()
        val existingPhoto = photoStorage.photoFileOf(source)
        val copiedPhotoName = if (existingPhoto != null) {
            photoStorage.copyPhoto(existingPhoto, "$id.jpg") ?: ""
        } else ""
        val duplicated = HistoryEntryLogic.createDuplicatedEntry(
            source = source,
            targetDay = targetDay,
            targetTime = targetTime,
            newId = id,
            newPhotoFile = copiedPhotoName
        )
        val dao = historyDao
        val existingSameDay = dao.getAll().map { it.toDomain() }.filter {
            Instant.ofEpochMilli(it.dateMillis).atZone(ZoneId.systemDefault()).toLocalDate() == targetDay
        }
        val synced = HistoryEntryLogic.computeChronologicalSortOrders(existingSameDay, duplicated)
        val finalDuplicated = synced.first { it.id == duplicated.id }
        dao.insertAll(synced.map { it.toEntity() })
        // Wochenfazit der Zielwoche invalidieren; Tagesfazit bleibt stehen und wird durch die neue
        // Entry-ID automatisch als veraltet (stale = true / „⟳ Neu bewerten“) markiert.
        deleteWeekFazitForEntry(finalDuplicated)
        return finalDuplicated
    }

    /** Löscht alle Verlaufsdaten (Einträge, Fotos, Tages- und Wochenfazits) komplett. */
    override suspend fun clearAll() {
        photoStorage.deleteAll()
        historyDao.deleteAll()
        dayFazitDao.deleteAll()
        weekFazitDao.deleteAll()
        DayFazitSession.attempted.clear()
        DayFazitSession.attemptedWeekly.clear()
    }

    /** Löscht alle Einträge (inkl. Fotos) sowie Tages-/Wochenfazits, die älter als [weeks]
     *  volle Wochen sind. 0/negativ = nichts löschen. Liefert die Anzahl gelöschter Einträge. */
    override suspend fun purgeOlderThanWeeks(weeks: Int): Int {
        if (weeks <= 0) return 0
        val cutoffMillis = retentionCutoffMillis(weeks)
        val cutoffWeekKey = weekKeyOf(cutoffMillis)
        val cutoffDayKey = dayKeyOf(cutoffMillis)

        // 1. Einträge vor dem Stichtag inkl. Foto-Dateien löschen
        val stale = historyDao.getAll().map { it.toDomain() }
            .filter { it.dateMillis < cutoffMillis }
        stale.forEach { entry ->
            currentCoroutineContext().ensureActive()
            photoStorage.deletePhoto(entry.photoFile)
            historyDao.deleteById(entry.id)
        }

        // 2. Tages-/Wochenfazits vor dem Stichtag löschen (inkl. verwaister)
        dayFazitDao.getAll().map { it.dayKey }
            .filter { it < cutoffDayKey }
            .forEach {
                currentCoroutineContext().ensureActive()
                dayFazitDao.delete(it)
            }
        weekFazitDao.getAll().map { it.weekKey }
            .filter { it < cutoffWeekKey }
            .forEach {
                currentCoroutineContext().ensureActive()
                weekFazitDao.delete(it)
            }

        // 3. Session-Guard aufräumen (damit Fazits neuer Tage nicht irrtümlich „versucht" bleiben)
        DayFazitSession.attempted.removeAll { it < cutoffDayKey }
        DayFazitSession.attemptedWeekly.removeAll { it < cutoffWeekKey }

        return stale.size
    }

    override suspend fun purgeOrphanedPhotos(): Int {
        val knownFileNames = historyDao.getAll()
            .map { it.photoFile }
            .filter { it.isNotBlank() }
            .toSet()
        val dir = photoStorage.dir()
        val orphans = dir.listFiles { f ->
            f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") &&
                f.name !in knownFileNames
        } ?: return 0
        orphans.forEach { file ->
            currentCoroutineContext().ensureActive()
            runCatching { file.delete() }
        }
        return orphans.size
    }

    override suspend fun saveDayFazit(fazit: DayFazit) {
        dayFazitDao.insert(fazit.toEntity())
    }

    override suspend fun loadDayFazit(dayKey: String): DayFazit? {
        return dayFazitDao.getByKey(dayKey)?.toDomain()
    }

    override suspend fun loadAllDayFazits(): List<DayFazit> {
        return dayFazitDao.getAll().map { it.toDomain() }
    }

    override suspend fun saveAllDayFazits(fazits: List<DayFazit>) {
        if (fazits.isEmpty()) return
        dayFazitDao.insertAll(fazits.map { it.toEntity() })
    }

    /** Fazit eines Tags löschen (z. B. weil ein Eintrag des Tages gelöscht wurde). */
    override suspend fun deleteDayFazitForEntry(entry: HistoryEntry) {
        dayFazitDao.delete(dayKeyOf(entry.dateMillis))
    }

    override suspend fun saveWeekFazit(fazit: DayFazit) {
        weekFazitDao.insert(fazit.toWeekEntity())
    }

    override suspend fun loadWeekFazit(weekKey: String): DayFazit? {
        return weekFazitDao.getByKey(weekKey)?.toDomain()
    }

    override suspend fun loadAllWeekFazits(): List<DayFazit> {
        return weekFazitDao.getAll().map { it.toDomain() }
    }

    override suspend fun saveAllWeekFazits(fazits: List<DayFazit>) {
        if (fazits.isEmpty()) return
        weekFazitDao.insertAll(fazits.map { it.toWeekEntity() })
    }

    /** Wochen-Fazit löschen (Akzeptiert Keys mit oder ohne „week_"-Präfix). */
    override suspend fun deleteWeekFazit(weekKey: String) {
        weekFazitDao.delete(weekKey.removePrefix("week_"))
    }

    /** Wochen-Fazit löschen (nachträglicher Eintrag in derselben Woche). */
    override suspend fun deleteWeekFazitForEntry(entry: HistoryEntry) {
        weekFazitDao.delete(weekKeyOf(entry.dateMillis))
    }

    override fun photoFileOf(entry: HistoryEntry): File? =
        photoStorage.photoFileOf(entry)

    override suspend fun decodeThumbnail(entry: HistoryEntry, target: Int): Bitmap? =
        withContext(dispatchers.io) {
            photoFileOf(entry)?.let { photoStorage.decodeThumb(it, target) }
        }
}
