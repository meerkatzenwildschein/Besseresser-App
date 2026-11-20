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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.io.File
import de.gurum.besseresser.R
import de.gurum.besseresser.data.PhotoCaptureManager
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
    photoCaptureManager: PhotoCaptureManager = koinInject(),
    viewModel: MainViewModel = koinViewModel()
) {
    val ctx = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Capture file is created fresh each time the camera is launched (not at composition time).
    val captureState = remember { mutableStateOf<Pair<File, Uri>?>(null) }
    fun ensureCaptureFile(): Pair<File, Uri> {
        val existing = captureState.value
        if (existing != null) return existing
        val fresh = photoCaptureManager.createCaptureFile()
        captureState.value = fresh
        return fresh
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val (_, uri) = captureState.value ?: return@rememberLauncherForActivityResult
        viewModel.onSystemCameraResult(ok, uri)
        if (ok) {
            viewModel.setShowReview(true)
        } else {
            captureState.value?.first?.let { photoCaptureManager.deleteCaptureFile(it) }
            captureState.value = null
        }
    }

    // Android-Zurück-Button: Overlay-Ebenen der Aufnahme schließen, statt die App zu beenden.
    BackHandler(enabled = uiState.showCamera) {
        viewModel.setShowCamera(false)
        captureState.value?.first?.let { photoCaptureManager.deleteCaptureFile(it) }
        captureState.value = null
    }

    val triggerAnalyze: (String, Uri) -> Unit = { _, targetUri ->
        viewModel.onPhotoTaken(targetUri)
        viewModel.analyze(settings)
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
            val (captureFile, captureUri) = ensureCaptureFile()
            CameraCaptureScreen(
                outputFile = captureFile,
                note = uiState.note,
                onNoteChange = { viewModel.setNote(it) },
                onCancel = {
                    viewModel.setShowCamera(false)
                    captureState.value?.first?.let { photoCaptureManager.deleteCaptureFile(it) }
                    captureState.value = null
                },
                onFallbackSystemCamera = {
                    viewModel.setShowCamera(false)
                    cameraLauncher.launch(captureUri)
                },
                onConfirm = {
                    viewModel.setShowCamera(false)
                    val noteTrimmed = uiState.note.trim()
                    viewModel.pushRecentNote(noteTrimmed)
                    triggerAnalyze(noteTrimmed, captureUri)
                    captureState.value = null
                }
            )
        } else if (uiState.showReview) {
            val captureUri = captureState.value?.second
            MainReviewScreen(
                photoUri = uiState.photoPreviewUri ?: captureUri,
                note = uiState.note,
                error = uiState.error,
                busy = uiState.isBusy,
                onNoteChange = { viewModel.setNote(it) },
                onBack = {
                    viewModel.setShowReview(false)
                    captureState.value?.first?.let { photoCaptureManager.deleteCaptureFile(it) }
                    viewModel.reset()
                    captureState.value = null
                },
                onRate = {
                    val trimmed = uiState.note.trim()
                    viewModel.pushRecentNote(trimmed)
                    captureUri?.let { triggerAnalyze(trimmed, it) }
                    captureState.value = null
                },
                onRetake = {
                    viewModel.setShowReview(false)
                    captureState.value?.first?.let { photoCaptureManager.deleteCaptureFile(it) }
                    viewModel.reset()
                    captureState.value = null
                    val (_, freshUri) = ensureCaptureFile()
                    cameraLauncher.launch(freshUri)
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
                onEditRecognizedMeal = { viewModel.setshowRecognizedEdit(true) },
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

            if (uiState.showRecognizedEdit && uiState.evaluation != null) {
                RecognizedMealEditDialog(
                    initialText = uiState.evaluation?.recognizedMeal.orEmpty(),
                    onOk = { newText ->
                        viewModel.updateRecognizedMeal(newText)
                    },
                    onDismiss = { viewModel.setshowRecognizedEdit(false) }
                )
            }
        }
    }
}

private class MainSwipeState(
    private val isBlocked: Boolean,
    private val onBlocked: () -> Unit,
    private val onOpenHistory: () -> Unit
) {
    private var acc = 0f
    private var hintShown = false
    private var triggered = false  // prevent multiple navigations in one drag gesture

    fun reset() {
        acc = 0f
        hintShown = false
        triggered = false
    }

    fun onDrag(dragAmount: Float) {
        if (dragAmount >= 0) {
            acc = 0f
            return
        }
        if (triggered) return  // already navigated this gesture, ignore further movement
        acc += dragAmount
        if (acc > -160f) return

        acc = 0f
        if (isBlocked) {
            handleBlocked()
        } else {
            triggered = true
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
