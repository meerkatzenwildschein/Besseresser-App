package de.gurum.besseresser

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

// ----------------------------------------------------------------- Repository Interfaces

interface SettingsRepository {
    fun load(): Settings
    fun save(settings: Settings)
    fun loadRecentNotes(sortStarredFirst: Boolean = true): List<RecentNote>
    fun pushRecentNote(note: String)
    fun toggleStarRecentNote(noteText: String)
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
    suspend fun add(photoUri: Uri?, ev: Evaluation, note: String = "", dateOverride: Long? = null, backdated: Boolean = false): HistoryEntry
    suspend fun updateErkannt(id: Long, newText: String)
    suspend fun updateEvaluation(id: Long, goalResults: List<GoalResult>, summary: String)
    suspend fun delete(entry: HistoryEntry)
    suspend fun saveDayFazit(fazit: DayFazit)
    suspend fun loadDayFazit(dayKey: String): DayFazit?
    suspend fun deleteDayFazitForEntry(entry: HistoryEntry)
    suspend fun saveWeekFazit(fazit: DayFazit)
    suspend fun loadWeekFazit(weekKey: String): DayFazit?
    suspend fun deleteWeekFazit(weekKey: String)
    suspend fun deleteWeekFazitForEntry(entry: HistoryEntry)
    fun photoFileOf(entry: HistoryEntry): File?
    fun decodeThumbnail(entry: HistoryEntry, target: Int = 200): Bitmap?
}

// ----------------------------------------------------------------- Default Implementations

class DefaultSettingsRepository(private val context: Context) : SettingsRepository {
    override fun load(): Settings = Store.load(context)
    override fun save(settings: Settings) = Store.save(context, settings)
    override fun loadRecentNotes(sortStarredFirst: Boolean): List<RecentNote> =
        RecentNotesStore.load(context, sortStarredFirst)
    override fun pushRecentNote(note: String) = RecentNotesStore.push(context, note)
    override fun toggleStarRecentNote(noteText: String) = RecentNotesStore.toggleStar(context, noteText)
    override fun saveRecentNotes(notes: List<RecentNote>) = RecentNotesStore.save(context, notes)
}

class DefaultProfileRepository(private val context: Context) : ProfileRepository {
    override fun load(): Profile = ProfileStore.load(context)
    override fun save(profile: Profile) =
        ProfileStore.save(context, profile.birthDate, profile.weightKg, profile.heightCm, profile.notes, profile.gender)
}

class DefaultHistoryRepository(
    private val context: Context,
    private val db: AppDatabase = AppDatabase.getInstance(context)
) : HistoryRepository {

    override fun ensureMigrated() {
        HistoryStore.ensureMigrated(context)
        DayFazitStore.ensureMigrated(context)
        WeekFazitStore.ensureMigrated(context)
    }

    override fun getAllFlow(): Flow<List<HistoryEntry>> =
        db.historyDao().getAllFlow().map { list -> list.map { it.toDomain() } }

    override fun getDayFazitsFlow(): Flow<Map<String, DayFazit>> =
        db.dayFazitDao().getAllFlow().map { list -> list.map { it.toDomain() }.associateBy { it.dayKey } }

    override fun getWeekFazitsFlow(): Flow<Map<String, DayFazit>> =
        db.weekFazitDao().getAllFlow().map { list -> list.map { it.toDomain() }.associateBy { it.dayKey } }

    override suspend fun add(
        photoUri: Uri?,
        ev: Evaluation,
        note: String,
        dateOverride: Long?,
        backdated: Boolean
    ): HistoryEntry = withContext(Dispatchers.IO) {
        HistoryStore.add(context, photoUri, ev, note, dateOverride, backdated)
    }

    override suspend fun updateErkannt(id: Long, newText: String) = withContext(Dispatchers.IO) {
        HistoryStore.updateErkannt(context, id, newText)
    }

    override suspend fun updateEvaluation(id: Long, goalResults: List<GoalResult>, summary: String) = withContext(Dispatchers.IO) {
        HistoryStore.updateEvaluation(context, id, goalResults, summary)
    }

    override suspend fun delete(entry: HistoryEntry) = withContext(Dispatchers.IO) {
        HistoryStore.delete(context, entry)
    }

    override suspend fun saveDayFazit(fazit: DayFazit) = withContext(Dispatchers.IO) {
        DayFazitStore.save(context, fazit)
    }

    override suspend fun loadDayFazit(dayKey: String): DayFazit? = withContext(Dispatchers.IO) {
        DayFazitStore.load(context, dayKey)
    }

    override suspend fun deleteDayFazitForEntry(entry: HistoryEntry) = withContext(Dispatchers.IO) {
        DayFazitStore.deleteForEntry(context, entry)
    }

    override suspend fun saveWeekFazit(fazit: DayFazit) = withContext(Dispatchers.IO) {
        WeekFazitStore.save(context, fazit)
    }

    override suspend fun loadWeekFazit(weekKey: String): DayFazit? = withContext(Dispatchers.IO) {
        WeekFazitStore.load(context, weekKey)
    }

    override suspend fun deleteWeekFazit(weekKey: String) = withContext(Dispatchers.IO) {
        WeekFazitStore.delete(context, weekKey)
    }

    override suspend fun deleteWeekFazitForEntry(entry: HistoryEntry) = withContext(Dispatchers.IO) {
        WeekFazitStore.deleteForEntry(context, entry)
    }

    override fun photoFileOf(entry: HistoryEntry): File? =
        HistoryStore.photoFileOf(context, entry)

    override fun decodeThumbnail(entry: HistoryEntry, target: Int): Bitmap? =
        photoFileOf(entry)?.let { decodeThumb(it, target) }
}

// ----------------------------------------------------------------- Provider Helper

object RepositoryProvider {
    fun getSettingsRepository(ctx: Context): SettingsRepository =
        DefaultSettingsRepository(ctx.applicationContext)

    fun getProfileRepository(ctx: Context): ProfileRepository =
        DefaultProfileRepository(ctx.applicationContext)

    fun getHistoryRepository(ctx: Context): HistoryRepository =
        DefaultHistoryRepository(ctx.applicationContext)
}
