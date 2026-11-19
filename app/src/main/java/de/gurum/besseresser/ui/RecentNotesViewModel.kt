package de.gurum.besseresser.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.gurum.besseresser.data.SettingsRepository
import de.gurum.besseresser.model.RecentNote
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** ViewModel für die Notiz-Schnellauswahl (MRU + Favoriten-Sternchen).
 *  Reaktiv an den SettingsRepository-Flow angebunden. */
class RecentNotesViewModel(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val notes: StateFlow<List<RecentNote>> = settingsRepository.recentNotesFlow(sortStarredFirst = true)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun toggleStar(note: RecentNote) {
        viewModelScope.launch {
            settingsRepository.toggleStarRecentNote(note.text)
        }
    }

    /** Auswahl rutscht nach oben (MRU); Anzeige aktualisiert sich beim nächsten Öffnen. */
    fun push(note: RecentNote) {
        viewModelScope.launch { settingsRepository.pushRecentNote(note.text) }
    }
}
