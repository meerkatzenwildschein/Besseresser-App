package de.gurum.besseresser

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

// ----------------------------------------------------------------- Historie-Screen (Liste)

/** Ein Kalendertag mit allen gespeicherten Bewertungen desselben Tages. */
private data class DayGroup(
    val day: LocalDate,
    val entries: List<HistoryEntry>
)

/** Mahlzeiten-Zeilen für den Tagesfazit-Prompt: „HH:mm · Erkanntes mit Menge · Anmerkung“;
 *  nachgetragene Einträge ohne echte Uhrzeit → Label „nachträglich erfasst“. */
fun dayFazitMealLines(ctx: android.content.Context, entriesOfDay: List<HistoryEntry>): List<String> =
    entriesOfDay.sortedBy { it.dateMillis }.map { e ->
        val time = if (e.backdated)
            ctx.getString(R.string.nacherfassen_label)
        else
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(e.dateMillis))
        val recog = e.erkannt.ifBlank { ctx.getString(R.string.dayfazit_line_none) }
        var line = "$time · $recog"
        e.note.takeIf { it.isNotBlank() }
            ?.let { line += " · " + ctx.getString(R.string.dayfazit_line_note, it.trim()) }
        line
    }

/** Auto-Fazits starten erst ab dieser Uhrzeit (Stunde, lokal) – davor nur manueller Chip. */
private const val FAZIT_AUTO_HOUR = 18

/** Nach Kalendertag gruppieren (absteigend); Einträge bleiben innerhalb der Gruppe absteigend. */private fun groupByDay(entries: List<HistoryEntry>): List<DayGroup> =
    entries.groupBy {
        Instant.ofEpochMilli(it.dateMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    }.map { (day, list) -> DayGroup(day = day, entries = list) }
    .sortedByDescending { it.day }

@Composable
fun HistoryScreen(settings: Settings, profile: Profile, onPick: (HistoryEntry) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    // Android-Zurück-Button verhält sich wie der On-Screen-Zurückpfeil
    BackHandler(onBack = onBack)
    var entries by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    LaunchedEffect(Unit) { entries = HistoryStore.entries(ctx) }

    // ------------------------------------------------- KI-Tagesfazit-Zustand
    var fazitMap by remember { mutableStateOf<Map<String, DayFazit>>(emptyMap()) }
    val loadingFazits = remember { mutableStateListOf<String>() }
    var expandedFazits = remember { mutableStateListOf<String>() }

    var weekFazit by remember { mutableStateOf<DayFazit?>(null) }
    var loadingWeek by remember { mutableStateOf(false) }
    var expandedWeek by remember { mutableStateOf(false) }

    LaunchedEffect(entries) {
        fazitMap = DayFazitStore.loadAll(ctx).associateBy { it.dayKey }
        // Wochenfazit: vorhanden? Bei geänderten Wochen-Einträgen (Fingerprint) LÖSCHEN
        val wk = weekKeyOf(System.currentTimeMillis())
        val wf = WeekFazitStore.load(ctx, wk)
        if (wf != null && wf.fingerprint != dayFingerprint(entries.filter { dayKeyOf(it.dateMillis) == wk })) {
            WeekFazitStore.delete(ctx, wk)
            weekFazit = null
        } else {
            weekFazit = wf
        }
    }

    // ------------------------------------------------- Nacherfassen (Essen nachtragen für einen Tag)
    var addDialogForDay by remember { mutableStateOf<LocalDate?>(null) }
    var addCaptureWithPhoto by remember { mutableStateOf(false) }   // false = nur Text (Pflichtfeld)
    var showAddCamera by remember { mutableStateOf(false) }
    var showAddReview by remember { mutableStateOf(false) }         // Foto-Prüfen-Zweig (Fallback + eigener Fluss)
    var showAddText by remember { mutableStateOf(false) }           // Ohne-Foto-Zweig
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

    /** Speichern eines nachgetragenen Essens: Tagesfazit + Wochenfazit werden VERWORFEN
     *  (dort neu nur manuell per Chip bzw. Auto-Regel ab 18 Uhr), Eintrag → Detailansicht. */
    fun saveBackdatedEntry(ev: Evaluation, photoUriIn: Uri?) {
        val day = addTargetDay ?: return
        val millis = day.atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val entry = HistoryStore.add(
            ctx = ctx, photoUri = photoUriIn, ev = ev,
            note = addNote.trim(), dateOverride = millis, backdated = true
        )
        DayFazitStore.deleteForEntry(ctx, entry)   // Tagesfazit KOMPLETT verworfen (nicht „veraltet“)
        WeekFazitStore.deleteForEntry(ctx, entry)  // 3.2: Wochenfazit ebenfalls verworfen
        RecentNotesStore.push(ctx, addNote.trim())
        entries = HistoryStore.entries(ctx)
        // Overlay-Zustände schließen
        showAddCamera = false
        showAddReview = false
        showAddText = false
        showAddBusyCard = false
        addNote = ""
        addTargetDay = null
        onPick(entry)   // Detailansicht des neu erfassten Essens (Zurück → Liste → Hauptbildschirm)
    }

    fun generateFazit(day: LocalDate, entriesOfDay: List<HistoryEntry>) {
        val key = dayKeyOf(day)
        if (key in loadingFazits) return
        loadingFazits.add(key)
        expandedFazits.add(key)   // während/neu nach dem Laden direkt anzeigen
        val lines = dayFazitMealLines(ctx, entriesOfDay)
        ApiClient.dayFazit(
            settings = settings,
            goals = settings.goals,
            promptContext = profileContextLine(ctx, profile),
            mealLines = lines,
            promptIntro = ctx.getString(R.string.prompt_dayfazit_intro),
            promptRules = ctx.getString(R.string.prompt_dayfazit_rules),
            promptLanguage = ctx.getString(R.string.prompt_dayfazit_language),
            promptHonesty = ctx.getString(R.string.prompt_honesty),
            onOk = { ampel, text ->
                DayFazitStore.save(
                    ctx,
                    DayFazit(
                        key, ampel, text, entriesOfDay.map { it.id },
                        System.currentTimeMillis(), dayFingerprint(entriesOfDay)
                    )
                )
                loadingFazits.remove(key)
                fazitMap = DayFazitStore.loadAll(ctx).associateBy { it.dayKey }
            },
            onError = { msg ->
                Toast.makeText(ctx, ctx.getString(R.string.dayfazit_error_prefix, msg), Toast.LENGTH_SHORT).show()
                loadingFazits.remove(key)
            }
        )
    }

    fun generateWeekFazit() {
        if (loadingWeek) return
        val wk = weekKeyOf(System.currentTimeMillis())
        val entriesOfWeek = entries.filter { dayKeyOf(it.dateMillis) == wk }
        if (entriesOfWeek.isEmpty()) return
        loadingWeek = true
        expandedWeek = true
        ApiClient.dayFazit(
            settings = settings,
            goals = settings.goals,
            promptContext = profileContextLine(ctx, profile),
            mealLines = dayFazitMealLines(ctx, entriesOfWeek),
            promptIntro = ctx.getString(R.string.prompt_weekfazit_intro),
            promptRules = ctx.getString(R.string.prompt_dayfazit_rules),
            promptLanguage = ctx.getString(R.string.prompt_dayfazit_language),
            promptHonesty = ctx.getString(R.string.prompt_honesty),
            onOk = { ampel, text ->
                WeekFazitStore.save(
                    ctx,
                    DayFazit(
                        wk, ampel, text, entriesOfWeek.map { it.id },
                        System.currentTimeMillis(), dayFingerprint(entriesOfWeek)
                    )
                )
                loadingWeek = false
                weekFazit = WeekFazitStore.load(ctx, wk)
            },
            onError = { msg ->
                Toast.makeText(ctx, ctx.getString(R.string.dayfazit_error_prefix, msg), Toast.LENGTH_SHORT).show()
                loadingWeek = false
            }
        )
    }

    // Auto-Logik (nur die beiden neuesten Tagesgruppen):
    //  • kein Fazit     → 1× pro Tag & App-Session automatisch (Ersatztext „KI bewertet den Tag …")
    //  • Fazit veraltet → automatisch neu bewerten (veraltet = Entry-IDs/Fingerprint weichen ab)
    //  • ältere Gruppen nur manuell per Chip-Tap
    //  • AUTO erst ab 18 Uhr (`FAZIT_AUTO_HOUR`): verhindert Frühstücks-Fazits; manuell (Chip)
    //    geht zu JEDER Zeit
    LaunchedEffect(entries, fazitMap) {
        if (entries.isEmpty()) return@LaunchedEffect
        delay(1200)   // Debounce: schnelles Löschen mehrerer Einträge löst nur einen Call aus
        val autoFazitTime = LocalTime.now().hour >= FAZIT_AUTO_HOUR
        groupByDay(entries).take(2).forEach { group ->
            val key = dayKeyOf(group.day)
            if (key in loadingFazits) return@forEach
            val fazit = fazitMap[key]
            if (fazit == null) {
                if (autoFazitTime && key !in DayFazitSession.attempted) {
                    DayFazitSession.attempted.add(key)
                    generateFazit(group.day, group.entries)
                }
            } else if (autoFazitTime && (
                    fazit.entryIds.toSet() != group.entries.map { it.id }.toSet() ||
                        (fazit.fingerprint.isNotBlank() && fazit.fingerprint != dayFingerprint(group.entries))
                    )
            ) {
                generateFazit(group.day, group.entries)
            }
        }
        // Wochenfazit: auto NUR Sonntag ≥ 18 Uhr, 1× pro Woche & Session (sonst Chip-Tipp)
        val today = LocalDate.now()
        if (autoFazitTime && today.dayOfWeek == java.time.DayOfWeek.SUNDAY && weekFazit == null) {
            if (!loadingWeek) {
                val wk = weekKeyOf(System.currentTimeMillis())
                if (wk !in DayFazitSession.attemptedWeekly) {
                    DayFazitSession.attemptedWeekly.add(wk)
                    generateWeekFazit()
                }
            }
        }
    }

    // Root als Box: Die Nacherfassen-Overlays (Kamera/Review/Text) liegen als Geschwister
    // ÜBER der Liste — Gesten & Scroll der Liste können sie nicht durchdringen.
    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Nach rechts wischen → zurück zum Hauptbildschirm (SPERRE während
                // der Nachtrage-Erfassung — Wisch-Eingaben werden ignoriert, Toast-Hinweis)
                var acc = 0f
                var hintShown = false
                detectHorizontalDragGestures(
                    onDragEnd = { acc = 0f; hintShown = false },
                    onDragCancel = { acc = 0f; hintShown = false }
                ) { _, dragAmount ->
                    val overlayOpen = showAddCamera || showAddReview || showAddText || showAddBusyCard
                    if (dragAmount != 0f) {
                        acc += dragAmount
                        if (kotlin.math.abs(acc) > 160f) {
                            if (overlayOpen) {
                                if (!hintShown) {
                                    Toast.makeText(
                                        ctx,
                                        ctx.getString(R.string.nacherfassen_block_hint),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    hintShown = true
                                }
                                acc = 0f
                            } else if (dragAmount > 0) {
                                acc = 0f
                                onBack()
                            } else {
                                acc = 0f
                            }
                        }
                    }
                }
            }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Verlauf-Liste nur zeigen, wenn KEIN Nachtrage-Overlay aktiv ist — sonst dieser
        // Screen + Leiste unter dem Overlay durchscheinen (Doppelverter „Verlauf“ oben)
        if (!showAddCamera && !showAddReview && !showAddText) {
            TopBarRow(
                title = stringResource(R.string.history_title),
                onBack = onBack,
                trailing = { },
                icon = Icons.Filled.DateRange
            )

            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.history_empty),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    stringResource(R.string.history_list_hint, entries.size),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    groupByDay(entries).forEach { group ->
                        val dayKey = dayKeyOf(group.day)
                        val fazit = fazitMap[dayKey]
                    val loadingFazit = dayKey in loadingFazits
                    val staleFazit = fazit != null && (
                        fazit.entryIds.toSet() != group.entries.map { it.id }.toSet() ||
                            // geänderte erkannt-Texte (Korrekturen) machen das Fazit ebenfalls veraltet
                            (fazit.fingerprint.isNotBlank() && fazit.fingerprint != dayFingerprint(group.entries))
                        )
                    item(key = "day-$dayKey") {
                        DayGroupHeader(
                            date = group.day,
                            fazit = fazit,
                            loading = loadingFazit,
                            stale = staleFazit,
                            expanded = dayKey in expandedFazits,
                            onToggleExpand = {
                                if (dayKey in expandedFazits) expandedFazits.remove(dayKey)
                                else expandedFazits.add(dayKey)
                            },
                            onGenerate = { generateFazit(group.day, group.entries) },
                            onAddMeal = { addDialogForDay = group.day }
                        )
                        when {
                            loadingFazit -> Text(
                                stringResource(R.string.dayfazit_loading_line),
                                fontSize = 11.sp,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                            // Veraltet: bewusst KEINE gedimmte Alt-Fassung mehr zeigen
                            // (nicht verständlich, warum sie noch da steht) – nur der
                            // „⟳ Neu bewerten"-Chip macht den Zustand kenntlich
                            fazit != null && !staleFazit && dayKey in expandedFazits -> Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    fazit.text,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                    group.entries.forEach { entry ->
                        item(key = entry.id) {
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    if (value == SwipeToDismissBoxValue.EndToStart) {
                                        entries = entries.filterNot { it.id == entry.id }
                                        HistoryStore.delete(ctx, entry)
                                        true
                                    } else false
                                }
                            )
                            SwipeToDismissBox(
                                state = dismissState,
                                enableDismissFromStartToEnd = false,
                                backgroundContent = {
                                    Box(
                                        modifier = Modifier
                                            // Gleiche Rundung wie die Karte, sonst schimmert Rot an den Ecken
                                            .clip(RoundedCornerShape(12.dp))
                                            .fillMaxSize()
                                            .background(Color(0xFFD32F2F)),
                                        contentAlignment = Alignment.CenterEnd
                                    ) {
                                        Text(
                                            stringResource(R.string.history_delete_action),
                                            color = Color.White,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 24.dp)
                                        )
                                    }
                                }
                            ) {
                                HistoryItem(entry, onClick = { onPick(entry) })
                            }
                        }
                    }
                }

                // Wochenfazit-Zeile: NUR am Sonntag ab 18 Uhr sichtbar (gleiche Chip-Sprache
                // wie die Tagesfazit-Köpfe); bei Wochen-Änderungen wird das Fazit gelöscht
                if (LocalDate.now().dayOfWeek == java.time.DayOfWeek.SUNDAY &&
                    LocalTime.now().hour >= FAZIT_AUTO_HOUR
                ) {
                    item(key = "week") {
                        WeekFazitRow(
                            fazit = weekFazit,
                            loading = loadingWeek,
                            expanded = expandedWeek,
                            onToggleExpand = { expandedWeek = !expandedWeek },
                            onGenerate = { generateWeekFazit() }
                        )
                    }
                }
            }
            }   // if (!overlayOpen) — Verlauf-Liste nur ohne Overlay
        }

        // „KI denkt"-Karte während der Nachtrage-Bewertung (identische Optik wie der
        // Hauptpfad): zentrierter Dialog-Kardiroller mit großem Spinner, NICHT schließbar
        if (showAddBusyCard) {
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

        // Nachtrage-Dialog: Mit Foto / Ohne Foto (für die + Taste je Tagesleiste)
        addDialogForDay?.let { day ->
            AlertDialog(
                onDismissRequest = { addDialogForDay = null },
                title = { Text(stringResource(R.string.nacherfassen_title)) },
                text = {
                    Text(
                        stringResource(R.string.nacherfassen_text) + "\n" +
                            day.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        addTargetDay = day
                        addDialogForDay = null
                        addNote = ""
                        showAddCamera = true
                    }) { Text(stringResource(R.string.nacherfassen_with_photo)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        addTargetDay = day
                        addDialogForDay = null
                        addNote = ""
                        showAddText = true
                    }) { Text(stringResource(R.string.nacherfassen_without_photo)) }
                }
            )
        }
    }

    // ---------------------------------------------------------------- Nacherfassen-Overlays
    // Als Geschwister ÜBER der Liste (Box-Root): Die Wisch-Gesten des Listen-Layouts können
    // die Erfassungsscreens nicht mehr durchdringen (Fix: nach links wischen während
    // „Mit Foto"/„Ohne Foto" warf den Nutzer ungewollt zurück in die Liste).
    if (showAddCamera) {
        CameraCaptureScreen(
            outputFile = addPhotoFile,
            note = addNote,
            onNoteChange = { addNote = it },
            onCancel = { showAddCamera = false },
            onFallbackSystemCamera = {
                showAddCamera = false
                addSystemCameraLauncher.launch(addPhotoUri)
            },
            onConfirm = {
                showAddCamera = false
                showAddBusyCard = true
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
                ApiClient.analyze(
                    ctx = ctx,
                    photoUri = addPhotoUri,
                    settings = settings,
                    promptBundle = prompts,
                    onResult = {
                        showAddBusyCard = false
                        saveBackdatedEntry(it, addPhotoUri)
                    },
                    onError = { msg ->
                        showAddBusyCard = false
                        Toast.makeText(ctx, ctx.getString(R.string.error_prefix, msg), Toast.LENGTH_LONG).show()
                    }
                )
            }
        )
    }
    if (showAddReview) {
        AddMealReviewScreen(
            photoUri = addPhotoUri,
            note = addNote,
            onNoteChange = { addNote = it },
            onBack = { showAddReview = false },
            busy = showAddBusyCard,
            onRate = {
                showAddBusyCard = true
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
                ApiClient.analyze(
                    ctx = ctx,
                    photoUri = addPhotoUri,
                    settings = settings,
                    promptBundle = prompts,
                    onResult = {
                        showAddBusyCard = false
                        saveBackdatedEntry(it, addPhotoUri)
                    },
                    onError = { msg ->
                        showAddBusyCard = false
                        Toast.makeText(ctx, ctx.getString(R.string.error_prefix, msg), Toast.LENGTH_LONG).show()
                    }
                )
            }
        )
    }
    if (showAddText) {
        AddMealNoPhotoScreen(
            day = addTargetDay,
            note = addNote,
            onNoteChange = { addNote = it },
            onBack = { showAddText = false },
            busy = showAddBusyCard,
            onSend = {
                showAddBusyCard = true
                ApiClient.analyzeText(
                    ctx = ctx,
                    settings = settings,
                    userDescription = addNote.trim(),
                    onResult = {
                        showAddBusyCard = false
                        saveBackdatedEntry(it, null)
                    },
                    onError = { msg ->
                        showAddBusyCard = false
                        Toast.makeText(ctx, ctx.getString(R.string.error_prefix, msg), Toast.LENGTH_LONG).show()
                    }
                )
            }
        )
    }
    }   // Box-Root
}

/** Wochenfazit-Zeile: Label links, Chip rechts (idle/loading/ready-farbig); kein „veraltet"-
 *  Zustand – Wochen-Änderungen löschen das Fazit stattdessen (Chip → idle). */
@Composable
private fun WeekFazitRow(
    fazit: DayFazit?,
    loading: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onGenerate: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.weekfazit_row_label),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            when {
                loading -> HeadChip(
                    label = stringResource(R.string.weekfazit_chip_loading),
                    onClick = null,
                    leading = {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
                fazit == null -> HeadChip(
                    label = stringResource(R.string.weekfazit_chip_idle),
                    onClick = onGenerate
                )
                else -> HeadChip(
                    label = stringResource(R.string.weekfazit_chip_label),
                    onClick = onToggleExpand,
                    accent = ampelColor(fazit.ampel),
                    leading = { Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White)) },
                    trailing = {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp).rotate(if (expanded) 180f else 0f)
                        )
                    }
                )
            }
        }
        when {
            loading -> Text(
                stringResource(R.string.weekfazit_loading_line),
                fontSize = 11.sp,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
            fazit != null && expanded -> Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(fazit.text, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
            }
        }
    }
}

/** Kopf-Chip (Tages-/Wochenfazit): 40dp hoch — exakt so hoch wie der „+“-Button —,
 *  zentrierter Inhalt, größere Schrift (13sp). accent != null → farbiger Ampel-Chip
 *  mit weißem Inhalt; sonst grau. onClick = null → nicht tappbar (Loading). */
@Composable
private fun HeadChip(
    label: String,
    onClick: (() -> Unit)?,
    accent: Color? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val shape = RoundedCornerShape(20.dp)
    val content: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(40.dp)
                .padding(horizontal = 14.dp)
        ) {
            leading?.invoke()
            if (leading != null) Spacer(Modifier.width(6.dp))
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = if (accent != null) FontWeight.Bold else FontWeight.SemiBold,
                color = if (accent != null) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (trailing != null) Spacer(Modifier.width(4.dp))
            trailing?.invoke()
        }
    }
    Surface(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        shape = shape,
        color = accent ?: MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.height(40.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) { content() }
    }
}

/** Nachtrage-Zweig MIT Foto (Systemkamera-Fallback): Foto prüfen + Anmerkung → bewerten.
 *  Kein Abschluss-/Speichern-Screen: das Ergebnis geht direkt ins Tagesdatum.
 *  OPAKER Hintergrund, damit die Verlaufs-Liste darunter nicht durchscheint. */
@Composable
private fun AddMealReviewScreen(
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
                    BitmapFactory.decodeStream(reviewCtx.contentResolver.openInputStream(photoUri))
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
                // Mit der Tastatur mitfahren (Notiz feld + fixierter Button kollidieren sonst)
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
private fun AddMealNoPhotoScreen(
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
                (day?.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) ?: "") +
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

/** Kopfzeile einer Tagesgruppe: Datum links, Tagesfazit-Chip rechts (Zustandsmaschine);
 *  „+"-Taste zum Nachtragen eines Essens für genau diesen Tag. */
@Composable
private fun DayGroupHeader(
    date: LocalDate,
    fazit: DayFazit?,
    loading: Boolean,
    stale: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onGenerate: () -> Unit,
    onAddMeal: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        if (onAddMeal != null) {
            // Markanter als das alte 28dp-Bare-Icon: tonal gefüllter Kreis-Button (40dp-Tappfläche,
            // primärer + Glyph) mit klarem Abstand zum Fazit-Chip – kein Fehl-Tapp aufs Pendant
            FilledTonalIconButton(
                onClick = onAddMeal,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.nacherfassen_title),
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(6.dp))
        }
        when {
            loading -> HeadChip(
                label = stringResource(R.string.dayfazit_chip_loading),
                onClick = null,
                leading = {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
            fazit == null -> HeadChip(
                label = stringResource(R.string.dayfazit_chip_idle),
                onClick = onGenerate
            )
            stale -> HeadChip(
                label = stringResource(R.string.dayfazit_chip_stale),
                onClick = onGenerate,
                leading = {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
            )
            else -> HeadChip(
                label = stringResource(R.string.dayfazit_chip_label),
                onClick = onToggleExpand,
                accent = ampelColor(fazit.ampel),
                leading = { Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White)) },
                trailing = {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp).rotate(if (expanded) 180f else 0f)
                    )
                }
            )
        }
    }
}

@Composable
private fun HistoryItem(entry: HistoryEntry, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        // Deckkraft voll: sonst scheint der rote Swipe-Loesch-Hintergrund dauerhaft durch
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail (klein skaliert decodiert)
            val thumb = remember(entry.id) {
                HistoryStore.photoFileOf(ctx, entry)?.let { decodeThumb(it) }
            }
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = stringResource(R.string.photo_of_meal),
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) { Text("📷", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (entry.backdated)
                        stringResource(R.string.nacherfassen_label)   // keine echte Uhrzeit bekannt
                    else
                        SimpleDateFormat("HH:mm", Locale.getDefault())
                            .format(Date(entry.dateMillis)),
                    fontSize = if (entry.backdated) 12.sp else 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontStyle = if (entry.backdated) FontStyle.Italic else FontStyle.Normal
                )
                if (entry.erkannt.isNotBlank()) {
                    Text(
                        stringResource(R.string.detected_prefix, entry.erkannt),
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    zielePreview(
                        entry,
                        stringResource(R.string.history_no_goals),
                        stringResource(
                            R.string.goals_preview_more,
                            (entry.goalResults.size - 2).coerceAtLeast(0)
                        )
                    ),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ScoreBadge(entry)
        }
    }
}

private fun zielePreview(entry: HistoryEntry, emptyText: String, moreText: String): String {
    val list = entry.goalResults.map { it.goal }
    return when {
        list.isEmpty() -> emptyText
        list.size <= 2 -> list.joinToString(", ")
        else -> list.take(2).joinToString(", ") + moreText
    }
}

/** Farbskala für Ampel-Scores (auch der Tagesscore): grau = nichts bewertbar, sonst Ampel. */
private fun scoreColor(score: Int): Color = when {
    score < 0 -> ampelColor("unbekannt")
    score >= 80 -> ampelColor("gruen")
    score >= 50 -> ampelColor("gelb")
    else -> ampelColor("rot")
}

/** Farbiges Kreis-Badge mit Score (Long-Press-Erklärung via Tooltip). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScoreCircle(score: Int, tooltipRes: Int) {
    val tooltipState = rememberTooltipState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        state = tooltipState,
        tooltip = { PlainTooltip { Text(stringResource(tooltipRes)) } }
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(scoreColor(score))
                .padding(3.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (score >= 0) "$score" else "–",
                fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold
            )
        }
    }
}

/** Ampel-Score als farbiger Kreis + Bezeichnung; Long-Press erklärt die Berechnung. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreBadge(entry: HistoryEntry) {
    val score = entry.score
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ScoreCircle(
            score = score,
            tooltipRes = if (score >= 0) R.string.score_tooltip_ok
                         else R.string.score_tooltip_unknown
        )
        Text(stringResource(R.string.score_caption), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ----------------------------------------------------------------- Detail-Screen

@Composable
fun HistoryDetailScreen(entry: HistoryEntry, settings: Settings, onBack: () -> Unit) {
    val ctx = LocalContext.current
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
        if (photoUri != null) {
            ApiClient.analyze(
                ctx = ctx,
                photoUri = photoUri,
                settings = settings,
                promptBundle = PromptBundle(   // korrigierte Erkennung als Nutzer-Information in den Prompt
                    intro = ctx.getString(R.string.prompt_eval_intro),
                    formatIntro = ctx.getString(R.string.prompt_eval_format),
                    ampelMeaning = ctx.getString(R.string.prompt_eval_ampel_meaning),
                    exactlyOne = ctx.getString(R.string.prompt_eval_exactly_one),
                    responseLanguage = ctx.getString(R.string.prompt_eval_language),
                    recognized = ctx.getString(R.string.prompt_eval_recognized),
                    honesty = ctx.getString(R.string.prompt_honesty),
                    noteHeader = ctx.getString(R.string.prompt_reeval_header, correctedText)
                ),
                onResult = onDone,
                onError = onFail
            )
        } else {
            ApiClient.analyzeText(
                ctx = ctx,
                settings = settings,
                userDescription = correctedText,
                onResult = onDone,
                onError = onFail
            )
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
                SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(current.dateMillis)) +
                    " · " + stringResource(R.string.nacherfassen_label)
            else
                SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(current.dateMillis)),
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
                if (newText.isNotBlank() && !reEvalBusy) reEvaluate(newText)   // 1: KI bewertet Korrektur erneut
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
