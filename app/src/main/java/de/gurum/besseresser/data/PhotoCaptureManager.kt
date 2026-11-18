package de.gurum.besseresser.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val FILEPROVIDER_AUTHORITY = "de.gurum.besseresser.fileprovider"
private const val PHOTO_CACHE_DIR = "photos"
private const val PHOTO_PREFIX = "essen_"
private const val PHOTO_SUFFIX = ".jpg"
private const val DATE_PATTERN = "yyyyMMdd_HHmmss"

/**
 * Creates fresh temporary capture files on demand, so each camera session gets
 * a unique file name. The file is only created when [createCaptureFile] is called,
 * not eagerly at composition time.
 */
class PhotoCaptureManager(private val context: Context) {

    /** Creates a new uniquely-named JPEG file in the app's cache and returns its [Uri]. */
    fun createCaptureFile(): Pair<File, Uri> {
        val dir = File(context.cacheDir, PHOTO_CACHE_DIR).also { it.mkdirs() }
        val name = "$PHOTO_PREFIX${SimpleDateFormat(DATE_PATTERN, Locale.US).format(Date())}$PHOTO_SUFFIX"
        val file = File(dir, name)
        val uri = FileProvider.getUriForFile(context, FILEPROVIDER_AUTHORITY, file)
        return file to uri
    }
}
