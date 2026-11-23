package de.gurum.besseresser.model
import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

// ----------------------------------------------------------------- Reine Verlaufs-Logik (kontextfrei)

/** Kontextfreie Logik für Verlaufseinträge: Duplikate erzeugen und chronologische
 *  Sortierpositionen innerhalb eines Tages berechnen. Persistenz: HistoryRepository. */
object HistoryEntryLogic {

    /** Erzeugt ein Duplikat-Domänenobjekt für das gewählte Zieldatum und die gewählte Uhrzeit. */
    fun createDuplicatedEntry(
        source: HistoryEntry,
        targetDay: LocalDate,
        targetTime: java.time.LocalTime = java.time.LocalTime.now(),
        newId: Long = System.currentTimeMillis(),
        newPhotoFile: String = ""
    ): HistoryEntry {
        val targetMillis = targetDay
            .atTime(targetTime.hour, targetTime.minute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return source.copy(
            id = newId,
            dateMillis = targetMillis,
            photoFile = newPhotoFile,
            backdated = false,
            sortOrder = targetMillis
        )
    }

    /** Berechnet beim Einfügen eines neuen Eintrags (mit konkreter Uhrzeit) in einen Tag konsistente
     *  `sortOrder`-Werte für alle Einträge dieses Tages:
     *  Der neue Eintrag wird anhand seiner echten Uhrzeit (`dateMillis`) an der chronologisch richtigen
     *  Stelle zwischen den bestehenden Einträgen des Tages eingereiht, auch wenn bestehende Einträge
     *  zuvor manuell verschoben wurden oder als altes `backdated` (23:59) gespeichert waren. */
    fun computeChronologicalSortOrders(
        existingDayEntries: List<HistoryEntry>,
        newEntry: HistoryEntry
    ): List<HistoryEntry> {
        if (existingDayEntries.isEmpty()) return listOf(newEntry)
        // Bestehende Einträge in ihrer aktuellen Anzeigereihenfolge (oben = Index 0 = späteste Position)
        val ordered = existingDayEntries.sortedByDescending { it.effectiveSortOrder }.toMutableList()

        // Suche die chronologisch passende Einfügeposition anhand der echten Uhrzeiten (!backdated):
        // - Vor dem ersten Eintrag mit echter Uhrzeit <= newEntry.dateMillis
        // - Falls es Einträge mit echter Uhrzeit > newEntry.dateMillis gibt, direkt hinter dem letzten davon
        // - Falls der Tag bisher nur alte backdated-Einträge (ohne Uhrzeit) hat, kommt der neue Eintrag mit Uhrzeit nach oben (Index 0)
        val firstEarlierTimedIdx = ordered.indexOfFirst { !it.backdated && it.dateMillis <= newEntry.dateMillis }
        val lastLaterTimedIdx = ordered.indexOfLast { !it.backdated && it.dateMillis > newEntry.dateMillis }
        val insertIdx = when {
            firstEarlierTimedIdx != -1 -> firstEarlierTimedIdx
            lastLaterTimedIdx != -1 -> lastLaterTimedIdx + 1
            else -> 0
        }
        ordered.add(insertIdx, newEntry)

        // Allen Einträgen streng absteigende sortOrder-Werte zuweisen (Basis: absteigend sortierte Zeitstempel)
        val baseSortOrders = ordered.map { maxOf(it.effectiveSortOrder, it.dateMillis) }
            .sortedDescending()
            .toMutableList()
        for (i in 1 until baseSortOrders.size) {
            if (baseSortOrders[i] >= baseSortOrders[i - 1]) {
                baseSortOrders[i] = baseSortOrders[i - 1] - 1000L
            }
        }
        return ordered.mapIndexed { idx, e -> e.copy(sortOrder = baseSortOrders[idx]) }
    }
}

// ----------------------------------------------------------------- Historien-Begrenzung (retention)

/** Montag (00:00) der Woche, die [weeks] Wochen vor [today] liegt – Einträge/Fazits
 *  vor diesem Zeitpunkt gelten als „älter als [weeks] Wochen" und werden bereinigt. */
fun retentionCutoffMillis(weeks: Int, today: LocalDate = LocalDate.now()): Long {
    val cutoffMonday = today.minusWeeks(weeks.toLong())
        .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
    return cutoffMonday.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

// ----------------------------------------------------------------- Tagesfazit

/** Lokaler Tagesschlüssel (yyyyyyyyMMdd, Systemzeitzone) – Basis für Tagesgruppen und Fazit-Dateien. */
private val DAY_KEY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")

fun dayKeyOf(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DAY_KEY_FORMAT)

fun dayKeyOf(date: LocalDate): String = date.format(DAY_KEY_FORMAT)

/** Wochen-Key = ISO-Montag der Woche, in der das Datum liegt (Format yyyyMMdd). */
fun weekKeyOf(millis: Long): String {
    val day = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    return weekKeyOf(day)
}

fun weekKeyOf(day: LocalDate): String {
    val monday = day.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
    return monday.format(DAY_KEY_FORMAT)
}

fun weekMondayOfKey(weekKey: String): LocalDate? = runCatching {
    LocalDate.parse(weekKey.removePrefix("week_"), DAY_KEY_FORMAT)
}.getOrNull()

/** ISO-Kalenderwoche (1..53) für ein gegebenes Datum. */
fun calendarWeekOf(date: LocalDate = LocalDate.now()): Int =
    date.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())

/** ISO-Kalenderwoche (1..53) zu einem Wochen-Key (yyyyMMdd). */
fun calendarWeekOfKey(weekKey: String): Int =
    weekMondayOfKey(weekKey)?.let { calendarWeekOf(it) } ?: calendarWeekOf()

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
}

/** „Quellen-Fingerprint" eines Tages: ID+recognizedMeal je bewertetem Eintrag. Unbewertete Einträge
 *  (`!isRated`) werden ignoriert. Bearbeitete recognizedMeal-Texte oder gelöschte/ergänzte bewertete
 *  Einträge machen jedes Tagesfazit auf dieser Basis veraltet. */
fun dayFingerprint(entries: List<HistoryEntry>): String =
    entries.filter { it.isRated }.sortedBy { it.id }.joinToString("|") { "${it.id}:${it.recognizedMeal}" }.hashCode().toString(16)
