package app.pdfdoc.data.convert

import androidx.test.core.app.ApplicationProvider
import app.pdfdoc.data.pdf.PdfFonts
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocxWriterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
    }

    @Test
    fun paragraphsJoinLinesAndHyphenation() {
        val text = " Первая строка абзаца и пере-\nнос слова.\n Второй абзац.\n"
        assertEquals(listOf("Первая строка абзаца и перенос слова.", "Второй абзац."), DocxWriter.paragraphs(text))
    }

    @Test
    fun escapesXmlAndDropsControlCharacters() {
        assertEquals("a &lt;b&gt; &amp; \"c\"".replace("\"", "&quot;"), DocxWriter.escape("a <b> & \"c\"\u0001"))
    }

    @Test
    fun convertsPdfToValidPackageWithPageBreaks() {
        val pdf = tmp.newFile()
        PDDocument().use { doc ->
            val font = PdfFonts.load(doc, context)
            listOf("Договор № 1", "Приложение").forEach { title ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(font, 14f)
                    cs.newLineAtOffset(72f, 760f)
                    cs.showText(title)
                    cs.endText()
                }
            }
            doc.save(pdf)
        }
        val bytes = ByteArrayOutputStream().also { DocxWriter().convert(pdf, it) }.toByteArray()
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes().decodeToString() }
        }
        assertEquals(setOf("[Content_Types].xml", "_rels/.rels", "word/document.xml"), entries.keys)
        val doc = entries.getValue("word/document.xml")
        assertTrue(doc, doc.contains("Договор № 1") && doc.contains("Приложение"))
        assertEquals(1, Regex("""w:type="page"""").findAll(doc).count())
    }
}
