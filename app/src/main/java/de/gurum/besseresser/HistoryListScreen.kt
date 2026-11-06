package de.gurum.besseresser

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Ein Kalendertag mit allen gespeicherten Bewertungen desselben Tages. */
data class DayGroup(
    val day: LocalDate,
    val entries: List<HistoryEntry>
)

/** Nach Kalendertag gruppieren (absteigend); Einträge bleiben innerhalb der Gruppe absteigend. */
fun groupByDay(entries: List<HistoryEntry>): List<DayGroup> =
    entries.groupBy {
        Instant.ofEpochMilli(it.dateMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    }.map { (day, list) -> DayGroup(day = day, entries = list) }
        .sortedByDescending { it.day }

/** Merkt sich Versuche pro App-Lauf — Deklaration liegt zentral in HistoryStore.kt. */

@Composable
fun HistoryListContent(
    entries: List<HistoryEntry>,
    pendingDeleteIds: Set<Long> = emptySet(),
    fazitMap: Map<String, DayFazit>,
    loadingFazits: List<String>,
    expandedFazits: List<String>,
    weekFazit: DayFazit?,
    loadingWeek: Boolean,
    expandedWeek: Boolean,
    onBack: () -> Unit,
    onPick: (HistoryEntry) -> Unit,
    onGenerateFazit: (LocalDate, List<HistoryEntry>) -> Unit,
    onGenerateWeekFazit: () -> Unit,
    onToggleExpandWeek: () -> Unit,
    onToggleExpandFazit: (String) -> Unit,
    onAddMealForDay: (LocalDate) -> Unit,
    onDeleteEntry: (HistoryEntry) -> Unit,
    onUndoDeleteEntry: (HistoryEntry) -> Unit = {}
) {
    TopBarRow(
        title = stringResource(R.string.history_title),
        onBack = onBack,
        trailing = { },
        icon = Icons.Filled.DateRange
    )

    val today = LocalDate.now()
    val rawGroups = groupByDay(entries)
    val displayGroups = if (rawGroups.any { it.day == today }) {
        rawGroups
    } else {
        (listOf(DayGroup(day = today, entries = emptyList())) + rawGroups)
            .sortedByDescending { it.day }
    }

    if (entries.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.history_list_hint, entries.size),
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(Modifier.height(6.dp))
    } else {
        Spacer(Modifier.height(8.dp))
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        displayGroups.forEach { group ->
            val dayKey = dayKeyOf(group.day)
            val hasEntries = group.entries.isNotEmpty()
            val fazit = if (hasEntries) fazitMap[dayKey] else null
            val loadingFazit = hasEntries && dayKey in loadingFazits
            val staleFazit = hasEntries && fazit != null && (
                fazit.entryIds.toSet() != group.entries.map { it.id }.toSet() ||
                    (fazit.fingerprint.isNotBlank() && fazit.fingerprint != dayFingerprint(group.entries))
                )
            item(key = "day-$dayKey") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                ) {
                    DayGroupHeader(
                        date = group.day,
                        fazit = fazit,
                        loading = loadingFazit,
                        stale = staleFazit,
                        expanded = dayKey in expandedFazits,
                        onToggleExpand = { onToggleExpandFazit(dayKey) },
                        onGenerate = { onGenerateFazit(group.day, group.entries) },
                        onAddMeal = { onAddMealForDay(group.day) },
                        hasEntries = hasEntries
                    )
                    when {
                        loadingFazit -> Text(
                            stringResource(R.string.dayfazit_loading_line),
                            fontSize = 11.5.sp,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, start = 6.dp)
                        )
                        fazit != null && !staleFazit && dayKey in expandedFazits -> {
                            FazitExpandedCard(fazit = fazit)
                        }
                    }
                }
            }
            group.entries.forEach { entry ->
                item(key = entry.id) {
                    val isPendingDelete = entry.id in pendingDeleteIds
                    if (isPendingDelete) {
                        PendingDeleteHistoryItem(
                            entry = entry,
                            onUndo = { onUndoDeleteEntry(entry) }
                        )
                    } else {
                        ShortSwipeToDeleteBox(
                            onTriggerDelete = { onDeleteEntry(entry) }
                        ) {
                            HistoryItem(entry, onClick = { onPick(entry) })
                        }
                    }
                }
            }
        }

        if (entries.isEmpty()) {
            item(key = "empty-hint") {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🍽️", fontSize = 32.sp)
                        }
                        Text(
                            stringResource(R.string.history_empty),
                            textAlign = TextAlign.Center,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            stringResource(R.string.history_empty_sub),
                            textAlign = TextAlign.Center,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else if (LocalDate.now().dayOfWeek == java.time.DayOfWeek.SUNDAY &&
            LocalTime.now().hour >= FAZIT_AUTO_HOUR
        ) {
            item(key = "week") {
                WeekFazitRow(
                    fazit = weekFazit,
                    loading = loadingWeek,
                    expanded = expandedWeek,
                    onToggleExpand = onToggleExpandWeek,
                    onGenerate = onGenerateWeekFazit
                )
            }
        }
    }
}

@Composable
fun HistoryItem(entry: HistoryEntry, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val thumb = remember(entry.id) {
                HistoryStore.photoFileOf(ctx, entry)?.let { decodeThumb(it) }
            }
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = stringResource(R.string.photo_of_meal),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                            RoundedCornerShape(12.dp)
                        )
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                        .border(
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                            RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🍽️", fontSize = 24.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        if (entry.backdated)
                            stringResource(R.string.nacherfassen_label)
                        else
                            SimpleDateFormat("HH:mm", Locale.getDefault())
                                .format(Date(entry.dateMillis)),
                        fontSize = if (entry.backdated) 12.sp else 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontStyle = if (entry.backdated) FontStyle.Italic else FontStyle.Normal,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (entry.goalResults.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            entry.goalResults.take(6).forEach { gr ->
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(gr.ampel.color())
                                )
                            }
                        }
                    }
                }
                if (entry.erkannt.isNotBlank()) {
                    Text(
                        stringResource(R.string.detected_prefix, entry.erkannt),
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
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
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            ScoreBadge(entry)
        }
    }
}

/**
 * Begrenztes Links-Schieben mit festem kurzem Anschlag (~96dp):
 * Die Karte lässt sich nur so weit nach links schieben, bis das rote Mülleimer-Feld sichtbar ist.
 * Wird die Karte am Anschlag losgelassen, löst [onTriggerDelete] den deaktivierten „Rückgängig"-Zustand aus.
 */
@Composable
private fun ShortSwipeToDeleteBox(
    onTriggerDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val maxSwipePx = remember(density) { with(density) { 96.dp.toPx() } }
    val triggerPx = maxSwipePx * 0.72f
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val animatedOffset by animateFloatAsState(
        targetValue = dragOffset,
        animationSpec = tween(durationMillis = 150),
        label = "short-swipe-offset"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(maxSwipePx) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val shouldDelete = dragOffset <= -triggerPx
                        dragOffset = 0f
                        if (shouldDelete) {
                            onTriggerDelete()
                        }
                    },
                    onDragCancel = {
                        dragOffset = 0f
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        // Nur Links-Schieben (oder Zurückschieben aus bereits geöffneter Position) abfangen
                        if (dragAmount < 0f || dragOffset < 0f) {
                            change.consume()
                            dragOffset = (dragOffset + dragAmount).coerceIn(-maxSwipePx, 0f)
                        }
                    }
                )
            }
    ) {
        if (animatedOffset < -2f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFD32F2F)),
                contentAlignment = Alignment.CenterEnd
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .width(96.dp)
                        .padding(horizontal = 8.dp)
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.delete),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.delete),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.5.sp
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(animatedOffset.roundToInt(), 0) }
        ) {
            content()
        }
    }
}

/**
 * Deaktivierte Darstellung eines zum Löschen vorgemerkten Eintrags direkt an seinem Listenplatz:
 * Gedimmter Inhalt mit durchgestrichenem Text, „Rückgängig"-Button und 5-Sekunden-Ablaufleiste.
 */
@Composable
fun PendingDeleteHistoryItem(
    entry: HistoryEntry,
    onUndo: () -> Unit
) {
    val ctx = LocalContext.current
    val progress = remember(entry.id) { Animatable(1f) }
    LaunchedEffect(entry.id) {
        progress.animateTo(
            targetValue = 0f,
            animationSpec = tween(
                durationMillis = UNDO_DELETE_DELAY_MS.toInt(),
                easing = LinearEasing
            )
        )
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val thumb = remember(entry.id) {
                    HistoryStore.photoFileOf(ctx, entry)?.let { decodeThumb(it) }
                }
                Box(modifier = Modifier.alpha(0.32f)) {
                    if (thumb != null) {
                        Image(
                            bitmap = thumb.asImageBitmap(),
                            contentDescription = stringResource(R.string.photo_of_meal),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🍽️", fontSize = 22.sp)
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .alpha(0.48f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        stringResource(R.string.history_deleted_label) + " · " +
                            if (entry.backdated)
                                stringResource(R.string.nacherfassen_label)
                            else
                                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(entry.dateMillis)),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    if (entry.erkannt.isNotBlank()) {
                        Text(
                            entry.erkannt,
                            fontSize = 12.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textDecoration = TextDecoration.LineThrough,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                FilledTonalButton(
                    onClick = onUndo,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        stringResource(R.string.history_undo_delete),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            LinearProgressIndicator(
                progress = { progress.value },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent
            )
        }
    }
}
