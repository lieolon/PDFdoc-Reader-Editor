package app.pdfdoc.data.pdf

import android.content.Context
import app.pdfdoc.data.ocr.OcrWord
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Adds OCR results to a page as invisible text (rendering mode 3), positioned over the words in
 * the page image. The page looks unchanged but becomes searchable and selectable.
 */
class TextLayerWriter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * [words] are in pixels of an image of [imageWidth] x [imageHeight] that covers the whole page
     * as displayed (rotation applied).
     */
    fun write(doc: PDDocument, page: PDPage, words: List<OcrWord>, imageWidth: Int, imageHeight: Int, font: PDType0Font) {
        val geometry = PageGeometry.of(page)
        write(doc, page, words, geometry.displayWidth / imageWidth, geometry.displayHeight / imageHeight, font)
    }

    /** [words] already in page display points. */
    fun writeInPoints(doc: PDDocument, page: PDPage, words: List<OcrWord>, font: PDType0Font) =
        write(doc, page, words, 1f, 1f, font)

    private fun write(doc: PDDocument, page: PDPage, words: List<OcrWord>, sx: Float, sy: Float, font: PDType0Font) {
        if (words.isEmpty()) return
        val geometry = PageGeometry.of(page)
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.transform(geometry.matrix())
            cs.beginText()
            cs.setRenderingMode(RenderingMode.NEITHER)
            words.forEach { word ->
                val text = AppearancePainter.safeText(font, word.text)
                val left = word.box.left * sx
                val bottom = word.box.bottom * sy
                val height = word.box.height * sy
                val width = word.box.width * sx
                if (height <= 0f || width <= 0f) return@forEach
                val size = height
                val natural = font.getStringWidth(text) / 1000f * size
                if (natural <= 0f) return@forEach
                cs.setFont(font, size)
                cs.setHorizontalScaling(100f * width / natural)
                // Word boxes include descenders; put the baseline slightly above the bottom.
                cs.setTextMatrix(Matrix(1f, 0f, 0f, -1f, left, bottom - height * 0.2f))
                cs.showText("$text ")
            }
            cs.endText()
        }
    }

    fun font(doc: PDDocument): PDType0Font = PdfFonts.load(doc, context)
}
