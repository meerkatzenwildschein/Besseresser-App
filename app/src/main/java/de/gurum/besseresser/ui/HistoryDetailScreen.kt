package de.gurum.besseresser.ui
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.core.content.FileProvider
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
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import de.gurum.besseresser.R
import de.gurum.besseresser.model.Evaluation
import de.gurum.besseresser.model.HistoryEntry
import de.gurum.besseresser.data.PromptBuilder
import de.gurum.besseresser.data.PromptBundle
import de.gurum.besseresser.model.Settings
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

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

/** Baut den Teilen-Text aus erkanntem Essen (🍽️) und allgemeiner Bewertung (🧠) zusammen. */
internal fun buildShareText(
    recognizedMeal: String,
    summary: String,
    formatDetected: (String) -> String = { "🍽️ $it" },
    formatSummary: (String) -> String = { "🧠 $it" }
): String = buildList {
    if (recognizedMeal.isNotBlank()) add(formatDetected(recognizedMeal.trim()))
    if (summary.isNotBlank()) add(formatSummary(summary.trim()))
}.joinToString("\n\n")

private fun shareHistoryEntry(ctx: Context, photoFile: java.io.File?, entry: HistoryEntry) {
    val shareText = buildShareText(
        recognizedMeal = entry.recognizedMeal,
        summary = entry.summary,
        formatDetected = { ctx.getString(R.string.detected_prefix, it) },
        formatSummary = { ctx.getString(R.string.summary_prefix, it) }
    )
    if (shareText.isNotBlank()) {
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(ctx.getString(R.string.app_name), shareText))
        Toast.makeText(ctx, ctx.getString(R.string.share_clipboard_hint), Toast.LENGTH_SHORT).show()
    }
    val photoUri = photoFile?.let {
        runCatching {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", it)
        }.getOrNull()
    }
    if (photoUri == null && shareText.isBlank()) return

    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        if (photoUri != null) {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, photoUri)
            clipData = ClipData.newUri(ctx.contentResolver, ctx.getString(R.string.photo_of_meal), photoUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (shareText.isNotBlank()) {
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
        } else {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
    }
    val chooser = Intent.createChooser(sendIntent, ctx.getString(R.string.history_entry_share))
    ctx.startActivity(chooser)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailScreen(
    entryId: Long,
    settings: Settings,
    onBack: () -> Unit,
    viewModel: HistoryDetailViewModel = koinViewModel { parametersOf(entryId) }
) {
    val ctx = LocalContext.current
    val detailState by viewModel.uiState.collectAsStateWithLifecycle()
    val entry = detailState.entry ?: run {
        // Nur zurück, wenn der erste DB-Load abgeschlossen ist und der Eintrag wirklich fehlt.
        // Sonst würde das initiale null-State die Navigation sofort zurück auslösen.
        if (detailState.loaded) {
            LaunchedEffect(Unit) { onBack() }
        }
        return
    }
    // Android-Zurück-Button verhält sich wie der On-Screen-Zurückpfeil
    BackHandler(onBack = onBack)
    var confirmDelete by remember { mutableStateOf(false) }
    var showDuplicateDatePicker by remember { mutableStateOf(false) }
    var duplicateTargetDate by remember { mutableStateOf<LocalDate?>(null) }
    var showRecognizedEdit by remember { mutableStateOf(false) }
    val photoFile = remember(entry.id, entry.photoFile) {
        viewModel.photoFile()
    }

    // Fehler aus der Re-Evaluation als Toast anzeigen
    LaunchedEffect(detailState.error) {
        detailState.error?.let { msg ->
            Toast.makeText(ctx, ctx.getString(R.string.dayfazit_error_prefix, msg), Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBarRow(
            title = if (entry.backdated)
                SimpleDateFormat(DATE_FORMAT_PATTERN, Locale.getDefault()).format(Date(entry.dateMillis)) +
                    " · " + stringResource(R.string.nacherfassen_label)
            else
                SimpleDateFormat("$DATE_FORMAT_PATTERN HH:mm", Locale.getDefault()).format(Date(entry.dateMillis)),
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

        if (photoFile != null) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                AsyncImage(
                    model = photoFile,
                    contentDescription = stringResource(R.string.photo_of_meal),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                )
            }
        }

        if (detailState.reEvalBusy) {
            // Re-Bewertung durch KI läuft (nach recognizedMeal-Korrektur)
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

        val canShare = photoFile != null || entry.recognizedMeal.isNotBlank() || entry.summary.isNotBlank()
        if (entry.summary.isNotBlank() || entry.recognizedMeal.isNotBlank() || entry.note.isNotBlank()) {
            // Gleicher antipbarer Block wie in der Auswertung: 🧠 sichtbar, 🍽️/📝 einklappbar,
            // 🍽️ mit Pencil → recognizedMeal userkorrigierbar, unten rechts Teilen-Button
            CollapsibleSummaryBlock(
                summary = entry.summary,
                recognizedMeal = entry.recognizedMeal,
                note = entry.note,
                onEditRecognizedMeal = { if (entry.recognizedMeal.isNotBlank()) showRecognizedEdit = true },
                onShare = if (canShare) ({ shareHistoryEntry(ctx, viewModel.photoFile(), entry) }) else null
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
            if (entry.score >= 0) {
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
                        score = entry.score,
                        tooltipRes = R.string.score_tooltip_ok
                    )
                }
            }
        }
        entry.goalResults.forEach { GoalRow(it.goal, it) }

        Spacer(Modifier.height(16.dp))
        }
    }

    if (showRecognizedEdit) {
        RecognizedMealEditDialog(
            initialText = entry.recognizedMeal,
            onOk = { newText ->
                val trimmed = newText.trim()
                viewModel.updateRecognizedMeal(trimmed)
                showRecognizedEdit = false
                if (trimmed.isNotBlank() && !detailState.reEvalBusy) {
                    val photoBundle = PromptBundle(
                        intro = ctx.getString(R.string.prompt_eval_intro),
                        formatIntro = ctx.getString(R.string.prompt_eval_format),
                        ampelMeaning = ctx.getString(R.string.prompt_eval_ampel_meaning),
                        exactlyOne = ctx.getString(R.string.prompt_eval_exactly_one),
                        responseLanguage = ctx.getString(R.string.prompt_eval_language),
                        recognized = ctx.getString(R.string.prompt_eval_recognized),
                        honesty = ctx.getString(R.string.prompt_honesty),
                        noteHeader = ctx.getString(R.string.prompt_reeval_header, trimmed)
                    )
                    val textBundle = PromptBundle(
                        intro = ctx.getString(R.string.prompt_eval_intro_textonly),
                        formatIntro = ctx.getString(R.string.prompt_eval_format),
                        ampelMeaning = ctx.getString(R.string.prompt_eval_ampel_meaning),
                        exactlyOne = ctx.getString(R.string.prompt_eval_exactly_one),
                        responseLanguage = ctx.getString(R.string.prompt_eval_language),
                        honesty = ctx.getString(R.string.prompt_honesty)
                    )
                    viewModel.reEvaluate(
                        correctedText = trimmed,
                        settings = settings,
                        photoPromptBundle = photoBundle,
                        textPrompt = PromptBuilder.createAnalyzeTextPrompt(
                            goals = settings.goals,
                            promptBundle = textBundle,
                            textOnlyContext = ctx.getString(R.string.prompt_textonly_context, trimmed)
                        )
                    )
                }
            },
            onDismiss = { showRecognizedEdit = false }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.history_delete_confirm_title)) },
            text = { Text(stringResource(R.string.history_delete_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteEntry {
                        confirmDelete = false
                        onBack()
                    }
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
                        duplicateTargetDate = LocalDate.ofEpochDay(ms / 86_400_000L)
                        showDuplicateDatePicker = false
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

    duplicateTargetDate?.let { targetDay ->
        val now = remember { LocalTime.now() }
        val timePickerState = rememberTimePickerState(
            initialHour = now.hour,
            initialMinute = now.minute,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { duplicateTargetDate = null },
            title = {
                Text(
                    stringResource(R.string.history_duplicate_pick_time_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TimePicker(state = timePickerState)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val targetTime = LocalTime.of(timePickerState.hour, timePickerState.minute)
                        viewModel.duplicateToDate(targetDay, targetTime) {
                            duplicateTargetDate = null
                            val formattedDateTime = "${targetDay.format(DATE_FORMATTER)}, ${targetTime.format(DateTimeFormatter.ofPattern("HH:mm"))}"
                            Toast.makeText(
                                ctx,
                                ctx.getString(R.string.history_duplicate_done, formattedDateTime),
                                Toast.LENGTH_SHORT
                            ).show()
                            onBack()
                        }
                    }
                ) {
                    Text(stringResource(R.string.profile_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { duplicateTargetDate = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
