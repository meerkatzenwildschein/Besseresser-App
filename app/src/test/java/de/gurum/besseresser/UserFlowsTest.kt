package de.gurum.besseresser

import android.net.TestUri
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
import de.gurum.besseresser.model.HistoryEntry
import de.gurum.besseresser.model.Settings
import de.gurum.besseresser.model.TrafficLight
import de.gurum.besseresser.model.toWeightedGoals
import de.gurum.besseresser.ui.HistoryDetailViewModel
import de.gurum.besseresser.ui.HistoryViewModel
import de.gurum.besseresser.ui.MainViewModel
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
            goals = listOf("Wenig Zucker", "Viel Protein").toWeightedGoals(),
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
            assertTrue(goals.any { it.text == "Wenig Zucker" })
            assertTrue(goals.any { it.text == "Viel Protein" })
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
        assertEquals(listOf("Ziel A", "Ziel B").toWeightedGoals(), vm.uiState.value.goals)
    }

    @Test
    fun `setup flow - startSplitGoals with successful API response adds goals directly as chips`() =
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

            assertFalse(vm.uiState.value.splitting)
            assertEquals(listOf("Ziel 1", "Ziel 2", "Ziel 3").toWeightedGoals(), vm.uiState.value.goals)
        }

    @Test
    fun `setup flow - goal reordering and removal operations`() = runTest(testDispatcher) {
        val vm = SetupViewModel(
            apiService = FakeApiService(),
            backupService = FakeBackupService(),
            settingsRepository = FakeSettingsRepository()
        )
        vm.setGoals(listOf("A", "B", "C"))
        assertEquals(listOf("A", "B", "C").toWeightedGoals(), vm.uiState.value.goals)

        // Move B up -> [B, A, C]
        vm.moveGoalUp(1)
        assertEquals(listOf("B", "A", "C").toWeightedGoals(), vm.uiState.value.goals)

        // Move B down -> [A, B, C]
        vm.moveGoalDown(0)
        assertEquals(listOf("A", "B", "C").toWeightedGoals(), vm.uiState.value.goals)

        // Remove item at index 1 (B) -> [A, C]
        vm.removeGoal(1)
        assertEquals(listOf("A", "C").toWeightedGoals(), vm.uiState.value.goals)

        // Out of bounds operations are ignored safely
        vm.moveGoalUp(0)
        vm.moveGoalDown(1)
        vm.removeGoal(5)
        assertEquals(listOf("A", "C").toWeightedGoals(), vm.uiState.value.goals)
    }

    @Test
    fun `setup flow - setGoalWeight updates individual goal weight while keeping text and other goals intact`() = runTest(testDispatcher) {
        val vm = SetupViewModel(
            apiService = FakeApiService(),
            backupService = FakeBackupService(),
            settingsRepository = FakeSettingsRepository()
        )
        vm.setGoals(listOf("Ziel 1", "Ziel 2", "Ziel 3"))
        assertEquals(listOf(3, 3, 3), vm.uiState.value.goals.map { it.weight })

        // Set weight of Ziel 2 to 5 (Sehr hoch)
        vm.setGoalWeight(1, 5)
        assertEquals(5, vm.uiState.value.goals[1].weight)
        assertEquals(3, vm.uiState.value.goals[0].weight)
        assertEquals(3, vm.uiState.value.goals[2].weight)
        assertEquals(listOf("Ziel 1", "Ziel 2", "Ziel 3"), vm.uiState.value.goals.map { it.text })

        // Set weight of Ziel 1 to 1 (Sehr niedrig)
        vm.setGoalWeight(0, 1)
        assertEquals(1, vm.uiState.value.goals[0].weight)

        // Out-of-bounds weight clamped to 1..5
        vm.setGoalWeight(2, 99)
        assertEquals(5, vm.uiState.value.goals[2].weight)

        vm.setGoalWeight(2, -10)
        assertEquals(1, vm.uiState.value.goals[2].weight)

        // Reordering maintains weights
        vm.moveGoalUp(1) // Ziel 2 (weight 5) moves to index 0
        assertEquals("Ziel 2", vm.uiState.value.goals[0].text)
        assertEquals(5, vm.uiState.value.goals[0].weight)
        assertEquals("Ziel 1", vm.uiState.value.goals[1].text)
        assertEquals(1, vm.uiState.value.goals[1].weight)
    }

    @Test
    fun `setup flow - first run populates default goals as explicit chips`() = runTest(testDispatcher) {
        val vm = SetupViewModel(
            apiService = FakeApiService(),
            backupService = FakeBackupService(),
            settingsRepository = FakeSettingsRepository()
        )
        val defaultGoals = listOf("Blutdruck senken", "Weniger Zucker", "Gewicht verlieren", "Gesünder ernähren")
        vm.init(Settings(), defaultGoals)

        assertEquals(defaultGoals.toWeightedGoals(), vm.uiState.value.goals)
    }

    @Test
    fun `setup flow - addGoalsFromText appends to existing chips without clearing them`() = runTest(testDispatcher) {
        val splitResult = listOf("Mehr Wasser", "Kein Alkohol")
        val fakeApi = FakeApiService(splitGoalsResult = Result.success(splitResult))
        val vm = SetupViewModel(
            apiService = fakeApi,
            backupService = FakeBackupService(),
            settingsRepository = FakeSettingsRepository()
        )
        vm.setGoals(listOf("Bestehendes Ziel"))
        vm.setNewGoalText("Mehr Wasser trinken und keinen Alkohol mehr")

        vm.addGoalsFromText()
        advanceUntilIdle()

        assertEquals(listOf("Bestehendes Ziel", "Mehr Wasser", "Kein Alkohol").toWeightedGoals(), vm.uiState.value.goals)
        assertEquals("", vm.uiState.value.newGoalText)
        assertFalse(vm.uiState.value.splitting)
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
    fun `history detail - updateRecognizedMeal persists new text`() = runTest(testDispatcher) {
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

        vm.updateRecognizedMeal("Veggie-Burger")
        advanceUntilIdle()

        assertEquals("Veggie-Burger", histRepo.getAll().first { it.id == entry.id }.recognizedMeal)
    }

    // ----------------------------------------------------------------- 4. Photo capture file lifecycle cleanup

    @Test
    fun `main flow - reset cleans up unsaved photo capture file`() = runTest(testDispatcher) {
        val fakeCaptureManager = FakePhotoCaptureManager()
        val vm = MainViewModel(
            apiService = FakeApiService(),
            historyRepository = FakeHistoryRepository(),
            settingsRepository = FakeSettingsRepository(),
            imagePreprocessor = FakeImagePreprocessor(),
            dispatchers = testDispatchers,
            photoCaptureManager = fakeCaptureManager
        )

        val testUri = TestUri("content://de.gurum.besseresser.fileprovider/photos/temp1.jpg")
        vm.onPhotoTaken(testUri)
        assertEquals(testUri, vm.uiState.value.photoPreviewUri)

        vm.reset()
        advanceUntilIdle()

        assertTrue(fakeCaptureManager.deletedUris.contains(testUri))
    }

    @Test
    fun `main flow - saveToHistory cleans up temporary capture file`() = runTest(testDispatcher) {
        val fakeCaptureManager = FakePhotoCaptureManager()
        val histRepo = FakeHistoryRepository()
        val vm = MainViewModel(
            apiService = FakeApiService(),
            historyRepository = histRepo,
            settingsRepository = FakeSettingsRepository(),
            imagePreprocessor = FakeImagePreprocessor(),
            dispatchers = testDispatchers,
            photoCaptureManager = fakeCaptureManager
        )

        val testUri = TestUri("content://de.gurum.besseresser.fileprovider/photos/temp2.jpg")
        vm.onPhotoTaken(testUri)
        vm.analyze(
            Settings(baseUrl = "https://test.api", apiKey = "key", model = "m", goals = listOf("Ziel").toWeightedGoals()),
            PromptBundle("", "", "", "", "", "", "", "")
        )
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.evaluation)

        vm.saveToHistory()
        advanceUntilIdle()

        assertTrue(fakeCaptureManager.deletedUris.contains(testUri))
        assertEquals(1, histRepo.getAll().size)
    }

    @Test
    fun `history flow - saveBackdatedEntry cleans up temporary capture file`() = runTest(testDispatcher) {
        val fakeCaptureManager = FakePhotoCaptureManager()
        val histRepo = FakeHistoryRepository()
        val vm = HistoryViewModel(
            apiService = FakeApiService(),
            historyRepository = histRepo,
            settingsRepository = FakeSettingsRepository(),
            imagePreprocessor = FakeImagePreprocessor(),
            dispatchers = testDispatchers,
            photoCaptureManager = fakeCaptureManager
        )

        val testUri = TestUri("content://de.gurum.besseresser.fileprovider/photos/temp3.jpg")
        vm.setAddTargetDay(LocalDate.now())
        vm.setAddTargetTime(LocalTime.now())
        vm.saveBackdatedEntry(
            photoUriIn = testUri,
            ev = Evaluation(recognizedMeal = "Suppe")
        )
        advanceUntilIdle()

        assertTrue(fakeCaptureManager.deletedUris.contains(testUri))
        assertEquals(1, histRepo.getAll().size)
    }

    @Test
    fun `share image flow - opens review screen with shared image and note, then analyzes and saves`() = runTest(testDispatcher) {
        val cachedUri = TestUri("content://de.gurum.besseresser.fileprovider/photos/shared_copy.jpg")
        val fakeCaptureManager = FakePhotoCaptureManager(
            file = java.io.File("shared_copy.jpg"),
            uri = cachedUri
        )
        val histRepo = FakeHistoryRepository()
        val setRepo = FakeSettingsRepository()
        val apiService = FakeApiService()
        val vm = MainViewModel(
            apiService = apiService,
            historyRepository = histRepo,
            settingsRepository = setRepo,
            imagePreprocessor = FakeImagePreprocessor(),
            dispatchers = testDispatchers,
            photoCaptureManager = fakeCaptureManager
        )

        val externalSharedUri = TestUri("content://com.external.gallery/images/42")
        vm.onSharedImageReceived(externalSharedUri, "  Vollkornpasta mit Pesto  ")

        // Verify review screen state is active with copied URI and trimmed note
        assertTrue(vm.uiState.value.showReview)
        assertFalse(vm.uiState.value.showCamera)
        assertFalse(vm.uiState.value.isBusy)
        assertEquals(cachedUri, vm.uiState.value.photoPreviewUri)
        assertEquals("Vollkornpasta mit Pesto", vm.uiState.value.note)
        assertTrue(fakeCaptureManager.copiedSourceUris.contains(externalSharedUri))

        // User taps "Jetzt bewerten" in MainReviewScreen
        vm.pushRecentNote(vm.uiState.value.note)
        vm.onPhotoTaken(vm.uiState.value.photoPreviewUri!!)
        vm.analyze(
            Settings(baseUrl = "https://test.api", apiKey = "key", model = "m", goals = listOf("Ziel").toWeightedGoals()),
            PromptBundle("", "", "", "", "", "", "", "")
        )
        advanceUntilIdle()

        assertFalse(vm.uiState.value.showReview)
        assertFalse(vm.uiState.value.isBusy)
        assertNotNull(vm.uiState.value.evaluation)

        // Save to history and verify temporary cached file is deleted
        vm.saveToHistory()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.savedToHistory)
        assertTrue(fakeCaptureManager.deletedUris.contains(cachedUri))
        assertEquals(1, histRepo.getAll().size)
        assertEquals("Vollkornpasta mit Pesto", histRepo.getAll().first().note)
    }

    @Test
    fun `share image flow - replacing an unsaved capture cleans up previous capture file`() = runTest(testDispatcher) {
        val firstUri = TestUri("content://de.gurum.besseresser.fileprovider/photos/first.jpg")
        val secondUri = TestUri("content://de.gurum.besseresser.fileprovider/photos/second.jpg")
        val fakeCaptureManager = FakePhotoCaptureManager(
            file = java.io.File("second.jpg"),
            uri = secondUri
        )
        val vm = MainViewModel(
            apiService = FakeApiService(),
            historyRepository = FakeHistoryRepository(),
            settingsRepository = FakeSettingsRepository(),
            imagePreprocessor = FakeImagePreprocessor(),
            dispatchers = testDispatchers,
            photoCaptureManager = fakeCaptureManager
        )

        // Simulate existing unsaved capture
        vm.onPhotoTaken(firstUri)
        // Receive shared image before saving first capture
        vm.onSharedImageReceived(TestUri("content://external/99"), null)

        assertTrue(fakeCaptureManager.deletedUris.contains(firstUri))
        assertEquals(secondUri, vm.uiState.value.photoPreviewUri)
        assertEquals("", vm.uiState.value.note)
        assertTrue(vm.uiState.value.showReview)
    }

    @Test
    fun `offline main flow - shows no-internet error banner and allows saving unrated meal to history`() = runTest(testDispatcher) {
        val captureUri = TestUri("content://de.gurum.besseresser.fileprovider/photos/offline_meal.jpg")
        val fakeCaptureManager = FakePhotoCaptureManager(
            file = java.io.File("offline_meal.jpg"),
            uri = captureUri
        )
        val histRepo = FakeHistoryRepository()
        val setRepo = FakeSettingsRepository()
        val vm = MainViewModel(
            apiService = FakeApiService(),
            historyRepository = histRepo,
            settingsRepository = setRepo,
            imagePreprocessor = FakeImagePreprocessor(),
            dispatchers = testDispatchers,
            photoCaptureManager = fakeCaptureManager,
            networkChecker = { false }
        )

        vm.setNote("Linseneintopf")
        vm.onPhotoTaken(captureUri)
        vm.analyze(
            Settings(baseUrl = "https://test.api", apiKey = "key", model = "m", goals = listOf("Ziel").toWeightedGoals()),
            PromptBundle("", "", "", "", "", "", "", "")
        )
        advanceUntilIdle()

        // Must not call AI, must set offlineNoRating = true and show error message
        assertFalse(vm.uiState.value.isBusy)
        assertEquals(null, vm.uiState.value.evaluation)
        assertTrue(vm.uiState.value.offlineNoRating)
        assertNotNull(vm.uiState.value.error)

        // User can save unrated meal to history
        vm.saveToHistory()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.savedToHistory)
        assertTrue(fakeCaptureManager.deletedUris.contains(captureUri))
        assertEquals(1, histRepo.getAll().size)
        val savedEntry = histRepo.getAll().first()
        assertFalse(savedEntry.isRated)
        assertEquals("Linseneintopf", savedEntry.note)
        assertTrue(savedEntry.goalResults.isEmpty())
    }

    @Test
    fun `offline backdated flow - saves unrated entry without calling AI and opens detail view`() = runTest(testDispatcher) {
        val histRepo = FakeHistoryRepository()
        val setRepo = FakeSettingsRepository()
        val apiService = FakeApiService()
        val vm = HistoryViewModel(
            apiService = apiService,
            historyRepository = histRepo,
            settingsRepository = setRepo,
            imagePreprocessor = FakeImagePreprocessor(),
            dispatchers = testDispatchers,
            networkChecker = { false }
        )

        val targetDay = LocalDate.of(2026, 11, 20)
        vm.setAddTargetDay(targetDay)
        vm.setAddTargetTime(LocalTime.of(13, 15))
        vm.setAddNote("Gemüsepfanne")

        var pickedEntry: HistoryEntry? = null
        vm.submitBackdated(
            photoUri = null,
            settings = Settings(baseUrl = "https://test.api", apiKey = "key", model = "m", goals = listOf("Ziel").toWeightedGoals()),
            promptBundle = null,
            textPrompt = "Prompt",
            onPick = { pickedEntry = it }
        )
        advanceUntilIdle()

        assertEquals(0, apiService.analyzeTextCallCount)
        val entry = requireNotNull(pickedEntry)
        assertFalse(entry.isRated)
        assertEquals("Gemüsepfanne", entry.note)
        assertEquals(1, histRepo.getAll().size)
    }

    @Test
    fun `unrated entry detail flow - blocks AI evaluation when still offline, succeeds when online`() = runTest(testDispatcher) {
        val histRepo = FakeHistoryRepository()
        val setRepo = FakeSettingsRepository()
        val apiService = FakeApiService()
        val unratedEntry = histRepo.add(
            photoUri = null,
            ev = Evaluation(),
            note = "Haferflocken mit Beeren"
        )
        assertFalse(unratedEntry.isRated)

        var online = false
        val detailVm = HistoryDetailViewModel(
            entryId = unratedEntry.id,
            historyRepository = histRepo,
            settingsRepository = setRepo,
            apiService = apiService,
            imagePreprocessor = FakeImagePreprocessor(),
            dispatchers = testDispatchers,
            networkChecker = { online }
        )
        advanceUntilIdle()

        // Try rating while still offline
        detailVm.reEvaluate(
            correctedText = "Haferflocken mit Beeren",
            settings = Settings(baseUrl = "https://test.api", apiKey = "key", model = "m", goals = listOf("Z1").toWeightedGoals()),
            photoPromptBundle = null,
            textPrompt = "Evaluate Haferflocken"
        )
        advanceUntilIdle()

        assertNotNull(detailVm.uiState.value.error)
        assertFalse(histRepo.getAll().first().isRated)

        // Now go online and trigger AI rating again
        online = true
        detailVm.reEvaluate(
            correctedText = "Haferflocken mit Beeren",
            settings = Settings(baseUrl = "https://test.api", apiKey = "key", model = "m", goals = listOf("Z1").toWeightedGoals()),
            photoPromptBundle = null,
            textPrompt = "Evaluate Haferflocken"
        )
        advanceUntilIdle()

        assertEquals(null, detailVm.uiState.value.error)
        val updatedEntry = histRepo.getAll().first()
        assertTrue(updatedEntry.isRated)
        assertEquals("Apfel und Nüsse", updatedEntry.recognizedMeal)
        assertEquals(1, updatedEntry.goalResults.size)
    }

    @Test
    fun `api error main flow - shows error banner and allows saving unrated meal to history`() = runTest(testDispatcher) {
        val captureUri = TestUri("content://de.gurum.besseresser.fileprovider/photos/failed_ai_meal.jpg")
        val fakeCaptureManager = FakePhotoCaptureManager(
            file = java.io.File("failed_ai_meal.jpg"),
            uri = captureUri
        )
        val histRepo = FakeHistoryRepository()
        val setRepo = FakeSettingsRepository()
        val failingApi = FakeApiService(
            analyzeResult = Result.failure(RuntimeException("HTTP 503 Service Unavailable"))
        )
        val vm = MainViewModel(
            apiService = failingApi,
            historyRepository = histRepo,
            settingsRepository = setRepo,
            imagePreprocessor = FakeImagePreprocessor(),
            dispatchers = testDispatchers,
            photoCaptureManager = fakeCaptureManager,
            networkChecker = { true }
        )

        vm.setNote("Kürbissuppe")
        vm.onPhotoTaken(captureUri)
        vm.analyze(
            Settings(baseUrl = "https://test.api", apiKey = "key", model = "m", goals = listOf("Ziel").toWeightedGoals()),
            PromptBundle("", "", "", "", "", "", "", "")
        )
        advanceUntilIdle()

        // AI failed, so error must be displayed and saving without rating must be enabled
        assertFalse(vm.uiState.value.isBusy)
        assertEquals(null, vm.uiState.value.evaluation)
        assertTrue(vm.uiState.value.offlineNoRating)
        assertEquals("HTTP 503 Service Unavailable", vm.uiState.value.error)

        vm.saveToHistory()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.savedToHistory)
        assertTrue(fakeCaptureManager.deletedUris.contains(captureUri))
        assertEquals(1, histRepo.getAll().size)
        val savedEntry = histRepo.getAll().first()
        assertFalse(savedEntry.isRated)
        assertEquals("Kürbissuppe", savedEntry.note)
    }

    @Test
    fun `api error backdated flow - saves unrated entry to history and records error when AI fails`() = runTest(testDispatcher) {
        val histRepo = FakeHistoryRepository()
        val setRepo = FakeSettingsRepository()
        val failingApi = FakeApiService(
            analyzeTextResult = Result.failure(RuntimeException("API Timeout"))
        )
        val vm = HistoryViewModel(
            apiService = failingApi,
            historyRepository = histRepo,
            settingsRepository = setRepo,
            imagePreprocessor = FakeImagePreprocessor(),
            dispatchers = testDispatchers,
            networkChecker = { true }
        )

        vm.setAddTargetDay(LocalDate.of(2026, 11, 21))
        vm.setAddTargetTime(LocalTime.of(19, 0))
        vm.setAddNote("Abendbrot")

        var pickedEntry: HistoryEntry? = null
        vm.submitBackdated(
            photoUri = null,
            settings = Settings(baseUrl = "https://test.api", apiKey = "key", model = "m", goals = listOf("Ziel").toWeightedGoals()),
            promptBundle = null,
            textPrompt = "Prompt",
            onPick = { pickedEntry = it }
        )
        advanceUntilIdle()

        assertEquals(1, failingApi.analyzeTextCallCount)
        assertEquals("API Timeout", vm.uiState.value.addError)
        val entry = requireNotNull(pickedEntry)
        assertFalse(entry.isRated)
        assertEquals("Abendbrot", entry.note)
        assertEquals(1, histRepo.getAll().size)
    }

    @Test
    fun `backdated with photo flow - setShowAddCamera allocates capture file and submitBackdated saves and cleans up`() = runTest(testDispatcher) {
        val captureFile = java.io.File("backdated_photo.jpg")
        val captureUri = TestUri("content://de.gurum.besseresser.fileprovider/photos/backdated_photo.jpg")
        val fakeCaptureManager = FakePhotoCaptureManager(
            file = captureFile,
            uri = captureUri
        )
        val histRepo = FakeHistoryRepository()
        val setRepo = FakeSettingsRepository()
        val apiService = FakeApiService()
        val vm = HistoryViewModel(
            apiService = apiService,
            historyRepository = histRepo,
            settingsRepository = setRepo,
            imagePreprocessor = FakeImagePreprocessor(),
            dispatchers = testDispatchers,
            photoCaptureManager = fakeCaptureManager
        )

        // User taps "+" -> selects "With photo"
        val targetDay = LocalDate.of(2026, 11, 22)
        val targetTime = LocalTime.of(12, 45)
        vm.setAddTargetDay(targetDay)
        vm.setAddTargetTime(targetTime)
        vm.setAddDialogForDay(null)
        vm.setShowAddCamera(true)

        // Camera overlay precondition: both showAddCamera and addPhotoFile must be non-null
        assertTrue(vm.uiState.value.showAddCamera)
        assertEquals(captureFile, vm.uiState.value.addPhotoFile)
        assertEquals(captureUri, vm.uiState.value.addPhotoUri)
        assertEquals(1, fakeCaptureManager.callCount)

        // User enters note and confirms photo capture in CameraCaptureScreen
        vm.setAddNote("Griechischer Salat")
        vm.setShowAddCamera(false)
        assertFalse("Capture file must NOT be deleted when camera closes on confirm", fakeCaptureManager.deletedFiles.contains(captureFile))

        var pickedEntry: HistoryEntry? = null
        vm.submitBackdated(
            photoUri = vm.uiState.value.addPhotoUri,
            settings = Settings(baseUrl = "https://test.api", apiKey = "key", model = "m", goals = listOf("Z1").toWeightedGoals()),
            promptBundle = PromptBundle("", "", "", "", "", "", "", ""),
            textPrompt = null,
            onPick = { pickedEntry = it }
        )
        advanceUntilIdle()

        val entry = requireNotNull(pickedEntry)
        assertTrue(entry.isRated)
        assertEquals("Griechischer Salat", entry.note)
        assertTrue(fakeCaptureManager.deletedUris.contains(captureUri))
        assertNull(vm.uiState.value.addPhotoFile)
        assertNull(vm.uiState.value.addPhotoUri)
        assertFalse(vm.uiState.value.showAddCamera)
    }

    @Test
    fun `backdated with photo flow - cancelAddPhotoCapture deletes temporary capture file and resets state`() = runTest(testDispatcher) {
        val captureFile = java.io.File("cancelled_backdated.jpg")
        val captureUri = TestUri("content://de.gurum.besseresser.fileprovider/photos/cancelled_backdated.jpg")
        val fakeCaptureManager = FakePhotoCaptureManager(
            file = captureFile,
            uri = captureUri
        )
        val vm = HistoryViewModel(
            apiService = FakeApiService(),
            historyRepository = FakeHistoryRepository(),
            settingsRepository = FakeSettingsRepository(),
            imagePreprocessor = FakeImagePreprocessor(),
            dispatchers = testDispatchers,
            photoCaptureManager = fakeCaptureManager
        )

        vm.setShowAddCamera(true)
        assertEquals(captureFile, vm.uiState.value.addPhotoFile)

        // User cancels camera overlay
        vm.cancelAddPhotoCapture()

        assertFalse(vm.uiState.value.showAddCamera)
        assertFalse(vm.uiState.value.showAddReview)
        assertNull(vm.uiState.value.addPhotoFile)
        assertNull(vm.uiState.value.addPhotoUri)
        assertTrue(fakeCaptureManager.deletedFiles.contains(captureFile))
    }
}
