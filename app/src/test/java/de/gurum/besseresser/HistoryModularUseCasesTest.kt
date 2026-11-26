package de.gurum.besseresser

import de.gurum.besseresser.data.AutoFazitCallbacks
import de.gurum.besseresser.data.AutoFazitHistoryContext
import de.gurum.besseresser.data.AutoFazitPromptConfig
import de.gurum.besseresser.data.FazitManager
import de.gurum.besseresser.data.FazitPromptBundle
import de.gurum.besseresser.data.HistoryDeleteManager
import de.gurum.besseresser.data.HistoryReorderUseCase
import de.gurum.besseresser.data.TriggerAutoFazitUseCase
import de.gurum.besseresser.model.Evaluation
import de.gurum.besseresser.model.HistoryEntry
import de.gurum.besseresser.model.Settings
import de.gurum.besseresser.model.TrafficLight
import de.gurum.besseresser.model.dayKeyOf
import de.gurum.besseresser.model.toWeightedGoals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryModularUseCasesTest {

    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun testHistoryDeleteManagerRequestUndoAndFlush() = runTest(testDispatcher) {
        val histRepo = FakeHistoryRepository()
        val entry1 = histRepo.add(null, Evaluation(recognizedMeal = "Eintrag 1"))
        val entry2 = histRepo.add(null, Evaluation(recognizedMeal = "Eintrag 2"))

        val deleteManager = HistoryDeleteManager(histRepo)
        var pendingIds = emptySet<Long>()

        deleteManager.requestDeleteEntry(entry1, delayMs = 1000L, scope = this) { pendingIds = it }
        assertTrue(pendingIds.contains(entry1.id))

        // Undo before delay
        deleteManager.undoDeleteEntry(entry1.id) { pendingIds = it }
        assertFalse(pendingIds.contains(entry1.id))

        // Wait beyond delay and verify entry1 is still present
        advanceTimeBy(1500L)
        advanceUntilIdle()
        assertNotNull(histRepo.getAll().find { it.id == entry1.id })

        // Request delete entry2 and let timer expire
        deleteManager.requestDeleteEntry(entry2, delayMs = 500L, scope = this) { pendingIds = it }
        assertTrue(pendingIds.contains(entry2.id))
        advanceTimeBy(600L)
        advanceUntilIdle()
        assertFalse(pendingIds.contains(entry2.id))
        assertEquals(null, histRepo.getAll().find { it.id == entry2.id })

        // Test flush
        val entry3 = histRepo.add(null, Evaluation(recognizedMeal = "Eintrag 3"))
        deleteManager.requestDeleteEntry(entry3, delayMs = 5000L, scope = this) { pendingIds = it }
        assertTrue(pendingIds.contains(entry3.id))
        deleteManager.flushPendingDeletes(this) { pendingIds = it }
        assertTrue(pendingIds.isEmpty())
        advanceUntilIdle()
        assertEquals(null, histRepo.getAll().find { it.id == entry3.id })
    }

    @Test
    fun testHistoryReorderUseCase() = runTest(testDispatcher) {
        val histRepo = FakeHistoryRepository()
        val today = LocalDate.now()
        val m1 = today.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val m2 = today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val e1 = histRepo.add(null, Evaluation(recognizedMeal = "Mahlzeit 1"), dateOverride = m1)
        val e2 = histRepo.add(null, Evaluation(recognizedMeal = "Mahlzeit 2"), dateOverride = m2)

        val reorderUseCase = HistoryReorderUseCase(histRepo)
        val list = listOf(e2, e1) // descending by date

        val reordered = reorderUseCase.reorderWithinDay(today, fromIndex = 0, toIndex = 1, currentEntries = list)
        assertNotNull(reordered)
        assertEquals(e1.id, reordered!![0].id)
        assertEquals(e2.id, reordered[1].id)

        reorderUseCase.persistReorder(today, reordered)
        val fromRepo = histRepo.getAll()
        assertEquals(e1.id, fromRepo[0].id)
    }

    @Test
    fun testTriggerAutoFazitUseCase() = runTest(testDispatcher) {
        val api = FakeApiService()
        val histRepo = FakeHistoryRepository()
        val setRepo = FakeSettingsRepository()
        val fazitManager = FazitManager(api, histRepo, setRepo)
        val useCase = TriggerAutoFazitUseCase(fazitManager, setRepo)

        val pastDay = LocalDate.now().minusDays(1)
        val pastDayMillis = pastDay.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val entry = histRepo.add(null, Evaluation(recognizedMeal = "Suppe"), dateOverride = pastDayMillis)

        var dayFazitTriggered = false
        useCase.checkAndTriggerAutoFazits(
            historyContext = AutoFazitHistoryContext(
                entries = listOf(entry),
                fazitMap = emptyMap(),
                loadingFazits = emptySet(),
                weekFazitMap = emptyMap(),
                loadingWeeks = emptySet()
            ),
            promptConfig = AutoFazitPromptConfig(
                settings = Settings("", "", "", listOf("Ziel 1").toWeightedGoals()),
                promptContext = null,
                dayPrompts = FazitPromptBundle("Intro", "Rules", "de", "Ehrlich"),
                weekPromptsProvider = { FazitPromptBundle("WIntro", "Rules", "de", "Ehrlich") },
                daymealDescriptionsProvider = { it.map { e -> e.recognizedMeal } },
                weekmealDescriptionsProvider = { it.map { e -> e.recognizedMeal } }
            ),
            callbacks = AutoFazitCallbacks(
                onGenerateDayFazit = { day, entries, _, _, _, _ ->
                    dayFazitTriggered = true
                    assertEquals(pastDay, day)
                    assertEquals(1, entries.size)
                },
                onGenerateWeekFazit = { _, _, _, _, _ -> }
            )
        )

        assertTrue(dayFazitTriggered)
    }
}
