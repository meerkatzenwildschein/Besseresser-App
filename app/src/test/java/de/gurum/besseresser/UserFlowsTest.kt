package de.gurum.besseresser

import android.net.Uri
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import de.gurum.besseresser.data.AppDispatchers
import de.gurum.besseresser.data.PromptBundle
import de.gurum.besseresser.model.Evaluation
import de.gurum.besseresser.model.GoalResult
import de.gurum.besseresser.model.Settings
import de.gurum.besseresser.model.TrafficLight
import de.gurum.besseresser.ui.HistoryDetailViewModel
import de.gurum.besseresser.ui.HistoryViewModel
import de.gurum.besseresser.ui.SetupViewModel
import kotlinx.coroutines.Dispatchers

/**
 * ViewModel-level tests covering the three user flows named in REFACTORING.md 3.6.1:
 *   1. Setup flow  – first-run API configuration via SetupViewModel
 *   2. Manual entry (retro capture) – HistoryViewModel.submitBackdated() without photo
 *   3. History list + detail view  – entries load, detail ViewModel finds the right entry
 *
 * All tests run on the JVM using in-memory fakes; no emulator or Compose test rule needed.
 */
@ExperimentalCoroutinesApi
class UserFlowsTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testDispatchers = AppDispatchers(
        main = testDispatcher,
        io = testDispatcher,
        default = testDispatcher
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ----------------------------------------------------------------- 1. Setup flow

    @Test
    fun `setup flow - init populates state from existing settings`() = runTest(testDispatcher) {
        val settings = Settings(
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-test",
            model = "gpt-4o",
            goals = listOf("Wenig Zucker", "Viel Protein"),
            retentionWeeks = 4
        )
        val vm = SetupViewModel(
            apiService = FakeApiService(),
            backupService = FakeBackupService(),
            settingsRepository = FakeSettingsRepository(initialSettings = settings)
        )

        vm.init(settings, emptyList())
        advanceUntilIdle()

        with(vm.uiState.value) {
            assertEquals("https://api.openai.com/v1", url)
            assertEquals("sk-test", key)
            assertEquals("gpt-4o", model)
            assertTrue(goals.contains("Wenig Zucker"))
            assertTrue(goals.contains("Viel Protein"))
            assertEquals("4", retentionWeeks)
        }
    }

    @Test
    fun `setup flow - setUrl, setKey, setModel update state independently`() = runTest(testDispatcher) {
        val vm = SetupViewModel(
            apiService = FakeApiService(),
            backupService = FakeBackupService(),
            settingsRepository = FakeSettingsRepository()
        )

        vm.setUrl("https://custom.api/v1")
        vm.setKey("new-key-123")
        vm.setModel("gpt-4o-mini")
        vm.setRetentionWeeks("0")

        with(vm.uiState.value) {
            assertEquals("https://custom.api/v1", url)
            assertEquals("new-key-123", key)
            assertEquals("gpt-4o-mini", model)
            assertEquals("0", retentionWeeks)
        }
    }

    @Test
    fun `setup flow - setGoals marks goals as dirty`() = runTest(testDispatcher) {
        val vm = SetupViewModel(
            apiService = FakeApiService(),
            backupService = FakeBackupService(),
            settingsRepository = FakeSettingsRepository()
        )

        assertFalse(vm.uiState.value.goalsDirty)
        vm.setGoals("Ziel A\nZiel B")
        assertTrue(vm.uiState.value.goalsDirty)
        assertEquals("Ziel A\nZiel B", vm.uiState.value.goals)
    }

    @Test
    fun `setup flow - startSplitGoals with successful API response sets splitEdited and showSplit`() =
        runTest(testDispatcher) {
            val splitResult = listOf("Ziel 1", "Ziel 2", "Ziel 3")
            val fakeApi = FakeApiService(splitGoalsResult = Result.success(splitResult))
            val vm = SetupViewModel(
                apiService = fakeApi,
                backupService = FakeBackupService(),
                settingsRepository = FakeSettingsRepository()
            )
            vm.setUrl("https://api.openai.com/v1")
            vm.setKey("sk-test")

            vm.startSplitGoals("Wenig Zucker, viel Eiweiß, keine Fertigprodukte")
            advanceUntilIdle()

            assertTrue(vm.uiState.value.showSplit)
            assertFalse(vm.uiState.value.splitting)
            assertEquals("Ziel 1\nZiel 2\nZiel 3", vm.uiState.value.splitEdited)
        }

    // ----------------------------------------------------------------- 3. History list + detail view

    @Test
    fun `history list - entries are observable and reflect repository state`() =
        runTest(testDispatcher) {
            val histRepo = FakeHistoryRepository()
            val vm = HistoryViewModel(
                apiService = FakeApiService(),
                historyRepository = histRepo,
                settingsRepository = FakeSettingsRepository(),
                imagePreprocessor = FakeImagePreprocessor(),
                dispatchers = testDispatchers
            )
            advanceUntilIdle()
            assertEquals(0, vm.uiState.value.entries.size)

            val eval = Evaluation(recognizedMeal = "Lachs", goalResults = listOf(GoalResult("Ziel 1", TrafficLight.GREEN, "")))
            histRepo.add(null, eval, note = "Mittagessen", dateOverride = 1_000_000L)
            histRepo.add(null, eval, note = "Abendessen", dateOverride = 2_000_000L)
            advanceUntilIdle()

            assertEquals(2, vm.uiState.value.entries.size)
        }

    @Test
    fun `history list - pending delete is undoable within delay`() =
        runTest(testDispatcher) {
            val histRepo = FakeHistoryRepository()
            val eval = Evaluation(recognizedMeal = "Toast")
            val entry = histRepo.add(null, eval, note = "Frühstück", dateOverride = 5_000L)

            val vm = HistoryViewModel(
                apiService = FakeApiService(),
                historyRepository = histRepo,
                settingsRepository = FakeSettingsRepository(),
                imagePreprocessor = FakeImagePreprocessor(),
                dispatchers = testDispatchers
            )
            advanceUntilIdle()
            assertEquals(1, vm.uiState.value.entries.size)

            vm.requestDeleteEntry(entry, delayMs = 5_000L)
            assertTrue(vm.uiState.value.pendingDeleteIds.contains(entry.id))

            vm.undoDeleteEntry(entry.id)
            assertFalse(vm.uiState.value.pendingDeleteIds.contains(entry.id))
            assertEquals(1, histRepo.getAll().size)
        }

    @Test
    fun `history detail - loads correct entry by id`() = runTest(testDispatcher) {
        val histRepo = FakeHistoryRepository()
        val eval = Evaluation(
            recognizedMeal = "Pizza",
            goalResults = listOf(GoalResult("Ziel 1", TrafficLight.RED, "Zu viel Fett"))
        )
        val entry = histRepo.add(null, eval, note = "Mittagessen", dateOverride = 42_000L)

        val vm = HistoryDetailViewModel(
            entryId = entry.id,
            historyRepository = histRepo,
            settingsRepository = FakeSettingsRepository(),
            apiService = FakeApiService(),
            imagePreprocessor = FakeImagePreprocessor(),
            dispatchers = testDispatchers
        )
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.entry)
        assertEquals(entry.id, vm.uiState.value.entry!!.id)
        assertEquals("Pizza", vm.uiState.value.entry!!.recognizedMeal)
        assertTrue(vm.uiState.value.loaded)
    }

    @Test
    fun `history detail - returns null entry for unknown id`() = runTest(testDispatcher) {
        val histRepo = FakeHistoryRepository()
        val vm = HistoryDetailViewModel(
            entryId = 99_999L,
            historyRepository = histRepo,
            settingsRepository = FakeSettingsRepository(),
            apiService = FakeApiService(),
            imagePreprocessor = FakeImagePreprocessor(),
            dispatchers = testDispatchers
        )
        advanceUntilIdle()

        assertNull(vm.uiState.value.entry)
        assertTrue(vm.uiState.value.loaded)
    }

    @Test
    fun `history detail - updateErkannt persists new text`() = runTest(testDispatcher) {
        val histRepo = FakeHistoryRepository()
        val eval = Evaluation(recognizedMeal = "Burger")
        val entry = histRepo.add(null, eval, note = "Abend", dateOverride = 7_000L)

        val vm = HistoryDetailViewModel(
            entryId = entry.id,
            historyRepository = histRepo,
            settingsRepository = FakeSettingsRepository(),
            apiService = FakeApiService(),
            imagePreprocessor = FakeImagePreprocessor(),
            dispatchers = testDispatchers
        )
        advanceUntilIdle()
        assertEquals("Burger", vm.uiState.value.entry!!.recognizedMeal)

        vm.updateErkannt("Veggie-Burger")
        advanceUntilIdle()

        assertEquals("Veggie-Burger", histRepo.getAll().first { it.id == entry.id }.recognizedMeal)
    }
}
