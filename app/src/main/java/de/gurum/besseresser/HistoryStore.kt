package de.gurum.besseresser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Eine gespeicherte Foto-Bewertung. */
data class HistoryEntry(
    val id: Long,
    val dateMillis: Long,
    val photoFile: String,          // Dateiname in filesDir/history ("" = kein Foto)
    val goalResults: List<GoalResult>,
    val summary: String,
    val note: String = "",          // Nutzer-Anmerkung zum Foto (WhatsApp-Stil)
    val erkannt: String = ""        // Was die KI auf dem Foto erkannt zu haben meint
) {
    /** Ampel-Score 0..100 (gruen=100, gelb=50, rot=0, grau ausgeschlossen); -1 = nicht bewertbar. */
    val score: Int
        get() {
            val known = goalResults.filter { it.ampel != "unbekannt" }
            if (known.isEmpty()) return -1
            val avg = known.sumOf {
                when (it.ampel) {
                    "gruen" -> 100.0
                    "gelb" -> 50.0
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

    /** Speichert Bewertung + verkleinertes Foto (typisch < 100 KB). Foto-Verarbeitung darf scheitern (dann ohne Thumbnail). */
    fun add(ctx: Context, photoUri: Uri, ev: Evaluation, note: String = ""): HistoryEntry {
        val id = System.currentTimeMillis()
        var photoFile = ""
        try {
            val f = File(dir(ctx), "$id.jpg")
            saveScaledJpeg(ctx, photoUri, f)
            photoFile = if (f.exists() && f.length() > 0) f.name else ""
        } catch (e: Exception) {
            photoFile = ""
        }
        val entry = HistoryEntry(id, id, photoFile, ev.goalResults, ev.summary, note, ev.erkannt)
        File(dir(ctx), "$id.json").writeText(toJson(entry).toString())
        return entry
    }

    fun entries(ctx: Context): List<HistoryEntry> {
        val files = dir(ctx).listFiles { f -> f.extension == "json" } ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                val o = JSONObject(f.readText())
                val arr = o.optJSONArray("ziele") ?: JSONArray()
                val goals = (0 until arr.length()).mapNotNull { i ->
                    val g = arr.optJSONObject(i) ?: return@mapNotNull null
                    GoalResult(
                        goal = g.optString("ziel"),
                        ampel = g.optString("ampel", "unbekannt"),
                        begruendung = g.optString("begruendung")
                    )
                }
                HistoryEntry(
                    id = o.optLong("id", f.nameWithoutExtension.toLongOrNull() ?: 0L),
                    dateMillis = o.optLong("datum", 0L),
                    photoFile = o.optString("foto"),
                    goalResults = goals,
                    summary = o.optString("zusatz"),
                    note = o.optString("anmerkung"),
                    erkannt = o.optString("erkannt")
                )
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.dateMillis }
    }

    /** Eintrag inkl. JSON und Foto loeschen. */
    fun delete(ctx: Context, entry: HistoryEntry) {
        val d = dir(ctx)
        File(d, "${entry.id}.json").delete()
        File(d, "${entry.id}.jpg").delete()
    }

    /** Foto downgesampelt (<1024px) speichern, JPEG-Qualitaet Schritt fuer Schritt bis < 100 KB. */
    private fun saveScaledJpeg(ctx: Context, uri: Uri, out: File) {
        val res = ctx.contentResolver
        // Wichtig: decodeStream mit inJustDecodeBounds liefert IMMER null Bitmap –
        // nur die out*-Felder nutzen, nicht den Rueckgabewert pruefen!
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

    private fun toJson(e: HistoryEntry): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("datum", e.dateMillis)
        put("foto", e.photoFile)
        put("zusatz", e.summary)
        put("anmerkung", e.note)
        put("erkannt", e.erkannt)
        put(
            "ziele",
            JSONArray().also { arr ->
                e.goalResults.forEach { g ->
                    arr.put(JSONObject().apply {
                        put("ziel", g.goal)
                        put("ampel", g.ampel)
                        put("begruendung", g.begruendung)
                    })
                }
            }
        )
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
