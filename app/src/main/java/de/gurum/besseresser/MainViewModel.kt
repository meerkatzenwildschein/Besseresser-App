package de.gurum.besseresser

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val showErkanntEdit: Boolean = false,
    val savedEntryId: Long? = null,
    val menuOpen: Boolean = false,
    val recentNotes: List<RecentNote> = emptyList()
)

class MainViewModel(
    private val apiService: BesseresserApiService = ApiClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var analyzeJob: Job? = null

    fun loadRecentNotes(ctx: Context) {
        _uiState.update { it.copy(recentNotes = RecentNotesStore.load(ctx)) }
    }

    fun toggleStarNote(ctx: Context, noteText: String) {
        RecentNotesStore.toggleStar(ctx, noteText)
        _uiState.update { it.copy(recentNotes = RecentNotesStore.load(ctx, sortStarredFirst = false)) }
    }

    fun setNote(note: String) {
        _uiState.update { it.copy(note = note) }
    }

    fun setMenuOpen(open: Boolean) {
        _uiState.update { it.copy(menuOpen = open) }
    }

    fun setShowCamera(show: Boolean) {
        _uiState.update { it.copy(showCamera = show) }
    }

    fun setShowReview(show: Boolean) {
        _uiState.update { it.copy(showReview = show) }
    }

    fun setShowErkanntEdit(show: Boolean) {
        _uiState.update { it.copy(showErkanntEdit = show) }
    }

    fun onPhotoTaken(uri: Uri) {
        analyzeJob?.cancel()
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
            _uiState.update { it.copy(error = null, showReview = true) }
        }
    }

    fun analyze(ctx: Context, settings: Settings, promptBundle: PromptBundle) {
        val uri = _uiState.value.photoPreviewUri ?: return
        analyzeJob?.cancel()
        analyzeJob = viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null) }
            val res = apiService.analyze(ctx, uri, settings, promptBundle)
            res.onSuccess { ev ->
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

    fun saveToHistory(ctx: Context, settings: Settings, onSaved: (HistoryEntry) -> Unit = {}) {
        val ev = _uiState.value.evaluation ?: return
        val uri = _uiState.value.photoPreviewUri
        val note = _uiState.value.note
        viewModelScope.launch {
            _uiState.update { it.copy(savingHistory = true) }
            val entry = HistoryStore.add(ctx, uri, ev, note = note)
            DayFazitStore.deleteForEntry(ctx, entry)
            WeekFazitStore.deleteForEntry(ctx, entry)
            RecentNotesStore.push(ctx, note)
            _uiState.update {
                it.copy(
                    savingHistory = false,
                    savedToHistory = true,
                    savedEntryId = entry.id,
                    recentNotes = RecentNotesStore.load(ctx)
                )
            }
            onSaved(entry)
        }
    }

    fun updateErkannt(ctx: Context, newText: String) {
        val entryId = _uiState.value.savedEntryId
        _uiState.update { state ->
            val updatedEv = state.evaluation?.copy(erkannt = newText.trim())
            state.copy(evaluation = updatedEv, showErkanntEdit = false)
        }
        if (entryId != null) {
            viewModelScope.launch {
                HistoryStore.updateErkannt(ctx, entryId, newText.trim())
            }
        }
    }

    fun reEvaluate(ctx: Context, settings: Settings, promptBundle: PromptBundle, newText: String) {
        val uri = _uiState.value.photoPreviewUri ?: return
        analyzeJob?.cancel()
        analyzeJob = viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null) }
            val res = apiService.analyze(ctx, uri, settings, promptBundle)
            res.onSuccess { ev ->
                val merged = ev.copy(erkannt = newText.trim())
                _uiState.update { it.copy(evaluation = merged, isBusy = false) }
                val entryId = _uiState.value.savedEntryId
                if (entryId != null) {
                    HistoryStore.updateEvaluation(ctx, entryId, merged.goalResults, merged.summary)
                    DayFazitStore.deleteForEntry(ctx, HistoryEntry(id = entryId, dateMillis = System.currentTimeMillis()))
                    WeekFazitStore.deleteForEntry(ctx, HistoryEntry(id = entryId, dateMillis = System.currentTimeMillis()))
                }
            }.onFailure { e ->
                _uiState.update { it.copy(error = e.message ?: "Re-Evaluation fehlgeschlagen.", isBusy = false) }
            }
        }
    }

    fun reset() {
        analyzeJob?.cancel()
        _uiState.update {
            MainUiState(
                recentNotes = it.recentNotes
            )
        }
    }
}
