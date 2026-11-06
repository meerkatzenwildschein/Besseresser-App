package de.gurum.besseresser

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

const val UNDO_DELETE_DELAY_MS = 5000L

data class HistoryUiState(
    val entries: List<HistoryEntry> = emptyList(),
    val pendingDeleteIds: Set<Long> = emptySet(),
    val fazitMap: Map<String, DayFazit> = emptyMap(),
    val loadingFazits: Set<String> = emptySet(),
    val expandedFazits: Set<String> = emptySet(),
    val weekFazit: DayFazit? = null,
    val loadingWeek: Boolean = false,
    val expandedWeek: Boolean = false,
    val addDialogForDay: LocalDate? = null,
    val showAddCamera: Boolean = false,
    val showAddReview: Boolean = false,
    val showAddText: Boolean = false,
    val showAddBusyCard: Boolean = false,
    val addNote: String = "",
    val addTargetDay: LocalDate? = null
)

class HistoryViewModel(
    private val apiService: BesseresserApiService = ApiClient,
    private val historyRepository: HistoryRepository? = null,
    private val settingsRepository: SettingsRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    private var dbObserving = false
    private val pendingDeleteJobs = mutableMapOf<Long, Job>()
    private val pendingDeleteEntries = mutableMapOf<Long, HistoryEntry>()

    private fun getHistoryRepo(ctx: Context?): HistoryRepository? =
        historyRepository ?: ctx?.let { RepositoryProvider.getHistoryRepository(it) }

    private fun getSettingsRepo(ctx: Context?): SettingsRepository? =
        settingsRepository ?: ctx?.let { RepositoryProvider.getSettingsRepository(it) }

    fun startObserving(ctx: Context? = null) {
        if (dbObserving) return
        val repo = getHistoryRepo(ctx) ?: return
        dbObserving = true
        repo.ensureMigrated()

        observeEntries(repo)
        observeDayFazits(repo)
        observeWeekFazits(repo)
    }

    private fun observeEntries(repo: HistoryRepository) {
        viewModelScope.launch {
            repo.getAllFlow().collect { list ->
                val wk = weekKeyOf(System.currentTimeMillis())
                val wf = repo.loadWeekFazit(wk)
                val currentWkEntries = list.filter { dayKeyOf(it.dateMillis) == wk }
                val validWf = validateWeekFazit(repo, wk, wf, currentWkEntries)
                _uiState.update { current ->
                    current.copy(
                        entries = list,
                        weekFazit = validWf
                    )
                }
            }
        }
    }

    private suspend fun validateWeekFazit(
        repo: HistoryRepository,
        weekKey: String,
        fazit: DayFazit?,
        entries: List<HistoryEntry>
    ): DayFazit? {
        if (fazit == null) return null
        return if (fazit.fingerprint == dayFingerprint(entries)) {
            fazit
        } else {
            repo.deleteWeekFazit(weekKey)
            null
        }
    }

    private fun observeDayFazits(repo: HistoryRepository) {
        viewModelScope.launch {
            repo.getDayFazitsFlow().collect { map ->
                _uiState.update { it.copy(fazitMap = map) }
            }
        }
    }

    private fun observeWeekFazits(repo: HistoryRepository) {
        viewModelScope.launch {
            repo.getWeekFazitsFlow().collect { map ->
                val currentWk = weekKeyOf(System.currentTimeMillis())
                val currentWf = map[currentWk] ?: return@collect
                val currentWkEntries = _uiState.value.entries.filter { dayKeyOf(it.dateMillis) == currentWk }
                if (currentWf.fingerprint == dayFingerprint(currentWkEntries)) {
                    _uiState.update { it.copy(weekFazit = currentWf) }
                }
            }
        }
    }

    fun toggleFazitExpanded(dayKey: String) {
        _uiState.update { current ->
            val set = current.expandedFazits.toMutableSet()
            if (dayKey in set) set.remove(dayKey) else set.add(dayKey)
            current.copy(expandedFazits = set)
        }
    }

    fun toggleWeekExpanded() {
        _uiState.update { it.copy(expandedWeek = !it.expandedWeek) }
    }

    fun setAddDialogForDay(day: LocalDate?) {
        _uiState.update { it.copy(addDialogForDay = day) }
    }

    fun setShowAddCamera(show: Boolean) {
        _uiState.update { it.copy(showAddCamera = show) }
    }

    fun setShowAddReview(show: Boolean) {
        _uiState.update { it.copy(showAddReview = show) }
    }

    fun setShowAddText(show: Boolean) {
        _uiState.update { it.copy(showAddText = show) }
    }

    fun setShowAddBusyCard(show: Boolean) {
        _uiState.update { it.copy(showAddBusyCard = show) }
    }

    fun setAddNote(note: String) {
        _uiState.update { it.copy(addNote = note) }
    }

    fun setAddTargetDay(day: LocalDate?) {
        _uiState.update { it.copy(addTargetDay = day) }
    }

    fun saveBackdatedEntry(
        ctx: Context? = null,
        ev: Evaluation,
        photoUriIn: Uri?,
        onPick: (HistoryEntry) -> Unit = {}
    ) {
        val day = _uiState.value.addTargetDay ?: return
        val note = _uiState.value.addNote.trim()
        val millis = day.atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val histRepo = getHistoryRepo(ctx)
        val setRepo = getSettingsRepo(ctx)
        viewModelScope.launch {
            val entry = histRepo?.add(
                photoUri = photoUriIn, ev = ev,
                note = note, dateOverride = millis, backdated = true
            ) ?: HistoryEntry(id = millis, dateMillis = millis, goalResults = ev.goalResults, summary = ev.summary, note = note, backdated = true)
            histRepo?.deleteDayFazitForEntry(entry)
            histRepo?.deleteWeekFazitForEntry(entry)
            setRepo?.pushRecentNote(note)
            _uiState.update {
                it.copy(
                    showAddCamera = false,
                    showAddReview = false,
                    showAddText = false,
                    showAddBusyCard = false,
                    addNote = "",
                    addTargetDay = null
                )
            }
            onPick(entry)
        }
    }

    fun deleteEntry(ctx: Context? = null, entry: HistoryEntry) {
        pendingDeleteJobs.remove(entry.id)?.cancel()
        pendingDeleteEntries.remove(entry.id)
        _uiState.update { it.copy(pendingDeleteIds = it.pendingDeleteIds - entry.id) }
        val histRepo = getHistoryRepo(ctx)
        viewModelScope.launch {
            histRepo?.delete(entry)
            histRepo?.deleteDayFazitForEntry(entry)
            histRepo?.deleteWeekFazitForEntry(entry)
        }
    }

    /** Markiert einen Eintrag zunächst als „vorgemerkt zum Löschen" (deaktiviert + Rückgängig-Option)
     *  und führt das endgültige Löschen erst nach [delayMs] (Standard: 5 Sekunden) aus. */
    fun requestDeleteEntry(
        ctx: Context? = null,
        entry: HistoryEntry,
        delayMs: Long = UNDO_DELETE_DELAY_MS
    ) {
        pendingDeleteJobs.remove(entry.id)?.cancel()
        pendingDeleteEntries[entry.id] = entry
        _uiState.update { it.copy(pendingDeleteIds = it.pendingDeleteIds + entry.id) }
        pendingDeleteJobs[entry.id] = viewModelScope.launch {
            if (delayMs > 0L) delay(delayMs)
            pendingDeleteJobs.remove(entry.id)
            pendingDeleteEntries.remove(entry.id)
            _uiState.update { it.copy(pendingDeleteIds = it.pendingDeleteIds - entry.id) }
            val histRepo = getHistoryRepo(ctx)
            histRepo?.delete(entry)
            histRepo?.deleteDayFazitForEntry(entry)
            histRepo?.deleteWeekFazitForEntry(entry)
        }
    }

    /** Bricht das vorgemerkte Löschen ab und stellt den Eintrag sofort wieder aktiv her. */
    fun undoDeleteEntry(entryId: Long) {
        pendingDeleteJobs.remove(entryId)?.cancel()
        pendingDeleteEntries.remove(entryId)
        _uiState.update { it.copy(pendingDeleteIds = it.pendingDeleteIds - entryId) }
    }

    /** Führt alle noch offenen Löschvorgänge sofort endgültig aus (z. B. beim Verlassen des Verlaufs). */
    fun flushPendingDeletes(ctx: Context? = null) {
        val toDelete = pendingDeleteEntries.values.toList()
        if (toDelete.isEmpty()) return
        pendingDeleteJobs.values.forEach { it.cancel() }
        pendingDeleteJobs.clear()
        pendingDeleteEntries.clear()
        _uiState.update { it.copy(pendingDeleteIds = emptySet()) }
        val histRepo = getHistoryRepo(ctx)
        viewModelScope.launch {
            toDelete.forEach { entry ->
                histRepo?.delete(entry)
                histRepo?.deleteDayFazitForEntry(entry)
                histRepo?.deleteWeekFazitForEntry(entry)
            }
        }
    }

    fun generateFazit(
        ctx: Context? = null,
        day: LocalDate,
        entriesOfDay: List<HistoryEntry>,
        settings: Settings,
        profile: Profile,
        mealLines: List<String>? = null,
        prompts: FazitPromptBundle? = null
    ) {
        val key = dayKeyOf(day)
        if (key in _uiState.value.loadingFazits) return
        _uiState.update {
            it.copy(
                loadingFazits = it.loadingFazits + key,
                expandedFazits = it.expandedFazits + key
            )
        }
        val lines = when {
            mealLines != null -> mealLines
            ctx != null -> dayFazitMealLines(ctx, entriesOfDay)
            else -> entriesOfDay.map { it.erkannt }
        }
        val promptBundle = when {
            prompts != null -> prompts
            ctx != null -> FazitPromptBundle.forDay(ctx)
            else -> FazitPromptBundle("", "", "", "")
        }
        val histRepo = getHistoryRepo(ctx)
        viewModelScope.launch {
            val result = apiService.dayFazit(
                settings = settings,
                goals = settings.goals,
                promptContext = ctx?.let { profileContextLine(it, profile) },
                mealLines = lines,
                prompts = promptBundle
            )
            result.onSuccess { res ->
                val fazit = DayFazit(
                    dayKey = key,
                    ampel = res.ampel,
                    text = res.text,
                    entryIds = entriesOfDay.map { it.id },
                    createdAt = System.currentTimeMillis(),
                    fingerprint = dayFingerprint(entriesOfDay)
                )
                histRepo?.saveDayFazit(fazit)
                _uiState.update { it.copy(loadingFazits = it.loadingFazits - key) }
            }.onFailure {
                _uiState.update { it.copy(loadingFazits = it.loadingFazits - key) }
            }
        }
    }

    fun generateWeekFazit(
        ctx: Context? = null,
        settings: Settings,
        profile: Profile,
        mealLines: List<String>? = null,
        prompts: FazitPromptBundle? = null
    ) {
        if (_uiState.value.loadingWeek) return
        _uiState.update { it.copy(loadingWeek = true, expandedWeek = true) }
        val wk = weekKeyOf(System.currentTimeMillis())
        val wkEntries = _uiState.value.entries.filter { dayKeyOf(it.dateMillis) == wk }
        val lines = when {
            mealLines != null -> mealLines
            ctx != null -> dayFazitMealLines(ctx, wkEntries)
            else -> wkEntries.map { it.erkannt }
        }
        val promptBundle = when {
            prompts != null -> prompts
            ctx != null -> FazitPromptBundle.forWeek(ctx)
            else -> FazitPromptBundle("", "", "", "")
        }
        val histRepo = getHistoryRepo(ctx)
        viewModelScope.launch {
            val result = apiService.dayFazit(
                settings = settings,
                goals = settings.goals,
                promptContext = ctx?.let { profileContextLine(it, profile) },
                mealLines = lines,
                prompts = promptBundle
            )
            result.onSuccess { res ->
                val wf = DayFazit(
                    wk, res.ampel, res.text, wkEntries.map { it.id },
                    System.currentTimeMillis(), dayFingerprint(wkEntries)
                )
                histRepo?.saveWeekFazit(wf)
                _uiState.update { it.copy(weekFazit = wf, loadingWeek = false) }
            }.onFailure {
                _uiState.update { it.copy(loadingWeek = false) }
            }
        }
    }
}
