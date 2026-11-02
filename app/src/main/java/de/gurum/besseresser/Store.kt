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

/** Nutzerprofil: alles optional. Geburtsdatum (ISO yyyy-MM-dd, veraltet nicht) statt Alter. */
data class Profile(
    val birthDate: String? = null,  // ISO yyyy-MM-dd
    val weightKg: Double? = null,
    val heightCm: Int? = null,
    val notes: String = ""
) {
    /** Alter in Jahren – lokal aus dem Geburtsdatum berechnet (veraltet nie). */
    val ageYears: Int?
        get() = birthDate?.let {
            runCatching {
                java.time.Period.between(java.time.LocalDate.parse(it), java.time.LocalDate.now()).years
            }.getOrNull()
        }

    val isEmpty: Boolean
        get() = birthDate == null && weightKg == null && heightCm == null && notes.isBlank()
}

object ProfileStore {
    fun load(ctx: Context): Profile {
        val p: SharedPreferences = ctx.getSharedPreferences("besseresser_prefs", Context.MODE_PRIVATE)
        return Profile(
            birthDate = p.getString("birthDate", null)?.takeIf { it.isNotBlank() },
            weightKg = p.getString("profileWeight", null)
                ?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 },
            heightCm = p.getInt("profileHeight", -1).takeIf { it > 0 },
            notes = p.getString("profileNotes", "") ?: ""
        )
    }

    /** Leere Eingaben speichern als "nicht gesetzt" – das Profil darf komplett leer bleiben. */
    fun save(
        ctx: Context,
        birthDateIso: String?,
        weightKg: Double?,
        heightCm: Int?,
        notes: String
    ) {
        ctx.getSharedPreferences("besseresser_prefs", Context.MODE_PRIVATE).edit()
            .putString("birthDate", birthDateIso?.takeIf { it.isNotBlank() })
            .putString("profileWeight", weightKg?.toString())
            .putInt("profileHeight", heightCm ?: -1)
            .putString("profileNotes", notes.trim())
            .apply()
    }
}
