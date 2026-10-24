package app.pdfdoc.data.pdf

import androidx.test.core.app.ApplicationProvider
import app.pdfdoc.data.ocr.OcrWord
import app.pdfdoc.domain.edit.Box
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextLayerWriterTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
    }

    private fun searchable(rotation: Int): PDDocument {
        val doc = PDDocument()
        val page = PDPage(PDRectangle(600f, 800f)).apply { this.rotation = rotation }
        doc.addPage(page)
        val writer = TextLayerWriter(context)
        // Image is 2x the displayed page size.
        val geometry = PageGeometry.of(page)
        val imgW = (geometry.displayWidth * 2).toInt()
        val imgH = (geometry.displayHeight * 2).toInt()
        val words = listOf(
            OcrWord("Счёт", Box(200f, 400f, 360f, 440f), 90f),
            OcrWord("№42", Box(380f, 400f, 480f, 440f), 90f),
        )
        writer.write(doc, page, words, imgW, imgH, writer.font(doc))
        val bytes = ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        doc.close()
        return PDDocument.load(bytes)
    }

    @Test
    fun invisibleTextIsSearchableAtWordPosition() {
        searchable(0).use { doc ->
            val text = TextExtractor.extract(doc, 0)
            val hit = text.find("счет").single().single()
            // Word box in page points is (100, 200)-(180, 220).
            assertEquals(100f, hit.left, 3f)
            assertTrue("hit=$hit", hit.top in 190f..212f && hit.bottom in 212f..226f)
            assertEquals(1, text.find("№42").size)
        }
    }

    @Test
    fun rotatedPageKeepsWordsWhereTheyAreDisplayed() {
        searchable(90).use { doc ->
            val hit = TextExtractor.extract(doc, 0).find("счет").single().single()
            assertEquals(100f, hit.left, 3f)
            assertTrue("hit=$hit", hit.top in 190f..212f)
        }
    }
}
