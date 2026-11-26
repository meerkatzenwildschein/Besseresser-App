package de.gurum.besseresser.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.gurum.besseresser.model.AppJson
import de.gurum.besseresser.model.BETTERESSER_PREFS
import de.gurum.besseresser.model.Evaluation
import de.gurum.besseresser.model.RecentNote
import de.gurum.besseresser.model.RecentNotesLogic
import de.gurum.besseresser.model.Settings
import de.gurum.besseresser.model.WeightedGoal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString

// ----------------------------------------------------------------- DataStore (mit SharedPreferences-Migration)

/** Gemeinsame Preferences-DataStore für Einstellungen, Profil, Notizen und UI-Prefs.
 *  Bestehende SharedPreferences ("besseresser_prefs") werden einmalig migriert. */
val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(
    name = BETTERESSER_PREFS,
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, BETTERESSER_PREFS))
    }
)

// ----------------------------------------------------------------- Interface

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

    companion object {
        fun encodeGoalsToString(goals: List<WeightedGoal>): String {
            val validGoals = goals.filter { it.text.isNotBlank() }
            return AppJson.encodeToString(ListSerializer(WeightedGoal.serializer()), validGoals)
        }

        fun decodeGoalsFromString(raw: String?): List<WeightedGoal> {
            if (raw.isNullOrBlank()) return emptyList()
            val trimmed = raw.trim()
            if (trimmed.startsWith("[")) {
                return runCatching {
                    AppJson.decodeFromString(ListSerializer(WeightedGoal.serializer()), trimmed)
                        .filter { it.text.isNotBlank() }
                }.getOrElse { fallbackDecode(trimmed) }
            }
            return fallbackDecode(raw)
        }

        private fun fallbackDecode(raw: String): List<WeightedGoal> {
            return raw.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { WeightedGoal(it, WeightedGoal.DEFAULT_WEIGHT) }
        }
    }
}

// ----------------------------------------------------------------- Implementation

class DefaultSettingsRepository(private val dataStore: DataStore<Preferences>) : SettingsRepository {

    private object Keys {
        val BASE_URL = stringPreferencesKey("baseUrl")
        val API_KEY = stringPreferencesKey("apiKey")
        val MODEL = stringPreferencesKey("model")
        val GOALS = stringPreferencesKey("goals")
        val GOALS_WERE_SPLIT = booleanPreferencesKey("goalsWereSplit")
        val RETENTION_WEEKS = intPreferencesKey("retentionWeeks")
        val RECENT_NOTES = stringPreferencesKey("recentNotes")
        const val DEFAULT_MODEL = Settings.DEFAULT_MODEL
    }

    override val settings: Flow<Settings> = dataStore.data.map { p -> p.toSettings() }

    private fun Preferences.toSettings(): Settings {
        val goals = SettingsRepository.decodeGoalsFromString(this[Keys.GOALS])
        return Settings(
            baseUrl = this[Keys.BASE_URL] ?: "",
            apiKey = this[Keys.API_KEY]?.let { raw -> KeyVault.decrypt(raw).getOrDefault("") } ?: "",
            model = this[Keys.MODEL] ?: Keys.DEFAULT_MODEL,
            goals = goals,
            goalsWereSplit = this[Keys.GOALS_WERE_SPLIT] ?: false,
            retentionWeeks = this[Keys.RETENTION_WEEKS] ?: Settings.DEFAULT_RETENTION_WEEKS
        )
    }

    override suspend fun currentSettings(): Settings = dataStore.data.first().toSettings()

    override suspend fun save(settings: Settings) {
        val encryptedKey = KeyVault.encrypt(settings.apiKey.trim()).getOrThrow()
        dataStore.edit { p ->
            p[Keys.BASE_URL] = settings.baseUrl.trim()
            p[Keys.API_KEY] = encryptedKey
            p[Keys.MODEL] = settings.model.trim()
            p[Keys.GOALS] = SettingsRepository.encodeGoalsToString(settings.goals)
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
