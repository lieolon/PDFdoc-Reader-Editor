package app.pdfdoc.data.pdf

import android.graphics.BitmapFactory
import app.pdfdoc.domain.edit.Box
import app.pdfdoc.domain.edit.CoverEdit
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
import app.pdfdoc.domain.edit.TextAlign
import app.pdfdoc.domain.edit.TextEdit
import app.pdfdoc.domain.edit.TextLayout
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Kappa for approximating a quarter ellipse with a cubic Bézier curve. */
private const val KAPPA = 0.5523f

/** Arrow head geometry shared with the on-screen renderer. */
object ArrowHead {
    const val ANGLE = 0.45f

    fun length(strokeWidth: Float) = max(10f, strokeWidth * 4f)

    /** The two end points of the arrow head lines at [end] of a line from [start]. */
    fun points(start: Pt, end: Pt, strokeWidth: Float): Pair<Pt, Pt> {
        val angle = atan2(end.y - start.y, end.x - start.x)
        val len = length(strokeWidth)
        fun at(a: Float) = Pt(end.x - len * cos(a), end.y - len * sin(a))
        return at(angle - ANGLE) to at(angle + ANGLE)
    }
}

/** Shared geometry of measurement drawings (on screen and in the PDF). */
object MeasureTicks {
    const val LABEL_SIZE = 9f
    private const val TICK = 5f

    /** A short line across the measured segment at [end]. */
    fun points(end: Pt, next: Pt): Pair<Pt, Pt> {
        val dx = next.x - end.x
        val dy = next.y - end.y
        val len = kotlin.math.hypot(dx, dy).takeIf { it > 0f } ?: 1f
        val nx = -dy / len * TICK
        val ny = dx / len * TICK
        return Pt(end.x + nx, end.y + ny) to Pt(end.x - nx, end.y - ny)
    }

    /** Baseline center of the value label. */
    fun labelAnchor(e: app.pdfdoc.domain.edit.MeasureEdit): Pt = when (e.kind) {
        app.pdfdoc.domain.edit.MeasureKind.AREA -> {
            val c = Box.bounding(e.points)!!.center
            Pt(c.x, c.y + LABEL_SIZE / 2)
        }
        else -> {
            val mid = e.points.size / 2
            val a = e.points[(mid - 1).coerceAtLeast(0)]
            val b = e.points[mid.coerceAtMost(e.points.lastIndex)]
            Pt((a.x + b.x) / 2, (a.y + b.y) / 2 - 3f)
        }
    }
}

/**
 * Draws an [Edit] into a content stream whose coordinates were already switched to page
 * display space (y axis pointing down). The on-screen overlay mirrors this drawing.
 */
class AppearancePainter(
    private val doc: PDDocument,
    private val font: () -> PDType0Font,
) {
    fun paint(cs: PDPageContentStream, edit: Edit) {
        when (edit) {
            is InkEdit -> ink(cs, edit)
            is ShapeEdit -> shape(cs, edit)
            is MarkupEdit -> markup(cs, edit)
            is TextEdit -> text(cs, edit)
            is NoteEdit -> note(cs, edit)
            is ImageEdit -> image(cs, edit)
            is StampEdit -> stamp(cs, edit)
            is CoverEdit -> {
                fillColor(cs, edit.color)
                cs.addRect(edit.box.left, edit.box.top, edit.box.width, edit.box.height)
                cs.fill()
            }
            is app.pdfdoc.domain.edit.MeasureEdit -> measure(cs, edit)
            // Links are invisible; redactions and fields are applied separately on save.
            is app.pdfdoc.domain.edit.LinkEdit, is app.pdfdoc.domain.edit.RedactEdit, is app.pdfdoc.domain.edit.FieldEdit -> Unit
        }
    }

    private fun measure(cs: PDPageContentStream, e: app.pdfdoc.domain.edit.MeasureEdit) {
        val pts = e.points
        if (pts.size < 2) return
        strokeStyle(cs, e.color, 1.2f)
        val closed = e.kind == app.pdfdoc.domain.edit.MeasureKind.AREA
        if (closed) {
            cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = 0.15f })
            fillColor(cs, e.color)
            cs.moveTo(pts[0].x, pts[0].y)
            pts.drop(1).forEach { cs.lineTo(it.x, it.y) }
            cs.closePath()
            cs.fillAndStroke()
            cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = 1f })
        } else {
            cs.moveTo(pts[0].x, pts[0].y)
            pts.drop(1).forEach { cs.lineTo(it.x, it.y) }
            cs.stroke()
            // End ticks.
            listOf(pts[0] to pts[1], pts.last() to pts[pts.size - 2]).forEach { (end, next) ->
                val (a, b) = MeasureTicks.points(end, next)
                cs.moveTo(a.x, a.y)
                cs.lineTo(b.x, b.y)
            }
            cs.stroke()
        }
        val f = font()
        val label = safeText(f, e.label)
        val at = MeasureTicks.labelAnchor(e)
        val size = MeasureTicks.LABEL_SIZE
        val w = f.getStringWidth(label) / 1000f * size
        cs.setNonStrokingColor(1f, 1f, 1f)
        cs.addRect(at.x - w / 2 - 2f, at.y - size, w + 4f, size + 3f)
        cs.fill()
        fillColor(cs, e.color)
        cs.beginText()
        cs.setFont(f, size)
        cs.setTextMatrix(Matrix(1f, 0f, 0f, -1f, at.x - w / 2, at.y))
        cs.showText(label)
        cs.endText()
    }

    private fun ink(cs: PDPageContentStream, e: InkEdit) {
        alpha(cs, e.alpha)
        strokeStyle(cs, e.color, e.width)
        e.strokes.filter { it.isNotEmpty() }.forEach { s ->
            cs.moveTo(s[0].x, s[0].y)
            if (s.size == 1) cs.lineTo(s[0].x + 0.01f, s[0].y) else s.drop(1).forEach { cs.lineTo(it.x, it.y) }
            cs.stroke()
        }
    }

    private fun shape(cs: PDPageContentStream, e: ShapeEdit) {
        alpha(cs, e.alpha)
        strokeStyle(cs, e.color, e.width)
        val box = e.frame()
        when (e.kind) {
            ShapeKind.RECT -> cs.addRect(box.left, box.top, box.width, box.height)
            ShapeKind.OVAL -> ellipse(cs, box)
            ShapeKind.LINE -> {
                cs.moveTo(e.start.x, e.start.y)
                cs.lineTo(e.end.x, e.end.y)
            }
            ShapeKind.ARROW -> {
                cs.moveTo(e.start.x, e.start.y)
                cs.lineTo(e.end.x, e.end.y)
                val (a, b) = ArrowHead.points(e.start, e.end, e.width)
                cs.moveTo(a.x, a.y)
                cs.lineTo(e.end.x, e.end.y)
                cs.lineTo(b.x, b.y)
            }
        }
        cs.stroke()
    }

    private fun markup(cs: PDPageContentStream, e: MarkupEdit) {
        when (e.kind) {
            MarkupKind.HIGHLIGHT -> {
                cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply {
                    blendMode = BlendMode.MULTIPLY
                    nonStrokingAlphaConstant = HIGHLIGHT_ALPHA
                })
                fillColor(cs, e.color)
                e.rects.forEach { cs.addRect(it.left, it.top, it.width, it.height) }
                cs.fill()
            }
            MarkupKind.UNDERLINE, MarkupKind.STRIKEOUT -> {
                e.rects.forEach { r ->
                    val y = if (e.kind == MarkupKind.UNDERLINE) r.bottom - r.height * 0.1f else r.top + r.height * 0.55f
                    strokeStyle(cs, e.color, max(1f, r.height * 0.07f), round = false)
                    cs.moveTo(r.left, y)
                    cs.lineTo(r.right, y)
                    cs.stroke()
                }
            }
        }
    }

    private fun text(cs: PDPageContentStream, e: TextEdit) {
        val f = font()
        fillColor(cs, e.color)
        cs.beginText()
        cs.setFont(f, e.size)
        e.text.split('\n').forEachIndexed { i, raw ->
            val line = safeText(f, raw)
            val w = f.getStringWidth(line) / 1000f * e.size
            val x = when (e.align) {
                TextAlign.LEFT -> e.box.left
                TextAlign.CENTER -> e.box.center.x - w / 2
                TextAlign.RIGHT -> e.box.right - w
            }
            val baseline = e.box.top + e.size * TextLayout.ASCENT + i * e.size * TextLayout.LINE_HEIGHT
            // Undo the y flip of display space so glyphs are upright.
            cs.setTextMatrix(Matrix(1f, 0f, 0f, -1f, x, baseline))
            cs.showText(line)
        }
        cs.endText()
    }

    private fun note(cs: PDPageContentStream, e: NoteEdit) {
        val box = e.frame()
        fillColor(cs, e.color)
        cs.addRect(box.left, box.top, box.width, box.height)
        cs.fill()
        strokeStyle(cs, 0xFF5D4037.toInt(), 1f, round = false)
        cs.addRect(box.left, box.top, box.width, box.height)
        for (i in 1..3) {
            val y = box.top + box.height * i / 4.2f
            cs.moveTo(box.left + 4f, y)
            cs.lineTo(box.right - 4f, y)
        }
        cs.stroke()
    }

    private fun image(cs: PDPageContentStream, e: ImageEdit) {
        val bitmap = BitmapFactory.decodeFile(e.imagePath) ?: return
        // Photos stay JPEG; images with transparency (signatures) need lossless + soft mask.
        val img = if (e.imagePath.endsWith(".jpg")) JPEGFactory.createFromImage(doc, bitmap, 0.9f)
        else LosslessFactory.createFromImage(doc, bitmap)
        val rotated = e.rotation == 90 || e.rotation == 270
        val w = if (rotated) e.box.height else e.box.width
        val h = if (rotated) e.box.width else e.box.height
        cs.saveGraphicsState()
        cs.transform(Matrix.getTranslateInstance(e.box.center.x, e.box.center.y))
        cs.transform(Matrix.getRotateInstance(e.rotation * PI / 180.0, 0f, 0f))
        // Negative height flips the image back upright in the y-down space.
        cs.drawImage(img, Matrix(w, 0f, 0f, -h, -w / 2, h / 2))
        cs.restoreGraphicsState()
    }

    private fun stamp(cs: PDPageContentStream, e: StampEdit) {
        val f = font()
        val box = e.box
        val size = e.fontSize
        strokeStyle(cs, e.color, max(1.5f, size * 0.12f), round = true)
        roundRect(cs, box.inset(size * 0.1f), size * 0.35f)
        cs.stroke()
        val line = safeText(f, e.text)
        val w = f.getStringWidth(line) / 1000f * size
        fillColor(cs, e.color)
        cs.beginText()
        cs.setFont(f, size)
        cs.setTextMatrix(Matrix(1f, 0f, 0f, -1f, box.center.x - w / 2, box.center.y + size * 0.35f))
        cs.showText(line)
        cs.endText()
    }

    private fun alpha(cs: PDPageContentStream, alpha: Float) {
        if (alpha >= 1f) return
        cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply {
            strokingAlphaConstant = alpha
            nonStrokingAlphaConstant = alpha
        })
    }

    private fun strokeStyle(cs: PDPageContentStream, color: Int, width: Float, round: Boolean = true) {
        cs.setStrokingColor(r(color), g(color), b(color))
        cs.setLineWidth(width)
        cs.setLineCapStyle(if (round) 1 else 0)
        cs.setLineJoinStyle(if (round) 1 else 0)
    }

    private fun fillColor(cs: PDPageContentStream, color: Int) {
        cs.setNonStrokingColor(r(color), g(color), b(color))
    }

    private fun ellipse(cs: PDPageContentStream, box: Box) {
        val cx = box.center.x
        val cy = box.center.y
        val rx = box.width / 2
        val ry = box.height / 2
        val ox = rx * KAPPA
        val oy = ry * KAPPA
        cs.moveTo(cx - rx, cy)
        cs.curveTo(cx - rx, cy - oy, cx - ox, cy - ry, cx, cy - ry)
        cs.curveTo(cx + ox, cy - ry, cx + rx, cy - oy, cx + rx, cy)
        cs.curveTo(cx + rx, cy + oy, cx + ox, cy + ry, cx, cy + ry)
        cs.curveTo(cx - ox, cy + ry, cx - rx, cy + oy, cx - rx, cy)
        cs.closePath()
    }

    private fun roundRect(cs: PDPageContentStream, b: Box, radius: Float) {
        val r = minOf(radius, b.width / 2, b.height / 2)
        val k = r * (1 - KAPPA)
        cs.moveTo(b.left + r, b.top)
        cs.lineTo(b.right - r, b.top)
        cs.curveTo(b.right - k, b.top, b.right, b.top + k, b.right, b.top + r)
        cs.lineTo(b.right, b.bottom - r)
        cs.curveTo(b.right, b.bottom - k, b.right - k, b.bottom, b.right - r, b.bottom)
        cs.lineTo(b.left + r, b.bottom)
        cs.curveTo(b.left + k, b.bottom, b.left, b.bottom - k, b.left, b.bottom - r)
        cs.lineTo(b.left, b.top + r)
        cs.curveTo(b.left, b.top + k, b.left + k, b.top, b.left + r, b.top)
        cs.closePath()
    }

    companion object {
        const val HIGHLIGHT_ALPHA = 0.45f

        fun r(c: Int) = (c shr 16 and 0xFF) / 255f
        fun g(c: Int) = (c shr 8 and 0xFF) / 255f
        fun b(c: Int) = (c and 0xFF) / 255f

        /** Replaces characters the font cannot encode (e.g. emoji) so showText does not throw. */
        fun safeText(font: PDType0Font, text: String): String = buildString {
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val s = String(Character.toChars(cp))
                append(if (runCatching { font.encode(s) }.isSuccess) s else "?")
                i += Character.charCount(cp)
            }
        }
    }
}
