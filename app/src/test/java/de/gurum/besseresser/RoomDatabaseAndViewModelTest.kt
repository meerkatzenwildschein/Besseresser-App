package de.gurum.besseresser

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import java.time.LocalDate
import java.time.LocalTime
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
import de.gurum.besseresser.data.AppDatabase
import de.gurum.besseresser.data.AppDatabase_Impl
import de.gurum.besseresser.data.HistoryEntryEntity
import de.gurum.besseresser.data.RoomConverters
import androidx.sqlite.db.SupportSQLiteDatabase
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
    fun testAppDatabaseImplSchemaAndVersion() {
        val dbImpl = AppDatabase_Impl()
        val delegateMethod = AppDatabase_Impl::class.java.getDeclaredMethod("createOpenDelegate")
        delegateMethod.isAccessible = true
        val openDelegate = delegateMethod.invoke(dbImpl) as androidx.room.RoomOpenDelegate
        assertEquals(3, openDelegate.version)
    }

    @Test
    fun testMigration1to2AddsSortOrder() {
        val executedSql = mutableListOf<String>()
        val fakeDb = java.lang.reflect.Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                executedSql.add(args[0] as String)
            }
            null
        } as SupportSQLiteDatabase

        AppDatabase.MIGRATION_1_2.migrate(fakeDb)

        assertTrue(executedSql.any { it.contains("ADD COLUMN sortOrder") })
        assertTrue(executedSql.any { it.contains("SET sortOrder = dateMillis") })
    }

    @Test
    fun testMigration2to3CreatesIndices() {
        val executedSql = mutableListOf<String>()
        val fakeDb = java.lang.reflect.Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                executedSql.add(args[0] as String)
            }
            null
        } as SupportSQLiteDatabase

        AppDatabase.MIGRATION_2_3.migrate(fakeDb)

        assertTrue(executedSql.any { it.contains("index_history_entries_dateMillis") && it.contains("dateMillis") })
        assertTrue(executedSql.any { it.contains("index_history_entries_sortOrder") && it.contains("sortOrder") })
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
        assertEquals(listOf("Ziel A", "Ziel B"), state.goals)

        viewModel.setModel("gpt-4o-mini")
        assertEquals("gpt-4o-mini", viewModel.uiState.value.model)

        viewModel.setGoals("Neues Ziel")
        assertEquals(listOf("Neues Ziel"), viewModel.uiState.value.goals)
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
    fun testHistoryDetailViewModelLoadsViaGetByIdFlow() = runTest(testDispatcher) {
        val historyRepo = FakeHistoryRepository()
        val settingsRepo = FakeSettingsRepository()
        val apiService = FakeApiService()
        val imagePreprocessor = FakeImagePreprocessor()

        val ev = Evaluation(recognizedMeal = "Pasta", goalResults = listOf(GoalResult("Z1", TrafficLight.GREEN, "Super")))
        val entry = historyRepo.add(null, ev, note = "Mittag", dateOverride = 500L)

        val viewModel = HistoryDetailViewModel(
            entryId = entry.id,
            historyRepository = historyRepo,
            settingsRepository = settingsRepo,
            apiService = apiService,
            imagePreprocessor = imagePreprocessor,
            dispatchers = de.gurum.besseresser.data.AppDispatchers(io = testDispatcher)
        )

        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.loaded)
        assertNotNull(viewModel.uiState.value.entry)
        assertEquals(entry.id, viewModel.uiState.value.entry?.id)
        assertEquals("Pasta", viewModel.uiState.value.entry?.recognizedMeal)

        historyRepo.updateRecognizedMeal(entry.id, "Vollkorn-Pasta")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Vollkorn-Pasta", viewModel.uiState.value.entry?.recognizedMeal)

        collectJob.cancel()
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
    fun testAppStateViewModelUiStateTransitions() = runTest(testDispatcher) {
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
        profileRepo.save(newProfile)

        testDispatcher.scheduler.advanceUntilIdle()

        val updatedState = viewModel.uiState.value
        assertEquals(AppThemeMode.DARK, updatedState.themeMode)
        assertEquals(AppLanguage.EN, updatedState.language)
        assertEquals(newSettings, updatedState.settings)
        assertEquals(newProfile, updatedState.profile)
    }

    @Test
    fun testProfileViewModelSavesCentrallyAndAppStateReceivesFlowUpdate() = runTest(testDispatcher) {
        val settingsRepo = FakeSettingsRepository()
        val profileRepo = FakeProfileRepository()
        val uiPrefsRepo = FakeUiPrefsRepository()
        val historyRepo = FakeHistoryRepository()

        val appStateViewModel = AppStateViewModel(settingsRepo, profileRepo, uiPrefsRepo, historyRepo)
        val profileViewModel = ProfileViewModel(profileRepo)

        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(Profile(), appStateViewModel.uiState.value.profile)

        profileViewModel.setBirthDate("1992-06-15")
        profileViewModel.setWeightText("68,5")
        profileViewModel.setHeightText("172")
        profileViewModel.setGender(Gender.FEMALE)
        profileViewModel.setNotesText("Keine Laktose")

        var onSavedCalled = false
        val success = profileViewModel.save(onSaved = { onSavedCalled = true })
        assertTrue(success)
        assertTrue(onSavedCalled)

        testDispatcher.scheduler.advanceUntilIdle()

        val expectedProfile = Profile(
            birthDate = "1992-06-15",
            weightKg = 68.5,
            heightCm = 172,
            gender = Gender.FEMALE,
            notes = "Keine Laktose"
        )
        // Repository has received the saved profile
        assertEquals(expectedProfile, profileRepo.currentProfile())
        // AppStateViewModel received the update purely reactively via Flow without duplicate save call
        assertEquals(expectedProfile, appStateViewModel.uiState.value.profile)
    }

    @Test
    fun testMainViewModelSavedStateHandlePersistenceAndRestoration() {
        val handle = SavedStateHandle()
        val viewModel1 = MainViewModel(
            apiService = FakeApiService(),
            historyRepository = FakeHistoryRepository(),
            settingsRepository = FakeSettingsRepository(),
            imagePreprocessor = FakeImagePreprocessor(),
            savedStateHandle = handle
        )

        viewModel1.setNote("Frühstücks-Müsli")
        viewModel1.setMenuOpen(true)
        viewModel1.setShowCamera(true)
        viewModel1.setShowReview(true)
        viewModel1.setshowRecognizedEdit(true)

        // Simuliere Prozess-Beendigung und Wiederherstellung via SavedStateHandle
        val viewModel2 = MainViewModel(
            apiService = FakeApiService(),
            historyRepository = FakeHistoryRepository(),
            settingsRepository = FakeSettingsRepository(),
            imagePreprocessor = FakeImagePreprocessor(),
            savedStateHandle = handle
        )

        val restoredState = viewModel2.uiState.value
        assertEquals("Frühstücks-Müsli", restoredState.note)
        assertTrue(restoredState.menuOpen)
        assertTrue(restoredState.showCamera)
        assertTrue(restoredState.showReview)
        assertTrue(restoredState.showRecognizedEdit)

        // Reset löscht SavedState
        viewModel2.reset()
        val viewModel3 = MainViewModel(
            apiService = FakeApiService(),
            historyRepository = FakeHistoryRepository(),
            settingsRepository = FakeSettingsRepository(),
            imagePreprocessor = FakeImagePreprocessor(),
            savedStateHandle = handle
        )
        assertEquals("", viewModel3.uiState.value.note)
        assertFalse(viewModel3.uiState.value.menuOpen)
        assertFalse(viewModel3.uiState.value.showCamera)
        assertFalse(viewModel3.uiState.value.showReview)
        assertFalse(viewModel3.uiState.value.showRecognizedEdit)
    }

    @Test
    fun testHistoryViewModelSavedStateHandlePersistenceAndRestoration() {
        val handle = SavedStateHandle()
        val historyRepo = FakeHistoryRepository()
        val viewModel1 = HistoryViewModel(
            apiService = FakeApiService(),
            historyRepository = historyRepo,
            settingsRepository = FakeSettingsRepository(),
            imagePreprocessor = FakeImagePreprocessor(),
            savedStateHandle = handle
        )

        val targetDay = LocalDate.of(2026, 11, 19)
        val targetTime = LocalTime.of(14, 30)

        viewModel1.setAddNote("Zwischenmahlzeit Apfel")
        viewModel1.setAddTargetDay(targetDay)
        viewModel1.setAddTargetTime(targetTime)
        viewModel1.setAddDialogForDay(targetDay)
        viewModel1.setShowAddCamera(true)
        viewModel1.setShowAddReview(true)
        viewModel1.setShowAddText(true)
        viewModel1.toggleFazitExpanded("2026-11-19")
        viewModel1.toggleWeekExpanded("2026-W47")

        // Simuliere Prozess-Wiederherstellung mit demselben SavedStateHandle
        val viewModel2 = HistoryViewModel(
            apiService = FakeApiService(),
            historyRepository = historyRepo,
            settingsRepository = FakeSettingsRepository(),
            imagePreprocessor = FakeImagePreprocessor(),
            savedStateHandle = handle
        )

        val restoredState = viewModel2.uiState.value
        assertEquals("Zwischenmahlzeit Apfel", restoredState.addNote)
        assertEquals(targetDay, restoredState.addTargetDay)
        assertEquals(targetTime, restoredState.addTargetTime)
        assertEquals(targetDay, restoredState.addDialogForDay)
        assertTrue(restoredState.showAddCamera)
        assertTrue(restoredState.showAddReview)
        assertTrue(restoredState.showAddText)
        assertTrue(restoredState.expandedFazits.contains("2026-11-19"))
        assertTrue(restoredState.expandedWeeks.contains("2026-W47"))
        assertTrue(restoredState.expandedWeek)
    }

    @Test
    fun testSetupViewModelSavedStateHandlePersistenceAndPreservationOnReinit() {
        val handle = SavedStateHandle()
        val viewModel1 = SetupViewModel(
            apiService = FakeApiService(),
            backupService = FakeBackupService(),
            settingsRepository = FakeSettingsRepository(),
            savedStateHandle = handle
        )

        viewModel1.setUrl("https://my-custom-proxy.internal")
        viewModel1.setKey("sk-secret-key-42")
        viewModel1.setModel("claude-3-5-sonnet")
        viewModel1.setGoals("Mehr Wasser trinken\nKein Zucker")
        viewModel1.setRetentionWeeks("24")
        viewModel1.setShowModels(true)
        viewModel1.setShowProviders(true)
        viewModel1.setShowSplit(true)
        viewModel1.setSplitEdited("Split Test")

        // Simuliere Prozess-Beendigung und Wiederherstellung
        val viewModel2 = SetupViewModel(
            apiService = FakeApiService(),
            backupService = FakeBackupService(),
            settingsRepository = FakeSettingsRepository(),
            savedStateHandle = handle
        )

        val stateAfterRecreate = viewModel2.uiState.value
        assertEquals("https://my-custom-proxy.internal", stateAfterRecreate.url)
        assertEquals("sk-secret-key-42", stateAfterRecreate.key)
        assertEquals("claude-3-5-sonnet", stateAfterRecreate.model)
        assertEquals(listOf("Mehr Wasser trinken", "Kein Zucker"), stateAfterRecreate.goals)
        assertTrue(stateAfterRecreate.goalsDirty)
        assertEquals("24", stateAfterRecreate.retentionWeeks)
        assertTrue(stateAfterRecreate.showModels)
        assertTrue(stateAfterRecreate.showProviders)
        assertTrue(stateAfterRecreate.showSplit)
        assertEquals("Split Test", stateAfterRecreate.splitEdited)

        // Wenn SetupScreen bei Neuerstellung LaunchedEffect(initial) { init(...) } aufruft,
        // dürfen ungespeicherte Benutzereingaben nicht durch alte Settings überschrieben werden!
        val defaultSettings = Settings("https://api.openai.com/v1", "sk-old", "gpt-4o", listOf("Altes Ziel"))
        viewModel2.init(defaultSettings, listOf("Default Goal"))

        val stateAfterScreenReinit = viewModel2.uiState.value
        assertEquals("https://my-custom-proxy.internal", stateAfterScreenReinit.url)
        assertEquals("sk-secret-key-42", stateAfterScreenReinit.key)
        assertEquals(listOf("Mehr Wasser trinken", "Kein Zucker"), stateAfterScreenReinit.goals)

        // Bei explizitem force = true (z.B. nach Backup-Import) wird überschrieben
        viewModel2.init(defaultSettings, listOf("Default Goal"), force = true)
        val stateAfterForcedInit = viewModel2.uiState.value
        assertEquals("https://api.openai.com/v1", stateAfterForcedInit.url)
        assertEquals("sk-old", stateAfterForcedInit.key)
        assertEquals(listOf("Altes Ziel"), stateAfterForcedInit.goals)
    }
}
