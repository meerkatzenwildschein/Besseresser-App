package de.gurum.besseresser.ui
import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import de.gurum.besseresser.data.UiPrefsRepository
import de.gurum.besseresser.model.AppLanguage
import de.gurum.besseresser.model.AppThemeMode

/** Erzeugt einen ContextWrapper mit der gewählten App-Sprache, behält aber den Basis-Activity-Context bei. */
fun Context.withAppLanguage(language: AppLanguage): Context {
    val targetLocale = language.locale
    java.util.Locale.setDefault(targetLocale)
    val config = android.content.res.Configuration(resources.configuration)
    config.setLocale(targetLocale)
    config.setLocales(android.os.LocaleList(targetLocale))
    val localized = createConfigurationContext(config)
    return object : android.content.ContextWrapper(this) {
        override fun getResources(): android.content.res.Resources = localized.resources
        override fun getAssets(): android.content.res.AssetManager = localized.assets
    }
}

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

/** Routen der Root-Navigation (Navigation Compose). */
object Routes {
    const val MAIN = "main"
    const val SETUP = "setup"
    const val PROFILE = "profile"
    const val HISTORY = "history"
    const val CONFIG = "config"
    const val DETAIL = "detail/{entryId}"
    fun detail(entryId: Long) = "detail/$entryId"
}

class MainActivity : ComponentActivity() {

    private val uiPrefsRepository: UiPrefsRepository by inject()

    override fun attachBaseContext(newBase: Context) {
        // Einmaliger synchroner Sprach-Read beim Activity-Start (Koin läuft bereits);
        // danach fließt die Sprache reaktiv über den AppStateViewModel.
        val lang = runBlocking { uiPrefsRepository.currentLanguage() }
        super.attachBaseContext(newBase.withAppLanguage(lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { BesseresserAppContent() }
    }
}

@Composable
fun BesseresserAppContent() {
    val baseCtx = LocalContext.current
    val appStateViewModel: AppStateViewModel = koinViewModel()
    val appState by appStateViewModel.uiState.collectAsStateWithLifecycle()

    val localizedCtx = remember(baseCtx, appState.language) {
        baseCtx.withAppLanguage(appState.language)
    }
    val localizedConfig = remember(localizedCtx) {
        localizedCtx.resources.configuration
    }

    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (appState.themeMode) {
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
        AppThemeMode.SYSTEM -> systemDark
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (baseCtx as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(
        LocalContext provides localizedCtx,
        LocalConfiguration provides localizedConfig
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) BesseresserDarkColors else BesseresserLightColors,
            shapes = BesseresserShapes
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Box(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                    if (!appState.loaded) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        AppNavHost(
                            appState = appState,
                            appStateViewModel = appStateViewModel,
                            baseCtx = baseCtx
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppNavHost(
    appState: AppState,
    appStateViewModel: AppStateViewModel,
    baseCtx: Context
) {
    val navController = rememberNavController()
    val mainViewModel: MainViewModel = koinViewModel()
    val startDestination = if (appState.settings.isComplete) Routes.MAIN else Routes.SETUP

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(220))
        },
        exitTransition = {
            slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { -it / 3 } + fadeOut(tween(220))
        },
        popEnterTransition = {
            slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { -it / 3 } + fadeIn(tween(220))
        },
        popExitTransition = {
            slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(220))
        }
    ) {
        composable(Routes.MAIN) {
            MainScreen(
                settings = appState.settings,
                onEditSettings = { navController.navigate(Routes.SETUP) },
                onOpenProfile = { navController.navigate(Routes.PROFILE) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenConfig = { navController.navigate(Routes.CONFIG) },
                viewModel = mainViewModel
            )
        }
        composable(Routes.SETUP) {
            SetupScreen(
                initial = appState.settings,
                firstRun = !appState.settings.isComplete,
                onCancel = {
                    if (appState.settings.isComplete) navController.popBackStack()
                    else (baseCtx as? Activity)?.finish()
                },
                onDone = { neu ->
                    val firstRun = !appState.settings.isComplete
                    appStateViewModel.saveSettings(neu)
                    if (firstRun) {
                        // Nach dem ERSTEN Erfassen einmalig das Profil öffnen (Setup aus dem Stack lösen)
                        navController.navigate(Routes.MAIN) { popUpTo(Routes.SETUP) { inclusive = true } }
                        navController.navigate(Routes.PROFILE)
                    } else {
                        navController.popBackStack()
                    }
                },
                onBackupImported = { importedSettings ->
                    if (importedSettings.isComplete) {
                        navController.navigate(Routes.MAIN) { popUpTo(Routes.SETUP) { inclusive = true } }
                    }
                },
                viewModel = koinViewModel()
            )
        }
        composable(Routes.PROFILE) {
            ProfileScreen(
                initial = appState.profile,
                onCancel = { navController.popBackStack() },
                onSave = { p ->
                    appStateViewModel.saveProfile(p)
                    navController.popBackStack()
                },
                viewModel = koinViewModel()
            )
        }
        composable(Routes.HISTORY) {
            HistoryScreen(
                settings = appState.settings,
                profile = appState.profile,
                onPick = { navController.navigate(Routes.detail(it.id)) },
                onBack = { navController.popBackStack() },
                viewModel = koinViewModel()
            )
        }
        composable(Routes.CONFIG) {
            ConfigScreen(
                themeMode = appState.themeMode,
                onThemeModeChange = { appStateViewModel.setThemeMode(it) },
                appLanguage = appState.language,
                onLanguageChange = { appStateViewModel.setLanguage(it) },
                onBack = { navController.popBackStack() },
                onDataImported = { },
                onHistoryCleared = {
                    mainViewModel.reset()
                    navController.popBackStack(Routes.MAIN, inclusive = false)
                },
                viewModel = koinViewModel()
            )
        }
        composable(
            route = Routes.DETAIL,
            arguments = listOf(navArgument("entryId") { type = NavType.LongType })
        ) { backStackEntry ->
            val entryId = backStackEntry.arguments?.getLong("entryId") ?: return@composable
            HistoryDetailScreen(
                entryId = entryId,
                settings = appState.settings,
                onBack = { navController.popBackStack() },
                viewModel = koinViewModel { parametersOf(entryId) }
            )
        }
    }
}
