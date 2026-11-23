package de.gurum.besseresser.ui

import android.net.Uri
import androidx.compose.runtime.Immutable
import de.gurum.besseresser.model.DayFazit
import de.gurum.besseresser.model.HistoryEntry
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

const val UNDO_DELETE_DELAY_MS = 5000L

@Immutable
data class HistoryUiState(
    val entries: List<HistoryEntry> = emptyList(),
    val uiItems: List<HistoryUiItem> = emptyList(),
    val pendingDeleteIds: Set<Long> = emptySet(),
    val fazitMap: Map<String, DayFazit> = emptyMap(),
    val loadingFazits: Set<String> = emptySet(),
    val expandedFazits: Set<String> = emptySet(),
    val weekFazit: DayFazit? = null,
    val loadingWeek: Boolean = false,
    val expandedWeek: Boolean = false,
    val weekFazitMap: Map<String, DayFazit> = emptyMap(),
    val loadingWeeks: Set<String> = emptySet(),
    val expandedWeeks: Set<String> = emptySet(),
    val addDialogForDay: LocalDate? = null,
    val showAddCamera: Boolean = false,
    val showAddReview: Boolean = false,
    val showAddText: Boolean = false,
    val showAddBusyCard: Boolean = false,
    val addPhotoFile: File? = null,
    val addPhotoUri: Uri? = null,
    val addNote: String = "",
    val addTargetDay: LocalDate? = null,
    val addTargetTime: LocalTime? = null,
    val addError: String? = null
)
