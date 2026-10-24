package app.pdfdoc.data.pdf

import androidx.test.core.app.ApplicationProvider
import app.pdfdoc.domain.form.CheckFormField
import app.pdfdoc.domain.form.ChoiceFormField
import app.pdfdoc.domain.form.TextFormField
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class PdfFormsTest {

    @get:Rule val tmp = TemporaryFolder()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val forms by lazy { PdfForms(context) }

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
    }

    /** A Helvetica-based form like most real-world forms: no Cyrillic in its own font. */
    private fun formPdf(): File {
        val file = tmp.newFile()
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val form = PDAcroForm(doc)
            doc.documentCatalog.acroForm = form
            form.defaultResources = PDResources().apply { put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
            form.defaultAppearance = "/Helv 0 Tf 0 g"

            val name = PDTextField(form).apply { partialName = "name"; alternateFieldName = "ФИО"; defaultAppearance = "/Helv 12 Tf 0 g" }
            val agree = PDCheckBox(form).apply { partialName = "agree" }
            val city = PDComboBox(form).apply { partialName = "city"; setOptions(listOf("msk", "spb"), listOf("Москва", "Санкт-Петербург")); defaultAppearance = "/Helv 10 Tf 0 g" }
            listOf(name, agree, city).forEachIndexed { i, field ->
                val widget = field.widgets.first()
                widget.rectangle = PDRectangle(50f, 700f - i * 40f, 200f, 20f)
                widget.page = page
                page.annotations.add(widget)
                form.fields.add(field)
            }
            // Check boxes need an "on" appearance state to be checkable.
            val ap = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary()
            val on = com.tom_roush.pdfbox.cos.COSDictionary()
            on.setItem(COSName.getPDFName("Yes"), com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream(doc).cosObject)
            on.setItem(COSName.Off, com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream(doc).cosObject)
            ap.cosObject.setItem(COSName.N, on)
            agree.widgets.first().appearance = ap
            doc.save(file)
        }
        return file
    }

    @Test
    fun readsFieldsWithLabelsAndOptions() {
        val fields = forms.read(formPdf()).associateBy { it.name }
        assertEquals("ФИО", fields.getValue("name").label)
        assertFalse((fields.getValue("agree") as CheckFormField).checked)
        assertEquals(listOf("Москва", "Санкт-Петербург"), (fields.getValue("city") as ChoiceFormField).options.map { it.display })
        assertTrue(forms.hasFields(formPdf()))
    }

    @Test
    fun writesCyrillicValuesThatReadBack() {
        val source = formPdf()
        val target = tmp.newFile()
        forms.write(source, target, mapOf("name" to "Иванов Иван", "agree" to "true", "city" to "spb"))
        val fields = forms.read(target).associateBy { it.name }
        assertEquals("Иванов Иван", (fields.getValue("name") as TextFormField).value)
        assertTrue((fields.getValue("agree") as CheckFormField).checked)
        assertEquals("spb", (fields.getValue("city") as ChoiceFormField).value)
        // The combo box shows the display text, not the export value.
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(target).use { doc ->
            val ap = doc.documentCatalog.acroForm.getField("city").widgets.first().normalAppearanceStream
            val content = ap.contentStream.createInputStream().use { it.readBytes() }
            val font = ap.resources.fontNames.map { ap.resources.getFont(it) }.first()
            assertTrue(content.isNotEmpty() && font != null)
            assertEquals("spb", doc.documentCatalog.acroForm.getField("city").valueAsString.trim('[', ']'))
        }
        // The filled form is visible to PdfRenderer through the flattened copy.
        assertTrue(RenderCopyBuilder().build(target, tmp.newFile(), excludeOwn = false))
    }
}
