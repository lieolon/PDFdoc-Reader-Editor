package app.pdfdoc.data.pdf

import android.graphics.Bitmap
import android.graphics.PointF
import app.pdfdoc.domain.edit.Box
import app.pdfdoc.domain.edit.Pt
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import javax.inject.Inject
import kotlin.math.abs

/** An image drawn by the page content. [doIndex] counts the page-level "Do" operators. */
data class ImagePlacement(val doIndex: Int, val box: Box, val rotation: Int, val removable: Boolean)

/** Finds images on a page and takes them out of the page content so they can be edited as objects. */
class PageImages @Inject constructor() {

    fun find(file: File, pageIndex: Int): List<ImagePlacement> =
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            val page = doc.getPage(pageIndex)
            Locator(page).run { list() }
        }

    /**
     * Removes the [placement] from page [pageIndex] of [source], writes the result to [target]
     * and returns the image pixels.
     */
    fun detach(source: File, target: File, pageIndex: Int, placement: ImagePlacement): Bitmap? =
        PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            val page = doc.getPage(pageIndex)
            val locator = Locator(page, capture = placement.doIndex)
            locator.list()
            val bitmap = locator.captured ?: return null
            val parser = PDFStreamParser(page)
            parser.parse()
            val tokens = parser.tokens.toMutableList()
            var count = 0
            var removeAt = -1
            tokens.forEachIndexed { i, t ->
                if (t is Operator && t.name == "Do") {
                    if (count == placement.doIndex) removeAt = i
                    count++
                }
            }
            if (removeAt < 1 || tokens[removeAt - 1] !is COSName) return null
            tokens.removeAt(removeAt)
            tokens.removeAt(removeAt - 1)
            val stream = PDStream(doc)
            stream.createOutputStream(COSName.FLATE_DECODE).use { out -> ContentStreamWriter(out).writeTokens(tokens) }
            page.setContents(stream)
            doc.save(target)
            bitmap
        }

    private class Locator(private val page: PDPage, private val capture: Int = -1) : PDFGraphicsStreamEngine(page) {
        private val geometry = PageGeometry.of(page)
        private val result = mutableListOf<ImagePlacement>()
        private var depth = 0
        private var doCount = -1
        private var inline = false
        var captured: Bitmap? = null

        fun list(): List<ImagePlacement> {
            processPage(page)
            return result
        }

        override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
            if (depth == 0) {
                if (operator.name == "Do") doCount++
                if (operator.name == "BI") inline = true
            }
            super.processOperator(operator, operands)
            if (operator.name == "BI") inline = false
        }

        override fun showForm(form: PDFormXObject) {
            depth++
            try {
                super.showForm(form)
            } finally {
                depth--
            }
        }

        override fun drawImage(pdImage: PDImage) {
            val ctm: Matrix = graphicsState.currentTransformationMatrix
            fun map(x: Float, y: Float): Pt {
                val p = ctm.transformPoint(x, y)
                return geometry.toDisplay(Pt(p.x, p.y))
            }
            val origin = map(0f, 0f)
            val xAxis = map(1f, 0f) - origin
            val box = Box.bounding(listOf(origin, map(1f, 0f), map(1f, 1f), map(0f, 1f))) ?: return
            if (box.width < 4f || box.height < 4f) return
            val rotation = when {
                abs(xAxis.x) >= abs(xAxis.y) -> if (xAxis.x >= 0) 0 else 180
                else -> if (xAxis.y >= 0) 90 else 270
            }
            val removable = depth == 0 && !inline
            result += ImagePlacement(if (removable) doCount else -1, box, rotation, removable)
            if (removable && doCount == capture && captured == null) {
                captured = runCatching { pdImage.image }.getOrNull()
            }
        }

        override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) = Unit
        override fun clip(windingRule: android.graphics.Path.FillType) = Unit
        override fun moveTo(x: Float, y: Float) = Unit
        override fun lineTo(x: Float, y: Float) = Unit
        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = Unit
        override fun getCurrentPoint(): PointF = PointF(0f, 0f)
        override fun closePath() = Unit
        override fun endPath() = Unit
        override fun strokePath() = Unit
        override fun fillPath(windingRule: android.graphics.Path.FillType) = Unit
        override fun fillAndStrokePath(windingRule: android.graphics.Path.FillType) = Unit
        override fun shadingFill(shadingName: COSName) = Unit
    }
}
