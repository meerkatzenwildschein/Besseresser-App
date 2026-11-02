package de.gurum.besseresser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ----------------------------------------------------------------- Profil-Screen

/**
 * Profil-Seite: Alter, Gewicht, Körpergröße, Freitext (Anmerkungen/Vorerkrankungen).
 * ALLES optional – man kann auch ohne irgendetwas speichern und weitergehen.
 */
@Composable
fun ProfileScreen(initial: Profile, onCancel: () -> Unit, onSave: (Profile) -> Unit) {
    val ctx = LocalContext.current
    // Android-Zurück-Button schließt das Profil (wie der TopBar-Pfeil)
    BackHandler(onBack = onCancel)

    var ageText by remember { mutableStateOf(initial.ageYears?.toString() ?: "") }
    var weightText by remember {
        mutableStateOf(initial.weightKg?.toString()?.replace('.', ',') ?: "")
    }
    var heightText by remember { mutableStateOf(initial.heightCm?.toString() ?: "") }
    var notesText by remember { mutableStateOf(initial.notes) }
    var error by remember { mutableStateOf<String?>(null) }

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

            OutlinedTextField(
                value = ageText,
                onValueChange = { ageText = it },
                label = { Text(stringResource(R.string.profile_age_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
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
                val age = ageText.trim().takeIf { it.isNotBlank() }?.toIntOrNull()
                val weight = weightText.trim().replace(',', '.')
                    .takeIf { it.isNotBlank() }?.toDoubleOrNull()
                val height = heightText.trim().takeIf { it.isNotBlank() }?.toIntOrNull()

                val plausible = (age == null || age in 1..150) &&
                    (weight == null || weight in 1.0..700.0) &&
                    (height == null || height in 30..280)
                if (!plausible) {
                    error = ctx.getString(R.string.profile_invalid_number)
                } else {
                    onSave(Profile(age, weight, height, notesText.trim()))
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
}
