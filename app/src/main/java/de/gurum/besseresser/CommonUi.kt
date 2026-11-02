package de.gurum.besseresser

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


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
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.cd_back_no_save)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.weight(1f)
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center
        ) { trailing() }
    }
}

/** Roter Fehler-Hinweisblock – Material-Farben, identisch in Review/Main/Profil. */
@Composable
fun ErrorBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            stringResource(R.string.error_prefix, message),
            modifier = Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.onErrorContainer
        )
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
        onClick = { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .animateContentSize()
        ) {
            if (summary.isNotBlank()) {
                Text(stringResource(R.string.summary_prefix, summary))
            }
            if (expanded) {
                if (summary.isNotBlank()) Spacer(Modifier.height(4.dp))
                if (erkannt.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.detected_prefix, erkannt),
                            modifier = if (onEditErkannt != null) Modifier.weight(1f) else Modifier
                        )
                        if (onEditErkannt != null) {
                            IconButton(
                                onClick = { onEditErkannt.invoke() },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Create,
                                    contentDescription = stringResource(R.string.erkannt_edit_desc),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
                if (note.isNotBlank()) {
                    Text(stringResource(R.string.history_note_prefix, note))
                }
            }
            if (extraCount > 0) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (expanded)
                            stringResource(R.string.details_toggle_less)
                        else ctx.resources.getQuantityString(R.plurals.details_toggle_more, extraCount, extraCount),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.rotate(chevron)
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

@Composable
fun GoalRow(goal: String, res: GoalResult?) {
    val color = ampelColor(res?.ampel ?: "unbekannt")
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(color, CircleShape)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(goal, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                if (res != null) {
                    Text(
                        "${ampelLabel(res.ampel)} – ${res.begruendung}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(stringResource(R.string.main_no_rating_yet), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
