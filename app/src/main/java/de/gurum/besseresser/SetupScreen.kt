package de.gurum.besseresser

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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

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
    viewModel: SetupViewModel = viewModel()
) {
    val ctx = LocalContext.current
    val defaultGoals = remember { ctx.resources.getStringArray(R.array.default_goals).toList() }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
            onBack = { viewModel.setShowModels(false) }
        )
        return
    }

    val valid = uiState.url.trim().startsWith("http") && uiState.key.isNotBlank() && uiState.model.isNotBlank() && !uiState.splitting

    val doSave: (List<String>, Boolean) -> Unit = { liste, wasSplit ->
        onDone(Settings(uiState.url.trim(), uiState.key.trim(), uiState.model.trim(), liste.ifEmpty { defaultGoals }, wasSplit))
    }

    val save: () -> Unit = {
        val text = uiState.goals.trim()
        when {
            text.isBlank() -> doSave(defaultGoals, true)
            initial.goalsWereSplit && !uiState.goalsDirty -> doSave(
                text.split("\n").map { it.trim() }.filter { it.isNotBlank() }, true
            )
            else -> viewModel.startSplitGoals(ctx, text, onDone = onDone)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            TopBarRow(
                title = stringResource(R.string.menu_settings),
                onBack = onCancel,
                trailing = { },
                icon = Icons.Filled.Settings
            )

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

            SectionCard(title = stringResource(R.string.setup_section_api)) {
                LabelledField(stringResource(R.string.setup_url_label)) {
                    OutlinedTextField(
                        value = uiState.url,
                        onValueChange = { viewModel.setUrl(it) },
                        placeholder = { Text("https://api.openai.com/v1") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                FilledTonalButton(
                    onClick = { viewModel.setShowProviders(true) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.setup_pick_provider), fontWeight = FontWeight.SemiBold)
                }

                LabelledField(stringResource(R.string.setup_key_label)) {
                    OutlinedTextField(
                        value = uiState.key,
                        onValueChange = { viewModel.setKey(it) },
                        placeholder = { Text(stringResource(R.string.setup_key_placeholder)) },
                        visualTransformation = PasswordVisualTransformation(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                LabelledField(stringResource(R.string.setup_model_label)) {
                    OutlinedTextField(
                        value = uiState.model,
                        onValueChange = { viewModel.setModel(it) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                FilledTonalButton(
                    onClick = { viewModel.setShowModels(true) },
                    enabled = uiState.url.trim().startsWith("http"),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.setup_models_link), fontWeight = FontWeight.SemiBold)
                }
            }

            SectionCard(title = stringResource(R.string.setup_section_goals)) {
                LabelledField(stringResource(R.string.setup_goals_label), required = false) {
                    OutlinedTextField(
                        value = uiState.goals,
                        onValueChange = { viewModel.setGoals(it) },
                        placeholder = { Text(defaultGoals.joinToString("\n")) },
                        shape = RoundedCornerShape(12.dp),
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (uiState.splitting) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.setup_ai_splitting_text), fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.build_label, BuildConfig.APP_VERSION, BuildConfig.BUILD_TIME),
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(Modifier.height(95.dp))
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
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
                onClick = save,
                enabled = valid,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    stringResource(if (uiState.splitting) R.string.setup_ai_splitting_button else R.string.setup_save_continue),
                    fontSize = 16.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    if (uiState.showProviders) {
        AlertDialog(
            onDismissRequest = { viewModel.setShowProviders(false) },
            title = { Text(stringResource(R.string.provider_picker_title), fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 450.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(PROVIDERS) { p ->
                        val isSelected = uiState.url.trim().trimEnd('/') == p.url.trim().trimEnd('/')
                        Surface(
                            onClick = {
                                viewModel.setUrl(p.url)
                                viewModel.setShowProviders(false)
                            },
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
                TextButton(onClick = { viewModel.setShowProviders(false) }) { Text(stringResource(R.string.close)) }
            }
        )
    }

    if (uiState.showSplit) {
        AlertDialog(
            onDismissRequest = { viewModel.setShowSplit(false) },
            title = {
                Text(
                    stringResource(
                        if (uiState.splitDebug == null) R.string.split_title_ok else R.string.split_title_fail
                    ),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (uiState.splitDebug != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            SelectionContainer {
                                Text(
                                    uiState.splitDebug.orEmpty(),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.split_local_hint),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(stringResource(R.string.split_check_hint), fontSize = 13.sp)
                    }
                    OutlinedTextField(
                        value = uiState.splitEdited,
                        onValueChange = { viewModel.setSplitEdited(it) },
                        shape = RoundedCornerShape(12.dp),
                        minLines = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val liste = uiState.splitEdited.split("\n").map { it.trim() }.filter { it.isNotBlank() }
                    viewModel.setShowSplit(false)
                    doSave(liste, true)
                }) { Text(stringResource(R.string.split_save)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.setShowSplit(false)
                    doSave(listOf(uiState.splitRaw), true)
                }) { Text(stringResource(R.string.split_keep_original)) }
            }
        )
    }
}

// ----------------------------------------------------------------- Modell-Liste (eigener Screen)

/**
 * Modell-Screen: laedt {url}/models und zeigt die (vision-gefilterte) Liste an.
 * Bei Fehlern erscheint ein Debug-Report (Anfrage, Status, Rohantwort, Fehler)
 * in Monospace und kopierbar.
 */
@Composable
fun ModelListScreen(url: String, apiKey: String, onPick: (String) -> Unit, onBack: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var models by remember { mutableStateOf(ApiClient.ModelsResult(emptyList(), emptyList())) }
    var debugReport by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val result = ApiClient.fetchModels(
            baseUrl = url,
            apiKey = apiKey
        )
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
