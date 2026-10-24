package app.pdfdoc.data.pdf

import androidx.test.core.app.ApplicationProvider
import app.pdfdoc.domain.edit.Pt
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextExtractorTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
    }

    @Test
    fun extractsCyrillicWithPositionsInDisplaySpace() {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(600f, 800f))
            doc.addPage(page)
            val font = PdfFonts.load(doc, context)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(font, 20f)
                cs.newLineAtOffset(100f, 700f) // baseline 100pt from the top
                cs.showText("Привет мир")
                cs.endText()
            }
            // The subset font's ToUnicode map is written on save, as in real documents.
            val bytes = java.io.ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
            val text = PDDocument.load(bytes).use { TextExtractor.extract(it, 0) }
            val hits = text.find("мир")
            assertEquals(1, hits.size)
            val box = hits[0][0]
            assertTrue("box=$box", box.left > 150f && box.top in 80f..100f && box.bottom in 100f..110f)
            assertEquals("Привет мир", text.selectedText(Pt(100f, 95f), Pt(300f, 95f)))
        }
    }
}
