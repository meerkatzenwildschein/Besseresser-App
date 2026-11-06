package de.gurum.besseresser

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


// ----------------------------------------------------------------- Gemeinsame UI-Bausteine (von Main, Verlauf-Detail und Profil genutzt)

/** Top-Leiste: Zurueck-Pfeil links, Titel mittig, optionales Element rechts.
 *  Optional kleines Icon vor dem Titel (Wiedererkennung: dasselbe Symbol wie im Burger-Menü).
 *  Der rechte Slot hat immer 48dp (Icon-Größe), damit der Titel frei von trailing-Inhalt mittig bleibt. */
@Composable
fun TopBarRow(
    title: String,
    onBack: () -> Unit,
    trailing: @Composable () -> Unit = {},
    icon: ImageVector? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
            modifier = Modifier.size(42.dp)
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back_no_save),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            Text(
                title,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier.defaultMinSize(minWidth = 42.dp, minHeight = 42.dp),
            contentAlignment = Alignment.CenterEnd
        ) { trailing() }
    }
}

/** Roter Fehler-Hinweisblock – Material-Farben, identisch in Review/Main/Profil. */
@Composable
fun ErrorBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.error_prefix, message),
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}

/**
 * Zusammenfassungs-Block: 🧠 Gesamteindruck immer sichtbar; 🍽️ „Erkannt" und 📝 Anmerkung
 * eingeklappt hinter einem Toggle (der ganze Block antippbar = großes Touch-Ziel), Chevron
 * rotiert mit, animateContentSize animiert das Auf-/ Zuklappen. Nutzt Auswertung UND
 * Historie-Detail – bewusst identisch verhalten. Pencil an der 🍽️-Zeile (wenn onEditErkannt
 * gesetzt) öffnet den ErkanntEditDialog – der erkannt-Text ist userkorrigierbar.
 */
@Composable
fun CollapsibleSummaryBlock(
    summary: String,
    erkannt: String,
    note: String,
    onEditErkannt: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    val extraCount = listOf(erkannt.isNotBlank(), note.isNotBlank()).count { it }
    var expanded by remember { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")

    Surface(
        onClick = { if (extraCount > 0) expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .animateContentSize()
        ) {
            if (summary.isNotBlank()) {
                Text(
                    stringResource(R.string.summary_prefix, summary),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (expanded && extraCount > 0) {
                if (summary.isNotBlank()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (erkannt.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    stringResource(R.string.detected_prefix, erkannt),
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    modifier = if (onEditErkannt != null) Modifier.weight(1f) else Modifier
                                )
                                if (onEditErkannt != null) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        IconButton(
                                            onClick = { onEditErkannt.invoke() },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                Icons.Filled.Create,
                                                contentDescription = stringResource(R.string.erkannt_edit_desc),
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (note.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(R.string.history_note_prefix, note),
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
            if (extraCount > 0) {
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (expanded)
                            stringResource(R.string.details_toggle_less)
                        else ctx.resources.getQuantityString(R.plurals.details_toggle_more, extraCount, extraCount),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(16.dp)
                            .rotate(chevron)
                    )
                }
            }
        }
    }
}

/** Dialog zum direkten Korrigieren des erkannt-Essens-Textes. */
@Composable
fun ErkanntEditDialog(initialText: String, onOk: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.erkannt_edit_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 2,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onOk(text.trim()) }) { Text(stringResource(R.string.profile_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/** Kleines trailing-Icon im Anmerkungsfeld: Liste der letzten 5 Eingaben.
 *  Auswahl setzt den Text und verschiebt den Eintrag an die Spitze (MRU). */
@Composable
fun RecentNotesButton(onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    var recent by remember { mutableStateOf(RecentNotesStore.load(ctx)) }
    var open by remember { mutableStateOf(false) }
    val isEnabled = remember(open) { RecentNotesStore.load(ctx).isNotEmpty() }
    Box {
        IconButton(
            onClick = {
                // Anzeigereihenfolge NUR beim Öffnen: Sternchen nach oben (in-place-Toggling
                // darf nicht hüpfen), unmarkierte behalten ihre MRU-Reihenfolge
                recent = RecentNotesStore.load(ctx, sortStarredFirst = true)
                open = true
            },
            enabled = isEnabled,
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.List,
                contentDescription = stringResource(R.string.recent_notes_desc),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            recent.forEach { n ->
                DropdownMenuItem(
                    leadingIcon = {
                        // Sternchen = Favorit: ausgefüllt/umrissen; Toggle schließt das Menü NICHT
                        IconButton(onClick = {
                            // Eintrag vesconcem in der SICHTBAREN Liste kippen und genau diese
                            // Reihenfolge persistieren — kein Laden/Sortieren zwischendurch,
                            // sonst springt der Eintrag (unds dass sortierende load() lagern lässt)
                            val updated = recent.map {
                                if (it.text == n.text) RecentNote(it.text, !it.starred) else it
                            }
                            RecentNotesStore.save(ctx, updated)
                            recent = updated
                        }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = stringResource(R.string.recent_notes_star_desc),
                                // Core-Library kennt nur den gefüllten Stern: Favorit = primary-farbig,
                                // unmarkiert = deselect-dimmed grau statt Umriss
                                tint = if (n.starred) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    text = {
                        Text(
                            n.text,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = {
                        open = false
                        RecentNotesStore.push(ctx, n.text)   // Auswahl rutscht nach oben (MRU)
                        recent = RecentNotesStore.load(ctx)
                        onPick(n.text)
                    }
                )
            }
        }
    }
}

@Composable
fun GoalRow(goal: String, res: GoalResult?) {
    val isRated = res != null && res.ampel != TrafficLight.UNKNOWN
    val color = res?.ampel?.color() ?: TrafficLight.UNKNOWN.color()
    val containerColor = MaterialTheme.colorScheme.surfaceVariant
    val borderStroke = if (isRated) {
        BorderStroke(1.dp, color.copy(alpha = 0.4f))
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = borderStroke,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Zweistufiger Ampel-Indikator (Halo-Ring + satter Kern)
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(color.copy(alpha = if (isRated) 0.20f else 0.14f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(color, CircleShape)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        goal,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.5.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (res != null) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = color.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, color.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = res.ampel.label(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isRated) color else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (res != null && res.begruendung.isNotBlank()) {
                    Text(
                        res.begruendung,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (res != null) {
                    Text(
                        res.ampel.label(),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        stringResource(R.string.main_no_rating_yet),
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                }
            }
        }
    }
}

/** Wiederverwendbare Gruppierungskarte für Formular-Abschnitte (Setup & Profil). */
@Composable
fun SectionCard(
    title: String? = null,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!title.isNullOrBlank()) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            content()
        }
    }
}
