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
import androidx.compose.material.icons.filled.Create
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


// ----------------------------------------------------------------- Hauptbildschirm (Kamera/Review/Auswertung)

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
    var showErkanntEdit by remember { mutableStateOf(false) }
    var savedEntryId by remember { mutableStateOf<Long?>(null) }

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
        savedEntryId = null
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
                    RecentNotesStore.push(ctx, note)   // Notiz in den „Letzte 5"-Schaukasten
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
                    trailingIcon = {
                        // Letzte 5 Eingaben (Müsli-morgens-Komfort): Auswahl rückt nach oben
                        RecentNotesButton(onPick = { note = it })
                    },
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
                        RecentNotesStore.push(ctx, note.trim())   // Notiz in den „Letzte 5"-Schaukasten
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
                        // 🧠 sichtbar, 🍽️/📝 eingeklappt im antippbaren Block;
                        // 🍽️-Zeile bekommt den Pencil (Korrektur der KI-Erkennung)
                        CollapsibleSummaryBlock(
                            summary = summary,
                            erkannt = det,
                            note = note,
                            onEditErkannt = {
                                if (det.isNotBlank()) showErkanntEdit = true
                            }
                        )
                    }
                    // Manueller Speichern-Button (kein Auto-Save)
                    Button(
                        onClick = {
                            savingHistory = true
                            val ev = evaluation
                            if (ev != null) {
                                val stored = try {
                                    HistoryStore.add(ctx, photoUri, ev, note.trim())
                                } catch (e: Exception) { null }
                                savedToHistory = stored != null
                                savedEntryId = stored?.id
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
                    // „Zum Verlauf" (statt Banner): Direkter Sprung zur Tagesübersicht
                    OutlinedButton(
                        onClick = { onOpenHistory() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) { Text(stringResource(R.string.main_open_history)) }
                    // Der eigentliche „Neues Foto aufnehmen"-CTA steht fixiert unten
                    // (gleiche Optik/Position wie der Start-CTA) – hier nur der Verlauf-Sprung
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

            // Erkannt-Essens-Korrektur: Dialog; bei gespeichertem Durchlauf persistieren
            // (Store-Update; das Tagesfazit des Tages wird im Verlauf als veraltet erkannt
            // und dort ggf. automatisch neu bewertet)
            if (showErkanntEdit && evaluation != null) {
                ErkanntEditDialog(
                    initialText = evaluation?.erkannt.orEmpty(),
                    onOk = { newText ->
                        evaluation = evaluation?.copy(erkannt = newText)
                        showErkanntEdit = false
                        if (savedToHistory && savedEntryId != null) {
                            HistoryStore.updateErkannt(ctx, savedEntryId!!, newText)
                        }
                    },
                    onDismiss = { showErkanntEdit = false }
                )
            }
        }
    }
}
