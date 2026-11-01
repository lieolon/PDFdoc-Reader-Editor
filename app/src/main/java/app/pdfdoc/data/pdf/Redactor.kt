package app.pdfdoc.data.pdf

import android.graphics.Canvas
import android.graphics.Paint
import app.pdfdoc.data.ocr.OcrWord
import app.pdfdoc.domain.edit.Box
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Removes content for good. Each affected page is replaced by a high-resolution image with the
 * regions painted black, so hidden text, vector graphics and images are gone from the file.
 * Text outside the regions is put back as an invisible layer, so the page stays searchable.
 * Annotations touching a region are deleted as well.
 */
class Redactor @Inject constructor(
    private val renderers: PdfRenderSessionFactory,
    private val textLayer: TextLayerWriter,
) {
    /** [areas] are per zero-based page, in page display space. */
    suspend fun redact(source: File, target: File, areas: Map<Int, List<Box>>) {
        renderers.open(source).use { session ->
            PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                val font by lazy { textLayer.font(doc) }
                areas.filterValues { it.isNotEmpty() }.forEach { (index, boxes) ->
                    if (index !in 0 until doc.numberOfPages) return@forEach
                    val page = doc.getPage(index)
                    val geometry = PageGeometry.of(page)
                    val words = runCatching { TextExtractor.extract(doc, index) }.getOrNull()
                        ?.let { keptWords(it, boxes) }.orEmpty()

                    val width = (geometry.displayWidth * DPI / 72f).roundToInt().coerceIn(1, MAX_SIDE)
                    val bitmap = session.render(index, width) ?: error("render $index")
                    val k = bitmap.width / geometry.displayWidth
                    val paint = Paint().apply { color = android.graphics.Color.BLACK }
                    Canvas(bitmap).apply {
                        boxes.forEach { b -> drawRect(b.left * k, b.top * k, b.right * k, b.bottom * k, paint) }
                    }
                    val image = JPEGFactory.createFromImage(doc, bitmap, 0.92f)
                    bitmap.recycle()

                    // Fresh resources drop every font, image and form the old content used.
                    page.resources = PDResources()
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.OVERWRITE, true, false).use { cs ->
                        cs.transform(geometry.matrix())
                        cs.drawImage(image, Matrix(geometry.displayWidth, 0f, 0f, -geometry.displayHeight, 0f, geometry.displayHeight))
                    }
                    textLayer.writeInPoints(doc, page, words, font)

                    page.annotations = ArrayList(page.annotations.filterNot { a ->
                        val rect = a.rectangle ?: return@filterNot false
                        val box = PdfLinks.displayBox(geometry, rect)
                        boxes.any { overlaps(it, box) }
                    })
                }
                doc.save(target)
            }
        }
    }

    /** Words of the page that do not touch any redacted region, in display points. */
    private fun keptWords(text: app.pdfdoc.domain.text.PageText, boxes: List<Box>): List<OcrWord> {
        val words = mutableListOf<OcrWord>()
        val current = StringBuilder()
        var box: Box? = null
        var hidden = false
        fun flush() {
            val b = box
            if (current.isNotBlank() && b != null && !hidden) words += OcrWord(current.toString(), b, 100f)
            current.clear()
            box = null
            hidden = false
        }
        text.glyphs.forEach { g ->
            val gb = g.box
            if (gb == null || g.ch.isWhitespace()) {
                flush()
                return@forEach
            }
            current.append(g.ch)
            box = box?.union(gb) ?: gb
            if (boxes.any { overlaps(it, gb) }) hidden = true
        }
        flush()
        return words
    }

    private fun overlaps(a: Box, b: Box) =
        min(a.right, b.right) > max(a.left, b.left) && min(a.bottom, b.bottom) > max(a.top, b.top)

    private companion object {
        const val DPI = 200f
        const val MAX_SIDE = 2600
    }
}
