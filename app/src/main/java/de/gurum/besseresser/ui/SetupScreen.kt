package de.gurum.besseresser.ui
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import de.gurum.besseresser.R
import de.gurum.besseresser.BuildConfig
import de.gurum.besseresser.data.ApiClient
import de.gurum.besseresser.data.ApiClient.ModelsResult
import de.gurum.besseresser.data.ApiException
import de.gurum.besseresser.data.NetworkSecurity
import de.gurum.besseresser.data.UrlSecurityStatus
import de.gurum.besseresser.model.Settings
import de.gurum.besseresser.model.WeightedGoal
import de.gurum.besseresser.model.toWeightedGoals

// ----------------------------------------------------------------- Gängige Anbieter

private data class ProviderEntry(val name: String, val url: String, val noteRes: Int = 0)

/** Gängige OpenAI-kompatible Anbieter mit Basis-URL; Notizen lokalisiert via Ressourcen. */
private val PROVIDERS = listOf(
    ProviderEntry("OpenAI", "https://api.openai.com/v1"),
    ProviderEntry("OpenRouter", "https://openrouter.ai/api/v1", R.string.provider_note_aggregator),
    ProviderEntry("Mammouth", "https://api.mammouth.ai/v1", R.string.provider_note_fr),
    ProviderEntry("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/", R.string.provider_note_free_quota),
    ProviderEntry("Mistral", "https://api.mistral.ai/v1"),
    ProviderEntry("Groq", "https://api.groq.com/openai/v1", R.string.provider_note_fast),
    ProviderEntry("DeepSeek", "https://api.deepseek.com/v1", R.string.provider_note_text_only),
    ProviderEntry("xAI (Grok)", "https://api.x.ai/v1"),
    ProviderEntry("Together AI", "https://api.together.xyz/v1"),
    ProviderEntry("Fireworks AI", "https://api.fireworks.ai/inference/v1"),
    ProviderEntry("Perplexity", "https://api.perplexity.ai"),
    ProviderEntry("Anthropic (Kompatibilitäts-Modus)", "https://api.anthropic.com/v1/", R.string.provider_note_compat)
)

// ----------------------------------------------------------------- Einstellungen (Setup)

/**
 * Einstellungen: Top-Bar (Zurück ohne Speichern), Pflichtfelder mit rotem Stern,
 * "Modelle laden" öffnet ModelListScreen mit Debug-Report bei Fehlern.
 * Standard-Ziele: string-array "default_goals" (lokalisiert).
 */
@Composable
fun SetupScreen(
    initial: Settings,
    firstRun: Boolean,
    onCancel: () -> Unit,
    onDone: (Settings) -> Unit,
    onBackupImported: ((Settings) -> Unit)? = null,
    viewModel: SetupViewModel = koinViewModel()
) {
    val ctx = LocalContext.current
    val defaultGoals = remember { ctx.resources.getStringArray(R.array.default_goals).toList() }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var importingBackup by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    val backupImportLauncher = rememberLauncherForActivityResult(OpenBackupDocumentContract()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importingBackup = true
        importError = null
        viewModel.importBackup(uri, defaultGoals) { res ->
            importingBackup = false
            res.onSuccess { (updatedSettings, summary) ->
                val mealsStr = ctx.resources.getQuantityString(R.plurals.backup_meals_count, summary.entriesCount, summary.entriesCount)
                val photosStr = ctx.resources.getQuantityString(R.plurals.backup_photos_count, summary.photosCount, summary.photosCount)
                Toast.makeText(
                    ctx,
                    ctx.getString(R.string.config_import_success, mealsStr, photosStr),
                    Toast.LENGTH_LONG
                ).show()
                onBackupImported?.invoke(updatedSettings)
            }.onFailure { e ->
                importError = e.message ?: ctx.getString(R.string.config_import_failed)
            }
        }
    }

    LaunchedEffect(initial) {
        viewModel.init(initial, defaultGoals)
    }

    if (uiState.showModels) {
        ModelListScreen(
            url = uiState.url,
            apiKey = uiState.key,
            onPick = {
                viewModel.setModel(it)
                viewModel.setShowModels(false)
            },
            onBack = { viewModel.setShowModels(false) },
            onFetchModels = { u, k -> viewModel.fetchModels(u, k) }
        )
        return
    }

    val valid = NetworkSecurity.isPermittedUrl(uiState.url) && uiState.key.isNotBlank() && uiState.model.isNotBlank() && !uiState.splitting

    val doSave: (List<WeightedGoal>, Boolean) -> Unit = { liste, wasSplit ->
        val finalGoals = liste.ifEmpty { defaultGoals.toWeightedGoals() }
        onDone(Settings(uiState.url.trim(), uiState.key.trim(), uiState.model.trim(), finalGoals, wasSplit, uiState.retentionWeeks.trim().toIntOrNull() ?: Settings.DEFAULT_RETENTION_WEEKS))
    }

    val save: () -> Unit = {
        val currentGoals = uiState.goals.ifEmpty { defaultGoals.toWeightedGoals() }
        doSave(currentGoals, true)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopBarRow(
                title = stringResource(R.string.menu_settings),
                onBack = onCancel,
                trailing = { },
                icon = Icons.Filled.Settings
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SetupHeaderCard(
                    firstRun = firstRun,
                    hasBackupImport = onBackupImported != null,
                    importingBackup = importingBackup,
                    isSplitting = uiState.splitting,
                    onImportBackupClick = { backupImportLauncher.launch(arrayOf("*/*")) }
                )

                importError?.let { msg ->
                    ErrorBanner(msg)
                }

                SetupApiSection(
                    url = uiState.url,
                    key = uiState.key,
                    model = uiState.model,
                    onUrlChange = { viewModel.setUrl(it) },
                    onKeyChange = { viewModel.setKey(it) },
                    onModelChange = { viewModel.setModel(it) },
                    onShowProviders = { viewModel.setShowProviders(true) },
                    onShowModels = { viewModel.setShowModels(true) }
                )

                SetupGoalsSection(
                    goals = uiState.goals,
                    newGoalText = uiState.newGoalText,
                    splitting = uiState.splitting,
                    canAdd = NetworkSecurity.isPermittedUrl(uiState.url) && uiState.key.isNotBlank(),
                    onNewGoalTextChange = { viewModel.setNewGoalText(it) },
                    onAddGoal = {
                        viewModel.addGoalsFromText(
                            splitIntro = ctx.getString(R.string.prompt_split_intro),
                            splitRules = ctx.getString(R.string.prompt_split_rules)
                        )
                    },
                    onMoveUp = { viewModel.moveGoalUp(it) },
                    onMoveDown = { viewModel.moveGoalDown(it) },
                    onDelete = { viewModel.removeGoal(it) },
                    onSetWeight = { index, weight -> viewModel.setGoalWeight(index, weight) }
                )

                SetupRetentionSection(
                    retentionWeeks = uiState.retentionWeeks,
                    onRetentionWeeksChange = { viewModel.setRetentionWeeks(it) }
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.build_label, BuildConfig.APP_VERSION, BuildConfig.BUILD_TIME),
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Spacer(Modifier.height(95.dp))
            }
        }

        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            SetupBottomSaveBar(
                valid = valid,
                isSplitting = uiState.splitting,
                onSave = save
            )
        }
    }

    if (uiState.showProviders) {
        ProviderPickerDialog(
            currentUrl = uiState.url,
            onSelectProvider = {
                viewModel.setUrl(it)
                viewModel.setShowProviders(false)
            },
            onDismiss = { viewModel.setShowProviders(false) }
        )
    }
}

@Composable
private fun SetupHeaderCard(
    firstRun: Boolean,
    hasBackupImport: Boolean,
    importingBackup: Boolean,
    isSplitting: Boolean,
    onImportBackupClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (firstRun) {
                Text(
                    stringResource(R.string.setup_welcome),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    stringResource(R.string.setup_welcome_text),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
                if (hasBackupImport) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = onImportBackupClick,
                        enabled = !importingBackup && !isSplitting,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (importingBackup) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(
                                Icons.Filled.Build,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            stringResource(R.string.setup_import_backup_button),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            } else {
                Text(
                    stringResource(R.string.setup_edit_title),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    stringResource(R.string.setup_edit_text),
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
            }
        }
    }
}

@Composable
private fun SetupApiSection(
    url: String,
    key: String,
    model: String,
    onUrlChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onShowProviders: () -> Unit,
    onShowModels: () -> Unit
) {
    val urlStatus = remember(url) { NetworkSecurity.assessUrl(url) }
    val isUrlError = url.isNotBlank() && (urlStatus == UrlSecurityStatus.INSECURE_REMOTE_HTTP || urlStatus == UrlSecurityStatus.INVALID)

    SectionCard(title = stringResource(R.string.setup_section_api)) {
        LabelledField(stringResource(R.string.setup_url_label)) {
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                placeholder = { Text("https://api.openai.com/v1") },
                isError = isUrlError,
                supportingText = {
                    when (urlStatus) {
                        UrlSecurityStatus.INSECURE_REMOTE_HTTP -> {
                            Text(
                                stringResource(R.string.setup_url_error_insecure_http),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        UrlSecurityStatus.INVALID -> {
                            if (url.isNotBlank()) {
                                Text(
                                    stringResource(R.string.setup_url_error_invalid),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        UrlSecurityStatus.LOCAL_CLEARTEXT_HTTP -> {
                            Text(
                                stringResource(R.string.setup_url_hint_local_http),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        UrlSecurityStatus.SECURE_HTTPS -> null
                    }
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        FilledTonalButton(
            onClick = onShowProviders,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.setup_pick_provider), fontWeight = FontWeight.SemiBold)
        }

        LabelledField(stringResource(R.string.setup_key_label)) {
            OutlinedTextField(
                value = key,
                onValueChange = onKeyChange,
                placeholder = { Text(stringResource(R.string.setup_key_placeholder)) },
                visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        LabelledField(stringResource(R.string.setup_model_label)) {
            OutlinedTextField(
                value = model,
                onValueChange = onModelChange,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        FilledTonalButton(
            onClick = onShowModels,
            enabled = NetworkSecurity.isPermittedUrl(url),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.setup_models_link), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SetupGoalsSection(
    goals: List<WeightedGoal>,
    newGoalText: String,
    splitting: Boolean,
    canAdd: Boolean,
    onNewGoalTextChange: (String) -> Unit,
    onAddGoal: () -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onSetWeight: (Int, Int) -> Unit
) {
    SectionCard(title = stringResource(R.string.setup_section_goals)) {
        Text(
            stringResource(R.string.setup_goals_hint),
            fontSize = 12.5.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (goals.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.setup_goals_empty),
                    fontSize = 12.5.sp,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                goals.forEachIndexed { index, goal ->
                    GoalChipItem(
                        goal = goal,
                        canMoveUp = index > 0 && !splitting,
                        canMoveDown = index < goals.size - 1 && !splitting,
                        enabled = !splitting,
                        onMoveUp = { onMoveUp(index) },
                        onMoveDown = { onMoveDown(index) },
                        onDelete = { onDelete(index) },
                        onSetWeight = { w -> onSetWeight(index, w) }
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        LabelledField(stringResource(R.string.setup_add_goal_label), required = false) {
            OutlinedTextField(
                value = newGoalText,
                onValueChange = onNewGoalTextChange,
                placeholder = { Text(stringResource(R.string.setup_add_goal_placeholder)) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                enabled = !splitting,
                minLines = 2
            )
        }

        Button(
            onClick = onAddGoal,
            enabled = canAdd && !splitting && newGoalText.isNotBlank(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (splitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.setup_ai_splitting_button), fontWeight = FontWeight.SemiBold)
            } else {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.setup_add_goal_button), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun GoalChipItem(
    goal: WeightedGoal,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    enabled: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onSetWeight: (Int) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = goal.text,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = onMoveUp,
                    enabled = canMoveUp && enabled,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.setup_goal_move_up),
                        tint = if (canMoveUp && enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = onMoveDown,
                    enabled = canMoveDown && enabled,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.setup_goal_move_down),
                        tint = if (canMoveDown && enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = onDelete,
                    enabled = enabled,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.setup_goal_delete),
                        tint = if (enabled) MaterialTheme.colorScheme.error.copy(alpha = 0.85f) else MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.setup_goal_weight_label, goal.weight),
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Normal
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    (WeightedGoal.MIN_WEIGHT..WeightedGoal.MAX_WEIGHT).forEach { w ->
                        val isSelected = goal.weight == w
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (isSelected) null else BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.size(width = 28.dp, height = 24.dp),
                            onClick = { if (enabled) onSetWeight(w) }
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Text(
                                    text = "$w",
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupRetentionSection(
    retentionWeeks: String,
    onRetentionWeeksChange: (String) -> Unit
) {
    SectionCard(title = stringResource(R.string.setup_section_retention)) {
        LabelledField(stringResource(R.string.setup_retention_label), required = false) {
            OutlinedTextField(
                value = retentionWeeks,
                onValueChange = onRetentionWeeksChange,
                placeholder = { Text(Settings.DEFAULT_RETENTION_WEEKS.toString()) },
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Text(
            stringResource(R.string.setup_retention_hint),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SetupBottomSaveBar(
    valid: Boolean,
    isSplitting: Boolean,
    onSave: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
    ) {
        Button(
            onClick = onSave,
            enabled = valid,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(
                stringResource(if (isSplitting) R.string.setup_ai_splitting_button else R.string.setup_save_continue),
                fontSize = 16.5.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun ProviderPickerDialog(
    currentUrl: String,
    onSelectProvider: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.provider_picker_title), fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 450.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(PROVIDERS) { p ->
                    val isSelected = currentUrl.trim().trimEnd('/') == p.url.trim().trimEnd('/')
                    Surface(
                        onClick = { onSelectProvider(p.url) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(p.name, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                            if (p.noteRes != 0) {
                                Text(
                                    stringResource(p.noteRes),
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                p.url,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}


// ----------------------------------------------------------------- Modell-Liste (eigener Screen)

/**
 * Modell-Screen: laedt {url}/models und zeigt die (vision-gefilterte) Liste an.
 * Bei Fehlern erscheint ein Debug-Report (Anfrage, Status, Rohantwort, Fehler)
 * in Monospace und kopierbar.
 */
@Composable
fun ModelListScreen(
    url: String,
    apiKey: String,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
    onFetchModels: suspend (String, String) -> Result<ApiClient.ModelsResult>
) {
    var loading by remember { mutableStateOf(true) }
    var models by remember { mutableStateOf(ApiClient.ModelsResult(emptyList(), emptyList())) }
    var debugReport by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val result = onFetchModels(url, apiKey)
        result.onSuccess {
            models = it
            loading = false
        }.onFailure { e ->
            debugReport = extractErrorDebugReport(e)
            loading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TopBarRow(
            title = stringResource(R.string.model_picker_title),
            onBack = onBack,
            trailing = { },
            icon = Icons.Filled.Settings
        )

        if (loading) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(14.dp)
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.models_loading, url.trimEnd('/')), fontSize = 13.5.sp)
                }
            }
        }

        if (debugReport != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                SelectionContainer {
                    Text(
                        debugReport.orEmpty(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
            Text(
                stringResource(R.string.models_debug_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (!loading && debugReport == null) {
            val liste = if (models.vision.isNotEmpty()) models.vision else models.all
            Text(
                stringResource(
                    if (models.vision.isNotEmpty()) R.string.models_vision_summary
                    else R.string.models_all_summary,
                    if (models.vision.isNotEmpty()) models.vision.size else models.all.size,
                    models.all.size
                ),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (liste.isEmpty()) {
                Text(stringResource(R.string.models_none_found), fontSize = 14.sp)
            } else {
                // Große Liste: eigener Scroll-Bereich im scrollenden Screen
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 620.dp)
                ) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(liste) { id ->
                            Surface(
                                onClick = { onPick(id) },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    id,
                                    fontSize = 13.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------- Gemeinsame Bausteine

/** Feld mit Label; Pflichtfelder bekommen einen roten Stern. */
@Composable
private fun LabelledField(
    label: String,
    required: Boolean = true,
    field: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (required) {
                Text(" *", fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
            }
        }
        field()
    }
}

private fun extractErrorDebugReport(e: Throwable): String {
    if (e is ApiException) {
        val report = e.debugReport
        if (!report.isNullOrBlank()) {
            return report
        }
    }
    val msg = e.message
    if (!msg.isNullOrBlank()) {
        return msg
    }
    return e.javaClass.simpleName
}
