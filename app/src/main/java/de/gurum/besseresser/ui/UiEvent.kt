package de.gurum.besseresser.ui

import androidx.compose.runtime.Immutable

/**
 * Einmalige UI-Ereignisse (Single Live Events / One-Shot Events), die über Channels
 * gesendet und in der UI mit `LaunchedEffect` konsumiert werden.
 */
@Immutable
sealed interface UiEvent {
    data class ShowToast(val message: UiText) : UiEvent
    data class ShowSnackbar(val message: UiText, val actionLabel: UiText? = null) : UiEvent
    data object NavigateBack : UiEvent
}
