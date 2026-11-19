package de.gurum.besseresser.data

import de.gurum.besseresser.model.AppJson
import de.gurum.besseresser.model.DayFazit
import de.gurum.besseresser.model.HistoryEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Migriert historische JSON-Dateien aus `filesDir/history` in die Room-Datenbank
 * und löscht die migrierten JSON-Dateien anschließend.
 */
object LegacyJsonMigrator {

    suspend fun migrate(
        historyDir: File,
        historyDao: HistoryDao,
        dayFazitDao: DayFazitDao,
        weekFazitDao: WeekFazitDao,
        dispatcher: CoroutineDispatcher = Dispatchers.IO
    ) = withContext(dispatcher) {
        migrateInternal(historyDir, historyDao, dayFazitDao, weekFazitDao)
    }

    fun migrateSync(
        historyDir: File,
        historyDao: HistoryDao,
        dayFazitDao: DayFazitDao,
        weekFazitDao: WeekFazitDao
    ) {
        runBlocking(Dispatchers.IO) {
            migrateInternal(historyDir, historyDao, dayFazitDao, weekFazitDao)
        }
    }

    private suspend fun migrateInternal(
        historyDir: File,
        historyDao: HistoryDao,
        dayFazitDao: DayFazitDao,
        weekFazitDao: WeekFazitDao
    ) {
        if (!historyDir.exists() || !historyDir.isDirectory) return

        val jsonFiles = historyDir.listFiles { f -> f.extension == "json" } ?: return
        if (jsonFiles.isEmpty()) return

        // 1. History-Einträge (keine day_ / week_ Dateien)
        jsonFiles.filter { !it.name.startsWith("day_") && !it.name.startsWith("week_") }.forEach { f ->
            runCatching {
                val entry = AppJson.decodeFromString<HistoryEntry>(f.readText())
                val e = if (entry.id == 0L) entry.copy(id = f.nameWithoutExtension.toLongOrNull() ?: 0L) else entry
                historyDao.insert(e.toEntity())
                f.delete()
            }
        }

        // 2. Tagesfazits (day_*.json)
        jsonFiles.filter { it.name.startsWith("day_") }.forEach { f ->
            runCatching {
                val fazit = AppJson.decodeFromString<DayFazit>(f.readText())
                dayFazitDao.insert(fazit.toEntity())
                f.delete()
            }
        }

        // 3. Wochenfazits (week_*.json)
        jsonFiles.filter { it.name.startsWith("week_") }.forEach { f ->
            runCatching {
                val fazit = AppJson.decodeFromString<DayFazit>(f.readText())
                weekFazitDao.insert(fazit.toWeekEntity())
                f.delete()
            }
        }
    }
}
