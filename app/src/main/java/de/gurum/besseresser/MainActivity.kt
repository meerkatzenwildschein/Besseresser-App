package de.gurum.besseresser

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
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
enum class AppScreen(val order: Int) { Main(0), History(1), Detail(2) }

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

            LaunchedEffect(Unit) {
                settings = Store.load(ctx)
                showSetup = !settings.isComplete
                loaded = true
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
                        Store.save(ctx, neu)
                        settings = neu
                        showSetup = false
                    }
                )
                else -> {
                    val target = when {
                        historyEntry != null -> AppScreen.Detail
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
                                onEditSettings = { showSetup = true },
                                onOpenHistory = {
                                    showHistory = true
                                    historyEntry = null
                                }
                            )
                            AppScreen.History -> HistoryScreen(
                                onPick = { historyEntry = it },
                                onBack = { showHistory = false }
                            )
                            AppScreen.Detail -> HistoryDetailScreen(
                                entry = historyEntry!!,
                                onBack = { historyEntry = null }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------- Hauptbildschirm

@Composable
fun MainScreen(settings: Settings, onEditSettings: () -> Unit, onOpenHistory: () -> Unit) {
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Nach links wischen → Historie (Schwellwert, kein vertik. Scrollkonflikt)
                var acc = 0f
                detectHorizontalDragGestures(
                    onDragEnd = { acc = 0f },
                    onDragCancel = { acc = 0f }
                ) { _, dragAmount ->
                    if (dragAmount < 0) {
                        acc += dragAmount
                        if (acc < -160f) {
                            acc = 0f
                            onOpenHistory()
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
                        userNote = note,
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
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(R.string.error_prefix, error ?: ""),
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
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
                            userNote = note.trim(),
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
                                text = { Text(stringResource(R.string.menu_settings)) },
                                onClick = {
                                    menuOpen = false
                                    onEditSettings()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_history)) },
                                onClick = {
                                    menuOpen = false
                                    onOpenHistory()
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
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(R.string.error_prefix, msg),
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
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
                    if (summary.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(stringResource(R.string.summary_prefix, summary))
                                evaluation?.erkannt?.takeIf { it.isNotBlank() }?.let { det ->
                                    Spacer(Modifier.height(4.dp))
                                    Text(stringResource(R.string.detected_prefix, det))
                                }
                            }
                        }
                    } else {
                        // Kein Gesamteindruck, aber Erkennung -> Block trotzdem zeigen
                        val det = evaluation?.erkannt.orEmpty()
                        if (det.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.detected_prefix, det), modifier = Modifier.padding(12.dp))
                            }
                        }
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
                        onClick = {
                            evaluation = null; lastGoaled = false; photoPreview = null
                            savedToHistory = false; note = ""
                        },
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
                    OutlinedButton(
                        onClick = {
                            evaluation = null; lastGoaled = false; photoPreview = null
                            savedToHistory = false; note = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.main_new_photo)) }
                }

                // Platzhalter, damit nichts unter dem fixierten Foto-Button versteckt wird
                Spacer(Modifier.height(100.dp))
            }

            // Fixierter Kamera-CTA unten
            Button(
                onClick = {
                    // Frischer Durchlauf: alte Anmerkung + altes Ergebnis verwerfen,
                    // damit Kamera-Note leer ist und die Ampeln grau wie beim Erststart sind
                    evaluation = null
                    lastGoaled = false
                    photoPreview = null
                    savedToHistory = false
                    error = null
                    note = ""
                    showCamera = true
                },
                enabled = !busy,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    stringResource(
                        if (busy) R.string.main_ai_working else R.string.main_take_photo
                    ),
                    fontSize = 17.sp
                )
            }
        }
    }
}

@Composable
fun GoalRow(goal: String, res: GoalResult?) {
    val color = if (res == null) Color(0xFF9E9E9E) else ampelColor(res.ampel)
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
