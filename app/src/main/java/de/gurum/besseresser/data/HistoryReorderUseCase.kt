package de.gurum.besseresser.data

import de.gurum.besseresser.model.HistoryEntry
import java.time.LocalDate
import java.time.ZoneId

/**
 * Dedizierter Use-Case für das Reordering von Verlaufseinträgen innerhalb eines Tages.
 */
class HistoryReorderUseCase(
    private val historyRepository: HistoryRepository
) {
    fun reorderWithinDay(
        day: LocalDate,
        fromIndex: Int,
        toIndex: Int,
        currentEntries: List<HistoryEntry>
    ): List<HistoryEntry>? {
        if (fromIndex == toIndex) return null
        val dayEntries = currentEntries.filter {
            java.time.Instant.ofEpochMilli(it.dateMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate() == day
        }.sortedByDescending { it.effectiveSortOrder }.toMutableList()

        if (fromIndex !in dayEntries.indices || toIndex !in dayEntries.indices) return null

        val moved = dayEntries.removeAt(fromIndex)
        dayEntries.add(toIndex, moved)

        val sortOrders = dayEntries.map { it.effectiveSortOrder }.sortedDescending().toMutableList()
        for (i in 1 until sortOrders.size) {
            if (sortOrders[i] >= sortOrders[i - 1]) {
                sortOrders[i] = sortOrders[i - 1] - 1000L
            }
        }

        val reorderedDayEntries = dayEntries.mapIndexed { idx, entry ->
            entry.copy(sortOrder = sortOrders[idx])
        }

        val reorderedMap = reorderedDayEntries.associateBy { it.id }
        return currentEntries.map { reorderedMap[it.id] ?: it }
            .sortedByDescending { it.effectiveSortOrder }
    }

    suspend fun persistReorder(
        day: LocalDate,
        currentEntries: List<HistoryEntry>
    ) {
        val dayEntries = currentEntries.filter {
            java.time.Instant.ofEpochMilli(it.dateMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate() == day
        }
        historyRepository.reorderEntries(dayEntries)
    }
}
