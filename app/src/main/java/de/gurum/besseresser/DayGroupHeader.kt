package de.gurum.besseresser

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

const val DATE_FORMAT_PATTERN = "dd.MM.yyyy"
val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern(DATE_FORMAT_PATTERN)

/**
 * Kopfzeile einer Tagesgruppe: Datum links, Tagesfazit-Chip rechts (Zustandsmaschine);
 * „+"-Taste zum Nachtragen eines Essens für genau diesen Tag.
 * Fazit-Chip und „+"-Button sind einheitlich 40dp hoch.
 */
@Composable
fun DayGroupHeader(
    date: LocalDate,
    fazit: DayFazit?,
    loading: Boolean,
    stale: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onGenerate: () -> Unit,
    onAddMeal: (() -> Unit)? = null,
    hasEntries: Boolean = true
) {
    val chevronAngle by animateFloatAsState(if (expanded) 180f else 0f, label = "day-fazit-chevron")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp, start = 2.dp, end = 2.dp),
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
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                date.format(DATE_FORMATTER),
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        if (onAddMeal != null) {
            FilledTonalIconButton(
                onClick = onAddMeal,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.nacherfassen_title),
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        when {
            !hasEntries -> HeadChip(
                label = stringResource(R.string.dayfazit_chip_label),
                onClick = null
            )
            loading -> HeadChip(
                label = stringResource(R.string.dayfazit_chip_loading),
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
                label = stringResource(R.string.dayfazit_chip_idle),
                onClick = onGenerate
            )
            stale -> HeadChip(
                label = stringResource(R.string.dayfazit_chip_stale),
                onClick = onGenerate,
                leading = {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
            )
            else -> HeadChip(
                label = stringResource(R.string.dayfazit_chip_label),
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
}

/**
 * Vorschautext für Ziele in der Mahlzeitenliste.
 */
fun zielePreview(entry: HistoryEntry, emptyText: String, moreText: String): String {
    val list = entry.goalResults.map { it.goal }
    return when {
        list.isEmpty() -> emptyText
        list.size <= 2 -> list.joinToString(", ")
        else -> list.take(2).joinToString(", ") + (if (moreText.startsWith(" ")) moreText else " $moreText")
    }
}

/** Farbskala für Ampel-Scores (auch der Tagesscore): grau = nichts bewertbar, sonst Ampel. */
fun scoreColor(score: Int): Color = when {
    score < 0 -> TrafficLight.UNKNOWN.color()
    score >= 80 -> TrafficLight.GREEN.color()
    score >= 50 -> TrafficLight.YELLOW.color()
    else -> TrafficLight.RED.color()
}

/** Farbiges Kreis-Badge mit Score (Long-Press-Erklärung via Tooltip). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreCircle(score: Int, tooltipRes: Int) {
    val tooltipState = rememberTooltipState()
    val col = scoreColor(score)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        state = tooltipState,
        tooltip = { PlainTooltip { Text(stringResource(tooltipRes)) } }
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(col.copy(alpha = 0.18f))
                .border(BorderStroke(1.5.dp, col.copy(alpha = 0.45f)), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(col),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (score >= 0) "$score" else "–",
                    fontSize = 11.5.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** Ampel-Score als farbiger Kreis + Bezeichnung; Long-Press erklärt die Berechnung. */
@Composable
fun ScoreBadge(entry: HistoryEntry) {
    val score = entry.score
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ScoreCircle(
            score = score,
            tooltipRes = if (score >= 0) R.string.score_tooltip_ok
                         else R.string.score_tooltip_unknown
        )
        Spacer(Modifier.height(2.dp))
        Text(
            stringResource(R.string.score_caption),
            fontSize = 9.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
