package app.pdfdoc.data.pdf

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font

/**
 * The standard 14 PDF fonts have no Cyrillic, so added text and form values use a bundled
 * Roboto (Apache 2.0) embedded into the document.
 */
object PdfFonts {
    const val ASSET = "fonts/Roboto-Regular.ttf"

    fun load(doc: PDDocument, context: Context, embedSubset: Boolean = true): PDType0Font =
        context.assets.open(ASSET).use { PDType0Font.load(doc, it, embedSubset) }
}
