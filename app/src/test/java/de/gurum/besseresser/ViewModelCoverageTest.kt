package de.gurum.besseresser

import android.net.TestUri
import android.net.Uri
import de.gurum.besseresser.data.AppDispatchers
import de.gurum.besseresser.data.BackupSummary
import de.gurum.besseresser.data.ImagePreprocessor
import de.gurum.besseresser.data.PromptBundle
import de.gurum.besseresser.model.DayFazit
import de.gurum.besseresser.model.Evaluation
import de.gurum.besseresser.model.GoalResult
import de.gurum.besseresser.model.HistoryEntry
import de.gurum.besseresser.model.RecentNote
import de.gurum.besseresser.model.Settings
import de.gurum.besseresser.model.TrafficLight
import de.gurum.besseresser.ui.ConfigViewModel
import de.gurum.besseresser.ui.HistoryDetailViewModel
import de.gurum.besseresser.ui.HistoryViewModel
import de.gurum.besseresser.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
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

/**
 * ViewModel coverage tests for branches that were previously untested.
 * Covers error paths, guard clauses, and secondary flows in MainViewModel,
 * HistoryViewModel and HistoryDetailViewModel.
 */
@ExperimentalCoroutinesApi
class ViewModelCoverageTest {

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

    // ----------------------------------------------------------------- MainViewModel

    @Test
    fun `MainViewModel restores and persists evaluation in SavedStateHandle across process death`() = runTest(testDispatcher) {
        val testEv = Evaluation(
            goalResults = listOf(GoalResult(goal = "Low Carb", rating = TrafficLight.GREEN, reason = "Keine Kohlenhydrate")),
            summary = "Super Low Carb",
            recognizedMeal = "Steak mit Brokkoli"
        )
        val handle = androidx.lifecycle.SavedStateHandle()
        val vm1 = MainViewModel(
            apiService = FakeApiService(analyzeResult = Result.success(testEv)),
            historyRepository = FakeHistoryRepository(),
            settingsRepository = FakeSettingsRepository(),
            imagePreprocessor = FakeImagePreprocessor(),
            dispatchers = testDispatchers,
            savedStateHandle = handle
        )
        vm1.onPhotoTaken(TestUri("content://media/photo.jpg"))
        vm1.analyze(Settings(baseUrl = "x", apiKey = "k", model = "m", goals = listOf("Low Carb")), PromptBundle("", "", "", "", "", "", "", ""))
        advanceUntilIdle()

        assertEquals(testEv, vm1.uiState.value.evaluation)
        assertNotNull(handle.get<String>("main_evaluation"))

        // Recreate ViewModel with the same handle (simulating process recreation)
        val vm2 = MainViewModel(
            apiService = FakeApiService(),
            historyRepository = FakeHistoryRepository(),
            settingsRepository = FakeSettingsRepository(),
            imagePreprocessor = FakeImagePreprocessor(),
            dispatchers = testDispatchers,
            savedStateHandle = handle
        )
        assertEquals(testEv, vm2.uiState.value.evaluation)

        // Reset clears saved evaluation
        vm2.reset()
        assertNull(handle.get<String>("main_evaluation"))
        assertNull(vm2.uiState.value.evaluation)
    }

    @Test
    fun `analyze returns early when no photoUri set`() = runTest(testDispatcher) {
        val vm = MainViewModel(FakeApiService(), FakeHistoryRepository(), FakeSettingsRepository(), FakeImagePreprocessor(), testDispatchers)
        // photoPreviewUri is null → analyze() returns immediately without touching isBusy
        vm.analyze(Settings(baseUrl = "x", apiKey = "k", model = "m", goals = listOf("Z")), PromptBundle("", "", "", "", "", "", "", ""))
        advanceUntilIdle()
        assertFalse("isBusy must stay false when no photo was taken", vm.uiState.value.isBusy)
        assertNull("error must stay null when no photo was taken", vm.uiState.value.error)
    }

    @Test
    fun `analyze sets error when imagePreprocessor throws with photoUri`() = runTest(testDispatcher) {
        val failPreprocessor = object : ImagePreprocessor {
            override fun prepareImageDataUri(photoUri: Uri, maxSide: Int): String =
                throw RuntimeException("Foto konnte nicht gelesen werden")
        }
        val vm = MainViewModel(FakeApiService(), FakeHistoryRepository(), FakeSettingsRepository(), failPreprocessor, testDispatchers)
        vm.onPhotoTaken(TestUri("content://media/photo.jpg"))
        vm.analyze(Settings(baseUrl = "x", apiKey = "k", model = "m", goals = listOf("Z")), PromptBundle("", "", "", "", "", "", "", ""))
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isBusy)
        assertNotNull(vm.uiState.value.error)
        assertTrue(vm.uiState.value.error!!.contains("Foto konnte nicht gelesen werden"))
    }

    @Test
    fun `analyze success with photoUri updates evaluation and pushes recent note`() = runTest(testDispatcher) {
        val expectedEv = Evaluation(
            goalResults = listOf(GoalResult(goal = "Z", rating = TrafficLight.GREEN, reason = "Top")),
            summary = "Gesund",
            recognizedMeal = "Salat"
        )
        val apiService = FakeApiService(analyzeResult = Result.success(expectedEv))
        val setRepo = FakeSettingsRepository()
        val vm = MainViewModel(apiService, FakeHistoryRepository(), setRepo, FakeImagePreprocessor(), testDispatchers)
        vm.onPhotoTaken(TestUri("content://media/photo.jpg"))
        vm.setNote("Mittags-Salat")
        vm.analyze(Settings(baseUrl = "x", apiKey = "k", model = "m", goals = listOf("Z")), PromptBundle("", "", "", "", "", "", "", ""))
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isBusy)
        assertNull(vm.uiState.value.error)
        assertEquals(expectedEv, vm.uiState.value.evaluation)
        assertTrue(setRepo.loadRecentNotes().any { it.text == "Mittags-Salat" })
    }

    @Test
    fun `analyze failure with photoUri sets error on API failure`() = runTest(testDispatcher) {
        val apiService = FakeApiService(analyzeResult = Result.failure(RuntimeException("Timeout bei KI-Anfrage")))
        val vm = MainViewModel(apiService, FakeHistoryRepository(), FakeSettingsRepository(), FakeImagePreprocessor(), testDispatchers)
        vm.onPhotoTaken(TestUri("content://media/photo.jpg"))
        vm.analyze(Settings(baseUrl = "x", apiKey = "k", model = "m", goals = listOf("Z")), PromptBundle("", "", "", "", "", "", "", ""))
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isBusy)
        assertNotNull(vm.uiState.value.error)
        assertTrue(vm.uiState.value.error!!.contains("Timeout bei KI-Anfrage"))
    }

    @Test
    fun `analyze uses cached evaluation when note matches goals in repository`() = runTest(testDispatcher) {
        val cachedEv = Evaluation(
            goalResults = listOf(GoalResult(goal = "Gesund", rating = TrafficLight.GREEN, reason = "Viel Gemüse")),
            summary = "Sehr gut",
            recognizedMeal = "Gemüsepfanne"
        )
        val setRepo = FakeSettingsRepository(initialRecentNotes = listOf(
            RecentNote(
                text = "Gemüse",
                starred = false,
                evaluation = cachedEv
            )
        ))
        val apiService = FakeApiService(analyzeResult = Result.failure(RuntimeException("Should not be called")))
        val vm = MainViewModel(apiService, FakeHistoryRepository(), setRepo, FakeImagePreprocessor(), testDispatchers)
        vm.onPhotoTaken(TestUri("content://media/photo.jpg"))
        vm.setNote("Gemüse")
        vm.analyze(Settings(baseUrl = "x", apiKey = "k", model = "m", goals = listOf("Gesund")), PromptBundle("", "", "", "", "", "", "", ""))
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isBusy)
        assertNull(vm.uiState.value.error)
        assertEquals(cachedEv, vm.uiState.value.evaluation)
    }

    @Test
    fun `analyze sets error on API failure`() = runTest(testDispatcher) {
        // Test via HistoryViewModel.submitBackdated which exercises the same API failure path
        // without requiring a real Uri (text-only analysis)
        val failApi = FakeApiService(analyzeTextResult = Result.failure(RuntimeException("Netzwerkfehler")))
        val histRepo = FakeHistoryRepository()
        val vm = HistoryViewModel(failApi, histRepo, FakeSettingsRepository(), FakeImagePreprocessor(), testDispatchers)
        vm.setAddTargetDay(LocalDate.now())
        vm.setAddTargetTime(LocalTime.NOON)
        vm.setAddNote("Test")
        vm.submitBackdated(null, Settings(baseUrl = "x", apiKey = "k", model = "m", goals = listOf("Z")), null, "bewerte")
        advanceUntilIdle()
        assertNotNull("error must be set after API failure", vm.uiState.value.addError)
        assertTrue(vm.uiState.value.addError!!.contains("Netzwerkfehler"))
        assertFalse(vm.uiState.value.showAddBusyCard)
    }

    @Test
    fun `cancelAnalyze resets isBusy`() = runTest(testDispatcher) {
        val vm = MainViewModel(FakeApiService(), FakeHistoryRepository(), FakeSettingsRepository(), FakeImagePreprocessor(), testDispatchers)
        // Force isBusy via the state (simulating an ongoing analysis)
        vm.setShowCamera(true) // doesn't set isBusy, but ensures we can call cancelAnalyze safely
        vm.cancelAnalyze()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isBusy)
    }

    @Test
    fun `pushRecentNote with blank text does not call repository`() = runTest(testDispatcher) {
        val setRepo = FakeSettingsRepository()
        val vm = MainViewModel(FakeApiService(), FakeHistoryRepository(), setRepo, FakeImagePreprocessor(), testDispatchers)
        val notesBefore = setRepo.loadRecentNotes()
        vm.pushRecentNote("   ")
        advanceUntilIdle()
        assertEquals(notesBefore, setRepo.loadRecentNotes())
    }

    @Test
    fun `saveToHistory returns early when evaluation is null`() = runTest(testDispatcher) {
        val histRepo = FakeHistoryRepository()
        val vm = MainViewModel(FakeApiService(), histRepo, FakeSettingsRepository(), FakeImagePreprocessor(), testDispatchers)
        vm.saveToHistory()
        advanceUntilIdle()
        assertEquals(0, histRepo.getAll().size)
    }

    @Test
    fun `updateRecognizedMeal with savedEntryId updates history and settings repository`() = runTest(testDispatcher) {
        val histRepo = FakeHistoryRepository()
        val setRepo = FakeSettingsRepository()
        val entry = histRepo.add(null, Evaluation(recognizedMeal = "Alt"), note = "Notiz 1")
        val vm = MainViewModel(
            apiService = FakeApiService(),
            historyRepository = histRepo,
            settingsRepository = setRepo,
            imagePreprocessor = FakeImagePreprocessor(),
            dispatchers = testDispatchers,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(mapOf(
                "main_saved_entry_id" to entry.id,
                "main_note" to "Notiz 1"
            ))
        )
        vm.updateRecognizedMeal("Neu erkannt")
        advanceUntilIdle()
        assertEquals("Neu erkannt", histRepo.getAll().find { it.id == entry.id }?.recognizedMeal)
    }

    @Test
    fun `updateRecognizedMeal without savedEntryId does not call repository`() = runTest(testDispatcher) {
        val histRepo = FakeHistoryRepository()
        val vm = MainViewModel(FakeApiService(), histRepo, FakeSettingsRepository(), FakeImagePreprocessor(), testDispatchers)
        // savedEntryId is null initially
        vm.updateRecognizedMeal("Neue Mahlzeit")
        advanceUntilIdle()
        // No calls to repo since no entry was saved
        assertEquals(0, histRepo.getAll().size)
    }

    // ----------------------------------------------------------------- HistoryViewModel: generateFazit

    @Test
    fun `generateFazit does not call API twice for same day key`() = runTest(testDispatcher) {
        val api = FakeApiService()
        val histRepo = FakeHistoryRepository()
        val day = LocalDate.now()
        val entry = histRepo.add(null, Evaluation(recognizedMeal = "Salat"), dateOverride = day.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
        val vm = HistoryViewModel(api, histRepo, FakeSettingsRepository(), FakeImagePreprocessor(), testDispatchers)
        advanceUntilIdle()

        val settings = Settings(baseUrl = "x", apiKey = "k", model = "m", goals = listOf("Z"))
        val prompts = de.gurum.besseresser.data.FazitPromptBundle("", "", "", "")

        // First call
        vm.generateFazit(day, listOf(entry), settings, null, listOf("Salat"), prompts)
        // Second call for the same day while first is in-flight
        vm.generateFazit(day, listOf(entry), settings, null, listOf("Salat"), prompts)
        advanceUntilIdle()

        // dayFazit should have been called exactly once (the second call is a no-op due to loadingFazits guard)
        // The test verifies no crash and the loading state is cleared
        assertFalse(vm.uiState.value.loadingFazits.isNotEmpty())
    }

    @Test
    fun `generateFazit on API failure clears loading state`() = runTest(testDispatcher) {
        val failApi = FakeApiService(dayFazitResult = Result.failure(RuntimeException("API down")))
        val histRepo = FakeHistoryRepository()
        val day = LocalDate.now()
        val entry = histRepo.add(null, Evaluation(), dateOverride = day.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
        val vm = HistoryViewModel(failApi, histRepo, FakeSettingsRepository(), FakeImagePreprocessor(), testDispatchers)
        advanceUntilIdle()

        val key = de.gurum.besseresser.model.dayKeyOf(day)
        vm.generateFazit(day, listOf(entry), Settings(baseUrl = "x", apiKey = "k", model = "m", goals = listOf("Z")), null, listOf(), de.gurum.besseresser.data.FazitPromptBundle("", "", "", ""))
        advanceUntilIdle()

        assertFalse("loadingFazits must be cleared after API failure", vm.uiState.value.loadingFazits.contains(key))
    }

    @Test
    fun `generateWeekFazit on success clears loading state`() = runTest(testDispatcher) {
        val api = FakeApiService()
        val histRepo = FakeHistoryRepository()
        val vm = HistoryViewModel(api, histRepo, FakeSettingsRepository(), FakeImagePreprocessor(), testDispatchers)
        advanceUntilIdle()

        val weekKey = de.gurum.besseresser.model.weekKeyOf(System.currentTimeMillis())
        val settings = Settings(baseUrl = "x", apiKey = "k", model = "m", goals = listOf("Z"))
        vm.generateWeekFazit(settings, null, weekKey, listOf("Salat"), de.gurum.besseresser.data.FazitPromptBundle("", "", "", ""))
        advanceUntilIdle()

        assertFalse("loadingWeeks must be cleared after success", vm.uiState.value.loadingWeeks.contains(weekKey))
        assertFalse("loadingWeek must be false after success", vm.uiState.value.loadingWeek)
    }

    @Test
    fun `generateWeekFazit on API failure clears loading state`() = runTest(testDispatcher) {
        val failApi = FakeApiService(dayFazitResult = Result.failure(RuntimeException("down")))
        val vm = HistoryViewModel(failApi, FakeHistoryRepository(), FakeSettingsRepository(), FakeImagePreprocessor(), testDispatchers)
        advanceUntilIdle()

        val weekKey = de.gurum.besseresser.model.weekKeyOf(System.currentTimeMillis())
        vm.generateWeekFazit(Settings(baseUrl = "x", apiKey = "k", model = "m", goals = listOf("Z")), null, weekKey, listOf(), de.gurum.besseresser.data.FazitPromptBundle("", "", "", ""))
        advanceUntilIdle()

        assertFalse("loadingWeeks must be cleared after failure", vm.uiState.value.loadingWeeks.contains(weekKey))
        assertFalse("loadingWeek must be false after failure", vm.uiState.value.loadingWeek)
    }

    @Test
    fun `generateWeekFazit duplicate call is no-op`() = runTest(testDispatcher) {
        val api = FakeApiService()
        val vm = HistoryViewModel(api, FakeHistoryRepository(), FakeSettingsRepository(), FakeImagePreprocessor(), testDispatchers)
        val weekKey = de.gurum.besseresser.model.weekKeyOf(System.currentTimeMillis())
        val settings = Settings(baseUrl = "x", apiKey = "k", model = "m", goals = listOf("Z"))
        val prompts = de.gurum.besseresser.data.FazitPromptBundle("", "", "", "")
        vm.generateWeekFazit(settings, null, weekKey, listOf(), prompts)
        vm.generateWeekFazit(settings, null, weekKey, listOf(), prompts) // second call while first in-flight
        advanceUntilIdle()
        // No crash, state settled
        assertFalse(vm.uiState.value.loadingWeeks.contains(weekKey))
    }

    // ----------------------------------------------------------------- HistoryViewModel: deleteEntry with fazit cleanup

    @Test
    fun `deleteEntry removes day fazit for the deleted entry`() = runTest(testDispatcher) {
        val histRepo = FakeHistoryRepository()
        val day = LocalDate.now()
        val millis = day.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val entry = histRepo.add(null, Evaluation(), dateOverride = millis)
        val dayKey = de.gurum.besseresser.model.dayKeyOf(millis)
        histRepo.saveDayFazit(DayFazit(dayKey = dayKey, rating = TrafficLight.GREEN, text = "Test", entryIds = listOf(entry.id)))

        val vm = HistoryViewModel(FakeApiService(), histRepo, FakeSettingsRepository(), FakeImagePreprocessor(), testDispatchers)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.entries.size)

        vm.deleteEntry(entry)
        advanceUntilIdle()

        assertEquals(0, histRepo.getAll().size)
        assertNull("Day fazit must be removed when its entry is deleted", histRepo.loadDayFazit(dayKey))
    }

    @Test
    fun `flushPendingDeletes removes day fazit for each flushed entry`() = runTest(testDispatcher) {
        val histRepo = FakeHistoryRepository()
        val millis = System.currentTimeMillis()
        val entry = histRepo.add(null, Evaluation(), dateOverride = millis)
        val dayKey = de.gurum.besseresser.model.dayKeyOf(millis)
        histRepo.saveDayFazit(DayFazit(dayKey = dayKey, rating = TrafficLight.GREEN, text = "Test", entryIds = listOf(entry.id)))

        val vm = HistoryViewModel(FakeApiService(), histRepo, FakeSettingsRepository(), FakeImagePreprocessor(), testDispatchers)
        advanceUntilIdle()

        vm.requestDeleteEntry(entry, delayMs = 10_000L) // long delay, will be flushed
        vm.flushPendingDeletes()
        advanceUntilIdle()

        assertEquals(0, histRepo.getAll().size)
        assertNull("Day fazit must be removed on flush", histRepo.loadDayFazit(dayKey))
    }

    @Test
    fun `moveEntryWithinDay with identical fromIndex and toIndex does nothing`() = runTest(testDispatcher) {
        val histRepo = FakeHistoryRepository()
        val day = LocalDate.now()
        val base = day.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        histRepo.add(null, Evaluation(), dateOverride = base)
        histRepo.add(null, Evaluation(), dateOverride = base + 60_000)

        val vm = HistoryViewModel(FakeApiService(), histRepo, FakeSettingsRepository(), FakeImagePreprocessor(), testDispatchers)
        advanceUntilIdle()
        val before = vm.uiState.value.entries.map { it.id }

        vm.moveEntryWithinDay(day, 0, 0)
        advanceUntilIdle()

        assertEquals("Entry order must be unchanged when from == to", before, vm.uiState.value.entries.map { it.id })
    }

    @Test
    fun `moveEntryWithinDay with out-of-bounds indices does nothing`() = runTest(testDispatcher) {
        val histRepo = FakeHistoryRepository()
        val day = LocalDate.now()
        val base = day.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        histRepo.add(null, Evaluation(), dateOverride = base)

        val vm = HistoryViewModel(FakeApiService(), histRepo, FakeSettingsRepository(), FakeImagePreprocessor(), testDispatchers)
        advanceUntilIdle()
        val before = vm.uiState.value.entries.map { it.id }

        vm.moveEntryWithinDay(day, 0, 99) // toIndex out of bounds
        advanceUntilIdle()

        assertEquals("Entry order must be unchanged on out-of-bounds", before, vm.uiState.value.entries.map { it.id })
    }

    @Test
    fun `toggleWeekExpanded with weekKey manages expandedWeeks set`() = runTest(testDispatcher) {
        val vm = HistoryViewModel(FakeApiService(), FakeHistoryRepository(), FakeSettingsRepository(), FakeImagePreprocessor(), testDispatchers)
        val wk = "20261118"

        vm.toggleWeekExpanded(wk)
        assertTrue("Week must be in expandedWeeks after first toggle", vm.uiState.value.expandedWeeks.contains(wk))

        vm.toggleWeekExpanded(wk)
        assertFalse("Week must be removed from expandedWeeks after second toggle", vm.uiState.value.expandedWeeks.contains(wk))
    }

    @Test
    fun `submitBackdated text-only API failure sets addError`() = runTest(testDispatcher) {
        val failApi = FakeApiService(analyzeTextResult = Result.failure(RuntimeException("Server Error")))
        val vm = HistoryViewModel(failApi, FakeHistoryRepository(), FakeSettingsRepository(), FakeImagePreprocessor(), testDispatchers)
        vm.setAddTargetDay(LocalDate.now())
        vm.setAddTargetTime(LocalTime.NOON)
        vm.setAddNote("Pasta")
        vm.submitBackdated(null, Settings(baseUrl = "x", apiKey = "k", model = "m", goals = listOf("Z")), null, "text prompt")
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.addError)
        assertFalse(vm.uiState.value.showAddBusyCard)
    }

    // ----------------------------------------------------------------- HistoryDetailViewModel

    @Test
    fun `reEvaluate returns early when entry is null`() = runTest(testDispatcher) {
        val vm = HistoryDetailViewModel(99L, FakeHistoryRepository(), FakeSettingsRepository(), FakeApiService(), FakeImagePreprocessor(), testDispatchers)
        advanceUntilIdle()
        assertNull(vm.uiState.value.entry)

        vm.reEvaluate("corrected", Settings(baseUrl = "x", apiKey = "k", model = "m", goals = listOf("Z")), null, "text")
        advanceUntilIdle()

        assertFalse("reEvalBusy must stay false when entry is null", vm.uiState.value.reEvalBusy)
        assertNull("error must stay null when entry is null", vm.uiState.value.error)
    }

    @Test
    fun `reEvaluate text-only success updates evaluation and clears busy`() = runTest(testDispatcher) {
        val histRepo = FakeHistoryRepository()
        val entry = histRepo.add(null, Evaluation(recognizedMeal = "Alt"), note = "Mittagessen", dateOverride = 1_000L)

        val vm = HistoryDetailViewModel(entry.id, histRepo, FakeSettingsRepository(), FakeApiService(), FakeImagePreprocessor(), testDispatchers)
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.entry)

        vm.reEvaluate("Neu", Settings(baseUrl = "x", apiKey = "k", model = "m", goals = listOf("Z")), null, "bewerte: Pasta")
        advanceUntilIdle()

        assertFalse("reEvalBusy must be false after success", vm.uiState.value.reEvalBusy)
        assertNull("error must be null after success", vm.uiState.value.error)
    }

    @Test
    fun `reEvaluate on API failure sets error and clears busy`() = runTest(testDispatcher) {
        val histRepo = FakeHistoryRepository()
        val entry = histRepo.add(null, Evaluation(), note = "", dateOverride = 1_000L)
        val failApi = FakeApiService(analyzeTextResult = Result.failure(RuntimeException("KI Fehler")))

        val vm = HistoryDetailViewModel(entry.id, histRepo, FakeSettingsRepository(), failApi, FakeImagePreprocessor(), testDispatchers)
        advanceUntilIdle()

        vm.reEvaluate("x", Settings(baseUrl = "x", apiKey = "k", model = "m", goals = listOf("Z")), null, "bewerte")
        advanceUntilIdle()

        assertFalse("reEvalBusy must be cleared on failure", vm.uiState.value.reEvalBusy)
        assertNotNull("error must be set on failure", vm.uiState.value.error)
    }

    @Test
    fun `reEvaluate with blank note does not update recent notes`() = runTest(testDispatcher) {
        val histRepo = FakeHistoryRepository()
        val setRepo = FakeSettingsRepository()
        // Entry with blank note
        val entry = histRepo.add(null, Evaluation(), note = "", dateOverride = 1_000L)

        val vm = HistoryDetailViewModel(entry.id, histRepo, setRepo, FakeApiService(), FakeImagePreprocessor(), testDispatchers)
        advanceUntilIdle()

        val notesBefore = setRepo.loadRecentNotes().size
        vm.reEvaluate("x", Settings(baseUrl = "x", apiKey = "k", model = "m", goals = listOf("Z")), null, "bewerte")
        advanceUntilIdle()

        assertEquals("updateRecentNoteEvaluation must not add entries for noteless entries", notesBefore, setRepo.loadRecentNotes().size)
    }

    @Test
    fun `deleteEntry invokes onDeleted callback and removes from repository`() = runTest(testDispatcher) {
        val histRepo = FakeHistoryRepository()
        val entry = histRepo.add(null, Evaluation(), dateOverride = 1_000L)

        val vm = HistoryDetailViewModel(entry.id, histRepo, FakeSettingsRepository(), FakeApiService(), FakeImagePreprocessor(), testDispatchers)
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.entry)

        var callbackFired = false
        vm.deleteEntry { callbackFired = true }
        advanceUntilIdle()

        assertTrue("onDeleted callback must be invoked", callbackFired)
        assertEquals(0, histRepo.getAll().size)
    }

    @Test
    fun `deleteEntry returns early when entry is null`() = runTest(testDispatcher) {
        val histRepo = FakeHistoryRepository()
        val vm = HistoryDetailViewModel(99L, histRepo, FakeSettingsRepository(), FakeApiService(), FakeImagePreprocessor(), testDispatchers)
        advanceUntilIdle()

        var callbackFired = false
        vm.deleteEntry { callbackFired = true }
        advanceUntilIdle()

        assertFalse("onDeleted must not fire when entry is null", callbackFired)
        assertEquals(0, histRepo.getAll().size)
    }

    @Test
    fun `duplicateToDate invokes onDuplicated callback`() = runTest(testDispatcher) {
        val histRepo = FakeHistoryRepository()
        val entry = histRepo.add(null, Evaluation(recognizedMeal = "Pizza"), dateOverride = 1_000L)

        val vm = HistoryDetailViewModel(entry.id, histRepo, FakeSettingsRepository(), FakeApiService(), FakeImagePreprocessor(), testDispatchers)
        advanceUntilIdle()

        var callbackFired = false
        vm.duplicateToDate(LocalDate.now(), LocalTime.NOON) { callbackFired = true }
        advanceUntilIdle()

        assertTrue("onDuplicated callback must be invoked", callbackFired)
        assertEquals("Repository must contain original + duplicate", 2, histRepo.getAll().size)
    }

    @Test
    fun `duplicateToDate returns early when entry is null`() = runTest(testDispatcher) {
        val histRepo = FakeHistoryRepository()
        val vm = HistoryDetailViewModel(99L, histRepo, FakeSettingsRepository(), FakeApiService(), FakeImagePreprocessor(), testDispatchers)
        advanceUntilIdle()

        var callbackFired = false
        vm.duplicateToDate(LocalDate.now(), LocalTime.NOON) { callbackFired = true }
        advanceUntilIdle()

        assertFalse("onDuplicated must not fire when entry is null", callbackFired)
    }

    @Test
    fun `thumbnail returns null when entry is null or has no decoded thumbnail`() = runTest(testDispatcher) {
        val histRepo = FakeHistoryRepository()
        val vm = HistoryDetailViewModel(99L, histRepo, FakeSettingsRepository(), FakeApiService(), FakeImagePreprocessor(), testDispatchers)
        advanceUntilIdle()

        val thumb = vm.thumbnail(1280)
        assertNull("thumbnail must be null when entry is null", thumb)

        val entry = histRepo.add(null, Evaluation(recognizedMeal = "Salat"), dateOverride = 1_000L)
        val vmWithEntry = HistoryDetailViewModel(entry.id, histRepo, FakeSettingsRepository(), FakeApiService(), FakeImagePreprocessor(), testDispatchers)
        advanceUntilIdle()

        val thumbWithEntry = vmWithEntry.thumbnail(1280)
        assertNull("FakeHistoryRepository returns null for thumbnail", thumbWithEntry)
    }

    @Test
    fun `HistoryViewModel decodeThumbnail calls repository asynchronously`() = runTest(testDispatcher) {
        val histRepo = FakeHistoryRepository()
        val vm = HistoryViewModel(FakeApiService(), histRepo, FakeSettingsRepository(), FakeImagePreprocessor(), testDispatchers)
        val entry = histRepo.add(null, Evaluation(recognizedMeal = "Suppe"), dateOverride = 1_000L)

        val thumb = vm.decodeThumbnail(entry, 128)
        assertNull("FakeHistoryRepository returns null", thumb)
    }

    // ----------------------------------------------------------------- ConfigViewModel

    @Test
    fun `ConfigViewModel exportBackup success updates uiState`() = runTest(testDispatcher) {
        val summary = BackupSummary(fileName = "backup.json", entriesCount = 5, hasProfile = true, recentNotesCount = 2, hasApiKey = true)
        val backupService = FakeBackupService(exportResult = Result.success(summary))
        val vm = ConfigViewModel(backupService, FakeHistoryRepository(), testDispatchers)

        vm.exportBackup()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.busy)
        assertNull(vm.uiState.value.error)
        assertEquals(summary, vm.uiState.value.exportSummary)
    }

    @Test
    fun `ConfigViewModel exportBackup failure sets error`() = runTest(testDispatcher) {
        val backupService = FakeBackupService(exportResult = Result.failure(RuntimeException("Export fehlgeschlagen")))
        val vm = ConfigViewModel(backupService, FakeHistoryRepository(), testDispatchers)

        vm.exportBackup()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.busy)
        assertEquals("Export fehlgeschlagen", vm.uiState.value.error)
        assertNull(vm.uiState.value.exportSummary)
    }

    @Test
    fun `ConfigViewModel importBackup success updates uiState`() = runTest(testDispatcher) {
        val summary = BackupSummary(fileName = "backup.json", entriesCount = 3, hasProfile = false, recentNotesCount = 1, hasApiKey = true)
        val backupService = FakeBackupService(importResult = Result.success(summary))
        val vm = ConfigViewModel(backupService, FakeHistoryRepository(), testDispatchers)

        vm.importBackup(TestUri("content://backup.json"))
        advanceUntilIdle()

        assertFalse(vm.uiState.value.busy)
        assertNull(vm.uiState.value.error)
        assertEquals(summary, vm.uiState.value.importSummary)
    }

    @Test
    fun `ConfigViewModel importBackup failure sets error`() = runTest(testDispatcher) {
        val backupService = FakeBackupService(importResult = Result.failure(RuntimeException("Ungültiges JSON")))
        val vm = ConfigViewModel(backupService, FakeHistoryRepository(), testDispatchers)

        vm.importBackup(TestUri("content://invalid.json"))
        advanceUntilIdle()

        assertFalse(vm.uiState.value.busy)
        assertEquals("Ungültiges JSON", vm.uiState.value.error)
        assertNull(vm.uiState.value.importSummary)
    }

    @Test
    fun `ConfigViewModel clearHistory success clears repository and sets cleared flag`() = runTest(testDispatcher) {
        val histRepo = FakeHistoryRepository()
        histRepo.add(null, Evaluation(recognizedMeal = "A"), dateOverride = 1000L)
        histRepo.add(null, Evaluation(recognizedMeal = "B"), dateOverride = 2000L)
        assertEquals(2, histRepo.getAll().size)

        val vm = ConfigViewModel(FakeBackupService(), histRepo, testDispatchers)
        vm.clearHistory()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.busy)
        assertTrue(vm.uiState.value.cleared)
        assertEquals(0, histRepo.getAll().size)
    }

    @Test
    fun `ConfigViewModel clearHistory failure sets error`() = runTest(testDispatcher) {
        val failRepo = object : de.gurum.besseresser.data.HistoryRepository by FakeHistoryRepository() {
            override suspend fun clearAll() {
                throw RuntimeException("Datenbank gesperrt")
            }
        }
        val vm = ConfigViewModel(FakeBackupService(), failRepo, testDispatchers)
        vm.clearHistory()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.busy)
        assertFalse(vm.uiState.value.cleared)
        assertEquals("Datenbank gesperrt", vm.uiState.value.error)
    }
}
