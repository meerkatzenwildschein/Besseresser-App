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

    override fun prepareImageDataUri(photoUri: Uri, maxSide: Int): String {
        val resolver = context.contentResolver
        val options = decodeImageBounds(resolver, photoUri, context)
        val sample = calculateSampleSize(options.outWidth, options.outHeight, maxSide)
        val originalBitmap = decodeSampledBitmap(resolver, photoUri, context, sample)

        return try {
            val scaled = scaleDown(originalBitmap, maxSide)
            try {
                encodeBitmapAsJpegDataUri(scaled)
            } finally {
                if (scaled !== originalBitmap) {
                    scaled.recycle()
                }
            }
        } finally {
            originalBitmap.recycle()
        }
    }

    private fun decodeImageBounds(resolver: ContentResolver?, uri: Uri, context: Context): BitmapFactory.Options {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = openStreamRobust(resolver, uri, context)
            ?: throw IllegalStateException("Foto konnte nicht gelesen werden.")
        boundsStream.use { BitmapFactory.decodeStream(it, null, options) }
        check(options.outWidth > 0 && options.outHeight > 0) { "Foto konnte nicht decodiert werden." }
        return options
    }

    private fun calculateSampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > maxSide * 2) {
            sample *= 2
        }
        return sample
    }

    private fun decodeSampledBitmap(resolver: ContentResolver?, uri: Uri, context: Context, sample: Int): Bitmap {
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
        val decodeStream = openStreamRobust(resolver, uri, context)
            ?: throw IllegalStateException("Foto konnte nicht gelesen werden.")
        return decodeStream.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: throw IllegalStateException("Foto konnte nicht decodiert werden.")
    }

    private fun encodeBitmapAsJpegDataUri(bitmap: Bitmap): String {
        val jpeg = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, COMPRESSION_QUALITY_JPEG, out)
            out.toByteArray()
        }
        return "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
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
    return openContentResolverStream(resolver, uri)
        ?: openFileProviderStream(context, uri)
        ?: openFileSchemeStream(uri)
        ?: openDirectPathStream(uri)
}

private fun openContentResolverStream(resolver: ContentResolver?, uri: Uri): InputStream? {
    if (resolver == null) return null
    return runCatching { resolver.openInputStream(uri) }.getOrNull()
}

private fun openFileProviderStream(context: Context?, uri: Uri): InputStream? {
    if (context == null || uri.authority != "de.gurum.besseresser.fileprovider") return null
    val path = uri.path?.removePrefix("/") ?: return null
    if (path.isBlank()) return null

    val candidateFile = when {
        path.startsWith("photos/") -> File(context.cacheDir, path)
        path.startsWith("history/") -> File(context.filesDir, path)
        else -> File(context.cacheDir, path)
    }
    return if (candidateFile.exists() && candidateFile.length() > 0) {
        runCatching { candidateFile.inputStream() }.getOrNull()
    } else {
        null
    }
}

private fun openFileSchemeStream(uri: Uri): InputStream? {
    if (uri.scheme != "file") return null
    val raw = uri.path ?: uri.schemeSpecificPart ?: return null
    val filePath = raw.removePrefix("file://").removePrefix("file:").removePrefix("//")
    if (filePath.isBlank()) return null
    val file = File(filePath)
    return if (file.exists() && file.length() > 0) {
        runCatching { file.inputStream() }.getOrNull()
    } else {
        null
    }
}

private fun openDirectPathStream(uri: Uri): InputStream? {
    val rawPath = uri.path ?: return null
    if (rawPath.isBlank()) return null
    val file = File(rawPath)
    return if (file.exists() && file.length() > 0) {
        runCatching { file.inputStream() }.getOrNull()
    } else {
        null
    }
}
