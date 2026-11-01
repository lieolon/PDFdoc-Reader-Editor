package app.pdfdoc.data.convert

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentFormatsTest {

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            entries.forEach { (name, text) ->
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun detectsFormats() {
        assertEquals(ImportFormat.TEXT, ImportFormats.detect("notes.txt", null))
        assertEquals(ImportFormat.TEXT, ImportFormats.detect("file", "text/plain"))
        assertEquals(ImportFormat.MARKDOWN, ImportFormats.detect("README.md", "application/octet-stream"))
        assertEquals(ImportFormat.DOCX, ImportFormats.detect("a.DOCX", null))
        assertEquals(ImportFormat.FB2, ImportFormats.detect("book.fb2", "application/octet-stream"))
        assertEquals(ImportFormat.IMAGE, ImportFormats.detect("photo", "image/jpeg"))
        assertNull(ImportFormats.detect("doc.pdf", "text/plain"))
        assertNull(ImportFormats.detect("blob", "application/octet-stream"))
    }

    @Test
    fun decodesEncodings() {
        val text = "Привет, мир! Это проверка."
        assertEquals(text, TextDecoding.decode(text.toByteArray(Charsets.UTF_8)))
        assertEquals(text, TextDecoding.decode(text.toByteArray(charset("windows-1251"))))
        assertEquals(text, TextDecoding.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)))
        assertEquals(text, TextDecoding.decode(text.toByteArray(Charsets.UTF_16LE)))
        val latin = "Café crème à la française, déjà vu."
        assertEquals(latin, TextDecoding.decode(latin.toByteArray(charset("windows-1252"))))
    }

    @Test
    fun htmlBlocks() {
        val blocks = HtmlText.blocks(
            "<html><head><title>T</title><style>p{}</style></head><body><h1>Title</h1>" +
                "<p>One &amp; <b>two</b>\n   three</p><ul><li>A</li><li>B</li></ul><p>x<br>y</p></body></html>",
        )
        assertEquals(
            listOf(TextBlock("Title", heading = 1), TextBlock("One & two three"), TextBlock("A", bullet = true), TextBlock("B", bullet = true), TextBlock("x\ny")),
            blocks,
        )
    }

    @Test
    fun docxParagraphs() {
        val xml = """<w:document><w:body>
            <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Глава</w:t></w:r></w:p>
            <w:p><w:r><w:t xml:space="preserve">Hello </w:t></w:r><w:r><w:t>world</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/></w:numPr></w:pPr><w:r><w:t>Item</w:t></w:r></w:p>
            <w:p><w:del><w:r><w:delText>gone</w:delText></w:r></w:del><w:r><w:t>kept</w:t></w:r></w:p>
            </w:body></w:document>"""
        assertEquals(
            listOf(TextBlock("Глава", heading = 1), TextBlock("Hello world"), TextBlock("Item", bullet = true), TextBlock("kept")),
            OfficeText.docx(zip("word/document.xml" to xml)),
        )
    }

    @Test
    fun odtParagraphs() {
        val xml = """<office:document-content><office:body><office:text>
            <text:h text:outline-level="2">Head</text:h>
            <text:p>a<text:s text:c="2"/>b</text:p>
            <text:list><text:list-item><text:p>li</text:p></text:list-item></text:list>
            </office:text></office:body></office:document-content>"""
        assertEquals(
            listOf(TextBlock("Head", heading = 2), TextBlock("a  b"), TextBlock("li", bullet = true)),
            OfficeText.odtXml(xml),
        )
    }

    @Test
    fun epubInSpineOrder() {
        val epub = zip(
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
            "OEBPS/content.opf" to """<package><metadata><dc:title>Книга</dc:title></metadata>
                <manifest><item id="c1" href="text/one.xhtml"/><item id="c2" href="text/two%20b.xhtml"/></manifest>
                <spine><itemref idref="c2"/><itemref idref="c1"/></spine></package>""",
            "OEBPS/text/one.xhtml" to "<html><body><p>First&nbsp;file</p></body></html>",
            "OEBPS/text/two b.xhtml" to "<html><body><h2>Second</h2></body></html>",
        )
        val book = BookText.epub(epub)
        assertEquals("Книга", book.title)
        assertEquals(listOf(TextBlock("Second", heading = 2), TextBlock("First file")), book.blocks)
    }

    @Test
    fun fb2Sections() {
        val xml = """<?xml version="1.0" encoding="utf-8"?><FictionBook><description><title-info>
            <book-title>Роман</book-title></title-info></description><body><section><title><p>Глава 1</p></title>
            <p>Текст <emphasis>главы</emphasis>.</p><empty-line/><p>Ещё</p></section></body>
            <binary id="x">AAAA</binary></FictionBook>"""
        val book = BookText.fb2(xml)
        assertEquals("Роман", book.title)
        assertEquals(listOf(TextBlock("Глава 1", heading = 2), TextBlock("Текст главы."), TextBlock("Ещё")), book.blocks)
    }

    @Test
    fun rtfText() {
        val rtf = """{\rtf1\ansi\ansicpg1251{\fonttbl{\f0 Arial;}}{\colortbl;\red0\green0\blue0;}""" +
            "{\\*\\generator Test;}\\f0 Hello \\b bold\\b0\\par \\'cf\\'f0\\'e8\\'e2\\'e5\\'f2\\par \\uc1\\u1046?ук\\tab x\\par}"
        assertEquals("Hello bold\nПривет\nЖук\tx", RtfText.text(rtf))
    }

    @Test
    fun plainTextKeepsLines() {
        assertEquals(listOf(TextBlock("a\nb"), TextBlock("c")), TextToPdf.plain("a\r\nb\n\n\nc\n"))
    }
}
