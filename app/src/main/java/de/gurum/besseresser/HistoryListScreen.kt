package de.gurum.besseresser

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
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

/** Merkt sich Versuche pro App-Lauf (damit fehlgeschlagene KI-Calls nicht im Loop hängen). */
object DayFazitSession {
    val attempted = mutableSetOf<String>()
    val attemptedWeekly = mutableSetOf<String>()
}

@Composable
fun HistoryListContent(
    entries: List<HistoryEntry>,
    fazitMap: Map<String, DayFazit>,
    loadingFazits: List<String>,
    expandedFazits: MutableList<String>,
    weekFazit: DayFazit?,
    loadingWeek: Boolean,
    expandedWeek: Boolean,
    onBack: () -> Unit,
    onPick: (HistoryEntry) -> Unit,
    onGenerateFazit: (LocalDate, List<HistoryEntry>) -> Unit,
    onGenerateWeekFazit: () -> Unit,
    onToggleExpandWeek: () -> Unit,
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
                        onGenerate = { onGenerateFazit(group.day, group.entries) },
                        onAddMeal = { onAddMealForDay(group.day) }
                    )
                    when {
                        loadingFazit -> Text(
                            stringResource(R.string.dayfazit_loading_line),
                            fontSize = 11.sp,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, start = 4.dp)
                        )
                        fazit != null && !staleFazit && dayKey in expandedFazits -> Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp, bottom = 2.dp)
                        ) {
                            Text(
                                fazit.text,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                modifier = Modifier.padding(12.dp)
                            )
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
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                        stringResource(R.string.nacherfassen_label)
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
