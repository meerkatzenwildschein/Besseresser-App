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
    private val apiService: BesseresserApiService,
    private val historyRepository: HistoryRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var analyzeJob: Job? = null

    fun loadRecentNotes() {
        _uiState.update { it.copy(recentNotes = settingsRepository.loadRecentNotes()) }
    }

    fun toggleStarNote(noteText: String) {
        settingsRepository.toggleStarRecentNote(noteText)
        _uiState.update {
            it.copy(recentNotes = settingsRepository.loadRecentNotes(sortStarredFirst = false))
        }
    }

    /** MRU-Bump einer Anmerkung (ohne KI-Ergebnis), z. B. beim Start einer Analyse. */
    fun pushRecentNote(note: String) {
        if (note.isBlank()) return
        settingsRepository.pushRecentNote(note)
        _uiState.update { it.copy(recentNotes = settingsRepository.loadRecentNotes()) }
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

    /** Prüft, ob für die aktuelle Anmerkung bereits eine gültige KI-Bewertung im Menü-Speicher
     *  vorliegt, die zu [settings.goals] passt. Falls ja, wird sie direkt übernommen (ohne KI-Aufruf). */
    fun tryUseCachedEvaluation(settings: Settings): Boolean {
        val currentNote = _uiState.value.note.trim()
        val cachedEv = settingsRepository.findCachedEvaluation(currentNote, settings.goals) ?: return false
        analyzeJob?.cancel()
        settingsRepository.pushRecentNote(currentNote, cachedEv)
        val updatedNotes = settingsRepository.loadRecentNotes()
        _uiState.update {
            it.copy(
                evaluation = cachedEv,
                isBusy = false,
                error = null,
                recentNotes = updatedNotes
            )
        }
        return true
    }

    fun analyze(ctx: Context, settings: Settings, promptBundle: PromptBundle) {
        val uri = _uiState.value.photoPreviewUri ?: return
        if (tryUseCachedEvaluation(settings)) return
        val currentNote = _uiState.value.note.trim()
        analyzeJob?.cancel()
        analyzeJob = viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null) }
            val res = apiService.analyze(ctx, uri, settings, promptBundle)
            res.onSuccess { ev ->
                if (currentNote.isNotBlank()) {
                    settingsRepository.pushRecentNote(currentNote, ev)
                }
                val updatedNotes = settingsRepository.loadRecentNotes()
                _uiState.update { it.copy(evaluation = ev, isBusy = false, recentNotes = updatedNotes) }
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
            historyRepository.deleteDayFazitForEntry(entry)
            historyRepository.deleteWeekFazitForEntry(entry)
            settingsRepository.pushRecentNote(note, ev)
            val updatedNotes = settingsRepository.loadRecentNotes()
            _uiState.update {
                it.copy(
                    savingHistory = false,
                    savedToHistory = true,
                    savedEntryId = entry.id,
                    recentNotes = updatedNotes
                )
            }
            onSaved(entry)
        }
    }

    fun updateErkannt(newText: String) {
        val trimmed = newText.trim()
        val entryId = _uiState.value.savedEntryId
        val currentNote = _uiState.value.note.trim()
        var updatedEv: Evaluation? = null
        _uiState.update { state ->
            updatedEv = state.evaluation?.copy(erkannt = trimmed)
            state.copy(evaluation = updatedEv, showErkanntEdit = false)
        }
        val evToSave = updatedEv
        if (evToSave != null && currentNote.isNotBlank()) {
            settingsRepository.updateRecentNoteEvaluation(currentNote, evToSave)
            _uiState.update { it.copy(recentNotes = settingsRepository.loadRecentNotes()) }
        }
        if (entryId != null) {
            viewModelScope.launch {
                historyRepository.updateErkannt(entryId, trimmed)
            }
        }
    }

    fun reEvaluate(ctx: Context, settings: Settings, promptBundle: PromptBundle, newText: String) {
        val uri = _uiState.value.photoPreviewUri ?: return
        val currentNote = _uiState.value.note.trim()
        analyzeJob?.cancel()
        analyzeJob = viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null) }
            val res = apiService.analyze(ctx, uri, settings, promptBundle)
            res.onSuccess { ev ->
                val merged = ev.copy(erkannt = newText.trim())
                if (currentNote.isNotBlank()) {
                    settingsRepository.updateRecentNoteEvaluation(currentNote, merged)
                }
                val updatedNotes = settingsRepository.loadRecentNotes()
                _uiState.update { it.copy(evaluation = merged, isBusy = false, recentNotes = updatedNotes) }
                val entryId = _uiState.value.savedEntryId
                if (entryId != null) {
                    val dummyEntry = HistoryEntry(id = entryId, dateMillis = System.currentTimeMillis())
                    historyRepository.updateEvaluation(entryId, merged.goalResults, merged.summary)
                    historyRepository.deleteDayFazitForEntry(dummyEntry)
                    historyRepository.deleteWeekFazitForEntry(dummyEntry)
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
