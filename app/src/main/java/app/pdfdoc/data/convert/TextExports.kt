package app.pdfdoc.data.convert

import app.pdfdoc.data.pdf.TextExtractor
import app.pdfdoc.domain.edit.Box
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

enum class TextFormat(val extension: String, val mime: String) {
    HTML("html", "text/html"),
    MARKDOWN("md", "text/markdown"),
    EPUB("epub", "application/epub+zip"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
}

/**
 * Text-based exports. Like DOCX they keep the text and its paragraphs, not the page layout.
 * XLSX turns every page into a sheet, splitting lines into cells at wide gaps, which recovers
 * simple tables.
 */
class TextExports @Inject constructor() {

    fun export(source: File, format: TextFormat, title: String, out: OutputStream) {
        when (format) {
            TextFormat.HTML -> out.write(html(paragraphs(source), title).toByteArray())
            TextFormat.MARKDOWN -> out.write(markdown(paragraphs(source), title).toByteArray())
            TextFormat.EPUB -> epub(paragraphs(source), title, out)
            TextFormat.XLSX -> xlsx(tables(source), out)
        }
    }

    /** Paragraphs of each page. */
    fun paragraphs(source: File): List<List<String>> =
        PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                paragraphStart = " "
                paragraphEnd = ""
            }
            (1..doc.numberOfPages).map { i ->
                stripper.startPage = i
                stripper.endPage = i
                DocxWriter.paragraphs(stripper.getText(doc))
            }
        }

    /** Rows and cells of each page. */
    fun tables(source: File): List<List<List<String>>> =
        PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            (0 until doc.numberOfPages).map { i ->
                val text = runCatching { TextExtractor.extract(doc, i) }.getOrNull() ?: return@map emptyList()
                rows(text.glyphs.mapNotNull { g -> g.box?.let { it to g.ch } })
            }
        }

    private fun html(pages: List<List<String>>, title: String) = buildString {
        append("<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><title>").append(esc(title)).append("</title>")
        append("<style>body{font-family:serif;max-width:46em;margin:2em auto;padding:0 1em;line-height:1.5}")
        append(".page{color:#888;font:small sans-serif;border-top:1px solid #ddd;margin-top:2em;padding-top:.5em}</style></head><body>\n")
        append("<h1>").append(esc(title)).append("</h1>\n")
        pages.forEachIndexed { i, paragraphs ->
            append("<div class=\"page\" id=\"page").append(i + 1).append("\">").append(i + 1).append("</div>\n")
            paragraphs.forEach { append("<p>").append(esc(it)).append("</p>\n") }
        }
        append("</body></html>\n")
    }

    private fun markdown(pages: List<List<String>>, title: String) = buildString {
        append("# ").append(title).append("\n\n")
        pages.forEachIndexed { i, paragraphs ->
            if (i > 0) append("---\n\n")
            append("<!-- page ").append(i + 1).append(" -->\n\n")
            paragraphs.forEach { append(it.replace(Regex("([*_`#\\[\\]])"), "\\\\$1")).append("\n\n") }
        }
    }

    private fun epub(pages: List<List<String>>, title: String, out: OutputStream) {
        val id = "urn:uuid:${UUID.randomUUID()}"
        ZipOutputStream(out).use { zip ->
            // The mimetype entry must come first and be stored uncompressed.
            val mimetype = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimetype.size.toLong()
                compressedSize = size
                crc = CRC32().apply { update(mimetype) }.value
            })
            zip.write(mimetype)
            zip.closeEntry()
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            entry("META-INF/container.xml", """<?xml version="1.0" encoding="UTF-8"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            val chapters = pages.indices.map { "page${it + 1}.xhtml" }
            pages.forEachIndexed { i, paragraphs ->
                entry("OEBPS/${chapters[i]}", buildString {
                    append("""<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE html><html xmlns="http://www.w3.org/1999/xhtml"><head><title>""")
                    append(i + 1).append("</title></head><body>")
                    paragraphs.forEach { append("<p>").append(esc(it)).append("</p>") }
                    append("</body></html>")
                })
            }
            entry("OEBPS/nav.xhtml", buildString {
                append("""<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE html><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>""")
                append(esc(title)).append("""</title></head><body><nav epub:type="toc"><ol>""")
                chapters.forEachIndexed { i, c -> append("<li><a href=\"").append(c).append("\">").append(i + 1).append("</a></li>") }
                append("</ol></nav></body></html>")
            })
            entry("OEBPS/content.opf", buildString {
                append("""<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">""")
                append("<dc:identifier id=\"id\">").append(id).append("</dc:identifier><dc:title>").append(esc(title))
                append("</dc:title><dc:language>und</dc:language><meta property=\"dcterms:modified\">")
                append(java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date()))
                append("</meta></metadata><manifest><item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>")
                chapters.forEachIndexed { i, c -> append("<item id=\"p").append(i + 1).append("\" href=\"").append(c).append("\" media-type=\"application/xhtml+xml\"/>") }
                append("</manifest><spine>")
                chapters.indices.forEach { append("<itemref idref=\"p").append(it + 1).append("\"/>") }
                append("</spine></package>")
            })
        }
    }

    private fun xlsx(pages: List<List<List<String>>>, out: OutputStream) {
        val sheets = pages.ifEmpty { listOf(emptyList()) }
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            entry("[Content_Types].xml", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
                sheets.indices.forEach { append("<Override PartName=\"/xl/worksheets/sheet").append(it + 1).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>") }
                append("</Types>")
            })
            entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            entry("xl/workbook.xml", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
                sheets.indices.forEach { append("<sheet name=\"").append(it + 1).append("\" sheetId=\"").append(it + 1).append("\" r:id=\"rId").append(it + 1).append("\"/>") }
                append("</sheets></workbook>")
            })
            entry("xl/_rels/workbook.xml.rels", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
                sheets.indices.forEach { append("<Relationship Id=\"rId").append(it + 1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet").append(it + 1).append(".xml\"/>") }
                append("</Relationships>")
            })
            sheets.forEachIndexed { i, rows ->
                entry("xl/worksheets/sheet${i + 1}.xml", buildString {
                    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
                    rows.forEachIndexed { r, cells ->
                        append("<row r=\"").append(r + 1).append("\">")
                        cells.forEachIndexed { c, value ->
                            val ref = column(c) + (r + 1)
                            val number = numberOf(value)
                            if (number != null) append("<c r=\"").append(ref).append("\"><v>").append(number).append("</v></c>")
                            else append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(esc(value)).append("</t></is></c>")
                        }
                        append("</row>")
                    }
                    append("</sheetData></worksheet>")
                })
            }
        }
    }

    companion object {
        /**
         * Groups glyphs into lines (by vertical overlap) and lines into cells, starting a new
         * cell where the gap to the previous glyph is wider than about two characters.
         */
        fun rows(glyphs: List<Pair<Box, Char>>): List<List<String>> {
            val lines = mutableListOf<MutableList<Pair<Box, Char>>>()
            glyphs.sortedWith(compareBy({ it.first.center.y }, { it.first.left })).forEach { g ->
                val line = lines.lastOrNull()?.takeIf { l -> l.last().first.verticalOverlap(g.first) > 0.5f }
                if (line != null) line += g else lines += mutableListOf(g)
            }
            return lines.map { line ->
                val sorted = line.sortedBy { it.first.left }
                val height = sorted.map { it.first.height }.average().toFloat()
                val cells = mutableListOf(StringBuilder())
                var previous: Box? = null
                sorted.forEach { (box, ch) ->
                    val gap = previous?.let { box.left - it.right } ?: 0f
                    when {
                        gap > height * 1.6f -> cells += StringBuilder()
                        gap > height * 0.2f && cells.last().isNotEmpty() -> cells.last().append(' ')
                    }
                    cells.last().append(ch)
                    previous = box
                }
                cells.map { it.toString().trim() }
            }
        }

        /** A cell value that is a plain number; codes with leading zeros stay text. */
        fun numberOf(value: String): Double? {
            val compact = value.replace(" ", "").replace('\u00A0', ' ').replace(" ", "")
            if (compact.length > 1 && compact.startsWith("0") && compact[1].isDigit()) return null
            return compact.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
        }

        fun column(index: Int): String {
            var n = index + 1
            val sb = StringBuilder()
            while (n > 0) {
                val r = (n - 1) % 26
                sb.insert(0, 'A' + r)
                n = (n - 1) / 26
            }
            return sb.toString()
        }

        private fun esc(text: String) = DocxWriter.escape(text)
    }
}
