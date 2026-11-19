package de.gurum.besseresser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import de.gurum.besseresser.model.DayFazit
import de.gurum.besseresser.model.Evaluation
import de.gurum.besseresser.model.Gender
import de.gurum.besseresser.model.GoalResult
import de.gurum.besseresser.model.HistoryEntry
import de.gurum.besseresser.ui.AppStateViewModel
import de.gurum.besseresser.ui.ConfigViewModel
import de.gurum.besseresser.ui.HistoryDetailViewModel
import de.gurum.besseresser.ui.HistoryViewModel
import de.gurum.besseresser.ui.MainViewModel
import de.gurum.besseresser.ui.ProfileViewModel
import de.gurum.besseresser.ui.RecentNotesViewModel
import de.gurum.besseresser.data.RoomConverters
import de.gurum.besseresser.ui.SetupViewModel
import de.gurum.besseresser.data.BackupService
import de.gurum.besseresser.data.BackupSummary
import de.gurum.besseresser.data.ImagePreprocessor
import de.gurum.besseresser.data.toEntity
import de.gurum.besseresser.data.toWeekEntity
import de.gurum.besseresser.model.AppLanguage
import de.gurum.besseresser.model.AppThemeMode
import de.gurum.besseresser.model.RecentNote
import de.gurum.besseresser.model.TrafficLight
import de.gurum.besseresser.model.Profile
import de.gurum.besseresser.model.Settings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class RoomDatabaseAndViewModelTest {

    private val converters = RoomConverters()
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testRoomGoalResultConverters() {
        val goals = listOf(
            GoalResult("Ziel 1", TrafficLight.GREEN, "Begründung 1"),
            GoalResult("Ziel 2", TrafficLight.YELLOW, "Begründung 2")
        )
        val json = converters.fromGoalResultList(goals)
        assertTrue(json.contains("Ziel 1"))
        val restored = converters.toGoalResultList(json)
        assertEquals(goals, restored)
    }

    @Test
    fun testRoomLongListConverters() {
        val ids = listOf(101L, 202L, 303L)
        val json = converters.fromLongList(ids)
        assertTrue(json.contains("101"))
        val restored = converters.toLongList(json)
        assertEquals(ids, restored)
    }

    @Test
    fun testRoomTrafficLightConverters() {
        assertEquals("gruen", converters.fromTrafficLight(TrafficLight.GREEN))
        assertEquals("gelb", converters.fromTrafficLight(TrafficLight.YELLOW))
        assertEquals("rot", converters.fromTrafficLight(TrafficLight.RED))
        assertEquals("unbekannt", converters.fromTrafficLight(TrafficLight.UNKNOWN))

        assertEquals(TrafficLight.GREEN, converters.toTrafficLight("gruen"))
        assertEquals(TrafficLight.YELLOW, converters.toTrafficLight("gelb"))
        assertEquals(TrafficLight.RED, converters.toTrafficLight("rot"))
    }

    @Test
    fun testHistoryEntryEntityMapping() {
        val domain = HistoryEntry(
            id = 42L,
            dateMillis = 1700000000000L,
            photoFile = "42.jpg",
            goalResults = listOf(GoalResult("Z1", TrafficLight.GREEN, "B1")),
            summary = "Summary",
            note = "Note",
            recognizedMeal = "Pizza",
            backdated = true,
            sortOrder = 1700000005000L
        )
        val entity = domain.toEntity()
        assertEquals(domain.id, entity.id)
        assertEquals(domain.dateMillis, entity.dateMillis)
        assertEquals(domain.photoFile, entity.photoFile)
        assertEquals(domain.goalResults, entity.goalResults)
        assertEquals(domain.summary, entity.summary)
        assertEquals(domain.note, entity.note)
        assertEquals(domain.recognizedMeal, entity.recognizedMeal)
        assertEquals(domain.backdated, entity.backdated)
        assertEquals(domain.sortOrder, entity.sortOrder)

        val restoredDomain = entity.toDomain()
        assertEquals(domain, restoredDomain)
    }

    @Test
    fun testDayFazitEntityMapping() {
        val domain = DayFazit(
            dayKey = "20261106",
            rating = TrafficLight.GREEN,
            text = "Super Tag",
            entryIds = listOf(1L, 2L),
            createdAt = 1730000000000L,
            fingerprint = "fp123"
        )
        val entity = domain.toEntity()
        assertEquals(domain.dayKey, entity.dayKey)
        assertEquals(domain.rating, entity.rating)
        assertEquals(domain.text, entity.text)
        assertEquals(domain.entryIds, entity.entryIds)
        assertEquals(domain.createdAt, entity.createdAt)
        assertEquals(domain.fingerprint, entity.fingerprint)

        val restored = entity.toDomain()
        assertEquals(domain, restored)
    }

    @Test
    fun testWeekFazitEntityMapping() {
        val domain = DayFazit(
            dayKey = "20261102",
            rating = TrafficLight.YELLOW,
            text = "Gute Woche",
            entryIds = listOf(10L, 20L),
            createdAt = 1730000000000L,
            fingerprint = "fp_week"
        )
        val entity = domain.toWeekEntity()
        assertEquals(domain.dayKey, entity.weekKey)
        assertEquals(domain.rating, entity.rating)
        assertEquals(domain.text, entity.text)
        assertEquals(domain.entryIds, entity.entryIds)
        assertEquals(domain.createdAt, entity.createdAt)
        assertEquals(domain.fingerprint, entity.fingerprint)

        val restored = entity.toDomain()
        assertEquals(domain, restored)
    }

    @Test
    fun testMainViewModelUiStateTransitions() {
        val viewModel = MainViewModel(FakeApiService(), FakeHistoryRepository(), FakeSettingsRepository(), FakeImagePreprocessor())
        val initial = viewModel.uiState.value
        assertNull(initial.evaluation)
        assertFalse(initial.isBusy)
        assertNull(initial.error)

        viewModel.setNote("Lecker Salat")
        assertEquals("Lecker Salat", viewModel.uiState.value.note)

        viewModel.setShowCamera(true)
        assertTrue(viewModel.uiState.value.showCamera)

        viewModel.setShowReview(true)
        assertTrue(viewModel.uiState.value.showReview)

        viewModel.setMenuOpen(true)
        assertTrue(viewModel.uiState.value.menuOpen)

        viewModel.reset()
        assertFalse(viewModel.uiState.value.showCamera)
        assertFalse(viewModel.uiState.value.showReview)
        assertEquals("", viewModel.uiState.value.note)
    }

    @Test
    fun testHistoryViewModelUiStateTransitions() {
        val viewModel = HistoryViewModel(FakeApiService(), FakeHistoryRepository(), FakeSettingsRepository(), FakeImagePreprocessor())
        val initial = viewModel.uiState.value
        assertTrue(initial.entries.isEmpty())
        assertTrue(initial.loadingFazits.isEmpty())

        viewModel.toggleFazitExpanded("20261106")
        assertTrue(viewModel.uiState.value.expandedFazits.contains("20261106"))
        viewModel.toggleFazitExpanded("20261106")
        assertFalse(viewModel.uiState.value.expandedFazits.contains("20261106"))

        viewModel.toggleWeekExpanded()
        assertTrue(viewModel.uiState.value.expandedWeek)
        viewModel.toggleWeekExpanded()
        assertFalse(viewModel.uiState.value.expandedWeek)
    }

    @Test
    fun testProfileViewModelUiStateTransitions() {
        val viewModel = ProfileViewModel(FakeProfileRepository())
        val profile = Profile(
            birthDate = "1985-10-20",
            weightKg = 82.0,
            heightCm = 178,
            notes = "Test Note",
            gender = Gender.MALE
        )
        viewModel.initFromProfile(profile)
        val state = viewModel.uiState.value
        assertEquals("1985-10-20", state.birthDate)
        assertEquals("82", state.weightText)
        assertEquals("178", state.heightText)
        assertEquals("Test Note", state.notesText)
        assertEquals(Gender.MALE, state.gender)

        viewModel.setWeightText("83,5")
        assertEquals("83,5", viewModel.uiState.value.weightText)

        viewModel.setGender(Gender.FEMALE)
        assertEquals(Gender.FEMALE, viewModel.uiState.value.gender)
    }

    @Test
    fun testSetupViewModelUiStateTransitions() {
        val viewModel = SetupViewModel(FakeApiService(), FakeBackupService(), FakeSettingsRepository())
        val settings = Settings(
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-test",
            model = "gpt-4o",
            goals = listOf("Ziel A", "Ziel B")
        )
        viewModel.init(settings, listOf("Default Goal"))
        val state = viewModel.uiState.value
        assertEquals("https://api.openai.com/v1", state.url)
        assertEquals("sk-test", state.key)
        assertEquals("gpt-4o", state.model)
        assertEquals("Ziel A\nZiel B", state.goals)

        viewModel.setModel("gpt-4o-mini")
        assertEquals("gpt-4o-mini", viewModel.uiState.value.model)

        viewModel.setGoals("Neues Ziel")
        assertEquals("Neues Ziel", viewModel.uiState.value.goals)
        assertTrue(viewModel.uiState.value.goalsDirty)
    }

    @Test
    fun testHistoryDetailViewModelUiStateTransitions() {
        val historyRepo = FakeHistoryRepository()
        val settingsRepo = FakeSettingsRepository()
        val apiService = FakeApiService()
        val imagePreprocessor = FakeImagePreprocessor()

        val viewModel = HistoryDetailViewModel(
            entryId = 100L,
            historyRepository = historyRepo,
            settingsRepository = settingsRepo,
            apiService = apiService,
            imagePreprocessor = imagePreprocessor
        )

        val state = viewModel.uiState.value
        assertNull(state.entry)
        assertFalse(state.reEvalBusy)
        assertNull(state.error)

        viewModel.clearError()
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun testConfigViewModelUiStateTransitions() {
        val backupService = FakeBackupService()
        val historyRepo = FakeHistoryRepository()
        val viewModel = ConfigViewModel(backupService, historyRepo)

        val state = viewModel.uiState.value
        assertFalse(state.busy)
        assertNull(state.importSummary)
        assertNull(state.exportSummary)
        assertFalse(state.cleared)
        assertNull(state.error)

        viewModel.clearHistory()
        for (i in 0 until 50) {
            testDispatcher.scheduler.advanceUntilIdle()
            if (viewModel.uiState.value.cleared) break
            Thread.sleep(20)
        }
        assertTrue(viewModel.uiState.value.cleared)
    }

    @Test
    fun testRecentNotesViewModelUiStateTransitions() = runTest(testDispatcher) {
        val note = RecentNote("Salat", starred = false)
        val settingsRepo = FakeSettingsRepository(initialRecentNotes = listOf(note))
        val viewModel = RecentNotesViewModel(settingsRepo)

        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.notes.collect()
        }

        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.toggleStar(note)
        testDispatcher.scheduler.advanceUntilIdle()
        val updated = checkNotNull(viewModel.notes.value.firstOrNull { it.text == "Salat" })
        assertTrue(updated.starred)

        val newNote = RecentNote("Obst", starred = false)
        viewModel.push(newNote)
        testDispatcher.scheduler.advanceUntilIdle()
        val pushed = checkNotNull(viewModel.notes.value.firstOrNull { it.text == "Obst" })
        assertFalse(pushed.starred)

        collectJob.cancel()
    }

    @Test
    fun testRecentNotesViewModelReactiveUpdatesFromRepository() = runTest(testDispatcher) {
        val settingsRepo = FakeSettingsRepository(initialRecentNotes = listOf(RecentNote("Suppe", starred = false)))
        val viewModel = RecentNotesViewModel(settingsRepo)

        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.notes.collect()
        }

        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.notes.value.size)
        assertEquals("Suppe", viewModel.notes.value.first().text)

        // Externes Hinzufügen über Repository (z.B. MainViewModel oder HistoryViewModel)
        settingsRepo.pushRecentNote("Pizza")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, viewModel.notes.value.size)
        assertTrue(viewModel.notes.value.any { it.text == "Pizza" })

        // Externes Favorisieren
        settingsRepo.toggleStarRecentNote("Pizza")
        testDispatcher.scheduler.advanceUntilIdle()

        val pizza = checkNotNull(viewModel.notes.value.firstOrNull { it.text == "Pizza" })
        assertTrue(pizza.starred)
        // Bei sortStarredFirst = true muss Pizza an erster Stelle stehen
        assertEquals("Pizza", viewModel.notes.value.first().text)

        collectJob.cancel()
    }

    @Test
    fun testAppStateViewModelUiStateTransitions() {
        val settingsRepo = FakeSettingsRepository()
        val profileRepo = FakeProfileRepository()
        val uiPrefsRepo = FakeUiPrefsRepository()
        val historyRepo = FakeHistoryRepository()

        val viewModel = AppStateViewModel(settingsRepo, profileRepo, uiPrefsRepo, historyRepo)
        testDispatcher.scheduler.advanceUntilIdle()
        val state = viewModel.uiState.value
        assertTrue(state.loaded)

        viewModel.setThemeMode(AppThemeMode.DARK)
        viewModel.setLanguage(AppLanguage.EN)
        val newSettings = Settings(baseUrl = "https://custom.api", apiKey = "sk-custom", model = "gpt-4o", goals = listOf("Goal 1"))
        viewModel.saveSettings(newSettings)
        val newProfile = Profile(birthDate = "1990-01-01", weightKg = 70.0, heightCm = 175, gender = Gender.MALE)
        viewModel.saveProfile(newProfile)

        testDispatcher.scheduler.advanceUntilIdle()

        val updatedState = viewModel.uiState.value
        assertEquals(AppThemeMode.DARK, updatedState.themeMode)
        assertEquals(AppLanguage.EN, updatedState.language)
        assertEquals(newSettings, updatedState.settings)
        assertEquals(newProfile, updatedState.profile)
    }
}
