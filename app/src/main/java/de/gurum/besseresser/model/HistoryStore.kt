package de.gurum.besseresser.model
import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Eine gespeicherte Foto-Bewertung. */
@Immutable
@Serializable
data class HistoryEntry(
    val id: Long = 0L,
    @SerialName("datum")
    val dateMillis: Long = 0L,
    @SerialName("foto")
    val photoFile: String = "",          // Dateiname in filesDir/history ("" = kein Foto)
    @SerialName("ziele")
    val goalResults: List<GoalResult> = emptyList(),
    @SerialName("zusatz")
    val summary: String = "",
    @SerialName("anmerkung")
    val note: String = "",          // Nutzer-Anmerkung zum Foto (WhatsApp-Stil)
    @SerialName("erkannt")
    val recognizedMeal: String = "",       // Was die KI auf dem Foto erkannt hat (inkl. Mengenangabe)
    @SerialName("nacherfasst")
    val backdated: Boolean = false, // nachträglich erfasst (keine echte Uhrzeit bekannt)
    @SerialName("sortOrder")
    val sortOrder: Long = dateMillis // Sortierposition (getrennt von dateMillis, damit die echte Uhrzeit beim Verschieben erhalten bleibt)
) {
    /** Effektiver Sortierschlüssel (Fallback auf dateMillis für ältere Backups/Einträge mit sortOrder == 0). */
    val effectiveSortOrder: Long
        get() = if (sortOrder != 0L) sortOrder else dateMillis

    /** True, wenn für diesen Eintrag eine KI-Bewertung (Ziele, Gesamteindruck oder erkanntes Essen) vorliegt. */
    val isRated: Boolean
        get() = goalResults.isNotEmpty() || summary.isNotBlank() || recognizedMeal.isNotBlank()

    /** Ampel-Score 0..100 (gruen=100, gelb=50, rot=0, grau ausgeschlossen); -1 = nicht bewertbar. */
    val score: Int
        get() {
            val known = goalResults.filter { it.rating != TrafficLight.UNKNOWN }
            if (known.isEmpty()) return -1
            val avg = known.sumOf {
                when (it.rating) {
                    TrafficLight.GREEN -> 100.0
                    TrafficLight.YELLOW -> 50.0
                    else -> 0.0
                }
            } / known.size
            return avg.toInt()
        }
}

/** KI-generiertes Fazit eines Tages (Text-Ampel statt rechnerischem Tagesscore). */
@Immutable
@Serializable
data class DayFazit(
    @SerialName("tag")
    val dayKey: String = "",
    @SerialName("ampel")
    val rating: TrafficLight = TrafficLight.UNKNOWN,              // GREEN | YELLOW | RED
    @SerialName("text")
    val text: String = "",
    @SerialName("eintrage")
    val entryIds: List<Long> = emptyList(),       // Snapshot der zugrunde liegenden Einträge (= Veraltetscheck)
    @SerialName("erstellt")
    val createdAt: Long = 0L,
    @SerialName("fp")
    val fingerprint: String = ""    // Hash-Mix aus ID+recognizedMeal je Eintrag (Text-Korrekturen → veraltet)
)

/** In-Memory-Sitzungsguard: Auto-Bewertung „kein Fazit" max. 1× pro Tag und App-Session. */
object DayFazitSession {
    val attempted = mutableSetOf<String>()
    val attemptedWeekly = mutableSetOf<String>()
    val attemptedOutdated = mutableSetOf<String>()
}
