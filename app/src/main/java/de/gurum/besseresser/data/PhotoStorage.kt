package de.gurum.besseresser.data
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.withContext
import java.io.File
import de.gurum.besseresser.model.HistoryEntry

/**
 * Datei-basierte Foto-Ablage für Verlaufseinträge (ein JPEG pro Eintrag in filesDir/history).
 * Zuständig für Speichern (downgesampelt), Lesen (Thumbnails), Kopieren und Löschen.
 */
class PhotoStorage(
    private val context: Context,
    private val dispatchers: AppDispatchers = AppDispatchers()
) {

    /** Verzeichnis aller Verlaufs-Fotos (und historischer JSON-Dateien, siehe Migration). */
    fun dir(): File = File(context.filesDir, HISTORY_DIR).apply { mkdirs() }

    /** Vollständige Datei zu einem gespeicherten Dateinamen (oder null, wenn nicht vorhanden). */
    fun photoFileOf(photoFileName: String): File? =
        photoFileName.takeIf { it.isNotBlank() }
            ?.let { File(dir(), it) }
            ?.takeIf { it.exists() }

    /** Vollständige Datei zum Foto eines Eintrags (oder null). */
    fun photoFileOf(entry: HistoryEntry): File? = photoFileOf(entry.photoFile)

    /** Foto von [uri] downgesampelt (<1024px) als JPEG speichern; Qualität sinkt schrittweise,
     *  bis die Datei < 100 KB ist. Liefert den Dateinamen bei Erfolg, sonst null. */
    suspend fun saveScaledJpeg(uri: Uri, outFileName: String): String? = withContext(dispatchers.io) {
        runCatching {
            val out = File(dir(), outFileName)
            writeScaledJpeg(uri, out)
            if (out.exists() && out.length() > 0) out.name else null
        }.getOrNull()
    }

    /** Kopiert ein gespeichertes Foto unter einem neuen Dateinamen (z. B. beim Duplizieren). */
    suspend fun copyPhoto(source: File, targetFileName: String): String? = withContext(dispatchers.io) {
        runCatching {
            val targetFile = File(dir(), targetFileName)
            source.copyTo(targetFile, overwrite = true)
            if (targetFile.exists() && targetFile.length() > 0) targetFile.name else null
        }.getOrNull()
    }

    /** Löscht eine einzelne Foto-Datei (ignoriert leere Namen und Fehler). */
    suspend fun deletePhoto(photoFileName: String) = withContext(dispatchers.io) {
        if (photoFileName.isNotBlank()) {
            runCatching { File(dir(), photoFileName).delete() }
        }
    }

    /** Löscht alle Dateien im Verlaufs-Verzeichnis (Fotos wie ggf. verbliebene JSON-Altdateien). */
    suspend fun deleteAll() = withContext(dispatchers.io) {
        dir().listFiles()?.forEach { runCatching { it.delete() } }
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

    /** Foto downgesampelt (<1024px) speichern, JPEG-Qualitaet Schritt fuer Schritt bis < 100 KB. */
    private fun writeScaledJpeg(uri: Uri, out: File) {
        val res = context.contentResolver
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

    companion object {
        private const val HISTORY_DIR = "history"
    }
}
