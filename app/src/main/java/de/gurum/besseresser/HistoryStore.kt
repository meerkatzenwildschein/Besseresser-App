package de.gurum.besseresser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Eine gespeicherte Foto-Bewertung. */
@Serializable
data class HistoryEntry(
    val id: Long = 0L,
    @SerialName("datum")
    val dateMillis: Long = 0L,
    @SerialName("foto")
    val photoFile: String = "",          // Dateiname in filesDir/history ("" = kein Foto)
    @SerialName("ziele")
    val goalResults: List<GoalResult> = emptyList(),
    @SerialName("zusatz")
    val summary: String = "",
    @SerialName("anmerkung")
    val note: String = "",          // Nutzer-Anmerkung zum Foto (WhatsApp-Stil)
    @SerialName("erkannt")
    val erkannt: String = "",       // Was die KI auf dem Foto erkannt zu haben meint
    @SerialName("nacherfasst")
    val backdated: Boolean = false  // nachträglich erfasst (keine echte Uhrzeit bekannt)
) {
    /** Ampel-Score 0..100 (gruen=100, gelb=50, rot=0, grau ausgeschlossen); -1 = nicht bewertbar. */
    val score: Int
        get() {
            val known = goalResults.filter { it.ampel != TrafficLight.UNKNOWN }
            if (known.isEmpty()) return -1
            val avg = known.sumOf {
                when (it.ampel) {
                    TrafficLight.GREEN -> 100.0
                    TrafficLight.YELLOW -> 50.0
                    else -> 0.0
                }
            } / known.size
            return avg.toInt()
        }
}

/** Persistenz der Historie: pro Eintrag <id>.json + <id>.jpg in filesDir/history. */
object HistoryStore {
    private fun dir(ctx: Context) =
        File(ctx.filesDir, "history").apply { mkdirs() }

    /** Vollständiger Pfad zum gespeicherten Foto (oder null). */
    fun photoFileOf(ctx: Context, entry: HistoryEntry): File? =
        entry.photoFile.takeIf { it.isNotBlank() }
            ?.let { File(dir(ctx), it) }
            ?.takeIf { it.exists() }

    /** Speichert Bewertung + verkleinertes Foto (typisch < 100 KB). Foto-Verarbeitung darf scheitern (dann ohne Thumbnail).
     *  photoUri = null → Eintrag OHNE Foto (Nacherfassung „ohne Foto“).
     *  dateOverride + backdated: nachträgliche Erfassung für ein bestimmtes Tagesdatum. */
    fun add(
        ctx: Context,
        photoUri: Uri?,
        ev: Evaluation,
        note: String = "",
        dateOverride: Long? = null,
        backdated: Boolean = false
    ): HistoryEntry {
        val id = System.currentTimeMillis()
        val date = dateOverride ?: id
        var photoFile = ""
        if (photoUri != null) {
            try {
                val f = File(dir(ctx), "$id.jpg")
                saveScaledJpeg(ctx, photoUri, f)
                photoFile = if (f.exists() && f.length() > 0) f.name else ""
            } catch (e: Exception) {
                photoFile = ""
            }
        }
        val entry = HistoryEntry(
            id = id, dateMillis = date, photoFile = photoFile,
            goalResults = ev.goalResults, summary = ev.summary,
            note = note, erkannt = ev.erkannt, backdated = backdated
        )
        File(dir(ctx), "$id.json").writeText(AppJson.encodeToString(entry))
        return entry
    }

    fun entries(ctx: Context): List<HistoryEntry> {
        val files = dir(ctx).listFiles { f ->
            f.extension == "json" && !f.name.startsWith("day_") && !f.name.startsWith("week_")
        } ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                val raw = f.readText()
                val entry = AppJson.decodeFromString<HistoryEntry>(raw)
                if (entry.id == 0L) {
                    entry.copy(id = f.nameWithoutExtension.toLongOrNull() ?: 0L)
                } else entry
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.dateMillis }
    }

    /** Eintrag inkl. JSON und Foto loeschen. Löscht Tages-/Wochenfazit des Tages gleich mit. */
    fun delete(ctx: Context, entry: HistoryEntry) {
        val d = dir(ctx)
        File(d, "${entry.id}.json").delete()
        File(d, "${entry.id}.jpg").delete()
        DayFazitStore.deleteForEntry(ctx, entry)
        WeekFazitStore.deleteForEntry(ctx, entry)
    }

    /** erkannt-Text eines gespeicherten Eintrags userkorrigieren (JSON neu schreiben).
     *  Das Tagesfazit bleibt auf Platte, wird aber über den Fingerprint als veraltet erkannt. */
    fun updateErkannt(ctx: Context, id: Long, newText: String) {
        val f = File(dir(ctx), "$id.json")
        if (!f.exists()) return
        try {
            val entry = AppJson.decodeFromString<HistoryEntry>(f.readText())
            val updated = entry.copy(erkannt = newText.trim())
            f.writeText(AppJson.encodeToString(updated))
        } catch (e: Exception) {
            // Snapshot unverändert lassen, wenn das Umschreiben scheitert
        }
    }

    /** Ziele + Gesamteindruck eines Eintrags neu schreiben (KI-Re-Bewertung nach Korrektur). */
    fun updateEvaluation(ctx: Context, id: Long, goalResults: List<GoalResult>, summary: String) {
        val f = File(dir(ctx), "$id.json")
        if (!f.exists()) return
        try {
            val entry = AppJson.decodeFromString<HistoryEntry>(f.readText())
            val updated = entry.copy(goalResults = goalResults, summary = summary)
            f.writeText(AppJson.encodeToString(updated))
        } catch (e: Exception) {
            // bei Fehler bleibt der alte Stand bestehen
        }
    }

    /** Foto downgesampelt (<1024px) speichern, JPEG-Qualitaet Schritt fuer Schritt bis < 100 KB. */
    private fun saveScaledJpeg(ctx: Context, uri: Uri, out: File) {
        val res = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        res.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        val bmp = res.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return
        var quality = 75
        while (quality >= 40) {
            out.outputStream().use { fos -> bmp.compress(Bitmap.CompressFormat.JPEG, quality, fos) }
            if (out.length() <= 100_000) break
            quality -= 15
        }
        bmp.recycle()
    }
}

/** Kleines Thumbnail decodieren (inSampleSize, damit die Liste flink bleibt). */
fun decodeThumb(file: File, target: Int = 200): Bitmap? {
    return try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > target) sample *= 2
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    } catch (e: Exception) {
        null
    }
}

// ----------------------------------------------------------------- Tagesfazit

/** Lokaler Tagesschlüssel (yyyyyyyyMMdd, Systemzeitzone) – Basis für Tagesgruppen und Fazit-Dateien. */
private val DAY_KEY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")

fun dayKeyOf(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DAY_KEY_FORMAT)

fun dayKeyOf(date: LocalDate): String = date.format(DAY_KEY_FORMAT)

/** Wochen-Key = ISO-Montag der Woche, in der das Datum liegt (Format yyyyMMdd). */
fun weekKeyOf(millis: Long): String {
    val day = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    val monday = day.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
    return monday.format(DAY_KEY_FORMAT)
}

/** KI-generiertes Fazit eines Tages (Text-Ampel statt rechnerischem Tagesscore). */
@Serializable
data class DayFazit(
    @SerialName("tag")
    val dayKey: String = "",
    @SerialName("ampel")
    val ampel: TrafficLight = TrafficLight.UNKNOWN,              // GREEN | YELLOW | RED
    @SerialName("text")
    val text: String = "",
    @SerialName("eintrage")
    val entryIds: List<Long> = emptyList(),       // Snapshot der zugrunde liegenden Einträge (= Veraltetscheck)
    @SerialName("erstellt")
    val createdAt: Long = 0L,
    @SerialName("fp")
    val fingerprint: String = ""    // Hash-Mix aus ID+erkannt je Eintrag (Text-Korrekturen → veraltet)
)

/** Persistenz day_<tag>.json im selben Verzeichnis; entries() filtert diese Dateien via day_-Präfix. */
object DayFazitStore {

    private fun file(ctx: Context, dayKey: String) =
        File(ctx.filesDir, "history/day_$dayKey.json")

    fun load(ctx: Context, dayKey: String): DayFazit? {
        val f = file(ctx, dayKey)
        if (!f.exists()) return null
        return try {
            AppJson.decodeFromString<DayFazit>(f.readText())
        } catch (e: Exception) {
            null
        }
    }

    fun loadAll(ctx: Context): List<DayFazit> {
        val files = File(ctx.filesDir, "history")
            .listFiles { f -> f.name.startsWith("day_") && f.extension == "json" } ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                AppJson.decodeFromString<DayFazit>(f.readText())
            } catch (e: Exception) {
                null
            }
        }
    }

    fun save(ctx: Context, fazit: DayFazit) {
        file(ctx, fazit.dayKey).writeText(AppJson.encodeToString(fazit))
    }

    /** Fazit-Datei eines Tags löschen (z. B. weil ein Eintrag des Tages gelöscht wurde). */
    fun deleteForEntry(ctx: Context, entry: HistoryEntry) {
        file(ctx, dayKeyOf(entry.dateMillis)).delete()
    }
}

/** Fazit-Speicher für die WOCHE (file-Prefix week_, Typ & Struktur wie das Tagesfazit).
 *  Invaldierung: Bei jeder Änderung der Wochen-Einträge (Fingerprint) wird die Datei GELÖSCHT –
 *  kein veraltet-Dimmen wie beim Tagesfazit (Req: „gelöscht … erneut anfordern"). */
object WeekFazitStore {
    private fun file(ctx: Context, weekKey: String) =
        File(ctx.filesDir, "history/week_$weekKey.json")

    fun load(ctx: Context, weekKey: String): DayFazit? {
        val f = file(ctx, weekKey)
        if (!f.exists()) return null
        return try {
            AppJson.decodeFromString<DayFazit>(f.readText())
        } catch (e: Exception) {
            null
        }
    }

    fun save(ctx: Context, fazit: DayFazit) {
        file(ctx, fazit.dayKey).writeText(AppJson.encodeToString(fazit))
    }

    /** Wochen-Fazit löschen (nachträglicher Eintrag in derselben Woche). */
    fun deleteForEntry(ctx: Context, entry: HistoryEntry) {
        file(ctx, weekKeyOf(entry.dateMillis)).delete()
    }

    fun delete(ctx: Context, weekKey: String) {
        file(ctx, weekKey).delete()
    }
}

/** In-Memory-Sitzungsguard: Auto-Bewertung „kein Fazit" max. 1× pro Tag und App-Session. */
object DayFazitSession {
    val attempted = mutableSetOf<String>()
    val attemptedWeekly = mutableSetOf<String>()
}

/** „Quellen-Fingerprint" eines Tages: ID+erkannt je Eintrag. Bearbeitete erkannt-Texte
 *  oder gelöschte/ergänzte Einträge machen jedes Tagesfazit auf dieser Basis KEINEN gültigen Basis mehr. */
fun dayFingerprint(entries: List<HistoryEntry>): String =
    entries.sortedBy { it.id }.joinToString("|") { "${it.id}:${it.erkannt}" }.hashCode().toString(16)
