package de.gurum.besseresser.data

import de.gurum.besseresser.model.HistoryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val UNDO_DELETE_DELAY_MS = 5000L

/**
 * Verwaltet Vormerkungen zum Löschen von Verlaufseinträgen mit konfigurierbarem Undo-Timer.
 */
class HistoryDeleteManager(
    private val historyRepository: HistoryRepository
) {
    private val pendingDeleteJobs = mutableMapOf<Long, Job>()
    private val pendingDeleteEntries = mutableMapOf<Long, HistoryEntry>()

    fun deleteEntry(
        entry: HistoryEntry,
        scope: CoroutineScope,
        onPendingChanged: (Set<Long>) -> Unit
    ) {
        val job = synchronized(pendingDeleteJobs) {
            pendingDeleteEntries.remove(entry.id)
            pendingDeleteJobs.remove(entry.id)
        }
        job?.cancel()
        onPendingChanged(pendingDeleteEntries.keys.toSet())
        scope.launch {
            historyRepository.delete(entry)
            historyRepository.deleteDayFazitForEntry(entry)
            historyRepository.deleteWeekFazitForEntry(entry)
        }
    }

    fun requestDeleteEntry(
        entry: HistoryEntry,
        delayMs: Long = UNDO_DELETE_DELAY_MS,
        scope: CoroutineScope,
        onPendingChanged: (Set<Long>) -> Unit
    ) {
        val existingJob = synchronized(pendingDeleteJobs) {
            pendingDeleteEntries[entry.id] = entry
            pendingDeleteJobs.remove(entry.id)
        }
        existingJob?.cancel()
        onPendingChanged(pendingDeleteEntries.keys.toSet())
        val job = scope.launch {
            if (delayMs > 0L) delay(delayMs)
            val shouldDelete = synchronized(pendingDeleteJobs) {
                pendingDeleteJobs.remove(entry.id)
                pendingDeleteEntries.remove(entry.id) != null
            }
            onPendingChanged(pendingDeleteEntries.keys.toSet())
            if (shouldDelete) {
                historyRepository.delete(entry)
                historyRepository.deleteDayFazitForEntry(entry)
                historyRepository.deleteWeekFazitForEntry(entry)
            }
        }
        synchronized(pendingDeleteJobs) {
            if (pendingDeleteEntries.containsKey(entry.id)) {
                pendingDeleteJobs[entry.id] = job
            }
        }
    }

    fun undoDeleteEntry(
        entryId: Long,
        onPendingChanged: (Set<Long>) -> Unit
    ) {
        val job = synchronized(pendingDeleteJobs) {
            pendingDeleteEntries.remove(entryId)
            pendingDeleteJobs.remove(entryId)
        }
        job?.cancel()
        onPendingChanged(pendingDeleteEntries.keys.toSet())
    }

    fun flushPendingDeletes(
        scope: CoroutineScope,
        onPendingChanged: (Set<Long>) -> Unit
    ) {
        val toDelete = synchronized(pendingDeleteJobs) {
            val list = pendingDeleteEntries.values.toList()
            pendingDeleteJobs.values.forEach { it.cancel() }
            pendingDeleteJobs.clear()
            pendingDeleteEntries.clear()
            list
        }
        if (toDelete.isEmpty()) return
        onPendingChanged(emptySet())
        scope.launch {
            toDelete.forEach { entry ->
                historyRepository.delete(entry)
                historyRepository.deleteDayFazitForEntry(entry)
                historyRepository.deleteWeekFazitForEntry(entry)
            }
        }
    }
}
