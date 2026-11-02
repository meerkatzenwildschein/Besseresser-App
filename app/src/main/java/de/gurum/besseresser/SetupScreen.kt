package de.gurum.besseresser

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
fun SetupScreen(initial: Settings, firstRun: Boolean, onCancel: () -> Unit, onDone: (Settings) -> Unit) {
    val ctx = LocalContext.current
    val defaultGoals = remember { ctx.resources.getStringArray(R.array.default_goals).toList() }
    var url by remember { mutableStateOf(initial.baseUrl) }
    var key by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model.ifBlank { "gpt-4o-mini" }) }
    var goals by remember { mutableStateOf(initial.goals.joinToString("\n").ifBlank { defaultGoals.joinToString("\n") }) }
    var goalsDirty by remember { mutableStateOf(false) }   // Feld wurde angefasst → neue KI-Aufteilung
    var showModels by remember { mutableStateOf(false) }
    var showProviders by remember { mutableStateOf(false) }

    // --- KI-Aufteilung beim Speichern
    var splitting by remember { mutableStateOf(false) }
    var showSplit by remember { mutableStateOf(false) }
    var splitEdited by remember { mutableStateOf("") }
    var splitRaw by remember { mutableStateOf("") }
    var splitDebug by remember { mutableStateOf<String?>(null) }

    if (showModels) {
        ModelListScreen(
            url = url,
            apiKey = key,
            onPick = { model = it; showModels = false },
            onBack = { showModels = false }
        )
        return
    }

    val valid = url.trim().startsWith("http") && key.isNotBlank() && model.isNotBlank() && !splitting
    // Leeres Ziele-Feld ist ok – dann greifen DEFAULT_GOALS beim Speichern

    val doSave: (List<String>, Boolean) -> Unit = { liste, wasSplit ->
        onDone(Settings(url.trim(), key.trim(), model.trim(), liste.ifEmpty { defaultGoals }, wasSplit))
    }

    val save: () -> Unit = {
        val text = goals.trim()
        when {
            // Leeres Feld = Standard-Ziele, keine KI nötig
            text.isBlank() -> doSave(defaultGoals, true)
            // Bereits bestätigt aufgeteilt und unverändert → direkt übernehmen
            initial.goalsWereSplit && !goalsDirty -> doSave(
                text.split("\n").map { it.trim() }.filter { it.isNotBlank() }, true
            )
            // Sonst: KI-Aufteilung anfordern
            else -> {
                splitting = true
                splitDebug = null
                splitRaw = text
                ApiClient.splitGoals(
                baseUrl = url,
                apiKey = key,
                model = model,
                rawGoals = text,
                splitIntro = ctx.getString(R.string.prompt_split_intro),
                splitRules = ctx.getString(R.string.prompt_split_rules),
                onOk = { liste ->
                    splitting = false
                    splitEdited = liste.joinToString("\n")
                    showSplit = true
                },
                onError = { debug ->
                    splitting = false
                    splitDebug = debug
                    splitEdited = ApiClient.splitGoalsLocally(text).joinToString("\n").ifBlank { text }
                    showSplit = true
                }
            )
            }
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
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // --- Top-Bar: Zurueck (ohne Speichern) | Titel; Speichern als Button unten
        TopBarRow(
            title = "Einstellungen",
            onBack = onCancel,
            trailing = { },
            icon = Icons.Filled.Settings
        )
        Spacer(Modifier.height(4.dp))
        if (firstRun) {
            Text(stringResource(R.string.setup_welcome), fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.setup_welcome_text),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(stringResource(R.string.setup_edit_title), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.setup_edit_text),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LabelledField(stringResource(R.string.setup_url_label)) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = { Text("https://api.openai.com/v1") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        TextButton(onClick = { showProviders = true }) {
            Text(stringResource(R.string.setup_pick_provider))
        }

        LabelledField(stringResource(R.string.setup_key_label)) {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                placeholder = { Text(stringResource(R.string.setup_key_placeholder)) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        LabelledField(stringResource(R.string.setup_model_label)) {
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        TextButton(
            onClick = { showModels = true },
            enabled = url.trim().startsWith("http")
        ) {
            Text(stringResource(R.string.setup_models_link))
        }

        LabelledField(stringResource(R.string.setup_goals_label), required = false) {
            OutlinedTextField(
                value = goals,
                onValueChange = { goals = it; goalsDirty = true },
                placeholder = { Text(defaultGoals.joinToString("\n")) },
                minLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (splitting) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.setup_ai_splitting_text), fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(28.dp))
        Text(
            stringResource(R.string.build_label, BuildConfig.APP_VERSION, BuildConfig.BUILD_TIME),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Platzhalter, damit die Build-Zeile nie unter dem fixierten Save-Button steckt
        Spacer(Modifier.height(90.dp))
    }

    // Fixierter Speicher-CTA unten (immer sichtbar, wie der Foto-Button)
    Button(
        onClick = save,
        enabled = valid,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(16.dp)
            .fillMaxWidth()
            .height(56.dp)
    ) {
        Text(
            stringResource(if (splitting) R.string.setup_ai_splitting_button else R.string.setup_save_continue),
            fontSize = 17.sp
        )
    }
    }

    // --- Dialog: Anbieter-Auswahl (fuellt das URL-Feld)
    if (showProviders) {
        AlertDialog(
            onDismissRequest = { showProviders = false },
            title = { Text(stringResource(R.string.provider_picker_title)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 450.dp)) {
                    items(PROVIDERS) { p ->
                        TextButton(
                            onClick = { url = p.url; showProviders = false },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(p.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                if (p.noteRes != 0) {
                                    Text(
                                        stringResource(p.noteRes),
                                        fontSize = 11.sp,
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
                TextButton(onClick = { showProviders = false }) { Text(stringResource(R.string.close)) }
            }
        )
    }

    // --- Dialog: KI-aufgeteilte Ziele pruefen & uebernehmen
    if (showSplit) {
        AlertDialog(
            onDismissRequest = { showSplit = false },
            title = {
                Text(
                    stringResource(
                        if (splitDebug == null) R.string.split_title_ok else R.string.split_title_fail
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (splitDebug != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            SelectionContainer {
                                Text(
                                    splitDebug!!,
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
                        value = splitEdited,
                        onValueChange = { splitEdited = it },
                        minLines = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val liste = splitEdited.split("\n").map { it.trim() }.filter { it.isNotBlank() }
                    showSplit = false
                    doSave(liste, true)
                }) { Text(stringResource(R.string.split_save)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSplit = false
                    // Ausweg: Original-Freitext unverändert als ein Ziel
                    doSave(listOf(splitRaw), true)
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
        ApiClient.fetchModels(
            baseUrl = url,
            apiKey = apiKey,
            onOk = {
                models = it
                loading = false
            },
            onError = {
                debugReport = it
                loading = false
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TopBarRow(
            title = stringResource(R.string.model_picker_title),
            onBack = onBack,
            trailing = { },
            icon = Icons.Filled.Settings
        )

        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.models_loading, url.trimEnd('/')), fontSize = 14.sp)
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
                        debugReport!!,
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
                    LazyColumn {
                        items(liste) { id ->
                            TextButton(
                                onClick = { onPick(id) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(id, fontSize = 14.sp)
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
