package de.gurum.besseresser

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

data class HistoryUiState(
    val entries: List<HistoryEntry> = emptyList(),
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
    private val apiService: BesseresserApiService = ApiClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    private var dbObserving = false

    fun startObserving(ctx: Context) {
        if (dbObserving) return
        dbObserving = true
        HistoryStore.ensureMigrated(ctx)
        DayFazitStore.ensureMigrated(ctx)
        WeekFazitStore.ensureMigrated(ctx)

        val db = AppDatabase.getInstance(ctx)

        viewModelScope.launch {
            db.historyDao().getAllFlow().collect { entities ->
                val list = entities.map { it.toDomain() }
                _uiState.update { current ->
                    current.copy(
                        entries = list,
                        weekFazit = syncWeekFazit(ctx, list)
                    )
                }
            }
        }

        viewModelScope.launch {
            db.dayFazitDao().getAllFlow().collect { entities ->
                val map = entities.map { it.toDomain() }.associateBy { it.dayKey }
                _uiState.update { it.copy(fazitMap = map) }
            }
        }

        viewModelScope.launch {
            db.weekFazitDao().getAllFlow().collect { entities ->
                val map = entities.map { it.toDomain() }.associateBy { it.dayKey }
                val currentWk = weekKeyOf(System.currentTimeMillis())
                val currentWf = map[currentWk]
                if (currentWf != null) {
                    val currentWkEntries = _uiState.value.entries.filter { dayKeyOf(it.dateMillis) == currentWk }
                    if (currentWf.fingerprint == dayFingerprint(currentWkEntries)) {
                        _uiState.update { it.copy(weekFazit = currentWf) }
                    }
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
        ctx: Context,
        ev: Evaluation,
        photoUriIn: Uri?,
        onPick: (HistoryEntry) -> Unit
    ) {
        val day = _uiState.value.addTargetDay ?: return
        val note = _uiState.value.addNote.trim()
        val millis = day.atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        viewModelScope.launch {
            val entry = HistoryStore.add(
                ctx = ctx, photoUri = photoUriIn, ev = ev,
                note = note, dateOverride = millis, backdated = true
            )
            DayFazitStore.deleteForEntry(ctx, entry)
            WeekFazitStore.deleteForEntry(ctx, entry)
            RecentNotesStore.push(ctx, note)
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

    fun deleteEntry(ctx: Context, entry: HistoryEntry) {
        viewModelScope.launch {
            HistoryStore.delete(ctx, entry)
            DayFazitStore.deleteForEntry(ctx, entry)
            WeekFazitStore.deleteForEntry(ctx, entry)
        }
    }

    fun generateFazit(
        ctx: Context,
        day: LocalDate,
        entriesOfDay: List<HistoryEntry>,
        settings: Settings,
        profile: Profile
    ) {
        val key = dayKeyOf(day)
        if (key in _uiState.value.loadingFazits) return
        _uiState.update {
            it.copy(
                loadingFazits = it.loadingFazits + key,
                expandedFazits = it.expandedFazits + key
            )
        }
        val lines = dayFazitMealLines(ctx, entriesOfDay)
        viewModelScope.launch {
            val result = apiService.dayFazit(
                settings = settings,
                goals = settings.goals,
                profile = profile,
                mealLines = lines,
                prompts = FazitPromptBundle.forDay(ctx)
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
                DayFazitStore.save(ctx, fazit)
                _uiState.update { it.copy(loadingFazits = it.loadingFazits - key) }
            }.onFailure {
                _uiState.update { it.copy(loadingFazits = it.loadingFazits - key) }
            }
        }
    }

    fun generateWeekFazit(
        ctx: Context,
        settings: Settings,
        profile: Profile
    ) {
        if (_uiState.value.loadingWeek) return
        _uiState.update { it.copy(loadingWeek = true, expandedWeek = true) }
        val wk = weekKeyOf(System.currentTimeMillis())
        val wkEntries = _uiState.value.entries.filter { dayKeyOf(it.dateMillis) == wk }
        val lines = dayFazitMealLines(ctx, wkEntries)
        viewModelScope.launch {
            val result = apiService.dayFazit(
                settings = settings,
                goals = settings.goals,
                profile = profile,
                mealLines = lines,
                prompts = FazitPromptBundle.forWeek(ctx)
            )
            result.onSuccess { res ->
                val wf = DayFazit(
                    wk, res.ampel, res.text, wkEntries.map { it.id },
                    System.currentTimeMillis(), dayFingerprint(wkEntries)
                )
                WeekFazitStore.save(ctx, wf)
                _uiState.update { it.copy(weekFazit = wf, loadingWeek = false) }
            }.onFailure {
                _uiState.update { it.copy(loadingWeek = false) }
            }
        }
    }
}
