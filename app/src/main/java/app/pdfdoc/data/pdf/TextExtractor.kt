package app.pdfdoc.data.pdf

import app.pdfdoc.domain.edit.Box
import app.pdfdoc.domain.text.Glyph
import app.pdfdoc.domain.text.PageText
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.StringWriter
import kotlin.math.max

/** Extracts per-character positions in page display space using PDFBox. */
object TextExtractor {

    fun extract(doc: PDDocument, pageIndex: Int): PageText {
        val collector = GlyphCollector()
        collector.sortByPosition = true
        collector.startPage = pageIndex + 1
        collector.endPage = pageIndex + 1
        collector.writeText(doc, StringWriter())
        return PageText(collector.glyphs)
    }

    private class GlyphCollector : PDFTextStripper() {
        val glyphs = ArrayList<Glyph>()

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            textPositions.forEach { p ->
                val box = boxOf(p)
                p.unicode.forEach { ch -> glyphs += Glyph(ch, box) }
            }
        }

        override fun writeWordSeparator() {
            glyphs += Glyph(' ', null)
        }

        override fun writeLineSeparator() {
            glyphs += Glyph('\n', null)
        }

        /** getX/getY are rotation-adjusted with a top-left origin; y is the baseline. */
        private fun boxOf(p: TextPosition): Box {
            val size = if (p.fontSizeInPt > 0f) p.fontSizeInPt else p.heightDir
            val ascent = max(p.heightDir, size * 0.75f)
            return Box(p.x, p.y - ascent, p.x + p.width, p.y + size * 0.22f)
        }
    }
}
