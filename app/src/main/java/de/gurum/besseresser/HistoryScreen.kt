package de.gurum.besseresser

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

// ----------------------------------------------------------------- Historie-Screen (Liste)

/** Ein Kalendertag mit allen gespeicherten Bewertungen desselben Tages. */
private data class DayGroup(
    val day: LocalDate,
    val entries: List<HistoryEntry>
)

/** Mahlzeiten-Zeilen für den Tagesfazit-Prompt: „HH:mm · Erkanntes mit Menge · Anmerkung“. */
fun dayFazitMealLines(ctx: android.content.Context, entriesOfDay: List<HistoryEntry>): List<String> =
    entriesOfDay.sortedBy { it.dateMillis }.map { e ->
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(e.dateMillis))
        val recog = e.erkannt.ifBlank { ctx.getString(R.string.dayfazit_line_none) }
        var line = "$time · $recog"
        e.note.takeIf { it.isNotBlank() }
            ?.let { line += " · " + ctx.getString(R.string.dayfazit_line_note, it.trim()) }
        line
    }

/** Auto-Fazits starten erst ab dieser Uhrzeit (Stunde, lokal) – davor nur manueller Chip. */
private const val FAZIT_AUTO_HOUR = 18

/** Nach Kalendertag gruppieren (absteigend); Einträge bleiben innerhalb der Gruppe absteigend. */private fun groupByDay(entries: List<HistoryEntry>): List<DayGroup> =
    entries.groupBy {
        Instant.ofEpochMilli(it.dateMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    }.map { (day, list) -> DayGroup(day = day, entries = list) }
    .sortedByDescending { it.day }

@Composable
fun HistoryScreen(settings: Settings, profile: Profile, onPick: (HistoryEntry) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    // Android-Zurück-Button verhält sich wie der On-Screen-Zurückpfeil
    BackHandler(onBack = onBack)
    var entries by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    LaunchedEffect(Unit) { entries = HistoryStore.entries(ctx) }

    // ------------------------------------------------- KI-Tagesfazit-Zustand
    var fazitMap by remember { mutableStateOf<Map<String, DayFazit>>(emptyMap()) }
    val loadingFazits = remember { mutableStateListOf<String>() }
    var expandedFazits = remember { mutableStateListOf<String>() }

    var weekFazit by remember { mutableStateOf<DayFazit?>(null) }
    var loadingWeek by remember { mutableStateOf(false) }
    var expandedWeek by remember { mutableStateOf(false) }

    LaunchedEffect(entries) {
        fazitMap = DayFazitStore.loadAll(ctx).associateBy { it.dayKey }
        // Wochenfazit: vorhanden? Bei geänderten Wochen-Einträgen (Fingerprint) LÖSCHEN
        val wk = weekKeyOf(System.currentTimeMillis())
        val wf = WeekFazitStore.load(ctx, wk)
        if (wf != null && wf.fingerprint != dayFingerprint(entries.filter { dayKeyOf(it.dateMillis) == wk })) {
            WeekFazitStore.delete(ctx, wk)
            weekFazit = null
        } else {
            weekFazit = wf
        }
    }

    fun generateFazit(day: LocalDate, entriesOfDay: List<HistoryEntry>) {
        val key = dayKeyOf(day)
        if (key in loadingFazits) return
        loadingFazits.add(key)
        expandedFazits.add(key)   // während/neu nach dem Laden direkt anzeigen
        val lines = dayFazitMealLines(ctx, entriesOfDay)
        ApiClient.dayFazit(
            settings = settings,
            goals = settings.goals,
            promptContext = profileContextLine(ctx, profile),
            mealLines = lines,
            promptIntro = ctx.getString(R.string.prompt_dayfazit_intro),
            promptRules = ctx.getString(R.string.prompt_dayfazit_rules),
            promptLanguage = ctx.getString(R.string.prompt_dayfazit_language),
            onOk = { ampel, text ->
                DayFazitStore.save(
                    ctx,
                    DayFazit(
                        key, ampel, text, entriesOfDay.map { it.id },
                        System.currentTimeMillis(), dayFingerprint(entriesOfDay)
                    )
                )
                loadingFazits.remove(key)
                fazitMap = DayFazitStore.loadAll(ctx).associateBy { it.dayKey }
            },
            onError = { msg ->
                Toast.makeText(ctx, ctx.getString(R.string.dayfazit_error_prefix, msg), Toast.LENGTH_SHORT).show()
                loadingFazits.remove(key)
            }
        )
    }

    fun generateWeekFazit() {
        if (loadingWeek) return
        val wk = weekKeyOf(System.currentTimeMillis())
        val entriesOfWeek = entries.filter { dayKeyOf(it.dateMillis) == wk }
        if (entriesOfWeek.isEmpty()) return
        loadingWeek = true
        expandedWeek = true
        ApiClient.dayFazit(
            settings = settings,
            goals = settings.goals,
            promptContext = profileContextLine(ctx, profile),
            mealLines = dayFazitMealLines(ctx, entriesOfWeek),
            promptIntro = ctx.getString(R.string.prompt_weekfazit_intro),
            promptRules = ctx.getString(R.string.prompt_dayfazit_rules),
            promptLanguage = ctx.getString(R.string.prompt_dayfazit_language),
            onOk = { ampel, text ->
                WeekFazitStore.save(
                    ctx,
                    DayFazit(
                        wk, ampel, text, entriesOfWeek.map { it.id },
                        System.currentTimeMillis(), dayFingerprint(entriesOfWeek)
                    )
                )
                loadingWeek = false
                weekFazit = WeekFazitStore.load(ctx, wk)
            },
            onError = { msg ->
                Toast.makeText(ctx, ctx.getString(R.string.dayfazit_error_prefix, msg), Toast.LENGTH_SHORT).show()
                loadingWeek = false
            }
        )
    }

    // Auto-Logik (nur die beiden neuesten Tagesgruppen):
    //  • kein Fazit     → 1× pro Tag & App-Session automatisch (Ersatztext „KI bewertet den Tag …")
    //  • Fazit veraltet → automatisch neu bewerten (veraltet = Entry-IDs/Fingerprint weichen ab)
    //  • ältere Gruppen nur manuell per Chip-Tap
    //  • AUTO erst ab 18 Uhr (`FAZIT_AUTO_HOUR`): verhindert Frühstücks-Fazits; manuell (Chip)
    //    geht zu JEDER Zeit
    LaunchedEffect(entries, fazitMap) {
        if (entries.isEmpty()) return@LaunchedEffect
        delay(1200)   // Debounce: schnelles Löschen mehrerer Einträge löst nur einen Call aus
        val autoFazitTime = LocalTime.now().hour >= FAZIT_AUTO_HOUR
        groupByDay(entries).take(2).forEach { group ->
            val key = dayKeyOf(group.day)
            if (key in loadingFazits) return@forEach
            val fazit = fazitMap[key]
            if (fazit == null) {
                if (autoFazitTime && key !in DayFazitSession.attempted) {
                    DayFazitSession.attempted.add(key)
                    generateFazit(group.day, group.entries)
                }
            } else if (autoFazitTime && (
                    fazit.entryIds.toSet() != group.entries.map { it.id }.toSet() ||
                        (fazit.fingerprint.isNotBlank() && fazit.fingerprint != dayFingerprint(group.entries))
                    )
            ) {
                generateFazit(group.day, group.entries)
            }
        }
        // Wochenfazit: auto NUR Sonntag ≥ 18 Uhr, 1× pro Woche & Session (sonst Chip-Tipp)
        val today = LocalDate.now()
        if (autoFazitTime && today.dayOfWeek == java.time.DayOfWeek.SUNDAY && weekFazit == null) {
            if (!loadingWeek) {
                val wk = weekKeyOf(System.currentTimeMillis())
                if (wk !in DayFazitSession.attemptedWeekly) {
                    DayFazitSession.attemptedWeekly.add(wk)
                    generateWeekFazit()
                }
            }
        }
    }

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
                            // geänderte erkannt-Texte (Korrekturen) machen das Fazit ebenfalls veraltet
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
                            onGenerate = { generateFazit(group.day, group.entries) }
                        )
                        when {
                            loadingFazit -> Text(
                                stringResource(R.string.dayfazit_loading_line),
                                fontSize = 11.sp,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                            staleFazit && fazit != null -> Text(
                                fazit.text,   // alte Fassung, gedimmt angedeutet
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            fazit != null && dayKey in expandedFazits -> Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    fazit.text,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                    group.entries.forEach { entry ->
                        item(key = entry.id) {
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

                // Wochenfazit-Zeile: NUR am Sonntag ab 18 Uhr sichtbar (gleiche Chip-Sprache
                // wie die Tagesfazit-Köpfe); bei Wochen-Änderungen wird das Fazit gelöscht
                if (LocalDate.now().dayOfWeek == java.time.DayOfWeek.SUNDAY &&
                    LocalTime.now().hour >= FAZIT_AUTO_HOUR
                ) {
                    item(key = "week") {
                        WeekFazitRow(
                            fazit = weekFazit,
                            loading = loadingWeek,
                            expanded = expandedWeek,
                            onToggleExpand = { expandedWeek = !expandedWeek },
                            onGenerate = { generateWeekFazit() }
                        )
                    }
                }
            }
        }
    }
}

/** Wochenfazit-Zeile: Label links, Chip rechts (idle/loading/ready-farbig); kein „veraltet"-
 *  Zustand – Wochen-Änderungen löschen das Fazit stattdessen (Chip → idle). */
@Composable
private fun WeekFazitRow(
    fazit: DayFazit?,
    loading: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onGenerate: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.weekfazit_row_label),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            when {
                loading -> Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                        Spacer(Modifier.width(5.dp))
                        Text(
                            stringResource(R.string.weekfazit_chip_loading),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                fazit == null -> Surface(
                    onClick = onGenerate,
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        stringResource(R.string.weekfazit_chip_idle),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
                else -> Surface(
                    onClick = onToggleExpand,
                    shape = RoundedCornerShape(18.dp),
                    color = ampelColor(fazit.ampel)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(Color.White))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.weekfazit_chip_label),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp).rotate(if (expanded) 180f else 0f)
                        )
                    }
                }
            }
        }
        when {
            loading -> Text(
                stringResource(R.string.weekfazit_loading_line),
                fontSize = 11.sp,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
            fazit != null && expanded -> Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(fazit.text, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
            }
        }
    }
}

/** Kopfzeile einer Tagesgruppe: Datum links, Tagesfazit-Chip rechts (Zustandsmaschine). */@Composable
private fun DayGroupHeader(
    date: LocalDate,
    fazit: DayFazit?,
    loading: Boolean,
    stale: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onGenerate: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        when {
            loading -> Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        stringResource(R.string.dayfazit_chip_loading),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            fazit == null -> Surface(
                onClick = onGenerate,
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    stringResource(R.string.dayfazit_chip_idle),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
            stale -> Surface(
                onClick = onGenerate,
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.dayfazit_chip_stale),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> Surface(
                onClick = onToggleExpand,
                shape = RoundedCornerShape(18.dp),
                color = ampelColor(fazit.ampel)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(Color.White))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.dayfazit_chip_label),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp).rotate(if (expanded) 180f else 0f)
                    )
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
                    SimpleDateFormat("HH:mm", Locale.getDefault())
                        .format(Date(entry.dateMillis)),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
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

private fun zielePreview(entry: HistoryEntry, emptyText: String, moreText: String): String {
    val list = entry.goalResults.map { it.goal }
    return when {
        list.isEmpty() -> emptyText
        list.size <= 2 -> list.joinToString(", ")
        else -> list.take(2).joinToString(", ") + moreText
    }
}

/** Farbskala für Ampel-Scores (auch der Tagesscore): grau = nichts bewertbar, sonst Ampel. */
private fun scoreColor(score: Int): Color = when {
    score < 0 -> ampelColor("unbekannt")
    score >= 80 -> ampelColor("gruen")
    score >= 50 -> ampelColor("gelb")
    else -> ampelColor("rot")
}

/** Farbiges Kreis-Badge mit Score (Long-Press-Erklärung via Tooltip). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScoreCircle(score: Int, tooltipRes: Int) {
    val tooltipState = rememberTooltipState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        state = tooltipState,
        tooltip = { PlainTooltip { Text(stringResource(tooltipRes)) } }
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(scoreColor(score))
                .padding(3.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (score >= 0) "$score" else "–",
                fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold
            )
        }
    }
}

/** Ampel-Score als farbiger Kreis + Bezeichnung; Long-Press erklärt die Berechnung. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreBadge(entry: HistoryEntry) {
    val score = entry.score
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ScoreCircle(
            score = score,
            tooltipRes = if (score >= 0) R.string.score_tooltip_ok
                         else R.string.score_tooltip_unknown
        )
        Text(stringResource(R.string.score_caption), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ----------------------------------------------------------------- Detail-Screen

@Composable
fun HistoryDetailScreen(entry: HistoryEntry, onBack: () -> Unit) {
    val ctx = LocalContext.current
    // Android-Zurück-Button verhält sich wie der On-Screen-Zurückpfeil
    BackHandler(onBack = onBack)
    var confirmDelete by remember { mutableStateOf(false) }
    var showErkanntEdit by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf(entry) }
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

        if (current.summary.isNotBlank() || current.erkannt.isNotBlank() || current.note.isNotBlank()) {
            // Gleicher antipbarer Block wie in der Auswertung: 🧠 sichtbar, 🍽️/📝 einklappbar,
            // 🍽️ mit Pencil → erkannt userkorrigierbar
            CollapsibleSummaryBlock(
                summary = current.summary,
                erkannt = current.erkannt,
                note = current.note,
                onEditErkannt = { if (current.erkannt.isNotBlank()) showErkanntEdit = true }
            )
        }

        Text(stringResource(R.string.my_goals), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        current.goalResults.forEach { GoalRow(it.goal, it) }

        Spacer(Modifier.height(16.dp))
    }

    if (showErkanntEdit) {
        ErkanntEditDialog(
            initialText = current.erkannt,
            onOk = { newText ->
                HistoryStore.updateErkannt(ctx, current.id, newText)
                current = current.copy(erkannt = newText)
                showErkanntEdit = false
            },
            onDismiss = { showErkanntEdit = false }
        )
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
