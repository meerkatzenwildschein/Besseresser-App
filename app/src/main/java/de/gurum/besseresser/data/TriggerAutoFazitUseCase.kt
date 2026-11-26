package de.gurum.besseresser.data

import de.gurum.besseresser.model.calendarWeekOfKey
import de.gurum.besseresser.model.DayFazitSession
import de.gurum.besseresser.model.dayKeyOf
import de.gurum.besseresser.model.DayFazit
import de.gurum.besseresser.model.HistoryEntry
import de.gurum.besseresser.model.Profile
import de.gurum.besseresser.model.Settings
import de.gurum.besseresser.model.weekKeyOf
import de.gurum.besseresser.ui.groupByDay
import de.gurum.besseresser.ui.isFazitOutdated
import de.gurum.besseresser.ui.latestDueWeekKey
import de.gurum.besseresser.ui.FAZIT_AUTO_HOUR
import java.time.LocalDate
import java.time.LocalTime

/** Zustand der Verlaufsdaten für die Fazit-Prüfung. */
data class AutoFazitHistoryContext(
    val entries: List<HistoryEntry>,
    val fazitMap: Map<String, DayFazit>,
    val loadingFazits: Set<String>,
    val weekFazitMap: Map<String, DayFazit>,
    val loadingWeeks: Set<String>
)

/** Konfiguration und Hilfs-Provider für Fazit-Prompts. */
data class AutoFazitPromptConfig(
    val settings: Settings? = null,
    val promptContext: String? = null,
    val dayPrompts: FazitPromptBundle? = null,
    val weekPromptsProvider: ((calendarWeek: Int) -> FazitPromptBundle)? = null,
    val daymealDescriptionsProvider: ((List<HistoryEntry>) -> List<String>)? = null,
    val weekmealDescriptionsProvider: ((List<HistoryEntry>) -> List<String>)? = null
)

/** Callback-Schnittstellen für die Generierung von Fazits. */
data class AutoFazitCallbacks(
    val onGenerateDayFazit: (day: LocalDate, entries: List<HistoryEntry>, settings: Settings, promptContext: String?, mealDescriptions: List<String>, prompts: FazitPromptBundle) -> Unit,
    val onGenerateWeekFazit: (settings: Settings, promptContext: String?, weekKey: String, mealDescriptions: List<String>, prompts: FazitPromptBundle) -> Unit
)

private data class DayFazitExecutionParams(
    val settings: Settings,
    val promptContext: String?,
    val dayPrompts: FazitPromptBundle,
    val daymealDescriptionsProvider: (List<HistoryEntry>) -> List<String>
)

private data class WeekFazitExecutionParams(
    val settings: Settings,
    val promptContext: String?,
    val weekPromptsProvider: (calendarWeek: Int) -> FazitPromptBundle,
    val weekmealDescriptionsProvider: (List<HistoryEntry>) -> List<String>
)

/**
 * Dedizierter Use-Case für die Prüfung und automatische Generierung fälliger Tages- und Wochen-Fazits.
 */
class TriggerAutoFazitUseCase(
    private val fazitManager: FazitManager,
    private val settingsRepository: SettingsRepository,
    private val profileRepository: ProfileRepository? = null,
    private val promptRepository: PromptRepository? = null
) {

    suspend fun checkAndTriggerAutoFazits(
        historyContext: AutoFazitHistoryContext,
        promptConfig: AutoFazitPromptConfig = AutoFazitPromptConfig(),
        callbacks: AutoFazitCallbacks
    ) {
        val entries = historyContext.entries
        if (entries.isEmpty()) return

        val effectiveSettings = promptConfig.settings ?: settingsRepository.currentSettings()
        val effectiveProfile = profileRepository?.currentProfile() ?: Profile()
        val effectivePromptContext = promptConfig.promptContext ?: promptRepository?.profileContextLine(effectiveProfile)
        val effectiveDayPrompts = promptConfig.dayPrompts ?: promptRepository?.createDayFazitPrompts() ?: FazitPromptBundle("", "", "", "")
        val effectiveDayMealDescriptions = promptConfig.daymealDescriptionsProvider ?: { list ->
            promptRepository?.dayFazitMealDescriptions(list) ?: list.map { it.recognizedMeal }
        }
        val effectiveWeekPromptsProvider = promptConfig.weekPromptsProvider ?: { cw ->
            promptRepository?.createWeekFazitPrompts(cw) ?: FazitPromptBundle("", "", "", "")
        }
        val effectiveWeekMealDescriptions = promptConfig.weekmealDescriptionsProvider ?: { list ->
            promptRepository?.weekFazitMealDescriptions(list) ?: list.map { it.recognizedMeal }
        }

        val nowDate = LocalDate.now()
        val nowHour = LocalTime.now().hour
        val autoFazitTime = nowHour >= FAZIT_AUTO_HOUR

        val dayParams = DayFazitExecutionParams(
            settings = effectiveSettings,
            promptContext = effectivePromptContext,
            dayPrompts = effectiveDayPrompts,
            daymealDescriptionsProvider = effectiveDayMealDescriptions
        )

        val weekParams = WeekFazitExecutionParams(
            settings = effectiveSettings,
            promptContext = effectivePromptContext,
            weekPromptsProvider = effectiveWeekPromptsProvider,
            weekmealDescriptionsProvider = effectiveWeekMealDescriptions
        )

        checkAutoDayFazits(
            historyContext = historyContext,
            nowDate = nowDate,
            autoFazitTime = autoFazitTime,
            params = dayParams,
            onGenerateDayFazit = callbacks.onGenerateDayFazit
        )

        checkAutoWeekFazits(
            historyContext = historyContext,
            nowDate = nowDate,
            nowHour = nowHour,
            params = weekParams,
            onGenerateWeekFazit = callbacks.onGenerateWeekFazit
        )
    }

    private fun checkAutoDayFazits(
        historyContext: AutoFazitHistoryContext,
        nowDate: LocalDate,
        autoFazitTime: Boolean,
        params: DayFazitExecutionParams,
        onGenerateDayFazit: (day: LocalDate, entries: List<HistoryEntry>, settings: Settings, promptContext: String?, mealDescriptions: List<String>, prompts: FazitPromptBundle) -> Unit
    ) {
        groupByDay(historyContext.entries).take(2).forEach { group ->
            val ratedEntries = group.entries.filter { it.isRated }
            if (ratedEntries.isEmpty()) return@forEach
            val dayAutoReady = group.day.isBefore(nowDate) || autoFazitTime
            val key = dayKeyOf(group.day)
            if (key in historyContext.loadingFazits || !dayAutoReady) return@forEach

            val fazit = historyContext.fazitMap[key]
            if (fazit == null) {
                if (key !in DayFazitSession.attempted) {
                    DayFazitSession.attempted.add(key)
                    onGenerateDayFazit(
                        group.day,
                        ratedEntries,
                        params.settings,
                        params.promptContext,
                        params.daymealDescriptionsProvider(ratedEntries),
                        params.dayPrompts
                    )
                }
            } else if (isFazitOutdated(fazit, ratedEntries)) {
                val attemptKey = "$key:${de.gurum.besseresser.model.dayFingerprint(ratedEntries)}"
                if (attemptKey !in DayFazitSession.attemptedOutdated) {
                    DayFazitSession.attemptedOutdated.add(attemptKey)
                    onGenerateDayFazit(
                        group.day,
                        ratedEntries,
                        params.settings,
                        params.promptContext,
                        params.daymealDescriptionsProvider(ratedEntries),
                        params.dayPrompts
                    )
                }
            }
        }
    }

    private fun checkAutoWeekFazits(
        historyContext: AutoFazitHistoryContext,
        nowDate: LocalDate,
        nowHour: Int,
        params: WeekFazitExecutionParams,
        onGenerateWeekFazit: (settings: Settings, promptContext: String?, weekKey: String, mealDescriptions: List<String>, prompts: FazitPromptBundle) -> Unit
    ) {
        val dueWk = latestDueWeekKey(historyContext.entries, nowDate, nowHour) ?: return

        if (dueWk !in historyContext.loadingWeeks && historyContext.weekFazitMap[dueWk] == null && dueWk !in DayFazitSession.attemptedWeekly) {
            DayFazitSession.attemptedWeekly.add(dueWk)
            val wkEntries = historyContext.entries.filter { it.isRated && weekKeyOf(it.dateMillis) == dueWk }
            if (wkEntries.isEmpty()) return
            onGenerateWeekFazit(
                params.settings,
                params.promptContext,
                dueWk,
                params.weekmealDescriptionsProvider(wkEntries),
                params.weekPromptsProvider(calendarWeekOfKey(dueWk))
            )
        }
    }
}
