package de.gurum.besseresser

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ----------------------------------------------------------------- Historie-Screen (Liste)

@Composable
fun HistoryScreen(onPick: (HistoryEntry) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var entries by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    LaunchedEffect(Unit) { entries = HistoryStore.entries(ctx) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Nach rechts wischen → zurück zum Hauptbildschirm
                var acc = 0f
                detectHorizontalDragGestures(
                    onDragEnd = { acc = 0f },
                    onDragCancel = { acc = 0f }
                ) { _, dragAmount ->
                    if (dragAmount > 0) {
                        acc += dragAmount
                        if (acc > 160f) {
                            acc = 0f
                            onBack()
                        }
                    } else {
                        acc = 0f
                    }
                }
            }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TopBarRow(title = stringResource(R.string.history_title), onBack = onBack, trailing = { })

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
                items(entries, key = { it.id }) { entry ->
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
                    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                        .format(Date(entry.dateMillis)),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
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

/** Ampel-Score als farbiger Kreis + Bezeichnung; Long-Press erklärt die Berechnung. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreBadge(entry: HistoryEntry) {
    val score = entry.score
    val color = when {
        score < 0 -> Color(0xFF9E9E9E)
        score >= 80 -> Color(0xFF2E9E44)
        score >= 50 -> Color(0xFFE6A817)
        else -> Color(0xFFD32F2F)
    }
    val tooltipState = rememberTooltipState()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            state = tooltipState,
            tooltip = {
                PlainTooltip {
                    Text(
                        stringResource(
                            if (score >= 0) R.string.score_tooltip_ok
                            else R.string.score_tooltip_unknown
                        )
                    )
                }
            }
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(color)
                    .padding(3.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (score >= 0) "$score" else "–",
                    fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold
                )
            }
        }
        Text(stringResource(R.string.score_caption), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ----------------------------------------------------------------- Detail-Screen

@Composable
fun HistoryDetailScreen(entry: HistoryEntry, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    val thumb = remember(entry.id) {
        HistoryStore.photoFileOf(ctx, entry)?.let { decodeThumb(it, target = 1280) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TopBarRow(
            title = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(entry.dateMillis)),
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

        if (entry.summary.isNotBlank() || entry.note.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    buildString {
                        if (entry.summary.isNotBlank()) {
                            appendLine(stringResource(R.string.summary_prefix, entry.summary))
                        }
                        if (entry.note.isNotBlank()) {
                            append(stringResource(R.string.history_note_prefix, entry.note))
                        }
                    }.trim(),
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Text(stringResource(R.string.my_goals), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        entry.goalResults.forEach { GoalRow(it.goal, it) }

        Spacer(Modifier.height(16.dp))
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
