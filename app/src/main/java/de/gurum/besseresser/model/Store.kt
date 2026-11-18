package de.gurum.besseresser.model
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

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

/** Eine gemeinsame Prefs-Datei für Einstellungen UND Profil (lokal auf dem Gerät).
 *  (Name wird auch als DataStore-Dateiname und für die SharedPreferences-Migration genutzt.) */
const val BETTERESSER_PREFS = "besseresser_prefs"

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

/** Reine Listen-Logik der Notiz-Schnellauswahl (komplett kontextfrei, direkt testbar).
 *  Persistenz (DataStore) übernimmt das SettingsRepository. */
object RecentNotesLogic {
    internal const val MAX_UNSTARRED = 5
    internal const val MAX_TOTAL = 40

    /** Anzeigereihenfolge. sortStarredFirst = true (Standard): Sternchen zuerst, dann unmarkierte
     *  (MRU-Anzeige beim Öffnen). false = exakt die gespeicherte Reihenfolge — für den
     *  Stern-Klick inPlace (kein Hüpfen beim Markieren). Unmarkierte immer max. 5. */
    fun formatLoadedNotes(parsed: List<RecentNote>, sortStarredFirst: Boolean = true): List<RecentNote> {
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

    /** Tolerantes Parsen des persistierten JSON (inkl. Legacy-Formaten: String-Arrays,
     *  ausgeschriebene Feldnamen, Bool als Int/String). */
    fun parseRecentNotes(raw: String): List<RecentNote> {
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
                                    ?: (sPrimitive?.intOrNull?.let { it == 1 })
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
    fun pushNote(
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

    fun toggleStarInList(all: List<RecentNote>, noteText: String): List<RecentNote> {
        val t = noteText.trim()
        if (t.isBlank()) return all
        return normalizeAndPrune(
            all.map {
                if (it.text == t) it.copy(starred = !it.starred) else it
            }
        )
    }

    /** Aktualisiert die gespeicherte KI-Antwort eines bestehenden Menü-Eintrags (z. B. nach Korrektur
     *  des erkannt-Textes oder Re-Evaluierung), ohne Reihenfolge oder Stern-Status zu ändern. */
    fun updateEvaluationInList(
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

    /** Sucht nach einem exakten Text-Match (getrimmt) in [notes] und liefert dessen gespeichertes
     *  KI-Ergebnis zurück, sofern es vorhanden ist und zu den aktuellen [goals] passt. */
    fun findMatchingEvaluation(
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

    fun normalizeAndPrune(notes: List<RecentNote>): List<RecentNote> {
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
}

