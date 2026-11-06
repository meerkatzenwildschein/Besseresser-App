package de.gurum.besseresser

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
import java.io.File
import java.time.LocalDate

// ----------------------------------------------------------------- In-Memory Test Fakes

class FakeSettingsRepository(
    private var settings: Settings = Settings("https://api.openai.com/v1", "test-key", "gpt-4o-mini", listOf("Ziel 1")),
    private val recentNotesList: MutableList<RecentNote> = mutableListOf()
) : SettingsRepository {
    override fun load(): Settings = settings
    override fun save(settings: Settings) {
        this.settings = settings
    }

    override fun loadRecentNotes(sortStarredFirst: Boolean): List<RecentNote> {
        return if (sortStarredFirst) {
            val starred = recentNotesList.filter { it.starred }
            val unstarred = recentNotesList.filter { !it.starred }.take(5)
            starred + unstarred
        } else {
            recentNotesList
        }
    }

    override fun pushRecentNote(note: String) {
        val t = note.trim()
        if (t.isBlank()) return
        recentNotesList.removeAll { it.text == t }
        recentNotesList.add(0, RecentNote(t, false))
    }

    override fun toggleStarRecentNote(noteText: String) {
        val idx = recentNotesList.indexOfFirst { it.text == noteText }
        if (idx != -1) {
            val cur = recentNotesList[idx]
            recentNotesList[idx] = cur.copy(starred = !cur.starred)
        }
    }

    override fun saveRecentNotes(notes: List<RecentNote>) {
        recentNotesList.clear()
        recentNotesList.addAll(notes)
    }
}

class FakeProfileRepository(
    private var profile: Profile = Profile()
) : ProfileRepository {
    override fun load(): Profile = profile
    override fun save(profile: Profile) {
        this.profile = profile
    }
}

class FakeHistoryRepository : HistoryRepository {
    private val entriesFlow = MutableStateFlow<List<HistoryEntry>>(emptyList())
    private val dayFazitsFlow = MutableStateFlow<Map<String, DayFazit>>(emptyMap())
    private val weekFazitsFlow = MutableStateFlow<Map<String, DayFazit>>(emptyMap())

    var migrationChecked = false

    override fun ensureMigrated() {
        migrationChecked = true
    }

    override fun getAllFlow(): Flow<List<HistoryEntry>> = entriesFlow.asStateFlow()
    override fun getDayFazitsFlow(): Flow<Map<String, DayFazit>> = dayFazitsFlow.asStateFlow()
    override fun getWeekFazitsFlow(): Flow<Map<String, DayFazit>> = weekFazitsFlow.asStateFlow()

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
            erkannt = ev.erkannt,
            backdated = backdated
        )
        entriesFlow.update { listOf(entry) + it }
        return entry
    }

    override suspend fun updateErkannt(id: Long, newText: String) {
        entriesFlow.update { list ->
            list.map { if (it.id == id) it.copy(erkannt = newText) else it }
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

    override suspend fun saveDayFazit(fazit: DayFazit) {
        dayFazitsFlow.update { it + (fazit.dayKey to fazit) }
    }

    override suspend fun loadDayFazit(dayKey: String): DayFazit? = dayFazitsFlow.value[dayKey]

    override suspend fun deleteDayFazitForEntry(entry: HistoryEntry) {
        val key = dayKeyOf(entry.dateMillis)
        dayFazitsFlow.update { it - key }
    }

    override suspend fun saveWeekFazit(fazit: DayFazit) {
        weekFazitsFlow.update { it + (fazit.dayKey to fazit) }
    }

    override suspend fun loadWeekFazit(weekKey: String): DayFazit? = weekFazitsFlow.value[weekKey]

    override suspend fun deleteWeekFazit(weekKey: String) {
        weekFazitsFlow.update { it - weekKey }
    }

    override suspend fun deleteWeekFazitForEntry(entry: HistoryEntry) {
        val key = weekKeyOf(entry.dateMillis)
        weekFazitsFlow.update { it - key }
    }

    override fun photoFileOf(entry: HistoryEntry): File? = null
    override fun decodeThumbnail(entry: HistoryEntry, target: Int): Bitmap? = null
}

class FakeApiService(
    var analyzeResult: Result<Evaluation> = Result.success(
        Evaluation(
            erkannt = "Apfel und Nüsse",
            goalResults = listOf(GoalResult("Z1", TrafficLight.GREEN, "Gut")),
            summary = "Super Mahlzeit"
        )
    ),
    var dayFazitResult: Result<DayFazitResult> = Result.success(
        DayFazitResult(TrafficLight.GREEN, "Guter Tag!", "Super Tag")
    ),
    var splitResult: Result<List<String>> = Result.success(listOf("Ziel A", "Ziel B")),
    var modelsResult: Result<List<String>> = Result.success(listOf("gpt-4o", "gpt-4o-mini"))
) : BesseresserApiService {

    override suspend fun analyze(
        ctx: Context?,
        photoUri: Uri,
        settings: Settings,
        promptBundle: PromptBundle
    ): Result<Evaluation> = analyzeResult

    override suspend fun analyzeText(
        ctx: Context?,
        settings: Settings,
        userDescription: String
    ): Result<Evaluation> = analyzeResult

    override suspend fun dayFazit(
        settings: Settings,
        goals: List<String>,
        profile: Profile,
        mealLines: List<String>,
        prompts: FazitPromptBundle
    ): Result<DayFazitResult> = dayFazitResult

    override suspend fun splitGoals(
        baseUrl: String,
        apiKey: String,
        model: String,
        request: SplitGoalsRequest
    ): Result<List<String>> = splitResult

    override suspend fun fetchModels(baseUrl: String, apiKey: String): Result<List<String>> = modelsResult
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
    fun testFakeSettingsRepository() {
        val repo = FakeSettingsRepository()
        val s = repo.load()
        assertEquals("gpt-4o-mini", s.model)

        repo.save(s.copy(model = "gpt-4o"))
        assertEquals("gpt-4o", repo.load().model)

        repo.pushRecentNote("Kaffee")
        repo.pushRecentNote("Tee")
        val notes = repo.loadRecentNotes()
        assertEquals(2, notes.size)
        assertEquals("Tee", notes[0].text)
        assertFalse(notes[0].starred)

        repo.toggleStarRecentNote("Tee")
        val starred = repo.loadRecentNotes(sortStarredFirst = true)
        assertTrue(starred[0].starred)
    }

    @Test
    fun testFakeProfileRepository() {
        val repo = FakeProfileRepository()
        assertEquals(Profile(), repo.load())

        val updated = Profile(birthDate = "1990-05-15", weightKg = 72.5, heightCm = 180, gender = Gender.MALE)
        repo.save(updated)
        assertEquals(updated, repo.load())
    }

    @Test
    fun testFakeHistoryRepository() = runTest(testDispatcher) {
        val repo = FakeHistoryRepository()
        repo.ensureMigrated()
        assertTrue(repo.migrationChecked)

        val ev = Evaluation("Salat", listOf(GoalResult("Z1", TrafficLight.GREEN, "Frisch")), "Toll")
        val entry = repo.add(null, ev, note = "Mittagessen")
        assertEquals("Salat", entry.erkannt)
        assertEquals("Mittagessen", entry.note)

        repo.updateErkannt(entry.id, "Bunter Salat")
        val fazit = DayFazit("20261106", TrafficLight.GREEN, "Klasse", listOf(entry.id), 1000L, "fp")
        repo.saveDayFazit(fazit)
        val loadedFazit = repo.loadDayFazit("20261106")
        assertNotNull(loadedFazit)
        assertEquals("Klasse", loadedFazit?.text)

        repo.deleteDayFazitForEntry(entry)
        assertNull(repo.loadDayFazit("20261106"))

        repo.delete(entry)
    }

    @Test
    fun testMainViewModelWithFakes() = runTest(testDispatcher) {
        val api = FakeApiService()
        val histRepo = FakeHistoryRepository()
        val setRepo = FakeSettingsRepository()
        val vm = MainViewModel(apiService = api, historyRepository = histRepo, settingsRepository = setRepo)

        vm.loadRecentNotes()
        assertEquals(0, vm.uiState.value.recentNotes.size)

        setRepo.pushRecentNote("Snack")
        vm.loadRecentNotes()
        assertEquals(1, vm.uiState.value.recentNotes.size)

        vm.setNote("Leckerer Snack")
        assertEquals("Leckerer Snack", vm.uiState.value.note)

        val ev = Evaluation("Apfel", listOf(GoalResult("Ziel", TrafficLight.GREEN, "Top")), "Sehr gut")
        api.analyzeResult = Result.success(ev)

        vm.saveToHistory(settings = Settings("", "", "", emptyList()))
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
    fun testProfileViewModelWithFakes() {
        val profileRepo = FakeProfileRepository()
        val vm = ProfileViewModel(profileRepository = profileRepo)

        vm.initFromProfile(Profile(birthDate = "1985-01-01", weightKg = 80.0, heightCm = 175, gender = Gender.MALE))
        assertEquals("80", vm.uiState.value.weightText)
        assertEquals("175", vm.uiState.value.heightText)

        // Invalid weight test
        vm.setWeightText("-5")
        val savedInvalid = vm.save(weightErrMsg = "Fehler Gewicht", heightErrMsg = "Fehler Größe")
        assertFalse(savedInvalid)
        assertEquals("Fehler Gewicht", vm.uiState.value.error)

        // Invalid height test
        vm.setWeightText("75")
        vm.setHeightText("350")
        val savedInvalidHeight = vm.save(weightErrMsg = "Fehler Gewicht", heightErrMsg = "Fehler Größe")
        assertFalse(savedInvalidHeight)
        assertEquals("Fehler Größe", vm.uiState.value.error)

        // Valid save
        vm.setHeightText("178")
        val savedOk = vm.save()
        assertTrue(savedOk)
        assertNull(vm.uiState.value.error)
        assertEquals(75.0, profileRepo.load().weightKg)
        assertEquals(178, profileRepo.load().heightCm)
    }

    @Test
    fun testSetupViewModelWithFakes() = runTest(testDispatcher) {
        val api = FakeApiService(splitResult = Result.success(listOf("Ziel 1", "Ziel 2")))
        val setRepo = FakeSettingsRepository()
        val vm = SetupViewModel(apiService = api, settingsRepository = setRepo)

        val initial = Settings("https://api.openai.com/v1", "key123", "gpt-4o", listOf("Z1"))
        vm.init(initial, listOf("Default Ziel"))
        assertEquals("https://api.openai.com/v1", vm.uiState.value.url)
        assertEquals("key123", vm.uiState.value.key)
        assertEquals("gpt-4o", vm.uiState.value.model)

        vm.startSplitGoals(rawGoals = "Gesund ernähren und viel Wasser trinken")
        advanceUntilIdle()

        assertFalse(vm.uiState.value.splitting)
        assertTrue(vm.uiState.value.showSplit)
        assertEquals("Ziel 1\nZiel 2", vm.uiState.value.splitEdited)
    }

    @Test
    fun testHistoryViewModelWithFakes() = runTest(testDispatcher) {
        val api = FakeApiService()
        val histRepo = FakeHistoryRepository()
        val setRepo = FakeSettingsRepository()
        val vm = HistoryViewModel(apiService = api, historyRepository = histRepo, settingsRepository = setRepo)

        vm.startObserving()
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
            profile = Profile(),
            mealLines = listOf("12:00 · Suppe"),
            prompts = FazitPromptBundle("Intro", "Rules", "de", "Ehrlich")
        )
        advanceUntilIdle()

        val savedDayFazit = histRepo.loadDayFazit("20261106")
        assertNotNull(savedDayFazit)
        assertEquals(TrafficLight.GREEN, savedDayFazit?.ampel)
    }
}
