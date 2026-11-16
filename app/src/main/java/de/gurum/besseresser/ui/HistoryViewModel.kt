package de.gurum.besseresser.ui
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
import java.time.LocalTime
import java.time.ZoneId
import de.gurum.besseresser.data.BesseresserApiService
import de.gurum.besseresser.model.calendarWeekOfKey
import de.gurum.besseresser.model.DayFazit
import de.gurum.besseresser.model.dayFingerprint
import de.gurum.besseresser.model.dayKeyOf
import de.gurum.besseresser.model.Evaluation
import de.gurum.besseresser.data.FazitPromptBundle
import de.gurum.besseresser.model.HistoryEntry
import de.gurum.besseresser.data.HistoryRepository
import de.gurum.besseresser.model.Profile
import de.gurum.besseresser.model.Settings
import de.gurum.besseresser.data.SettingsRepository
import de.gurum.besseresser.model.weekKeyOf

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
    val weekFazitMap: Map<String, DayFazit> = emptyMap(),
    val loadingWeeks: Set<String> = emptySet(),
    val expandedWeeks: Set<String> = emptySet(),
    val addDialogForDay: LocalDate? = null,
    val showAddCamera: Boolean = false,
    val showAddReview: Boolean = false,
    val showAddText: Boolean = false,
    val showAddBusyCard: Boolean = false,
    val addNote: String = "",
    val addTargetDay: LocalDate? = null,
    val addTargetTime: LocalTime? = null
)

class HistoryViewModel(
    private val apiService: BesseresserApiService,
    private val historyRepository: HistoryRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    private var dbObserving = false
    private val pendingDeleteJobs = mutableMapOf<Long, Job>()
    private val pendingDeleteEntries = mutableMapOf<Long, HistoryEntry>()

    fun startObserving() {
        if (dbObserving) return
        dbObserving = true
        historyRepository.ensureMigrated()

        observeEntries(historyRepository)
        observeDayFazits(historyRepository)
        observeWeekFazits(historyRepository)
    }

    private var latestRawWeekMap: Map<String, DayFazit> = emptyMap()

    private fun observeEntries(repo: HistoryRepository) {
        viewModelScope.launch {
            repo.getAllFlow().collect { list ->
                val validWeekMap = validateAllWeekFazits(repo, list, latestRawWeekMap)
                val targetWk = latestDueWeekKey(list) ?: weekKeyOf(System.currentTimeMillis())
                val validWf = validWeekMap[targetWk] ?: repo.loadWeekFazit(targetWk)?.let { wf ->
                    val wkEntries = list.filter { weekKeyOf(it.dateMillis) == targetWk }
                    validateWeekFazit(targetWk, wf, wkEntries)
                }
                val mergedWeekMap = if (validWf != null) validWeekMap + (targetWk to validWf) else validWeekMap
                _uiState.update { current ->
                    current.copy(
                        entries = list,
                        weekFazit = validWf,
                        weekFazitMap = mergedWeekMap
                    )
                }
            }
        }
    }

    private suspend fun validateAllWeekFazits(
        repo: HistoryRepository,
        entries: List<HistoryEntry>,
        rawMap: Map<String, DayFazit>
    ): Map<String, DayFazit> {
        if (entries.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, DayFazit>()
        for ((wk, wf) in rawMap) {
            val wkEntries = entries.filter { weekKeyOf(it.dateMillis) == wk }
            val validated = validateWeekFazit(wk, wf, wkEntries)
            if (validated != null) {
                result[wk] = validated
            }
        }
        return result
    }

    private suspend fun validateWeekFazit(
        weekKey: String,
        fazit: DayFazit?,
        entries: List<HistoryEntry>
    ): DayFazit? {
        if (fazit == null) return null
        return if (entries.isNotEmpty() && fazit.fingerprint == dayFingerprint(entries)) {
            fazit
        } else {
            historyRepository.deleteWeekFazit(weekKey)
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
                latestRawWeekMap = map
                val entries = _uiState.value.entries
                val validWeekMap = validateAllWeekFazits(repo, entries, map)
                val targetWk = latestDueWeekKey(entries) ?: weekKeyOf(System.currentTimeMillis())
                _uiState.update {
                    it.copy(
                        weekFazit = validWeekMap[targetWk],
                        weekFazitMap = validWeekMap
                    )
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

    fun toggleWeekExpanded(weekKey: String? = null) {
        _uiState.update { current ->
            if (weekKey == null) {
                current.copy(expandedWeek = !current.expandedWeek)
            } else {
                val set = current.expandedWeeks.toMutableSet()
                if (weekKey in set) set.remove(weekKey) else set.add(weekKey)
                current.copy(
                    expandedWeek = weekKey in set,
                    expandedWeeks = set
                )
            }
        }
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

    fun setAddTargetTime(time: LocalTime?) {
        _uiState.update { it.copy(addTargetTime = time) }
    }

    /** Prüft beim Nacherfassen (mit oder ohne Foto), ob für die aktuelle Anmerkung [addNote]
     *  bereits eine zu [settings.goals] passende KI-Bewertung im Menü-Speicher existiert.
     *  Falls ja, wird der Eintrag sofort ohne KI-Aufruf gespeichert. */
    suspend fun trySaveCachedBackdated(
        settings: Settings,
        photoUriIn: Uri?,
        onPick: (HistoryEntry) -> Unit = {}
    ): Boolean {
        val note = _uiState.value.addNote.trim()
        val cachedEv = settingsRepository.findCachedEvaluation(note, settings.goals) ?: return false
        saveBackdatedEntry(cachedEv, photoUriIn, onPick)
        return true
    }

    fun saveBackdatedEntry(
        ev: Evaluation,
        photoUriIn: Uri?,
        onPick: (HistoryEntry) -> Unit = {}
    ) {
        val day = _uiState.value.addTargetDay ?: return
        val time = _uiState.value.addTargetTime ?: LocalTime.now()
        val note = _uiState.value.addNote.trim()
        val millis = day.atTime(time.hour, time.minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        viewModelScope.launch {
            val entry = historyRepository.add(
                photoUri = photoUriIn, ev = ev,
                note = note, dateOverride = millis, backdated = false
            )
            historyRepository.deleteDayFazitForEntry(entry)
            historyRepository.deleteWeekFazitForEntry(entry)
            settingsRepository.pushRecentNote(note, ev)
            _uiState.update {
                it.copy(
                    showAddCamera = false,
                    showAddReview = false,
                    showAddText = false,
                    showAddBusyCard = false,
                    addNote = "",
                    addTargetDay = null,
                    addTargetTime = null
                )
            }
            onPick(entry)
        }
    }

    fun deleteEntry(entry: HistoryEntry) {
        pendingDeleteJobs.remove(entry.id)?.cancel()
        pendingDeleteEntries.remove(entry.id)
        _uiState.update { it.copy(pendingDeleteIds = it.pendingDeleteIds - entry.id) }
        viewModelScope.launch {
            historyRepository.delete(entry)
            historyRepository.deleteDayFazitForEntry(entry)
            historyRepository.deleteWeekFazitForEntry(entry)
        }
    }

    /** Markiert einen Eintrag zunächst als „vorgemerkt zum Löschen" (deaktiviert + Rückgängig-Option)
     *  und führt das endgültige Löschen erst nach [delayMs] (Standard: 5 Sekunden) aus. */
    fun requestDeleteEntry(
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
            historyRepository.delete(entry)
            historyRepository.deleteDayFazitForEntry(entry)
            historyRepository.deleteWeekFazitForEntry(entry)
        }
    }

    /** Bricht das vorgemerkte Löschen ab und stellt den Eintrag sofort wieder aktiv her. */
    fun undoDeleteEntry(entryId: Long) {
        pendingDeleteJobs.remove(entryId)?.cancel()
        pendingDeleteEntries.remove(entryId)
        _uiState.update { it.copy(pendingDeleteIds = it.pendingDeleteIds - entryId) }
    }

    /** Führt alle noch offenen Löschvorgänge sofort endgültig aus (z. B. beim Verlassen des Verlaufs). */
    fun flushPendingDeletes() {
        val toDelete = pendingDeleteEntries.values.toList()
        if (toDelete.isEmpty()) return
        pendingDeleteJobs.values.forEach { it.cancel() }
        pendingDeleteJobs.clear()
        pendingDeleteEntries.clear()
        _uiState.update { it.copy(pendingDeleteIds = emptySet()) }
        viewModelScope.launch {
            toDelete.forEach { entry ->
                historyRepository.delete(entry)
                historyRepository.deleteDayFazitForEntry(entry)
                historyRepository.deleteWeekFazitForEntry(entry)
            }
        }
    }

    /** Verschiebt einen Eintrag innerhalb seines Tages von [fromIndex] nach [toIndex].
     *  Aktualisiert ausschließlich [HistoryEntry.sortOrder] (absteigend), sodass die echte
     *  Erfassungsuhrzeit ([HistoryEntry.dateMillis]) unverändert erhalten bleibt. */
    fun moveEntryWithinDay(day: LocalDate, fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val currentEntries = _uiState.value.entries
        val dayEntries = currentEntries.filter {
            java.time.Instant.ofEpochMilli(it.dateMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate() == day
        }.sortedByDescending { it.effectiveSortOrder }.toMutableList()

        if (fromIndex !in dayEntries.indices || toIndex !in dayEntries.indices) return

        val moved = dayEntries.removeAt(fromIndex)
        dayEntries.add(toIndex, moved)

        // Die vorhandenen Sortierwerte absteigend sortieren und der neuen Reihenfolge zuweisen
        val sortOrders = dayEntries.map { it.effectiveSortOrder }.sortedDescending().toMutableList()
        // Sicherstellen, dass keine doppelten Sortierwerte existieren (jeweils mind. 1000 Differenz)
        for (i in 1 until sortOrders.size) {
            if (sortOrders[i] >= sortOrders[i - 1]) {
                sortOrders[i] = sortOrders[i - 1] - 1000L
            }
        }

        val reorderedDayEntries = dayEntries.mapIndexed { idx, entry ->
            entry.copy(sortOrder = sortOrders[idx])
        }

        // Optimistisches Update im UI-State
        val reorderedMap = reorderedDayEntries.associateBy { it.id }
        val updatedAllEntries = currentEntries.map { reorderedMap[it.id] ?: it }
            .sortedByDescending { it.effectiveSortOrder }

        _uiState.update { it.copy(entries = updatedAllEntries) }

        viewModelScope.launch {
            historyRepository.reorderEntries(reorderedDayEntries)
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
                historyRepository.saveDayFazit(fazit)
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
        weekKey: String? = null,
        mealLines: List<String>? = null,
        prompts: FazitPromptBundle? = null
    ) {
        val wk = weekKey
            ?: latestDueWeekKey(_uiState.value.entries)
            ?: weekKeyOf(System.currentTimeMillis())
        if (wk in _uiState.value.loadingWeeks) return
        _uiState.update {
            it.copy(
                loadingWeek = true,
                expandedWeek = true,
                loadingWeeks = it.loadingWeeks + wk,
                expandedWeeks = it.expandedWeeks + wk
            )
        }
        val wkEntries = _uiState.value.entries.filter { weekKeyOf(it.dateMillis) == wk }
        val lines = when {
            mealLines != null -> mealLines
            ctx != null -> weekFazitMealLines(ctx, wkEntries)
            else -> wkEntries.map { it.erkannt }
        }
        val promptBundle = when {
            prompts != null -> prompts
            ctx != null -> FazitPromptBundle.forWeek(ctx, calendarWeekOfKey(wk))
            else -> FazitPromptBundle("", "", "", "")
        }
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
                historyRepository.saveWeekFazit(wf)
                _uiState.update {
                    val newLoading = it.loadingWeeks - wk
                    it.copy(
                        weekFazit = wf,
                        loadingWeek = newLoading.isNotEmpty(),
                        weekFazitMap = it.weekFazitMap + (wk to wf),
                        loadingWeeks = newLoading
                    )
                }
            }.onFailure {
                _uiState.update {
                    val newLoading = it.loadingWeeks - wk
                    it.copy(
                        loadingWeek = newLoading.isNotEmpty(),
                        loadingWeeks = newLoading
                    )
                }
            }
        }
    }
}
