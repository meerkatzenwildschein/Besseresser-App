package de.gurum.besseresser.ui

import androidx.compose.runtime.Immutable
import de.gurum.besseresser.model.DayFazit
import de.gurum.besseresser.model.HistoryEntry
import de.gurum.besseresser.model.calendarWeekOfKey
import de.gurum.besseresser.model.dayFingerprint
import de.gurum.besseresser.model.dayKeyOf
import de.gurum.besseresser.model.weekKeyOf
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Ein Kalendertag mit allen gespeicherten Bewertungen desselben Tages. */
@Immutable
data class DayGroup(
    val day: LocalDate,
    val entries: List<HistoryEntry>
)

/** Flaches UI-Item-Modell für die funktionale und nebeneffektfreie Darstellung in LazyColumn. */
@Immutable
sealed interface HistoryUiItem {
    val key: Any

    data class WeekFazitItem(
        val weekKey: String,
        val calendarWeek: Int
    ) : HistoryUiItem {
        override val key: String get() = "week-$weekKey"
    }

    data class DayHeaderItem(
        val day: LocalDate,
        val dayKey: String,
        val entries: List<HistoryEntry>,
        val hasEntries: Boolean,
        val staleFazit: Boolean = false
    ) : HistoryUiItem {
        override val key: String get() = "day-$dayKey"
    }

    data class MealEntryItem(
        val entry: HistoryEntry,
        val day: LocalDate,
        val indexOnDay: Int,
        val totalCountOnDay: Int
    ) : HistoryUiItem {
        override val key: Long get() = entry.id
    }
}

/** Nach Kalendertag gruppieren (absteigend); Einträge innerhalb der Gruppe nach sortOrder absteigend. */
fun groupByDay(entries: List<HistoryEntry>): List<DayGroup> =
    entries.groupBy {
        Instant.ofEpochMilli(it.dateMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    }.map { (day, list) ->
        DayGroup(day = day, entries = list.sortedByDescending { it.effectiveSortOrder })
    }.sortedByDescending { it.day }

/** Erstellt eine flache, nebeneffektfreie Liste von UI-Elementen für die Historienliste mit vorberechneten Zuständen. */
fun buildHistoryUiItems(
    entries: List<HistoryEntry>,
    today: LocalDate = LocalDate.now(),
    nowHour: Int = LocalTime.now().hour,
    fazitMap: Map<String, DayFazit> = emptyMap()
): List<HistoryUiItem> {
    val weeksWithEntries = entries.map { weekKeyOf(it.dateMillis) }.toSet()
    val rawGroups = groupByDay(entries)
    val displayGroups = if (rawGroups.any { it.day == today }) {
        rawGroups
    } else {
        (listOf(DayGroup(day = today, entries = emptyList())) + rawGroups)
            .sortedByDescending { it.day }
    }

    val items = mutableListOf<HistoryUiItem>()
    val seenWeeks = mutableSetOf<String>()

    displayGroups.forEach { group ->
        val weekKey = weekKeyOf(group.day)
        if (weekKey !in seenWeeks &&
            weekKey in weeksWithEntries &&
            isWeekFazitDue(weekKey, today, nowHour)
        ) {
            seenWeeks.add(weekKey)
            items.add(
                HistoryUiItem.WeekFazitItem(
                    weekKey = weekKey,
                    calendarWeek = calendarWeekOfKey(weekKey)
                )
            )
        }

        val dayKey = dayKeyOf(group.day)
        val hasEntries = group.entries.isNotEmpty()
        val fazit = if (hasEntries) fazitMap[dayKey] else null
        val staleFazit = hasEntries && fazit != null && (
            fazit.entryIds.toSet() != group.entries.map { it.id }.toSet() ||
                (fazit.fingerprint.isNotBlank() && fazit.fingerprint != dayFingerprint(group.entries))
        )

        items.add(
            HistoryUiItem.DayHeaderItem(
                day = group.day,
                dayKey = dayKey,
                entries = group.entries,
                hasEntries = hasEntries,
                staleFazit = staleFazit
            )
        )

        group.entries.forEachIndexed { indexOnDay, entry ->
            items.add(
                HistoryUiItem.MealEntryItem(
                    entry = entry,
                    day = group.day,
                    indexOnDay = indexOnDay,
                    totalCountOnDay = group.entries.size
                )
            )
        }
    }

    return items
}
