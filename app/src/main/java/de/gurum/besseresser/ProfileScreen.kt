package de.gurum.besseresser

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

// ----------------------------------------------------------------- Profil-Screen

private val BIRTH_DISPLAY_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/** Kontextzeile für KI-Prompts (Tagesfazit) – null, wenn das Profil komplett leer ist. */
fun profileContextLine(ctx: Context, profile: Profile): String? {
    val parts = mutableListOf<String>()
    profile.ageYears?.let { parts.add(ctx.getString(R.string.profile_ctx_age, it)) }
    profile.weightKg?.let {
        val shown = if (abs(it % 1.0) < 0.001) "${it.toInt()}"
                    else String.format(Locale.US, "%.1f", it)
        parts.add(ctx.getString(R.string.profile_ctx_weight, shown))
    }
    profile.heightCm?.let { parts.add(ctx.getString(R.string.profile_ctx_height, it)) }
    if (profile.gender != Gender.NONE) {
        val word = ctx.getString(
            when (profile.gender) {
                Gender.MALE -> R.string.profile_gender_male
                Gender.FEMALE -> R.string.profile_gender_female
                else -> R.string.profile_gender_diverse
            }
        )
        parts.add(ctx.getString(R.string.profile_ctx_gender, word))
    }
    if (profile.notes.isNotBlank()) parts.add(ctx.getString(R.string.profile_ctx_notes, profile.notes.trim()))
    if (parts.isEmpty()) return null
    return ctx.getString(R.string.profile_ctx_header, parts.joinToString(" · "))
}

/**
 * Profil-Seite: Geburtsdatum (DatePicker, veraltet nie), Gewicht, Körpergröße, Freitext
 * (Anmerkungen/Vorerkrankungen). ALLES optional – man kann auch ohne Angabe speichern.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    initial: Profile,
    onCancel: () -> Unit,
    onSave: (Profile) -> Unit,
    viewModel: ProfileViewModel = viewModel()
) {
    val ctx = LocalContext.current
    BackHandler(onBack = onCancel)

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(initial) {
        viewModel.initFromProfile(initial)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            TopBarRow(
                title = stringResource(R.string.profile_title),
                onBack = onCancel,
                trailing = { },
                icon = Icons.Filled.Person
            )

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        stringResource(R.string.profile_intro),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        stringResource(R.string.profile_usage_hint),
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }

            uiState.error?.let { msg ->
                ErrorBanner(msg)
            }

            SectionCard(title = stringResource(R.string.profile_section_body)) {
                Box {
                    OutlinedTextField(
                        value = uiState.birthDate?.let {
                            LocalDate.parse(it).format(BIRTH_DISPLAY_FORMAT)
                        } ?: "",
                        onValueChange = { },
                        enabled = false,
                        readOnly = true,
                        label = { Text(stringResource(R.string.profile_birth_label)) },
                        trailingIcon = {
                            Icon(Icons.Filled.DateRange, contentDescription = null)
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledContainerColor = MaterialTheme.colorScheme.surface,
                            disabledBorderColor = MaterialTheme.colorScheme.outline,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { viewModel.setShowDatePicker(true) }
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.profile_gender_label),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = uiState.gender == Gender.MALE,
                            onClick = { viewModel.setGender(if (uiState.gender == Gender.MALE) Gender.NONE else Gender.MALE) },
                            label = { Text(stringResource(R.string.profile_gender_male), fontSize = 12.5.sp) }
                        )
                        FilterChip(
                            selected = uiState.gender == Gender.FEMALE,
                            onClick = { viewModel.setGender(if (uiState.gender == Gender.FEMALE) Gender.NONE else Gender.FEMALE) },
                            label = { Text(stringResource(R.string.profile_gender_female), fontSize = 12.5.sp) }
                        )
                        FilterChip(
                            selected = uiState.gender == Gender.DIVERSE,
                            onClick = { viewModel.setGender(if (uiState.gender == Gender.DIVERSE) Gender.NONE else Gender.DIVERSE) },
                            label = { Text(stringResource(R.string.profile_gender_diverse), fontSize = 12.5.sp) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = uiState.weightText,
                        onValueChange = { viewModel.setWeightText(it) },
                        label = { Text(stringResource(R.string.profile_weight_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedTextField(
                        value = uiState.heightText,
                        onValueChange = { viewModel.setHeightText(it) },
                        label = { Text(stringResource(R.string.profile_height_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            SectionCard(title = stringResource(R.string.profile_section_notes)) {
                OutlinedTextField(
                    value = uiState.notesText,
                    onValueChange = { viewModel.setNotesText(it) },
                    label = { Text(stringResource(R.string.profile_notes_label)) },
                    placeholder = { Text(stringResource(R.string.profile_notes_hint)) },
                    shape = RoundedCornerShape(12.dp),
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(100.dp))
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
        ) {
            Button(
                onClick = {
                    val weight = uiState.weightText.trim().replace(',', '.')
                        .takeIf { it.isNotBlank() }?.toDoubleOrNull()
                    val height = uiState.heightText.trim().takeIf { it.isNotBlank() }?.toIntOrNull()

                    val plausible =
                        (weight == null || weight in 1.0..700.0) &&
                        (height == null || height in 30..280)
                    if (!plausible) {
                        viewModel.save(ctx, onSaved = onSave)
                    } else {
                        onSave(Profile(uiState.birthDate, weight, height, uiState.notesText.trim(), uiState.gender))
                    }
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    stringResource(R.string.profile_save),
                    fontSize = 16.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    if (uiState.showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = uiState.birthDate
                ?.let { try { LocalDate.parse(it).toEpochDay() * 86_400_000 } catch (e: Exception) { null } }
        )
        DatePickerDialog(
            onDismissRequest = { viewModel.setShowDatePicker(false) },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { ms ->
                        viewModel.setBirthDate(LocalDate.ofEpochDay(ms / 86_400_000).toString())
                    }
                    viewModel.setShowDatePicker(false)
                }) { Text(stringResource(R.string.profile_ok)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.setBirthDate(null)
                    viewModel.setShowDatePicker(false)
                }) { Text(stringResource(R.string.profile_date_clear)) }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}
