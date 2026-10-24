package app.pdfdoc.data.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.pdfdoc.data.ocr.OcrEngine
import app.pdfdoc.data.pdf.TextLayerWriter
import app.pdfdoc.domain.scan.ScanFilter
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

sealed interface ExportProgress {
    data class Processing(val page: Int, val total: Int) : ExportProgress
    data class Recognizing(val page: Int, val total: Int) : ExportProgress
}

/** Turns straightened page images into a PDF, optionally with an OCR text layer. */
class ScanExporter @Inject constructor(
    private val ocr: OcrEngine,
    private val textLayer: TextLayerWriter,
) {
    suspend fun writePdf(
        pages: List<File>,
        filter: ScanFilter,
        recognize: Boolean,
        target: File,
        progress: (ExportProgress) -> Unit,
    ) {
        PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            val font by lazy { textLayer.font(doc) }
            pages.forEachIndexed { i, file ->
                currentCoroutineContext().ensureActive()
                progress(ExportProgress.Processing(i + 1, pages.size))
                val bitmap = filtered(file, filter)
                val page = PDPage(pageSize(bitmap.width, bitmap.height))
                doc.addPage(page)
                val image = JPEGFactory.createFromImage(doc, bitmap, JPEG_QUALITY)
                PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(image, 0f, 0f, page.mediaBox.width, page.mediaBox.height)
                }
                if (recognize) {
                    progress(ExportProgress.Recognizing(i + 1, pages.size))
                    val words = ocr.recognize(bitmap)
                    textLayer.write(doc, page, words, bitmap.width, bitmap.height, font)
                }
                bitmap.recycle()
            }
            doc.save(target)
        }
    }

    fun filtered(file: File, filter: ScanFilter): Bitmap {
        val source = BitmapFactory.decodeFile(file.absolutePath) ?: throw IOException("Cannot decode $file")
        return ScanImages.filter(source, filter)
    }

    private companion object {
        const val JPEG_QUALITY = 0.85f
        /** Short side of an A4 page in points; the long side follows the image aspect ratio. */
        const val SHORT_SIDE = 595f

        fun pageSize(w: Int, h: Int): PDRectangle =
            if (w <= h) PDRectangle(SHORT_SIDE, SHORT_SIDE * h / w) else PDRectangle(SHORT_SIDE * w / h, SHORT_SIDE)
    }
}
