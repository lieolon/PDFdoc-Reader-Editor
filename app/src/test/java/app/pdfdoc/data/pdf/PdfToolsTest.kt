package app.pdfdoc.data.pdf

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import app.pdfdoc.domain.tools.CompressLevel
import app.pdfdoc.domain.tools.StampPosition
import app.pdfdoc.domain.tools.TextStamp
import app.pdfdoc.domain.tools.TextWatermark
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.File
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
class PdfToolsTest {

    @get:Rule val tmp = TemporaryFolder()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val tools by lazy { PdfTools(context) }
    private val security = PdfSecurity()

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
    }

    private fun pdf(pages: Int, rotation: Int = 0): File {
        val file = tmp.newFile()
        PDDocument().use { doc ->
            repeat(pages) { doc.addPage(PDPage(PDRectangle(600f, 800f)).apply { this.rotation = rotation }) }
            doc.save(file)
        }
        return file
    }

    @Test
    fun pageNumbersAreSearchableAtTheBottom() {
        val out = tmp.newFile()
        tools.addTextStamps(pdf(3), out, listOf(TextStamp("Страница {n} из {total}", StampPosition.BOTTOM_CENTER)), "x.pdf")
        PDDocument.load(out).use { doc ->
            val hit = TextExtractor.extract(doc, 1).find("страница 2 из 3").single().single()
            assertTrue("hit=$hit", hit.bottom > 760f && hit.center.x in 280f..320f)
        }
    }

    @Test
    fun headerOnRotatedPageStaysAtDisplayedTop() {
        val out = tmp.newFile()
        tools.addTextStamps(pdf(1, rotation = 90), out, listOf(TextStamp("{file}", StampPosition.TOP_LEFT)), "Отчёт.pdf")
        PDDocument.load(out).use { doc ->
            val hit = TextExtractor.extract(doc, 0).find("отчет.pdf").single().single()
            assertTrue("hit=$hit", hit.top < 50f && hit.left in 20f..40f)
        }
    }

    @Test
    fun watermarkTextIsAddedToEveryPage() {
        val out = tmp.newFile()
        tools.addWatermark(pdf(2), out, TextWatermark("ЧЕРНОВИК", angle = 0f)) // rotated text is not extractable as a word
        PDDocument.load(out).use { doc ->
            (0..1).forEach { assertEquals(1, TextExtractor.extract(doc, it).find("черновик").size) }
        }
    }

    @Test
    fun compressShrinksLargeImages() {
        val source = tmp.newFile()
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            // A noisy 3000x3000 image compresses badly losslessly.
            val bitmap = Bitmap.createBitmap(3000, 3000, Bitmap.Config.ARGB_8888)
            val rnd = java.util.Random(1)
            val pixels = IntArray(3000 * 3000) { Color.rgb(rnd.nextInt(256), rnd.nextInt(256), 128) }
            bitmap.setPixels(pixels, 0, 3000, 0, 0, 3000, 3000)
            bitmap.setHasAlpha(false)
            val image = LosslessFactory.createFromImage(doc, bitmap)
            PDPageContentStream(doc, page).use { it.drawImage(image, 0f, 0f, 595f, 842f) }
            doc.save(source)
        }
        val result = tools.compress(source, tmp.newFile(), CompressLevel.STRONG)
        assertEquals(1, result.imagesChanged)
        assertTrue("before=${result.before} after=${result.after}", result.after < result.before / 4)
    }

    @Test
    fun extractTextHasPageHeaders() {
        val out = tmp.newFile()
        tools.addTextStamps(pdf(2), out, listOf(TextStamp("Привет {n}", StampPosition.TOP_CENTER)), "")
        val text = tools.extractText(out) { "--- $it ---" }
        assertTrue(text, text.contains("--- 1 ---\n\nПривет 1") && text.contains("--- 2 ---\n\nПривет 2"))
    }

    @Test
    fun passwordProtectUnlockAndRemove() {
        val locked = tmp.newFile()
        security.protect(pdf(1), locked, "секрет", "владелец", PdfPermissions(print = false))
        assertEquals(LockState.NEEDS_PASSWORD, security.state(locked, null))
        assertEquals(LockState.NEEDS_PASSWORD, security.state(locked, "wrong"))
        assertEquals(LockState.UNLOCKED, security.state(locked, "секрет"))
        PDDocument.load(locked, "секрет").use { doc ->
            assertEquals(256, doc.encryption.length)
            assertTrue(!doc.currentAccessPermission.canPrint())
        }
        val plain = tmp.newFile()
        security.decrypt(locked, plain, "секрет")
        assertEquals(LockState.OPEN, security.state(plain, null))
    }

    @Test
    fun ownerOnlyProtectionOpensWithoutPassword() {
        val locked = tmp.newFile()
        security.protect(pdf(1), locked, "", "owner", PdfPermissions(copy = false))
        assertEquals(LockState.UNLOCKED, security.state(locked, null))
    }
}
