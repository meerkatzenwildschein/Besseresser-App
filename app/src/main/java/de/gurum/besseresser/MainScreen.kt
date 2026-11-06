package de.gurum.besseresser

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
                onTakePhoto = {
                    if (!uiState.isBusy) {
                        viewModel.reset()
                        viewModel.setShowCamera(true)
                    }
                },
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
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
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
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = stringResource(R.string.photo_of_meal),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(290.dp)
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
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )
            if (busy) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                )
            }
            Spacer(Modifier.height(150.dp))
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .imePadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onRate,
                    enabled = !busy,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        stringResource(if (busy) R.string.main_ai_working else R.string.main_rate_now),
                        fontSize = 16.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                OutlinedButton(
                    onClick = onRetake,
                    enabled = !busy,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text(stringResource(R.string.main_retake))
                }
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
    onTakePhoto: () -> Unit,
    onSaveToHistory: () -> Unit,
    onDiscard: () -> Unit
) {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
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

        if (!lastGoaled) {
            MainHeroCard(onTakePhoto = onTakePhoto)
        } else {
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
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(230.dp)
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = stringResource(R.string.photo_of_meal),
                            contentScale = ContentScale.Crop,
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
        val evalScore = remember(results) {
            if (results.isNullOrEmpty()) -1
            else HistoryEntry(goalResults = results).score
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.my_goals),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            if (lastGoaled && evalScore >= 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        stringResource(R.string.score_caption),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ScoreCircle(
                        score = evalScore,
                        tooltipRes = R.string.score_tooltip_ok
                    )
                }
            }
        }

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
            Spacer(Modifier.height(2.dp))
            Button(
                onClick = onSaveToHistory,
                enabled = !savingHistory,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text(
                    stringResource(if (savingHistory) R.string.main_saving else R.string.main_save_to_history),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
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
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(
                    Icons.Filled.DateRange,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.main_open_history), fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(if (reportShown && !savedToHistory) 28.dp else 110.dp))
    }
}

@Composable
private fun MainHeroCard(onTakePhoto: () -> Unit) {
    Surface(
        onClick = onTakePhoto,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🥗", fontSize = 22.sp)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.main_hero_title),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.main_hero_sub),
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                ) {
                    Text(
                        stringResource(R.string.main_swipe_hint),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }
        }
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(TrafficLight.GREEN.color()))
                    Box(Modifier.size(7.dp).clip(CircleShape).background(TrafficLight.YELLOW.color()))
                    Box(Modifier.size(7.dp).clip(CircleShape).background(TrafficLight.RED.color()))
                }
            }
            Text(
                stringResource(R.string.main_title),
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.size(42.dp)
            ) {
                IconButton(
                    onClick = onOpenHistory,
                    enabled = menuEnabled,
                    modifier = Modifier.size(42.dp)
                ) {
                    Icon(
                        Icons.Filled.DateRange,
                        contentDescription = stringResource(R.string.menu_history),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Box {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    modifier = Modifier.size(42.dp)
                ) {
                    IconButton(
                        onClick = { onMenuToggle(true) },
                        modifier = Modifier.size(42.dp)
                    ) {
                        Icon(
                            Icons.Filled.Menu,
                            contentDescription = stringResource(R.string.menu_open_description),
                            modifier = Modifier.size(20.dp)
                        )
                    }
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
}

@Composable
private fun MainAiBusyCard(modifier: Modifier = Modifier) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
            modifier = modifier.padding(horizontal = 32.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 30.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(42.dp),
                        strokeWidth = 3.5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    stringResource(R.string.main_ai_working),
                    fontSize = 16.5.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    stringResource(R.string.main_ai_working_sub),
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
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
    if ((lastGoaled && savedToHistory) || !reportShown) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
        ) {
            if (lastGoaled && savedToHistory) {
                Button(
                    onClick = onNewPhoto,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        "📷  " + stringResource(R.string.main_new_photo),
                        fontSize = 16.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else {
                Button(
                    onClick = onTakePhoto,
                    enabled = !busy,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 16.dp)
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
                            Text(
                                stringResource(R.string.main_ai_working),
                                fontSize = 16.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else {
                        Text(
                            "📷  " + stringResource(R.string.main_take_photo),
                            fontSize = 16.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
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
