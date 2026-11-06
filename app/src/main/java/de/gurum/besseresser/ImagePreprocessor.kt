package de.gurum.besseresser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Aufbereitung und Transformation von Bilddaten für die KI-Analyse.
 */
object ImagePreprocessor {

    /**
     * Lädt das Bild von der übergebenen URI, skaliert es herunter und kodiert es als Base64 JPEG Data-URI.
     */
    fun prepareImageDataUri(ctx: Context, photoUri: Uri, maxSide: Int = 1280): String {
        val resolver = ctx.contentResolver
        val bitmap = resolver.openInputStream(photoUri)?.use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalStateException("Foto konnte nicht gelesen werden.")
        val scaled = scaleDown(bitmap, maxSide)
        val jpeg = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
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
        return Bitmap.createScaledBitmap(bmp, (bmp.width * factor).toInt(), (bmp.height * factor).toInt(), true)
    }
}
