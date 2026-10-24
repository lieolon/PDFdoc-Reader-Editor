package app.pdfdoc.data.pdf

import app.pdfdoc.domain.edit.Box
import app.pdfdoc.domain.edit.Pt
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.util.Matrix

/**
 * Maps page display space (points, origin top-left, page rotation applied — what the user sees)
 * to PDF user space (origin bottom-left of the unrotated crop box).
 *
 * The mapping is affine: x' = a·x + c·y + e, y' = b·x + d·y + f.
 */
class PageGeometry(
    private val left: Float,
    private val bottom: Float,
    private val width: Float,
    private val height: Float,
    rotation: Int,
) {
    private val rotation = rotation.mod(360)

    val a: Float
    val b: Float
    val c: Float
    val d: Float
    val e: Float
    val f: Float

    init {
        when (this.rotation) {
            90 -> { a = 0f; b = 1f; c = 1f; d = 0f; e = left; f = bottom }
            180 -> { a = -1f; b = 0f; c = 0f; d = 1f; e = left + width; f = bottom }
            270 -> { a = 0f; b = -1f; c = -1f; d = 0f; e = left + width; f = bottom + height }
            else -> { a = 1f; b = 0f; c = 0f; d = -1f; e = left; f = bottom + height }
        }
    }

    /** Page size as displayed. */
    val displayWidth: Float get() = if (rotation == 90 || rotation == 270) height else width
    val displayHeight: Float get() = if (rotation == 90 || rotation == 270) width else height

    fun toPdf(p: Pt): Pt = Pt(a * p.x + c * p.y + e, b * p.x + d * p.y + f)

    /** Inverse mapping; the matrix is a signed permutation, so its inverse is its transpose. */
    fun toDisplay(p: Pt): Pt {
        val x = p.x - e
        val y = p.y - f
        return Pt(a * x + b * y, c * x + d * y)
    }

    /** Bounding box of a display-space box, in PDF space, as (llx, lly, urx, ury). */
    fun toPdfRect(box: Box): FloatArray {
        val pts = box.corners().map(::toPdf)
        return floatArrayOf(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
    }

    fun toPdfRectangle(box: Box): PDRectangle {
        val r = toPdfRect(box)
        return PDRectangle(r[0], r[1], r[2] - r[0], r[3] - r[1])
    }

    /** Matrix to prepend to a content stream so it can draw in display space. */
    fun matrix(): Matrix = Matrix(a, b, c, d, e, f)

    companion object {
        fun of(page: PDPage): PageGeometry {
            val crop = page.cropBox
            return PageGeometry(crop.lowerLeftX, crop.lowerLeftY, crop.width, crop.height, page.rotation)
        }
    }
}
