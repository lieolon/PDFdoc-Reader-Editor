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

/** A block of the source: a heading (level 1–3), a list item or a paragraph. */
data class TextBlock(val text: String, val heading: Int = 0, val bullet: Boolean = false, val code: Boolean = false)

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
                stream.beginText()
                stream.setFont(font, size)
                if (b.bullet && i == 0) {
                    stream.newLineAtOffset(margin + 4f, y)
                    stream.showText("•")
                    stream.newLineAtOffset(indent - 4f, 0f)
                } else {
                    stream.newLineAtOffset(margin + indent, y)
                }
                stream.showText(line)
                stream.endText()
                y -= leading
            }
            space(if (b.bullet) 2f else size * 0.5f)
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
            }
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
