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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(settings: Settings, profile: Profile, onPick: (HistoryEntry) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // Android-Zurück-Button verhält sich wie der On-Screen-Zurückpfeil
    BackHandler(onBack = onBack)
    var entries by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    LaunchedEffect(Unit) { entries = HistoryStore.entries(ctx) }

    // ------------------------------------------------- KI-Tagesfazit-Zustand
    var fazitMap by remember { mutableStateOf<Map<String, DayFazit>>(emptyMap()) }
    val loadingFazits = remember { mutableStateListOf<String>() }
    val expandedFazits = remember { mutableStateListOf<String>() }

    var weekFazit by remember { mutableStateOf<DayFazit?>(null) }
    var loadingWeek by remember { mutableStateOf(false) }
    var expandedWeek by remember { mutableStateOf(false) }

    LaunchedEffect(entries) {
        fazitMap = DayFazitStore.loadAll(ctx).associateBy { it.dayKey }
        weekFazit = syncWeekFazit(ctx, entries)
    }

    // ------------------------------------------------- Nacherfassen (Essen nachtragen für einen Tag)
    var addDialogForDay by remember { mutableStateOf<LocalDate?>(null) }
    var showAddCamera by remember { mutableStateOf(false) }
    var showAddReview by remember { mutableStateOf(false) }
    var showAddText by remember { mutableStateOf(false) }
    var showAddBusyCard by remember { mutableStateOf(false) }
    var addNote by remember { mutableStateOf("") }
    var addTargetDay by remember { mutableStateOf<LocalDate?>(null) }

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
            showAddReview = true
        }
    }
    BackHandler(enabled = showAddCamera) { showAddCamera = false }
    BackHandler(enabled = showAddReview) { showAddReview = false }
    BackHandler(enabled = showAddText) { showAddText = false }

    fun saveBackdatedEntry(ev: Evaluation, photoUriIn: Uri?) {
        val day = addTargetDay ?: return
        val millis = day.atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val entry = HistoryStore.add(
            ctx = ctx, photoUri = photoUriIn, ev = ev,
            note = addNote.trim(), dateOverride = millis, backdated = true
        )
        DayFazitStore.deleteForEntry(ctx, entry)
        WeekFazitStore.deleteForEntry(ctx, entry)
        RecentNotesStore.push(ctx, addNote.trim())
        entries = HistoryStore.entries(ctx)
        showAddCamera = false
        showAddReview = false
        showAddText = false
        showAddBusyCard = false
        addNote = ""
        addTargetDay = null
        onPick(entry)
    }

    fun generateFazit(day: LocalDate, entriesOfDay: List<HistoryEntry>) {
        val key = dayKeyOf(day)
        if (key in loadingFazits) return
        loadingFazits.add(key)
        expandedFazits.add(key)
        val lines = dayFazitMealLines(ctx, entriesOfDay)
        coroutineScope.launch {
            val result = ApiClient.dayFazit(
                settings = settings,
                goals = settings.goals,
                promptContext = profileContextLine(ctx, profile),
                mealLines = lines,
                prompts = createDayFazitPrompts(ctx)
            )
            result.onSuccess { res ->
                DayFazitStore.save(
                    ctx,
                    DayFazit(
                        key, res.ampel, res.text, entriesOfDay.map { it.id },
                        System.currentTimeMillis(), dayFingerprint(entriesOfDay)
                    )
                )
                loadingFazits.remove(key)
                fazitMap = DayFazitStore.loadAll(ctx).associateBy { it.dayKey }
            }.onFailure { e ->
                loadingFazits.remove(key)
                val msg = e.message ?: e.javaClass.simpleName
                Toast.makeText(ctx, ctx.getString(R.string.dayfazit_error_prefix, msg), Toast.LENGTH_LONG).show()
            }
        }
    }

    fun generateWeekFazit() {
        val wk = weekKeyOf(System.currentTimeMillis())
        val wkEntries = entries.filter { dayKeyOf(it.dateMillis) == wk }
        if (wkEntries.isEmpty() || loadingWeek) return
        loadingWeek = true
        expandedWeek = true
        val lines = dayFazitMealLines(ctx, wkEntries)
        coroutineScope.launch {
            val result = ApiClient.dayFazit(
                settings = settings,
                goals = settings.goals,
                promptContext = profileContextLine(ctx, profile),
                mealLines = lines,
                prompts = createWeekFazitPrompts(ctx)
            )
            result.onSuccess { res ->
                val wf = DayFazit(
                    wk, res.ampel, res.text, wkEntries.map { it.id },
                    System.currentTimeMillis(), dayFingerprint(wkEntries)
                )
                WeekFazitStore.save(ctx, wf)
                weekFazit = wf
                loadingWeek = false
            }.onFailure { e ->
                loadingWeek = false
                val msg = e.message ?: e.javaClass.simpleName
                Toast.makeText(ctx, ctx.getString(R.string.dayfazit_error_prefix, msg), Toast.LENGTH_LONG).show()
            }
        }
    }

    HistoryAutoFazitEffect(
        entries = entries,
        fazitMap = fazitMap,
        loadingFazits = loadingFazits,
        weekFazit = weekFazit,
        loadingWeek = loadingWeek,
        onGenerateFazit = { day, list -> generateFazit(day, list) },
        onGenerateWeekFazit = { generateWeekFazit() }
    )

    val anyOverlayOpen = showAddCamera || showAddReview || showAddText || addDialogForDay != null || showAddBusyCard

    Box(
        modifier = Modifier
            .fillMaxSize()
            .historySwipeHandler(
                isBlocked = anyOverlayOpen,
                onBlocked = {
                    Toast.makeText(ctx, ctx.getString(R.string.history_swipe_blocked_overlay), Toast.LENGTH_SHORT).show()
                },
                onBack = onBack
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 20.dp)
        ) {
            HistoryListContent(
                entries = entries,
                fazitMap = fazitMap,
                loadingFazits = loadingFazits,
                expandedFazits = expandedFazits,
                weekFazit = weekFazit,
                loadingWeek = loadingWeek,
                expandedWeek = expandedWeek,
                onBack = onBack,
                onPick = onPick,
                onGenerateFazit = { day, list -> generateFazit(day, list) },
                onGenerateWeekFazit = { generateWeekFazit() },
                onToggleExpandWeek = { expandedWeek = !expandedWeek },
                onAddMealForDay = { day -> addDialogForDay = day },
                onDeleteEntry = { entry ->
                    HistoryStore.delete(ctx, entry)
                    DayFazitStore.deleteForEntry(ctx, entry)
                    WeekFazitStore.deleteForEntry(ctx, entry)
                    entries = HistoryStore.entries(ctx)
                }
            )
        }

        NacherfassenChoiceDialog(
            day = addDialogForDay,
            onDismiss = { addDialogForDay = null },
            onSelectWithPhoto = { targetDay ->
                addTargetDay = targetDay
                addDialogForDay = null
                showAddCamera = true
            },
            onSelectWithoutPhoto = { targetDay ->
                addTargetDay = targetDay
                addDialogForDay = null
                showAddText = true
            }
        )

        if (showAddBusyCard) {
            BackdatedBusyDialog()
        }

        BackdatedOverlays(
            showCamera = showAddCamera,
            showReview = showAddReview,
            showText = showAddText,
            showBusy = showAddBusyCard,
            addPhotoFile = addPhotoFile,
            addPhotoUri = addPhotoUri,
            addNote = addNote,
            addTargetDay = addTargetDay,
            settings = settings,
            onNoteChange = { addNote = it },
            onDismissCamera = { showAddCamera = false },
            onDismissReview = { showAddReview = false },
            onDismissText = { showAddText = false },
            onFallbackSystemCamera = {
                showAddCamera = false
                addSystemCameraLauncher.launch(addPhotoUri)
            },
            onSaveBackdated = { ev, uri -> saveBackdatedEntry(ev, uri) },
            onSetBusy = { showAddBusyCard = it }
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
