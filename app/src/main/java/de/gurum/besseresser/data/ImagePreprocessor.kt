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
        openStreamRobust(resolver, photoUri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalStateException("Foto konnte nicht gelesen werden.")

        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / sample > maxSide * 2) {
            sample *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
        val originalBitmap = openStreamRobust(resolver, photoUri)?.use {
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
 * 1. ContentResolver.openInputStream (Standard für content://-URIs)
 * 2. Direkter Dateizugriff als Fallback (für file://-URIs und wenn FileProvider null liefert)
 *
 * Hintergrund: FileProvider-content://-URIs werden vom ContentResolver im eigenen Prozess
 * normalerweise korrekt aufgelöst. Auf einigen Geräten/Android-Versionen kann openInputStream
 * dennoch null zurückgeben, obwohl die Datei existiert. Der Datei-Fallback greift dann direkt
 * auf den tatsächlichen Pfad zu.
 */
internal fun openStreamRobust(resolver: ContentResolver, uri: Uri): InputStream? {
    // Versuch 1: Standard ContentResolver (funktioniert für content:// und file://)
    runCatching { resolver.openInputStream(uri) }
        .getOrNull()
        ?.let { return it }

    // Versuch 2: Datei direkt lesen, wenn der Pfad aus der URI extrahierbar ist
    val path = uri.path
    if (!path.isNullOrBlank()) {
        val file = File(path)
        if (file.exists() && file.length() > 0) {
            runCatching { file.inputStream() }.getOrNull()?.let { return it }
        }
    }

    // Versuch 3: file://-Schema explizit auflösen
    if (uri.scheme == "file") {
        val filePath = uri.schemeSpecificPart?.removePrefix("//")
        if (!filePath.isNullOrBlank()) {
            val file = File(filePath)
            if (file.exists() && file.length() > 0) {
                runCatching { file.inputStream() }.getOrNull()?.let { return it }
            }
        }
    }

    return null
}
