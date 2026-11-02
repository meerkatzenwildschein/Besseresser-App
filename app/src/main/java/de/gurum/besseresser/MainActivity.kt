package de.gurum.besseresser

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ----------------------------------------------------------------- Activity & Navigation

/** Reihenfolge für Animationsrichtung: vor = nach rechts tiefer, zurück = nach links höher. */
enum class AppScreen(val order: Int) { Main(0), Profile(1), History(1), Detail(2) }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GesundEssenApp() }
    }
}

@Composable
fun GesundEssenApp() {
    // Folgt dem System-Theme: hell im hellen, dunkel im dunklen Modus
    val darkTheme = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            val ctx = LocalContext.current
            var loaded by remember { mutableStateOf(false) }
            var showSetup by remember { mutableStateOf(true) }
            var settings by remember { mutableStateOf(Settings("", "", "", emptyList())) }
            var showHistory by remember { mutableStateOf(false) }
            var historyEntry by remember { mutableStateOf<HistoryEntry?>(null) }
            var showProfile by remember { mutableStateOf(false) }
            var profile by remember { mutableStateOf(Profile()) }

            LaunchedEffect(Unit) {
                settings = Store.load(ctx)
                profile = ProfileStore.load(ctx)
                showSetup = !settings.isComplete
                loaded = true
            }

            // Einstellungs-Overlay über einem bestehenden Setup: Android-Zurück schließt
            // es (wie der Zurückpfeil). Beim Erst-Setup (unvollständig) bleibt Zurück = App beenden.
            if (loaded && showSetup && settings.isComplete) {
                BackHandler { showSetup = false }
            }

            when {
                !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                showSetup -> SetupScreen(
                    initial = settings,
                    firstRun = !settings.isComplete,
                    onCancel = {
                        if (settings.isComplete) showSetup = false
                        else (ctx as? android.app.Activity)?.finish()
                    },
                    onDone = { neu ->
                        val firstRun = !settings.isComplete
                        Store.save(ctx, neu)
                        settings = neu
                        showSetup = false
                        // Nach dem ERSTEN Erfassen der KI-Zugangsdaten einmalig das Profil öffnen
                        if (firstRun) showProfile = true
                    }
                )
                else -> {
                    val target = when {
                        historyEntry != null -> AppScreen.Detail
                        showProfile -> AppScreen.Profile
                        showHistory -> AppScreen.History
                        else -> AppScreen.Main
                    }
                    AnimatedContent(
                        targetState = target,
                        label = "screen-switch",
                        transitionSpec = {
                            // vorwärts: neuer Screen von rechts; rückwärts: von links
                            val forward = targetState.order > initialState.order
                            if (forward) {
                                slideInHorizontally { it } + fadeIn() togetherWith
                                        slideOutHorizontally { -it / 3 } + fadeOut()
                            } else {
                                slideInHorizontally { -it / 3 } + fadeIn() togetherWith
                                        slideOutHorizontally { it } + fadeOut()
                            }
                        }
                    ) { s ->
                        when (s) {
                            AppScreen.Main -> MainScreen(
                                settings = settings,
                                profile = profile,
                                onEditSettings = { showSetup = true },
                                onOpenProfile = { showProfile = true },
                                onOpenHistory = {
                                    showHistory = true
                                    historyEntry = null
                                }
                            )
                            AppScreen.Profile -> ProfileScreen(
                                initial = profile,
                                onCancel = { showProfile = false },
                                onSave = { p ->
                                    ProfileStore.save(ctx, p.birthDate, p.weightKg, p.heightCm, p.notes)
                                    profile = p
                                    showProfile = false
                                }
                            )
                            AppScreen.History -> HistoryScreen(
                                settings = settings,
                                profile = profile,
                                onPick = { historyEntry = it },
                                onBack = { showHistory = false }
                            )
                            AppScreen.Detail -> {
                                // Wichtig-Pitfall: onBack nullt historyEntry, während der Detail-Screen
                                // als outgoing-Content der Back-Animation NOCH komponiert ist. Ein
                                // `historyEntry!!` direkt im Branch würde beim Re-Compose mit null
                                // crashen. Deshalb: Eintrag einmalig per remember einfrieren.
                                val entry = remember { historyEntry }
                                if (entry != null) {
                                    HistoryDetailScreen(
                                        entry = entry,
                                        onBack = { historyEntry = null }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------- Hauptbildschirm

@Composable
fun MainScreen(settings: Settings, profile: Profile, onEditSettings: () -> Unit, onOpenProfile: () -> Unit, onOpenHistory: () -> Unit) {
    val ctx = LocalContext.current
    var evaluation by remember { mutableStateOf<Evaluation?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var photoPreview by remember { mutableStateOf<Uri?>(null) }
    var lastGoaled by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var savedToHistory by remember { mutableStateOf(false) }
    var savingHistory by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }   // WhatsApp-Stil: Anmerkung zum Foto
    var showReview by remember { mutableStateOf(false) }   // Review-Zweig (Fallback über Systemkamera)
    var showCamera by remember { mutableStateOf(false) }   // Eigene Kamera (CameraX)

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
        if (ok) {
            error = null
            photoPreview = photoUri
            showReview = true
        }
    }

    // Ein frischer Erfassungs-Durchlauf: verwirft Evaluierung, Foto, Anmerkung, Historie-Status
    // und Fehler – genutzt vom fixierten CTA, dem „Neues Foto aufnehmen“-CTA und „Verwerfen“.
    val resetCycle = {
        evaluation = null
        lastGoaled = false
        photoPreview = null
        savedToHistory = false
        error = null
        note = ""
        Unit
    }

    // Android-Zurück-Button: Overlay-Ebenen der Aufnahme schließen, statt die App zu beenden.
    // Gleiche Wirkung wie die On-Screen-Zurückpfeile: Kamera ohne Bewerten verlassen; Review
    // verwirft Foto + Anmerkung + Fehler.
    BackHandler(enabled = showCamera) { showCamera = false }
    BackHandler(enabled = showReview) {
        showReview = false
        photoPreview = null
        note = ""
        error = null
    }

    // ------------------------------------------------- KI-Tagesfazit für „Dein Tag bisher"
    var dayFazit by remember { mutableStateOf<DayFazit?>(null) }
    var dayFazitBusy by remember { mutableStateOf(false) }

    // Beim Speichern in den Verlauf: Tagesfazit für HEUTE ermitteln (Text-Call)
    // und persistieren – die Verlaufs-Liste sieht es dann als frisches Fazit.
    LaunchedEffect(savedToHistory) {
        if (!savedToHistory) return@LaunchedEffect
        dayFazitBusy = true
        dayFazit = null
        val todayKey = dayKeyOf(System.currentTimeMillis())
        val todayEntries = HistoryStore.entries(ctx).filter { dayKeyOf(it.dateMillis) == todayKey }
        val lines = dayFazitMealLines(ctx, todayEntries)
        ApiClient.dayFazit(
            settings = settings,
            goals = settings.goals,
            promptContext = profileContextLine(ctx, profile),
            mealLines = lines,
            promptIntro = ctx.getString(R.string.prompt_dayfazit_intro),
            promptRules = ctx.getString(R.string.prompt_dayfazit_rules),
            promptLanguage = ctx.getString(R.string.prompt_dayfazit_language),
            onOk = { ampel, text ->
                val f = DayFazit(todayKey, ampel, text, todayEntries.map { it.id }, System.currentTimeMillis())
                DayFazitStore.save(ctx, f)
                dayFazit = f
                dayFazitBusy = false
            },
            onError = { msg ->
                Toast.makeText(ctx, ctx.getString(R.string.dayfazit_error_prefix, msg), Toast.LENGTH_SHORT).show()
                dayFazitBusy = false
            }
        )
    }
    BackHandler(enabled = showReview) {
        showReview = false
        photoPreview = null
        note = ""
        error = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Nach links wischen → Verlauf (Schwellwert, kein vertik. Scrollkonflikt).
                // GESPERRT, solange der Durchlauf ungespeichert ist (Bericht offen / Foto-Review /
                // Kamera / KI läuft): der Screen-Wechsel würde den MainScreen-State (Foto, Note,
                // Auswertung) verwerfen. Hinweis-Toast, max. einmal pro Wisch-Geste.
                var acc = 0f
                var hintShown = false
                detectHorizontalDragGestures(
                    onDragEnd = { acc = 0f; hintShown = false },
                    onDragCancel = { acc = 0f; hintShown = false }
                ) { _, dragAmount ->
                    if (dragAmount < 0) {
                        acc += dragAmount
                        val blocked = busy || showCamera || showReview ||
                            (lastGoaled && !savedToHistory)
                        if (acc < -160f) {
                            if (blocked) {
                                if (!hintShown) {
                                    Toast.makeText(
                                        ctx,
                                        ctx.getString(R.string.nav_blocked_hint),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    hintShown = true
                                }
                                acc = 0f
                            } else {
                                acc = 0f
                                onOpenHistory()
                            }
                        }
                    } else {
                        acc = 0f
                    }
                }
            }
    ) {
        if (showCamera) {
            // Eigenes Kamera-Overlay: Aufnahme + Anmerkung + Bewerten/Wiederholen in einem Schritt
            CameraCaptureScreen(
                outputFile = photoFile,
                note = note,
                onNoteChange = { note = it },
                onCancel = { showCamera = false },
                onFallbackSystemCamera = {
                    showCamera = false
                    cameraLauncher.launch(photoUri)
                },
                onConfirm = {
                    showCamera = false
                    note = note.trim()
                    photoPreview = photoUri
                    busy = true
                    error = null
                    val prompts = PromptBundle(
                        intro = ctx.getString(R.string.prompt_eval_intro),
                        formatIntro = ctx.getString(R.string.prompt_eval_format),
                        ampelMeaning = ctx.getString(R.string.prompt_eval_ampel_meaning),
                        exactlyOne = ctx.getString(R.string.prompt_eval_exactly_one),
                        responseLanguage = ctx.getString(R.string.prompt_eval_language),
                        recognized = ctx.getString(R.string.prompt_eval_recognized),
                        noteHeader = if (note.isNotBlank())
                            ctx.getString(R.string.prompt_note_header, note)
                        else ""
                    )
                    ApiClient.analyze(
                        ctx = ctx,
                        photoUri = photoUri,
                        settings = settings,
                        promptBundle = prompts,
                        onResult = {
                            evaluation = it
                            busy = false
                            lastGoaled = true
                        },
                        onError = {
                            error = it
                            busy = false
                        }
                    )
                }
            )
        } else if (showReview) {
            // ------------------------------------------------- „Foto prüfen" direkt nach der Kamera
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TopBarRow(
                    title = stringResource(R.string.review_title),
                    onBack = {
                        showReview = false
                        photoPreview = null
                        note = ""
                        error = null
                    },
                    trailing = { }
                )
                photoPreview?.let { uri ->
                    val bmp = remember(uri) {
                        try {
                            BitmapFactory.decodeStream(ctx.contentResolver.openInputStream(uri))
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
                    ErrorBanner(error ?: "")
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    placeholder = { Text(stringResource(R.string.main_note_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 4
                )
                if (busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                // Platzhalter für die fixierten Buttons
                Spacer(Modifier.height(140.dp))
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        busy = true
                        error = null
                        val prompts = PromptBundle(
                            intro = ctx.getString(R.string.prompt_eval_intro),
                            formatIntro = ctx.getString(R.string.prompt_eval_format),
                            ampelMeaning = ctx.getString(R.string.prompt_eval_ampel_meaning),
                            exactlyOne = ctx.getString(R.string.prompt_eval_exactly_one),
                            responseLanguage = ctx.getString(R.string.prompt_eval_language),
                            recognized = ctx.getString(R.string.prompt_eval_recognized),
                            noteHeader = if (note.isNotBlank())
                                ctx.getString(R.string.prompt_note_header, note.trim())
                            else ""
                        )
                        ApiClient.analyze(
                            ctx = ctx,
                            photoUri = photoUri,
                            settings = settings,
                            promptBundle = prompts,
                            onResult = {
                                evaluation = it
                                busy = false
                                lastGoaled = true
                                showReview = false
                            },
                            onError = {
                                error = it
                                busy = false
                            }
                        )
                    },
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
                    onClick = {
                        // Foto verwerfen und direkt neu fotografieren
                        showReview = false
                        photoPreview = null
                        note = ""
                        cameraLauncher.launch(photoUri)
                    },
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text(stringResource(R.string.main_retake))
                }
            }
        } else {
            // ------------------------------------------------- Normalzustand
            // Zeigt ein Bericht mit Ergebnissen? Dann übernehmen die Inline-Aktionen
            // (Speichern/Verwerfen bzw. „Neues Foto aufnehmen") – der fixierte Foto-CTA
            // wäre redundant und wird ausgeblendet.
            val reportShown = lastGoaled && !evaluation?.goalResults.isNullOrEmpty()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Kopf: Titel links, Burger-Menü rechts
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.main_title), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Filled.Menu,
                                contentDescription = stringResource(R.string.menu_open_description)
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_profile)) },
                                onClick = {
                                    menuOpen = false
                                    onOpenProfile()
                                },
                                leadingIcon = {
                                    // Standard-Tint (LocalContentColor) → passt im Hell/Dark von selbst
                                    Icon(Icons.Filled.Person, contentDescription = null)
                                },
                                // Gesperrt bei ungespeichertem Durchlauf (s. Wisch-Sperre)
                                enabled = !busy && !showCamera && !showReview &&
                                    !(lastGoaled && !savedToHistory)
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_history)) },
                                onClick = {
                                    menuOpen = false
                                    onOpenHistory()
                                },
                                leadingIcon = {
                                    Icon(Icons.Filled.DateRange, contentDescription = null)
                                },
                                // Gesperrt bei ungespeichertem Durchlauf (s. Wisch-Sperre)
                                enabled = !busy && !showCamera && !showReview &&
                                    !(lastGoaled && !savedToHistory)
                            )
                            // Einstellungen bewusst als LETZTES (dauerhafte Konfig, selten geändert)
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_settings)) },
                                onClick = {
                                    menuOpen = false
                                    onEditSettings()
                                },
                                leadingIcon = {
                                    Icon(Icons.Filled.Settings, contentDescription = null)
                                }
                            )
                        }
                    }
                }

                // Foto (angezeugt, sobald bewertet wurde; vor dem Bewerten ist es auf dem Review-Screen)
                if (lastGoaled) photoPreview?.let { uri ->
                    val bmp = remember(uri) {
                        try {
                            BitmapFactory.decodeStream(ctx.contentResolver.openInputStream(uri))
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

                error?.let { msg ->
                    ErrorBanner(msg)
                }

                // Ampeln pro Ziel
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
                        // 🧠 sichtbar, 🍽️/📝 eingeklappt im antippbaren Block
                        CollapsibleSummaryBlock(summary, det, note)
                    }
                    // Manueller Speichern-Button (kein Auto-Save)
                    Button(
                        onClick = {
                            savingHistory = true
                            val ev = evaluation
                            if (ev != null) {
                                val ok = try {
                                    HistoryStore.add(ctx, photoUri, ev, note.trim())
                                    true
                                } catch (e: Exception) { false }
                                savedToHistory = ok
                            }
                            savingHistory = false
                        },
                        enabled = !savingHistory,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) { Text(stringResource(if (savingHistory) R.string.main_saving else R.string.main_save_to_history)) }
                    TextButton(
                        onClick = { resetCycle() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.main_discard)) }
                }
                if (lastGoaled && savedToHistory) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(R.string.main_saved_banner),
                            modifier = Modifier.padding(12.dp),
                            fontSize = 13.sp
                        )
                    }
                    // „Dein Tag bisher": KI-Tagesfazit (wird beim Speichern angestoßen)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (dayFazitBusy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else if (dayFazit != null) {
                                    Box(
                                        Modifier
                                            .size(11.dp)
                                            .clip(CircleShape)
                                            .background(ampelColor(dayFazit!!.ampel))
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.dayfazit_today_title),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            if (dayFazitBusy) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.dayfazit_loading_line),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            dayFazit?.let { f ->
                                Spacer(Modifier.height(4.dp))
                                Text(f.text, fontSize = 13.sp)
                            }
                        }
                    }
                    // Der eigentliche „Neues Foto aufnehmen"-CTA steht fixiert unten
                    // (gleiche Optik/Position wie der Start-CTA) – hier nur der Banner
                }

                // Platzhalter, damit nichts unter dem fixierten Foto-Button versteckt wird
                // (kleiner, wenn der CTA wegen angezeigtem Bericht ausgeblendet ist —
                // AUSNAHME: nach dem Speichern sitzt stattdessen der neue Foto-CTA fixiert unten)
                Spacer(Modifier.height(if (reportShown && !savedToHistory) 24.dp else 100.dp))
            }

            // Zentrierte „KI denkt"-Karte (wie Play-Protect-Ladegetüm): großer Spinner + Text,
            // während der KI-Call im frischen Durchlauf läuft (Bericht ist da ja noch leer/gemischt)
            if (busy && evaluation == null) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp)
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

            // Fixierter „Neues Foto aufnehmen"-CTA nach dem Speichern – gleiche Optik
            // und Daumen-Position wie der Start-CTA (ausgefüllt statt outlined).
            if (lastGoaled && savedToHistory) {
                Button(
                    onClick = {
                    // Frischer Durchlauf UND direkt in die Kamera: nach dem Speichern ist
                    // „das nächste Essen fotografieren“ der einzig sinnvolle nächste Schritt
                    resetCycle()
                    showCamera = true
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                    Text(stringResource(R.string.main_new_photo), fontSize = 17.sp)
                }
            } else if (!reportShown) {
                Button(
                onClick = {
                    // Frischer Durchlauf: alte Anmerkung + altes Ergebnis verwerfen,
                    // damit Kamera-Note leer ist und die Ampeln grau wie beim Erststart sind
                    resetCycle()
                    showCamera = true
                },
                enabled = !busy,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                if (busy) {
                    // M3-Muster: indeterminierter Spinner IM Button – der Fortschritt ist
                    // sofort sichtbar, während der Text „KI bewertet …" daneben steht
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
    }
}

/** Roter Fehler-Hinweisblock – Material-Farben, identisch in Review/Main/Profil. */
@Composable
fun ErrorBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            stringResource(R.string.error_prefix, message),
            modifier = Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

/**
 * Zusammenfassungs-Block: 🧠 Gesamteindruck immer sichtbar; 🍽️ „Erkannt“ und 📝 Anmerkung
 * eingeklappt hinter einem Toggle (der ganze Block antippbar = großes Touch-Ziel), Chevron
 * rotiert mit, animateContentSize animiert das Auf-/ Zuklappen. Nutzt Auswertung UND
 * Historie-Detail – bewusst identisch verhalten.
 */
@Composable
fun CollapsibleSummaryBlock(summary: String, erkannt: String, note: String) {
    val ctx = LocalContext.current
    val lines = buildList {
        if (erkannt.isNotBlank()) add(R.string.detected_prefix to erkannt)
        if (note.isNotBlank()) add(R.string.history_note_prefix to note)
    }
    var expanded by remember { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")

    Surface(
        onClick = { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .animateContentSize()
        ) {
            if (summary.isNotBlank()) {
                Text(stringResource(R.string.summary_prefix, summary))
            }
            if (expanded) {
                if (summary.isNotBlank()) Spacer(Modifier.height(4.dp))
                lines.forEach { (prefixRes, text) ->
                    Text(stringResource(prefixRes, text))
                }
            }
            if (lines.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (expanded)
                            stringResource(R.string.details_toggle_less)
                        else ctx.resources.getQuantityString(R.plurals.details_toggle_more, lines.size, lines.size),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.rotate(chevron)
                    )
                }
            }
        }
    }
}

@Composable
fun GoalRow(goal: String, res: GoalResult?) {
    val color = ampelColor(res?.ampel ?: "unbekannt")
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(color, CircleShape)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(goal, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                if (res != null) {
                    Text(
                        "${ampelLabel(res.ampel)} – ${res.begruendung}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(stringResource(R.string.main_no_rating_yet), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
