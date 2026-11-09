package de.gurum.besseresser

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    settings: Settings,
    profile: Profile,
    onPick: (HistoryEntry) -> Unit,
    onBack: () -> Unit,
    viewModel: HistoryViewModel = viewModel()
) {
    val ctx = LocalContext.current
    val handleBack = {
        viewModel.flushPendingDeletes(ctx)
        onBack()
    }
    BackHandler(onBack = handleBack)

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.startObserving(ctx)
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.flushPendingDeletes(ctx)
        }
    }

    val addPhotoFile = remember {
        File(ctx.cacheDir, "photos").also { it.mkdirs() }
            .let { d -> File(d, "essen_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg") }
    }
    val addPhotoUri: Uri = remember {
        androidx.core.content.FileProvider.getUriForFile(ctx, "de.gurum.besseresser.fileprovider", addPhotoFile)
    }
    val addSystemCameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        if (ok) {
            viewModel.setShowAddReview(true)
        }
    }
    BackHandler(enabled = uiState.showAddCamera) { viewModel.setShowAddCamera(false) }
    BackHandler(enabled = uiState.showAddReview) { viewModel.setShowAddReview(false) }
    BackHandler(enabled = uiState.showAddText) { viewModel.setShowAddText(false) }

    HistoryAutoFazitEffect(
        entries = uiState.entries,
        fazitMap = uiState.fazitMap,
        loadingFazits = uiState.loadingFazits.toList(),
        weekFazitMap = uiState.weekFazitMap,
        loadingWeeks = uiState.loadingWeeks,
        onGenerateFazit = { day, list -> viewModel.generateFazit(ctx, day, list, settings, profile) },
        onGenerateWeekFazit = { wk -> viewModel.generateWeekFazit(ctx, settings, profile, weekKey = wk) }
    )

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
                pendingDeleteIds = uiState.pendingDeleteIds,
                fazitMap = uiState.fazitMap,
                loadingFazits = uiState.loadingFazits.toList(),
                expandedFazits = uiState.expandedFazits.toList(),
                weekFazitMap = uiState.weekFazitMap,
                loadingWeeks = uiState.loadingWeeks,
                expandedWeeks = uiState.expandedWeeks,
                onBack = handleBack,
                onPick = { entry ->
                    viewModel.flushPendingDeletes(ctx)
                    onPick(entry)
                },
                onGenerateFazit = { day, list -> viewModel.generateFazit(ctx, day, list, settings, profile) },
                onGenerateWeekFazit = { wk -> viewModel.generateWeekFazit(ctx, settings, profile, weekKey = wk) },
                onToggleExpandWeek = { wk -> viewModel.toggleWeekExpanded(wk) },
                onToggleExpandFazit = { viewModel.toggleFazitExpanded(it) },
                onAddMealForDay = { day -> viewModel.setAddDialogForDay(day) },
                onDeleteEntry = { entry -> viewModel.requestDeleteEntry(ctx, entry) },
                onUndoDeleteEntry = { entry -> viewModel.undoDeleteEntry(entry.id) },
                onMoveEntryWithinDay = { day, fromIdx, toIdx ->
                    viewModel.moveEntryWithinDay(ctx, day, fromIdx, toIdx)
                }
            )
        }

        NacherfassenChoiceDialog(
            day = uiState.addDialogForDay,
            onDismiss = { viewModel.setAddDialogForDay(null) },
            onSelectWithPhoto = { targetDay ->
                viewModel.setAddTargetDay(targetDay)
                viewModel.setAddDialogForDay(null)
                viewModel.setShowAddCamera(true)
            },
            onSelectWithoutPhoto = { targetDay ->
                viewModel.setAddTargetDay(targetDay)
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
            addPhotoFile = addPhotoFile,
            addPhotoUri = addPhotoUri,
            addNote = uiState.addNote,
            addTargetDay = uiState.addTargetDay,
            settings = settings,
            onNoteChange = { viewModel.setAddNote(it) },
            onDismissCamera = { viewModel.setShowAddCamera(false) },
            onDismissReview = { viewModel.setShowAddReview(false) },
            onDismissText = { viewModel.setShowAddText(false) },
            onFallbackSystemCamera = {
                viewModel.setShowAddCamera(false)
                addSystemCameraLauncher.launch(addPhotoUri)
            },
            onSaveBackdated = { ev, uri ->
                viewModel.saveBackdatedEntry(ctx, ev, uri, onPick)
            },
            onSetBusy = { viewModel.setShowAddBusyCard(it) }
        )
    }
}

private class HistorySwipeBackState(
    private val isBlocked: Boolean,
    private val onBlocked: () -> Unit,
    private val onBack: () -> Unit
) {
    private var acc = 0f
    private var hintShown = false

    fun reset() {
        acc = 0f
        hintShown = false
    }

    fun onDrag(dragAmount: Float) {
        if (dragAmount == 0f) return
        acc += dragAmount
        if (kotlin.math.abs(acc) <= 160f) return

        acc = 0f
        if (isBlocked) {
            handleBlocked()
        } else if (dragAmount > 0) {
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
