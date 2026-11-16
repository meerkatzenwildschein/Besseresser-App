package de.gurum.besseresser.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import de.gurum.besseresser.data.BackupService
import de.gurum.besseresser.data.BackupSummary
import de.gurum.besseresser.data.HistoryRepository

/** ViewModel für den Konfigurations-Screen: JSON-Export/-Import und Verlauf-Löschen.
 *  Meldet Ergebnisse als State (UI formatiert die Meldungen). */
class ConfigViewModel(
    private val backupService: BackupService,
    private val historyRepository: HistoryRepository
) : ViewModel() {

    data class ConfigUiState(
        val busy: Boolean = false,
        val importSummary: BackupSummary? = null,
        val exportSummary: BackupSummary? = null,
        val cleared: Boolean = false,
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(ConfigUiState())
    val uiState: StateFlow<ConfigUiState> = _uiState.asStateFlow()

    fun exportBackup() {
        _uiState.update { it.copy(busy = true, error = null, importSummary = null, exportSummary = null, cleared = false) }
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { backupService.exportToDownloads() }
            res.onSuccess { summary ->
                _uiState.update { it.copy(busy = false, exportSummary = summary) }
            }.onFailure { e ->
                _uiState.update { it.copy(busy = false, error = e.message) }
            }
        }
    }

    fun importBackup(uri: Uri) {
        _uiState.update { it.copy(busy = true, error = null, importSummary = null, exportSummary = null, cleared = false) }
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { backupService.importFromUri(uri) }
            res.onSuccess { summary ->
                _uiState.update { it.copy(busy = false, importSummary = summary) }
            }.onFailure { e ->
                _uiState.update { it.copy(busy = false, error = e.message) }
            }
        }
    }

    fun clearHistory() {
        _uiState.update { it.copy(busy = true, error = null, importSummary = null, exportSummary = null, cleared = false) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { historyRepository.clearAll() }
            }.onSuccess {
                _uiState.update { it.copy(busy = false, cleared = true) }
            }.onFailure { e ->
                _uiState.update { it.copy(busy = false, error = e.message) }
            }
        }
    }
}
