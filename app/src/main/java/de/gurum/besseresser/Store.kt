package de.gurum.besseresser

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Eine gemeinsame Prefs-Datei für Einstellungen UND Profil (lokal auf dem Gerät). */
internal const val BETTERESSER_PREFS = "besseresser_prefs"

/** Persistenz der Einstellungen in SharedPreferences; API-Key via KeyVault verschlüsselt. */
object Store {
    private const val PREFS = BETTERESSER_PREFS

    fun load(ctx: Context): Settings {
        val p: SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val goals = p.getString("goals", "")!!.split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return Settings(
            baseUrl = p.getString("baseUrl", "")!!,
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
data class Profile(
    val birthDate: String? = null,  // ISO yyyy-MM-dd
    val weightKg: Double? = null,
    val heightCm: Int? = null,
    val notes: String = "",
    val gender: String = ""         // "" = keine Angabe; "male" | "female" | "diverse"
) {
    /** Alter in Jahren – lokal aus dem Geburtsdatum berechnet (veraltet nie). */
    val ageYears: Int?
        get() = birthDate?.let {
            runCatching {
                java.time.Period.between(java.time.LocalDate.parse(it), java.time.LocalDate.now()).years
            }.getOrNull()
        }

    val isEmpty: Boolean
        get() = birthDate == null && weightKg == null && heightCm == null && notes.isBlank() && gender.isBlank()
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
            gender = p.getString("profileGender", "") ?: ""
        )
    }

    /** Leere Eingaben speichern als "nicht gesetzt" – das Profil darf komplett leer bleiben. */
    fun save(
        ctx: Context,
        birthDateIso: String?,
        weightKg: Double?,
        heightCm: Int?,
        notes: String,
        gender: String = ""
    ) {
        ctx.getSharedPreferences(BETTERESSER_PREFS, Context.MODE_PRIVATE).edit()
            .putString("birthDate", birthDateIso?.takeIf { it.isNotBlank() })
            .putString("profileWeight", weightKg?.toString())
            .putInt("profileHeight", heightCm ?: -1)
            .putString("profileNotes", notes.trim())
            .putString("profileGender", gender.takeIf { it in setOf("male", "female", "diverse") } ?: "")
            .apply()
    }
}

// ----------------------------------------------------------------- Notiz-Schnellauswahl (MRU + Favoriten)

/** Eintrag der Schnellauswahl-Liste: Text + Favoriten-Sternchen.
 *  Sternchen = dauerhaft in der Liste (bis abgewählt); ohne Stern rotieren max. 5 nach MRU. */
data class RecentNote(val text: String, val starred: Boolean = false)

object RecentNotesStore {
    private const val KEY = "recentNotes"
    private const val MAX_UNSTARRED = 5
    private const val MAX_TOTAL = 40

    /** Liest die Liste. sortStarredFirst = true (Standard): Sternchen zuerst, dann unmarkierte
     *  (MRU-Anzeige beim Öffnen). false = exakt die gespeicherte Reihenfolge — für den
     *  Stern-Klick inPlace (kein Hüpfen beim Markieren). Unmarkierte immer max. 5. */
    fun load(ctx: Context, sortStarredFirst: Boolean = true): List<RecentNote> = runCatching {
        val raw = ctx.getSharedPreferences(BETTERESSER_PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        val arr = JSONArray(raw)
        val parsed = (0 until arr.length()).mapNotNull { i ->
            val o = arr.opt(i)
            when (o) {
                is String -> RecentNote(o, false)   // Alt-Format ohne Sternchen
                is JSONObject -> {
                    val t = o.optString("t")
                    if (t.isNotBlank()) RecentNote(t, o.optBoolean("s", false)) else null
                }
                else -> null
            }
        }
        if (sortStarredFirst) {
            val starred = parsed.filter { it.starred }
            val unstarred = parsed.filter { !it.starred }.take(MAX_UNSTARRED)
            starred + unstarred
        } else {
            val capped = parsed.partition { it.starred }
            // Positionen BLIEBEN; die 5er-Grenze für unmarkierte greift trotzdem
            val keepLast = capped.second.take(MAX_UNSTARRED)
            capped.first + keepLast
        }
    }.getOrDefault(emptyList())

    /** Nach vorne stellen (Dedup); unmarkierte rotieren max. 5 nach MRU, älteste fliegt raus.
     *  Sternchen-Einträge bleiben immer erhalten (bis abgewählt). */
    fun push(ctx: Context, note: String) {
        val t = note.trim()
        if (t.isBlank()) return
        val all = load(ctx)
        val starred = all.filter { it.starred && it.text != t }
        val unstarred = listOf(RecentNote(t, false)) + all.filter { !it.starred && it.text != t }
        write(ctx, starred + unstarred.take(MAX_UNSTARRED))
    }

    /** Sternchen umschalten (beim Antippen des Sterns im Dropdown) — OHNE Neusortierung:
     *  der Eintrag bleibt an seiner Stelle stehen; die Anzeige sortiert (Sternchen oben)
     *  erst beim nächsten Öffnen der Liste. Persistenz folgt der neuen Markierung. */
    fun toggleStar(ctx: Context, noteText: String) {
        val t = noteText.trim()
        if (t.isBlank()) return
        val list = load(ctx).map {
            if (it.text == t) RecentNote(it.text, !it.starred) else it
        }
        // Beim ABWÄHLEN gilt die 5er-MRU-Regel: der frisch freigestellte Eintrag ist der
        // neueste an seiner Position – die ÄLTESTE unmarkierte fliegt (erst beim nächsten
        // Öffnen sichtbar) raus.
        write(ctx, list)
    }

    /** Sichtbaren Zustand persistieren (vom UI geführt, damit Klicks nichts umsortieren). */
    fun save(ctx: Context, notes: List<RecentNote>) {
        write(ctx, notes)
    }

    private fun write(ctx: Context, notes: List<RecentNote>) {
        val arr = JSONArray().apply {
            notes.distinctBy { it.text }.take(MAX_TOTAL).forEach { n ->
                put(JSONObject().apply {
                    put("t", n.text)
                    put("s", n.starred)
                })
            }
        }
        ctx.getSharedPreferences(BETTERESSER_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, arr.toString())
            .apply()
    }
}
