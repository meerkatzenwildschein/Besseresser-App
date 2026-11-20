package de.gurum.besseresser.data
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream

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
     */
    override fun prepareImageDataUri(photoUri: Uri, maxSide: Int): String {
        val resolver = context.contentResolver
        val bitmap = resolver.openInputStream(photoUri)?.use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalStateException("Foto konnte nicht gelesen werden.")
        val scaled = scaleDown(bitmap, maxSide)
        val jpeg = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, COMPRESSION_QUALITY_JPEG, it) }.toByteArray()
        val dataUri = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
        if (scaled !== bitmap) {
            scaled.recycle()
        }
        return dataUri
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
