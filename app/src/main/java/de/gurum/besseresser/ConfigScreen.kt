package de.gurum.besseresser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Öffnet den System-Dateipicker direkt mit Startverzeichnis „Downloads". */
class OpenBackupDocumentContract : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        val intent = super.createIntent(context, input)
        runCatching {
            val downloadsUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload")
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloadsUri)
        }
        return intent
    }
}

/**
 * Konfigurations-Screen (im Burger-Menü ganz unten mit Werkzeug-Icon `Icons.Filled.Build`):
 * 1. Vollständiger JSON-Export aller App-Daten (inkl. API-Key & Base64-Fotos) nach „Downloads"
 * 2. Vollständiger JSON-Import (überschreibt Duplikate & bestehende Einstellungen/Einträge)
 * 3. Alle Verlaufsdaten löschen (mit Sicherheitsabfrage)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
    appLanguage: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    onBack: () -> Unit,
    onDataImported: (Settings, Profile) -> Unit,
    onHistoryCleared: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    BackHandler(enabled = !busy) { onBack() }

    val importLauncher = rememberLauncherForActivityResult(OpenBackupDocumentContract()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        statusMessage = null
        errorMessage = null
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                BackupStore.importFromUri(ctx, uri)
            }
            res.onSuccess { summary ->
                val updatedSettings = Store.load(ctx)
                val updatedProfile = ProfileStore.load(ctx)
                onDataImported(updatedSettings, updatedProfile)
                busy = false
                statusMessage = ctx.getString(
                    R.string.config_import_success,
                    summary.entriesCount,
                    summary.photosCount
                )
            }.onFailure { e ->
                busy = false
                errorMessage = e.message ?: ctx.getString(R.string.config_import_failed)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TopBarRow(
            title = stringResource(R.string.menu_config),
            onBack = { if (!busy) onBack() },
            trailing = { },
            icon = Icons.Filled.Build
        )

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    stringResource(R.string.config_intro_title),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    stringResource(R.string.config_intro_text),
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
            }
        }

        errorMessage?.let { msg ->
            ErrorBanner(msg)
        }

        statusMessage?.let { msg ->
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        msg,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        if (busy) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.config_busy_hint),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        SectionCard(title = stringResource(R.string.config_section_appearance)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.config_language_label),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val languageOptions = listOf(
                    AppLanguage.DE to ("🇩🇪 " + stringResource(R.string.config_language_de)),
                    AppLanguage.EN to ("🇬🇧 " + stringResource(R.string.config_language_en))
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    languageOptions.forEachIndexed { index, (lang, label) ->
                        SegmentedButton(
                            selected = appLanguage == lang,
                            onClick = { if (appLanguage != lang) onLanguageChange(lang) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = languageOptions.size
                            )
                        ) {
                            Text(
                                text = label,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.config_theme_label),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val themeOptions = listOf(
                    AppThemeMode.SYSTEM to ("📱 " + stringResource(R.string.config_theme_system)),
                    AppThemeMode.LIGHT to ("☀️ " + stringResource(R.string.config_theme_light)),
                    AppThemeMode.DARK to ("🌙 " + stringResource(R.string.config_theme_dark))
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    themeOptions.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = themeMode == mode,
                            onClick = { if (themeMode != mode) onThemeModeChange(mode) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = themeOptions.size
                            )
                        ) {
                            Text(
                                text = label,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        SectionCard(title = stringResource(R.string.config_section_backup)) {
            Text(
                stringResource(R.string.config_backup_desc),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = {
                    busy = true
                    statusMessage = null
                    errorMessage = null
                    scope.launch {
                        val res = withContext(Dispatchers.IO) {
                            BackupStore.exportToDownloads(ctx)
                        }
                        res.onSuccess { summary ->
                            busy = false
                            statusMessage = ctx.getString(
                                R.string.config_export_success,
                                summary.fileName,
                                summary.entriesCount,
                                summary.photosCount
                            )
                        }.onFailure { e ->
                            busy = false
                            errorMessage = e.message ?: ctx.getString(R.string.config_export_failed)
                        }
                    }
                },
                enabled = !busy,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    stringResource(R.string.config_export_button),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            FilledTonalButton(
                onClick = {
                    importLauncher.launch(arrayOf("*/*"))
                },
                enabled = !busy,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    stringResource(R.string.config_import_button),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        SectionCard(title = stringResource(R.string.config_section_history_clear)) {
            Text(
                stringResource(R.string.config_clear_history_desc),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedButton(
                onClick = { showClearConfirm = true },
                enabled = !busy,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.config_clear_history_button),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(Modifier.height(40.dp))
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = {
                Text(
                    stringResource(R.string.config_clear_confirm_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(stringResource(R.string.config_clear_confirm_text))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        busy = true
                        statusMessage = null
                        errorMessage = null
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    HistoryStore.clearAll(ctx)
                                }
                            }.onSuccess {
                                onHistoryCleared()
                                busy = false
                                statusMessage = ctx.getString(R.string.config_clear_success)
                            }.onFailure { e ->
                                busy = false
                                errorMessage = e.message ?: ctx.getString(R.string.config_clear_failed)
                            }
                        }
                    }
                ) {
                    Text(
                        stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
