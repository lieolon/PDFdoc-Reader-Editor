package app.pdfdoc.data.convert

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * Minimal PDF → DOCX conversion: text only, grouped into paragraphs, one Word page break per PDF
 * page. Layout, tables and images are not reproduced — the UI warns about this.
 */
class DocxWriter @Inject constructor() {

    fun convert(source: File, out: OutputStream) {
        val pages = PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                paragraphStart = PARAGRAPH
                paragraphEnd = ""
            }
            (1..doc.numberOfPages).map { i ->
                stripper.startPage = i
                stripper.endPage = i
                paragraphs(stripper.getText(doc))
            }
        }
        write(pages, out)
    }

    /** Writes [pages] (each a list of paragraphs) as a .docx package. */
    fun write(pages: List<List<String>>, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            entry("[Content_Types].xml", CONTENT_TYPES)
            entry("_rels/.rels", RELS)
            entry("word/document.xml", document(pages))
        }
    }

    private fun document(pages: List<List<String>>) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
        pages.forEachIndexed { i, paragraphs ->
            if (i > 0) append("""<w:p><w:r><w:br w:type="page"/></w:r></w:p>""")
            paragraphs.forEach { text ->
                append("<w:p><w:r><w:t xml:space=\"preserve\">").append(escape(text)).append("</w:t></w:r></w:p>")
            }
        }
        // A4 with common Russian office margins.
        append("""<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="850" w:bottom="1134" w:left="1701" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>""")
        append("</w:body></w:document>")
    }

    companion object {
        private const val PARAGRAPH = " "

        /**
         * Splits stripper output into paragraphs: lines inside a paragraph are joined, and a word
         * hyphenated at a line end is glued back together.
         */
        fun paragraphs(text: String): List<String> =
            text.split(PARAGRAPH).mapNotNull { block ->
                val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (lines.isEmpty()) return@mapNotNull null
                buildString {
                    lines.forEachIndexed { i, line ->
                        if (i > 0) {
                            if (endsWith("-") && length > 1 && this[length - 2].isLetter() && line.first().isLowerCase()) {
                                setLength(length - 1)
                            } else {
                                append(' ')
                            }
                        }
                        append(line)
                    }
                }
            }

        /** XML-escapes text and drops characters that are not allowed in XML 1.0. */
        fun escape(text: String): String = buildString(text.length) {
            text.forEach { c ->
                when {
                    c == '&' -> append("&amp;")
                    c == '<' -> append("&lt;")
                    c == '>' -> append("&gt;")
                    c == '"' -> append("&quot;")
                    c == '\t' || c >= ' ' && c != '￾' && c != '￿' -> append(c)
                }
            }
        }

        private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""

        private const val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
    }
}
