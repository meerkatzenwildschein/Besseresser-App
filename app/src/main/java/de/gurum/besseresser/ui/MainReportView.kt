package de.gurum.besseresser.ui

import android.net.Uri
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import de.gurum.besseresser.R
import de.gurum.besseresser.model.Evaluation
import de.gurum.besseresser.model.HistoryEntry
import de.gurum.besseresser.model.Settings

@Composable
internal fun MainReportView(
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
    onOpenConfig: () -> Unit,
    onEditRecognizedMeal: () -> Unit,
    onTakePhoto: () -> Unit,
    onSaveToHistory: () -> Unit,
    onDiscard: () -> Unit
) {
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
            onEditSettings = onEditSettings,
            onOpenConfig = onOpenConfig
        )

        if (!lastGoaled) {
            MainHeroCard(onTakePhoto = onTakePhoto)
        } else {
            photoUri?.let { uri ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(230.dp)
                ) {
                    AsyncImage(
                        model = uri,
                        contentDescription = stringResource(R.string.photo_of_meal),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
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
            val det = evaluation?.recognizedMeal.orEmpty()
            if (summary.isNotBlank() || det.isNotBlank()) {
                CollapsibleSummaryBlock(
                    summary = summary,
                    recognizedMeal = det,
                    note = note,
                    onEditRecognizedMeal = { if (det.isNotBlank()) onEditRecognizedMeal() }
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
internal fun MainHeroCard(onTakePhoto: () -> Unit) {
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
                        .clip(CircleShape),
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
