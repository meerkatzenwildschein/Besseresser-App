package de.gurum.besseresser

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

/** Verlaufseintrag inklusive Base64-codiertem JPEG-Foto für den Voll-Export/Import. */
@Serializable
data class BackupHistoryEntry(
    @SerialName("eintrag")
    val entry: HistoryEntry,
    @SerialName("fotoBase64")
    val photoBase64: String? = null
)

/** Vollständiges App-Backup: Einstellungen (inkl. API-Key), Profil, Favoriten-Notizen, Verlauf & Fazits. */
@Serializable
data class AppBackup(
    @SerialName("version")
    val version: Int = 1,
    @SerialName("exportiertAm")
    val exportedAtMillis: Long = 0L,
    @SerialName("einstellungen")
    val settings: Settings = Settings(),
    @SerialName("profil")
    val profile: Profile = Profile(),
    @SerialName("notizen")
    val recentNotes: List<RecentNote> = emptyList(),
    @SerialName("verlauf")
    val historyEntries: List<BackupHistoryEntry> = emptyList(),
    @SerialName("tagesfazits")
    val dayFazits: List<DayFazit> = emptyList(),
    @SerialName("wochenfazits")
    val weekFazits: List<DayFazit> = emptyList()
)

/** Kompakte Zusammenfassung eines durchgeführten Exports oder Imports für die UI. */
data class BackupSummary(
    val fileName: String = "",
    val entriesCount: Int = 0,
    val photosCount: Int = 0,
    val dayFazitsCount: Int = 0,
    val weekFazitsCount: Int = 0,
    val recentNotesCount: Int = 0,
    val hasApiKey: Boolean = false,
    val hasProfile: Boolean = false
)

object BackupStore {

    private val BackupJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
        prettyPrint = true
    }

    internal fun encodePhotoBase64(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes)

    internal fun decodePhotoBase64(base64: String): ByteArray =
        Base64.getDecoder().decode(base64.trim())

    internal fun serializeBackup(backup: AppBackup): String =
        BackupJson.encodeToString(backup)

    internal fun deserializeBackup(rawJson: String): AppBackup =
        BackupJson.decodeFromString<AppBackup>(rawJson)

    /** Importierte Einstellungen überschreiben vorhandene Werte (inkl. API-Key und Ziele). */
    internal fun mergeSettings(existing: Settings, imported: Settings): Settings = Settings(
        baseUrl = imported.baseUrl.trim().ifBlank { existing.baseUrl },
        apiKey = imported.apiKey.trim().ifBlank { existing.apiKey },
        model = imported.model.trim().ifBlank { existing.model },
        goals = if (imported.goals.isNotEmpty()) imported.goals else existing.goals,
        goalsWereSplit = if (imported.goals.isNotEmpty()) imported.goalsWereSplit else existing.goalsWereSplit
    )

    /** Importierte Profildaten überschreiben vorhandene Felder. */
    internal fun mergeProfile(existing: Profile, imported: Profile): Profile {
        if (imported.isEmpty) return existing
        return Profile(
            birthDate = imported.birthDate?.takeIf { it.isNotBlank() } ?: existing.birthDate,
            weightKg = imported.weightKg ?: existing.weightKg,
            heightCm = imported.heightCm ?: existing.heightCm,
            notes = imported.notes.trim().ifBlank { existing.notes },
            gender = if (imported.gender != Gender.NONE) imported.gender else existing.gender
        )
    }

    /** Importierte Notizen überschreiben gleichlautende vorhandene Notizen; Reihenfolge aus dem Backup hat Vorrang. */
    internal fun mergeRecentNotes(existing: List<RecentNote>, imported: List<RecentNote>): List<RecentNote> {
        val cleanedImported = imported
            .map { it.copy(text = it.text.trim()) }
            .filter { it.text.isNotBlank() }
            .distinctBy { it.text }
        val importedTexts = cleanedImported.map { it.text }.toSet()
        val remainingExisting = existing
            .map { it.copy(text = it.text.trim()) }
            .filter { it.text.isNotBlank() && it.text !in importedTexts }
        return cleanedImported + remainingExisting
    }

    /** Erstellt ein vollständiges In-Memory-Backup aller aktuell gespeicherten App-Daten inkl. Fotos als Base64. */
    fun createBackup(ctx: Context, nowMillis: Long = System.currentTimeMillis()): AppBackup {
        val settings = Store.load(ctx)
        val profile = ProfileStore.load(ctx)
        val recentNotes = RecentNotesStore.load(ctx, sortStarredFirst = false)
        val entries = HistoryStore.entries(ctx)
        val dayFazits = DayFazitStore.loadAll(ctx)
        val weekFazits = WeekFazitStore.loadAll(ctx)

        val backupEntries = entries.map { entry ->
            val photoFile = HistoryStore.photoFileOf(ctx, entry)
            val base64 = photoFile?.let { file ->
                runCatching {
                    val bytes = file.readBytes()
                    if (bytes.isNotEmpty()) encodePhotoBase64(bytes) else null
                }.getOrNull()
            }
            BackupHistoryEntry(entry = entry, photoBase64 = base64)
        }

        return AppBackup(
            version = 1,
            exportedAtMillis = nowMillis,
            settings = settings,
            profile = profile,
            recentNotes = recentNotes,
            historyEntries = backupEntries,
            dayFazits = dayFazits,
            weekFazits = weekFazits
        )
    }

    /** Exportiert alle App-Daten als JSON-Datei direkt in den öffentlichen Ordner „Downloads". */
    fun exportToDownloads(ctx: Context): Result<BackupSummary> = runCatching {
        val now = System.currentTimeMillis()
        val backup = createBackup(ctx, now)
        val json = serializeBackup(backup)
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(now))
        val fileName = "besseresser-backup-$timestamp.json"

        writeJsonToDownloads(ctx, fileName, json)

        BackupSummary(
            fileName = fileName,
            entriesCount = backup.historyEntries.size,
            photosCount = backup.historyEntries.count { !it.photoBase64.isNullOrBlank() },
            dayFazitsCount = backup.dayFazits.size,
            weekFazitsCount = backup.weekFazits.size,
            recentNotesCount = backup.recentNotes.size,
            hasApiKey = backup.settings.apiKey.isNotBlank(),
            hasProfile = !backup.profile.isEmpty
        )
    }

    private fun writeJsonToDownloads(ctx: Context, fileName: String, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Backup-Datei konnte nicht unter Downloads angelegt werden.")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                    out.flush()
                } ?: throw IllegalStateException("OutputStream für Downloads konnte nicht geöffnet werden.")
                val doneValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, doneValues, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                .apply { mkdirs() }
            val targetFile = File(downloadsDir, fileName)
            targetFile.writeBytes(bytes)
        }
    }

    /** Liest eine Backup-JSON-Datei über ihre Content-URI ein und stellt alle Daten wieder her. */
    fun importFromUri(ctx: Context, uri: Uri): Result<BackupSummary> = runCatching {
        val rawJson = ctx.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw IllegalStateException("Datei konnte nicht gelesen werden.")
        importFromJson(ctx, rawJson).getOrThrow()
    }

    /** Importiert ein JSON-Backup; vorhandene Daten und Duplikate (Einstellungen, API-Key, Profil,
     *  Notizen, Verlaufseinträge inkl. Fotos sowie Tages-/Wochenfazits) werden überschrieben. */
    fun importFromJson(ctx: Context, rawJson: String): Result<BackupSummary> = runCatching {
        val backup = deserializeBackup(rawJson)

        // 1. Einstellungen (inkl. API-Key) zusammenführen & speichern
        val mergedSettings = mergeSettings(Store.load(ctx), backup.settings)
        Store.save(ctx, mergedSettings)

        // 2. Profil zusammenführen & speichern
        val mergedProfile = mergeProfile(ProfileStore.load(ctx), backup.profile)
        ProfileStore.save(
            ctx = ctx,
            birthDateIso = mergedProfile.birthDate,
            weightKg = mergedProfile.weightKg,
            heightCm = mergedProfile.heightCm,
            notes = mergedProfile.notes,
            gender = mergedProfile.gender
        )

        // 3. Notiz-Schnellauswahl (inkl. Favoriten-Sternchen) zusammenführen & speichern
        val existingNotes = RecentNotesStore.load(ctx, sortStarredFirst = false)
        val mergedNotes = mergeRecentNotes(existingNotes, backup.recentNotes)
        RecentNotesStore.save(ctx, mergedNotes)

        // 4. Verlaufseinträge & Base64-Fotos wiederherstellen (Duplikate überschreiben)
        val histDir = HistoryStore.dir(ctx)
        val existingEntries = HistoryStore.entries(ctx)
        var restoredPhotos = 0

        val entriesToSave = backup.historyEntries.map { item ->
            val rawEntry = item.entry
            // Falls ein Eintrag mit identischem Zeitstempel, Erkennung und Anmerkung schon unter
            // anderer ID existiert, dessen altes Duplikat entfernen, damit nichts doppelt erscheint.
            existingEntries.filter { existing ->
                existing.id != rawEntry.id &&
                        existing.dateMillis == rawEntry.dateMillis &&
                        existing.erkannt == rawEntry.erkannt &&
                        existing.note == rawEntry.note
            }.forEach { duplicate ->
                HistoryStore.delete(ctx, duplicate)
            }

            var photoFileName = rawEntry.photoFile
            val b64 = item.photoBase64
            if (!b64.isNullOrBlank()) {
                val decodedBytes = runCatching { decodePhotoBase64(b64) }.getOrNull()
                if (decodedBytes != null && decodedBytes.isNotEmpty()) {
                    val safeName = File(photoFileName.ifBlank { "${rawEntry.id}.jpg" }).name
                    val targetFile = File(histDir, safeName)
                    targetFile.writeBytes(decodedBytes)
                    photoFileName = safeName
                    restoredPhotos++
                }
            }
            rawEntry.copy(photoFile = photoFileName)
        }
        HistoryStore.saveAll(ctx, entriesToSave)

        // 5. Tages- und Wochenfazits speichern (überschreibt vorhandene Keys via REPLACE)
        DayFazitStore.saveAll(ctx, backup.dayFazits)
        WeekFazitStore.saveAll(ctx, backup.weekFazits)

        BackupSummary(
            entriesCount = entriesToSave.size,
            photosCount = restoredPhotos,
            dayFazitsCount = backup.dayFazits.size,
            weekFazitsCount = backup.weekFazits.size,
            recentNotesCount = mergedNotes.size,
            hasApiKey = mergedSettings.apiKey.isNotBlank(),
            hasProfile = !mergedProfile.isEmpty
        )
    }
}
