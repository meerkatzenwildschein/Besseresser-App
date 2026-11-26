package de.gurum.besseresser.ui
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.io.File
import de.gurum.besseresser.data.AppDispatchers
import de.gurum.besseresser.data.AutoFazitCallbacks
import de.gurum.besseresser.data.AutoFazitHistoryContext
import de.gurum.besseresser.data.AutoFazitPromptConfig
import de.gurum.besseresser.data.BesseresserApiService
import de.gurum.besseresser.data.FazitManager
import de.gurum.besseresser.data.HistoryDeleteManager
import de.gurum.besseresser.data.HistoryReorderUseCase
import de.gurum.besseresser.data.ImagePreprocessor
import de.gurum.besseresser.data.NetworkConnectivityChecker
import de.gurum.besseresser.data.PhotoCaptureManager
import de.gurum.besseresser.data.TriggerAutoFazitUseCase
import de.gurum.besseresser.data.UNDO_DELETE_DELAY_MS
import de.gurum.besseresser.model.calendarWeekOfKey
import de.gurum.besseresser.model.DayFazit
import de.gurum.besseresser.model.dayKeyOf
import de.gurum.besseresser.model.Evaluation
import de.gurum.besseresser.data.FazitPromptBundle
import de.gurum.besseresser.model.HistoryEntry
import de.gurum.besseresser.data.HistoryRepository
import de.gurum.besseresser.data.PromptBundle
import de.gurum.besseresser.model.Profile
import de.gurum.besseresser.data.ProfileRepository
import de.gurum.besseresser.data.PromptRepository
import de.gurum.besseresser.model.Settings
import de.gurum.besseresser.data.SettingsRepository
import de.gurum.besseresser.ui.latestDueWeekKey
import de.gurum.besseresser.model.weekKeyOf

class HistoryViewModel(
    private val apiService: BesseresserApiService,
    private val historyRepository: HistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val imagePreprocessor: ImagePreprocessor,
    private val dispatchers: AppDispatchers = AppDispatchers(),
    private val photoCaptureManager: PhotoCaptureManager? = null,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    private val profileRepository: ProfileRepository? = null,
    private val promptRepository: PromptRepository? = null,
    fazitManager: FazitManager? = null,
    private val networkChecker: NetworkConnectivityChecker = NetworkConnectivityChecker { true },
    deleteManager: HistoryDeleteManager? = null,
    reorderUseCase: HistoryReorderUseCase? = null,
    autoFazitUseCase: TriggerAutoFazitUseCase? = null
) : ViewModel() {

    private val fazitManager: FazitManager = fazitManager ?: FazitManager(
        apiService = apiService,
        historyRepository = historyRepository,
        settingsRepository = settingsRepository,
        profileRepository = profileRepository,
        promptRepository = promptRepository,
        dispatchers = dispatchers
    )
    private val deleteManager: HistoryDeleteManager = deleteManager ?: HistoryDeleteManager(historyRepository)
    private val reorderUseCase: HistoryReorderUseCase = reorderUseCase ?: HistoryReorderUseCase(historyRepository)
    private val autoFazitUseCase: TriggerAutoFazitUseCase = autoFazitUseCase ?: TriggerAutoFazitUseCase(
        fazitManager = this.fazitManager,
        settingsRepository = settingsRepository,
        profileRepository = profileRepository,
        promptRepository = promptRepository
    )

    fun createCaptureFile(): Pair<File, Uri> {
        return photoCaptureManager?.createCaptureFile()
            ?: error("PhotoCaptureManager is not configured")
    }

    fun deleteCaptureFile(file: File?) {
        photoCaptureManager?.deleteCaptureFile(file)
    }

    fun deleteCaptureFile(uri: Uri?) {
        photoCaptureManager?.deleteCaptureFile(uri)
    }

    companion object {
        private const val KEY_ADD_NOTE = "history_add_note"
        private const val KEY_ADD_TARGET_DAY = "history_add_target_day"
        private const val KEY_ADD_TARGET_TIME = "history_add_target_time"
        private const val KEY_ADD_DIALOG_FOR_DAY = "history_add_dialog_for_day"
        private const val KEY_SHOW_ADD_CAMERA = "history_show_add_camera"
        private const val KEY_SHOW_ADD_REVIEW = "history_show_add_review"
        private const val KEY_SHOW_ADD_TEXT = "history_show_add_text"
        private const val KEY_EXPANDED_FAZITS = "history_expanded_fazits"
        private const val KEY_EXPANDED_WEEKS = "history_expanded_weeks"
        private const val KEY_EXPANDED_WEEK = "history_expanded_week"
    }

    private val _uiState = MutableStateFlow(
        HistoryUiState(
            addNote = savedStateHandle[KEY_ADD_NOTE] ?: "",
            addTargetDay = savedStateHandle.get<String>(KEY_ADD_TARGET_DAY)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            addTargetTime = savedStateHandle.get<String>(KEY_ADD_TARGET_TIME)?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
            addDialogForDay = savedStateHandle.get<String>(KEY_ADD_DIALOG_FOR_DAY)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            showAddCamera = savedStateHandle[KEY_SHOW_ADD_CAMERA] ?: false,
            showAddReview = savedStateHandle[KEY_SHOW_ADD_REVIEW] ?: false,
            showAddText = savedStateHandle[KEY_SHOW_ADD_TEXT] ?: false,
            expandedFazits = (savedStateHandle.get<ArrayList<String>>(KEY_EXPANDED_FAZITS) ?: emptyList()).toSet(),
            expandedWeeks = (savedStateHandle.get<ArrayList<String>>(KEY_EXPANDED_WEEKS) ?: emptyList()).toSet(),
            expandedWeek = savedStateHandle[KEY_EXPANDED_WEEK] ?: false
        )
    )
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        observeHistoryData(historyRepository)
    }

    private fun observeHistoryData(repo: HistoryRepository) {
        viewModelScope.launch {
            combine(
                repo.getAllFlow(),
                repo.getDayFazitsFlow(),
                repo.getWeekFazitsFlow()
            ) { entries, dayFazits, rawWeekFazits ->
                val validWeekMap = validateAllWeekFazits(entries, rawWeekFazits)
                val targetWk = latestDueWeekKey(entries) ?: weekKeyOf(System.currentTimeMillis())
                val wkEntries = entries.filter { weekKeyOf(it.dateMillis) == targetWk }
                val validWf: DayFazit? = validWeekMap[targetWk] ?: validateWeekFazit(
                    targetWk,
                    repo.loadWeekFazit(targetWk),
                    wkEntries
                )
                val mergedWeekMap = if (validWf != null) validWeekMap + (targetWk to validWf) else validWeekMap
                HistoryDataUpdate(entries, dayFazits, validWf, mergedWeekMap)
            }.collect { data ->
                val nowDate = LocalDate.now()
                val nowHour = LocalTime.now().hour
                val items = buildHistoryUiItems(data.entries, nowDate, nowHour, fazitMap = data.dayFazits)
                _uiState.update { current ->
                    current.copy(
                        entries = data.entries,
                        uiItems = items,
                        fazitMap = data.dayFazits,
                        weekFazit = data.weekFazit,
                        weekFazitMap = data.weekFazitMap
                    )
                }
                triggerAutoFazits()
            }
        }
    }

    private data class HistoryDataUpdate(
        val entries: List<HistoryEntry>,
        val dayFazits: Map<String, DayFazit>,
        val weekFazit: DayFazit?,
        val weekFazitMap: Map<String, DayFazit>
    )

    private suspend fun validateAllWeekFazits(
        entries: List<HistoryEntry>,
        rawMap: Map<String, DayFazit>
    ): Map<String, DayFazit> = fazitManager.validateAllWeekFazits(entries, rawMap)

    private suspend fun validateWeekFazit(
        weekKey: String,
        fazit: DayFazit?,
        entries: List<HistoryEntry>
    ): DayFazit? = fazitManager.validateWeekFazit(weekKey, fazit, entries)

    fun toggleFazitExpanded(dayKey: String) {
        _uiState.update { current ->
            val set = current.expandedFazits.toMutableSet()
            if (dayKey in set) set.remove(dayKey) else set.add(dayKey)
            savedStateHandle[KEY_EXPANDED_FAZITS] = ArrayList(set)
            current.copy(expandedFazits = set)
        }
    }

    fun toggleWeekExpanded(weekKey: String? = null) {
        _uiState.update { current ->
            if (weekKey == null) {
                val exp = !current.expandedWeek
                savedStateHandle[KEY_EXPANDED_WEEK] = exp
                current.copy(expandedWeek = exp)
            } else {
                val set = current.expandedWeeks.toMutableSet()
                if (weekKey in set) set.remove(weekKey) else set.add(weekKey)
                savedStateHandle[KEY_EXPANDED_WEEKS] = ArrayList(set)
                savedStateHandle[KEY_EXPANDED_WEEK] = weekKey in set
                current.copy(
                    expandedWeek = weekKey in set,
                    expandedWeeks = set
                )
            }
        }
    }

    fun setAddDialogForDay(day: LocalDate?) {
        if (day != null) savedStateHandle[KEY_ADD_DIALOG_FOR_DAY] = day.toString() else savedStateHandle.remove<String>(KEY_ADD_DIALOG_FOR_DAY)
        _uiState.update { it.copy(addDialogForDay = day) }
    }

    fun setShowAddCamera(show: Boolean) {
        savedStateHandle[KEY_SHOW_ADD_CAMERA] = show
        val current = _uiState.value
        val capturePair = if (show && current.addPhotoFile == null) {
            photoCaptureManager?.createCaptureFile()
        } else null
        _uiState.update {
            it.copy(
                showAddCamera = show,
                addPhotoFile = capturePair?.first ?: it.addPhotoFile,
                addPhotoUri = capturePair?.second ?: it.addPhotoUri
            )
        }
    }

    fun setShowAddReview(show: Boolean) {
        savedStateHandle[KEY_SHOW_ADD_REVIEW] = show
        val current = _uiState.value
        val capturePair = if (show && current.addPhotoFile == null) {
            photoCaptureManager?.createCaptureFile()
        } else null
        _uiState.update {
            it.copy(
                showAddReview = show,
                addPhotoFile = capturePair?.first ?: it.addPhotoFile,
                addPhotoUri = capturePair?.second ?: it.addPhotoUri
            )
        }
    }

    fun ensureAddCaptureFile(): Pair<File, Uri> {
        val current = _uiState.value
        val existingFile = current.addPhotoFile
        val existingUri = current.addPhotoUri
        if (existingFile != null && existingUri != null) {
            return existingFile to existingUri
        }
        val fresh = createCaptureFile()
        _uiState.update { it.copy(addPhotoFile = fresh.first, addPhotoUri = fresh.second) }
        return fresh
    }

    fun cancelAddPhotoCapture() {
        val fileToDelete = _uiState.value.addPhotoFile
        val uriToDelete = _uiState.value.addPhotoUri
        savedStateHandle[KEY_SHOW_ADD_CAMERA] = false
        savedStateHandle[KEY_SHOW_ADD_REVIEW] = false
        _uiState.update {
            it.copy(
                showAddCamera = false,
                showAddReview = false,
                addPhotoFile = null,
                addPhotoUri = null
            )
        }
        if (fileToDelete != null) {
            photoCaptureManager?.deleteCaptureFile(fileToDelete)
        } else if (uriToDelete != null) {
            photoCaptureManager?.deleteCaptureFile(uriToDelete)
        }
    }

    fun setShowAddText(show: Boolean) {
        savedStateHandle[KEY_SHOW_ADD_TEXT] = show
        _uiState.update { it.copy(showAddText = show) }
    }

    fun setShowAddBusyCard(show: Boolean) {
        _uiState.update { it.copy(showAddBusyCard = show) }
    }

    fun setAddNote(note: String) {
        savedStateHandle[KEY_ADD_NOTE] = note
        _uiState.update { it.copy(addNote = note) }
    }

    fun setAddTargetDay(day: LocalDate?) {
        if (day != null) savedStateHandle[KEY_ADD_TARGET_DAY] = day.toString() else savedStateHandle.remove<String>(KEY_ADD_TARGET_DAY)
        _uiState.update { it.copy(addTargetDay = day) }
    }

    fun setAddTargetTime(time: LocalTime?) {
        if (time != null) savedStateHandle[KEY_ADD_TARGET_TIME] = time.toString() else savedStateHandle.remove<String>(KEY_ADD_TARGET_TIME)
        _uiState.update { it.copy(addTargetTime = time) }
    }

    fun clearAddError() {
        _uiState.update { it.copy(addError = null) }
    }

    fun submitBackdated(
        photoUri: Uri?,
        textOnly: Boolean = false,
        onPick: (HistoryEntry) -> Unit = {}
    ) {
        val resolvedUri = if (textOnly) null else (photoUri ?: _uiState.value.addPhotoUri)
        val note = _uiState.value.addNote.trim()
        viewModelScope.launch {
            val settings = settingsRepository.currentSettings()
            val promptBundle = if (resolvedUri != null) promptRepository?.createAnalyzePromptBundle(note) else null
            val textPrompt = if (textOnly) promptRepository?.createAnalyzeTextPrompt(settings.goals, note) else null
            submitBackdated(resolvedUri, settings, promptBundle, textPrompt, onPick)
        }
    }

    fun submitBackdated(
        photoUri: Uri?,
        settings: Settings,
        promptBundle: PromptBundle?,
        textPrompt: String?,
        onPick: (HistoryEntry) -> Unit = {}
    ) {
        val note = _uiState.value.addNote.trim()
        viewModelScope.launch {
            val cached = settingsRepository.findCachedEvaluation(note, settings.toGoalTextList())
            if (cached != null) {
                settingsRepository.pushRecentNote(note, cached)
                saveBackdatedEntry(cached, photoUri, onPick)
                return@launch
            }
            if (!networkChecker.isInternetAvailable()) {
                settingsRepository.pushRecentNote(note)
                _uiState.update { it.copy(showAddBusyCard = false, addError = null) }
                saveBackdatedEntry(Evaluation(), photoUri, onPick)
                return@launch
            }
            settingsRepository.pushRecentNote(note)
            _uiState.update { it.copy(showAddBusyCard = true) }
            val result: Result<Evaluation> = when {
                photoUri != null && promptBundle != null -> {
                    val dataUri = runCatching {
                        withContext(dispatchers.io) { imagePreprocessor.prepareImageDataUri(photoUri) }
                    }.getOrElse { e ->
                        _uiState.update { it.copy(showAddBusyCard = false, addError = ErrorMapper.toUserMessage(e)) }
                        saveBackdatedEntry(Evaluation(), photoUri, onPick)
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
                _uiState.update { it.copy(showAddBusyCard = false, addError = ErrorMapper.toUserMessage(e)) }
                saveBackdatedEntry(Evaluation(), photoUri, onPick)
            }
        }
    }

    suspend fun trySaveCachedBackdated(
        settings: Settings,
        photoUriIn: Uri?,
        onPick: (HistoryEntry) -> Unit = {}
    ): Boolean {
        val note = _uiState.value.addNote.trim()
        val cachedEv = settingsRepository.findCachedEvaluation(note, settings.toGoalTextList()) ?: return false
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
        val stateFile = _uiState.value.addPhotoFile
        val millis = day.atTime(time.hour, time.minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val isRatedEv = ev.goalResults.isNotEmpty() || ev.summary.isNotBlank() || ev.recognizedMeal.isNotBlank()
        viewModelScope.launch {
            val entry = historyRepository.add(
                photoUri = photoUriIn, ev = ev,
                note = note, dateOverride = millis, backdated = false
            )
            if (photoUriIn != null) {
                photoCaptureManager?.deleteCaptureFile(photoUriIn)
            } else if (stateFile != null) {
                photoCaptureManager?.deleteCaptureFile(stateFile)
            }
            if (isRatedEv) {
                historyRepository.deleteDayFazitForEntry(entry)
                historyRepository.deleteWeekFazitForEntry(entry)
                settingsRepository.pushRecentNote(note, ev)
            } else if (note.isNotBlank()) {
                settingsRepository.pushRecentNote(note)
            }
            savedStateHandle[KEY_SHOW_ADD_CAMERA] = false
            savedStateHandle[KEY_SHOW_ADD_REVIEW] = false
            savedStateHandle[KEY_SHOW_ADD_TEXT] = false
            savedStateHandle[KEY_ADD_NOTE] = ""
            savedStateHandle.remove<String>(KEY_ADD_TARGET_DAY)
            savedStateHandle.remove<String>(KEY_ADD_TARGET_TIME)
            _uiState.update {
                it.copy(
                    showAddCamera = false,
                    showAddReview = false,
                    showAddText = false,
                    showAddBusyCard = false,
                    addPhotoFile = null,
                    addPhotoUri = null,
                    addNote = "",
                    addTargetDay = null,
                    addTargetTime = null
                )
            }
            onPick(entry)
        }
    }

    fun deleteEntry(entry: HistoryEntry) {
        deleteManager.deleteEntry(entry, viewModelScope) { pendingIds ->
            _uiState.update { it.copy(pendingDeleteIds = pendingIds) }
        }
    }

    fun requestDeleteEntry(
        entry: HistoryEntry,
        delayMs: Long = UNDO_DELETE_DELAY_MS
    ) {
        deleteManager.requestDeleteEntry(entry, delayMs, viewModelScope) { pendingIds ->
            _uiState.update { it.copy(pendingDeleteIds = pendingIds) }
        }
    }

    fun undoDeleteEntry(entryId: Long) {
        deleteManager.undoDeleteEntry(entryId) { pendingIds ->
            _uiState.update { it.copy(pendingDeleteIds = pendingIds) }
        }
    }

    fun flushPendingDeletes() {
        deleteManager.flushPendingDeletes(viewModelScope) { pendingIds ->
            _uiState.update { it.copy(pendingDeleteIds = pendingIds) }
        }
    }

    fun moveEntryWithinDay(day: LocalDate, fromIndex: Int, toIndex: Int) {
        val currentEntries = _uiState.value.entries
        val updatedAllEntries = reorderUseCase.reorderWithinDay(day, fromIndex, toIndex, currentEntries) ?: return

        val nowDate = LocalDate.now()
        val nowHour = LocalTime.now().hour
        val items = buildHistoryUiItems(updatedAllEntries, nowDate, nowHour, fazitMap = _uiState.value.fazitMap)
        _uiState.update { it.copy(entries = updatedAllEntries, uiItems = items) }

        viewModelScope.launch {
            reorderUseCase.persistReorder(day, updatedAllEntries)
        }
    }

    fun generateFazit(
        day: LocalDate,
        entriesOfDay: List<HistoryEntry>? = null
    ) {
        val key = dayKeyOf(day)
        if (key in _uiState.value.loadingFazits) return
        val entries = (entriesOfDay ?: _uiState.value.entries.filter {
            java.time.Instant.ofEpochMilli(it.dateMillis).atZone(ZoneId.systemDefault()).toLocalDate() == day
        }).filter { it.isRated }
        if (entries.isEmpty()) return
        viewModelScope.launch {
            val settings = settingsRepository.currentSettings()
            val profile = profileRepository?.currentProfile() ?: Profile()
            val promptContext = promptRepository?.profileContextLine(profile)
            val prompts = promptRepository?.createDayFazitPrompts() ?: FazitPromptBundle("", "", "", "")
            val mealDescriptions = promptRepository?.dayFazitMealDescriptions(entries) ?: entries.map { it.recognizedMeal }
            generateFazit(
                day = day,
                entriesOfDay = entries,
                settings = settings,
                promptContext = promptContext,
                mealDescriptions = mealDescriptions,
                prompts = prompts
            )
        }
    }

    fun generateWeekFazit(weekKey: String) {
        if (weekKey in _uiState.value.loadingWeeks) return
        viewModelScope.launch {
            val settings = settingsRepository.currentSettings()
            val profile = profileRepository?.currentProfile() ?: Profile()
            val promptContext = promptRepository?.profileContextLine(profile)
            val wkEntries = _uiState.value.entries.filter { it.isRated && weekKeyOf(it.dateMillis) == weekKey }
            if (wkEntries.isEmpty()) return@launch
            val prompts = promptRepository?.createWeekFazitPrompts(calendarWeekOfKey(weekKey)) ?: FazitPromptBundle("", "", "", "")
            val mealDescriptions = promptRepository?.weekFazitMealDescriptions(wkEntries) ?: wkEntries.map { it.recognizedMeal }
            generateWeekFazit(
                settings = settings,
                promptContext = promptContext,
                weekKey = weekKey,
                mealDescriptions = mealDescriptions,
                prompts = prompts
            )
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
        val ratedEntries = entriesOfDay.filter { it.isRated }
        if (ratedEntries.isEmpty()) return
        val key = dayKeyOf(day)
        if (key in _uiState.value.loadingFazits) return
        _uiState.update {
            it.copy(
                loadingFazits = it.loadingFazits + key,
                expandedFazits = it.expandedFazits + key
            )
        }
        viewModelScope.launch {
            fazitManager.computeDayFazit(
                day = day,
                entriesOfDay = ratedEntries,
                settings = settings,
                promptContext = promptContext,
                mealDescriptions = mealDescriptions,
                prompts = prompts
            )
            _uiState.update { it.copy(loadingFazits = it.loadingFazits - key) }
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
        val wkEntries = _uiState.value.entries.filter { it.isRated && weekKeyOf(it.dateMillis) == wk }
        if (wkEntries.isEmpty()) return
        _uiState.update {
            it.copy(
                loadingWeek = true,
                expandedWeek = true,
                loadingWeeks = it.loadingWeeks + wk,
                expandedWeeks = it.expandedWeeks + wk
            )
        }
        val lines = mealDescriptions ?: wkEntries.map { it.recognizedMeal }
        val promptBundle = prompts ?: FazitPromptBundle("", "", "", "")
        viewModelScope.launch {
            val result = fazitManager.computeWeekFazit(
                weekKey = wk,
                entriesOfWeek = wkEntries,
                settings = settings,
                promptContext = promptContext,
                mealDescriptions = lines,
                prompts = promptBundle
            )
            result.onSuccess { wf ->
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

    suspend fun decodeThumbnail(entry: HistoryEntry, target: Int = 128): android.graphics.Bitmap? =
        withContext(dispatchers.io) {
            historyRepository.decodeThumbnail(entry, target)
        }

    private var autoFazitJob: Job? = null

    fun triggerAutoFazits() {
        autoFazitJob?.cancel()
        autoFazitJob = viewModelScope.launch {
            delay(1200) // Debounce
            val entries = _uiState.value.entries
            if (entries.isEmpty()) return@launch

            val historyContext = AutoFazitHistoryContext(
                entries = entries,
                fazitMap = _uiState.value.fazitMap,
                loadingFazits = _uiState.value.loadingFazits,
                weekFazitMap = _uiState.value.weekFazitMap,
                loadingWeeks = _uiState.value.loadingWeeks
            )

            val callbacks = AutoFazitCallbacks(
                onGenerateDayFazit = { day, ratedEntries, settings, promptContext, mealDescriptions, prompts ->
                    generateFazit(day, ratedEntries, settings, promptContext, mealDescriptions, prompts)
                },
                onGenerateWeekFazit = { settings, promptContext, weekKey, mealDescriptions, prompts ->
                    generateWeekFazit(settings, promptContext, weekKey, mealDescriptions, prompts)
                }
            )

            autoFazitUseCase.checkAndTriggerAutoFazits(
                historyContext = historyContext,
                callbacks = callbacks
            )
        }
    }

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

            val historyContext = AutoFazitHistoryContext(
                entries = entries,
                fazitMap = _uiState.value.fazitMap,
                loadingFazits = _uiState.value.loadingFazits,
                weekFazitMap = _uiState.value.weekFazitMap,
                loadingWeeks = _uiState.value.loadingWeeks
            )

            val promptConfig = AutoFazitPromptConfig(
                settings = settings,
                promptContext = promptContext,
                dayPrompts = dayPrompts,
                weekPromptsProvider = weekPromptsProvider,
                daymealDescriptionsProvider = daymealDescriptionsProvider,
                weekmealDescriptionsProvider = weekmealDescriptionsProvider
            )

            val callbacks = AutoFazitCallbacks(
                onGenerateDayFazit = { day, ratedEntries, set, ctx, descriptions, prompts ->
                    generateFazit(day, ratedEntries, set, ctx, descriptions, prompts)
                },
                onGenerateWeekFazit = { set, ctx, weekKey, descriptions, prompts ->
                    generateWeekFazit(set, ctx, weekKey, descriptions, prompts)
                }
            )

            autoFazitUseCase.checkAndTriggerAutoFazits(
                historyContext = historyContext,
                promptConfig = promptConfig,
                callbacks = callbacks
            )
        }
    }
}
