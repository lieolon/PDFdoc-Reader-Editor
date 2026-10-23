package app.pdfdoc.data.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class UriInfo(val name: String, val sizeBytes: Long)

/**
 * The only place that touches user files. Documents are accessed through SAF URIs;
 * edits happen on private working copies in cacheDir and are written back atomically.
 */
@Singleton
class FileStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val resolver get() = context.contentResolver

    val workDir: File get() = File(context.cacheDir, "work").apply { mkdirs() }
    val thumbDir: File get() = File(context.filesDir, "thumbs").apply { mkdirs() }

    fun newWorkFile(): File = File(workDir, "${UUID.randomUUID()}.pdf")

    fun takePersistablePermission(uri: Uri) {
        if (uri.scheme != "content") return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        // Write access is not always granted (e.g. VIEW intents); fall back to read-only.
        runCatching { resolver.takePersistableUriPermission(uri, flags) }
            .recoverCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    fun query(uri: Uri): UriInfo {
        var name: String? = null
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0) name = c.getString(n)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        return UriInfo(name ?: uri.lastPathSegment ?: "document.pdf", size)
    }

    /** Copies the document behind [uri] into a fresh private working file. */
    fun copyToWork(uri: Uri): File {
        val target = newWorkFile()
        val input = resolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
        input.use { src -> target.outputStream().use { src.copyTo(it) } }
        return target
    }

    /** Overwrites the document behind [uri] with [file]. */
    fun writeTo(uri: Uri, file: File) {
        val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot write $uri")
        output.use { dst -> file.inputStream().use { it.copyTo(dst) } }
    }

    fun clearCache() {
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }
}
