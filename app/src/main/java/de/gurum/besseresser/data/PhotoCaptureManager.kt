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
 * Manages the lifecycle of temporary photo capture files in the app cache:
 * creates fresh unique files on demand, deletes unconfirmed/cancelled captures,
 * and purges orphaned temporary cache files.
 */
interface PhotoCaptureManager {
    /** Creates a new uniquely-named JPEG file in the app's cache and returns its [Uri]. */
    fun createCaptureFile(): Pair<File, Uri>

    /**
     * Copies an external/shared image [sourceUri] into a fresh temporary capture file in the app's cache
     * and returns its [File] and [Uri], or `null` if the stream cannot be read.
     */
    fun copyToCaptureFile(sourceUri: Uri): Pair<File, Uri>?

    /** Deletes the specified capture file if present. */
    fun deleteCaptureFile(file: File?)

    /** Deletes the capture file corresponding to the given [Uri] if present. */
    fun deleteCaptureFile(uri: Uri?)

    /** Cleans up temporary photo capture files older than [maxAgeMillis] or empty zero-byte files. */
    fun cleanupOrphanedFiles(maxAgeMillis: Long = DEFAULT_MAX_CACHE_AGE_MILLIS)

    companion object {
        const val DEFAULT_MAX_CACHE_AGE_MILLIS = 3600_000L // 1 hour
    }
}

class DefaultPhotoCaptureManager(private val context: Context) : PhotoCaptureManager {

    init {
        cleanupOrphanedFiles()
    }

    private fun getPhotoCacheDir(): File {
        return File(context.cacheDir, PHOTO_CACHE_DIR).also { it.mkdirs() }
    }

    /** Creates a new uniquely-named JPEG file in the app's cache and returns its [Uri]. */
    override fun createCaptureFile(): Pair<File, Uri> {
        val dir = getPhotoCacheDir()
        val name = "$PHOTO_PREFIX${SimpleDateFormat(DATE_PATTERN, Locale.US).format(Date())}_${System.nanoTime()}$PHOTO_SUFFIX"
        val file = File(dir, name)
        val uri = runCatching {
            FileProvider.getUriForFile(context, FILEPROVIDER_AUTHORITY, file)
        }.getOrElse {
            Uri.fromFile(file)
        }
        return file to uri
    }

    override fun copyToCaptureFile(sourceUri: Uri): Pair<File, Uri>? {
        if (sourceUri.authority == FILEPROVIDER_AUTHORITY) {
            val lastSegment = sourceUri.lastPathSegment
                ?: sourceUri.path?.substringAfterLast('/')
            if (!lastSegment.isNullOrBlank()) {
                val existingFile = File(getPhotoCacheDir(), lastSegment)
                if (existingFile.exists() && existingFile.length() > 0L) {
                    return existingFile to sourceUri
                }
            }
        }
        val (targetFile, targetUri) = createCaptureFile()
        val copied = runCatching {
            openStreamRobust(context.contentResolver, sourceUri, context)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
                true
            } ?: false
        }.getOrDefault(false)
        if (!copied || !targetFile.exists() || targetFile.length() == 0L) {
            deleteCaptureFile(targetFile)
            return null
        }
        PhotoStorage.correctOrientationSync(targetFile)
        return targetFile to targetUri
    }

    override fun deleteCaptureFile(file: File?) {
        if (file == null) return
        runCatching {
            if (file.exists() && !file.delete()) {
                file.deleteOnExit()
            }
        }
    }

    override fun deleteCaptureFile(uri: Uri?) {
        if (uri == null) return
        val str = uri.toString()
        val lastSegment = uri.lastPathSegment
            ?: uri.path?.substringAfterLast('/')
            ?: str.substringAfterLast('/')
        if (lastSegment.isNotBlank()) {
            val file = File(getPhotoCacheDir(), lastSegment)
            if (file.exists()) {
                deleteCaptureFile(file)
                return
            }
        }
        val directPath = uri.path
        if (directPath != null) {
            val file = File(directPath)
            if (file.exists()) {
                deleteCaptureFile(file)
                return
            }
        }
        if (str.startsWith("file:")) {
            val path = str.removePrefix("file://").removePrefix("file:")
            val file = File(path)
            if (file.exists()) {
                deleteCaptureFile(file)
            }
        }
    }

    override fun cleanupOrphanedFiles(maxAgeMillis: Long) {
        runCatching {
            val dir = getPhotoCacheDir()
            val cutoff = System.currentTimeMillis() - maxAgeMillis
            dir.listFiles()?.forEach { file ->
                if (file.isFile && (file.lastModified() < cutoff || file.length() == 0L) && !file.delete()) {
                    file.deleteOnExit()
                }
            }
        }
    }
}
