package app.pdfdoc.data.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Size
import java.io.Closeable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe wrapper over [PdfRenderer]. The platform renderer allows only one open
 * page at a time, so every call is serialized through a mutex.
 */
class PdfRenderSession(fd: ParcelFileDescriptor) : Closeable {
    private val renderer = PdfRenderer(fd)
    private val mutex = Mutex()
    @Volatile private var closeRequested = false
    private var released = false

    val pageCount: Int = renderer.pageCount

    /** Page sizes in PDF points, rotation already applied by the renderer. */
    val pageSizes: List<Size> = (0 until pageCount).map { i ->
        renderer.openPage(i).use { Size(it.width, it.height) }
    }

    suspend fun render(index: Int, widthPx: Int): Bitmap? = mutex.withLock {
        if (closeRequested) {
            releaseLocked()
            return null
        }
        val size = pageSizes[index]
        val heightPx = (widthPx.toLong() * size.height / size.width).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        renderer.openPage(index).use { it.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
        if (closeRequested) releaseLocked()
        bitmap
    }

    /**
     * Called from the main thread on dispose. If a render is in flight, the renderer is
     * released by that render once it finishes instead of blocking here.
     */
    override fun close() {
        closeRequested = true
        if (mutex.tryLock()) {
            try {
                releaseLocked()
            } finally {
                mutex.unlock()
            }
        }
    }

    private fun releaseLocked() {
        if (released) return
        released = true
        renderer.close()
    }
}
