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
        entries: List<HistoryEntry>,
        fazitMap: Map<String, DayFazit>,
        loadingFazits: Set<String>,
        weekFazitMap: Map<String, DayFazit>,
        loadingWeeks: Set<String>,
        settings: Settings? = null,
        promptContext: String? = null,
        dayPrompts: FazitPromptBundle? = null,
        weekPromptsProvider: ((calendarWeek: Int) -> FazitPromptBundle)? = null,
        daymealDescriptionsProvider: ((List<HistoryEntry>) -> List<String>)? = null,
        weekmealDescriptionsProvider: ((List<HistoryEntry>) -> List<String>)? = null,
        onGenerateDayFazit: (day: LocalDate, entries: List<HistoryEntry>, settings: Settings, promptContext: String?, mealDescriptions: List<String>, prompts: FazitPromptBundle) -> Unit,
        onGenerateWeekFazit: (settings: Settings, promptContext: String?, weekKey: String, mealDescriptions: List<String>, prompts: FazitPromptBundle) -> Unit
    ) {
        if (entries.isEmpty()) return

        val effectiveSettings = settings ?: settingsRepository.currentSettings()
        val effectiveProfile = profileRepository?.currentProfile() ?: Profile()
        val effectivePromptContext = promptContext ?: promptRepository?.profileContextLine(effectiveProfile)
        val effectiveDayPrompts = dayPrompts ?: promptRepository?.createDayFazitPrompts() ?: FazitPromptBundle("", "", "", "")
        val effectiveDayMealDescriptions = daymealDescriptionsProvider ?: { list ->
            promptRepository?.dayFazitMealDescriptions(list) ?: list.map { it.recognizedMeal }
        }
        val effectiveWeekPromptsProvider = weekPromptsProvider ?: { cw ->
            promptRepository?.createWeekFazitPrompts(cw) ?: FazitPromptBundle("", "", "", "")
        }
        val effectiveWeekMealDescriptions = weekmealDescriptionsProvider ?: { list ->
            promptRepository?.weekFazitMealDescriptions(list) ?: list.map { it.recognizedMeal }
        }

        val nowDate = LocalDate.now()
        val nowHour = LocalTime.now().hour
        val autoFazitTime = nowHour >= FAZIT_AUTO_HOUR

        checkAutoDayFazits(
            entries = entries,
            fazitMap = fazitMap,
            loadingFazits = loadingFazits,
            nowDate = nowDate,
            autoFazitTime = autoFazitTime,
            settings = effectiveSettings,
            promptContext = effectivePromptContext,
            dayPrompts = effectiveDayPrompts,
            daymealDescriptionsProvider = effectiveDayMealDescriptions,
            onGenerateDayFazit = onGenerateDayFazit
        )

        checkAutoWeekFazits(
            entries = entries,
            weekFazitMap = weekFazitMap,
            loadingWeeks = loadingWeeks,
            nowDate = nowDate,
            nowHour = nowHour,
            settings = effectiveSettings,
            promptContext = effectivePromptContext,
            weekPromptsProvider = effectiveWeekPromptsProvider,
            weekmealDescriptionsProvider = effectiveWeekMealDescriptions,
            onGenerateWeekFazit = onGenerateWeekFazit
        )
    }

    private fun checkAutoDayFazits(
        entries: List<HistoryEntry>,
        fazitMap: Map<String, DayFazit>,
        loadingFazits: Set<String>,
        nowDate: LocalDate,
        autoFazitTime: Boolean,
        settings: Settings,
        promptContext: String?,
        dayPrompts: FazitPromptBundle,
        daymealDescriptionsProvider: (List<HistoryEntry>) -> List<String>,
        onGenerateDayFazit: (day: LocalDate, entries: List<HistoryEntry>, settings: Settings, promptContext: String?, mealDescriptions: List<String>, prompts: FazitPromptBundle) -> Unit
    ) {
        groupByDay(entries).take(2).forEach { group ->
            val ratedEntries = group.entries.filter { it.isRated }
            if (ratedEntries.isEmpty()) return@forEach
            val dayAutoReady = group.day.isBefore(nowDate) || autoFazitTime
            val key = dayKeyOf(group.day)
            if (key in loadingFazits || !dayAutoReady) return@forEach

            val fazit = fazitMap[key]
            if (fazit == null) {
                if (key !in DayFazitSession.attempted) {
                    DayFazitSession.attempted.add(key)
                    onGenerateDayFazit(
                        group.day,
                        ratedEntries,
                        settings,
                        promptContext,
                        daymealDescriptionsProvider(ratedEntries),
                        dayPrompts
                    )
                }
            } else if (isFazitOutdated(fazit, ratedEntries)) {
                onGenerateDayFazit(
                    group.day,
                    ratedEntries,
                    settings,
                    promptContext,
                    daymealDescriptionsProvider(ratedEntries),
                    dayPrompts
                )
            }
        }
    }

    private fun checkAutoWeekFazits(
        entries: List<HistoryEntry>,
        weekFazitMap: Map<String, DayFazit>,
        loadingWeeks: Set<String>,
        nowDate: LocalDate,
        nowHour: Int,
        settings: Settings,
        promptContext: String?,
        weekPromptsProvider: (calendarWeek: Int) -> FazitPromptBundle,
        weekmealDescriptionsProvider: (List<HistoryEntry>) -> List<String>,
        onGenerateWeekFazit: (settings: Settings, promptContext: String?, weekKey: String, mealDescriptions: List<String>, prompts: FazitPromptBundle) -> Unit
    ) {
        val dueWk = latestDueWeekKey(entries, nowDate, nowHour) ?: return

        if (dueWk !in loadingWeeks && weekFazitMap[dueWk] == null && dueWk !in DayFazitSession.attemptedWeekly) {
            DayFazitSession.attemptedWeekly.add(dueWk)
            val wkEntries = entries.filter { it.isRated && weekKeyOf(it.dateMillis) == dueWk }
            if (wkEntries.isEmpty()) return
            onGenerateWeekFazit(
                settings,
                promptContext,
                dueWk,
                weekmealDescriptionsProvider(wkEntries),
                weekPromptsProvider(calendarWeekOfKey(dueWk))
            )
        }
    }
}
