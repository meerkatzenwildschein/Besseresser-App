package de.gurum.besseresser.data
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import de.gurum.besseresser.model.retentionCutoffMillis
import de.gurum.besseresser.model.AppJson
import de.gurum.besseresser.model.AppLanguage
import de.gurum.besseresser.model.AppThemeMode
import de.gurum.besseresser.model.BETTERESSER_PREFS
import de.gurum.besseresser.model.DayFazit
import de.gurum.besseresser.model.DayFazitSession
import de.gurum.besseresser.model.dayKeyOf
import de.gurum.besseresser.model.Evaluation
import de.gurum.besseresser.model.GoalResult
import de.gurum.besseresser.model.HistoryEntry
import de.gurum.besseresser.model.HistoryEntryLogic
import de.gurum.besseresser.model.RecentNote
import de.gurum.besseresser.model.RecentNotesLogic
import de.gurum.besseresser.model.weekKeyOf
import de.gurum.besseresser.model.Gender
import de.gurum.besseresser.model.Profile
import de.gurum.besseresser.model.Settings

// ----------------------------------------------------------------- Repository Interfaces

interface SettingsRepository {
    val settings: Flow<Settings>
    suspend fun currentSettings(): Settings
    suspend fun save(settings: Settings)
    fun recentNotesFlow(sortStarredFirst: Boolean = true): Flow<List<RecentNote>>
    suspend fun loadRecentNotes(sortStarredFirst: Boolean = true): List<RecentNote>
    suspend fun pushRecentNote(note: String, evaluation: Evaluation? = null)
    suspend fun toggleStarRecentNote(noteText: String)
    suspend fun updateRecentNoteEvaluation(note: String, evaluation: Evaluation)
    suspend fun findCachedEvaluation(note: String, goals: List<String>): Evaluation?
    suspend fun saveRecentNotes(notes: List<RecentNote>)
}

interface ProfileRepository {
    val profile: Flow<Profile>
    suspend fun currentProfile(): Profile
    suspend fun save(profile: Profile)
}

interface UiPrefsRepository {
    val themeMode: Flow<AppThemeMode>
    val language: Flow<AppLanguage>
    suspend fun currentThemeMode(): AppThemeMode
    suspend fun currentLanguage(): AppLanguage
    suspend fun saveThemeMode(mode: AppThemeMode)
    suspend fun saveLanguage(language: AppLanguage)
}

// ----------------------------------------------------------------- DataStore (mit SharedPreferences-Migration)

/** Gemeinsame Preferences-DataStore für Einstellungen, Profil, Notizen und UI-Prefs.
 *  Bestehende SharedPreferences ("besseresser_prefs") werden einmalig migriert. */
val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(
    name = BETTERESSER_PREFS,
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, BETTERESSER_PREFS))
    }
)

interface HistoryRepository {
    suspend fun ensureMigrated()
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
    /** Löscht alle Einträge (inkl. Fotos) sowie Tages-/Wochenfazits, die älter als [weeks]
     *  volle Wochen sind. 0/negativ = nichts löschen. Liefert die Anzahl gelöschter Einträge. */
    suspend fun purgeOlderThanWeeks(weeks: Int): Int
    fun photoFileOf(entry: HistoryEntry): File?
    fun decodeThumbnail(entry: HistoryEntry, target: Int = 200): Bitmap?
}

// ----------------------------------------------------------------- Settings (DataStore + KeyVault)

class DefaultSettingsRepository(private val dataStore: DataStore<Preferences>) : SettingsRepository {

    private object Keys {
        val BASE_URL = stringPreferencesKey("baseUrl")
        val API_KEY = stringPreferencesKey("apiKey")
        val MODEL = stringPreferencesKey("model")
        val GOALS = stringPreferencesKey("goals")
        val GOALS_WERE_SPLIT = booleanPreferencesKey("goalsWereSplit")
        val RETENTION_WEEKS = intPreferencesKey("retentionWeeks")
        val RECENT_NOTES = stringPreferencesKey("recentNotes")
        const val DEFAULT_MODEL = "gpt-4o-mini"
    }

    override val settings: Flow<Settings> = dataStore.data.map { p -> p.toSettings() }

    private fun Preferences.toSettings(): Settings {
        val goals = (this[Keys.GOALS] ?: "").split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return Settings(
            baseUrl = this[Keys.BASE_URL] ?: "",
            apiKey = this[Keys.API_KEY]?.let { raw -> KeyVault.decrypt(raw) ?: raw } ?: "",
            model = this[Keys.MODEL] ?: Keys.DEFAULT_MODEL,
            goals = goals,
            goalsWereSplit = this[Keys.GOALS_WERE_SPLIT] ?: false,
            retentionWeeks = this[Keys.RETENTION_WEEKS] ?: Settings.DEFAULT_RETENTION_WEEKS
        )
    }

    override suspend fun currentSettings(): Settings = dataStore.data.first().toSettings()

    override suspend fun save(settings: Settings) {
        dataStore.edit { p ->
            p[Keys.BASE_URL] = settings.baseUrl.trim()
            p[Keys.API_KEY] = KeyVault.encrypt(settings.apiKey.trim())
            p[Keys.MODEL] = settings.model.trim()
            p[Keys.GOALS] = settings.goals.joinToString("\n")
            p[Keys.GOALS_WERE_SPLIT] = settings.goalsWereSplit
            p[Keys.RETENTION_WEEKS] = settings.retentionWeeks
        }
    }

    // ----------------------------------------------------------------- Notiz-Schnellauswahl

    override fun recentNotesFlow(sortStarredFirst: Boolean): Flow<List<RecentNote>> =
        dataStore.data.map { p ->
            val raw = p[Keys.RECENT_NOTES] ?: return@map emptyList()
            runCatching {
                RecentNotesLogic.formatLoadedNotes(RecentNotesLogic.parseRecentNotes(raw), sortStarredFirst)
            }.getOrDefault(emptyList())
        }

    override suspend fun loadRecentNotes(sortStarredFirst: Boolean): List<RecentNote> {
        val raw = dataStore.data.first()[Keys.RECENT_NOTES] ?: return emptyList()
        return runCatching {
            RecentNotesLogic.formatLoadedNotes(RecentNotesLogic.parseRecentNotes(raw), sortStarredFirst)
        }.getOrDefault(emptyList())
    }

    /** Nach vorne stellen (Dedup); unmarkierte rotieren max. 5 nach MRU, älteste fliegt raus.
     *  Sternchen-Einträge bleiben dauerhaft erhalten (bis abgewählt) und verlieren ihren Stern
     *  auch bei erneuter Nutzung / Speicherung keinesfalls. */
    override suspend fun pushRecentNote(note: String, evaluation: Evaluation?) {
        val t = note.trim()
        if (t.isBlank()) return
        val all = loadRecentNotes(sortStarredFirst = false)
        writeRecentNotes(RecentNotesLogic.pushNote(all, t, evaluation))
    }

    /** Sternchen umschalten (beim Antippen des Sterns im Dropdown) — OHNE Neusortierung:
     *  der Eintrag bleibt an seiner Stelle stehen; die Anzeige sortiert (Sternchen oben)
     *  erst beim nächsten Öffnen der Liste. Persistenz folgt der neuen Markierung. */
    override suspend fun toggleStarRecentNote(noteText: String) {
        val t = noteText.trim()
        if (t.isBlank()) return
        val list = RecentNotesLogic.toggleStarInList(loadRecentNotes(sortStarredFirst = false), t)
        writeRecentNotes(list)
    }

    /** Aktualisiert die gespeicherte KI-Antwort eines bestehenden Menü-Eintrags, ohne Reihenfolge
     *  oder Stern-Status zu ändern. */
    override suspend fun updateRecentNoteEvaluation(note: String, evaluation: Evaluation) {
        val t = note.trim()
        if (t.isBlank()) return
        val current = loadRecentNotes(sortStarredFirst = false)
        if (current.none { it.text == t }) return
        writeRecentNotes(RecentNotesLogic.updateEvaluationInList(current, t, evaluation))
    }

    /** Sucht in den gespeicherten Menü-Einträgen nach einer passenden KI-Bewertung für [note] und [goals]. */
    override suspend fun findCachedEvaluation(note: String, goals: List<String>): Evaluation? =
        RecentNotesLogic.findMatchingEvaluation(loadRecentNotes(sortStarredFirst = false), note, goals)

    /** Sichtbaren Zustand persistieren (vom UI geführt, damit Klicks nichts umsortieren). */
    override suspend fun saveRecentNotes(notes: List<RecentNote>) {
        writeRecentNotes(notes)
    }

    private suspend fun writeRecentNotes(notes: List<RecentNote>) {
        val json = AppJson.encodeToString(RecentNotesLogic.normalizeAndPrune(notes))
        dataStore.edit { p -> p[Keys.RECENT_NOTES] = json }
    }
}

// ----------------------------------------------------------------- Profil (DataStore)

class DefaultProfileRepository(private val dataStore: DataStore<Preferences>) : ProfileRepository {

    private object Keys {
        val BIRTH_DATE = stringPreferencesKey("birthDate")
        val WEIGHT = stringPreferencesKey("profileWeight")
        val HEIGHT = intPreferencesKey("profileHeight")
        val NOTES = stringPreferencesKey("profileNotes")
        val GENDER = stringPreferencesKey("profileGender")
    }

    override val profile: Flow<Profile> = dataStore.data.map { p -> p.toProfile() }

    private fun Preferences.toProfile(): Profile = Profile(
        birthDate = this[Keys.BIRTH_DATE]?.takeIf { it.isNotBlank() },
        weightKg = this[Keys.WEIGHT]?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 },
        heightCm = this[Keys.HEIGHT]?.takeIf { it > 0 },
        notes = this[Keys.NOTES] ?: "",
        gender = Gender.fromString(this[Keys.GENDER])
    )

    override suspend fun currentProfile(): Profile = dataStore.data.first().toProfile()

    /** Leere Eingaben speichern als "nicht gesetzt" – das Profil darf komplett leer bleiben.
     *  (birthDate/weight bleiben bei "nicht gesetzt" als leere Strings erhalten, height als -1 –
     *  identisch zur früheren SharedPreferences-Schreibweise.) */
    override suspend fun save(profile: Profile) {
        dataStore.edit { p ->
            p[Keys.BIRTH_DATE] = profile.birthDate?.takeIf { it.isNotBlank() } ?: ""
            p[Keys.WEIGHT] = profile.weightKg?.toString() ?: ""
            p[Keys.HEIGHT] = profile.heightCm ?: -1
            p[Keys.NOTES] = profile.notes.trim()
            p[Keys.GENDER] = profile.gender.rawValue
        }
    }
}

// ----------------------------------------------------------------- UI-Einstellungen (DataStore)

class DefaultUiPrefsRepository(private val dataStore: DataStore<Preferences>) : UiPrefsRepository {

    private object Keys {
        val THEME = stringPreferencesKey("appThemeMode")
        val LANGUAGE = stringPreferencesKey("appLanguage")
    }

    override val themeMode: Flow<AppThemeMode> = dataStore.data.map { p ->
        AppThemeMode.fromString(p[Keys.THEME])
    }

    override val language: Flow<AppLanguage> = dataStore.data.map { p ->
        AppLanguage.resolve(p[Keys.LANGUAGE])
    }

    override suspend fun currentThemeMode(): AppThemeMode = themeMode.first()

    override suspend fun currentLanguage(): AppLanguage = language.first()

    override suspend fun saveThemeMode(mode: AppThemeMode) {
        dataStore.edit { p -> p[Keys.THEME] = mode.rawValue }
    }

    override suspend fun saveLanguage(language: AppLanguage) {
        dataStore.edit { p -> p[Keys.LANGUAGE] = language.rawValue }
    }
}

// ----------------------------------------------------------------- Verlauf (Room + PhotoStorage)

class DefaultHistoryRepository(
    private val photoStorage: PhotoStorage,
    private val historyDao: HistoryDao,
    private val dayFazitDao: DayFazitDao,
    private val weekFazitDao: WeekFazitDao,
    private val dispatchers: AppDispatchers = AppDispatchers()
) : HistoryRepository {

    @Volatile
    private var migrationChecked = false

    /** Einmalige Migration historischer JSON-Dateien (filesDir/history) nach Room. */
    override suspend fun ensureMigrated() {
        if (migrationChecked) return
        withContext(dispatchers.io) {
            synchronized(this@DefaultHistoryRepository) {
                if (migrationChecked) return@withContext
                migrationChecked = true
            }
            val d = photoStorage.dir()

            d.listFiles { f ->
                f.extension == "json" && !f.name.startsWith("day_") && !f.name.startsWith("week_")
            }?.forEach { f ->
                runCatching {
                    val entry = AppJson.decodeFromString<HistoryEntry>(f.readText())
                    val e = if (entry.id == 0L) entry.copy(id = f.nameWithoutExtension.toLongOrNull() ?: 0L) else entry
                    historyDao.insert(e.toEntity())
                    f.delete()
                }
            }
            d.listFiles { f -> f.name.startsWith("day_") && f.extension == "json" }?.forEach { f ->
                runCatching {
                    val fazit = AppJson.decodeFromString<DayFazit>(f.readText())
                    dayFazitDao.insert(fazit.toEntity())
                    f.delete()
                }
            }
            d.listFiles { f -> f.name.startsWith("week_") && f.extension == "json" }?.forEach { f ->
                runCatching {
                    val fazit = AppJson.decodeFromString<DayFazit>(f.readText())
                    weekFazitDao.insert(fazit.toWeekEntity())
                    f.delete()
                }
            }
        }
    }

    override fun getAllFlow(): Flow<List<HistoryEntry>> =
        historyDao.getAllFlow().map { list -> list.map { it.toDomain() } }

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

    /** erkannt-Text eines gespeicherten Eintrags userkorrigieren (in Room DB aktualisieren).
     *  Das Tagesfazit bleibt in der DB, wird aber über den Fingerprint als veraltet erkannt. */
    override suspend fun updateErkannt(id: Long, newText: String) {
        historyDao.updateErkannt(id, newText.trim())
    }

    /** Ziele + Gesamteindruck eines Eintrags neu schreiben (KI-Re-Bewertung nach Korrektur). */
    override suspend fun updateEvaluation(id: Long, goalResults: List<GoalResult>, summary: String) {
        historyDao.updateEvaluation(id, goalResults, summary)
    }

    /** Aktualisiert die Zeitstempel der übergebenen Einträge (für Umsortierung innerhalb eines Tages).
     *  Bestehende Tages- und Wochenfazits bleiben unverändert gültig (keine Neuberechnung nötig). */
    override suspend fun reorderEntries(reordered: List<HistoryEntry>) {
        if (reordered.isEmpty()) return
        ensureMigrated()
        historyDao.insertAll(reordered.map { it.toEntity() })
    }

    /** Speichert/überschreibt mehrere Verlaufseinträge (z. B. beim Backup-Import). */
    override suspend fun saveAll(entries: List<HistoryEntry>) {
        if (entries.isEmpty()) return
        ensureMigrated()
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
        ensureMigrated()
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
        ensureMigrated()
        val cutoffMillis = retentionCutoffMillis(weeks)
        val cutoffWeekKey = weekKeyOf(cutoffMillis)
        val cutoffDayKey = dayKeyOf(cutoffMillis)

        // 1. Einträge vor dem Stichtag inkl. Foto-Dateien löschen
        val stale = historyDao.getAll().map { it.toDomain() }
            .filter { it.dateMillis < cutoffMillis }
        stale.forEach { entry ->
            photoStorage.deletePhoto(entry.photoFile)
            historyDao.deleteById(entry.id)
        }

        // 2. Tages-/Wochenfazits vor dem Stichtag löschen (inkl. verwaister)
        dayFazitDao.getAll().map { it.dayKey }
            .filter { it < cutoffDayKey }
            .forEach { dayFazitDao.delete(it) }
        weekFazitDao.getAll().map { it.weekKey }
            .filter { it < cutoffWeekKey }
            .forEach { weekFazitDao.delete(it) }

        // 3. Session-Guard aufräumen (damit Fazits neuer Tage nicht irrtümlich „versucht" bleiben)
        DayFazitSession.attempted.removeAll { it < cutoffDayKey }
        DayFazitSession.attemptedWeekly.removeAll { it < cutoffWeekKey }

        return stale.size
    }

    override suspend fun saveDayFazit(fazit: DayFazit) {
        ensureMigrated()
        dayFazitDao.insert(fazit.toEntity())
    }

    override suspend fun loadDayFazit(dayKey: String): DayFazit? {
        ensureMigrated()
        return dayFazitDao.getByKey(dayKey)?.toDomain()
    }

    override suspend fun loadAllDayFazits(): List<DayFazit> {
        ensureMigrated()
        return dayFazitDao.getAll().map { it.toDomain() }
    }

    override suspend fun saveAllDayFazits(fazits: List<DayFazit>) {
        if (fazits.isEmpty()) return
        ensureMigrated()
        dayFazitDao.insertAll(fazits.map { it.toEntity() })
    }

    /** Fazit eines Tags löschen (z. B. weil ein Eintrag des Tages gelöscht wurde). */
    override suspend fun deleteDayFazitForEntry(entry: HistoryEntry) {
        dayFazitDao.delete(dayKeyOf(entry.dateMillis))
    }

    override suspend fun saveWeekFazit(fazit: DayFazit) {
        ensureMigrated()
        weekFazitDao.insert(fazit.toWeekEntity())
    }

    override suspend fun loadWeekFazit(weekKey: String): DayFazit? {
        ensureMigrated()
        return weekFazitDao.getByKey(weekKey)?.toDomain()
    }

    override suspend fun loadAllWeekFazits(): List<DayFazit> {
        ensureMigrated()
        return weekFazitDao.getAll().map { it.toDomain() }
    }

    override suspend fun saveAllWeekFazits(fazits: List<DayFazit>) {
        if (fazits.isEmpty()) return
        ensureMigrated()
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

    override fun decodeThumbnail(entry: HistoryEntry, target: Int): Bitmap? =
        photoFileOf(entry)?.let { photoStorage.decodeThumb(it, target) }
}
