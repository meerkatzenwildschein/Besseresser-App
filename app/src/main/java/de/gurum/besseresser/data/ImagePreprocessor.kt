package de.gurum.besseresser.data
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

const val COMPRESSION_QUALITY_JPEG = 82
const val DEFAULT_MAX_IMAGE_SIDE = 1280

/** Schnittstelle für die Bild-Aufbereitung (ermöglicht einfache Test-Fakes). */
interface ImagePreprocessor {
    fun prepareImageDataUri(photoUri: Uri, maxSide: Int = DEFAULT_MAX_IMAGE_SIDE): String
}

/**
 * Aufbereitung und Transformation von Bilddaten für die KI-Analyse.
 * Als injizierbare Klasse, damit ViewModels ohne Context-Parameter auskommen.
 */
class AndroidImagePreprocessor(private val context: Context) : ImagePreprocessor {

    /**
     * Lädt das Bild von der übergebenen URI, skaliert es herunter und kodiert es als Base64 JPEG Data-URI.
     *
     * Öffnet den Stream robust: zuerst via ContentResolver (für content://-URIs), dann direkt
     * als Datei (für file://-URIs und als Fallback, wenn FileProvider keinen Stream liefert).
     */
    override fun prepareImageDataUri(photoUri: Uri, maxSide: Int): String {
        val resolver = context.contentResolver
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }

        // 1. Abmessungen ermitteln (decodeStream liefert bei inJustDecodeBounds=true immer null)
        val boundsStream = openStreamRobust(resolver, photoUri, context)
            ?: throw IllegalStateException("Foto konnte nicht gelesen werden.")
        boundsStream.use { BitmapFactory.decodeStream(it, null, options) }
        check(options.outWidth > 0 && options.outHeight > 0) { "Foto konnte nicht decodiert werden." }

        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / sample > maxSide * 2) {
            sample *= 2
        }

        // 2. Bild mit passendem inSampleSize decodieren
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
        val decodeStream = openStreamRobust(resolver, photoUri, context)
            ?: throw IllegalStateException("Foto konnte nicht gelesen werden.")
        val originalBitmap = decodeStream.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: throw IllegalStateException("Foto konnte nicht decodiert werden.")

        try {
            val scaled = scaleDown(originalBitmap, maxSide)
            try {
                val jpeg = ByteArrayOutputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, COMPRESSION_QUALITY_JPEG, out)
                    out.toByteArray()
                }
                return "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
            } finally {
                if (scaled !== originalBitmap) {
                    scaled.recycle()
                }
            }
        } finally {
            originalBitmap.recycle()
        }
    }

    /**
     * Skaliert eine Bitmap proportional herunter, wenn die längere Seite [maxSide] überschreitet.
     */
    fun scaleDown(bmp: Bitmap, maxSide: Int): Bitmap {
        val maxOrig = maxOf(bmp.width, bmp.height)
        if (maxOrig <= maxSide) return bmp
        val factor = maxSide.toFloat() / maxOrig
        return bmp.scale((bmp.width * factor).toInt(), (bmp.height * factor).toInt())
    }
}

/**
 * Öffnet einen InputStream für die gegebene URI robust:
 * 1. ContentResolver.openInputStream (Standard für content://- und file://-URIs)
 * 2. Direkter Zugriff auf App-interne Verzeichnisse bei FileProvider-URIs (falls ContentResolver fehlschlägt)
 * 3. Direkter Dateizugriff bei file://-URIs und absoluten Pfaden
 */
internal fun openStreamRobust(resolver: ContentResolver?, uri: Uri, context: Context? = null): InputStream? {
    // Versuch 1: Standard ContentResolver (funktioniert für content:// und file://)
    if (resolver != null) {
        val stream = runCatching { resolver.openInputStream(uri) }.getOrNull()
        if (stream != null) return stream
    }

    // Versuch 2: Bei eigenen FileProvider-URIs direkt im Cache-/Files-Ordner nachsehen
    if (context != null && uri.authority == "de.gurum.besseresser.fileprovider") {
        val path = uri.path?.removePrefix("/")
        if (!path.isNullOrBlank()) {
            val candidateFile = if (path.startsWith("photos/")) {
                File(context.cacheDir, path)
            } else if (path.startsWith("history/")) {
                File(context.filesDir, path)
            } else {
                File(context.cacheDir, path)
            }
            if (candidateFile.exists() && candidateFile.length() > 0) {
                val stream = runCatching { candidateFile.inputStream() }.getOrNull()
                if (stream != null) return stream
            }
        }
    }

    // Versuch 3: file://-Schema explizit auflösen
    if (uri.scheme == "file") {
        val raw = uri.path ?: uri.schemeSpecificPart
        val filePath = raw?.removePrefix("file://")?.removePrefix("file:")?.removePrefix("//")
        if (!filePath.isNullOrBlank()) {
            val file = File(filePath)
            if (file.exists() && file.length() > 0) {
                val stream = runCatching { file.inputStream() }.getOrNull()
                if (stream != null) return stream
            }
        }
    }

    // Versuch 4: Direkter Dateipfad
    val rawPath = uri.path
    if (!rawPath.isNullOrBlank()) {
        val file = File(rawPath)
        if (file.exists() && file.length() > 0) {
            val stream = runCatching { file.inputStream() }.getOrNull()
            if (stream != null) return stream
        }
    }

    return null
}
