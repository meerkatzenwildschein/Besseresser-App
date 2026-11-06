package de.gurum.besseresser

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/** Offizielles Material-Design „ContentCopy"-Icon als ImageVector (ohne Abhängigkeit von material-icons-extended). */
private val DuplicateCopyIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ContentCopy",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(16f, 1f)
            horizontalLineTo(4f)
            curveTo(2.9f, 1f, 2f, 1.9f, 2f, 3f)
            verticalLineTo(17f)
            horizontalLineTo(4f)
            verticalLineTo(3f)
            horizontalLineTo(16f)
            verticalLineTo(1f)
            close()
            moveTo(19f, 5f)
            horizontalLineTo(8f)
            curveTo(6.9f, 5f, 6f, 5.9f, 6f, 7f)
            verticalLineTo(21f)
            curveTo(6f, 22.1f, 6.9f, 23f, 8f, 23f)
            horizontalLineTo(19f)
            curveTo(20.1f, 23f, 21f, 22.1f, 21f, 21f)
            verticalLineTo(7f)
            curveTo(21f, 5.9f, 20.1f, 5f, 19f, 5f)
            close()
            moveTo(19f, 21f)
            horizontalLineTo(8f)
            verticalLineTo(7f)
            horizontalLineTo(19f)
            verticalLineTo(21f)
            close()
        }
    }.build()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailScreen(entry: HistoryEntry, settings: Settings, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // Android-Zurück-Button verhält sich wie der On-Screen-Zurückpfeil
    BackHandler(onBack = onBack)
    var confirmDelete by remember { mutableStateOf(false) }
    var showDuplicateDatePicker by remember { mutableStateOf(false) }
    var showErkanntEdit by remember { mutableStateOf(false) }
    var reEvalBusy by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf(entry) }
    val thumb = remember(entry.id) {
        HistoryStore.photoFileOf(ctx, entry)?.let { decodeThumb(it, target = 1280) }
    }

    /** Re-Bewertung nach erkannt-Korrektur: Mit Foto wenn vorhanden, sonst text-only.
     *  Ziele + Gesamteindruck werden 1:1 ersetzt; Tages-/Wochenfazit werden verworfen. */
    fun reEvaluate(correctedText: String) {
        val photoFile = HistoryStore.photoFileOf(ctx, current)
        // Acht-Pitfall: KEIN FileProvider für gespeicherte Fotos! Dessen file_paths.xml mappt
        // nur cache-path/photos – getUriForFile auf filesDir/history wirft IllegalArgumentException
        // (Crash nach Pencil-OK). analyzer liest intern via openInputStream: file://-Uri reicht.
        val photoUri = photoFile?.let { Uri.fromFile(it) }
        reEvalBusy = true
        val onDone: (Evaluation) -> Unit = { ev ->
            HistoryStore.updateEvaluation(ctx, current.id, ev.goalResults, ev.summary)
            current = current.copy(goalResults = ev.goalResults, summary = ev.summary)
            DayFazitStore.deleteForEntry(ctx, current)
            WeekFazitStore.deleteForEntry(ctx, current)
            reEvalBusy = false
        }
        val onFail: (String) -> Unit = { msg ->
            Toast.makeText(ctx, ctx.getString(R.string.dayfazit_error_prefix, msg), Toast.LENGTH_LONG).show()
            reEvalBusy = false
        }
        coroutineScope.launch {
            val result = if (photoUri != null) {
                ApiClient.analyze(
                    ctx = ctx,
                    photoUri = photoUri,
                    settings = settings,
                    promptBundle = PromptBundle(
                        intro = ctx.getString(R.string.prompt_eval_intro),
                        formatIntro = ctx.getString(R.string.prompt_eval_format),
                        ampelMeaning = ctx.getString(R.string.prompt_eval_ampel_meaning),
                        exactlyOne = ctx.getString(R.string.prompt_eval_exactly_one),
                        responseLanguage = ctx.getString(R.string.prompt_eval_language),
                        recognized = ctx.getString(R.string.prompt_eval_recognized),
                        honesty = ctx.getString(R.string.prompt_honesty),
                        noteHeader = ctx.getString(R.string.prompt_reeval_header, correctedText)
                    )
                )
            } else {
                ApiClient.analyzeText(
                    ctx = ctx,
                    settings = settings,
                    userDescription = correctedText
                )
            }
            result.onSuccess(onDone).onFailure { e ->
                onFail(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TopBarRow(
            title = if (current.backdated)
                SimpleDateFormat(DATE_FORMAT_PATTERN, Locale.getDefault()).format(Date(current.dateMillis)) +
                    " · " + stringResource(R.string.nacherfassen_label)
            else
                SimpleDateFormat("$DATE_FORMAT_PATTERN HH:mm", Locale.getDefault()).format(Date(current.dateMillis)),
            onBack = onBack,
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        IconButton(
                            onClick = { showDuplicateDatePicker = true },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                DuplicateCopyIcon,
                                contentDescription = stringResource(R.string.history_entry_duplicate),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        IconButton(
                            onClick = { confirmDelete = true },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.history_entry_delete),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }
            }
        )

        if (thumb != null) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = stringResource(R.string.photo_of_meal),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                )
            }
        }

        if (reEvalBusy) {
            // Re-Bewertung durch KI läuft (nach erkannt-Korrektur)
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.reeval_progress),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        if (current.summary.isNotBlank() || current.erkannt.isNotBlank() || current.note.isNotBlank()) {
            // Gleicher antipbarer Block wie in der Auswertung: 🧠 sichtbar, 🍽️/📝 einklappbar,
            // 🍽️ mit Pencil → erkannt userkorrigierbar
            CollapsibleSummaryBlock(
                summary = current.summary,
                erkannt = current.erkannt,
                note = current.note,
                onEditErkannt = { if (current.erkannt.isNotBlank()) showErkanntEdit = true }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.my_goals),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            if (current.score >= 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        stringResource(R.string.score_caption),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ScoreCircle(
                        score = current.score,
                        tooltipRes = R.string.score_tooltip_ok
                    )
                }
            }
        }
        current.goalResults.forEach { GoalRow(it.goal, it) }

        Spacer(Modifier.height(16.dp))
    }

    if (showErkanntEdit) {
        ErkanntEditDialog(
            initialText = current.erkannt,
            onOk = { newText ->
                HistoryStore.updateErkannt(ctx, current.id, newText)
                current = current.copy(erkannt = newText)
                showErkanntEdit = false
                if (newText.isNotBlank() && !reEvalBusy) reEvaluate(newText)
            },
            onDismiss = { showErkanntEdit = false }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.history_delete_confirm_title)) },
            text = { Text(stringResource(R.string.history_delete_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    HistoryStore.delete(ctx, entry)
                    confirmDelete = false
                    onBack()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showDuplicateDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.now().toEpochDay() * 86_400_000L
        )
        DatePickerDialog(
            onDismissRequest = { showDuplicateDatePicker = false },
            confirmButton = {
                TextButton(
                    enabled = pickerState.selectedDateMillis != null,
                    onClick = {
                        val ms = pickerState.selectedDateMillis ?: return@TextButton
                        val targetDay = LocalDate.ofEpochDay(ms / 86_400_000L)
                        HistoryStore.duplicateToDate(ctx, current, targetDay)
                        showDuplicateDatePicker = false
                        Toast.makeText(
                            ctx,
                            ctx.getString(R.string.history_duplicate_done, targetDay.format(DATE_FORMATTER)),
                            Toast.LENGTH_SHORT
                        ).show()
                        onBack()
                    }
                ) {
                    Text(stringResource(R.string.profile_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDuplicateDatePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        ) {
            DatePicker(
                state = pickerState,
                title = {
                    Text(
                        stringResource(R.string.history_duplicate_pick_date_title),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)
                    )
                }
            )
        }
    }
}
