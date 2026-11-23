package de.gurum.besseresser.data

import de.gurum.besseresser.model.DayFazit
import de.gurum.besseresser.model.HistoryEntry
import de.gurum.besseresser.model.Settings
import de.gurum.besseresser.model.dayFingerprint
import de.gurum.besseresser.model.dayKeyOf
import de.gurum.besseresser.model.weekKeyOf
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Domain-Use-Case & Manager für die Validierung, Berechnung und Cache-Invalidierung von Tages- und Wochenfazits.
 */
class FazitManager(
    private val apiService: BesseresserApiService,
    private val historyRepository: HistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val profileRepository: ProfileRepository? = null,
    private val promptRepository: PromptRepository? = null,
    private val dispatchers: AppDispatchers = AppDispatchers()
) {
    suspend fun validateAllWeekFazits(
        entries: List<HistoryEntry>,
        rawMap: Map<String, DayFazit>
    ): Map<String, DayFazit> {
        val ratedEntries = entries.filter { it.isRated }
        if (ratedEntries.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, DayFazit>()
        for ((wk, wf) in rawMap) {
            val wkEntries = ratedEntries.filter { weekKeyOf(it.dateMillis) == wk }
            val validated = validateWeekFazit(wk, wf, wkEntries)
            if (validated != null) {
                result[wk] = validated
            }
        }
        return result
    }

    suspend fun validateWeekFazit(
        weekKey: String,
        fazit: DayFazit?,
        entries: List<HistoryEntry>
    ): DayFazit? {
        if (fazit == null) return null
        val ratedEntries = entries.filter { it.isRated }
        return if (ratedEntries.isNotEmpty() && fazit.fingerprint == dayFingerprint(ratedEntries)) {
            fazit
        } else {
            historyRepository.deleteWeekFazit(weekKey)
            null
        }
    }

    suspend fun computeDayFazit(
        day: LocalDate,
        entriesOfDay: List<HistoryEntry>,
        settings: Settings,
        promptContext: String?,
        mealDescriptions: List<String>,
        prompts: FazitPromptBundle
    ): Result<DayFazit> {
        val ratedEntries = entriesOfDay.filter { it.isRated }
        val key = dayKeyOf(day)
        val result = apiService.dayFazit(
            settings = settings,
            goals = settings.goals,
            promptContext = promptContext,
            mealDescriptions = mealDescriptions,
            prompts = prompts
        )
        return result.map { res ->
            val fazit = DayFazit(
                dayKey = key,
                rating = res.rating,
                text = res.text,
                entryIds = ratedEntries.map { it.id },
                createdAt = System.currentTimeMillis(),
                fingerprint = dayFingerprint(ratedEntries)
            )
            historyRepository.saveDayFazit(fazit)
            fazit
        }
    }

    suspend fun computeWeekFazit(
        weekKey: String,
        entriesOfWeek: List<HistoryEntry>,
        settings: Settings,
        promptContext: String?,
        mealDescriptions: List<String>,
        prompts: FazitPromptBundle
    ): Result<DayFazit> {
        val ratedEntries = entriesOfWeek.filter { it.isRated }
        val result = apiService.dayFazit(
            settings = settings,
            goals = settings.goals,
            promptContext = promptContext,
            mealDescriptions = mealDescriptions,
            prompts = prompts
        )
        return result.map { res ->
            val wf = DayFazit(
                weekKey, res.rating, res.text, ratedEntries.map { it.id },
                System.currentTimeMillis(), dayFingerprint(ratedEntries)
            )
            historyRepository.saveWeekFazit(wf)
            wf
        }
    }
}
