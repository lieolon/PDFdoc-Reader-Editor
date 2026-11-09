package app.pdfdoc.data.convert

import androidx.test.core.app.ApplicationProvider
import app.pdfdoc.data.pdf.PdfFormBuilder
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
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
class DocumentTemplatesTest {

    @get:Rule val tmp = TemporaryFolder()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
    }

    @Test
    fun everyTemplateIsOneFillablePage() {
        val templates = DocumentTemplates(context, PdfFormBuilder())
        DocumentTemplate.entries.forEach { t ->
            val out = tmp.newFile("${t.name}.pdf")
            templates.create(t, out)
            PDDocument.load(out).use { doc ->
                assertEquals(t.name, 1, doc.numberOfPages)
                val names = doc.documentCatalog.acroForm.fieldTree.map { it.fullyQualifiedName }
                assertTrue("${t.name}: ${names.size} fields", names.size >= 5)
                assertEquals("${t.name}: unique names", names.size, names.toSet().size)
            }
        }
    }
}
