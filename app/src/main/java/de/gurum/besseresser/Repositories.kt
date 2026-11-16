package de.gurum.besseresser

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// ----------------------------------------------------------------- Repository Interfaces

interface SettingsRepository {
    fun load(): Settings
    fun save(settings: Settings)
    fun loadRecentNotes(sortStarredFirst: Boolean = true): List<RecentNote>
    fun pushRecentNote(note: String, evaluation: Evaluation? = null)
    fun toggleStarRecentNote(noteText: String)
    fun updateRecentNoteEvaluation(note: String, evaluation: Evaluation)
    fun findCachedEvaluation(note: String, goals: List<String>): Evaluation?
    fun saveRecentNotes(notes: List<RecentNote>)
}

interface ProfileRepository {
    fun load(): Profile
    fun save(profile: Profile)
}

interface HistoryRepository {
    fun ensureMigrated()
    fun getAllFlow(): Flow<List<HistoryEntry>>
    fun getDayFazitsFlow(): Flow<Map<String, DayFazit>>
    fun getWeekFazitsFlow(): Flow<Map<String, DayFazit>>
    suspend fun getAll(): List<HistoryEntry>
    suspend fun add(photoUri: Uri?, ev: Evaluation, note: String = "", dateOverride: Long? = null, backdated: Boolean = false): HistoryEntry
    suspend fun updateErkannt(id: Long, newText: String)
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
    fun photoFileOf(entry: HistoryEntry): File?
    fun decodeThumbnail(entry: HistoryEntry, target: Int = 200): Bitmap?
}

// ----------------------------------------------------------------- Settings (SharedPreferences + KeyVault)

class DefaultSettingsRepository(private val context: Context) : SettingsRepository {

    override fun load(): Settings {
        val p = context.getSharedPreferences(BETTERESSER_PREFS, Context.MODE_PRIVATE)
        val goals = (p.getString(KEY_GOALS, "") ?: "").split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return Settings(
            baseUrl = p.getString(KEY_BASE_URL, "") ?: "",
            apiKey = p.getString(KEY_API_KEY, null)?.let { KeyVault.decrypt(it) } ?: "",
            model = p.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
            goals = goals,
            goalsWereSplit = p.getBoolean(KEY_GOALS_WERE_SPLIT, false)
        )
    }

    override fun save(settings: Settings) {
        context.getSharedPreferences(BETTERESSER_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_BASE_URL, settings.baseUrl.trim())
            .putString(KEY_API_KEY, KeyVault.encrypt(settings.apiKey.trim()))
            .putString(KEY_MODEL, settings.model.trim())
            .putString(KEY_GOALS, settings.goals.joinToString("\n"))
            .putBoolean(KEY_GOALS_WERE_SPLIT, settings.goalsWereSplit)
            .apply()
    }

    // ----------------------------------------------------------------- Notiz-Schnellauswahl

    override fun loadRecentNotes(sortStarredFirst: Boolean): List<RecentNote> = runCatching {
        val raw = context.getSharedPreferences(BETTERESSER_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECENT_NOTES, null) ?: return emptyList()
        val parsed = RecentNotesLogic.parseRecentNotes(raw)
        RecentNotesLogic.formatLoadedNotes(parsed, sortStarredFirst)
    }.getOrDefault(emptyList())

    /** Nach vorne stellen (Dedup); unmarkierte rotieren max. 5 nach MRU, älteste fliegt raus.
     *  Sternchen-Einträge bleiben dauerhaft erhalten (bis abgewählt) und verlieren ihren Stern
     *  auch bei erneuter Nutzung / Speicherung keinesfalls. */
    override fun pushRecentNote(note: String, evaluation: Evaluation?) {
        val t = note.trim()
        if (t.isBlank()) return
        val all = loadRecentNotes(sortStarredFirst = false)
        writeRecentNotes(RecentNotesLogic.pushNote(all, t, evaluation))
    }

    /** Sternchen umschalten (beim Antippen des Sterns im Dropdown) — OHNE Neusortierung:
     *  der Eintrag bleibt an seiner Stelle stehen; die Anzeige sortiert (Sternchen oben)
     *  erst beim nächsten Öffnen der Liste. Persistenz folgt der neuen Markierung. */
    override fun toggleStarRecentNote(noteText: String) {
        val t = noteText.trim()
        if (t.isBlank()) return
        val list = RecentNotesLogic.toggleStarInList(loadRecentNotes(sortStarredFirst = false), t)
        writeRecentNotes(list)
    }

    /** Aktualisiert die gespeicherte KI-Antwort eines bestehenden Menü-Eintrags, ohne Reihenfolge
     *  oder Stern-Status zu ändern. */
    override fun updateRecentNoteEvaluation(note: String, evaluation: Evaluation) {
        val t = note.trim()
        if (t.isBlank()) return
        val current = loadRecentNotes(sortStarredFirst = false)
        if (current.none { it.text == t }) return
        writeRecentNotes(RecentNotesLogic.updateEvaluationInList(current, t, evaluation))
    }

    /** Sucht in den gespeicherten Menü-Einträgen nach einer passenden KI-Bewertung für [note] und [goals]. */
    override fun findCachedEvaluation(note: String, goals: List<String>): Evaluation? =
        RecentNotesLogic.findMatchingEvaluation(loadRecentNotes(sortStarredFirst = false), note, goals)

    /** Sichtbaren Zustand persistieren (vom UI geführt, damit Klicks nichts umsortieren). */
    override fun saveRecentNotes(notes: List<RecentNote>) {
        writeRecentNotes(notes)
    }

    private fun writeRecentNotes(notes: List<RecentNote>) {
        val items = RecentNotesLogic.normalizeAndPrune(notes)
        val json = AppJson.encodeToString(items)
        context.getSharedPreferences(BETTERESSER_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RECENT_NOTES, json)
            .apply()
    }

    companion object {
        private const val KEY_BASE_URL = "baseUrl"
        private const val KEY_API_KEY = "apiKey"
        private const val KEY_MODEL = "model"
        private const val KEY_GOALS = "goals"
        private const val KEY_GOALS_WERE_SPLIT = "goalsWereSplit"
        private const val KEY_RECENT_NOTES = "recentNotes"
        private const val DEFAULT_MODEL = "gpt-4o-mini"
    }
}

// ----------------------------------------------------------------- Profil (SharedPreferences)

class DefaultProfileRepository(private val context: Context) : ProfileRepository {

    override fun load(): Profile {
        val p = context.getSharedPreferences(BETTERESSER_PREFS, Context.MODE_PRIVATE)
        return Profile(
            birthDate = p.getString(KEY_BIRTH_DATE, null)?.takeIf { it.isNotBlank() },
            weightKg = p.getString(KEY_WEIGHT, null)
                ?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 },
            heightCm = p.getInt(KEY_HEIGHT, -1).takeIf { it > 0 },
            notes = p.getString(KEY_NOTES, "") ?: "",
            gender = Gender.fromString(p.getString(KEY_GENDER, null))
        )
    }

    /** Leere Eingaben speichern als "nicht gesetzt" – das Profil darf komplett leer bleiben. */
    override fun save(profile: Profile) {
        context.getSharedPreferences(BETTERESSER_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_BIRTH_DATE, profile.birthDate?.takeIf { it.isNotBlank() })
            .putString(KEY_WEIGHT, profile.weightKg?.toString())
            .putInt(KEY_HEIGHT, profile.heightCm ?: -1)
            .putString(KEY_NOTES, profile.notes.trim())
            .putString(KEY_GENDER, profile.gender.rawValue)
            .apply()
    }

    companion object {
        private const val KEY_BIRTH_DATE = "birthDate"
        private const val KEY_WEIGHT = "profileWeight"
        private const val KEY_HEIGHT = "profileHeight"
        private const val KEY_NOTES = "profileNotes"
        private const val KEY_GENDER = "profileGender"
    }
}

// ----------------------------------------------------------------- Verlauf (Room + PhotoStorage)

class DefaultHistoryRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val photoStorage: PhotoStorage
) : HistoryRepository {

    @Volatile
    private var migrationChecked = false

    /** Einmalige Migration historischer JSON-Dateien (filesDir/history) nach Room. */
    override fun ensureMigrated() {
        if (migrationChecked) return
        synchronized(this) {
            if (migrationChecked) return
            migrationChecked = true
        }
        val d = photoStorage.dir()

        kotlinx.coroutines.runBlocking {
            d.listFiles { f ->
                f.extension == "json" && !f.name.startsWith("day_") && !f.name.startsWith("week_")
            }?.forEach { f ->
                runCatching {
                    val entry = AppJson.decodeFromString<HistoryEntry>(f.readText())
                    val e = if (entry.id == 0L) entry.copy(id = f.nameWithoutExtension.toLongOrNull() ?: 0L) else entry
                    db.historyDao().insert(e.toEntity())
                    f.delete()
                }
            }
            d.listFiles { f -> f.name.startsWith("day_") && f.extension == "json" }?.forEach { f ->
                runCatching {
                    val fazit = AppJson.decodeFromString<DayFazit>(f.readText())
                    db.dayFazitDao().insert(fazit.toEntity())
                    f.delete()
                }
            }
            d.listFiles { f -> f.name.startsWith("week_") && f.extension == "json" }?.forEach { f ->
                runCatching {
                    val fazit = AppJson.decodeFromString<DayFazit>(f.readText())
                    db.weekFazitDao().insert(fazit.toWeekEntity())
                    f.delete()
                }
            }
        }
    }

    override fun getAllFlow(): Flow<List<HistoryEntry>> =
        db.historyDao().getAllFlow().map { list -> list.map { it.toDomain() } }

    override fun getDayFazitsFlow(): Flow<Map<String, DayFazit>> =
        db.dayFazitDao().getAllFlow().map { list -> list.map { it.toDomain() }.associateBy { it.dayKey } }

    override fun getWeekFazitsFlow(): Flow<Map<String, DayFazit>> =
        db.weekFazitDao().getAllFlow().map { list -> list.map { it.toDomain() }.associateBy { it.dayKey } }

    override suspend fun getAll(): List<HistoryEntry> =
        db.historyDao().getAll().map { it.toDomain() }

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
        ensureMigrated()
        val id = System.currentTimeMillis()
        val date = dateOverride ?: id
        val photoFile = if (photoUri != null) {
            photoStorage.saveScaledJpeg(photoUri, "$id.jpg") ?: ""
        } else ""
        val entry = HistoryEntry(
            id = id, dateMillis = date, photoFile = photoFile,
            goalResults = ev.goalResults, summary = ev.summary,
            note = note, erkannt = ev.erkannt, backdated = backdated,
            sortOrder = date
        )
        val dao = db.historyDao()
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
        db.historyDao().deleteById(entry.id)
        deleteDayFazitForEntry(entry)
        deleteWeekFazitForEntry(entry)
    }

    /** erkannt-Text eines gespeicherten Eintrags userkorrigieren (in Room DB aktualisieren).
     *  Das Tagesfazit bleibt in der DB, wird aber über den Fingerprint als veraltet erkannt. */
    override suspend fun updateErkannt(id: Long, newText: String) {
        db.historyDao().updateErkannt(id, newText.trim())
    }

    /** Ziele + Gesamteindruck eines Eintrags neu schreiben (KI-Re-Bewertung nach Korrektur). */
    override suspend fun updateEvaluation(id: Long, goalResults: List<GoalResult>, summary: String) {
        db.historyDao().updateEvaluation(id, goalResults, summary)
    }

    /** Aktualisiert die Zeitstempel der übergebenen Einträge (für Umsortierung innerhalb eines Tages).
     *  Bestehende Tages- und Wochenfazits bleiben unverändert gültig (keine Neuberechnung nötig). */
    override suspend fun reorderEntries(reordered: List<HistoryEntry>) {
        if (reordered.isEmpty()) return
        ensureMigrated()
        db.historyDao().insertAll(reordered.map { it.toEntity() })
    }

    /** Speichert/überschreibt mehrere Verlaufseinträge (z. B. beim Backup-Import). */
    override suspend fun saveAll(entries: List<HistoryEntry>) {
        if (entries.isEmpty()) return
        ensureMigrated()
        db.historyDao().insertAll(entries.map { it.toEntity() })
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
        ensureMigrated()
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
        val dao = db.historyDao()
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
        ensureMigrated()
        photoStorage.deleteAll()
        db.historyDao().deleteAll()
        db.dayFazitDao().deleteAll()
        db.weekFazitDao().deleteAll()
        DayFazitSession.attempted.clear()
        DayFazitSession.attemptedWeekly.clear()
    }

    override suspend fun saveDayFazit(fazit: DayFazit) {
        ensureMigrated()
        db.dayFazitDao().insert(fazit.toEntity())
    }

    override suspend fun loadDayFazit(dayKey: String): DayFazit? {
        ensureMigrated()
        return db.dayFazitDao().getByKey(dayKey)?.toDomain()
    }

    override suspend fun loadAllDayFazits(): List<DayFazit> {
        ensureMigrated()
        return db.dayFazitDao().getAll().map { it.toDomain() }
    }

    override suspend fun saveAllDayFazits(fazits: List<DayFazit>) {
        if (fazits.isEmpty()) return
        ensureMigrated()
        db.dayFazitDao().insertAll(fazits.map { it.toEntity() })
    }

    /** Fazit eines Tags löschen (z. B. weil ein Eintrag des Tages gelöscht wurde). */
    override suspend fun deleteDayFazitForEntry(entry: HistoryEntry) {
        db.dayFazitDao().delete(dayKeyOf(entry.dateMillis))
    }

    override suspend fun saveWeekFazit(fazit: DayFazit) {
        ensureMigrated()
        db.weekFazitDao().insert(fazit.toWeekEntity())
    }

    override suspend fun loadWeekFazit(weekKey: String): DayFazit? {
        ensureMigrated()
        return db.weekFazitDao().getByKey(weekKey)?.toDomain()
    }

    override suspend fun loadAllWeekFazits(): List<DayFazit> {
        ensureMigrated()
        return db.weekFazitDao().getAll().map { it.toDomain() }
    }

    override suspend fun saveAllWeekFazits(fazits: List<DayFazit>) {
        if (fazits.isEmpty()) return
        ensureMigrated()
        db.weekFazitDao().insertAll(fazits.map { it.toWeekEntity() })
    }

    /** Wochen-Fazit löschen (Akzeptiert Keys mit oder ohne „week_"-Präfix). */
    override suspend fun deleteWeekFazit(weekKey: String) {
        db.weekFazitDao().delete(weekKey.removePrefix("week_"))
    }

    /** Wochen-Fazit löschen (nachträglicher Eintrag in derselben Woche). */
    override suspend fun deleteWeekFazitForEntry(entry: HistoryEntry) {
        db.weekFazitDao().delete(weekKeyOf(entry.dateMillis))
    }

    override fun photoFileOf(entry: HistoryEntry): File? =
        photoStorage.photoFileOf(entry)

    override fun decodeThumbnail(entry: HistoryEntry, target: Int): Bitmap? =
        photoFileOf(entry)?.let { photoStorage.decodeThumb(it, target) }
}
