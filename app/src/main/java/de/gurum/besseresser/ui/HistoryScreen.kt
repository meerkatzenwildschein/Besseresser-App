package de.gurum.besseresser.ui
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.io.File
import de.gurum.besseresser.R
import de.gurum.besseresser.data.PhotoCaptureManager
import de.gurum.besseresser.model.HistoryEntry
import de.gurum.besseresser.model.Profile
import de.gurum.besseresser.model.Settings
import de.gurum.besseresser.model.weekKeyOf

@Composable
fun HistoryScreen(
    settings: Settings,
    profile: Profile,
    onPick: (HistoryEntry) -> Unit,
    onBack: () -> Unit,
    photoCaptureManager: PhotoCaptureManager = koinInject(),
    viewModel: HistoryViewModel = koinViewModel()
) {
    val ctx = LocalContext.current
    val handleBack = {
        viewModel.flushPendingDeletes()
        onBack()
    }
    BackHandler(onBack = handleBack)

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Capture file is created fresh on demand, not at composition time.
    val addCaptureState = remember { mutableStateOf<Pair<File, Uri>?>(null) }
    fun ensureAddCaptureFile(): Pair<File, Uri> {
        val existing = addCaptureState.value
        if (existing != null) return existing
        val fresh = photoCaptureManager.createCaptureFile()
        addCaptureState.value = fresh
        return fresh
    }

    val addSystemCameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        if (ok) {
            viewModel.setShowAddReview(true)
        } else {
            addCaptureState.value?.first?.let { photoCaptureManager.deleteCaptureFile(it) }
            addCaptureState.value = null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.flushPendingDeletes()
        }
    }
    BackHandler(enabled = uiState.showAddCamera) {
        viewModel.setShowAddCamera(false)
        addCaptureState.value?.first?.let { photoCaptureManager.deleteCaptureFile(it) }
        addCaptureState.value = null
    }
    BackHandler(enabled = uiState.showAddReview) {
        viewModel.setShowAddReview(false)
        addCaptureState.value?.first?.let { photoCaptureManager.deleteCaptureFile(it) }
        addCaptureState.value = null
    }
    BackHandler(enabled = uiState.showAddText) { viewModel.setShowAddText(false) }

    val anyOverlayOpen = uiState.showAddCamera || uiState.showAddReview || uiState.showAddText || uiState.addDialogForDay != null || uiState.showAddBusyCard

    Box(
        modifier = Modifier
            .fillMaxSize()
            .historySwipeHandler(
                isBlocked = anyOverlayOpen,
                onBlocked = {
                    Toast.makeText(ctx, ctx.getString(R.string.history_swipe_blocked_overlay), Toast.LENGTH_SHORT).show()
                },
                onBack = handleBack
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 20.dp)
        ) {
            HistoryListContent(
                entries = uiState.entries,
                uiItems = uiState.uiItems,
                pendingDeleteIds = uiState.pendingDeleteIds,
                fazitMap = uiState.fazitMap,
                loadingFazits = uiState.loadingFazits.toList(),
                expandedFazits = uiState.expandedFazits.toList(),
                weekFazitMap = uiState.weekFazitMap,
                loadingWeeks = uiState.loadingWeeks,
                expandedWeeks = uiState.expandedWeeks,
                onBack = handleBack,
                onPick = { entry ->
                    viewModel.flushPendingDeletes()
                    onPick(entry)
                },
                onGenerateFazit = { day, list ->
                    viewModel.generateFazit(day, list)
                },
                onGenerateWeekFazit = { wk ->
                    viewModel.generateWeekFazit(wk)
                },
                onToggleExpandWeek = { wk -> viewModel.toggleWeekExpanded(wk) },
                onToggleExpandFazit = { viewModel.toggleFazitExpanded(it) },
                onAddMealForDay = { day -> viewModel.setAddDialogForDay(day) },
                onDeleteEntry = { entry -> viewModel.requestDeleteEntry(entry) },
                onUndoDeleteEntry = { entry -> viewModel.undoDeleteEntry(entry.id) },
                onMoveEntryWithinDay = { day, fromIdx, toIdx ->
                    viewModel.moveEntryWithinDay(day, fromIdx, toIdx)
                },
                onPhotoFile = { viewModel.photoFileOf(it) }
            )
        }

        NacherfassenChoiceDialog(
            day = uiState.addDialogForDay,
            onDismiss = { viewModel.setAddDialogForDay(null) },
            onSelectWithPhoto = { targetDay, targetTime ->
                viewModel.setAddTargetDay(targetDay)
                viewModel.setAddTargetTime(targetTime)
                viewModel.setAddDialogForDay(null)
                viewModel.setShowAddCamera(true)
            },
            onSelectWithoutPhoto = { targetDay, targetTime ->
                viewModel.setAddTargetDay(targetDay)
                viewModel.setAddTargetTime(targetTime)
                viewModel.setAddDialogForDay(null)
                viewModel.setShowAddText(true)
            }
        )

        if (uiState.showAddBusyCard) {
            BackdatedBusyDialog()
        }

        BackdatedOverlays(
            showCamera = uiState.showAddCamera,
            showReview = uiState.showAddReview,
            showText = uiState.showAddText,
            showBusy = uiState.showAddBusyCard,
            addPhotoFile = addCaptureState.value?.first,
            addPhotoUri = addCaptureState.value?.second,
            addNote = uiState.addNote,
            addTargetDay = uiState.addTargetDay,
            settings = settings,
            onNoteChange = { viewModel.setAddNote(it) },
            onDismissCamera = {
                viewModel.setShowAddCamera(false)
                addCaptureState.value?.first?.let { photoCaptureManager.deleteCaptureFile(it) }
                addCaptureState.value = null
            },
            onDismissReview = {
                viewModel.setShowAddReview(false)
                addCaptureState.value?.first?.let { photoCaptureManager.deleteCaptureFile(it) }
                addCaptureState.value = null
            },
            onDismissText = { viewModel.setShowAddText(false) },
            onFallbackSystemCamera = {
                viewModel.setShowAddCamera(false)
                val (_, uri) = ensureAddCaptureFile()
                addSystemCameraLauncher.launch(uri)
            },
            onSubmitBackdated = { uri, textOnly ->
                addCaptureState.value = null
                viewModel.submitBackdated(
                    photoUri = uri,
                    textOnly = textOnly,
                    onPick = onPick
                )
            }
        )

        // Fehler aus der Nacherfassen-Analyse als Toast anzeigen
        LaunchedEffect(uiState.addError) {
            uiState.addError?.let { msg ->
                Toast.makeText(ctx, ctx.getString(R.string.error_prefix, msg), Toast.LENGTH_LONG).show()
                viewModel.clearAddError()
            }
        }
    }
}

private class HistorySwipeBackState(
    private val isBlocked: Boolean,
    private val onBlocked: () -> Unit,
    private val onBack: () -> Unit
) {
    private var acc = 0f
    private var hintShown = false
    private var triggered = false  // prevent multiple back navigations in one drag gesture

    fun reset() {
        acc = 0f
        hintShown = false
        triggered = false
    }

    fun onDrag(dragAmount: Float) {
        if (dragAmount == 0f) return
        if (triggered) return
        acc += dragAmount
        if (kotlin.math.abs(acc) <= 160f) return

        acc = 0f
        if (isBlocked) {
            handleBlocked()
        } else if (dragAmount > 0) {
            triggered = true
            onBack()
        }
    }

    private fun handleBlocked() {
        if (!hintShown) {
            onBlocked()
            hintShown = true
        }
    }
}

private fun Modifier.historySwipeHandler(
    isBlocked: Boolean,
    onBlocked: () -> Unit,
    onBack: () -> Unit
): Modifier = pointerInput(Unit) {
    val state = HistorySwipeBackState(isBlocked, onBlocked, onBack)
    detectHorizontalDragGestures(
        onDragEnd = { state.reset() },
        onDragCancel = { state.reset() }
    ) { _, dragAmount ->
        state.onDrag(dragAmount)
    }
}
