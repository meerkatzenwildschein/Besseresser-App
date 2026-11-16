package de.gurum.besseresser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** App-weiter Zustand, der aus den Repositories als Flows gespeist wird:
 *  Einstellungen, Profil, Theme und Sprache. `loaded` wird true, sobald die
 *  erste Emission aller Quellen vorliegt (DataStore ist initial gelesen). */
data class AppState(
    val loaded: Boolean = false,
    val settings: Settings = Settings(),
    val profile: Profile = Profile(),
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.DE
)

/** Zentrales ViewModel für den App-Rahmen (MainActivity): hält Settings/Profil/Theme/Sprache
 *  reaktiv synchron – kein manuelles Nachladen nach Setup oder Backup-Import mehr nötig. */
class AppStateViewModel(
    private val settingsRepository: SettingsRepository,
    private val profileRepository: ProfileRepository,
    private val uiPrefsRepository: UiPrefsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppState())
    val uiState: StateFlow<AppState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                settingsRepository.settings,
                profileRepository.profile,
                uiPrefsRepository.themeMode,
                uiPrefsRepository.language
            ) { settings, profile, themeMode, language ->
                AppState(loaded = true, settings = settings, profile = profile, themeMode = themeMode, language = language)
            }.collect { _uiState.value = it }
        }
    }

    fun saveSettings(settings: Settings) {
        viewModelScope.launch { settingsRepository.save(settings) }
    }

    fun saveProfile(profile: Profile) {
        viewModelScope.launch { profileRepository.save(profile) }
    }

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch { uiPrefsRepository.saveThemeMode(mode) }
    }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch { uiPrefsRepository.saveLanguage(language) }
    }
}
