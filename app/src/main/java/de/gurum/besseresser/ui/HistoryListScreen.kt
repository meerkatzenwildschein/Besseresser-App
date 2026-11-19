package de.gurum.besseresser.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.gurum.besseresser.R
import de.gurum.besseresser.model.DayFazit
import de.gurum.besseresser.model.HistoryEntry
import de.gurum.besseresser.model.dayFingerprint
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

@Composable
fun HistoryListContent(
    entries: List<HistoryEntry>,
    pendingDeleteIds: Set<Long> = emptySet(),
    fazitMap: Map<String, DayFazit>,
    loadingFazits: List<String>,
    expandedFazits: List<String>,
    weekFazitMap: Map<String, DayFazit>,
    loadingWeeks: Set<String>,
    expandedWeeks: Set<String>,
    onBack: () -> Unit,
    onPick: (HistoryEntry) -> Unit,
    onGenerateFazit: (LocalDate, List<HistoryEntry>) -> Unit,
    onGenerateWeekFazit: (String) -> Unit,
    onToggleExpandWeek: (String) -> Unit,
    onToggleExpandFazit: (String) -> Unit,
    onAddMealForDay: (LocalDate) -> Unit,
    onDeleteEntry: (HistoryEntry) -> Unit,
    onUndoDeleteEntry: (HistoryEntry) -> Unit = {},
    onMoveEntryWithinDay: (LocalDate, Int, Int) -> Unit = { _, _, _ -> },
    onPhotoFile: (HistoryEntry) -> File? = { null }
) {
    TopBarRow(
        title = stringResource(R.string.history_title),
        onBack = onBack,
        trailing = { },
        icon = Icons.Filled.DateRange
    )

    val today = LocalDate.now()
    val nowHour = LocalTime.now().hour
    val uiItems = remember(entries, today, nowHour) {
        buildHistoryUiItems(entries, today, nowHour)
    }

    if (entries.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
            pluralStringResource(R.plurals.history_list_hint, entries.size, entries.size),
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(Modifier.height(6.dp))
    } else {
        Spacer(Modifier.height(8.dp))
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(
            items = uiItems,
            key = { it.key }
        ) { item ->
            when (item) {
                is HistoryUiItem.WeekFazitItem -> {
                    WeekFazitRow(
                        fazit = weekFazitMap[item.weekKey],
                        loading = item.weekKey in loadingWeeks,
                        expanded = item.weekKey in expandedWeeks,
                        onToggleExpand = { onToggleExpandWeek(item.weekKey) },
                        onGenerate = { onGenerateWeekFazit(item.weekKey) },
                        calendarWeek = item.calendarWeek
                    )
                }
                is HistoryUiItem.DayHeaderItem -> {
                    val fazit = if (item.hasEntries) fazitMap[item.dayKey] else null
                    val loadingFazit = item.hasEntries && item.dayKey in loadingFazits
                    val staleFazit = item.hasEntries && fazit != null && (
                        fazit.entryIds.toSet() != item.entries.map { it.id }.toSet() ||
                            (fazit.fingerprint.isNotBlank() && fazit.fingerprint != dayFingerprint(item.entries))
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize()
                    ) {
                        DayGroupHeader(
                            date = item.day,
                            fazit = fazit,
                            loading = loadingFazit,
                            stale = staleFazit,
                            expanded = item.dayKey in expandedFazits,
                            onToggleExpand = { onToggleExpandFazit(item.dayKey) },
                            onGenerate = { onGenerateFazit(item.day, item.entries) },
                            onAddMeal = { onAddMealForDay(item.day) },
                            hasEntries = item.hasEntries
                        )
                        when {
                            loadingFazit -> Text(
                                stringResource(R.string.dayfazit_loading_line),
                                fontSize = 11.5.sp,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp, start = 6.dp)
                            )
                            fazit != null && !staleFazit && item.dayKey in expandedFazits -> {
                                FazitExpandedCard(fazit = fazit)
                            }
                        }
                    }
                }
                is HistoryUiItem.MealEntryItem -> {
                    val isPendingDelete = item.entry.id in pendingDeleteIds
                    if (isPendingDelete) {
                        PendingDeleteHistoryItem(
                            entry = item.entry,
                            onUndo = { onUndoDeleteEntry(item.entry) },
                            onPhotoFile = onPhotoFile
                        )
                    } else {
                        ReorderableDayMealBox(
                            entryId = item.entry.id,
                            currentIndexOnDay = item.indexOnDay,
                            totalCountOnDay = item.totalCountOnDay,
                            onMove = { from, to -> onMoveEntryWithinDay(item.day, from, to) }
                        ) { isDragging ->
                            ShortSwipeToDeleteBox(
                                enabled = !isDragging,
                                onTriggerDelete = { onDeleteEntry(item.entry) }
                            ) {
                                HistoryItem(
                                    entry = item.entry,
                                    onClick = { onPick(item.entry) },
                                    isLifted = isDragging,
                                    onPhotoFile = onPhotoFile
                                )
                            }
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
        }
    }
}
