package app.pdfdoc.data.convert

import android.content.Context
import androidx.annotation.StringRes
import app.pdfdoc.R
import app.pdfdoc.data.pdf.PageGeometry
import app.pdfdoc.data.pdf.PdfFonts
import app.pdfdoc.data.pdf.PdfFormBuilder
import app.pdfdoc.domain.edit.Box
import app.pdfdoc.domain.edit.FieldEdit
import app.pdfdoc.domain.edit.FieldKind
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.util.Matrix
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/** Ready-made documents with fillable fields: the user fills them in the form screen. */
enum class DocumentTemplate(@StringRes val title: Int, @StringRes val description: Int) {
    INVOICE(R.string.tpl_invoice, R.string.tpl_invoice_desc),
    ACT(R.string.tpl_act, R.string.tpl_act_desc),
    RECEIPT(R.string.tpl_receipt, R.string.tpl_receipt_desc),
    APPLICATION(R.string.tpl_application, R.string.tpl_application_desc),
}

class DocumentTemplates @Inject constructor(
    @ApplicationContext private val context: Context,
    private val forms: PdfFormBuilder,
) {
    fun create(template: DocumentTemplate, target: File) {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val font = PdfFonts.load(doc, context)
            val fields = mutableListOf<FieldEdit>()
            PDPageContentStream(doc, page).use { cs ->
                cs.transform(PageGeometry.of(page).matrix())
                Writer(cs, font, fields).apply { layout(template) }
            }
            forms.add(doc, fields)
            doc.save(target)
        }
    }

    /** Draws labels and collects field boxes, top to bottom, in display space. */
    private inner class Writer(val cs: PDPageContentStream, val font: PDType0Font, val fields: MutableList<FieldEdit>) {
        val left = 50f
        val right = PDRectangle.A4.width - 50f
        var y = 60f

        fun s(@StringRes id: Int) = context.getString(id)

        fun text(value: String, size: Float, x: Float = left, bold: Boolean = false) {
            cs.setNonStrokingColor(if (bold) 0f else 0.25f)
            cs.beginText()
            cs.setFont(font, size)
            cs.setTextMatrix(Matrix(1f, 0f, 0f, -1f, x, y + size))
            cs.showText(value)
            cs.endText()
        }

        fun title(@StringRes id: Int) {
            text(s(id), 20f, bold = true)
            y += 40f
        }

        fun field(@StringRes label: Int, kind: FieldKind = FieldKind.TEXT, height: Float = 22f, from: Float = left, to: Float = right) {
            text(s(label), 9f, from)
            y += 13f
            fields += FieldEdit(fields.size.toLong(), 0, Box(from, y, to, y + height), kind, s(label))
            y += height + 10f
        }

        /** Two fields side by side on one line. */
        fun pair(@StringRes a: Int, @StringRes b: Int, kindB: FieldKind = FieldKind.TEXT, kindA: FieldKind = FieldKind.TEXT) {
            val mid = (left + right) / 2
            val top = y
            field(a, kindA, to = mid - 10f)
            y = top
            field(b, kindB, from = mid + 10f)
        }

        fun table(rows: Int) {
            val cols = listOf(R.string.tpl_item to 0.52f, R.string.tpl_qty to 0.12f, R.string.tpl_price to 0.18f, R.string.tpl_sum to 0.18f)
            val width = right - left
            var x = left
            val starts = cols.map { (_, w) -> x.also { x += width * w } }
            cols.forEachIndexed { i, (label, _) -> text(s(label), 9f, starts[i]) }
            y += 13f
            repeat(rows) { r ->
                cols.forEachIndexed { i, (label, w) ->
                    val x0 = starts[i]
                    fields += FieldEdit(fields.size.toLong(), 0, Box(x0, y, x0 + width * w - 4f, y + 20f), FieldKind.TEXT, "${s(label)} ${r + 1}")
                }
                y += 24f
            }
            y += 6f
        }

        fun layout(t: DocumentTemplate) = when (t) {
            DocumentTemplate.INVOICE -> {
                title(R.string.tpl_invoice)
                pair(R.string.tpl_number, R.string.tpl_date, FieldKind.DATE)
                field(R.string.tpl_seller, FieldKind.MULTILINE, 44f)
                field(R.string.tpl_buyer, FieldKind.MULTILINE, 44f)
                table(8)
                pair(R.string.tpl_total, R.string.tpl_due, FieldKind.DATE)
                field(R.string.tpl_payment, FieldKind.MULTILINE, 40f)
                field(R.string.tpl_signature, FieldKind.SIGNATURE, 44f, to = left + 220f)
            }
            DocumentTemplate.ACT -> {
                title(R.string.tpl_act)
                pair(R.string.tpl_number, R.string.tpl_date, FieldKind.DATE)
                field(R.string.tpl_contractor, FieldKind.MULTILINE, 40f)
                field(R.string.tpl_customer, FieldKind.MULTILINE, 40f)
                table(6)
                field(R.string.tpl_total)
                field(R.string.tpl_act_statement, FieldKind.MULTILINE, 50f)
                val top = y
                field(R.string.tpl_contractor_sign, FieldKind.SIGNATURE, 44f, to = (left + right) / 2 - 10f)
                y = top
                field(R.string.tpl_customer_sign, FieldKind.SIGNATURE, 44f, from = (left + right) / 2 + 10f)
            }
            DocumentTemplate.RECEIPT -> {
                title(R.string.tpl_receipt)
                pair(R.string.tpl_place, R.string.tpl_date, FieldKind.DATE)
                field(R.string.tpl_receiver)
                field(R.string.tpl_receiver_id, FieldKind.MULTILINE, 40f)
                field(R.string.tpl_giver)
                pair(R.string.tpl_amount, R.string.tpl_amount_words)
                field(R.string.tpl_purpose, FieldKind.MULTILINE, 60f)
                field(R.string.tpl_return_date, FieldKind.DATE, to = left + 220f)
                field(R.string.tpl_signature, FieldKind.SIGNATURE, 44f, to = left + 220f)
            }
            DocumentTemplate.APPLICATION -> {
                field(R.string.tpl_addressee, FieldKind.MULTILINE, 50f, from = (left + right) / 2)
                field(R.string.tpl_applicant, FieldKind.MULTILINE, 50f, from = (left + right) / 2)
                y += 10f
                title(R.string.tpl_application)
                field(R.string.tpl_application_text, FieldKind.MULTILINE, 300f)
                pair(R.string.tpl_date, R.string.tpl_signature, FieldKind.SIGNATURE, kindA = FieldKind.DATE)
            }
        }
    }
}
