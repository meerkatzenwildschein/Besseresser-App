package de.gurum.besseresser.ui
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import de.gurum.besseresser.data.AppDispatchers
import de.gurum.besseresser.data.HistoryRepository
import de.gurum.besseresser.model.AppLanguage
import de.gurum.besseresser.model.AppThemeMode
import de.gurum.besseresser.data.ProfileRepository
import de.gurum.besseresser.data.SettingsRepository
import de.gurum.besseresser.data.UiPrefsRepository
import de.gurum.besseresser.model.Profile
import de.gurum.besseresser.model.Settings

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
    private val uiPrefsRepository: UiPrefsRepository,
    private val historyRepository: HistoryRepository,
    private val dispatchers: AppDispatchers = AppDispatchers()
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
        // Historien-Begrenzung: einmalig beim App-Start im Hintergrund alte Einträge löschen
        // (Einträge inkl. Fotos sowie Tages-/Wochenfazits, die älter als retentionWeeks sind).
        viewModelScope.launch(dispatchers.io) {
            runCatching {
                val weeks = settingsRepository.currentSettings().retentionWeeks
                if (weeks > 0) historyRepository.purgeOlderThanWeeks(weeks)
            }
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
