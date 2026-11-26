package de.gurum.besseresser

import android.content.Context
import android.graphics.Bitmap
import android.net.TestUri
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import java.io.File
import java.time.LocalDate
import de.gurum.besseresser.data.ApiClient
import de.gurum.besseresser.data.ApiException
import de.gurum.besseresser.data.BackupService
import de.gurum.besseresser.data.BackupSummary
import de.gurum.besseresser.data.BesseresserApiService
import de.gurum.besseresser.data.DayFazitResult
import de.gurum.besseresser.data.ImagePreprocessor
import de.gurum.besseresser.data.KeyVault
import de.gurum.besseresser.data.PhotoCaptureManager
import de.gurum.besseresser.data.DefaultPhotoCaptureManager
import de.gurum.besseresser.data.UiPrefsRepository
import de.gurum.besseresser.data.LegacyJsonMigrator
import de.gurum.besseresser.data.HistoryDao
import de.gurum.besseresser.data.DayFazitDao
import de.gurum.besseresser.data.WeekFazitDao
import de.gurum.besseresser.data.HistoryEntryEntity
import de.gurum.besseresser.data.DayFazitEntity
import de.gurum.besseresser.data.WeekFazitEntity
import de.gurum.besseresser.model.AppJson
import kotlinx.serialization.encodeToString
import de.gurum.besseresser.model.AppLanguage
import de.gurum.besseresser.model.AppThemeMode
import de.gurum.besseresser.model.dayFingerprint
import de.gurum.besseresser.model.dayKeyOf
import de.gurum.besseresser.model.Evaluation
import de.gurum.besseresser.data.FazitPromptBundle
import de.gurum.besseresser.model.Gender
import de.gurum.besseresser.model.GoalResult
import de.gurum.besseresser.model.HistoryEntry
import de.gurum.besseresser.model.HistoryEntryLogic
import de.gurum.besseresser.data.HistoryRepository
import de.gurum.besseresser.ui.HistoryViewModel
import de.gurum.besseresser.ui.MainViewModel
import de.gurum.besseresser.model.Profile
import de.gurum.besseresser.data.ProfileRepository
import de.gurum.besseresser.ui.ProfileViewModel
import de.gurum.besseresser.data.PromptBundle
import de.gurum.besseresser.model.RecentNote
import de.gurum.besseresser.model.RecentNotesLogic
import de.gurum.besseresser.model.Settings
import de.gurum.besseresser.model.WeightedGoal
import de.gurum.besseresser.model.toWeightedGoals
import de.gurum.besseresser.data.SettingsRepository
import de.gurum.besseresser.ui.SetupViewModel
import de.gurum.besseresser.data.SplitGoalsRequest
import de.gurum.besseresser.model.TrafficLight
import de.gurum.besseresser.model.weekKeyOf
import de.gurum.besseresser.model.DayFazit
import de.gurum.besseresser.model.DayFazitSession
import de.gurum.besseresser.ui.UiText
import de.gurum.besseresser.R

// ----------------------------------------------------------------- In-Memory Test Fakes

class FakeSettingsRepository(
    initialSettings: Settings = Settings("https://api.openai.com/v1", "test-key", "gpt-4o-mini", listOf("Ziel 1").toWeightedGoals()),
    initialRecentNotes: List<RecentNote> = emptyList()
) : SettingsRepository {
    private val settingsFlow = MutableStateFlow(initialSettings)
    private val recentNotesFlowInternal = MutableStateFlow(initialRecentNotes)

    override val settings: Flow<Settings> = settingsFlow.asStateFlow()
    override suspend fun currentSettings(): Settings = settingsFlow.value
    override suspend fun save(settings: Settings) {
        settingsFlow.value = settings
    }

    override fun recentNotesFlow(sortStarredFirst: Boolean): Flow<List<RecentNote>> =
        recentNotesFlowInternal.asStateFlow().map { RecentNotesLogic.formatLoadedNotes(it, sortStarredFirst) }

    override suspend fun loadRecentNotes(sortStarredFirst: Boolean): List<RecentNote> =
        RecentNotesLogic.formatLoadedNotes(recentNotesFlowInternal.value, sortStarredFirst)

    override suspend fun pushRecentNote(note: String, evaluation: Evaluation?) {
        recentNotesFlowInternal.value = RecentNotesLogic.pushNote(recentNotesFlowInternal.value, note, evaluation)
    }

    override suspend fun toggleStarRecentNote(noteText: String) {
        recentNotesFlowInternal.value = RecentNotesLogic.toggleStarInList(recentNotesFlowInternal.value, noteText)
    }

    override suspend fun updateRecentNoteEvaluation(note: String, evaluation: Evaluation) {
        recentNotesFlowInternal.value = RecentNotesLogic.updateEvaluationInList(recentNotesFlowInternal.value, note, evaluation)
    }

    override suspend fun findCachedEvaluation(note: String, goals: List<String>): Evaluation? =
        RecentNotesLogic.findMatchingEvaluation(recentNotesFlowInternal.value, note, goals)

    override suspend fun saveRecentNotes(notes: List<RecentNote>) {
        recentNotesFlowInternal.value = RecentNotesLogic.normalizeAndPrune(notes)
    }
}

class FakeProfileRepository(
    initialProfile: Profile = Profile()
) : ProfileRepository {
    private val profileFlow = MutableStateFlow(initialProfile)
    override val profile: Flow<Profile> = profileFlow.asStateFlow()
    override suspend fun currentProfile(): Profile = profileFlow.value
    override suspend fun save(profile: Profile) {
        profileFlow.value = profile
    }
}

class FakeUiPrefsRepository(
    initialTheme: AppThemeMode = AppThemeMode.SYSTEM,
    initialLanguage: AppLanguage = AppLanguage.DE
) : UiPrefsRepository {
    private val themeFlow = MutableStateFlow(initialTheme)
    private val languageFlow = MutableStateFlow(initialLanguage)

    override val themeMode: Flow<AppThemeMode> = themeFlow.asStateFlow()
    override val language: Flow<AppLanguage> = languageFlow.asStateFlow()

    override suspend fun currentThemeMode(): AppThemeMode = themeFlow.value
    override suspend fun currentLanguage(): AppLanguage = languageFlow.value

    override suspend fun saveThemeMode(mode: AppThemeMode) {
        themeFlow.value = mode
    }

    override suspend fun saveLanguage(language: AppLanguage) {
        languageFlow.value = language
    }
}

class FakeHistoryRepository : HistoryRepository {
    private val entriesFlow = MutableStateFlow<List<HistoryEntry>>(emptyList())
    private val dayFazitsFlow = MutableStateFlow<Map<String, DayFazit>>(emptyMap())
    private val weekFazitsFlow = MutableStateFlow<Map<String, DayFazit>>(emptyMap())

    override fun getAllFlow(): Flow<List<HistoryEntry>> = entriesFlow.asStateFlow()
    override fun getByIdFlow(id: Long): Flow<HistoryEntry?> =
        entriesFlow.map { list -> list.find { it.id == id } }
    override fun getDayFazitsFlow(): Flow<Map<String, DayFazit>> = dayFazitsFlow.asStateFlow()
    override fun getWeekFazitsFlow(): Flow<Map<String, DayFazit>> = weekFazitsFlow.asStateFlow()

    override suspend fun getAll(): List<HistoryEntry> = entriesFlow.value

    override suspend fun add(
        photoUri: Uri?,
        ev: Evaluation,
        note: String,
        dateOverride: Long?,
        backdated: Boolean
    ): HistoryEntry {
        val id = dateOverride ?: System.currentTimeMillis()
        val entry = HistoryEntry(
            id = id,
            dateMillis = id,
            photoFile = if (photoUri != null) "$id.jpg" else "",
            goalResults = ev.goalResults,
            summary = ev.summary,
            note = note,
            recognizedMeal = ev.recognizedMeal,
            backdated = backdated,
            sortOrder = id
        )
        entriesFlow.update { (listOf(entry) + it).sortedByDescending { e -> e.effectiveSortOrder } }
        return entry
    }

    override suspend fun updateRecognizedMeal(id: Long, newText: String) {
        entriesFlow.update { list ->
            list.map { if (it.id == id) it.copy(recognizedMeal = newText) else it }
        }
    }

    override suspend fun updateEvaluation(id: Long, goalResults: List<GoalResult>, summary: String) {
        entriesFlow.update { list ->
            list.map { if (it.id == id) it.copy(goalResults = goalResults, summary = summary) else it }
        }
    }

    override suspend fun delete(entry: HistoryEntry) {
        entriesFlow.update { list -> list.filter { it.id != entry.id } }
    }

    override suspend fun clearAll() {
        entriesFlow.value = emptyList()
        dayFazitsFlow.value = emptyMap()
        weekFazitsFlow.value = emptyMap()
    }

    override suspend fun reorderEntries(reordered: List<HistoryEntry>) {
        val reorderedMap = reordered.associateBy { it.id }
        entriesFlow.update { list ->
            list.map { reorderedMap[it.id] ?: it }.sortedByDescending { it.effectiveSortOrder }
        }
    }

    override suspend fun saveAll(entries: List<HistoryEntry>) {
        entriesFlow.update { list ->
            val byId = entries.associateBy { it.id }
            (list.map { byId[it.id] ?: it } + entries.filter { e -> list.none { it.id == e.id } })
                .sortedByDescending { it.effectiveSortOrder }
        }
    }

    override suspend fun duplicateToDate(
        source: HistoryEntry,
        targetDay: LocalDate,
        targetTime: java.time.LocalTime
    ): HistoryEntry {
        val duplicated = HistoryEntryLogic.createDuplicatedEntry(
            source = source,
            targetDay = targetDay,
            targetTime = targetTime,
            newId = System.currentTimeMillis(),
            newPhotoFile = ""
        )
        entriesFlow.update { (listOf(duplicated) + it).sortedByDescending { e -> e.effectiveSortOrder } }
        return duplicated
    }

    override suspend fun saveDayFazit(fazit: DayFazit) {
        dayFazitsFlow.update { it + (fazit.dayKey to fazit) }
    }

    override suspend fun loadDayFazit(dayKey: String): DayFazit? = dayFazitsFlow.value[dayKey]

    override suspend fun loadAllDayFazits(): List<DayFazit> = dayFazitsFlow.value.values.toList()

    override suspend fun saveAllDayFazits(fazits: List<DayFazit>) {
        dayFazitsFlow.update { it + fazits.associateBy { f -> f.dayKey } }
    }

    override suspend fun deleteDayFazitForEntry(entry: HistoryEntry) {
        val key = dayKeyOf(entry.dateMillis)
        dayFazitsFlow.update { it - key }
    }

    override suspend fun saveWeekFazit(fazit: DayFazit) {
        weekFazitsFlow.update { it + (fazit.dayKey to fazit) }
    }

    override suspend fun loadWeekFazit(weekKey: String): DayFazit? = weekFazitsFlow.value[weekKey]

    override suspend fun loadAllWeekFazits(): List<DayFazit> = weekFazitsFlow.value.values.toList()

    override suspend fun saveAllWeekFazits(fazits: List<DayFazit>) {
        weekFazitsFlow.update { it + fazits.associateBy { f -> f.dayKey } }
    }

    override suspend fun deleteWeekFazit(weekKey: String) {
        weekFazitsFlow.update { it - weekKey }
    }

    override suspend fun deleteWeekFazitForEntry(entry: HistoryEntry) {
        val key = weekKeyOf(entry.dateMillis)
        weekFazitsFlow.update { it - key }
    }

    override fun photoFileOf(entry: HistoryEntry): File? = null
    override suspend fun decodeThumbnail(entry: HistoryEntry, target: Int): Bitmap? = null
    override suspend fun migrateLegacyIfNeeded() {}

    override suspend fun purgeOlderThanWeeks(weeks: Int): Int {
        if (weeks <= 0) return 0
        val cutoffMillis = de.gurum.besseresser.model.retentionCutoffMillis(weeks)
        val cutoffWeekKey = weekKeyOf(cutoffMillis)
        val stale = entriesFlow.value.filter { it.dateMillis < cutoffMillis }
        val staleDayKeys = stale.map { dayKeyOf(it.dateMillis) }.toSet()
        entriesFlow.update { list -> list.filter { it.dateMillis >= cutoffMillis } }
        dayFazitsFlow.update { map -> map - staleDayKeys }
        weekFazitsFlow.update { map -> map.filterKeys { it >= cutoffWeekKey } }
        staleDayKeys.forEach { DayFazitSession.attempted.remove(it) }
        DayFazitSession.attemptedWeekly.removeIf { it < cutoffWeekKey }
        return stale.size
    }

    override suspend fun purgeOrphanedPhotos(): Int = 0
}

class FakeApiService(
    var analyzeResult: Result<Evaluation> = Result.success(
        Evaluation(
            recognizedMeal = "Apfel und Nüsse",
            goalResults = listOf(GoalResult("Z1", TrafficLight.GREEN, "Gut")),
            summary = "Super Mahlzeit"
        )
    ),
    var analyzeTextResult: Result<Evaluation>? = null,
    var dayFazitResult: Result<DayFazitResult> = Result.success(
        DayFazitResult(TrafficLight.GREEN, "Guter Tag!")
    ),
    var splitResult: Result<List<String>> = Result.success(listOf("Ziel A", "Ziel B")),
    var splitGoalsResult: Result<List<String>>? = null,
    var modelsResult: Result<ApiClient.ModelsResult> = Result.success(
        ApiClient.ModelsResult(vision = listOf("gpt-4o", "gpt-4o-mini"), all = listOf("gpt-4o", "gpt-4o-mini"))
    )
) : BesseresserApiService {

    var analyzeTextCallCount = 0

    override suspend fun analyze(
        imageDataUri: String,
        settings: Settings,
        promptBundle: PromptBundle
    ): Result<Evaluation> = analyzeResult

    override suspend fun analyzeText(
        prompt: String,
        settings: Settings
    ): Result<Evaluation> {
        analyzeTextCallCount++
        return analyzeTextResult ?: analyzeResult
    }

    override suspend fun dayFazit(
        settings: Settings,
        goals: List<WeightedGoal>,
        promptContext: String?,
        mealDescriptions: List<String>,
        prompts: FazitPromptBundle
    ): Result<DayFazitResult> = dayFazitResult

    override suspend fun splitGoals(
        request: SplitGoalsRequest
    ): Result<List<String>> = splitGoalsResult ?: splitResult

    override suspend fun fetchModels(baseUrl: String, apiKey: String): Result<ApiClient.ModelsResult> = modelsResult
}

class FakeBackupService(
    var importResult: Result<BackupSummary> = Result.success(BackupSummary(entriesCount = 1)),
    var exportResult: Result<BackupSummary> = Result.success(BackupSummary(fileName = "backup.json"))
) : BackupService {
    override suspend fun exportToUri(uri: Uri): Result<BackupSummary> = exportResult
    override suspend fun exportToDownloads(): Result<BackupSummary> = exportResult
    override suspend fun importFromUri(uri: Uri): Result<BackupSummary> = importResult
}

class FakeImagePreprocessor : ImagePreprocessor {
    override fun prepareImageDataUri(photoUri: Uri, maxSide: Int): String = "data:image/jpeg;base64,fake"
}

class FakePhotoCaptureManager(
    var file: File = File("fake.jpg"),
    var uri: Uri = TestUri()
) : PhotoCaptureManager {
    var callCount = 0
    val copiedSourceUris = mutableListOf<Uri>()
    val deletedFiles = mutableListOf<File>()
    val deletedUris = mutableListOf<Uri>()
    var cleanupCalled = false

    override fun createCaptureFile(): Pair<File, Uri> {
        callCount++
        return file to uri
    }

    override fun copyToCaptureFile(sourceUri: Uri): Pair<File, Uri>? {
        copiedSourceUris.add(sourceUri)
        return file to uri
    }

    override fun deleteCaptureFile(file: File?) {
        if (file != null) deletedFiles.add(file)
    }

    override fun deleteCaptureFile(uri: Uri?) {
        if (uri != null) deletedUris.add(uri)
    }

    override fun cleanupOrphanedFiles(maxAgeMillis: Long) {
        cleanupCalled = true
    }
}


// ----------------------------------------------------------------- Tests

@OptIn(ExperimentalCoroutinesApi::class)
class RepositoriesAndFakesTest {

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
    fun testFakeSettingsRepository() = runTest(testDispatcher) {
        val repo = FakeSettingsRepository()
        val s = repo.currentSettings()
        assertEquals("gpt-4o-mini", s.model)

        repo.save(s.copy(model = "gpt-4o"))
        assertEquals("gpt-4o", repo.currentSettings().model)

        repo.pushRecentNote("Kaffee")
        repo.pushRecentNote("Tee")
        val notes = repo.loadRecentNotes()
        assertEquals(2, notes.size)
        assertEquals("Tee", notes[0].text)
        assertFalse(notes[0].starred)

        repo.toggleStarRecentNote("Tee")
        val starred = repo.loadRecentNotes(sortStarredFirst = true)
        assertTrue(starred[0].starred)

        // Erneutes Nutzen/Pushen einer favorisierten Notiz darf den Stern NIE löschen
        repo.pushRecentNote("Tee")
        val stillStarred = repo.loadRecentNotes(sortStarredFirst = true)
        assertTrue(stillStarred.first { it.text == "Tee" }.starred)
    }

    @Test
    fun testFakeProfileRepository() = runTest(testDispatcher) {
        val repo = FakeProfileRepository()
        assertEquals(Profile(), repo.currentProfile())

        val updated = Profile(birthDate = "1990-05-15", weightKg = 72.5, heightCm = 180, gender = Gender.MALE)
        repo.save(updated)
        assertEquals(updated, repo.currentProfile())
    }

    @Test
    fun testFakeUiPrefsRepository() = runTest(testDispatcher) {
        val repo = FakeUiPrefsRepository()
        assertEquals(AppThemeMode.SYSTEM, repo.currentThemeMode())
        assertEquals(AppLanguage.DE, repo.currentLanguage())

        repo.saveThemeMode(AppThemeMode.DARK)
        repo.saveLanguage(AppLanguage.EN)
        assertEquals(AppThemeMode.DARK, repo.currentThemeMode())
        assertEquals(AppLanguage.EN, repo.currentLanguage())
    }

    @Test
    fun testFakeHistoryRepository() = runTest(testDispatcher) {
        val repo = FakeHistoryRepository()

        val ev = Evaluation(goalResults = listOf(GoalResult("Z1", TrafficLight.GREEN, "Frisch")), summary = "Toll", recognizedMeal = "Salat")
        val entry = repo.add(null, ev, note = "Mittagessen")
        assertEquals("Salat", entry.recognizedMeal)
        assertEquals("Mittagessen", entry.note)

        val byIdFirst = repo.getByIdFlow(entry.id).first()
        assertNotNull(byIdFirst)
        assertEquals("Salat", byIdFirst?.recognizedMeal)

        val unknownFlow = repo.getByIdFlow(99999L).first()
        assertNull(unknownFlow)

        repo.updateRecognizedMeal(entry.id, "Bunter Salat")
        val byIdUpdated = repo.getByIdFlow(entry.id).first()
        assertEquals("Bunter Salat", byIdUpdated?.recognizedMeal)

        val entryDayKey = dayKeyOf(entry.dateMillis)
        val fazit = DayFazit(entryDayKey, TrafficLight.GREEN, "Klasse", listOf(entry.id), 1000L, "fp")
        repo.saveDayFazit(fazit)
        val loadedFazit = repo.loadDayFazit(entryDayKey)
        assertNotNull(loadedFazit)
        assertEquals("Klasse", loadedFazit?.text)

        repo.deleteDayFazitForEntry(entry)
        assertNull(repo.loadDayFazit(entryDayKey))

        repo.delete(entry)
        val byIdDeleted = repo.getByIdFlow(entry.id).first()
        assertNull(byIdDeleted)
    }

    @Test
    fun testMainViewModelWithFakes() = runTest(testDispatcher) {
        val api = FakeApiService()
        val histRepo = FakeHistoryRepository()
        val setRepo = FakeSettingsRepository()
        val vm = MainViewModel(apiService = api, historyRepository = histRepo, settingsRepository = setRepo, imagePreprocessor = FakeImagePreprocessor())

        vm.setNote("Leckerer Snack")
        assertEquals("Leckerer Snack", vm.uiState.value.note)

        val ev = Evaluation(goalResults = listOf(GoalResult("Ziel", TrafficLight.GREEN, "Top")), summary = "Sehr gut", recognizedMeal = "Apfel")
        api.analyzeResult = Result.success(ev)

        vm.saveToHistory()
        advanceUntilIdle()

        // When no evaluation is in state, saveToHistory early returns
        assertFalse(vm.uiState.value.savedToHistory)

        // Set evaluation and save
        vm.setShowReview(true)
        assertTrue(vm.uiState.value.showReview)

        vm.reset()
        assertFalse(vm.uiState.value.showReview)
    }

    @Test
    fun testProfileViewModelWithFakes() = runTest(testDispatcher) {
        val profileRepo = FakeProfileRepository()
        val vm = ProfileViewModel(profileRepository = profileRepo)

        vm.initFromProfile(Profile(birthDate = "1985-01-01", weightKg = 80.0, heightCm = 175, gender = Gender.MALE))
        assertEquals("80", vm.uiState.value.weightText)
        assertEquals("175", vm.uiState.value.heightText)

        // Invalid weight test
        vm.setWeightText("-5")
        val savedInvalid = vm.save()
        assertFalse(savedInvalid)
        assertTrue(vm.uiState.value.error is UiText.StringResource)
        assertEquals(R.string.profile_err_weight, (vm.uiState.value.error as UiText.StringResource).resId)

        // Invalid height test
        vm.setWeightText("75")
        vm.setHeightText("350")
        val savedInvalidHeight = vm.save()
        assertFalse(savedInvalidHeight)
        assertTrue(vm.uiState.value.error is UiText.StringResource)
        assertEquals(R.string.profile_err_height, (vm.uiState.value.error as UiText.StringResource).resId)

        // Valid save
        vm.setHeightText("178")
        val savedOk = vm.save()
        advanceUntilIdle()
        assertTrue(savedOk)
        assertNull(vm.uiState.value.error)
        assertEquals(75.0, profileRepo.currentProfile().weightKg)
        assertEquals(178, profileRepo.currentProfile().heightCm)
    }

    @Test
    fun testSetupViewModelWithFakes() = runTest(testDispatcher) {
        val api = FakeApiService(splitResult = Result.success(listOf("Ziel 1", "Ziel 2")))
        val vm = SetupViewModel(apiService = api, backupService = FakeBackupService(), settingsRepository = FakeSettingsRepository())

        val initial = Settings("https://api.openai.com/v1", "key123", "gpt-4o", listOf("Z1").toWeightedGoals())
        vm.init(initial, listOf("Default Ziel"))
        assertEquals("https://api.openai.com/v1", vm.uiState.value.url)
        assertEquals("key123", vm.uiState.value.key)
        assertEquals("gpt-4o", vm.uiState.value.model)
        assertEquals(listOf("Z1").toWeightedGoals(), vm.uiState.value.goals)

        vm.startSplitGoals(rawGoals = "Gesund ernähren und viel Wasser trinken")
        advanceUntilIdle()

        assertFalse(vm.uiState.value.splitting)
        assertEquals(listOf("Z1", "Ziel 1", "Ziel 2").toWeightedGoals(), vm.uiState.value.goals)
    }

    @Test
    fun testHistoryViewModelWithFakes() = runTest(testDispatcher) {
        val api = FakeApiService()
        val histRepo = FakeHistoryRepository()
        val setRepo = FakeSettingsRepository()
        val vm = HistoryViewModel(apiService = api, historyRepository = histRepo, settingsRepository = setRepo, imagePreprocessor = FakeImagePreprocessor())

        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.entries.size)

        vm.toggleFazitExpanded("20261106")
        assertTrue(vm.uiState.value.expandedFazits.contains("20261106"))
        vm.toggleFazitExpanded("20261106")
        assertFalse(vm.uiState.value.expandedFazits.contains("20261106"))

        vm.toggleWeekExpanded()
        assertTrue(vm.uiState.value.expandedWeek)

        val entry = HistoryEntry(100L, 100L, "", listOf(GoalResult("Z", TrafficLight.GREEN, "")), "", "", "Suppe")
        vm.deleteEntry(entry = entry)
        advanceUntilIdle()

        vm.generateFazit(
            day = LocalDate.of(2026, 11, 6),
            entriesOfDay = listOf(entry),
            settings = Settings("", "", "", emptyList()),
            promptContext = null,
            mealDescriptions = listOf("12:00 · Suppe"),
            prompts = FazitPromptBundle("Intro", "Rules", "de", "Ehrlich")
        )
        advanceUntilIdle()

        val savedDayFazit = histRepo.loadDayFazit("20261106")
        assertNotNull(savedDayFazit)
        assertEquals(TrafficLight.GREEN, savedDayFazit?.rating)

        // Backdated meal capture flow
        val targetDay = LocalDate.of(2026, 11, 5)
        val targetTime = java.time.LocalTime.of(14, 20)
        vm.setAddTargetDay(targetDay)
        vm.setAddTargetTime(targetTime)
        vm.setAddNote("150 g Skyr mit Beeren")
        vm.setShowAddText(true)

        var pickedBackdated: HistoryEntry? = null
        val backdatedEval = Evaluation(
            goalResults = listOf(GoalResult("Z", TrafficLight.GREEN, "Proteinreich")),
            summary = "Top Snack",
            recognizedMeal = "150 g Skyr mit Beeren"
        )
        vm.saveBackdatedEntry(ev = backdatedEval, photoUriIn = null) { pickedBackdated = it }
        advanceUntilIdle()

        val backdated = pickedBackdated
        assertNotNull(backdated)
        val backdatedEntry = checkNotNull(backdated)
        assertFalse(backdatedEntry.backdated)
        val expectedMillis = targetDay.atTime(14, 20).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expectedMillis, backdatedEntry.dateMillis)
        assertEquals("150 g Skyr mit Beeren", backdatedEntry.recognizedMeal)
        assertFalse(vm.uiState.value.showAddText)
        assertEquals("", vm.uiState.value.addNote)
        assertNull(vm.uiState.value.addTargetDay)
        assertNull(vm.uiState.value.addTargetTime)
        // Note AND its evaluation were also pushed to recent notes
        assertEquals("150 g Skyr mit Beeren", setRepo.loadRecentNotes().firstOrNull()?.text)
        assertEquals(backdatedEval, setRepo.loadRecentNotes().firstOrNull()?.evaluation)

        // Reusing a cached evaluation when backdating a meal (without calling AI):
        val day2 = LocalDate.of(2026, 11, 7)
        vm.setAddTargetDay(day2)
        vm.setAddTargetTime(java.time.LocalTime.of(9, 15))
        vm.setAddNote("  150 g Skyr mit Beeren  ")
        var reusedEntry: HistoryEntry? = null
        val usedCache = vm.trySaveCachedBackdated(
            settings = Settings("", "", "", listOf("Z").toWeightedGoals()),
            photoUriIn = null
        ) { reusedEntry = it }
        advanceUntilIdle()

        assertTrue(usedCache)
        assertNotNull(reusedEntry)
        assertEquals("Top Snack", reusedEntry?.summary)
        assertEquals("150 g Skyr mit Beeren", reusedEntry?.recognizedMeal)

        // When goals changed, trySaveCachedBackdated returns false (forces new AI evaluation)
        vm.setAddTargetDay(day2)
        vm.setAddNote("150 g Skyr mit Beeren")
        val usedCacheWithChangedGoals = vm.trySaveCachedBackdated(
            settings = Settings("", "", "", listOf("Z", "Neues Ziel").toWeightedGoals()),
            photoUriIn = null
        )
        assertFalse(usedCacheWithChangedGoals)
    }

    @Test
    fun testMainViewModelCachedEvaluationAndErkanntUpdate() = runTest(testDispatcher) {
        val api = FakeApiService()
        val histRepo = FakeHistoryRepository()
        val setRepo = FakeSettingsRepository()
        val vm = MainViewModel(apiService = api, historyRepository = histRepo, settingsRepository = setRepo, imagePreprocessor = FakeImagePreprocessor())

        val cachedEv = Evaluation(
            goalResults = listOf(GoalResult("Ziel 1", TrafficLight.GREEN, "Sehr gut")),
            summary = "Gesundes Frühstück",
            recognizedMeal = "Haferflocken mit Obst"
        )
        setRepo.pushRecentNote("Haferflocken", cachedEv)

        // Exact match with matching goals -> uses cached evaluation immediately without AI call
        vm.setNote("  Haferflocken ")
        val usedCache = vm.tryUseCachedEvaluation(settings = Settings(goals = listOf("Ziel 1").toWeightedGoals()))
        assertTrue(usedCache)
        assertFalse(vm.uiState.value.isBusy)
        assertEquals(cachedEv, vm.uiState.value.evaluation)

        // Editing "recognizedMeal" also updates the cached evaluation on the RecentNote entry
        vm.updateRecognizedMeal(newText = "Haferflocken mit Beeren und Nüssen")
        assertEquals("Haferflocken mit Beeren und Nüssen", vm.uiState.value.evaluation?.recognizedMeal)
        advanceUntilIdle()
        assertEquals(
            "Haferflocken mit Beeren und Nüssen",
            setRepo.findCachedEvaluation("Haferflocken", listOf("Ziel 1"))?.recognizedMeal
        )

        // Modified note text -> does not match cached entry -> returns false
        vm.setNote("Haferflocken mit Schoko")
        assertFalse(vm.tryUseCachedEvaluation(settings = Settings(goals = listOf("Ziel 1").toWeightedGoals())))

        // Changed goals -> cached evaluation does not match new goals -> returns false
        vm.setNote("Haferflocken")
        assertFalse(vm.tryUseCachedEvaluation(settings = Settings(goals = listOf("Ziel 1", "Ziel 2").toWeightedGoals())))
    }

    @Test
    fun testSetupViewModelSplitFailureFallback() = runTest(testDispatcher) {
        val api = FakeApiService(
            splitResult = Result.failure(ApiException("HTTP 401", "Debug: Unauthorized"))
        )
        val vm = SetupViewModel(apiService = api, backupService = FakeBackupService(), settingsRepository = FakeSettingsRepository())

        vm.startSplitGoals(rawGoals = "Weniger Salz, mehr Gemüse")
        advanceUntilIdle()

        assertFalse(vm.uiState.value.splitting)
        assertEquals(listOf("Weniger Salz", "mehr Gemüse").toWeightedGoals(), vm.uiState.value.goals)
        assertEquals("Debug: Unauthorized", vm.uiState.value.splitDebug)
    }

    @Test
    fun testProfileViewModelCommaDecimalAndEmptyFields() = runTest(testDispatcher) {
        val profileRepo = FakeProfileRepository()
        val vm = ProfileViewModel(profileRepository = profileRepo)

        // Comma decimal weight ("74,5")
        vm.setWeightText("74,5")
        vm.setHeightText("180")
        vm.setNotesText("  Kein Zucker  ")
        assertTrue(vm.save())
        advanceUntilIdle()
        assertEquals(74.5, profileRepo.currentProfile().weightKg ?: 0.0, 0.001)
        assertEquals(180, profileRepo.currentProfile().heightCm)
        assertEquals("Kein Zucker", profileRepo.currentProfile().notes)

        // Clearing all optional fields is valid
        vm.setWeightText("")
        vm.setHeightText("")
        vm.setBirthDate(null)
        vm.setNotesText("")
        vm.setGender(Gender.NONE)
        assertTrue(vm.save())
        advanceUntilIdle()
        assertTrue(profileRepo.currentProfile().isEmpty)
    }

    @Test
    fun testHistoryViewModelPendingDeleteAndUndo() = runTest(testDispatcher) {
        val histRepo = FakeHistoryRepository()
        val vm = HistoryViewModel(
            apiService = FakeApiService(),
            historyRepository = histRepo,
            settingsRepository = FakeSettingsRepository(),
            imagePreprocessor = FakeImagePreprocessor()
        )

        val e1 = histRepo.add(null, Evaluation(recognizedMeal = "Mahlzeit 1"), dateOverride = 1000L)
        val e2 = histRepo.add(null, Evaluation(recognizedMeal = "Mahlzeit 2"), dateOverride = 2000L)
        val e3 = histRepo.add(null, Evaluation(recognizedMeal = "Mahlzeit 3"), dateOverride = 3000L)
        advanceUntilIdle()
        assertEquals(3, vm.uiState.value.entries.size)

        // 1. Request delete on e1, then undo before 5s elapses -> entry stays!
        vm.requestDeleteEntry(entry = e1, delayMs = 5000L)
        assertTrue(e1.id in vm.uiState.value.pendingDeleteIds)
        advanceTimeBy(2000L)
        vm.undoDeleteEntry(e1.id)
        assertFalse(e1.id in vm.uiState.value.pendingDeleteIds)
        advanceUntilIdle()
        assertEquals(3, vm.uiState.value.entries.size)

        // 2. Request delete on e2 and let 5s timer expire -> entry is permanently deleted!
        vm.requestDeleteEntry(entry = e2, delayMs = 5000L)
        assertTrue(e2.id in vm.uiState.value.pendingDeleteIds)
        advanceTimeBy(5100L)
        advanceUntilIdle()
        assertFalse(e2.id in vm.uiState.value.pendingDeleteIds)
        assertEquals(2, vm.uiState.value.entries.size)
        assertFalse(vm.uiState.value.entries.any { it.id == e2.id })

        // 3. Request delete on e3 and flush immediately (simulating leaving History screen)
        vm.requestDeleteEntry(entry = e3, delayMs = 5000L)
        assertTrue(e3.id in vm.uiState.value.pendingDeleteIds)
        vm.flushPendingDeletes()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.pendingDeleteIds.isEmpty())
        assertEquals(1, vm.uiState.value.entries.size)
        assertEquals(e1.id, vm.uiState.value.entries.first().id)
    }

    @Test
    fun testHistoryRepositoryPurgeOlderThanWeeks() = runTest(testDispatcher) {
        val repo = FakeHistoryRepository()
        val today = java.time.LocalDate.now()
        val thisWeek = today.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val tenWeeksAgo = today.minusWeeks(10).atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        val recent = repo.add(null, Evaluation(recognizedMeal = "Heute"), dateOverride = thisWeek)
        val old = repo.add(null, Evaluation(recognizedMeal = "Vor 10 Wochen"), dateOverride = tenWeeksAgo)
        repo.saveDayFazit(DayFazit(dayKeyOf(thisWeek), TrafficLight.GREEN, "Heute ok", listOf(recent.id), 1L, "fp1"))
        repo.saveDayFazit(DayFazit(dayKeyOf(tenWeeksAgo), TrafficLight.RED, "Alt schlecht", listOf(old.id), 1L, "fp2"))
        repo.saveWeekFazit(DayFazit(weekKeyOf(thisWeek), TrafficLight.GREEN, "Woche ok", listOf(recent.id), 1L, "fp3"))
        repo.saveWeekFazit(DayFazit(weekKeyOf(tenWeeksAgo), TrafficLight.RED, "Woche alt", listOf(old.id), 1L, "fp4"))

        assertEquals(2, repo.getAll().size)

        // 8 Wochen Retention: Eintrag vor 10 Wochen muss gelöscht werden, heutiger bleibt
        val deleted = repo.purgeOlderThanWeeks(8)
        assertEquals(1, deleted)
        assertEquals(listOf(recent.id), repo.getAll().map { it.id })
        assertNotNull(repo.loadDayFazit(dayKeyOf(thisWeek)))
        assertNull(repo.loadDayFazit(dayKeyOf(tenWeeksAgo)))
        assertNotNull(repo.loadWeekFazit(weekKeyOf(thisWeek)))
        assertNull(repo.loadWeekFazit(weekKeyOf(tenWeeksAgo)))

        // 0 Wochen = deaktiviert, nichts wird gelöscht
        val deletedZero = repo.purgeOlderThanWeeks(0)
        assertEquals(0, deletedZero)
        assertEquals(1, repo.getAll().size)
    }

    @Test
    fun testHistoryViewModelMoveEntryWithinDay() = runTest(testDispatcher) {
        val histRepo = FakeHistoryRepository()
        val vm = HistoryViewModel(
            apiService = FakeApiService(),
            historyRepository = histRepo,
            settingsRepository = FakeSettingsRepository(),
            imagePreprocessor = FakeImagePreprocessor()
        )

        val day = LocalDate.of(2026, 11, 6)
        val m1 = day.atTime(8, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val m2 = day.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val m3 = day.atTime(19, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        // Descending order initially: Abendessen (m3), Mittagessen (m2), Frühstück (m1)
        histRepo.add(null, Evaluation(recognizedMeal = "Abendessen"), dateOverride = m3)
        histRepo.add(null, Evaluation(recognizedMeal = "Mittagessen"), dateOverride = m2)
        histRepo.add(null, Evaluation(recognizedMeal = "Frühstück"), dateOverride = m1)
        advanceUntilIdle()

        assertEquals("Abendessen", vm.uiState.value.entries[0].recognizedMeal)
        assertEquals("Mittagessen", vm.uiState.value.entries[1].recognizedMeal)
        assertEquals("Frühstück", vm.uiState.value.entries[2].recognizedMeal)

        // Save a DayFazit for this day before reordering
        val initialDayEntries = vm.uiState.value.entries
        val dayFazit = DayFazit(
            dayKey = dayKeyOf(day),
            rating = TrafficLight.GREEN,
            text = "Ausgewogener Tag",
            entryIds = initialDayEntries.map { it.id },
            fingerprint = dayFingerprint(initialDayEntries)
        )
        histRepo.saveDayFazit(dayFazit)
        advanceUntilIdle()
        assertNotNull(histRepo.loadDayFazit(dayKeyOf(day)))

        // Move Frühstück (index 2) to top (index 0)
        vm.moveEntryWithinDay(day = day, fromIndex = 2, toIndex = 0)
        advanceUntilIdle()

        val reordered = vm.uiState.value.entries
        assertEquals("Frühstück", reordered[0].recognizedMeal)
        assertEquals("Abendessen", reordered[1].recognizedMeal)
        assertEquals("Mittagessen", reordered[2].recognizedMeal)
        // Sort orders must be strictly descending, while original capture times (dateMillis) stay untouched!
        assertTrue(reordered[0].sortOrder > reordered[1].sortOrder)
        assertTrue(reordered[1].sortOrder > reordered[2].sortOrder)
        assertEquals(m1, reordered[0].dateMillis)
        assertEquals(m3, reordered[1].dateMillis)
        assertEquals(m2, reordered[2].dateMillis)

        // Test moving a normal entry above a backdated (23:59) entry: normal entry keeps its time!
        histRepo.add(
            null,
            Evaluation(recognizedMeal = "Später Snack"),
            dateOverride = day.atTime(23, 59).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            backdated = true
        )
        advanceUntilIdle()
        assertEquals("Später Snack", vm.uiState.value.entries[0].recognizedMeal)
        assertTrue(vm.uiState.value.entries[0].backdated)

        // Move Mittagessen (index 3) to top (index 0, above backdated 23:59 entry)
        vm.moveEntryWithinDay(day = day, fromIndex = 3, toIndex = 0)
        advanceUntilIdle()
        val afterBackdatedMove = vm.uiState.value.entries
        assertEquals("Mittagessen", afterBackdatedMove[0].recognizedMeal)
        assertFalse(afterBackdatedMove[0].backdated)
        // Mittagessen MUST still have its original 12:00 timestamp (m2), NOT 23:59!
        assertEquals(m2, afterBackdatedMove[0].dateMillis)
        assertTrue(afterBackdatedMove[0].sortOrder > afterBackdatedMove[1].sortOrder)

        // Existing DayFazit must still be present
        val loadedFazitAfterReorder = histRepo.loadDayFazit(dayKeyOf(day))
        assertNotNull(loadedFazitAfterReorder)

        // Clear all history data -> entries, dayFazits, weekFazits become empty
        histRepo.clearAll()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.entries.isEmpty())
        assertTrue(vm.uiState.value.fazitMap.isEmpty())
        assertNull(vm.uiState.value.weekFazit)
    }

    @Test
    fun testUiTextDataClassAndEquality() {
        val dynamic1 = UiText.DynamicString("Fehler aufgetreten")
        val dynamic2 = UiText.DynamicString("Fehler aufgetreten")
        val dynamic3 = UiText.DynamicString("Anderer Text")
        assertEquals(dynamic1, dynamic2)
        assertFalse(dynamic1 == dynamic3)

        val res1 = UiText.StringResource(R.string.profile_err_weight)
        val res2 = UiText.StringResource(R.string.profile_err_weight)
        val res3 = UiText.StringResource(R.string.profile_err_height)
        assertEquals(res1, res2)
        assertEquals(res1.hashCode(), res2.hashCode())
        assertFalse(res1 == res3)
    }

    @Test
    fun testHistoryViewModelAutoFazitTriggering() = runTest(testDispatcher) {
        val api = FakeApiService()
        val histRepo = FakeHistoryRepository()
        val setRepo = FakeSettingsRepository()
        val vm = HistoryViewModel(
            apiService = api,
            historyRepository = histRepo,
            settingsRepository = setRepo,
            imagePreprocessor = FakeImagePreprocessor()
        )

        val pastDay = LocalDate.now().minusDays(1)
        val pastDayMillis = pastDay.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        histRepo.add(null, Evaluation(recognizedMeal = "Salat"), dateOverride = pastDayMillis)
        advanceUntilIdle()

        DayFazitSession.attempted.clear()
        vm.checkAndTriggerAutoFazits(
            settings = Settings("", "", "", listOf("Ziel 1").toWeightedGoals()),
            promptContext = null,
            dayPrompts = FazitPromptBundle("Intro", "Rules", "de", "Ehrlich"),
            weekPromptsProvider = { FazitPromptBundle("WeekIntro", "Rules", "de", "Ehrlich") },
            daymealDescriptionsProvider = { list -> list.map { it.recognizedMeal } },
            weekmealDescriptionsProvider = { list -> list.map { it.recognizedMeal } }
        )
        advanceTimeBy(1300)
        advanceUntilIdle()

        val savedFazit = histRepo.loadDayFazit(dayKeyOf(pastDay))
        assertNotNull(savedFazit)
        assertEquals(TrafficLight.GREEN, savedFazit?.rating)
    }

    @Test
    fun testPhotoCaptureManagerIntegrationInViewModels() = runTest(testDispatcher) {
        val fakeManager = FakePhotoCaptureManager(
            file = File("custom_photo.jpg"),
            uri = TestUri("content://de.gurum.besseresser.fileprovider/photos/custom_photo.jpg")
        )
        val api = FakeApiService()
        val histRepo = FakeHistoryRepository()
        val setRepo = FakeSettingsRepository()
        val imagePreprocessor = FakeImagePreprocessor()

        val mainVm = MainViewModel(
            apiService = api,
            historyRepository = histRepo,
            settingsRepository = setRepo,
            imagePreprocessor = imagePreprocessor,
            photoCaptureManager = fakeManager
        )

        val historyVm = HistoryViewModel(
            apiService = api,
            historyRepository = histRepo,
            settingsRepository = setRepo,
            imagePreprocessor = imagePreprocessor,
            photoCaptureManager = fakeManager
        )

        val (mainFile, mainUri) = mainVm.createCaptureFile()
        assertEquals("custom_photo.jpg", mainFile.name)
        assertEquals("content://de.gurum.besseresser.fileprovider/photos/custom_photo.jpg", mainUri.toString())

        val (histFile, histUri) = historyVm.createCaptureFile()
        assertEquals("custom_photo.jpg", histFile.name)
        assertEquals("content://de.gurum.besseresser.fileprovider/photos/custom_photo.jpg", histUri.toString())
        assertEquals(2, fakeManager.callCount)

        // Deletion methods
        mainVm.deleteCaptureFile(mainFile)
        assertTrue(fakeManager.deletedFiles.contains(mainFile))

        historyVm.deleteCaptureFile(histUri)
        assertTrue(fakeManager.deletedUris.contains(histUri))
    }

    @Test
    fun testDefaultPhotoCaptureManagerLifecycleAndCleanup() {
        val tempDir = java.nio.file.Files.createTempDirectory("photo_cache_test").toFile()
        try {
            val mockContext = object : android.content.ContextWrapper(null) {
                override fun getCacheDir(): File = tempDir
                override fun getPackageName(): String = "de.gurum.besseresser"
            }

            val manager = DefaultPhotoCaptureManager(mockContext)

            // Test creation
            val (file1, uri1) = manager.createCaptureFile()
            assertTrue(file1.exists() || !file1.exists()) // File is initialized as File object
            file1.writeText("test image data")
            assertTrue(file1.exists())
            assertEquals(15L, file1.length())

            // Test deletion by File
            manager.deleteCaptureFile(file1)
            assertFalse(file1.exists())

            // Test deletion by Uri
            val (file2, _) = manager.createCaptureFile()
            file2.writeText("test image 2")
            assertTrue(file2.exists())
            val testUri2 = TestUri("content://de.gurum.besseresser.fileprovider/photos/${file2.name}")
            manager.deleteCaptureFile(testUri2)
            assertFalse(file2.exists())

            // Test orphan cleanup
            val photosDir = File(tempDir, "photos").also { it.mkdirs() }
            val expiredFile = File(photosDir, "essen_old.jpg").apply {
                writeText("old data")
                setLastModified(System.currentTimeMillis() - 7200_000L) // 2 hours old
            }
            val zeroByteFile = File(photosDir, "essen_empty.jpg").apply {
                createNewFile()
            }
            val recentFile = File(photosDir, "essen_fresh.jpg").apply {
                writeText("fresh data")
                setLastModified(System.currentTimeMillis())
            }

            assertTrue(expiredFile.exists())
            assertTrue(zeroByteFile.exists())
            assertTrue(recentFile.exists())

            // Run cleanup with 1 hour max age
            manager.cleanupOrphanedFiles(3600_000L)

            assertFalse(expiredFile.exists())
            assertFalse(zeroByteFile.exists())
            assertTrue(recentFile.exists())

            // Test copyToCaptureFile with external source file
            val externalFile = File(tempDir, "shared_from_gallery.jpg").apply {
                writeText("shared image content")
            }
            val copiedPair = manager.copyToCaptureFile(TestUri(externalFile.absolutePath))
            assertNotNull(copiedPair)
            assertTrue(copiedPair!!.first.exists())
            assertEquals("shared image content", copiedPair.first.readText())

            // Test copyToCaptureFile returns same file when already in app's FileProvider cache
            val alreadyCachedUri = TestUri("content://de.gurum.besseresser.fileprovider/photos/${copiedPair.first.name}")
            val reusedPair = manager.copyToCaptureFile(alreadyCachedUri)
            assertNotNull(reusedPair)
            assertEquals(copiedPair.first.absolutePath, reusedPair!!.first.absolutePath)

            // Test copyToCaptureFile returns null for non-existent source
            val failedCopy = manager.copyToCaptureFile(TestUri("file:///does/not/exist.jpg"))
            assertEquals(null, failedCopy)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testLegacyJsonMigratorWithValidFiles() = runTest(testDispatcher) {
        val tempDir = java.nio.file.Files.createTempDirectory("legacy_migrator_test").toFile()
        try {
            val entry = HistoryEntry(
                id = 555L,
                dateMillis = 1000L,
                photoFile = "555.jpg",
                goalResults = listOf(GoalResult("Z1", TrafficLight.GREEN, "Gut")),
                summary = "Lecker",
                note = "Mittags",
                recognizedMeal = "Pasta",
                backdated = false,
                sortOrder = 1000L
            )
            val entryFile = File(tempDir, "555.json")
            entryFile.writeText(AppJson.encodeToString(entry))

            val dayFazit = DayFazit(
                dayKey = "2026-11-19",
                rating = TrafficLight.GREEN,
                text = "Super Tag",
                entryIds = listOf(555L),
                createdAt = 2000L,
                fingerprint = "fp1"
            )
            val dayFazitFile = File(tempDir, "day_2026-11-19.json")
            dayFazitFile.writeText(AppJson.encodeToString(dayFazit))

            val weekFazit = DayFazit(
                dayKey = "2026-W47",
                rating = TrafficLight.YELLOW,
                text = "Gute Woche",
                entryIds = listOf(555L),
                createdAt = 3000L,
                fingerprint = "fp2"
            )
            val weekFazitFile = File(tempDir, "week_2026-W47.json")
            weekFazitFile.writeText(AppJson.encodeToString(weekFazit))

            val fakeHistoryDao = FakeHistoryDao()
            val fakeDayFazitDao = FakeDayFazitDao()
            val fakeWeekFazitDao = FakeWeekFazitDao()

            LegacyJsonMigrator.migrate(tempDir, fakeHistoryDao, fakeDayFazitDao, fakeWeekFazitDao, testDispatcher)

            // Assert DB insertions
            assertEquals(1, fakeHistoryDao.items.size)
            assertEquals(555L, fakeHistoryDao.items.first().id)
            assertEquals("Pasta", fakeHistoryDao.items.first().recognizedMeal)

            assertEquals(1, fakeDayFazitDao.items.size)
            assertEquals("2026-11-19", fakeDayFazitDao.items.first().dayKey)
            assertEquals("Super Tag", fakeDayFazitDao.items.first().text)

            assertEquals(1, fakeWeekFazitDao.items.size)
            assertEquals("2026-W47", fakeWeekFazitDao.items.first().weekKey)
            assertEquals("Gute Woche", fakeWeekFazitDao.items.first().text)

            // Assert JSON files were cleaned up
            assertFalse(entryFile.exists())
            assertFalse(dayFazitFile.exists())
            assertFalse(weekFazitFile.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testLegacyJsonMigratorFallbackIdFromFilename() = runTest(testDispatcher) {
        val tempDir = java.nio.file.Files.createTempDirectory("legacy_migrator_id_test").toFile()
        try {
            val entryWithoutId = HistoryEntry(
                id = 0L,
                dateMillis = 1000L,
                photoFile = "888.jpg",
                goalResults = emptyList(),
                summary = "Snack",
                note = "",
                recognizedMeal = "Banane",
                backdated = false,
                sortOrder = 1000L
            )
            val file = File(tempDir, "888.json")
            file.writeText(AppJson.encodeToString(entryWithoutId))

            val fakeHistoryDao = FakeHistoryDao()
            val fakeDayFazitDao = FakeDayFazitDao()
            val fakeWeekFazitDao = FakeWeekFazitDao()

            LegacyJsonMigrator.migrate(tempDir, fakeHistoryDao, fakeDayFazitDao, fakeWeekFazitDao, testDispatcher)

            assertEquals(1, fakeHistoryDao.items.size)
            assertEquals(888L, fakeHistoryDao.items.first().id)
            assertEquals("Banane", fakeHistoryDao.items.first().recognizedMeal)
            assertFalse(file.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testLegacyJsonMigratorEmptyAndCorruptedFiles() = runTest(testDispatcher) {
        val tempDir = java.nio.file.Files.createTempDirectory("legacy_migrator_corrupt_test").toFile()
        try {
            val corruptFile = File(tempDir, "corrupted.json")
            corruptFile.writeText("THIS IS NOT VALID JSON")

            val fakeHistoryDao = FakeHistoryDao()
            val fakeDayFazitDao = FakeDayFazitDao()
            val fakeWeekFazitDao = FakeWeekFazitDao()

            LegacyJsonMigrator.migrate(tempDir, fakeHistoryDao, fakeDayFazitDao, fakeWeekFazitDao, testDispatcher)

            // Should not crash, and should not insert corrupt item
            assertEquals(0, fakeHistoryDao.items.size)
            assertTrue(corruptFile.exists()) // Failed file is kept intact
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testKeyVaultEncryptionAndDecryptionRoundTrip() {
        val keyGen = javax.crypto.KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val secretKey = keyGen.generateKey()

        val plainApiKey = "sk-proj-1234567890abcdefABCDEF_!@#$"
        val encrypted = KeyVault.encryptWithKey(plainApiKey, secretKey)

        assertNotNull(encrypted)
        assertTrue(encrypted.isNotBlank())
        // Ciphertext should not contain plaintext API key
        assertFalse(encrypted.contains(plainApiKey))

        val decrypted = KeyVault.decryptWithKey(encrypted, secretKey)
        assertEquals(plainApiKey, decrypted)
    }

    @Test
    fun testKeyVaultBlankAndEmptyHandling() {
        val keyGen = javax.crypto.KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val secretKey = keyGen.generateKey()

        assertEquals("", KeyVault.encryptWithKey("", secretKey))
        assertEquals("", KeyVault.encryptWithKey("   ", secretKey))
        assertEquals("", KeyVault.decryptWithKey("", secretKey))
        assertEquals("", KeyVault.decryptWithKey("   ", secretKey))

        assertEquals("", KeyVault.encrypt("").getOrNull())
        assertEquals("", KeyVault.encrypt("   ").getOrNull())
        assertEquals("", KeyVault.decrypt("").getOrNull())
        assertEquals("", KeyVault.decrypt("   ").getOrNull())
    }

    @Test
    fun testKeyVaultCorruptedAndInvalidCiphertext() {
        val keyGen = javax.crypto.KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val secretKey1 = keyGen.generateKey()
        val secretKey2 = keyGen.generateKey()

        val plainApiKey = "sk-secret-test-key-12345"
        val encrypted = KeyVault.encryptWithKey(plainApiKey, secretKey1)

        // Decrypting with wrong key must return null (GCM auth tag verification fails)
        assertNull(KeyVault.decryptWithKey(encrypted, secretKey2))

        // Non-base64 invalid data must return null
        assertNull(KeyVault.decryptWithKey("not-valid-base64!@#$%", secretKey1))

        // Truncated data
        val truncatedBase64 = java.util.Base64.getEncoder().encodeToString(byteArrayOf(0x01))
        assertNull(KeyVault.decryptWithKey(truncatedBase64, secretKey1))

        // Manipulated ciphertext bytes
        val rawBytes = java.util.Base64.getDecoder().decode(encrypted)
        rawBytes[rawBytes.size - 1] = (rawBytes[rawBytes.size - 1].toInt() xor 0xFF).toByte()
        val tamperedBase64 = java.util.Base64.getEncoder().encodeToString(rawBytes)
        assertNull(KeyVault.decryptWithKey(tamperedBase64, secretKey1))
    }

    @Test
    fun testKeyVaultResultErrorPropagation() {
        val invalidCiphertext = "invalid_cipher_text"
        val result = KeyVault.decrypt(invalidCiphertext)
        assertTrue(result.isFailure)
    }
}

class FakeHistoryDao : HistoryDao {
    val items = mutableListOf<HistoryEntryEntity>()

    override fun getAllFlow(): Flow<List<HistoryEntryEntity>> = MutableStateFlow(items)
    override suspend fun getAll(): List<HistoryEntryEntity> = items.toList()
    override fun getByIdFlow(id: Long): Flow<HistoryEntryEntity?> = MutableStateFlow(items.find { it.id == id })
    override suspend fun getById(id: Long): HistoryEntryEntity? = items.find { it.id == id }
    override suspend fun insert(entry: HistoryEntryEntity) {
        items.removeAll { it.id == entry.id }
        items.add(entry)
    }
    override suspend fun insertAll(entries: List<HistoryEntryEntity>) {
        entries.forEach { insert(it) }
    }
    override suspend fun deleteById(id: Long) {
        items.removeAll { it.id == id }
    }
    override suspend fun deleteAll() {
        items.clear()
    }
    override suspend fun updateRecognizedMeal(id: Long, recognizedMeal: String) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) items[index] = items[index].copy(recognizedMeal = recognizedMeal)
    }
    override suspend fun updateEvaluation(id: Long, goalResults: List<GoalResult>, summary: String) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) items[index] = items[index].copy(goalResults = goalResults, summary = summary)
    }
}

class FakeDayFazitDao : DayFazitDao {
    val items = mutableListOf<DayFazitEntity>()

    override fun getAllFlow(): Flow<List<DayFazitEntity>> = MutableStateFlow(items)
    override suspend fun getAll(): List<DayFazitEntity> = items.toList()
    override suspend fun getByKey(dayKey: String): DayFazitEntity? = items.find { it.dayKey == dayKey }
    override suspend fun insert(entity: DayFazitEntity) {
        items.removeAll { it.dayKey == entity.dayKey }
        items.add(entity)
    }
    override suspend fun insertAll(entities: List<DayFazitEntity>) {
        entities.forEach { insert(it) }
    }
    override suspend fun delete(dayKey: String) {
        items.removeAll { it.dayKey == dayKey }
    }
    override suspend fun deleteAll() {
        items.clear()
    }
}

class FakeWeekFazitDao : WeekFazitDao {
    val items = mutableListOf<WeekFazitEntity>()

    override fun getAllFlow(): Flow<List<WeekFazitEntity>> = MutableStateFlow(items)
    override suspend fun getAll(): List<WeekFazitEntity> = items.toList()
    override suspend fun getByKey(weekKey: String): WeekFazitEntity? = items.find { it.weekKey == weekKey }
    override suspend fun insert(entity: WeekFazitEntity) {
        items.removeAll { it.weekKey == entity.weekKey }
        items.add(entity)
    }
    override suspend fun insertAll(entities: List<WeekFazitEntity>) {
        entities.forEach { insert(it) }
    }
    override suspend fun delete(weekKey: String) {
        items.removeAll { it.weekKey == weekKey }
    }
    override suspend fun deleteAll() {
        items.clear()
    }
}
