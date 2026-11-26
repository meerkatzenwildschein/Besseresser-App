package de.gurum.besseresser.ui

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import de.gurum.besseresser.data.ApiClient
import de.gurum.besseresser.data.ApiException
import de.gurum.besseresser.data.BackupService
import de.gurum.besseresser.data.BackupSummary
import de.gurum.besseresser.data.BesseresserApiService
import de.gurum.besseresser.data.NetworkSecurity
import de.gurum.besseresser.data.SettingsRepository
import de.gurum.besseresser.data.UrlSecurityStatus
import de.gurum.besseresser.model.Settings
import de.gurum.besseresser.model.WeightedGoal
import de.gurum.besseresser.model.toWeightedGoals
import de.gurum.besseresser.data.SplitGoalsRequest

@Immutable
data class SetupUiState(
    val url: String = "",
    val key: String = "",
    val model: String = Settings.DEFAULT_MODEL,
    val goals: List<WeightedGoal> = emptyList(),
    val newGoalText: String = "",
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
    private val settingsRepository: SettingsRepository,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
) : ViewModel() {

    companion object {
        private const val KEY_SETUP_INITIALIZED = "setup_initialized"
        private const val KEY_URL = "setup_url"
        private const val KEY_KEY = "setup_key"
        private const val KEY_MODEL = "setup_model"
        private const val KEY_GOALS = "setup_goals"
        private const val KEY_GOALS_JSON = "setup_goals_json"
        private const val KEY_NEW_GOAL_TEXT = "setup_new_goal_text"
        private const val KEY_GOALS_DIRTY = "setup_goals_dirty"
        private const val KEY_SHOW_MODELS = "setup_show_models"
        private const val KEY_SHOW_PROVIDERS = "setup_show_providers"
        private const val KEY_SHOW_SPLIT = "setup_show_split"
        private const val KEY_SPLIT_EDITED = "setup_split_edited"
        private const val KEY_SPLIT_RAW = "setup_split_raw"
        private const val KEY_SPLIT_DEBUG = "setup_split_debug"
        private const val KEY_RETENTION_WEEKS = "setup_retention_weeks"

        private fun parseGoalsFromHandle(handle: SavedStateHandle): List<WeightedGoal> {
            val rawJson = handle.get<String>(KEY_GOALS_JSON)
            if (rawJson != null) {
                return SettingsRepository.decodeGoalsFromString(rawJson)
            }
            val list = handle.get<ArrayList<WeightedGoal>>(KEY_GOALS)
            if (list != null) return list.toList()
            val raw = handle.get<String>(KEY_GOALS)
            if (raw != null) return SettingsRepository.decodeGoalsFromString(raw)
            return emptyList()
        }
    }

    private val _uiState = MutableStateFlow(
        SetupUiState(
            url = savedStateHandle[KEY_URL] ?: "",
            key = savedStateHandle[KEY_KEY] ?: "",
            model = savedStateHandle[KEY_MODEL] ?: Settings.DEFAULT_MODEL,
            goals = parseGoalsFromHandle(savedStateHandle),
            newGoalText = savedStateHandle[KEY_NEW_GOAL_TEXT] ?: "",
            goalsDirty = savedStateHandle[KEY_GOALS_DIRTY] ?: false,
            showModels = savedStateHandle[KEY_SHOW_MODELS] ?: false,
            showProviders = savedStateHandle[KEY_SHOW_PROVIDERS] ?: false,
            showSplit = savedStateHandle[KEY_SHOW_SPLIT] ?: false,
            splitEdited = savedStateHandle[KEY_SPLIT_EDITED] ?: "",
            splitRaw = savedStateHandle[KEY_SPLIT_RAW] ?: "",
            splitDebug = savedStateHandle[KEY_SPLIT_DEBUG],
            retentionWeeks = savedStateHandle[KEY_RETENTION_WEEKS] ?: Settings.DEFAULT_RETENTION_WEEKS.toString()
        )
    )
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    val isUrlPermitted: Boolean
        get() = NetworkSecurity.isPermittedUrl(_uiState.value.url)

    val urlSecurityStatus: UrlSecurityStatus
        get() = NetworkSecurity.assessUrl(_uiState.value.url)

    fun init(initial: Settings, defaultGoals: List<String>, force: Boolean = false) {
        val alreadyInitialized = savedStateHandle.get<Boolean>(KEY_SETUP_INITIALIZED) ?: false
        if (alreadyInitialized && !force) return

        val url = initial.baseUrl
        val key = initial.apiKey
        val model = initial.model.ifBlank { Settings.DEFAULT_MODEL }
        val goals = if (initial.goals.isNotEmpty()) initial.goals else defaultGoals.toWeightedGoals()
        val retention = initial.retentionWeeks.toString()

        savedStateHandle[KEY_SETUP_INITIALIZED] = true
        savedStateHandle[KEY_URL] = url
        savedStateHandle[KEY_KEY] = key
        savedStateHandle[KEY_MODEL] = model
        savedStateHandle[KEY_GOALS_JSON] = SettingsRepository.encodeGoalsToString(goals)
        savedStateHandle[KEY_NEW_GOAL_TEXT] = ""
        savedStateHandle[KEY_GOALS_DIRTY] = false
        savedStateHandle[KEY_RETENTION_WEEKS] = retention

        _uiState.update {
            it.copy(
                url = url,
                key = key,
                model = model,
                goals = goals,
                newGoalText = "",
                goalsDirty = false,
                retentionWeeks = retention
            )
        }
    }

    fun setUrl(url: String) {
        savedStateHandle[KEY_SETUP_INITIALIZED] = true
        savedStateHandle[KEY_URL] = url
        _uiState.update { it.copy(url = url) }
    }

    fun setKey(key: String) {
        savedStateHandle[KEY_SETUP_INITIALIZED] = true
        savedStateHandle[KEY_KEY] = key
        _uiState.update { it.copy(key = key) }
    }

    fun setModel(model: String) {
        savedStateHandle[KEY_SETUP_INITIALIZED] = true
        savedStateHandle[KEY_MODEL] = model
        _uiState.update { it.copy(model = model) }
    }

    fun setGoals(goals: List<WeightedGoal>) {
        savedStateHandle[KEY_SETUP_INITIALIZED] = true
        savedStateHandle[KEY_GOALS_JSON] = SettingsRepository.encodeGoalsToString(goals)
        savedStateHandle[KEY_GOALS_DIRTY] = true
        _uiState.update { it.copy(goals = goals, goalsDirty = true) }
    }

    @JvmName("setGoalsStrings")
    fun setGoals(goals: List<String>) {
        setGoals(goals.toWeightedGoals())
    }

    fun setGoals(goalsText: String) {
        val list = goalsText.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        setGoals(list.toWeightedGoals())
    }

    fun setGoalWeight(index: Int, weight: Int) {
        val validWeight = weight.coerceIn(WeightedGoal.MIN_WEIGHT, WeightedGoal.MAX_WEIGHT)
        val current = _uiState.value.goals
        if (index in current.indices) {
            val updated = current.toMutableList()
            updated[index] = updated[index].copy(weight = validWeight)
            setGoals(updated)
        }
    }

    fun removeGoal(index: Int) {
        val current = _uiState.value.goals
        if (index in current.indices) {
            val updated = current.toMutableList().apply { removeAt(index) }
            setGoals(updated)
        }
    }

    fun moveGoalUp(index: Int) {
        val current = _uiState.value.goals
        if (index in 1 until current.size) {
            val updated = current.toMutableList()
            java.util.Collections.swap(updated, index, index - 1)
            setGoals(updated)
        }
    }

    fun moveGoalDown(index: Int) {
        val current = _uiState.value.goals
        if (index in 0 until current.size - 1) {
            val updated = current.toMutableList()
            java.util.Collections.swap(updated, index, index + 1)
            setGoals(updated)
        }
    }

    fun setNewGoalText(text: String) {
        savedStateHandle[KEY_NEW_GOAL_TEXT] = text
        _uiState.update { it.copy(newGoalText = text) }
    }

    fun setShowModels(show: Boolean) {
        savedStateHandle[KEY_SHOW_MODELS] = show
        _uiState.update { it.copy(showModels = show) }
    }

    fun setShowProviders(show: Boolean) {
        savedStateHandle[KEY_SHOW_PROVIDERS] = show
        _uiState.update { it.copy(showProviders = show) }
    }

    fun setShowSplit(show: Boolean) {
        savedStateHandle[KEY_SHOW_SPLIT] = show
        _uiState.update { it.copy(showSplit = show) }
    }

    fun setSplitEdited(text: String) {
        savedStateHandle[KEY_SPLIT_EDITED] = text
        _uiState.update { it.copy(splitEdited = text) }
    }

    fun setRetentionWeeks(text: String) {
        savedStateHandle[KEY_SETUP_INITIALIZED] = true
        savedStateHandle[KEY_RETENTION_WEEKS] = text
        _uiState.update { it.copy(retentionWeeks = text) }
    }

    fun importBackup(uri: Uri, defaultGoals: List<String>, onResult: (Result<Pair<Settings, BackupSummary>>) -> Unit) {
        viewModelScope.launch {
            val res = backupService.importFromUri(uri)
            res.onSuccess { summary ->
                val updated = settingsRepository.currentSettings()
                init(updated, defaultGoals, force = true)
                onResult(Result.success(updated to summary))
            }.onFailure { e ->
                onResult(Result.failure(e))
            }
        }
    }

    fun addGoalsFromText(
        splitIntro: String? = null,
        splitRules: String? = null,
        customRequest: SplitGoalsRequest? = null,
        onFinished: ((Result<List<String>>) -> Unit)? = null
    ) {
        val raw = _uiState.value.newGoalText.trim()
        if (raw.isBlank()) return

        _uiState.update { it.copy(splitting = true) }
        val state = _uiState.value
        val req = customRequest ?: SplitGoalsRequest(
            baseUrl = state.url.trim(),
            apiKey = state.key.trim(),
            model = state.model.trim(),
            rawGoals = raw,
            splitIntro = splitIntro ?: "Split the following free text containing health goals into individual goals.",
            splitRules = splitRules ?: "Rules: one goal per item, concise (max. 8 words), no numbering."
        )

        viewModelScope.launch {
            val result = apiService.splitGoals(request = req)
            result.onSuccess { splitList ->
                val newGoals = splitList.map { it.trim() }.filter { it.isNotBlank() }.map { WeightedGoal(it, WeightedGoal.DEFAULT_WEIGHT) }
                val combined = _uiState.value.goals + newGoals
                val edited = splitList.joinToString("\n")
                savedStateHandle[KEY_SETUP_INITIALIZED] = true
                savedStateHandle[KEY_GOALS_JSON] = SettingsRepository.encodeGoalsToString(combined)
                savedStateHandle[KEY_GOALS_DIRTY] = true
                savedStateHandle[KEY_NEW_GOAL_TEXT] = ""
                savedStateHandle[KEY_SPLIT_EDITED] = edited
                savedStateHandle[KEY_SPLIT_RAW] = raw
                savedStateHandle.remove<String>(KEY_SPLIT_DEBUG)
                savedStateHandle[KEY_SHOW_SPLIT] = false

                _uiState.update {
                    it.copy(
                        goals = combined,
                        goalsDirty = true,
                        newGoalText = "",
                        splitting = false,
                        splitEdited = edited,
                        splitRaw = raw,
                        splitDebug = null,
                        showSplit = false
                    )
                }
                onFinished?.invoke(Result.success(splitList))
            }.onFailure { err ->
                val fallbackGoals = ApiClient.splitGoalsLocally(raw).filter { it.isNotBlank() }.map { WeightedGoal(it, WeightedGoal.DEFAULT_WEIGHT) }
                val combined = if (fallbackGoals.isNotEmpty()) _uiState.value.goals + fallbackGoals else _uiState.value.goals + listOf(WeightedGoal(raw, WeightedGoal.DEFAULT_WEIGHT))
                val debug = if (err is ApiException) err.debugReport else null
                savedStateHandle[KEY_SETUP_INITIALIZED] = true
                savedStateHandle[KEY_GOALS_JSON] = SettingsRepository.encodeGoalsToString(combined)
                savedStateHandle[KEY_GOALS_DIRTY] = true
                savedStateHandle[KEY_NEW_GOAL_TEXT] = ""
                savedStateHandle[KEY_SPLIT_EDITED] = raw
                savedStateHandle[KEY_SPLIT_RAW] = raw
                if (debug != null) savedStateHandle[KEY_SPLIT_DEBUG] = debug else savedStateHandle.remove<String>(KEY_SPLIT_DEBUG)
                savedStateHandle[KEY_SHOW_SPLIT] = false

                _uiState.update {
                    it.copy(
                        goals = combined,
                        goalsDirty = true,
                        newGoalText = "",
                        splitting = false,
                        splitEdited = raw,
                        splitRaw = raw,
                        splitDebug = debug,
                        showSplit = false
                    )
                }
                onFinished?.invoke(Result.failure(err))
            }
        }
    }

    fun startSplitGoals(
        rawGoals: String,
        splitIntro: String? = null,
        splitRules: String? = null,
        splitRequest: SplitGoalsRequest? = null,
        onDone: (Settings) -> Unit = {}
    ) {
        setNewGoalText(rawGoals)
        addGoalsFromText(splitIntro, splitRules, splitRequest)
    }

    suspend fun fetchModels(baseUrl: String, apiKey: String): Result<ApiClient.ModelsResult> =
        apiService.fetchModels(baseUrl, apiKey)
}
