package de.gurum.besseresser

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

@Composable
fun BackdatedBusyDialog() {
    androidx.compose.ui.window.Dialog(onDismissRequest = { /* läuft, nicht abbrechen */ }) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(52.dp),
                    strokeWidth = 4.dp
                )
                Text(
                    stringResource(R.string.main_ai_working),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Text(
                    stringResource(R.string.main_ai_working_sub),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun NacherfassenChoiceDialog(
    day: LocalDate?,
    onDismiss: () -> Unit,
    onSelectWithPhoto: (LocalDate) -> Unit,
    onSelectWithoutPhoto: (LocalDate) -> Unit
) {
    day?.let { targetDay ->
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.nacherfassen_title)) },
            text = {
                Text(
                    stringResource(R.string.nacherfassen_text) + "\n" +
                        targetDay.format(DATE_FORMATTER)
                )
            },
            confirmButton = {
                TextButton(onClick = { onSelectWithPhoto(targetDay) }) {
                    Text(stringResource(R.string.nacherfassen_with_photo))
                }
            },
            dismissButton = {
                TextButton(onClick = { onSelectWithoutPhoto(targetDay) }) {
                    Text(stringResource(R.string.nacherfassen_without_photo))
                }
            }
        )
    }
}

@Composable
fun BackdatedOverlays(
    showCamera: Boolean,
    showReview: Boolean,
    showText: Boolean,
    showBusy: Boolean,
    addPhotoFile: File,
    addPhotoUri: Uri,
    addNote: String,
    addTargetDay: LocalDate?,
    settings: Settings,
    onNoteChange: (String) -> Unit,
    onDismissCamera: () -> Unit,
    onDismissReview: () -> Unit,
    onDismissText: () -> Unit,
    onFallbackSystemCamera: () -> Unit,
    onSaveBackdated: (Evaluation, Uri?) -> Unit,
    onSetBusy: (Boolean) -> Unit
) {
    val ctx = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    if (showCamera) {
        CameraCaptureScreen(
            outputFile = addPhotoFile,
            note = addNote,
            onNoteChange = onNoteChange,
            onCancel = onDismissCamera,
            onFallbackSystemCamera = onFallbackSystemCamera,
            onConfirm = {
                onDismissCamera()
                onSetBusy(true)
                val prompts = PromptBundle(
                    intro = ctx.getString(R.string.prompt_eval_intro),
                    formatIntro = ctx.getString(R.string.prompt_eval_format),
                    ampelMeaning = ctx.getString(R.string.prompt_eval_ampel_meaning),
                    exactlyOne = ctx.getString(R.string.prompt_eval_exactly_one),
                    responseLanguage = ctx.getString(R.string.prompt_eval_language),
                    recognized = ctx.getString(R.string.prompt_eval_recognized),
                    honesty = ctx.getString(R.string.prompt_honesty),
                    noteHeader = if (addNote.isNotBlank())
                        ctx.getString(R.string.prompt_note_header, addNote)
                    else ""
                )
                coroutineScope.launch {
                    val result = ApiClient.analyze(
                        ctx = ctx,
                        photoUri = addPhotoUri,
                        settings = settings,
                        promptBundle = prompts
                    )
                    result.onSuccess {
                        onSetBusy(false)
                        onSaveBackdated(it, addPhotoUri)
                    }.onFailure { e ->
                        onSetBusy(false)
                        val msg = e.message ?: e.javaClass.simpleName
                        Toast.makeText(ctx, ctx.getString(R.string.error_prefix, msg), Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }
    if (showReview) {
        AddMealReviewScreen(
            photoUri = addPhotoUri,
            note = addNote,
            onNoteChange = onNoteChange,
            onBack = onDismissReview,
            busy = showBusy,
            onRate = {
                onSetBusy(true)
                val prompts = PromptBundle(
                    intro = ctx.getString(R.string.prompt_eval_intro),
                    formatIntro = ctx.getString(R.string.prompt_eval_format),
                    ampelMeaning = ctx.getString(R.string.prompt_eval_ampel_meaning),
                    exactlyOne = ctx.getString(R.string.prompt_eval_exactly_one),
                    responseLanguage = ctx.getString(R.string.prompt_eval_language),
                    recognized = ctx.getString(R.string.prompt_eval_recognized),
                    honesty = ctx.getString(R.string.prompt_honesty),
                    noteHeader = if (addNote.isNotBlank())
                        ctx.getString(R.string.prompt_note_header, addNote.trim())
                    else ""
                )
                coroutineScope.launch {
                    val result = ApiClient.analyze(
                        ctx = ctx,
                        photoUri = addPhotoUri,
                        settings = settings,
                        promptBundle = prompts
                    )
                    result.onSuccess {
                        onSetBusy(false)
                        onSaveBackdated(it, addPhotoUri)
                    }.onFailure { e ->
                        onSetBusy(false)
                        val msg = e.message ?: e.javaClass.simpleName
                        Toast.makeText(ctx, ctx.getString(R.string.error_prefix, msg), Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }
    if (showText) {
        AddMealNoPhotoScreen(
            day = addTargetDay,
            note = addNote,
            onNoteChange = onNoteChange,
            onBack = onDismissText,
            busy = showBusy,
            onSend = {
                onSetBusy(true)
                coroutineScope.launch {
                    val result = ApiClient.analyzeText(
                        ctx = ctx,
                        settings = settings,
                        userDescription = addNote.trim()
                    )
                    result.onSuccess {
                        onSetBusy(false)
                        onSaveBackdated(it, null)
                    }.onFailure { e ->
                        onSetBusy(false)
                        val msg = e.message ?: e.javaClass.simpleName
                        Toast.makeText(ctx, ctx.getString(R.string.error_prefix, msg), Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }
}

/** Nachtrage-Zweig MIT Foto (Systemkamera-Fallback): Foto prüfen + Anmerkung → bewerten.
 *  Kein Abschluss-/Speichern-Screen: das Ergebnis geht direkt ins Tagesdatum.
 *  OPAKER Hintergrund, damit die Verlaufs-Liste darunter nicht durchscheint. */
@Composable
fun AddMealReviewScreen(
    photoUri: Uri,
    note: String,
    onNoteChange: (String) -> Unit,
    onBack: () -> Unit,
    busy: Boolean,
    onRate: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TopBarRow(title = stringResource(R.string.review_title), onBack = onBack, trailing = { })
            val reviewCtx = LocalContext.current
            val bmp = remember(photoUri) {
                try {
                    reviewCtx.contentResolver.openInputStream(photoUri)?.use { BitmapFactory.decodeStream(it) }
                } catch (e: Exception) { null }
            }
            if (bmp != null) {
                Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = stringResource(R.string.photo_of_meal),
                        modifier = Modifier.fillMaxWidth().height(280.dp)
                    )
                }
            }
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = note,
                onValueChange = onNoteChange,
                placeholder = { Text(stringResource(R.string.main_note_placeholder)) },
                trailingIcon = { RecentNotesButton(onPick = onNoteChange) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 4
            )
            Spacer(Modifier.height(100.dp))
        }
        Button(
            onClick = onRate,
            enabled = !busy,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Mit der Tastatur mitfahren (Notizfeld + fixierter Button kollidieren sonst)
                .imePadding()
                .padding(16.dp)
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(stringResource(if (busy) R.string.main_ai_working else R.string.main_rate_now), fontSize = 17.sp)
        }
    }
}

/** Nachtrage-Zweig OHNE Foto: Beschreibung ist PFLICHT (deshalb Button erst mit Text), Text-only KI. */
@Composable
fun AddMealNoPhotoScreen(
    day: LocalDate?,
    note: String,
    onNoteChange: (String) -> Unit,
    onBack: () -> Unit,
    busy: Boolean,
    onSend: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TopBarRow(
                title = stringResource(R.string.nacherfassen_title),
                onBack = onBack,
                trailing = { },
                icon = Icons.Filled.Add
            )
            Text(
                (day?.format(DATE_FORMATTER) ?: "") +
                    " · " + stringResource(R.string.nacherfassen_without_photo),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.nacherfassen_empty_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = note,
                onValueChange = onNoteChange,
                placeholder = { Text(stringResource(R.string.main_note_placeholder)) },
                trailingIcon = { RecentNotesButton(onPick = onNoteChange) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth()
            )
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(100.dp))
        }
        Button(
            onClick = onSend,
            enabled = !busy && note.isNotBlank(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Fahrt mit der Tastatur mit: Der Button bleibt immer sichtbar/ tappbar,
                // statt hinter dem Input-Panel zu verschwinden
                .imePadding()
                .padding(16.dp)
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(
                stringResource(
                    if (busy) R.string.main_ai_working else R.string.nacherfassen_send
                ),
                fontSize = 17.sp
            )
        }
    }
}
