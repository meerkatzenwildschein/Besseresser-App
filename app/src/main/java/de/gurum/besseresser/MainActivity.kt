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
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ----------------------------------------------------------------- Activity & Navigation

/** Reihenfolge für Animationsrichtung: vor = nach rechts tiefer, zurück = nach links höher. */
enum class AppScreen(val order: Int) { Main(0), Profile(1), History(1), Detail(2) }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GesundEssenApp() }
    }
}

@Composable
fun GesundEssenApp() {
    // Folgt dem System-Theme: hell im hellen, dunkel im dunklen Modus
    val darkTheme = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            val ctx = LocalContext.current
            var loaded by remember { mutableStateOf(false) }
            var showSetup by remember { mutableStateOf(true) }
            var settings by remember { mutableStateOf(Settings("", "", "", emptyList())) }
            var showHistory by remember { mutableStateOf(false) }
            var historyEntry by remember { mutableStateOf<HistoryEntry?>(null) }
            var showProfile by remember { mutableStateOf(false) }
            var profile by remember { mutableStateOf(Profile()) }

            LaunchedEffect(Unit) {
                settings = Store.load(ctx)
                profile = ProfileStore.load(ctx)
                showSetup = !settings.isComplete
                loaded = true
            }

            // Einstellungs-Overlay über einem bestehenden Setup: Android-Zurück schließt
            // es (wie der Zurückpfeil). Beim Erst-Setup (unvollständig) bleibt Zurück = App beenden.
            if (loaded && showSetup && settings.isComplete) {
                BackHandler { showSetup = false }
            }

            when {
                !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                showSetup -> SetupScreen(
                    initial = settings,
                    firstRun = !settings.isComplete,
                    onCancel = {
                        if (settings.isComplete) showSetup = false
                        else (ctx as? android.app.Activity)?.finish()
                    },
                    onDone = { neu ->
                        val firstRun = !settings.isComplete
                        Store.save(ctx, neu)
                        settings = neu
                        showSetup = false
                        // Nach dem ERSTEN Erfassen der KI-Zugangsdaten einmalig das Profil öffnen
                        if (firstRun) showProfile = true
                    }
                )
                else -> {
                    val target = when {
                        historyEntry != null -> AppScreen.Detail
                        showProfile -> AppScreen.Profile
                        showHistory -> AppScreen.History
                        else -> AppScreen.Main
                    }
                    AnimatedContent(
                        targetState = target,
                        label = "screen-switch",
                        transitionSpec = {
                            // vorwärts: neuer Screen von rechts; rückwärts: von links
                            val forward = targetState.order > initialState.order
                            if (forward) {
                                slideInHorizontally { it } + fadeIn() togetherWith
                                        slideOutHorizontally { -it / 3 } + fadeOut()
                            } else {
                                slideInHorizontally { -it / 3 } + fadeIn() togetherWith
                                        slideOutHorizontally { it } + fadeOut()
                            }
                        }
                    ) { s ->
                        when (s) {
                            AppScreen.Main -> MainScreen(
                                settings = settings,
                                profile = profile,
                                onEditSettings = { showSetup = true },
                                onOpenProfile = { showProfile = true },
                                onOpenHistory = {
                                    showHistory = true
                                    historyEntry = null
                                }
                            )
                            AppScreen.Profile -> ProfileScreen(
                                initial = profile,
                                onCancel = { showProfile = false },
                                onSave = { p ->
                                    ProfileStore.save(ctx, p.birthDate, p.weightKg, p.heightCm, p.notes)
                                    profile = p
                                    showProfile = false
                                }
                            )
                            AppScreen.History -> HistoryScreen(
                                settings = settings,
                                profile = profile,
                                onPick = { historyEntry = it },
                                onBack = { showHistory = false }
                            )
                            AppScreen.Detail -> {
                                // Wichtig-Pitfall: onBack nullt historyEntry, während der Detail-Screen
                                // als outgoing-Content der Back-Animation NOCH komponiert ist. Ein
                                // `historyEntry!!` direkt im Branch würde beim Re-Compose mit null
                                // crashen. Deshalb: Eintrag einmalig per remember einfrieren.
                                val entry = remember { historyEntry }
                                if (entry != null) {
                                    HistoryDetailScreen(
                                        entry = entry,
                                        onBack = { historyEntry = null }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
