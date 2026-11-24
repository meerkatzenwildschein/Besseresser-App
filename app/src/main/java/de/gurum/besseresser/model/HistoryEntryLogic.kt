package de.gurum.besseresser.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ----------------------------------------------------------------- Reine Verlaufs-Logik (kontextfrei)

/**
 * Kontextfreie Logik für Verlaufseinträge: Duplikate erzeugen und chronologische
 * Sortierpositionen innerhalb eines Tages berechnen. Persistenz: HistoryRepository.
 */
object HistoryEntryLogic {

    /** Erzeugt ein Duplikat-Domänenobjekt für das gewählte Zieldatum und die gewählte Uhrzeit. */
    fun createDuplicatedEntry(
        source: HistoryEntry,
        targetDay: LocalDate,
        targetTime: LocalTime = LocalTime.now(),
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

    /**
     * Berechnet beim Einfügen eines neuen Eintrags (mit konkreter Uhrzeit) in einen Tag konsistente
     * `sortOrder`-Werte für alle Einträge dieses Tages.
     */
    fun computeChronologicalSortOrders(
        existingDayEntries: List<HistoryEntry>,
        newEntry: HistoryEntry
    ): List<HistoryEntry> {
        if (existingDayEntries.isEmpty()) return listOf(newEntry)
        val ordered = existingDayEntries.sortedByDescending { it.effectiveSortOrder }.toMutableList()

        val firstEarlierTimedIdx = ordered.indexOfFirst { !it.backdated && it.dateMillis <= newEntry.dateMillis }
        val lastLaterTimedIdx = ordered.indexOfLast { !it.backdated && it.dateMillis > newEntry.dateMillis }
        val insertIdx = when {
            firstEarlierTimedIdx != -1 -> firstEarlierTimedIdx
            lastLaterTimedIdx != -1 -> lastLaterTimedIdx + 1
            else -> 0
        }
        ordered.add(insertIdx, newEntry)

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

/** Montag (00:00) der Woche, die [weeks] Wochen vor [today] liegt. */
fun retentionCutoffMillis(weeks: Int, today: LocalDate = LocalDate.now()): Long {
    val cutoffMonday = today.minusWeeks(weeks.toLong())
        .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
    return cutoffMonday.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

// ----------------------------------------------------------------- Tagesfazit & Schlüssel

private val DAY_KEY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")

fun dayKeyOf(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DAY_KEY_FORMAT)

fun dayKeyOf(date: LocalDate): String = date.format(DAY_KEY_FORMAT)

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

fun calendarWeekOf(date: LocalDate = LocalDate.now()): Int =
    date.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())

fun calendarWeekOfKey(weekKey: String): Int =
    weekMondayOfKey(weekKey)?.let { calendarWeekOf(it) } ?: calendarWeekOf()

fun dayFingerprint(entries: List<HistoryEntry>): String =
    entries.filter { it.isRated }.sortedBy { it.id }.joinToString("|") { "${it.id}:${it.recognizedMeal}" }.hashCode().toString(16)
