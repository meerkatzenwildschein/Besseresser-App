package de.gurum.besseresser

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Eine gemeinsame Prefs-Datei für Einstellungen UND Profil (lokal auf dem Gerät). */
internal const val BETTERESSER_PREFS = "besseresser_prefs"

/** Persistenz der Einstellungen in SharedPreferences; API-Key via KeyVault verschlüsselt. */
object Store {
    private const val PREFS = BETTERESSER_PREFS

    fun load(ctx: Context): Settings {
        val p: SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val goals = (p.getString("goals", "") ?: "").split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return Settings(
            baseUrl = p.getString("baseUrl", "") ?: "",
            apiKey = p.getString("apiKey", null)?.let { KeyVault.decrypt(it) } ?: "",
            model = p.getString("model", "gpt-4o-mini") ?: "gpt-4o-mini",
            goals = goals,
            goalsWereSplit = p.getBoolean("goalsWereSplit", false)
        )
    }

    fun save(ctx: Context, s: Settings) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("baseUrl", s.baseUrl.trim())
            .putString("apiKey", KeyVault.encrypt(s.apiKey.trim()))
            .putString("model", s.model.trim())
            .putString("goals", s.goals.joinToString("\n"))
            .putBoolean("goalsWereSplit", s.goalsWereSplit)
            .apply()
    }
}

// ----------------------------------------------------------------- Profil

/** Nutzerprofil: alles optional. Geburtsdatum (ISO yyyy-MM-dd, veraltet nicht) statt Alter. */
@Serializable
data class Profile(
    val birthDate: String? = null,  // ISO yyyy-MM-dd
    val weightKg: Double? = null,
    val heightCm: Int? = null,
    val notes: String = "",
    val gender: Gender = Gender.NONE
) {
    /** Alter in Jahren – lokal aus dem Geburtsdatum berechnet (veraltet nie). */
    val ageYears: Int?
        get() = birthDate?.let {
            runCatching {
                java.time.Period.between(java.time.LocalDate.parse(it), java.time.LocalDate.now()).years
            }.getOrNull()
        }

    val isEmpty: Boolean
        get() = birthDate == null && weightKg == null && heightCm == null && notes.isBlank() && gender == Gender.NONE
}

object ProfileStore {
    fun load(ctx: Context): Profile {
        val p: SharedPreferences = ctx.getSharedPreferences(BETTERESSER_PREFS, Context.MODE_PRIVATE)
        return Profile(
            birthDate = p.getString("birthDate", null)?.takeIf { it.isNotBlank() },
            weightKg = p.getString("profileWeight", null)
                ?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 },
            heightCm = p.getInt("profileHeight", -1).takeIf { it > 0 },
            notes = p.getString("profileNotes", "") ?: "",
            gender = Gender.fromString(p.getString("profileGender", null))
        )
    }

    /** Leere Eingaben speichern als "nicht gesetzt" – das Profil darf komplett leer bleiben. */
    fun save(
        ctx: Context,
        birthDateIso: String?,
        weightKg: Double?,
        heightCm: Int?,
        notes: String,
        gender: Gender = Gender.NONE
    ) {
        ctx.getSharedPreferences(BETTERESSER_PREFS, Context.MODE_PRIVATE).edit()
            .putString("birthDate", birthDateIso?.takeIf { it.isNotBlank() })
            .putString("profileWeight", weightKg?.toString())
            .putInt("profileHeight", heightCm ?: -1)
            .putString("profileNotes", notes.trim())
            .putString("profileGender", gender.rawValue)
            .apply()
    }
}

// ----------------------------------------------------------------- Notiz-Schnellauswahl (MRU + Favoriten)

/** Eintrag der Schnellauswahl-Liste: Text + Favoriten-Sternchen + optional gecachtes KI-Ergebnis.
 *  Sternchen = dauerhaft in der Liste (bis abgewählt); ohne Stern rotieren max. 5 nach MRU.
 *  Fällt ein unmarkierter Eintrag aus den Top 5 heraus, wird auch sein persistiertes KI-Ergebnis entfernt. */
@Serializable
data class RecentNote(
    @SerialName("t")
    val text: String,
    @SerialName("s")
    val starred: Boolean = false,
    @SerialName("ev")
    val evaluation: Evaluation? = null
)

object RecentNotesStore {
    private const val KEY = "recentNotes"
    internal const val MAX_UNSTARRED = 5
    internal const val MAX_TOTAL = 40

    /** Liest die Liste. sortStarredFirst = true (Standard): Sternchen zuerst, dann unmarkierte
     *  (MRU-Anzeige beim Öffnen). false = exakt die gespeicherte Reihenfolge — für den
     *  Stern-Klick inPlace (kein Hüpfen beim Markieren). Unmarkierte immer max. 5. */
    fun load(ctx: Context, sortStarredFirst: Boolean = true): List<RecentNote> = runCatching {
        val raw = ctx.getSharedPreferences(BETTERESSER_PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        val parsed = parseRecentNotes(raw)
        formatLoadedNotes(parsed, sortStarredFirst)
    }.getOrDefault(emptyList())

    internal fun formatLoadedNotes(parsed: List<RecentNote>, sortStarredFirst: Boolean = true): List<RecentNote> {
        return if (sortStarredFirst) {
            val starred = parsed.filter { it.starred }
            val unstarred = parsed.filter { !it.starred }.take(MAX_UNSTARRED)
            starred + unstarred
        } else {
            var unstarredCount = 0
            parsed.filter {
                if (it.starred) true
                else {
                    unstarredCount++
                    unstarredCount <= MAX_UNSTARRED
                }
            }
        }
    }

    internal fun parseRecentNotes(raw: String): List<RecentNote> {
        return try {
            AppJson.decodeFromString<List<RecentNote>>(raw)
        } catch (e: Exception) {
            try {
                val element = AppJson.parseToJsonElement(raw)
                if (element is JsonArray) {
                    element.mapNotNull { item ->
                        when (item) {
                            is JsonPrimitive -> item.contentOrNull?.trim()?.takeIf { it.isNotBlank() }?.let { RecentNote(it, false) }
                            is JsonObject -> {
                                val text = ((item["t"] ?: item["text"])?.jsonPrimitive?.contentOrNull ?: "").trim()
                                val sPrimitive = (item["s"] ?: item["starred"])?.jsonPrimitive
                                val starred = sPrimitive?.booleanOrNull
                                    ?: (sPrimitive?.intOrNull == 1)
                                    ?: (sPrimitive?.contentOrNull?.equals("true", ignoreCase = true) == true)
                                val evalElem = item["ev"] ?: item["evaluation"]
                                val ev = evalElem?.let {
                                    runCatching { AppJson.decodeFromJsonElement(Evaluation.serializer(), it) }.getOrNull()
                                }
                                if (text.isNotBlank()) RecentNote(text, starred, ev) else null
                            }
                            else -> null
                        }
                    }
                } else emptyList()
            } catch (e2: Exception) {
                emptyList()
            }
        }
    }

    /** Reine Listen-Logik für push: Nach vorne stellen (Dedup); unmarkierte rotieren max. 5 nach MRU,
     *  älteste fliegt (inkl. persistierter KI-Antwort) raus.
     *  Sternchen-Einträge bleiben dauerhaft erhalten und verlieren ihren Stern keinesfalls.
     *  Wird [evaluation] übergeben, wird sie am Eintrag gespeichert; sonst bleibt eine vorhandene erhalten. */
    internal fun pushNote(
        all: List<RecentNote>,
        note: String,
        evaluation: Evaluation? = null
    ): List<RecentNote> {
        val t = note.trim()
        if (t.isBlank()) return normalizeAndPrune(all)
        val existing = all.firstOrNull { it.text == t }
        val isStarred = existing?.starred == true
        val effectiveEv = evaluation ?: existing?.evaluation
        val updatedEntry = RecentNote(text = t, starred = isStarred, evaluation = effectiveEv)

        return if (isStarred) {
            // Bereits favorisiert: bleibt favorisiert und rückt bei den Favoriten ganz nach oben
            val starred = listOf(updatedEntry) + all.filter { it.starred && it.text != t }
            val unstarred = all.filter { !it.starred && it.text != t }
            normalizeAndPrune(starred + unstarred.take(MAX_UNSTARRED))
        } else {
            // Unmarkiert: rückt in den MRU-Bereich nach oben, älteste unmarkierte fliegt ab 5 raus
            val starred = all.filter { it.starred }
            val unstarred = listOf(updatedEntry) + all.filter { !it.starred && it.text != t }
            normalizeAndPrune(starred + unstarred.take(MAX_UNSTARRED))
        }
    }

    /** Nach vorne stellen (Dedup); unmarkierte rotieren max. 5 nach MRU, älteste fliegt raus.
     *  Sternchen-Einträge bleiben dauerhaft erhalten (bis abgewählt) und verlieren ihren Stern
     *  auch bei erneuter Nutzung / Speicherung keinesfalls. */
    fun push(ctx: Context, note: String, evaluation: Evaluation? = null) {
        val t = note.trim()
        if (t.isBlank()) return
        val all = load(ctx, sortStarredFirst = false)
        write(ctx, pushNote(all, t, evaluation))
    }

    internal fun toggleStarInList(all: List<RecentNote>, noteText: String): List<RecentNote> {
        val t = noteText.trim()
        if (t.isBlank()) return all
        return normalizeAndPrune(
            all.map {
                if (it.text == t) it.copy(starred = !it.starred) else it
            }
        )
    }

    /** Sternchen umschalten (beim Antippen des Sterns im Dropdown) — OHNE Neusortierung:
     *  der Eintrag bleibt an seiner Stelle stehen; die Anzeige sortiert (Sternchen oben)
     *  erst beim nächsten Öffnen der Liste. Persistenz folgt der neuen Markierung. */
    fun toggleStar(ctx: Context, noteText: String) {
        val t = noteText.trim()
        if (t.isBlank()) return
        val list = toggleStarInList(load(ctx, sortStarredFirst = false), t)
        write(ctx, list)
    }

    /** Aktualisiert die gespeicherte KI-Antwort eines bestehenden Menü-Eintrags (z. B. nach Korrektur
     *  des erkannt-Textes oder Re-Evaluierung), ohne Reihenfolge oder Stern-Status zu ändern. */
    internal fun updateEvaluationInList(
        all: List<RecentNote>,
        note: String,
        evaluation: Evaluation
    ): List<RecentNote> {
        val t = note.trim()
        if (t.isBlank()) return all
        return all.map {
            if (it.text == t) it.copy(evaluation = evaluation) else it
        }
    }

    fun updateEvaluation(ctx: Context, note: String, evaluation: Evaluation) {
        val t = note.trim()
        if (t.isBlank()) return
        val current = load(ctx, sortStarredFirst = false)
        if (current.none { it.text == t }) return
        write(ctx, updateEvaluationInList(current, t, evaluation))
    }

    /** Sucht nach einem exakten Text-Match (getrimmt) in [notes] und liefert dessen gespeichertes
     *  KI-Ergebnis zurück, sofern es vorhanden ist und zu den aktuellen [goals] passt. */
    internal fun findMatchingEvaluation(
        notes: List<RecentNote>,
        note: String,
        goals: List<String>
    ): Evaluation? {
        val t = note.trim()
        if (t.isBlank()) return null
        val match = notes.firstOrNull { it.text == t } ?: return null
        val ev = match.evaluation ?: return null
        return if (ev.matchesGoals(goals)) ev else null
    }

    /** Sucht in den gespeicherten Menü-Einträgen nach einer passenden KI-Bewertung für [note] und [goals]. */
    fun findCachedEvaluation(ctx: Context, note: String, goals: List<String>): Evaluation? =
        findMatchingEvaluation(load(ctx, sortStarredFirst = false), note, goals)

    /** Sichtbaren Zustand persistieren (vom UI geführt, damit Klicks nichts umsortieren). */
    fun save(ctx: Context, notes: List<RecentNote>) {
        write(ctx, notes)
    }

    internal fun normalizeAndPrune(notes: List<RecentNote>): List<RecentNote> {
        var unstarredCount = 0
        return notes
            .map { it.copy(text = it.text.trim()) }
            .filter { it.text.isNotBlank() }
            .distinctBy { it.text }
            .filter {
                if (it.starred) true
                else {
                    unstarredCount++
                    unstarredCount <= MAX_UNSTARRED
                }
            }
            .take(MAX_TOTAL)
    }

    private fun write(ctx: Context, notes: List<RecentNote>) {
        val items = normalizeAndPrune(notes)
        val json = AppJson.encodeToString(items)
        ctx.getSharedPreferences(BETTERESSER_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, json)
            .commit()
    }
}

// ----------------------------------------------------------------- Sprache & Theme (UI-Einstellungen)

object UiPrefsStore {
    private const val KEY_THEME = "appThemeMode"
    private const val KEY_LANG = "appLanguage"

    fun loadThemeMode(ctx: Context): AppThemeMode {
        val raw = ctx.getSharedPreferences(BETTERESSER_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, null)
        return AppThemeMode.fromString(raw)
    }

    fun saveThemeMode(ctx: Context, mode: AppThemeMode) {
        ctx.getSharedPreferences(BETTERESSER_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_THEME, mode.rawValue)
            .apply()
    }

    fun loadLanguage(ctx: Context): AppLanguage {
        val raw = ctx.getSharedPreferences(BETTERESSER_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANG, null)
        return AppLanguage.resolve(raw)
    }

    fun saveLanguage(ctx: Context, language: AppLanguage) {
        ctx.getSharedPreferences(BETTERESSER_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LANG, language.rawValue)
            .apply()
    }
}

/** Erzeugt einen ContextWrapper mit der gewählten App-Sprache, behält aber den Basis-Activity-Context bei. */
fun Context.withAppLanguage(language: AppLanguage): Context {
    val targetLocale = language.locale
    java.util.Locale.setDefault(targetLocale)
    val config = android.content.res.Configuration(resources.configuration)
    config.setLocale(targetLocale)
    config.setLocales(android.os.LocaleList(targetLocale))
    val localized = createConfigurationContext(config)
    return object : android.content.ContextWrapper(this) {
        override fun getResources(): android.content.res.Resources = localized.resources
        override fun getAssets(): android.content.res.AssetManager = localized.assets
    }
}
