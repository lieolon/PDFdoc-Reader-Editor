package app.pdfdoc.data.pdf

import android.content.Context
import app.pdfdoc.domain.form.CheckFormField
import app.pdfdoc.domain.form.ChoiceFormField
import app.pdfdoc.domain.form.ChoiceOption
import app.pdfdoc.domain.form.FormField
import app.pdfdoc.domain.form.RadioFormField
import app.pdfdoc.domain.form.TextFormField
import app.pdfdoc.domain.form.UnsupportedFormField
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDVariableText
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/** Reads and fills AcroForm fields. */
class PdfForms @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun hasFields(file: File): Boolean = runCatching {
        load(file).use { doc -> doc.documentCatalog.acroForm?.fields?.isNotEmpty() == true }
    }.getOrDefault(false)

    fun read(file: File): List<FormField> = load(file).use { doc ->
        val form = doc.documentCatalog.acroForm ?: return emptyList()
        val pages = doc.pages.toList()
        form.fieldTree.filterIsInstance<PDTerminalField>().map { field ->
            val page = field.widgets.firstOrNull()?.page?.let(pages::indexOf)?.coerceAtLeast(0) ?: 0
            val label = field.alternateFieldName?.takeIf { it.isNotBlank() } ?: field.partialName ?: field.fullyQualifiedName
            when (field) {
                is PDTextField -> TextFormField(
                    field.fullyQualifiedName, label, page, field.value.orEmpty(), field.isMultiline,
                    field.maxLen.takeIf { it > 0 },
                )
                is PDCheckBox -> CheckFormField(field.fullyQualifiedName, label, page, field.isChecked)
                is PDRadioButton -> RadioFormField(
                    field.fullyQualifiedName, label, page, field.onValues.toList().sorted(), field.value?.takeIf { it != "Off" },
                )
                is PDChoice -> {
                    val exports = field.optionsExportValues
                    val displays = field.optionsDisplayValues
                    ChoiceFormField(
                        field.fullyQualifiedName, label, page,
                        exports.indices.map { ChoiceOption(exports[it], displays.getOrElse(it) { exports[it] }) },
                        field.value.firstOrNull(),
                        (field as? PDComboBox)?.isEdit == true,
                    )
                }
                else -> UnsupportedFormField(field.fullyQualifiedName, label, page)
            }
        }.sortedBy { it.page }
    }

    /**
     * Writes [values] (keyed by field name; "true"/"false" for check boxes) from [source] into [target].
     * Text appearances use the embedded Roboto so Cyrillic values render.
     */
    fun write(source: File, target: File, values: Map<String, String>) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        load(source).use { doc ->
            val form = doc.documentCatalog.acroForm ?: return@use
            val font = PdfFonts.load(doc, context, embedSubset = false)
            val resources = form.defaultResources ?: PDResources().also { form.defaultResources = it }
            val fontName = resources.add(font).name
            values.forEach { (name, value) ->
                val field: PDField = form.getField(name) ?: return@forEach
                when (field) {
                    is PDCheckBox -> if (value.toBoolean()) field.check() else field.unCheck()
                    is PDRadioButton -> if (value.isNotEmpty()) field.setValue(value)
                    is PDVariableText -> {
                        field.defaultAppearance = "/$fontName ${fontSize(field.defaultAppearance)} Tf 0 g"
                        when (field) {
                            is PDTextField -> field.setValue(value)
                            is PDComboBox -> if (value.isNotEmpty()) setComboValue(field, value)
                            is PDChoice -> if (value.isNotEmpty()) field.setValue(value)
                        }
                    }
                }
            }
            form.needAppearances = false
            doc.save(tmp)
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /**
     * PDFBox draws the export value (e.g. "spb") into a combo box. Generate the appearance from the
     * display text instead, then restore the options and store the export value as /V.
     */
    private fun setComboValue(field: PDComboBox, export: String) {
        val exports = field.optionsExportValues
        val display = field.optionsDisplayValues.getOrNull(exports.indexOf(export))
        if (display == null || display == export) {
            field.setValue(export)
            return
        }
        val options = field.cosObject.getDictionaryObject(COSName.OPT)
        field.setOptions(field.optionsDisplayValues)
        field.setValue(display)
        field.cosObject.setItem(COSName.OPT, options)
        field.cosObject.setString(COSName.V, export)
    }

    private fun load(file: File) = PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())

    /** Keeps the field's own font size; 0 means auto-size. */
    private fun fontSize(da: String?): String =
        da?.let { Regex("""(\d+(?:\.\d+)?)\s+Tf""").find(it)?.groupValues?.get(1) } ?: "0"
}
