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
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(photoUri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalStateException("Foto konnte nicht gelesen werden.")

        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / sample > maxSide * 2) {
            sample *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
        val originalBitmap = resolver.openInputStream(photoUri)?.use {
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
