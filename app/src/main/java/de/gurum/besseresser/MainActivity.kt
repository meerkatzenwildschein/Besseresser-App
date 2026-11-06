package de.gurum.besseresser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// ----------------------------------------------------------------- Farb- & Form-Theme (Besseresser)

private val BesseresserLightColors = lightColorScheme(
    primary = Color(0xFF1B7F4C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6F5E3),
    onPrimaryContainer = Color(0xFF062E18),
    secondary = Color(0xFF3E6652),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC0ECD2),
    onSecondaryContainer = Color(0xFF0C2418),
    tertiary = Color(0xFF286375),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC3E8F6),
    onTertiaryContainer = Color(0xFF001F28),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6FAF7),
    onBackground = Color(0xFF161D19),
    surface = Color(0xFFF6FAF7),
    onSurface = Color(0xFF161D19),
    surfaceVariant = Color(0xFFE6EFE9),
    onSurfaceVariant = Color(0xFF404943),
    outline = Color(0xFF707973),
    outlineVariant = Color(0xFFC0C9C2)
)

private val BesseresserDarkColors = darkColorScheme(
    primary = Color(0xFF63D999),
    onPrimary = Color(0xFF00391D),
    primaryContainer = Color(0xFF0E522E),
    onPrimaryContainer = Color(0xFF80F6B3),
    secondary = Color(0xFFA5CFB6),
    onSecondary = Color(0xFF0F3725),
    secondaryContainer = Color(0xFF274E3B),
    onSecondaryContainer = Color(0xFFC0ECD2),
    tertiary = Color(0xFF97CFE2),
    onTertiary = Color(0xFF003543),
    tertiaryContainer = Color(0xFF094C5D),
    onTertiaryContainer = Color(0xFFC3E8F6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1512),
    onBackground = Color(0xFFDFE4DF),
    surface = Color(0xFF0F1512),
    onSurface = Color(0xFFDFE4DF),
    surfaceVariant = Color(0xFF1E2823),
    onSurfaceVariant = Color(0xFFBFC9C2),
    outline = Color(0xFF89938C),
    outlineVariant = Color(0xFF3F4943)
)

private val BesseresserShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

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
        colorScheme = if (darkTheme) BesseresserDarkColors else BesseresserLightColors,
        shapes = BesseresserShapes
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
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
                            val slideSpec = tween<androidx.compose.ui.unit.IntOffset>(
                                durationMillis = 280,
                                easing = FastOutSlowInEasing
                            )
                            val fadeSpec = tween<Float>(durationMillis = 220)
                            if (forward) {
                                slideInHorizontally(slideSpec) { it } + fadeIn(fadeSpec) togetherWith
                                        slideOutHorizontally(slideSpec) { -it / 3 } + fadeOut(fadeSpec)
                            } else {
                                slideInHorizontally(slideSpec) { -it / 3 } + fadeIn(fadeSpec) togetherWith
                                        slideOutHorizontally(slideSpec) { it } + fadeOut(fadeSpec)
                            }
                        }
                    ) { s ->
                        when (s) {
                            AppScreen.Main -> MainScreen(
                                settings = settings,
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
                                    ProfileStore.save(ctx, p.birthDate, p.weightKg, p.heightCm, p.notes, p.gender)
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
                                        settings = settings,
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
