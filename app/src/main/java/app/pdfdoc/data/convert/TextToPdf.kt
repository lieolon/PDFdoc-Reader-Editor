package app.pdfdoc.data.convert

import android.content.Context
import app.pdfdoc.data.pdf.AppearancePainter
import app.pdfdoc.data.pdf.PdfFonts
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

enum class SourceFormat { TEXT, MARKDOWN, HTML }

enum class TextAlign { LEFT, CENTER, RIGHT, JUSTIFY }

data class TableCell(val text: String, val bold: Boolean = false, val span: Int = 1)

/** A table: [widths] are relative column widths (may be empty: equal columns), each row a list of cells. */
data class Table(val widths: List<Float>, val rows: List<List<TableCell>>)

/** A block of the source: a heading (level 1–3), a list item, a paragraph or a [table]. */
data class TextBlock(
    val text: String,
    val heading: Int = 0,
    val bullet: Boolean = false,
    val code: Boolean = false,
    val bold: Boolean = false,
    val align: TextAlign = TextAlign.LEFT,
    val table: Table? = null,
)

/** Creates an A4 PDF from plain text, Markdown or HTML, with automatic line and page breaks. */
class TextToPdf @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun convert(text: String, format: SourceFormat, title: String, target: File) {
        val blocks = when (format) {
            SourceFormat.TEXT -> plain(text)
            SourceFormat.MARKDOWN -> markdown(text)
            SourceFormat.HTML -> HtmlText.blocks(text)
        }
        write(blocks, title, target)
    }

    /** Lays out [blocks] on A4 pages; long documents are buffered in temporary files, not memory. */
    fun write(blocks: List<TextBlock>, title: String, target: File) {
        PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            val font = PdfFonts.load(doc, context)
            Layout(doc, font).apply { blocks.forEach(::block) }.finish()
            doc.documentInformation.title = title
            doc.documentInformation.creator = "PDFdoc"
            doc.save(target)
        }
    }

    private class Layout(private val doc: PDDocument, private val font: PDType0Font) {
        private val pageSize = PDRectangle.A4
        private val margin = 56f
        private val width = pageSize.width - 2 * margin
        private var cs: PDPageContentStream? = null
        private var y = 0f

        fun block(b: TextBlock) {
            b.table?.let { table(it); return }
            val size = when (b.heading) {
                1 -> 20f
                2 -> 16f
                3 -> 13.5f
                else -> 11f
            }
            val leading = size * 1.4f
            val indent = if (b.bullet) 16f else 0f
            if (b.heading > 0) space(size * 0.6f)
            val lines = wrap(b.text.replace("\t", "    ").split('\n').joinToString("\n") { AppearancePainter.safeText(font, it) }, size, width - indent)
            lines.forEachIndexed { i, line ->
                ensure(leading)
                val stream = cs!!
                if (b.bullet && i == 0) text(stream, "•", margin + 4f, y, size, bold = false)
                // The last line of a justified paragraph (and every line ending a source line) stays left aligned.
                val justify = b.align == TextAlign.JUSTIFY && i < lines.size - 1 && !lineEnds.contains(i)
                drawLine(stream, line, margin + indent, width - indent, y, size, b.bold || b.heading > 0, if (justify) TextAlign.JUSTIFY else if (b.align == TextAlign.JUSTIFY) TextAlign.LEFT else b.align)
                y -= leading
            }
            space(if (b.bullet) 2f else size * 0.5f)
        }

        /** Indices of wrapped lines that end a source line (set by [wrap]); they are never stretched. */
        private var lineEnds = emptySet<Int>()

        private fun drawLine(stream: PDPageContentStream, line: String, x: Float, w: Float, y: Float, size: Float, bold: Boolean, align: TextAlign) {
            val lineWidth = font.getStringWidth(line) / 1000f * size
            when (align) {
                TextAlign.CENTER -> text(stream, line, x + (w - lineWidth) / 2, y, size, bold)
                TextAlign.RIGHT -> text(stream, line, x + w - lineWidth, y, size, bold)
                TextAlign.JUSTIFY -> {
                    val words = line.split(' ').filter { it.isNotEmpty() }
                    if (words.size < 2) {
                        text(stream, line, x, y, size, bold)
                    } else {
                        val wordsWidth = words.sumOf { (font.getStringWidth(it) / 1000f * size).toDouble() }.toFloat()
                        val gap = (w - wordsWidth) / (words.size - 1)
                        var cx = x
                        words.forEach { word ->
                            text(stream, word, cx, y, size, bold)
                            cx += font.getStringWidth(word) / 1000f * size + gap
                        }
                    }
                }
                TextAlign.LEFT -> text(stream, line, x, y, size, bold)
            }
        }

        /** Bold is drawn by stroking the outline of the regular font, which is the only one embedded. */
        private fun text(stream: PDPageContentStream, value: String, x: Float, y: Float, size: Float, bold: Boolean) {
            stream.beginText()
            stream.setFont(font, size)
            if (bold) {
                stream.setRenderingMode(com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode.FILL_STROKE)
                stream.setLineWidth(size * 0.035f)
            }
            stream.newLineAtOffset(x, y)
            stream.showText(value)
            if (bold) stream.setRenderingMode(com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode.FILL)
            stream.endText()
        }

        /** A table with thin borders; a row moves to the next page when it does not fit. */
        private fun table(t: Table) {
            val columns = maxOf(t.widths.size, t.rows.maxOfOrNull { r -> r.sumOf { it.span } } ?: 1).coerceAtLeast(1)
            val raw = if (t.widths.size == columns && t.widths.all { it > 0f }) t.widths else List(columns) { 1f }
            val total = raw.sum()
            val colWidths = raw.map { it / total * width }
            val size = 10f
            val leading = size * 1.3f
            val pad = 4f
            space(4f)
            t.rows.forEach { row ->
                // Each cell spans one or more grid columns.
                var col = 0
                val cells = row.map { c ->
                    val w = (col until (col + c.span).coerceAtMost(columns)).sumOf { colWidths[it].toDouble() }.toFloat()
                    val x = margin + (0 until col.coerceAtMost(columns)).sumOf { colWidths[it].toDouble() }.toFloat()
                    col += c.span
                    val lines = wrap(c.text.replace("\t", "    ").split('\n').joinToString("\n") { AppearancePainter.safeText(font, it) }, size, (w - 2 * pad).coerceAtLeast(10f))
                    Triple(c, x to w, lines)
                }.filter { it.second.second > 0f }
                val height = (cells.maxOfOrNull { it.third.size } ?: 1) * leading + 2 * pad
                ensure(height)
                val stream = cs!!
                val top = y + size * 0.9f + pad
                stream.setLineWidth(0.6f)
                stream.setStrokingColor(0f, 0f, 0f)
                cells.forEach { (cell, xw, lines) ->
                    val (x, w) = xw
                    stream.addRect(x, top - height, w, height)
                    stream.stroke()
                    lines.forEachIndexed { i, line ->
                        text(stream, line, x + pad, top - pad - size * 0.9f - i * leading, size, cell.bold)
                    }
                }
                // The next row starts exactly at this row's bottom border.
                y = top - height - size * 0.9f - pad
            }
            space(8f)
        }

        private fun space(h: Float) {
            if (cs != null) y -= h
        }

        private fun ensure(h: Float) {
            if (cs == null || y - h < margin) {
                cs?.close()
                val page = PDPage(pageSize)
                doc.addPage(page)
                cs = PDPageContentStream(doc, page)
                y = pageSize.height - margin - h * 0.8f
            }
        }

        private fun wrap(text: String, size: Float, max: Float): List<String> {
            fun w(s: String) = font.getStringWidth(s) / 1000f * size
            val lines = mutableListOf<String>()
            val ends = mutableSetOf<Int>()
            text.split('\n').forEach { para ->
                var line = ""
                para.split(' ').forEach { word ->
                    val candidate = if (line.isEmpty()) word else "$line $word"
                    if (w(candidate) <= max) {
                        line = candidate
                    } else {
                        if (line.isNotEmpty()) lines += line
                        // A single word longer than the line is cut into pieces.
                        var rest = word
                        while (w(rest) > max && rest.length > 1) {
                            var cut = rest.length - 1
                            while (cut > 1 && w(rest.substring(0, cut)) > max) cut--
                            lines += rest.substring(0, cut)
                            rest = rest.substring(cut)
                        }
                        line = rest
                    }
                }
                lines += line
                ends += lines.size - 1
            }
            lineEnds = ends
            return lines
        }

        fun finish() {
            if (cs == null) ensure(0f)
            cs?.close()
        }
    }

    companion object {
        fun plain(text: String): List<TextBlock> =
            text.replace("\r\n", "\n").split(Regex("\n\\s*\n")).map { it.trimEnd() }.filter { it.isNotBlank() }.map { TextBlock(it) }

        /** The common subset of Markdown: headings, lists, paragraphs and code blocks; inline marks are dropped. */
        fun markdown(text: String): List<TextBlock> {
            val blocks = mutableListOf<TextBlock>()
            val paragraph = StringBuilder()
            var code: StringBuilder? = null
            fun flush() {
                if (paragraph.isNotBlank()) blocks += TextBlock(inline(paragraph.toString().trim()))
                paragraph.clear()
            }
            text.replace("\r\n", "\n").lines().forEach { raw ->
                val line = raw.trimEnd()
                if (line.trimStart().startsWith("```")) {
                    if (code == null) {
                        flush()
                        code = StringBuilder()
                    } else {
                        blocks += TextBlock(code.toString().trimEnd(), code = true)
                        code = null
                    }
                    return@forEach
                }
                code?.let { it.append(raw).append('\n'); return@forEach }
                val heading = Regex("^(#{1,6})\\s+(.*)$").find(line)
                val bullet = Regex("^\\s*(?:[-*+]|\\d+[.)])\\s+(.*)$").find(line)
                when {
                    line.isBlank() -> flush()
                    heading != null -> {
                        flush()
                        blocks += TextBlock(inline(heading.groupValues[2].trimEnd('#', ' ')), heading = heading.groupValues[1].length.coerceAtMost(3))
                    }
                    bullet != null -> {
                        flush()
                        blocks += TextBlock(inline(bullet.groupValues[1]), bullet = true)
                    }
                    Regex("^\\s*([-*_]\\s*){3,}$").matches(line) -> flush()
                    else -> {
                        if (paragraph.isNotEmpty()) paragraph.append(' ')
                        paragraph.append(line.trim().removePrefix(">").trim())
                    }
                }
            }
            code?.let { blocks += TextBlock(it.toString().trimEnd(), code = true) }
            flush()
            return blocks
        }

        private fun inline(s: String) = s
            .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("\\[([^]]*)]\\(([^)]*)\\)"), "$1 ($2)")
            .replace(Regex("(\\*\\*|__|\\*|_|`|~~)"), "")
    }
}
