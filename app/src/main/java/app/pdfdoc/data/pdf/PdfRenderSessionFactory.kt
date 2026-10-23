package app.pdfdoc.data.pdf

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

class PdfRenderSessionFactory @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun open(uri: Uri): PdfRenderSession {
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open $uri")
        return openFd(fd)
    }

    fun open(file: File): PdfRenderSession =
        openFd(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))

    private fun openFd(fd: ParcelFileDescriptor): PdfRenderSession = try {
        PdfRenderSession(fd)
    } catch (e: Exception) {
        fd.close()
        throw IOException("Unreadable PDF", e)
    }
}
