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
        val wasStarred = recentNotesList.firstOrNull { it.text == t }?.starred == true
        recentNotesList.removeAll { it.text == t }
        recentNotesList.add(0, RecentNote(t, wasStarred))
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
        entriesFlow.update { (listOf(entry) + it).sortedByDescending { e -> e.dateMillis } }
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

    override suspend fun reorderEntries(reordered: List<HistoryEntry>) {
        val reorderedMap = reordered.associateBy { it.id }
        entriesFlow.update { list ->
            list.map { reorderedMap[it.id] ?: it }.sortedByDescending { it.dateMillis }
        }
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
        DayFazitResult(TrafficLight.GREEN, "Guter Tag!")
    ),
    var splitResult: Result<List<String>> = Result.success(listOf("Ziel A", "Ziel B")),
    var modelsResult: Result<ApiClient.ModelsResult> = Result.success(
        ApiClient.ModelsResult(vision = listOf("gpt-4o", "gpt-4o-mini"), all = listOf("gpt-4o", "gpt-4o-mini"))
    )
) : BesseresserApiService {

    override suspend fun analyze(
        ctx: Context,
        photoUri: Uri,
        settings: Settings,
        promptBundle: PromptBundle
    ): Result<Evaluation> = analyzeResult

    override suspend fun analyzeText(
        ctx: Context,
        settings: Settings,
        userDescription: String
    ): Result<Evaluation> = analyzeResult

    override suspend fun dayFazit(
        settings: Settings,
        goals: List<String>,
        promptContext: String?,
        mealLines: List<String>,
        prompts: FazitPromptBundle
    ): Result<DayFazitResult> = dayFazitResult

    override suspend fun splitGoals(
        request: SplitGoalsRequest
    ): Result<List<String>> = splitResult

    override suspend fun fetchModels(baseUrl: String, apiKey: String): Result<ApiClient.ModelsResult> = modelsResult
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

        // Erneutes Nutzen/Pushen einer favorisierten Notiz darf den Stern NIE löschen
        repo.pushRecentNote("Tee")
        val stillStarred = repo.loadRecentNotes(sortStarredFirst = true)
        assertTrue(stillStarred.first { it.text == "Tee" }.starred)
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

        val ev = Evaluation(goalResults = listOf(GoalResult("Z1", TrafficLight.GREEN, "Frisch")), summary = "Toll", erkannt = "Salat")
        val entry = repo.add(null, ev, note = "Mittagessen")
        assertEquals("Salat", entry.erkannt)
        assertEquals("Mittagessen", entry.note)

        repo.updateErkannt(entry.id, "Bunter Salat")
        val entryDayKey = dayKeyOf(entry.dateMillis)
        val fazit = DayFazit(entryDayKey, TrafficLight.GREEN, "Klasse", listOf(entry.id), 1000L, "fp")
        repo.saveDayFazit(fazit)
        val loadedFazit = repo.loadDayFazit(entryDayKey)
        assertNotNull(loadedFazit)
        assertEquals("Klasse", loadedFazit?.text)

        repo.deleteDayFazitForEntry(entry)
        assertNull(repo.loadDayFazit(entryDayKey))

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

        val ev = Evaluation(goalResults = listOf(GoalResult("Ziel", TrafficLight.GREEN, "Top")), summary = "Sehr gut", erkannt = "Apfel")
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

        // Backdated meal capture flow
        val targetDay = LocalDate.of(2026, 11, 5)
        vm.setAddTargetDay(targetDay)
        vm.setAddNote("150 g Skyr mit Beeren")
        vm.setShowAddText(true)

        var pickedBackdated: HistoryEntry? = null
        val backdatedEval = Evaluation(
            goalResults = listOf(GoalResult("Z", TrafficLight.GREEN, "Proteinreich")),
            summary = "Top Snack",
            erkannt = "150 g Skyr mit Beeren"
        )
        vm.saveBackdatedEntry(ev = backdatedEval, photoUriIn = null) { pickedBackdated = it }
        advanceUntilIdle()

        assertNotNull(pickedBackdated)
        assertTrue(pickedBackdated!!.backdated)
        assertEquals("150 g Skyr mit Beeren", pickedBackdated!!.erkannt)
        assertFalse(vm.uiState.value.showAddText)
        assertEquals("", vm.uiState.value.addNote)
        assertNull(vm.uiState.value.addTargetDay)
        // Note was also pushed to recent notes
        assertEquals("150 g Skyr mit Beeren", setRepo.loadRecentNotes().firstOrNull()?.text)
    }

    @Test
    fun testSetupViewModelSplitFailureFallback() = runTest(testDispatcher) {
        val api = FakeApiService(
            splitResult = Result.failure(ApiException("HTTP 401", "Debug: Unauthorized"))
        )
        val vm = SetupViewModel(apiService = api, settingsRepository = FakeSettingsRepository())

        vm.startSplitGoals(rawGoals = "Weniger Salz, mehr Gemüse")
        advanceUntilIdle()

        assertFalse(vm.uiState.value.splitting)
        assertTrue(vm.uiState.value.showSplit)
        assertEquals("Weniger Salz, mehr Gemüse", vm.uiState.value.splitEdited)
        assertEquals("Debug: Unauthorized", vm.uiState.value.splitDebug)
    }

    @Test
    fun testProfileViewModelCommaDecimalAndEmptyFields() {
        val profileRepo = FakeProfileRepository()
        val vm = ProfileViewModel(profileRepository = profileRepo)

        // Comma decimal weight ("74,5")
        vm.setWeightText("74,5")
        vm.setHeightText("180")
        vm.setNotesText("  Kein Zucker  ")
        assertTrue(vm.save())
        assertEquals(74.5, profileRepo.load().weightKg ?: 0.0, 0.001)
        assertEquals(180, profileRepo.load().heightCm)
        assertEquals("Kein Zucker", profileRepo.load().notes)

        // Clearing all optional fields is valid
        vm.setWeightText("")
        vm.setHeightText("")
        vm.setBirthDate(null)
        vm.setNotesText("")
        vm.setGender(Gender.NONE)
        assertTrue(vm.save())
        assertTrue(profileRepo.load().isEmpty)
    }

    @Test
    fun testHistoryViewModelPendingDeleteAndUndo() = runTest(testDispatcher) {
        val histRepo = FakeHistoryRepository()
        val vm = HistoryViewModel(
            apiService = FakeApiService(),
            historyRepository = histRepo,
            settingsRepository = FakeSettingsRepository()
        )
        vm.startObserving()

        val e1 = histRepo.add(null, Evaluation(erkannt = "Mahlzeit 1"), dateOverride = 1000L)
        val e2 = histRepo.add(null, Evaluation(erkannt = "Mahlzeit 2"), dateOverride = 2000L)
        val e3 = histRepo.add(null, Evaluation(erkannt = "Mahlzeit 3"), dateOverride = 3000L)
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
    fun testHistoryViewModelMoveEntryWithinDay() = runTest(testDispatcher) {
        val histRepo = FakeHistoryRepository()
        val vm = HistoryViewModel(
            apiService = FakeApiService(),
            historyRepository = histRepo,
            settingsRepository = FakeSettingsRepository()
        )
        vm.startObserving()

        val day = LocalDate.of(2026, 11, 6)
        val m1 = day.atTime(8, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val m2 = day.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val m3 = day.atTime(19, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        // Descending order initially: Abendessen (m3), Mittagessen (m2), Frühstück (m1)
        val eAbend = histRepo.add(null, Evaluation(erkannt = "Abendessen"), dateOverride = m3)
        val eMittag = histRepo.add(null, Evaluation(erkannt = "Mittagessen"), dateOverride = m2)
        val eFrueh = histRepo.add(null, Evaluation(erkannt = "Frühstück"), dateOverride = m1)
        advanceUntilIdle()

        assertEquals("Abendessen", vm.uiState.value.entries[0].erkannt)
        assertEquals("Mittagessen", vm.uiState.value.entries[1].erkannt)
        assertEquals("Frühstück", vm.uiState.value.entries[2].erkannt)

        // Save a DayFazit for this day before reordering
        val initialDayEntries = vm.uiState.value.entries
        val dayFazit = DayFazit(
            dayKey = dayKeyOf(day),
            ampel = TrafficLight.GREEN,
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
        assertEquals("Frühstück", reordered[0].erkannt)
        assertEquals("Abendessen", reordered[1].erkannt)
        assertEquals("Mittagessen", reordered[2].erkannt)
        // Timestamps must stay strictly descending
        assertTrue(reordered[0].dateMillis > reordered[1].dateMillis)
        assertTrue(reordered[1].dateMillis > reordered[2].dateMillis)

        // Existing DayFazit must still be present and NOT outdated after reordering
        val loadedFazitAfterReorder = histRepo.loadDayFazit(dayKeyOf(day))
        assertNotNull(loadedFazitAfterReorder)
        assertFalse(isFazitOutdated(loadedFazitAfterReorder!!, reordered))
    }
}
