package de.gurum.besseresser.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import de.gurum.besseresser.R
import de.gurum.besseresser.model.HistoryEntry
import java.io.File

/**
 * Deaktivierte Darstellung eines zum Löschen vorgemerkten Eintrags direkt an seinem Listenplatz:
 * Gedimmter Inhalt mit durchgestrichenem Text, „Rückgängig"-Button und 5-Sekunden-Ablaufleiste.
 */
@Composable
fun PendingDeleteHistoryItem(
    entry: HistoryEntry,
    onUndo: () -> Unit,
    onPhotoFile: (HistoryEntry) -> File? = { null }
) {
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
                val photoFile = onPhotoFile(entry)
                Box(modifier = Modifier.alpha(0.32f)) {
                    if (photoFile != null) {
                        AsyncImage(
                            model = photoFile,
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
                                formatTime(entry.dateMillis),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    if (entry.recognizedMeal.isNotBlank()) {
                        Text(
                            entry.recognizedMeal,
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
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.65f),
                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
            )
        }
    }
}
