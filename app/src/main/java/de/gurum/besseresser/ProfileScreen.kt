package de.gurum.besseresser

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// ----------------------------------------------------------------- Profil-Screen

private val BIRTH_DISPLAY_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/** Kontextzeile für KI-Prompts (Tagesfazit) – null, wenn das Profil komplett leer ist. */
fun profileContextLine(ctx: Context, profile: Profile): String? {
    val parts = mutableListOf<String>()
    profile.ageYears?.let { parts.add(ctx.getString(R.string.profile_ctx_age, it)) }
    profile.weightKg?.let {
        val shown = if (Math.abs(it % 1.0) < 0.001) "${it.toInt()}"
                    else String.format(Locale.US, "%.1f", it)
        parts.add(ctx.getString(R.string.profile_ctx_weight, shown))
    }
    profile.heightCm?.let { parts.add(ctx.getString(R.string.profile_ctx_height, it)) }
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
fun ProfileScreen(initial: Profile, onCancel: () -> Unit, onSave: (Profile) -> Unit) {
    val ctx = LocalContext.current
    // Android-Zurück-Button schließt das Profil (wie der TopBar-Pfeil)
    BackHandler(onBack = onCancel)

    var birthDateIso by remember { mutableStateOf(initial.birthDate) }
    var weightText by remember {
        mutableStateOf(initial.weightKg?.toString()?.replace('.', ',') ?: "")
    }
    var heightText by remember { mutableStateOf(initial.heightCm?.toString() ?: "") }
    var notesText by remember { mutableStateOf(initial.notes) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    val dateInteractionSource = remember { MutableInteractionSource() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TopBarRow(title = stringResource(R.string.profile_title), onBack = onCancel, trailing = { })

            Text(
                stringResource(R.string.profile_intro),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.profile_usage_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            error?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(msg, modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }

            // Geburtsdatum: Material-DatePicker (System-lokalisiert), Anzeige dd.MM.yyyy
            OutlinedTextField(
                value = birthDateIso?.let {
                    LocalDate.parse(it).format(BIRTH_DISPLAY_FORMAT)
                } ?: "",
                onValueChange = { },
                readOnly = true,
                label = { Text(stringResource(R.string.profile_birth_label)) },
                trailingIcon = {
                    Icon(Icons.Filled.DateRange, contentDescription = null)
                },
                interactionSource = dateInteractionSource,
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = dateInteractionSource,
                        indication = null
                    ) { showDatePicker = true }
            )

            OutlinedTextField(
                value = weightText,
                onValueChange = { weightText = it },
                label = { Text(stringResource(R.string.profile_weight_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = heightText,
                onValueChange = { heightText = it },
                label = { Text(stringResource(R.string.profile_height_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = notesText,
                onValueChange = { notesText = it },
                label = { Text(stringResource(R.string.profile_notes_label)) },
                placeholder = { Text(stringResource(R.string.profile_notes_hint)) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth()
            )

            // Platzhalter, damit nichts unter dem fixierten Speichern-CTA steckt
            Spacer(Modifier.height(100.dp))
        }

        // Fixierter Speichern-CTA unten (wie im Setup)
        Button(
            onClick = {
                val weight = weightText.trim().replace(',', '.')
                    .takeIf { it.isNotBlank() }?.toDoubleOrNull()
                val height = heightText.trim().takeIf { it.isNotBlank() }?.toIntOrNull()

                val plausible =
                    (weight == null || weight in 1.0..700.0) &&
                    (height == null || height in 30..280)
                if (!plausible) {
                    error = ctx.getString(R.string.profile_invalid_number)
                } else {
                    onSave(Profile(birthDateIso, weight, height, notesText.trim()))
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(stringResource(R.string.profile_save), fontSize = 17.sp)
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = birthDateIso
                ?.let { try { LocalDate.parse(it).toEpochDay() * 86_400_000 } catch (e: Exception) { null } }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { ms ->
                        birthDateIso = LocalDate.ofEpochDay(ms / 86_400_000).toString()
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.profile_ok)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    birthDateIso = null
                    showDatePicker = false
                }) { Text(stringResource(R.string.profile_date_clear)) }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}
