package de.gurum.besseresser.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import de.gurum.besseresser.data.AppDispatchers
import de.gurum.besseresser.data.BesseresserApiService
import de.gurum.besseresser.data.HistoryRepository
import de.gurum.besseresser.data.ImagePreprocessor
import de.gurum.besseresser.data.PromptBundle
import de.gurum.besseresser.data.SettingsRepository
import de.gurum.besseresser.model.Evaluation
import de.gurum.besseresser.model.HistoryEntry
import de.gurum.besseresser.model.Settings

/** ViewModel für die Detailansicht eines Verlaufseintrags: hält den Eintrag reaktiv
 *  synchron und kapselt Re-Evaluation, recognizedMeal-Korrektur, Löschen und Duplizieren. */
class HistoryDetailViewModel(
    private val entryId: Long,
    val historyRepository: HistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val apiService: BesseresserApiService,
    private val imagePreprocessor: ImagePreprocessor,
    private val dispatchers: AppDispatchers = AppDispatchers()
) : ViewModel() {

    data class DetailUiState(
        val entry: HistoryEntry? = null,
        val loaded: Boolean = false,
        val reEvalBusy: Boolean = false,
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            historyRepository.getAllFlow().collect { list ->
                _uiState.update { it.copy(
                    entry = list.find { e -> e.id == entryId } ?: it.entry,
                    loaded = true
                ) }
            }
        }
    }

    /** Foto-Datei des aktuellen Eintrags (für Anzeige/Teilen; null wenn keins vorhanden). */
    fun photoFile(): File? = _uiState.value.entry?.let { historyRepository.photoFileOf(it) }

    fun thumbnail(target: Int = 1280): android.graphics.Bitmap? =
        _uiState.value.entry?.let { historyRepository.decodeThumbnail(it, target) }

    /** recognizedMeal-Text korrigieren (persistiert; Fazit wird über Fingerprint veraltet). */
    fun updateErkannt(newText: String) {
        val entry = _uiState.value.entry ?: return
        val trimmed = newText.trim()
        viewModelScope.launch {
            historyRepository.updateErkannt(entry.id, trimmed)
            if (entry.note.isNotBlank()) {
                settingsRepository.updateRecentNoteEvaluation(
                    entry.note,
                    Evaluation(
                        goalResults = entry.goalResults,
                        summary = entry.summary,
                        recognizedMeal = trimmed
                    )
                )
            }
        }
    }

    /** Re-Bewertung nach recognizedMeal-Korrektur: Mit Foto wenn vorhanden, sonst text-only.
     *  Prompts werden von der UI geliefert. Ziele + Gesamteindruck werden 1:1 ersetzt. */
    fun reEvaluate(
        correctedText: String,
        settings: Settings,
        photoPromptBundle: PromptBundle?,
        textPrompt: String?
    ) {
        val entry = _uiState.value.entry ?: return
        _uiState.update { it.copy(reEvalBusy = true, error = null) }
        viewModelScope.launch {
            val photoFile = historyRepository.photoFileOf(entry)
            // Acht-Pitfall: KEIN FileProvider für gespeicherte Fotos! Dessen file_paths.xml mappt
            // nur cache-path/photos – getUriForFile auf filesDir/history wirft IllegalArgumentException.
            // imagePreprocessor liest intern via openInputStream: file://-Uri reicht.
            val result: Result<Evaluation> = if (photoFile != null && photoPromptBundle != null) {
                val dataUri = runCatching {
                    withContext(dispatchers.io) { imagePreprocessor.prepareImageDataUri(Uri.fromFile(photoFile)) }
                }.getOrElse { e ->
                    _uiState.update { it.copy(reEvalBusy = false, error = e.message) }
                    return@launch
                }
                apiService.analyze(dataUri, settings, photoPromptBundle)
            } else if (textPrompt != null) {
                apiService.analyzeText(textPrompt, settings)
            } else {
                _uiState.update { it.copy(reEvalBusy = false) }
                return@launch
            }
            result.onSuccess { ev ->
                historyRepository.updateEvaluation(entry.id, ev.goalResults, ev.summary)
                if (entry.note.isNotBlank()) {
                    settingsRepository.updateRecentNoteEvaluation(
                        entry.note,
                        Evaluation(goalResults = ev.goalResults, summary = ev.summary, recognizedMeal = entry.recognizedMeal)
                    )
                }
                historyRepository.deleteDayFazitForEntry(entry)
                historyRepository.deleteWeekFazitForEntry(entry)
                _uiState.update { it.copy(reEvalBusy = false) }
            }.onFailure { e ->
                _uiState.update { it.copy(reEvalBusy = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun deleteEntry(onDeleted: () -> Unit) {
        val entry = _uiState.value.entry ?: return
        viewModelScope.launch {
            historyRepository.delete(entry)
            onDeleted()
        }
    }

    fun duplicateToDate(targetDay: LocalDate, targetTime: LocalTime, onDuplicated: () -> Unit) {
        val entry = _uiState.value.entry ?: return
        viewModelScope.launch {
            historyRepository.duplicateToDate(entry, targetDay, targetTime)
            onDuplicated()
        }
    }
}
