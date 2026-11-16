package de.gurum.besseresser.ui
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import de.gurum.besseresser.R
import de.gurum.besseresser.data.ApiException
import de.gurum.besseresser.data.BackupService
import de.gurum.besseresser.data.BackupSummary
import de.gurum.besseresser.data.BesseresserApiService
import de.gurum.besseresser.data.SettingsRepository
import de.gurum.besseresser.model.Settings
import de.gurum.besseresser.data.SplitGoalsRequest

data class SetupUiState(
    val url: String = "",
    val key: String = "",
    val model: String = "gpt-4o-mini",
    val goals: String = "",
    val goalsDirty: Boolean = false,
    val showModels: Boolean = false,
    val showProviders: Boolean = false,
    val splitting: Boolean = false,
    val showSplit: Boolean = false,
    val splitEdited: String = "",
    val splitRaw: String = "",
    val splitDebug: String? = null,
    val retentionWeeks: String = Settings.DEFAULT_RETENTION_WEEKS.toString()
)

class SetupViewModel(
    private val apiService: BesseresserApiService,
    private val backupService: BackupService,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    fun init(initial: Settings, defaultGoals: List<String>) {
        _uiState.update {
            it.copy(
                url = initial.baseUrl,
                key = initial.apiKey,
                model = initial.model.ifBlank { "gpt-4o-mini" },
                goals = initial.goals.joinToString("\n").ifBlank { defaultGoals.joinToString("\n") },
                retentionWeeks = initial.retentionWeeks.toString()
            )
        }
    }

    fun setUrl(url: String) {
        _uiState.update { it.copy(url = url) }
    }

    fun setKey(key: String) {
        _uiState.update { it.copy(key = key) }
    }

    fun setModel(model: String) {
        _uiState.update { it.copy(model = model) }
    }

    fun setGoals(goals: String) {
        _uiState.update { it.copy(goals = goals, goalsDirty = true) }
    }

    fun setShowModels(show: Boolean) {
        _uiState.update { it.copy(showModels = show) }
    }

    fun setShowProviders(show: Boolean) {
        _uiState.update { it.copy(showProviders = show) }
    }

    fun setShowSplit(show: Boolean) {
        _uiState.update { it.copy(showSplit = show) }
    }

    fun setSplitEdited(text: String) {
        _uiState.update { it.copy(splitEdited = text) }
    }

    fun setRetentionWeeks(text: String) {
        _uiState.update { it.copy(retentionWeeks = text) }
    }

    fun importBackup(uri: Uri, defaultGoals: List<String>, onResult: (Result<Pair<Settings, BackupSummary>>) -> Unit) {
        viewModelScope.launch {
            val res = backupService.importFromUri(uri)
            res.onSuccess { summary ->
                val updated = settingsRepository.currentSettings()
                init(updated, defaultGoals)
                onResult(Result.success(updated to summary))
            }.onFailure { e ->
                onResult(Result.failure(e))
            }
        }
    }

    fun startSplitGoals(
        ctx: Context? = null,
        rawGoals: String,
        splitRequest: SplitGoalsRequest? = null,
        onDone: (Settings) -> Unit = {}
    ) {
        val state = _uiState.value
        _uiState.update { it.copy(splitting = true) }
        val req = splitRequest ?: SplitGoalsRequest(
            baseUrl = state.url.trim(),
            apiKey = state.key.trim(),
            model = state.model.trim(),
            rawGoals = rawGoals,
            splitIntro = ctx?.getString(R.string.prompt_split_intro) ?: "Split the following free text containing health goals into individual goals.",
            splitRules = ctx?.getString(R.string.prompt_split_rules) ?: "Rules: one goal per item, concise (max. 8 words), no numbering."
        )
        viewModelScope.launch {
            val result = apiService.splitGoals(
                request = req
            )
            result.onSuccess { splitList ->
                _uiState.update {
                    it.copy(
                        splitting = false,
                        splitEdited = splitList.joinToString("\n"),
                        splitRaw = rawGoals,
                        splitDebug = null,
                        showSplit = true
                    )
                }
            }.onFailure { err ->
                val debug = if (err is ApiException) err.debugReport else null
                _uiState.update {
                    it.copy(
                        splitting = false,
                        splitEdited = rawGoals,
                        splitRaw = rawGoals,
                        splitDebug = debug,
                        showSplit = true
                    )
                }
            }
        }
    }
}
