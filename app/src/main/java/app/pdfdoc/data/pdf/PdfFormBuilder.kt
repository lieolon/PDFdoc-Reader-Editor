package app.pdfdoc.data.pdf

import app.pdfdoc.domain.edit.FieldEdit
import app.pdfdoc.domain.edit.FieldKind
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceEntry
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import javax.inject.Inject

/** Turns [FieldEdit]s into real, fillable AcroForm fields. */
class PdfFormBuilder @Inject constructor() {

    fun add(doc: PDDocument, fields: List<FieldEdit>) {
        if (fields.isEmpty()) return
        val catalog = doc.documentCatalog
        val form = catalog.acroForm ?: PDAcroForm(doc).also { catalog.acroForm = it }
        val resources = form.defaultResources ?: PDResources().also { form.defaultResources = it }
        resources.put(HELV, PDType1Font.HELVETICA)
        if (form.defaultAppearance.isNullOrEmpty()) form.defaultAppearance = "/Helv 0 Tf 0 g"
        val used = form.fieldTree.map { it.fullyQualifiedName }.toMutableSet()
        fun unique(base: String): String {
            val clean = base.trim().replace('.', '_').ifEmpty { "field" }
            var name = clean
            var n = 2
            while (!used.add(name)) name = "$clean ${n++}"
            return name
        }

        val (radios, others) = fields.partition { it.kind == FieldKind.RADIO }
        others.forEach { f ->
            val page = doc.getPage(f.page)
            val rect = PageGeometry.of(page).toPdfRectangle(f.box)
            val field: PDField = when (f.kind) {
                FieldKind.TEXT, FieldKind.MULTILINE, FieldKind.DATE -> PDTextField(form).apply {
                    defaultAppearance = "/Helv ${if (f.kind == FieldKind.MULTILINE) 10 else 0} Tf 0 g"
                    isMultiline = f.kind == FieldKind.MULTILINE
                    if (f.kind == FieldKind.DATE) alternateFieldName = DATE_HINT
                }
                FieldKind.DROPDOWN -> PDComboBox(form).apply {
                    defaultAppearance = "/Helv 0 Tf 0 g"
                    setOptions(f.options.ifEmpty { listOf("—") })
                }
                FieldKind.CHECKBOX -> PDCheckBox(form)
                FieldKind.SIGNATURE -> PDSignatureField(form)
                FieldKind.RADIO -> error("handled below")
            }
            field.partialName = unique(f.name)
            val widget = field.widgets.first()
            setupWidget(widget, page, rect)
            if (field is PDCheckBox) widget.appearance = checkAppearance(doc, rect, ON)
            form.fields.add(field)
            page.annotations.add(widget)
            if (field is PDTextField) runCatching { field.setValue("") }
            if (field is PDComboBox) runCatching { field.setValue(f.options.firstOrNull() ?: "—") }
        }

        radios.groupBy { it.group.ifBlank { it.name } }.forEach { (group, buttons) ->
            val field = PDRadioButton(form)
            field.partialName = unique(group)
            val widgets = buttons.mapIndexed { i, b ->
                val page = doc.getPage(b.page)
                val rect = PageGeometry.of(page).toPdfRectangle(b.box)
                val export = b.options.firstOrNull()?.takeIf { it.isNotBlank() } ?: "${i + 1}"
                PDAnnotationWidget().apply {
                    setupWidget(this, page, rect)
                    appearance = checkAppearance(doc, rect, COSName.getPDFName(export), round = true)
                    cosObject.setItem(COSName.PARENT, field)
                    page.annotations.add(this)
                }
            }
            field.widgets = widgets
            field.cosObject.setItem(COSName.V, COSName.Off)
            form.fields.add(field)
        }
        form.needAppearances = false
    }

    private fun setupWidget(widget: PDAnnotationWidget, page: PDPage, rect: PDRectangle) {
        widget.rectangle = rect
        widget.page = page
        widget.isPrinted = true
        // Thin dark border so the field is visible in any reader.
        val mk = COSDictionary().apply {
            setItem(COSName.getPDFName("BC"), COSArray().apply { add(COSFloat(0.3f)); add(COSFloat(0.3f)); add(COSFloat(0.3f)) })
        }
        widget.cosObject.setItem(COSName.getPDFName("MK"), mk)
        widget.cosObject.setItem(COSName.BS, COSDictionary().apply { setFloat(COSName.W, 1f) })
    }

    /** On and Off appearances for a check box or radio button. */
    private fun checkAppearance(doc: PDDocument, rect: PDRectangle, onName: COSName, round: Boolean = false): PDAppearanceDictionary {
        fun stream(on: Boolean): PDAppearanceStream {
            val s = PDAppearanceStream(doc)
            s.bBox = PDRectangle(rect.width, rect.height)
            s.resources = PDResources()
            PDPageContentStream(doc, s).use { cs ->
                val w = rect.width
                val h = rect.height
                cs.setStrokingColor(0.3f, 0.3f, 0.3f)
                cs.setLineWidth(1f)
                if (round) circle(cs, w / 2, h / 2, minOf(w, h) / 2 - 0.5f) else cs.addRect(0.5f, 0.5f, w - 1f, h - 1f)
                cs.stroke()
                if (on) {
                    cs.setNonStrokingColor(0f, 0f, 0f)
                    if (round) {
                        circle(cs, w / 2, h / 2, minOf(w, h) / 4)
                        cs.fill()
                    } else {
                        cs.setStrokingColor(0f, 0f, 0f)
                        cs.setLineWidth(minOf(w, h) * 0.12f)
                        cs.moveTo(w * 0.2f, h * 0.5f)
                        cs.lineTo(w * 0.42f, h * 0.25f)
                        cs.lineTo(w * 0.8f, h * 0.78f)
                        cs.stroke()
                    }
                }
            }
            return s
        }
        val normal = COSDictionary().apply {
            setItem(onName, stream(true))
            setItem(COSName.Off, stream(false))
        }
        return PDAppearanceDictionary().apply { setNormalAppearance(PDAppearanceEntry(normal)) }
    }

    private fun circle(cs: PDPageContentStream, cx: Float, cy: Float, r: Float) {
        val k = r * 0.5523f
        cs.moveTo(cx - r, cy)
        cs.curveTo(cx - r, cy + k, cx - k, cy + r, cx, cy + r)
        cs.curveTo(cx + k, cy + r, cx + r, cy + k, cx + r, cy)
        cs.curveTo(cx + r, cy - k, cx + k, cy - r, cx, cy - r)
        cs.curveTo(cx - k, cy - r, cx - r, cy - k, cx - r, cy)
        cs.closePath()
    }

    private companion object {
        val HELV: COSName = COSName.getPDFName("Helv")
        val ON: COSName = COSName.getPDFName("Yes")
        const val DATE_HINT = "dd.mm.yyyy"
    }
}
