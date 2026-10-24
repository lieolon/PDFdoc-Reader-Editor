package app.pdfdoc.data.pdf

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import javax.inject.Inject

/**
 * Android's PdfRenderer ignores annotations and form fields. This builds a display-only copy
 * where every visible annotation appearance is drawn into the page content.
 */
class RenderCopyBuilder @Inject constructor() {

    /**
     * Writes the flattened copy of [source] to [target].
     * Returns false (and writes nothing) when there is nothing to flatten or the file can't be parsed.
     * With [excludeOwn], annotations created by this app are dropped instead of drawn.
     */
    fun build(source: File, target: File, excludeOwn: Boolean): Boolean = runCatching {
        PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            val hasFields = doc.documentCatalog.acroForm?.fields?.isNotEmpty() == true
            val hasAnnotations = doc.pages.any { page -> page.annotations.any(::isVisual) }
            if (!hasFields && !hasAnnotations) return@use false

            doc.documentCatalog.acroForm?.let { form ->
                if (form.needAppearances) runCatching { form.refreshAppearances() }
            }
            doc.pages.forEach { page ->
                val annotations = page.annotations.filter(::isVisual)
                    .filterNot { excludeOwn && AnnotationIO.isOwn(it) }
                if (annotations.isNotEmpty()) {
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        annotations.forEach { flatten(cs, it) }
                    }
                }
                page.annotations = ArrayList(page.annotations.filterNot(::isVisual))
            }
            doc.documentCatalog.acroForm = null
            doc.save(target)
            true
        }
    }.getOrDefault(false)

    private fun isVisual(a: PDAnnotation) = a !is PDAnnotationLink && a !is PDAnnotationPopup

    private fun flatten(cs: PDPageContentStream, a: PDAnnotation) {
        if (a.isHidden || a.isNoView || a.isInvisible) return
        val appearance = a.normalAppearanceStream ?: return
        val rect = a.rectangle ?: return
        val bbox = appearance.bBox ?: return
        val m = appearance.matrix
        // Bounding box of the form's BBox after its own Matrix (PDF 32000 §12.5.5).
        val xs = FloatArray(4)
        val ys = FloatArray(4)
        listOf(
            bbox.lowerLeftX to bbox.lowerLeftY, bbox.upperRightX to bbox.lowerLeftY,
            bbox.upperRightX to bbox.upperRightY, bbox.lowerLeftX to bbox.upperRightY,
        ).forEachIndexed { i, (x, y) ->
            xs[i] = m.scaleX * x + m.shearX * y + m.translateX
            ys[i] = m.shearY * x + m.scaleY * y + m.translateY
        }
        val tw = xs.max() - xs.min()
        val th = ys.max() - ys.min()
        if (tw <= 0f || th <= 0f) return
        val sx = rect.width / tw
        val sy = rect.height / th
        cs.saveGraphicsState()
        cs.transform(Matrix(sx, 0f, 0f, sy, rect.lowerLeftX - xs.min() * sx, rect.lowerLeftY - ys.min() * sy))
        cs.drawForm(appearance)
        cs.restoreGraphicsState()
    }
}
