package de.gurum.besseresser

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

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
    onDeleteEntry: (HistoryEntry) -> Unit
) {
    TopBarRow(
        title = stringResource(R.string.history_title),
        onBack = onBack,
        trailing = { },
        icon = Icons.Filled.DateRange
    )

    if (entries.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
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
    } else {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.history_list_hint, entries.size),
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(Modifier.height(6.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            groupByDay(entries).forEach { group ->
                val dayKey = dayKeyOf(group.day)
                val fazit = fazitMap[dayKey]
                val loadingFazit = dayKey in loadingFazits
                val staleFazit = fazit != null && (
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
                            onAddMeal = { onAddMealForDay(group.day) }
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
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart) {
                                    onDeleteEntry(entry)
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
                                        .clip(RoundedCornerShape(16.dp))
                                        .fillMaxSize()
                                        .background(Color(0xFFD32F2F)),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 22.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            stringResource(R.string.delete),
                                            color = Color.White,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                            }
                        ) {
                            HistoryItem(entry, onClick = { onPick(entry) })
                        }
                    }
                }
            }

            if (LocalDate.now().dayOfWeek == java.time.DayOfWeek.SUNDAY &&
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
}

@Composable
fun HistoryItem(entry: HistoryEntry, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
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
