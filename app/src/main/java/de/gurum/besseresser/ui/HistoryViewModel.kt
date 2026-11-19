package de.gurum.besseresser.ui
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
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import de.gurum.besseresser.data.AppDispatchers
import de.gurum.besseresser.data.BesseresserApiService
import de.gurum.besseresser.data.ImagePreprocessor
import de.gurum.besseresser.model.calendarWeekOfKey
import de.gurum.besseresser.model.DayFazit
import de.gurum.besseresser.model.DayFazitSession
import de.gurum.besseresser.model.dayFingerprint
import de.gurum.besseresser.model.dayKeyOf
import de.gurum.besseresser.model.Evaluation
import de.gurum.besseresser.data.FazitPromptBundle
import de.gurum.besseresser.model.HistoryEntry
import de.gurum.besseresser.data.HistoryRepository
import de.gurum.besseresser.data.PromptBundle
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
    val addTargetTime: LocalTime? = null,
    val addError: String? = null
)

class HistoryViewModel(
    private val apiService: BesseresserApiService,
    private val historyRepository: HistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val imagePreprocessor: ImagePreprocessor,
    private val dispatchers: AppDispatchers = AppDispatchers()
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    private val pendingDeleteJobs = mutableMapOf<Long, Job>()
    private val pendingDeleteEntries = mutableMapOf<Long, HistoryEntry>()

    init {
        viewModelScope.launch { historyRepository.ensureMigrated() }
        observeEntries(historyRepository)
        observeDayFazits(historyRepository)
        observeWeekFazits(historyRepository)
    }

    private var latestRawWeekMap: Map<String, DayFazit> = emptyMap()

    private fun observeEntries(repo: HistoryRepository) {
        viewModelScope.launch {
            repo.getAllFlow().collect { list ->
                val validWeekMap = validateAllWeekFazits(list, latestRawWeekMap)
                val targetWk = latestDueWeekKey(list) ?: weekKeyOf(System.currentTimeMillis())
                val wkEntries = list.filter { weekKeyOf(it.dateMillis) == targetWk }
                val validWf: DayFazit? = validWeekMap[targetWk] ?: validateWeekFazit(
                    targetWk,
                    repo.loadWeekFazit(targetWk),
                    wkEntries
                )
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
                val validWeekMap = validateAllWeekFazits(entries, map)
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

    fun clearAddError() {
        _uiState.update { it.copy(addError = null) }
    }

    /** Nacherfassen (mit oder ohne Foto): nutzt eine gecachte Bewertung, falls vorhanden,
     *  ruft sonst die KI an und speichert danach. Prompts werden von der UI geliefert. */
    fun submitBackdated(
        photoUri: Uri?,
        settings: Settings,
        promptBundle: PromptBundle?,
        textPrompt: String?,
        onPick: (HistoryEntry) -> Unit = {}
    ) {
        val note = _uiState.value.addNote.trim()
        viewModelScope.launch {
            val cached = settingsRepository.findCachedEvaluation(note, settings.goals)
            if (cached != null) {
                settingsRepository.pushRecentNote(note, cached)
                saveBackdatedEntry(cached, photoUri, onPick)
                return@launch
            }
            settingsRepository.pushRecentNote(note)
            _uiState.update { it.copy(showAddBusyCard = true) }
            val result: Result<Evaluation> = when {
                photoUri != null && promptBundle != null -> {
                    val dataUri = runCatching {
                        withContext(dispatchers.io) { imagePreprocessor.prepareImageDataUri(photoUri) }
                    }.getOrElse { e ->
                        _uiState.update { it.copy(showAddBusyCard = false, addError = e.message) }
                        return@launch
                    }
                    apiService.analyze(dataUri, settings, promptBundle)
                }
                textPrompt != null -> apiService.analyzeText(textPrompt, settings)
                else -> {
                    _uiState.update { it.copy(showAddBusyCard = false) }
                    return@launch
                }
            }
            result.onSuccess { ev ->
                settingsRepository.pushRecentNote(note, ev)
                _uiState.update { it.copy(showAddBusyCard = false) }
                saveBackdatedEntry(ev, photoUri, onPick)
            }.onFailure { e ->
                _uiState.update { it.copy(showAddBusyCard = false, addError = e.message ?: e.javaClass.simpleName) }
            }
        }
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
        day: LocalDate,
        entriesOfDay: List<HistoryEntry>,
        settings: Settings,
        promptContext: String?,
        mealDescriptions: List<String>,
        prompts: FazitPromptBundle
    ) {
        val key = dayKeyOf(day)
        if (key in _uiState.value.loadingFazits) return
        _uiState.update {
            it.copy(
                loadingFazits = it.loadingFazits + key,
                expandedFazits = it.expandedFazits + key
            )
        }
        viewModelScope.launch {
            val result = apiService.dayFazit(
                settings = settings,
                goals = settings.goals,
                promptContext = promptContext,
                mealDescriptions = mealDescriptions,
                prompts = prompts
            )
            result.onSuccess { res ->
                val fazit = DayFazit(
                    dayKey = key,
                    rating = res.rating,
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
        settings: Settings,
        promptContext: String?,
        weekKey: String,
        mealDescriptions: List<String>? = null,
        prompts: FazitPromptBundle? = null
    ) {
        val wk = weekKey
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
        val lines = mealDescriptions ?: wkEntries.map { it.recognizedMeal }
        val promptBundle = prompts ?: FazitPromptBundle("", "", "", "")
        viewModelScope.launch {
            val result = apiService.dayFazit(
                settings = settings,
                goals = settings.goals,
                promptContext = promptContext,
                mealDescriptions = lines,
                prompts = promptBundle
            )
            result.onSuccess { res ->
                val wf = DayFazit(
                    wk, res.rating, res.text, wkEntries.map { it.id },
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
    fun photoFileOf(entry: HistoryEntry): java.io.File? =
        historyRepository.photoFileOf(entry)

    fun decodeThumbnail(entry: HistoryEntry, target: Int = 128): android.graphics.Bitmap? =
        historyRepository.decodeThumbnail(entry, target)

    private var autoFazitJob: Job? = null

    /**
     * Prüft und triggert fällige Tages- und Wochenfazits im Hintergrund (ViewModelScope).
     * Entkoppelt von UI-Rendering/LaunchedEffects für maximale Stabilität und Robustheit.
     */
    fun checkAndTriggerAutoFazits(
        settings: Settings,
        promptContext: String?,
        dayPrompts: FazitPromptBundle,
        weekPromptsProvider: (calendarWeek: Int) -> FazitPromptBundle,
        daymealDescriptionsProvider: (List<HistoryEntry>) -> List<String>,
        weekmealDescriptionsProvider: (List<HistoryEntry>) -> List<String>
    ) {
        autoFazitJob?.cancel()
        autoFazitJob = viewModelScope.launch {
            delay(1200) // Debounce
            val entries = _uiState.value.entries
            if (entries.isEmpty()) return@launch

            val nowDate = LocalDate.now()
            val nowHour = LocalTime.now().hour
            val autoFazitTime = nowHour >= FAZIT_AUTO_HOUR

            checkAutoDayFazits(
                entries = entries,
                nowDate = nowDate,
                autoFazitTime = autoFazitTime,
                settings = settings,
                promptContext = promptContext,
                dayPrompts = dayPrompts,
                daymealDescriptionsProvider = daymealDescriptionsProvider
            )

            checkAutoWeekFazits(
                entries = entries,
                nowDate = nowDate,
                nowHour = nowHour,
                settings = settings,
                promptContext = promptContext,
                weekPromptsProvider = weekPromptsProvider,
                weekmealDescriptionsProvider = weekmealDescriptionsProvider
            )
        }
    }

    private fun checkAutoDayFazits(
        entries: List<HistoryEntry>,
        nowDate: LocalDate,
        autoFazitTime: Boolean,
        settings: Settings,
        promptContext: String?,
        dayPrompts: FazitPromptBundle,
        daymealDescriptionsProvider: (List<HistoryEntry>) -> List<String>
    ) {
        val fazitMap = _uiState.value.fazitMap
        val loadingFazits = _uiState.value.loadingFazits

        groupByDay(entries).take(2).forEach { group ->
            val dayAutoReady = group.day.isBefore(nowDate) || autoFazitTime
            val key = dayKeyOf(group.day)
            if (key in loadingFazits || !dayAutoReady) return@forEach

            val fazit = fazitMap[key]
            if (fazit == null) {
                if (key !in DayFazitSession.attempted) {
                    DayFazitSession.attempted.add(key)
                    generateFazit(
                        day = group.day,
                        entriesOfDay = group.entries,
                        settings = settings,
                        promptContext = promptContext,
                        mealDescriptions = daymealDescriptionsProvider(group.entries),
                        prompts = dayPrompts
                    )
                }
            } else if (isFazitOutdated(fazit, group.entries)) {
                generateFazit(
                    day = group.day,
                    entriesOfDay = group.entries,
                    settings = settings,
                    promptContext = promptContext,
                    mealDescriptions = daymealDescriptionsProvider(group.entries),
                    prompts = dayPrompts
                )
            }
        }
    }

    private fun checkAutoWeekFazits(
        entries: List<HistoryEntry>,
        nowDate: LocalDate,
        nowHour: Int,
        settings: Settings,
        promptContext: String?,
        weekPromptsProvider: (calendarWeek: Int) -> FazitPromptBundle,
        weekmealDescriptionsProvider: (List<HistoryEntry>) -> List<String>
    ) {
        val weekFazitMap = _uiState.value.weekFazitMap
        val loadingWeeks = _uiState.value.loadingWeeks
        val dueWk = latestDueWeekKey(entries, nowDate, nowHour) ?: return

        if (dueWk !in loadingWeeks && weekFazitMap[dueWk] == null && dueWk !in DayFazitSession.attemptedWeekly) {
            DayFazitSession.attemptedWeekly.add(dueWk)
            val wkEntries = entries.filter { weekKeyOf(it.dateMillis) == dueWk }
            generateWeekFazit(
                settings = settings,
                promptContext = promptContext,
                weekKey = dueWk,
                mealDescriptions = weekmealDescriptionsProvider(wkEntries),
                prompts = weekPromptsProvider(calendarWeekOfKey(dueWk))
            )
        }
    }
}
