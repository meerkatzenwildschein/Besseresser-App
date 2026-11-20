package de.gurum.besseresser.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import de.gurum.besseresser.R
import de.gurum.besseresser.model.HistoryEntry
import java.io.File
import kotlin.math.roundToInt

@Composable
fun HistoryItem(
    entry: HistoryEntry,
    onClick: () -> Unit,
    isLifted: Boolean = false,
    onPhotoFile: (HistoryEntry) -> File? = { null }
) {
    val scale by animateFloatAsState(
        targetValue = if (isLifted) 1.025f else 1f,
        animationSpec = tween(150),
        label = "lift-scale"
    )
    val shadowElevation by animateFloatAsState(
        targetValue = if (isLifted) 12f else 0f,
        animationSpec = tween(150),
        label = "lift-shadow"
    )
    val borderColor = if (isLifted) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(if (isLifted) 1.5.dp else 1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .shadow(shadowElevation.dp, RoundedCornerShape(16.dp))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val photoFile = onPhotoFile(entry)
            if (photoFile != null) {
                AsyncImage(
                    model = photoFile,
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
                            formatTime(entry.dateMillis),
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
                                        .background(gr.rating.color())
                                )
                            }
                        }
                    }
                }
                if (entry.recognizedMeal.isNotBlank()) {
                    Text(
                        stringResource(R.string.detected_prefix, entry.recognizedMeal),
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
 * Ermöglicht das flüssige vertikale Verschieben einer Mahlzeiten-Karte über beliebig viele
 * Einträge innerhalb desselben Tages per Long-Press.
 * Wichtig: pointerInput ist nur an [entryId] gebunden (nicht an [currentIndexOnDay]), damit die
 * laufende Zieh-Geste beim Platztausch nicht abgebrochen wird, sondern nahtlos weiterläuft.
 */
@Composable
fun ReorderableDayMealBox(
    entryId: Long,
    currentIndexOnDay: Int,
    totalCountOnDay: Int,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    content: @Composable (isDragging: Boolean) -> Unit
) {
    if (totalCountOnDay <= 1) {
        content(false)
        return
    }

    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val spacingPx = remember(density) { with(density) { 10.dp.toPx() } }
    val defaultCardHeightPx = remember(density) { with(density) { 88.dp.toPx() } }
    val edgeRubberBandPx = remember(density) { with(density) { 10.dp.toPx() } }

    var cardHeightPx by remember { mutableFloatStateOf(defaultCardHeightPx) }
    var isDragging by remember(entryId) { mutableStateOf(false) }
    var dragAccumY by remember(entryId) { mutableFloatStateOf(0f) }

    val latestIndex by rememberUpdatedState(currentIndexOnDay)
    val latestTotal by rememberUpdatedState(totalCountOnDay)
    val latestOnMove by rememberUpdatedState(onMove)

    val settleOffset by animateFloatAsState(
        targetValue = 0f,
        animationSpec = tween(durationMillis = 140),
        label = "reorder-settle-y"
    )
    val displayOffsetY = if (isDragging) dragAccumY else settleOffset

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 10f else 0f)
            .onGloballyPositioned { coords ->
                if (coords.size.height > 0) {
                    cardHeightPx = coords.size.height.toFloat()
                }
            }
            .offset { IntOffset(0, displayOffsetY.roundToInt()) }
            .pointerInput(entryId) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        isDragging = true
                        dragAccumY = 0f
                    },
                    onDragEnd = {
                        isDragging = false
                        dragAccumY = 0f
                    },
                    onDragCancel = {
                        isDragging = false
                        dragAccumY = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val stepPx = (cardHeightPx + spacingPx).coerceAtLeast(1f)
                        val thresholdPx = stepPx * 0.55f

                        var localIdx = latestIndex
                        val total = latestTotal
                        dragAccumY += dragAmount.y

                        // Flüssig auch über mehrere Nachbarkarten hinweg weiterrücken
                        while (dragAccumY > thresholdPx && localIdx < total - 1) {
                            val nextIdx = localIdx + 1
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            latestOnMove(localIdx, nextIdx)
                            dragAccumY -= stepPx
                            localIdx = nextIdx
                        }
                        while (dragAccumY < -thresholdPx && localIdx > 0) {
                            val prevIdx = localIdx - 1
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            latestOnMove(localIdx, prevIdx)
                            dragAccumY += stepPx
                            localIdx = prevIdx
                        }

                        // Harte Tagesgrenzen: am ersten und letzten Eintrag des Tages sanft stoppen
                        val minDrag = if (localIdx <= 0) -edgeRubberBandPx else -stepPx
                        val maxDrag = if (localIdx >= total - 1) edgeRubberBandPx else stepPx
                        dragAccumY = dragAccumY.coerceIn(minDrag, maxDrag)
                    }
                )
            }
    ) {
        content(isDragging)
    }
}

/**
 * Begrenztes Links-Schieben mit Material 3 [SwipeToDismissBox]:
 * Wird die Karte nach links geschoben und losgelassen, löst [onTriggerDelete] den deaktivierten „Rückgängig"-Zustand aus.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortSwipeToDeleteBox(
    enabled: Boolean = true,
    onTriggerDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    if (!enabled) {
        content()
        return
    }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onTriggerDelete()
                false
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = enabled,
        backgroundContent = {
            if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(88.dp)
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
        },
        content = {
            content()
        }
    )
}
