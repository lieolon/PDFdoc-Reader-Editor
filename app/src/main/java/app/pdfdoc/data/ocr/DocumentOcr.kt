package app.pdfdoc.data.ocr

import app.pdfdoc.data.pdf.PageGeometry
import app.pdfdoc.data.pdf.PdfRenderSessionFactory
import app.pdfdoc.data.pdf.TextExtractor
import app.pdfdoc.data.pdf.TextLayerWriter
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt

/** Makes scanned pages of an existing PDF searchable by adding an invisible OCR text layer. */
class DocumentOcr @Inject constructor(
    private val ocr: OcrEngine,
    private val renderers: PdfRenderSessionFactory,
    private val textLayer: TextLayerWriter,
) {
    /**
     * Recognizes every page of [source] that has no text layer and writes the result to [target].
     * Returns the number of recognized pages; [target] is not written when it is 0.
     */
    suspend fun makeSearchable(source: File, target: File, progress: (page: Int, total: Int) -> Unit): Int {
        var recognized = 0
        renderers.open(source).use { session ->
            PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                val font by lazy { textLayer.font(doc) }
                val total = doc.numberOfPages
                for (i in 0 until total) {
                    currentCoroutineContext().ensureActive()
                    progress(i + 1, total)
                    if (!TextExtractor.extract(doc, i).isEmpty) continue
                    val page = doc.getPage(i)
                    val geometry = PageGeometry.of(page)
                    // About 300 dpi, capped to keep memory reasonable.
                    val width = (geometry.displayWidth * DPI / 72f).roundToInt().coerceIn(1, MAX_SIDE)
                    val bitmap = session.render(i, width) ?: continue
                    val words = ocr.recognize(bitmap)
                    textLayer.write(doc, page, words, bitmap.width, bitmap.height, font)
                    bitmap.recycle()
                    if (words.isNotEmpty()) recognized++
                }
                if (recognized > 0) doc.save(target)
            }
        }
        return recognized
    }

    private companion object {
        const val DPI = 300f
        const val MAX_SIDE = 3000
    }
}
