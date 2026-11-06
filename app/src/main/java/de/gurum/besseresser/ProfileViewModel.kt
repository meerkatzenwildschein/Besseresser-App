package de.gurum.besseresser

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    val birthDate: String? = null,
    val weightText: String = "",
    val heightText: String = "",
    val notesText: String = "",
    val gender: Gender = Gender.NONE,
    val error: String? = null,
    val showDatePicker: Boolean = false
)

class ProfileViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    fun initFromProfile(profile: Profile) {
        _uiState.update {
            it.copy(
                birthDate = profile.birthDate,
                weightText = profile.weightKg?.toString()?.replace('.', ',') ?: "",
                heightText = profile.heightCm?.toString() ?: "",
                notesText = profile.notes,
                gender = profile.gender
            )
        }
    }

    fun setBirthDate(dateIso: String?) {
        _uiState.update { it.copy(birthDate = dateIso) }
    }

    fun setWeightText(weight: String) {
        _uiState.update { it.copy(weightText = weight) }
    }

    fun setHeightText(height: String) {
        _uiState.update { it.copy(heightText = height) }
    }

    fun setNotesText(notes: String) {
        _uiState.update { it.copy(notesText = notes) }
    }

    fun setGender(gender: Gender) {
        _uiState.update { it.copy(gender = gender) }
    }

    fun setShowDatePicker(show: Boolean) {
        _uiState.update { it.copy(showDatePicker = show) }
    }

    fun save(ctx: Context, onSaved: (Profile) -> Unit): Boolean {
        val state = _uiState.value
        val w = if (state.weightText.isBlank()) null
        else state.weightText.replace(',', '.').toDoubleOrNull()
        if (state.weightText.isNotBlank() && (w == null || w <= 0 || w > 500)) {
            _uiState.update { it.copy(error = ctx.getString(R.string.profile_err_weight)) }
            return false
        }
        val h = if (state.heightText.isBlank()) null else state.heightText.toIntOrNull()
        if (state.heightText.isNotBlank() && (h == null || h <= 30 || h > 280)) {
            _uiState.update { it.copy(error = ctx.getString(R.string.profile_err_height)) }
            return false
        }
        _uiState.update { it.copy(error = null) }
        val updated = Profile(
            birthDate = state.birthDate,
            weightKg = w,
            heightCm = h,
            notes = state.notesText.trim(),
            gender = state.gender
        )
        ProfileStore.save(ctx, updated.birthDate, updated.weightKg, updated.heightCm, updated.notes, updated.gender)
        onSaved(updated)
        return true
    }
}
