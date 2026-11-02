package de.gurum.besseresser

import android.content.Context
import android.content.SharedPreferences

/** Persistenz der Einstellungen in SharedPreferences; API-Key via KeyVault verschlüsselt. */
object Store {
    private const val PREFS = "besseresser_prefs"

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

/** Nutzerprofil: alles optional (Alter/Gewicht/Größe + Freitext wie Vorerkrankungen). */
data class Profile(
    val ageYears: Int? = null,
    val weightKg: Double? = null,
    val heightCm: Int? = null,
    val notes: String = ""
) {
    val isEmpty: Boolean
        get() = ageYears == null && weightKg == null && heightCm == null && notes.isBlank()
}

object ProfileStore {
    fun load(ctx: Context): Profile {
        val p: SharedPreferences = ctx.getSharedPreferences("besseresser_prefs", Context.MODE_PRIVATE)
        return Profile(
            ageYears = p.getInt("profileAge", -1).takeIf { it > 0 },
            weightKg = p.getString("profileWeight", null)
                ?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 },
            heightCm = p.getInt("profileHeight", -1).takeIf { it > 0 },
            notes = p.getString("profileNotes", "") ?: ""
        )
    }

    /** Leere Eingaben speichern als "nicht gesetzt" – das Profil darf komplett leer bleiben. */
    fun save(
        ctx: Context,
        ageYears: Int?,
        weightKg: Double?,
        heightCm: Int?,
        notes: String
    ) {
        ctx.getSharedPreferences("besseresser_prefs", Context.MODE_PRIVATE).edit()
            .putInt("profileAge", ageYears ?: -1)
            .putString("profileWeight", weightKg?.toString())
            .putInt("profileHeight", heightCm ?: -1)
            .putString("profileNotes", notes.trim())
            .apply()
    }
}
