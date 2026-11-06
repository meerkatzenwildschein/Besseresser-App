package de.gurum.besseresser

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


// ----------------------------------------------------------------- Hauptbildschirm (Kamera/Review/Auswertung)

// ----------------------------------------------------------------- Hauptbildschirm

@Composable
fun MainScreen(
    settings: Settings,
    onEditSettings: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenHistory: () -> Unit,
    viewModel: MainViewModel = viewModel()
) {
    val ctx = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val photoFile = remember {
        File(ctx.cacheDir, "photos").also { it.mkdirs() }
            .let { d ->
                File(d, "essen_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg")
            }
    }
    val photoUri: Uri = remember {
        FileProvider.getUriForFile(ctx, "de.gurum.besseresser.fileprovider", photoFile)
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        viewModel.onSystemCameraResult(ok, photoUri)
        if (ok) {
            viewModel.setShowReview(true)
        }
    }

    // Android-Zurück-Button: Overlay-Ebenen der Aufnahme schließen, statt die App zu beenden.
    BackHandler(enabled = uiState.showCamera) { viewModel.setShowCamera(false) }

    val triggerAnalyze: (String, Uri) -> Unit = { trimmedNote, targetUri ->
        val prompts = createAnalyzePrompts(ctx, trimmedNote)
        viewModel.onPhotoTaken(targetUri)
        viewModel.analyze(ctx, settings, prompts)
    }

    val lastGoaled = uiState.evaluation != null || uiState.savedToHistory
    val navBlocked = uiState.isBusy || uiState.showCamera || uiState.showReview || (lastGoaled && !uiState.savedToHistory)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .mainSwipeToHistoryHandler(
                isBlocked = navBlocked,
                onBlocked = {
                    Toast.makeText(ctx, ctx.getString(R.string.nav_blocked_hint), Toast.LENGTH_SHORT).show()
                },
                onOpenHistory = onOpenHistory
            )
    ) {
        if (uiState.showCamera) {
            CameraCaptureScreen(
                outputFile = photoFile,
                note = uiState.note,
                onNoteChange = { viewModel.setNote(it) },
                onCancel = { viewModel.setShowCamera(false) },
                onFallbackSystemCamera = {
                    viewModel.setShowCamera(false)
                    cameraLauncher.launch(photoUri)
                },
                onConfirm = {
                    viewModel.setShowCamera(false)
                    val noteTrimmed = uiState.note.trim()
                    RecentNotesStore.push(ctx, noteTrimmed)
                    triggerAnalyze(noteTrimmed, photoUri)
                }
            )
        } else if (uiState.showReview) {
            MainReviewScreen(
                photoUri = uiState.photoPreviewUri ?: photoUri,
                note = uiState.note,
                error = uiState.error,
                busy = uiState.isBusy,
                onNoteChange = { viewModel.setNote(it) },
                onBack = {
                    viewModel.setShowReview(false)
                    viewModel.reset()
                },
                onRate = {
                    val trimmed = uiState.note.trim()
                    RecentNotesStore.push(ctx, trimmed)
                    triggerAnalyze(trimmed, photoUri)
                },
                onRetake = {
                    viewModel.setShowReview(false)
                    viewModel.reset()
                    cameraLauncher.launch(photoUri)
                }
            )
        } else {
            val reportShown = lastGoaled && !uiState.evaluation?.goalResults.isNullOrEmpty()
            val menuEnabled = !uiState.isBusy && !uiState.showCamera && !uiState.showReview && !(lastGoaled && !uiState.savedToHistory)
            MainReportView(
                settings = settings,
                evaluation = uiState.evaluation,
                photoUri = uiState.photoPreviewUri,
                note = uiState.note,
                error = uiState.error,
                lastGoaled = lastGoaled,
                savedToHistory = uiState.savedToHistory,
                savingHistory = uiState.savingHistory,
                reportShown = reportShown,
                menuOpen = uiState.menuOpen,
                menuItemsEnabled = menuEnabled,
                onMenuToggle = { viewModel.setMenuOpen(it) },
                onOpenProfile = onOpenProfile,
                onOpenHistory = onOpenHistory,
                onEditSettings = onEditSettings,
                onEditErkannt = { viewModel.setShowErkanntEdit(true) },
                onSaveToHistory = {
                    viewModel.saveToHistory(ctx, settings)
                },
                onDiscard = { viewModel.reset() }
            )

            if (uiState.isBusy && uiState.evaluation == null) {
                MainAiBusyCard(modifier = Modifier.align(Alignment.Center))
            }

            MainFixedCtaButtons(
                lastGoaled = lastGoaled,
                savedToHistory = uiState.savedToHistory,
                reportShown = reportShown,
                busy = uiState.isBusy,
                onNewPhoto = {
                    viewModel.reset()
                    viewModel.setShowCamera(true)
                },
                onTakePhoto = {
                    viewModel.reset()
                    viewModel.setShowCamera(true)
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            if (uiState.showErkanntEdit && uiState.evaluation != null) {
                ErkanntEditDialog(
                    initialText = uiState.evaluation?.erkannt.orEmpty(),
                    onOk = { newText ->
                        viewModel.updateErkannt(ctx, newText)
                    },
                    onDismiss = { viewModel.setShowErkanntEdit(false) }
                )
            }
        }
    }
}

@Composable
private fun MainReviewScreen(
    photoUri: Uri?,
    note: String,
    error: String?,
    busy: Boolean,
    onNoteChange: (String) -> Unit,
    onBack: () -> Unit,
    onRate: () -> Unit,
    onRetake: () -> Unit
) {
    val ctx = LocalContext.current
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TopBarRow(
                title = stringResource(R.string.review_title),
                onBack = onBack,
                trailing = { }
            )
            photoUri?.let { uri ->
                val bmp = remember(uri) {
                    try {
                        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    } catch (e: Exception) {
                        null
                    }
                }
                if (bmp != null) {
                    Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = stringResource(R.string.photo_of_meal),
                            modifier = Modifier.fillMaxWidth().height(300.dp)
                        )
                    }
                }
            }
            if (error != null) {
                ErrorBanner(error)
            }
            OutlinedTextField(
                value = note,
                onValueChange = onNoteChange,
                placeholder = { Text(stringResource(R.string.main_note_placeholder)) },
                trailingIcon = {
                    RecentNotesButton(onPick = onNoteChange)
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 4
            )
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(140.dp))
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .imePadding()
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onRate,
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    stringResource(if (busy) R.string.main_ai_working else R.string.main_rate_now),
                    fontSize = 17.sp
                )
            }
            OutlinedButton(
                onClick = onRetake,
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(stringResource(R.string.main_retake))
            }
        }
    }
}

@Composable
private fun MainReportView(
    settings: Settings,
    evaluation: Evaluation?,
    photoUri: Uri?,
    note: String,
    error: String?,
    lastGoaled: Boolean,
    savedToHistory: Boolean,
    savingHistory: Boolean,
    reportShown: Boolean,
    menuOpen: Boolean,
    menuItemsEnabled: Boolean,
    onMenuToggle: (Boolean) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenHistory: () -> Unit,
    onEditSettings: () -> Unit,
    onEditErkannt: () -> Unit,
    onSaveToHistory: () -> Unit,
    onDiscard: () -> Unit
) {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MainTopBar(
            menuOpen = menuOpen,
            menuEnabled = menuItemsEnabled,
            onMenuToggle = onMenuToggle,
            onOpenProfile = onOpenProfile,
            onOpenHistory = onOpenHistory,
            onEditSettings = onEditSettings
        )

        if (lastGoaled) {
            photoUri?.let { uri ->
                val bmp = remember(uri) {
                    try {
                        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    } catch (e: Exception) {
                        null
                    }
                }
                if (bmp != null) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = stringResource(R.string.photo_of_meal),
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        error?.let { msg ->
            ErrorBanner(msg)
        }

        val results = evaluation?.goalResults
        Text(stringResource(R.string.my_goals), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)

        settings.goals.forEach { goal ->
            val res = results?.firstOrNull { it.goal.equals(goal, ignoreCase = true) }
            GoalRow(goal, res)
        }

        if (lastGoaled && !results.isNullOrEmpty() && !savedToHistory) {
            val summary = evaluation?.summary ?: ""
            val det = evaluation?.erkannt.orEmpty()
            if (summary.isNotBlank() || det.isNotBlank()) {
                CollapsibleSummaryBlock(
                    summary = summary,
                    erkannt = det,
                    note = note,
                    onEditErkannt = { if (det.isNotBlank()) onEditErkannt() }
                )
            }
            Button(
                onClick = onSaveToHistory,
                enabled = !savingHistory,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(stringResource(if (savingHistory) R.string.main_saving else R.string.main_save_to_history))
            }
            TextButton(
                onClick = onDiscard,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.main_discard))
            }
        }
        if (lastGoaled && savedToHistory) {
            OutlinedButton(
                onClick = onOpenHistory,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) {
                Text(stringResource(R.string.main_open_history))
            }
        }

        Spacer(Modifier.height(if (reportShown && !savedToHistory) 24.dp else 100.dp))
    }
}

@Composable
private fun MainTopBar(
    menuOpen: Boolean,
    menuEnabled: Boolean,
    onMenuToggle: (Boolean) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenHistory: () -> Unit,
    onEditSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.main_title), fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Box {
            IconButton(onClick = { onMenuToggle(true) }) {
                Icon(
                    Icons.Filled.Menu,
                    contentDescription = stringResource(R.string.menu_open_description)
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { onMenuToggle(false) }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_profile)) },
                    onClick = {
                        onMenuToggle(false)
                        onOpenProfile()
                    },
                    leadingIcon = {
                        Icon(Icons.Filled.Person, contentDescription = null)
                    },
                    enabled = menuEnabled
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_history)) },
                    onClick = {
                        onMenuToggle(false)
                        onOpenHistory()
                    },
                    leadingIcon = {
                        Icon(Icons.Filled.DateRange, contentDescription = null)
                    },
                    enabled = menuEnabled
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_settings)) },
                    onClick = {
                        onMenuToggle(false)
                        onEditSettings()
                    },
                    leadingIcon = {
                        Icon(Icons.Filled.Settings, contentDescription = null)
                    }
                )
            }
        }
    }
}

@Composable
private fun MainAiBusyCard(modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.padding(horizontal = 32.dp)
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

@Composable
private fun MainFixedCtaButtons(
    lastGoaled: Boolean,
    savedToHistory: Boolean,
    reportShown: Boolean,
    busy: Boolean,
    onNewPhoto: () -> Unit,
    onTakePhoto: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (lastGoaled && savedToHistory) {
        Button(
            onClick = onNewPhoto,
            modifier = modifier
                .padding(16.dp)
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(stringResource(R.string.main_new_photo), fontSize = 17.sp)
        }
    } else if (!reportShown) {
        Button(
            onClick = onTakePhoto,
            enabled = !busy,
            modifier = modifier
                .padding(16.dp)
                .fillMaxWidth()
                .height(56.dp)
        ) {
            if (busy) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.main_ai_working), fontSize = 17.sp)
                }
            } else {
                Text(stringResource(R.string.main_take_photo), fontSize = 17.sp)
            }
        }
    }
}

private fun createAnalyzePrompts(ctx: Context, trimmedNote: String): PromptBundle = PromptBundle(
    intro = ctx.getString(R.string.prompt_eval_intro),
    formatIntro = ctx.getString(R.string.prompt_eval_format),
    ampelMeaning = ctx.getString(R.string.prompt_eval_ampel_meaning),
    exactlyOne = ctx.getString(R.string.prompt_eval_exactly_one),
    responseLanguage = ctx.getString(R.string.prompt_eval_language),
    recognized = ctx.getString(R.string.prompt_eval_recognized),
    honesty = ctx.getString(R.string.prompt_honesty),
    noteHeader = if (trimmedNote.isNotBlank())
        ctx.getString(R.string.prompt_note_header, trimmedNote)
    else ""
)

private fun saveMainEntryToHistory(
    ctx: Context,
    photoUri: Uri,
    evaluation: Evaluation?,
    note: String
): HistoryEntry? {
    val ev = evaluation ?: return null
    return try {
        HistoryStore.add(ctx, photoUri, ev, note.trim())
    } catch (e: Exception) {
        null
    }
}

private class MainSwipeState(
    private val isBlocked: Boolean,
    private val onBlocked: () -> Unit,
    private val onOpenHistory: () -> Unit
) {
    private var acc = 0f
    private var hintShown = false

    fun reset() {
        acc = 0f
        hintShown = false
    }

    fun onDrag(dragAmount: Float) {
        if (dragAmount >= 0) {
            acc = 0f
            return
        }
        acc += dragAmount
        if (acc > -160f) return

        acc = 0f
        if (isBlocked) {
            handleBlocked()
        } else {
            onOpenHistory()
        }
    }

    private fun handleBlocked() {
        if (!hintShown) {
            onBlocked()
            hintShown = true
        }
    }
}

private fun Modifier.mainSwipeToHistoryHandler(
    isBlocked: Boolean,
    onBlocked: () -> Unit,
    onOpenHistory: () -> Unit
): Modifier = pointerInput(Unit) {
    val state = MainSwipeState(isBlocked, onBlocked, onOpenHistory)
    detectHorizontalDragGestures(
        onDragEnd = { state.reset() },
        onDragCancel = { state.reset() }
    ) { _, dragAmount ->
        state.onDrag(dragAmount)
    }
}
