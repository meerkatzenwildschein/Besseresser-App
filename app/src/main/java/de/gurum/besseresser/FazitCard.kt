package de.gurum.besseresser

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** Auto-Fazits starten erst ab dieser Uhrzeit (Stunde, lokal) – davor nur manueller Chip. */
const val FAZIT_AUTO_HOUR = 18

/**
 * Kopf-Chip (Tages-/Wochenfazit): 40dp hoch — exakt so hoch wie der „+“-Button —,
 * zentrierter Inhalt, größere Schrift (13sp). accent != null → farbiger Ampel-Chip
 * mit weißem Inhalt; sonst grau. onClick = null → nicht tappbar (Loading).
 */
@Composable
fun HeadChip(
    label: String,
    onClick: (() -> Unit)?,
    accent: Color? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val shape = RoundedCornerShape(20.dp)
    val border = if (accent == null) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    } else null
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
    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = shape,
            color = accent ?: MaterialTheme.colorScheme.surfaceVariant,
            border = border,
            modifier = Modifier.height(40.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) { content() }
        }
    } else {
        Surface(
            shape = shape,
            color = accent ?: MaterialTheme.colorScheme.surfaceVariant,
            border = border,
            modifier = Modifier.height(40.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) { content() }
        }
    }
}

/** Aufgeklappter Fazit-Textblock (Tages- & Wochenfazit) mit Ampel-Akzentrand. */
@Composable
fun FazitExpandedCard(fazit: DayFazit) {
    val accent = ampelColor(fazit.ampel)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp)
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                fazit.text,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * Wochenfazit-Zeile: Label links, Chip rechts (idle/loading/ready-farbig); kein „veraltet"-
 * Zustand – Wochen-Änderungen löschen das Fazit stattdessen (Chip → idle).
 */
@Composable
fun WeekFazitRow(
    fazit: DayFazit?,
    loading: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onGenerate: () -> Unit,
    calendarWeek: Int = calendarWeekOf()
) {
    val chevronAngle by animateFloatAsState(if (expanded) 180f else 0f, label = "week-fazit-chevron")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .animateContentSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(18.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.tertiary)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.weekfazit_row_label_kw, calendarWeek),
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
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
                            modifier = Modifier.size(16.dp).rotate(chevronAngle)
                        )
                    }
                )
            }
        }
        when {
            loading -> Text(
                stringResource(R.string.weekfazit_loading_line),
                fontSize = 11.5.sp,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, start = 6.dp)
            )
            fazit != null && expanded -> FazitExpandedCard(fazit = fazit)
        }
    }
}

/** Mahlzeiten-Zeilen für den Tagesfazit-Prompt: „HH:mm · Erkanntes mit Menge · Anmerkung";
 *  nachgetragene Einträge ohne echte Uhrzeit → Label „nachträglich erfasst". */
fun dayFazitMealLines(ctx: Context, entriesOfDay: List<HistoryEntry>): List<String> =
    entriesOfDay.sortedBy { it.effectiveSortOrder }.map { e ->
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

/** Mahlzeiten-Zeilen für den Wochenfazit-Prompt: „dd.MM.yyyy · Erkanntes mit Menge · Anmerkung"
 *  (chronologisch aufsteigend, mit Tagesdatum statt Uhrzeit). */
fun weekFazitMealLines(ctx: Context, entriesOfWeek: List<HistoryEntry>): List<String> =
    entriesOfWeek.sortedWith(
        compareBy<HistoryEntry> {
            Instant.ofEpochMilli(it.dateMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        }.thenBy { it.effectiveSortOrder }
    ).map { e ->
        val dateStr = Instant.ofEpochMilli(e.dateMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(DATE_FORMATTER)
        val recog = e.erkannt.ifBlank { ctx.getString(R.string.dayfazit_line_none) }
        var line = "$dateStr · $recog"
        e.note.takeIf { it.isNotBlank() }
            ?.let { line += " · " + ctx.getString(R.string.dayfazit_line_note, it.trim()) }
        line
    }

fun createDayFazitPrompts(ctx: Context): FazitPromptBundle = FazitPromptBundle(
    promptIntro = ctx.getString(R.string.prompt_dayfazit_intro),
    promptRules = ctx.getString(R.string.prompt_dayfazit_rules),
    promptLanguage = ctx.getString(R.string.prompt_dayfazit_language),
    promptHonesty = ctx.getString(R.string.prompt_honesty)
)

fun createWeekFazitPrompts(ctx: Context, calendarWeek: Int = calendarWeekOf()): FazitPromptBundle = FazitPromptBundle(
    promptIntro = ctx.getString(R.string.prompt_weekfazit_intro, calendarWeek),
    promptRules = ctx.getString(R.string.prompt_dayfazit_rules),
    promptLanguage = ctx.getString(R.string.prompt_dayfazit_language),
    promptHonesty = ctx.getString(R.string.prompt_honesty)
)

fun syncWeekFazit(ctx: Context, entries: List<HistoryEntry>): DayFazit? {
    val wk = weekKeyOf(System.currentTimeMillis())
    val wf = WeekFazitStore.load(ctx, wk) ?: return null
    val currentWkEntries = entries.filter { weekKeyOf(it.dateMillis) == wk }
    if (wf.fingerprint != dayFingerprint(currentWkEntries)) {
        WeekFazitStore.delete(ctx, wk)
        return null
    }
    return wf
}

fun isFazitOutdated(fazit: DayFazit, entries: List<HistoryEntry>): Boolean {
    if (fazit.entryIds.toSet() != entries.map { it.id }.toSet()) return true
    return fazit.fingerprint.isNotBlank() && fazit.fingerprint != dayFingerprint(entries)
}

/** Ein Wochenfazit steht bereit (sichtbar & auto-generierbar), sobald der Sonntag der betreffenden
 *  Woche 18:00 Uhr erreicht hat – also am Sonntag ab 18:00 Uhr SOWIE an allen darauffolgenden Tagen
 *  (z. B. am Montag für die abgelaufene Vorwoche). */
fun isWeekFazitDue(
    weekKey: String,
    nowDate: LocalDate = LocalDate.now(),
    nowHour: Int = LocalTime.now().hour
): Boolean {
    val monday = weekMondayOfKey(weekKey) ?: return false
    val sunday = monday.plusDays(6)
    return when {
        nowDate.isAfter(sunday) -> true
        nowDate.isEqual(sunday) -> nowHour >= FAZIT_AUTO_HOUR
        else -> false
    }
}

/** Liefert den neuesten fälligen Wochen-Key (mit vorhandenen Einträgen), z. B. am Montag die abgelaufene
 *  Vorwoche oder am Sonntag ab 18:00 Uhr die laufende Woche. */
fun latestDueWeekKey(
    entries: List<HistoryEntry>,
    nowDate: LocalDate = LocalDate.now(),
    nowHour: Int = LocalTime.now().hour
): String? = entries
    .map { weekKeyOf(it.dateMillis) }
    .distinct()
    .sortedDescending()
    .firstOrNull { wk -> isWeekFazitDue(wk, nowDate, nowHour) }

@Composable
fun HistoryAutoFazitEffect(
    entries: List<HistoryEntry>,
    fazitMap: Map<String, DayFazit>,
    loadingFazits: List<String>,
    weekFazitMap: Map<String, DayFazit>,
    loadingWeeks: Set<String>,
    onGenerateFazit: (LocalDate, List<HistoryEntry>) -> Unit,
    onGenerateWeekFazit: (String) -> Unit
) {
    LaunchedEffect(entries, fazitMap, weekFazitMap) {
        if (entries.isEmpty()) return@LaunchedEffect
        delay(1200)   // Debounce
        val nowDate = LocalDate.now()
        val nowHour = LocalTime.now().hour
        val autoFazitTime = nowHour >= FAZIT_AUTO_HOUR
        groupByDay(entries).take(2).forEach { group ->
            val dayAutoReady = group.day.isBefore(nowDate) || autoFazitTime
            handleAutoDayFazit(group, fazitMap, loadingFazits, dayAutoReady, onGenerateFazit)
        }
        val dueWk = latestDueWeekKey(entries, nowDate, nowHour)
        if (dueWk != null) {
            handleAutoWeekFazitForKey(
                weekKey = dueWk,
                weekFazitMap = weekFazitMap,
                loadingWeeks = loadingWeeks,
                onGenerateWeekFazit = onGenerateWeekFazit
            )
        }
    }
}

fun handleAutoDayFazit(
    group: DayGroup,
    fazitMap: Map<String, DayFazit>,
    loadingFazits: List<String>,
    autoFazitTime: Boolean,
    onGenerateFazit: (LocalDate, List<HistoryEntry>) -> Unit
) {
    val key = dayKeyOf(group.day)
    if (key in loadingFazits || !autoFazitTime) return
    val fazit = fazitMap[key]
    if (fazit == null) {
        if (key !in DayFazitSession.attempted) {
            DayFazitSession.attempted.add(key)
            onGenerateFazit(group.day, group.entries)
        }
    } else if (isFazitOutdated(fazit, group.entries)) {
        onGenerateFazit(group.day, group.entries)
    }
}

fun handleAutoWeekFazitForKey(
    weekKey: String,
    weekFazitMap: Map<String, DayFazit>,
    loadingWeeks: Set<String>,
    onGenerateWeekFazit: (String) -> Unit
) {
    if (weekKey in loadingWeeks || weekFazitMap[weekKey] != null) return
    if (weekKey !in DayFazitSession.attemptedWeekly) {
        DayFazitSession.attemptedWeekly.add(weekKey)
        onGenerateWeekFazit(weekKey)
    }
}

fun handleAutoWeekFazit(
    autoFazitTime: Boolean,
    weekFazit: DayFazit?,
    loadingWeek: Boolean,
    onGenerateWeekFazit: () -> Unit
) {
    if (!autoFazitTime || LocalDate.now().dayOfWeek != java.time.DayOfWeek.SUNDAY || weekFazit != null || loadingWeek) {
        return
    }
    val wk = weekKeyOf(System.currentTimeMillis())
    if (wk !in DayFazitSession.attemptedWeekly) {
        DayFazitSession.attemptedWeekly.add(wk)
        onGenerateWeekFazit()
    }
}
