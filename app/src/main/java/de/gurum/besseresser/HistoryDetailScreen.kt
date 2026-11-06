package de.gurum.besseresser

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryDetailScreen(entry: HistoryEntry, settings: Settings, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // Android-Zurück-Button verhält sich wie der On-Screen-Zurückpfeil
    BackHandler(onBack = onBack)
    var confirmDelete by remember { mutableStateOf(false) }
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
            .padding(20.dp),
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
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.history_entry_delete)
                    )
                }
            }
        )

        if (thumb != null) {
            Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = stringResource(R.string.photo_of_meal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(230.dp)
                )
            }
        }

        if (reEvalBusy) {
            // Re-Bewertung durch KI läuft (nach erkannt-Korrektur)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.reeval_progress), fontSize = 13.sp)
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

        Text(stringResource(R.string.my_goals), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
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
}
