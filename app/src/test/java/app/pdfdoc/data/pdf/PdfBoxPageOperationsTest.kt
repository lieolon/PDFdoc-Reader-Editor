package app.pdfdoc.data.pdf

import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Each test page gets a unique width (100 + original index), so page identity
 * can be checked after reordering by reading the MediaBox back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfBoxPageOperationsTest {

    @get:Rule val tmp = TemporaryFolder()

    private val ops = PdfBoxPageOperations()

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun deletePages_removesSelected() {
        val file = pdf(5)
        ops.deletePages(file, file, setOf(1, 3))
        assertEquals(listOf(0, 2, 4), ids(file))
    }

    @Test(expected = IllegalArgumentException::class)
    fun deletePages_refusesToDeleteEverything() {
        val file = pdf(2)
        ops.deletePages(file, file, setOf(0, 1))
    }

    @Test
    fun rotatePages_accumulatesAndWraps() {
        val file = pdf(3)
        ops.rotatePages(file, file, setOf(0, 2), 90)
        ops.rotatePages(file, file, setOf(0), 270)
        assertEquals(listOf(0, 0, 90), rotations(file))
    }

    @Test
    fun movePage_forwardAndBackward() {
        val file = pdf(4)
        ops.movePage(file, file, from = 0, to = 2)
        assertEquals(listOf(1, 2, 0, 3), ids(file))
        ops.movePage(file, file, from = 3, to = 0)
        assertEquals(listOf(3, 1, 2, 0), ids(file))
    }

    @Test
    fun movePage_keepsRotation() {
        val file = pdf(3)
        ops.rotatePages(file, file, setOf(0), 180)
        ops.movePage(file, file, from = 0, to = 2)
        assertEquals(listOf(0, 0, 180), rotations(file))
    }

    @Test
    fun extractPages_writesOnlySelectedInOrder() {
        val source = pdf(6)
        val target = tmp.newFile("out.pdf")
        ops.extractPages(source, target, listOf(4, 1, 2))
        assertEquals(listOf(4, 1, 2), ids(target))
        assertEquals(6, ops.pageCount(source))
    }

    @Test
    fun merge_concatenatesInOrder() {
        val a = pdf(2, offset = 0)
        val b = pdf(3, offset = 10)
        val target = tmp.newFile("merged.pdf")
        ops.merge(listOf(b, a), target)
        assertEquals(listOf(10, 11, 12, 0, 1), ids(target))
    }

    @Test
    fun duplicatePage_insertsCopyAfterOriginal() {
        val file = pdf(3)
        ops.duplicatePage(file, file, 1)
        assertEquals(listOf(0, 1, 1, 2), ids(file))
    }

    @Test
    fun insertBlankPage_atPosition() {
        val file = pdf(2)
        ops.insertBlankPage(file, file, 1)
        assertEquals(listOf(0, 0, 1), ids(file))
        assertEquals(3, ops.pageCount(file))
    }

    @Test
    fun insertDocument_atPosition() {
        val file = pdf(3)
        ops.insertDocument(file, pdf(2, offset = 10), file, 1)
        assertEquals(listOf(0, 10, 11, 1, 2), ids(file))
    }

    @Test
    fun insertBlankPage_ruledPaperHasContent() {
        val file = pdf(2)
        ops.insertBlankPage(file, file, 1, app.pdfdoc.domain.pdf.Paper.SQUARED)
        PDDocument.load(file).use { doc ->
            assertEquals(3, doc.numberOfPages)
            assertEquals(true, doc.getPage(1).hasContents())
            assertEquals(false, doc.getPage(0).hasContents())
        }
    }

    private fun pdf(pages: Int, offset: Int = 0): File {
        val file = tmp.newFile()
        PDDocument().use { doc ->
            repeat(pages) { doc.addPage(PDPage(PDRectangle(100f + offset + it, 200f))) }
            doc.save(file)
        }
        return file
    }

    private fun ids(file: File): List<Int> =
        PDDocument.load(file).use { doc -> doc.pages.map { it.mediaBox.width.toInt() - 100 } }

    private fun rotations(file: File): List<Int> =
        PDDocument.load(file).use { doc -> doc.pages.map { it.rotation } }
}
