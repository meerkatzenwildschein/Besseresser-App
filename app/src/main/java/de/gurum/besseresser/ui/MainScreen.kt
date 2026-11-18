package de.gurum.besseresser.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import de.gurum.besseresser.R
import de.gurum.besseresser.data.PromptBundle
import de.gurum.besseresser.model.Settings

// ----------------------------------------------------------------- Hauptbildschirm (Kamera/Review/Auswertung)

@Composable
fun MainScreen(
    settings: Settings,
    onEditSettings: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenConfig: () -> Unit = {},
    viewModel: MainViewModel = koinViewModel()
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
        viewModel.analyze(settings, prompts)
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
                    viewModel.pushRecentNote(noteTrimmed)
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
                    viewModel.pushRecentNote(trimmed)
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
                onOpenConfig = onOpenConfig,
                onEditErkannt = { viewModel.setShowErkanntEdit(true) },
                onTakePhoto = {
                    if (!uiState.isBusy) {
                        viewModel.reset()
                        viewModel.setShowCamera(true)
                    }
                },
                onSaveToHistory = {
                    viewModel.saveToHistory()
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
                        viewModel.updateErkannt(newText)
                    },
                    onDismiss = { viewModel.setShowErkanntEdit(false) }
                )
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
