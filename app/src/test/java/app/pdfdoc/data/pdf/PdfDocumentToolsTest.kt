package app.pdfdoc.data.pdf

import androidx.test.core.app.ApplicationProvider
import app.pdfdoc.domain.edit.Box
import app.pdfdoc.domain.edit.FieldEdit
import app.pdfdoc.domain.edit.FieldKind
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class PdfDocumentToolsTest {

    @get:Rule val tmp = TemporaryFolder()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val tools by lazy { PdfDocumentTools(context, PdfRenderSessionFactory(context), RenderCopyBuilder()) }

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
    }

    private fun sample(pages: Int = 3): File = tmp.newFile("in.pdf").also { f ->
        PDDocument().use { doc ->
            repeat(pages) { i ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 12f)
                    cs.newLineAtOffset(72f, 700f)
                    cs.showText("Page ${i + 1} contact a@b.com")
                    cs.endText()
                }
            }
            doc.save(f)
        }
    }

    @Test
    fun propertiesRoundTrip() {
        val src = sample()
        val out = tmp.newFile("props.pdf")
        tools.setProperties(src, out, DocProperties(title = "Договор", author = "Ivan", keywords = "a, b"))
        val p = tools.properties(out)
        assertEquals("Договор", p.title)
        assertEquals("Ivan", p.author)
        assertEquals(3, p.pageCount)
    }

    @Test
    fun outlineRoundTrip() {
        val src = sample()
        val out = tmp.newFile("toc.pdf")
        val entries = listOf(OutlineEntry("Intro", 0, 0), OutlineEntry("Part", 1, 1), OutlineEntry("End", 2, 0))
        tools.setOutline(src, out, entries)
        assertEquals(entries, tools.outline(out))
    }

    @Test
    fun attachmentsAddReadRemove() {
        val src = sample()
        val added = tmp.newFile("att.pdf")
        tools.addAttachment(src, added, "note.txt", "text/plain", "hello".byteInputStream())
        assertEquals(listOf("note.txt"), tools.attachments(added).map { it.name })
        assertEquals("hello", tools.readAttachment(added, "note.txt")!!.decodeToString())
        val removed = tmp.newFile("att2.pdf")
        tools.removeAttachment(added, removed, "note.txt")
        assertTrue(tools.attachments(removed).isEmpty())
    }

    @Test
    fun resizeAndCrop() {
        val src = sample(1)
        val resized = tmp.newFile("letter.pdf")
        tools.resizePages(src, resized, PaperSize.LETTER)
        PDDocument.load(resized).use { assertEquals(612f, it.getPage(0).mediaBox.width, 0.5f) }
        val cropped = tmp.newFile("crop.pdf")
        tools.cropMargins(src, cropped, 10f, 20f, 30f, 40f, null)
        PDDocument.load(cropped).use {
            val box = it.getPage(0).cropBox
            assertEquals(PDRectangle.A4.width - 60f, box.width, 0.5f)
            assertEquals(PDRectangle.A4.height - 40f, box.height, 0.5f)
        }
    }

    @Test
    fun sanitizeRemovesMetadataAndComments() {
        val src = tmp.newFile("meta.pdf")
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            doc.documentInformation.author = "Secret"
            page.annotations.add(PDAnnotationText().apply { rectangle = PDRectangle(10f, 10f, 20f, 20f); contents = "hi" })
            doc.save(src)
        }
        val out = tmp.newFile("clean.pdf")
        tools.sanitize(src, out, SanitizeOptions(comments = true))
        PDDocument.load(out).use {
            assertNull(it.documentInformation.author)
            assertTrue(it.getPage(0).annotations.isEmpty())
        }
    }

    @Test
    fun pdfAHasIdentificationAndProfile() {
        val src = sample(1)
        val out = tmp.newFile("a.pdf")
        tools.toPdfA(src, out, "Archive")
        PDDocument.load(out).use { doc ->
            val xmp = doc.documentCatalog.metadata.toByteArray().decodeToString()
            assertTrue(xmp.contains("<pdfaid:part>2</pdfaid:part>"))
            assertEquals(1, doc.documentCatalog.outputIntents.size)
        }
        val icc = SrgbIcc.bytes()
        assertEquals(icc.size, java.nio.ByteBuffer.wrap(icc).int)
        assertEquals("acsp", String(icc, 36, 4))
    }

    @Test
    fun formFieldsAreCreated() {
        val src = sample(1)
        val out = tmp.newFile("form.pdf")
        PDDocument.load(src).use { doc ->
            PdfFormBuilder().add(doc, listOf(
                FieldEdit(1, 0, Box(50f, 50f, 250f, 70f), FieldKind.TEXT, "Name"),
                FieldEdit(2, 0, Box(50f, 80f, 62f, 92f), FieldKind.CHECKBOX, "Agree"),
                FieldEdit(3, 0, Box(50f, 100f, 62f, 112f), FieldKind.RADIO, "Yes", listOf("yes"), "Answer"),
                FieldEdit(4, 0, Box(70f, 100f, 82f, 112f), FieldKind.RADIO, "No", listOf("no"), "Answer"),
                FieldEdit(5, 0, Box(50f, 120f, 200f, 140f), FieldKind.DROPDOWN, "City", listOf("Moscow", "Belgrade")),
            ))
            doc.save(out)
        }
        val fields = PdfForms(context).read(out)
        assertEquals(setOf("Name", "Agree", "Answer", "City"), fields.map { it.name }.toSet())
        val radio = fields.filterIsInstance<app.pdfdoc.domain.form.RadioFormField>().single()
        assertEquals(setOf("yes", "no"), radio.options.toSet())
    }

    @Test
    fun commentsReplyAndDelete() {
        val src = tmp.newFile("c.pdf")
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            page.annotations.add(PDAnnotationText().apply { rectangle = PDRectangle(10f, 10f, 20f, 20f); contents = "Check this" })
            doc.save(src)
        }
        val comments = PdfComments()
        val first = comments.read(src).single()
        val replied = tmp.newFile("r.pdf")
        comments.reply(src, replied, first, "Done", "Me")
        val again = comments.read(replied).single()
        assertEquals("Done", again.replies.single().text)
        val deleted = tmp.newFile("d.pdf")
        comments.delete(replied, deleted, again)
        assertTrue(comments.read(deleted).isEmpty())
        PDDocument.load(deleted).use { assertTrue(it.getPage(0).annotations.isEmpty()) }
    }
}
