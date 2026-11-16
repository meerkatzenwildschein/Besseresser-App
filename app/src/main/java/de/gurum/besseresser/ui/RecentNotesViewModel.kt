package de.gurum.besseresser.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import de.gurum.besseresser.data.SettingsRepository
import de.gurum.besseresser.model.RecentNote

/** ViewModel für die Notiz-Schnellauswahl (MRU + Favoriten-Sternchen).
 *  Hält die Liste lokal; Stern-Toggle aktualisiert in-place (kein Hüpfen),
 *  Sortierung erfolgt beim nächsten Öffnen über loadRecentNotes(true). */
class RecentNotesViewModel(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _notes = MutableStateFlow<List<RecentNote>>(emptyList())
    val notes: StateFlow<List<RecentNote>> = _notes.asStateFlow()

    init {
        viewModelScope.launch {
            _notes.value = settingsRepository.loadRecentNotes(sortStarredFirst = true)
        }
    }

    fun toggleStar(note: RecentNote) {
        val updated = _notes.value.map {
            if (it.text == note.text) it.copy(starred = !it.starred) else it
        }
        _notes.value = updated
        viewModelScope.launch { settingsRepository.saveRecentNotes(updated) }
    }

    /** Auswahl rutscht nach oben (MRU); Anzeige aktualisiert sich beim nächsten Öffnen. */
    fun push(note: RecentNote) {
        viewModelScope.launch { settingsRepository.pushRecentNote(note.text) }
    }
}
