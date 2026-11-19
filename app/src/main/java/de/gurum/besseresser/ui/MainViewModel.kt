package de.gurum.besseresser.ui
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import de.gurum.besseresser.data.AppDispatchers
import de.gurum.besseresser.data.BesseresserApiService
import de.gurum.besseresser.data.ImagePreprocessor
import de.gurum.besseresser.data.PhotoCaptureManager
import de.gurum.besseresser.model.HistoryEntry
import de.gurum.besseresser.data.HistoryRepository
import de.gurum.besseresser.data.PromptBundle
import de.gurum.besseresser.model.Settings
import de.gurum.besseresser.data.SettingsRepository
import de.gurum.besseresser.model.Evaluation
import java.io.File

data class MainUiState(
    val evaluation: Evaluation? = null,
    val isBusy: Boolean = false,
    val error: String? = null,
    val photoPreviewUri: Uri? = null,
    val savedToHistory: Boolean = false,
    val savingHistory: Boolean = false,
    val note: String = "",
    val showReview: Boolean = false,
    val showCamera: Boolean = false,
    val showRecognizedEdit: Boolean = false,
    val savedEntryId: Long? = null,
    val menuOpen: Boolean = false
)

class MainViewModel(
    private val apiService: BesseresserApiService,
    private val historyRepository: HistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val imagePreprocessor: ImagePreprocessor,
    private val dispatchers: AppDispatchers = AppDispatchers(),
    private val photoCaptureManager: PhotoCaptureManager? = null,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
) : ViewModel() {

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
        private const val KEY_NOTE = "main_note"
        private const val KEY_PHOTO_URI = "main_photo_uri"
        private const val KEY_SHOW_REVIEW = "main_show_review"
        private const val KEY_SHOW_CAMERA = "main_show_camera"
        private const val KEY_SHOW_RECOGNIZED_EDIT = "main_show_recognized_edit"
        private const val KEY_SAVED_ENTRY_ID = "main_saved_entry_id"
        private const val KEY_SAVED_TO_HISTORY = "main_saved_to_history"
        private const val KEY_MENU_OPEN = "main_menu_open"
    }

    private val _uiState = MutableStateFlow(
        MainUiState(
            note = savedStateHandle[KEY_NOTE] ?: "",
            photoPreviewUri = savedStateHandle.get<String>(KEY_PHOTO_URI)?.let { Uri.parse(it) },
            showReview = savedStateHandle[KEY_SHOW_REVIEW] ?: false,
            showCamera = savedStateHandle[KEY_SHOW_CAMERA] ?: false,
            showRecognizedEdit = savedStateHandle[KEY_SHOW_RECOGNIZED_EDIT] ?: false,
            savedToHistory = savedStateHandle[KEY_SAVED_TO_HISTORY] ?: false,
            savedEntryId = savedStateHandle[KEY_SAVED_ENTRY_ID],
            menuOpen = savedStateHandle[KEY_MENU_OPEN] ?: false
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var analyzeJob: Job? = null

    /** MRU-Bump einer Anmerkung (ohne KI-Ergebnis), z. B. beim Start einer Analyse. */
    fun pushRecentNote(note: String) {
        if (note.isBlank()) return
        viewModelScope.launch { settingsRepository.pushRecentNote(note) }
    }

    fun setNote(note: String) {
        savedStateHandle[KEY_NOTE] = note
        _uiState.update { it.copy(note = note) }
    }

    fun setMenuOpen(open: Boolean) {
        savedStateHandle[KEY_MENU_OPEN] = open
        _uiState.update { it.copy(menuOpen = open) }
    }

    fun setShowCamera(show: Boolean) {
        savedStateHandle[KEY_SHOW_CAMERA] = show
        _uiState.update { it.copy(showCamera = show) }
    }

    fun setShowReview(show: Boolean) {
        savedStateHandle[KEY_SHOW_REVIEW] = show
        _uiState.update { it.copy(showReview = show) }
    }

    fun setshowRecognizedEdit(show: Boolean) {
        savedStateHandle[KEY_SHOW_RECOGNIZED_EDIT] = show
        _uiState.update { it.copy(showRecognizedEdit = show) }
    }

    fun onPhotoTaken(uri: Uri) {
        analyzeJob?.cancel()
        savedStateHandle[KEY_PHOTO_URI] = uri.toString()
        savedStateHandle[KEY_SHOW_CAMERA] = false
        savedStateHandle[KEY_SHOW_REVIEW] = false
        savedStateHandle[KEY_SAVED_TO_HISTORY] = false
        savedStateHandle.remove<Long>(KEY_SAVED_ENTRY_ID)
        _uiState.update {
            it.copy(
                photoPreviewUri = uri,
                showCamera = false,
                showReview = false,
                isBusy = true,
                error = null,
                evaluation = null,
                savedToHistory = false,
                savedEntryId = null
            )
        }
    }

    fun onSystemCameraResult(ok: Boolean, photoUri: Uri) {
        if (ok) {
            savedStateHandle[KEY_SHOW_REVIEW] = true
            _uiState.update { it.copy(error = null, showReview = true) }
        }
    }

    /** Prüft, ob für die aktuelle Anmerkung bereits eine gültige KI-Bewertung im Menü-Speicher
     *  vorliegt, die zu [settings.goals] passt. Falls ja, wird sie direkt übernommen (ohne KI-Aufruf). */
    suspend fun tryUseCachedEvaluation(settings: Settings): Boolean {
        val currentNote = _uiState.value.note.trim()
        val cachedEv = settingsRepository.findCachedEvaluation(currentNote, settings.goals) ?: return false
        analyzeJob?.cancel()
        settingsRepository.pushRecentNote(currentNote, cachedEv)
        _uiState.update {
            it.copy(
                evaluation = cachedEv,
                isBusy = false,
                error = null
            )
        }
        return true
    }

    fun analyze(settings: Settings, promptBundle: PromptBundle) {
        val uri = _uiState.value.photoPreviewUri ?: return
        val currentNote = _uiState.value.note.trim()
        analyzeJob?.cancel()
        analyzeJob = viewModelScope.launch {
            if (tryUseCachedEvaluation(settings)) return@launch
            _uiState.update { it.copy(isBusy = true, error = null) }
            val dataUri = runCatching {
                withContext(dispatchers.io) { imagePreprocessor.prepareImageDataUri(uri) }
            }.getOrElse { e ->
                _uiState.update { it.copy(error = e.message ?: "Foto konnte nicht gelesen werden.", isBusy = false) }
                return@launch
            }
            val res = apiService.analyze(dataUri, settings, promptBundle)
            res.onSuccess { ev ->
                if (currentNote.isNotBlank()) {
                    settingsRepository.pushRecentNote(currentNote, ev)
                }
                _uiState.update { it.copy(evaluation = ev, isBusy = false) }
            }.onFailure { e ->
                _uiState.update { it.copy(error = e.message ?: "Analyse fehlgeschlagen.", isBusy = false) }
            }
        }
    }

    fun cancelAnalyze() {
        analyzeJob?.cancel()
        _uiState.update { it.copy(isBusy = false) }
    }

    fun saveToHistory(onSaved: (HistoryEntry) -> Unit = {}) {
        val ev = _uiState.value.evaluation ?: return
        val uri = _uiState.value.photoPreviewUri
        val note = _uiState.value.note
        viewModelScope.launch {
            _uiState.update { it.copy(savingHistory = true) }
            val entry = historyRepository.add(uri, ev, note = note)
            uri?.let { photoCaptureManager?.deleteCaptureFile(it) }
            historyRepository.deleteDayFazitForEntry(entry)
            historyRepository.deleteWeekFazitForEntry(entry)
            settingsRepository.pushRecentNote(note, ev)
            savedStateHandle[KEY_SAVED_TO_HISTORY] = true
            savedStateHandle[KEY_SAVED_ENTRY_ID] = entry.id
            _uiState.update {
                it.copy(
                    savingHistory = false,
                    savedToHistory = true,
                    savedEntryId = entry.id
                )
            }
            onSaved(entry)
        }
    }

    fun updateRecognizedMeal(newText: String) {
        val trimmed = newText.trim()
        val entryId = _uiState.value.savedEntryId
        val currentNote = _uiState.value.note.trim()
        var updatedEv: Evaluation? = null
        savedStateHandle[KEY_SHOW_RECOGNIZED_EDIT] = false
        _uiState.update { state ->
            updatedEv = state.evaluation?.copy(recognizedMeal = trimmed)
            state.copy(evaluation = updatedEv, showRecognizedEdit = false)
        }
        val evToSave = updatedEv
        if (evToSave != null && currentNote.isNotBlank()) {
            viewModelScope.launch {
                settingsRepository.updateRecentNoteEvaluation(currentNote, evToSave)
            }
        }
        if (entryId != null) {
            viewModelScope.launch {
                historyRepository.updateRecognizedMeal(entryId, trimmed)
            }
        }
    }

    fun reset() {
        analyzeJob?.cancel()
        val currentUri = savedStateHandle.get<String>(KEY_PHOTO_URI)?.let { Uri.parse(it) }
            ?: _uiState.value.photoPreviewUri
        val wasSaved = savedStateHandle.get<Boolean>(KEY_SAVED_TO_HISTORY) ?: _uiState.value.savedToHistory
        if (!wasSaved && currentUri != null) {
            photoCaptureManager?.deleteCaptureFile(currentUri)
        }
        savedStateHandle.remove<String>(KEY_NOTE)
        savedStateHandle.remove<String>(KEY_PHOTO_URI)
        savedStateHandle.remove<Boolean>(KEY_SHOW_REVIEW)
        savedStateHandle.remove<Boolean>(KEY_SHOW_CAMERA)
        savedStateHandle.remove<Boolean>(KEY_SHOW_RECOGNIZED_EDIT)
        savedStateHandle.remove<Boolean>(KEY_SAVED_TO_HISTORY)
        savedStateHandle.remove<Long>(KEY_SAVED_ENTRY_ID)
        savedStateHandle.remove<Boolean>(KEY_MENU_OPEN)
        _uiState.update { MainUiState() }
    }
}
