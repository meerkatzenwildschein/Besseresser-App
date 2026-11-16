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
    private val apiService: BesseresserApiService = ApiClient,
    private val historyRepository: HistoryRepository? = null,
    private val settingsRepository: SettingsRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var analyzeJob: Job? = null

    private fun getHistoryRepo(ctx: Context?): HistoryRepository? =
        historyRepository ?: ctx?.let { RepositoryProvider.getHistoryRepository(it) }

    private fun getSettingsRepo(ctx: Context?): SettingsRepository? =
        settingsRepository ?: ctx?.let { RepositoryProvider.getSettingsRepository(it) }

    fun loadRecentNotes(ctx: Context? = null) {
        val repo = getSettingsRepo(ctx)
        if (repo != null) {
            _uiState.update { it.copy(recentNotes = repo.loadRecentNotes()) }
        }
    }

    fun toggleStarNote(ctx: Context? = null, noteText: String) {
        val repo = getSettingsRepo(ctx) ?: return
        repo.toggleStarRecentNote(noteText)
        _uiState.update { it.copy(recentNotes = repo.loadRecentNotes(sortStarredFirst = false)) }
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
    fun tryUseCachedEvaluation(ctx: Context? = null, settings: Settings): Boolean {
        val currentNote = _uiState.value.note.trim()
        val setRepo = getSettingsRepo(ctx) ?: return false
        val cachedEv = setRepo.findCachedEvaluation(currentNote, settings.goals) ?: return false
        analyzeJob?.cancel()
        setRepo.pushRecentNote(currentNote, cachedEv)
        val updatedNotes = setRepo.loadRecentNotes()
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
        if (tryUseCachedEvaluation(ctx, settings)) return
        val currentNote = _uiState.value.note.trim()
        val setRepo = getSettingsRepo(ctx)
        analyzeJob?.cancel()
        analyzeJob = viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null) }
            val res = apiService.analyze(ctx, uri, settings, promptBundle)
            res.onSuccess { ev ->
                if (currentNote.isNotBlank()) {
                    setRepo?.pushRecentNote(currentNote, ev)
                }
                val updatedNotes = setRepo?.loadRecentNotes() ?: _uiState.value.recentNotes
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

    fun saveToHistory(ctx: Context? = null, settings: Settings, onSaved: (HistoryEntry) -> Unit = {}) {
        val ev = _uiState.value.evaluation ?: return
        val uri = _uiState.value.photoPreviewUri
        val note = _uiState.value.note
        val histRepo = getHistoryRepo(ctx)
        val setRepo = getSettingsRepo(ctx)
        viewModelScope.launch {
            _uiState.update { it.copy(savingHistory = true) }
            val entry = histRepo?.add(uri, ev, note = note)
                ?: HistoryEntry(id = System.currentTimeMillis(), goalResults = ev.goalResults, summary = ev.summary, note = note)
            histRepo?.deleteDayFazitForEntry(entry)
            histRepo?.deleteWeekFazitForEntry(entry)
            setRepo?.pushRecentNote(note, ev)
            val updatedNotes = setRepo?.loadRecentNotes() ?: _uiState.value.recentNotes
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

    fun updateErkannt(ctx: Context? = null, newText: String) {
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
            val setRepo = getSettingsRepo(ctx)
            setRepo?.updateRecentNoteEvaluation(currentNote, evToSave)
            if (setRepo != null) {
                _uiState.update { it.copy(recentNotes = setRepo.loadRecentNotes()) }
            }
        }
        if (entryId != null) {
            val histRepo = getHistoryRepo(ctx)
            viewModelScope.launch {
                histRepo?.updateErkannt(entryId, trimmed)
            }
        }
    }

    fun reEvaluate(ctx: Context, settings: Settings, promptBundle: PromptBundle, newText: String) {
        val uri = _uiState.value.photoPreviewUri ?: return
        val currentNote = _uiState.value.note.trim()
        val setRepo = getSettingsRepo(ctx)
        analyzeJob?.cancel()
        analyzeJob = viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null) }
            val res = apiService.analyze(ctx, uri, settings, promptBundle)
            res.onSuccess { ev ->
                val merged = ev.copy(erkannt = newText.trim())
                if (currentNote.isNotBlank()) {
                    setRepo?.updateRecentNoteEvaluation(currentNote, merged)
                }
                val updatedNotes = setRepo?.loadRecentNotes() ?: _uiState.value.recentNotes
                _uiState.update { it.copy(evaluation = merged, isBusy = false, recentNotes = updatedNotes) }
                val entryId = _uiState.value.savedEntryId
                if (entryId != null) {
                    val histRepo = getHistoryRepo(ctx)
                    val dummyEntry = HistoryEntry(id = entryId, dateMillis = System.currentTimeMillis())
                    histRepo?.updateEvaluation(entryId, merged.goalResults, merged.summary)
                    histRepo?.deleteDayFazitForEntry(dummyEntry)
                    histRepo?.deleteWeekFazitForEntry(dummyEntry)
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
