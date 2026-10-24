package app.pdfdoc.data.pdf

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import app.pdfdoc.domain.edit.Box
import app.pdfdoc.domain.edit.Edit
import app.pdfdoc.domain.edit.ImageEdit
import app.pdfdoc.domain.edit.InkEdit
import app.pdfdoc.domain.edit.MarkupEdit
import app.pdfdoc.domain.edit.MarkupKind
import app.pdfdoc.domain.edit.NoteEdit
import app.pdfdoc.domain.edit.Pt
import app.pdfdoc.domain.edit.ShapeEdit
import app.pdfdoc.domain.edit.ShapeKind
import app.pdfdoc.domain.edit.StampEdit
import app.pdfdoc.domain.edit.TextEdit
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class AnnotationIOTest {

    @get:Rule val tmp = TemporaryFolder()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val io by lazy { AnnotationIO(context) }

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
    }

    private val red = 0xFFC62828.toInt()

    private fun sampleEdits(): List<Edit> = listOf(
        InkEdit(1, 0, listOf(listOf(Pt(10f, 10f), Pt(50f, 60f))), red, 3f, 0.5f),
        ShapeEdit(2, 0, ShapeKind.ARROW, Pt(100f, 100f), Pt(200f, 150f), red, 2f),
        ShapeEdit(3, 1, ShapeKind.OVAL, Pt(20f, 20f), Pt(80f, 60f), red, 2f),
        MarkupEdit(4, 1, MarkupKind.HIGHLIGHT, listOf(Box(10f, 100f, 200f, 114f)), 0xFFFFEB3B.toInt()),
        TextEdit(5, 0, Box(50f, 300f, 250f, 330f), "Привет, мир!\nВторая строка 😀", 14f, red),
        NoteEdit(6, 1, Pt(300f, 40f), "Заметка", 0xFFFFEB3B.toInt()),
        StampEdit(7, 0, Box(300f, 500f, 420f, 530f), "ОДОБРЕНО", 0xFF2E7D32.toInt()),
        app.pdfdoc.domain.edit.CoverEdit(8, 1, Box(50f, 600f, 250f, 620f)),
    )

    private fun pdf(pages: Int, rotation: Int = 0): File {
        val file = tmp.newFile()
        PDDocument().use { doc ->
            repeat(pages) { doc.addPage(PDPage(PDRectangle.A4).apply { this.rotation = rotation }) }
            doc.save(file)
        }
        return file
    }

    private fun writeAndReload(file: File, edits: List<Edit>): List<Edit> {
        PDDocument.load(file).use { doc ->
            io.write(doc, edits)
            doc.save(file)
        }
        return PDDocument.load(file).use { io.read(it, tmp.newFolder()) }
    }

    @Test
    fun roundTripKeepsEveryEditType() {
        val edits = sampleEdits()
        val read = writeAndReload(pdf(2), edits)
        assertEquals(edits.sortedBy { it.id }, read.sortedBy { it.id })
    }

    @Test
    fun writesStandardSubtypesWithAppearances() {
        val file = pdf(2)
        writeAndReload(file, sampleEdits())
        PDDocument.load(file).use { doc ->
            val subtypes = doc.pages.flatMap { it.annotations }.map { it.subtype }.toSet()
            assertEquals(setOf("Ink", "Line", "Circle", "Highlight", "FreeText", "Text", "Stamp", "Square"), subtypes)
            doc.pages.flatMap { it.annotations }.forEach { assertNotNull(it.normalAppearanceStream) }
        }
    }

    @Test
    fun rewriteReplacesOwnButKeepsForeignAnnotations() {
        val file = pdf(1)
        PDDocument.load(file).use { doc ->
            doc.getPage(0).annotations = listOf(PDAnnotationText().apply { contents = "чужая" })
            doc.save(file)
        }
        writeAndReload(file, sampleEdits().filter { it.page == 0 })
        val read = writeAndReload(file, listOf(NoteEdit(9, 0, Pt(1f, 1f), "одна", red)))
        assertEquals(1, read.size)
        PDDocument.load(file).use { doc ->
            val annots = doc.getPage(0).annotations
            assertEquals(2, annots.size)
            assertTrue(annots.any { it.contents == "чужая" })
        }
    }

    @Test
    fun imageEditRestoresPixelsFromPdf() {
        val png = tmp.newFile("sig.png")
        Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            .also { b -> png.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val read = writeAndReload(pdf(1), listOf(ImageEdit(1, 0, Box(10f, 10f, 90f, 50f), 90, png.absolutePath)))
        val image = read.single() as ImageEdit
        assertEquals(90, image.rotation)
        assertTrue(File(image.imagePath).length() > 0)
    }

    @Test
    fun rotatedPageAfterEditReprojectsCoordinates() {
        val file = pdf(1)
        val note = NoteEdit(1, 0, Pt(100f, 50f), "x", red)
        writeAndReload(file, listOf(note))
        PDDocument.load(file).use { doc ->
            doc.getPage(0).rotation = 90
            doc.save(file)
        }
        val moved = PDDocument.load(file).use { io.read(it, tmp.newFolder()) }.single() as NoteEdit
        // Same PDF location, seen through the rotated page.
        PDDocument.load(file).use { doc ->
            val crop = doc.getPage(0).cropBox
            val before = PageGeometry(crop.lowerLeftX, crop.lowerLeftY, crop.width, crop.height, 0).toPdf(note.at)
            val after = PageGeometry.of(doc.getPage(0)).toPdf(moved.at)
            assertEquals(before, after)
        }
    }

    @Test
    fun renderCopyFlattensAnnotationsAndCanExcludeOwn() {
        val file = pdf(1)
        writeAndReload(file, listOf(sampleEdits()[1]))
        val builder = RenderCopyBuilder()

        val flat = tmp.newFile()
        assertTrue(builder.build(file, flat, excludeOwn = false))
        PDDocument.load(flat).use { doc ->
            assertTrue(doc.getPage(0).annotations.isEmpty())
            val content = doc.getPage(0).contentStreams.asSequence().joinToString("") { it.toByteArray().decodeToString() }
            assertTrue(content.contains(" Do"))
        }

        val clean = tmp.newFile()
        assertTrue(builder.build(file, clean, excludeOwn = true))
        PDDocument.load(clean).use { doc ->
            val content = doc.getPage(0).contentStreams.asSequence().joinToString("") { it.toByteArray().decodeToString() }
            assertFalse(content.contains(" Do"))
        }

        assertFalse(builder.build(pdf(1), tmp.newFile(), excludeOwn = false))
    }

    private fun com.tom_roush.pdfbox.pdmodel.common.PDStream.toByteArray(): ByteArray = createInputStream().use { it.readBytes() }
}
