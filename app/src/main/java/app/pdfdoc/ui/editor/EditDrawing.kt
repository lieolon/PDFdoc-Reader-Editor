package app.pdfdoc.ui.editor

import android.graphics.Paint
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.pdfdoc.data.pdf.AnnotationIO
import app.pdfdoc.data.pdf.AppearancePainter
import app.pdfdoc.data.pdf.ArrowHead
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
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * On-screen counterpart of [AppearancePainter]: draws edits in a canvas where one PDF point
 * equals [k] pixels. Keep both in sync so what users see is what gets saved.
 */
class EditRenderer(private val textPaint: Paint) {

    fun DrawScope.draw(edit: Edit, k: Float, image: (String) -> ImageBitmap?) {
        when (edit) {
            is InkEdit -> ink(edit, k)
            is ShapeEdit -> shape(edit, k)
            is MarkupEdit -> markup(edit, k)
            is TextEdit -> text(edit, k)
            is NoteEdit -> note(edit, k)
            is ImageEdit -> image(edit.imagePath)?.let { image(edit, it, k) }
            is StampEdit -> stamp(edit, k)
            is CoverEdit -> drawRect(Color(edit.color), edit.box.topLeft(k), edit.box.size(k))
        }
    }

    fun DrawScope.selection(edit: Edit, k: Float, zoom: Float) {
        val frame = AnnotationIO.paddedFrame(edit)
        val stroke = 1.5f * density / zoom
        drawRect(
            SelectionColor,
            topLeft = frame.topLeft(k),
            size = frame.size(k),
            style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f * density / zoom, 6f * density / zoom))),
        )
        if (edit.resizable) {
            edit.frame().corners().forEach {
                drawCircle(Color.White, HANDLE_DP * density / zoom, it.offset(k))
                drawCircle(SelectionColor, HANDLE_DP * density / zoom, it.offset(k), style = Stroke(stroke * 1.4f))
            }
        }
    }

    private fun DrawScope.ink(e: InkEdit, k: Float) {
        val color = Color(e.color).copy(alpha = e.alpha)
        e.strokes.forEach { s ->
            if (s.size == 1) {
                drawCircle(color, e.width * k / 2, s[0].offset(k))
            } else {
                val path = Path().apply {
                    moveTo(s[0].x * k, s[0].y * k)
                    s.drop(1).forEach { lineTo(it.x * k, it.y * k) }
                }
                drawPath(path, color, style = Stroke(e.width * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }

    private fun DrawScope.shape(e: ShapeEdit, k: Float) {
        val color = Color(e.color).copy(alpha = e.alpha)
        val stroke = Stroke(e.width * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val f = e.frame()
        when (e.kind) {
            ShapeKind.RECT -> drawRect(color, f.topLeft(k), f.size(k), style = Stroke(e.width * k, join = StrokeJoin.Round))
            ShapeKind.OVAL -> drawOval(color, f.topLeft(k), f.size(k), style = stroke)
            ShapeKind.LINE -> drawLine(color, e.start.offset(k), e.end.offset(k), e.width * k, StrokeCap.Round)
            ShapeKind.ARROW -> {
                val (a, b) = ArrowHead.points(e.start, e.end, e.width)
                val path = Path().apply {
                    moveTo(e.start.x * k, e.start.y * k)
                    lineTo(e.end.x * k, e.end.y * k)
                    moveTo(a.x * k, a.y * k)
                    lineTo(e.end.x * k, e.end.y * k)
                    lineTo(b.x * k, b.y * k)
                }
                drawPath(path, color, style = stroke)
            }
        }
    }

    private fun DrawScope.markup(e: MarkupEdit, k: Float) {
        val color = Color(e.color)
        e.rects.forEach { r ->
            when (e.kind) {
                MarkupKind.HIGHLIGHT -> drawRect(
                    color.copy(alpha = AppearancePainter.HIGHLIGHT_ALPHA), r.topLeft(k), r.size(k), blendMode = BlendMode.Multiply,
                )
                MarkupKind.UNDERLINE, MarkupKind.STRIKEOUT -> {
                    val y = if (e.kind == MarkupKind.UNDERLINE) r.bottom - r.height * 0.1f else r.top + r.height * 0.55f
                    drawLine(color, Offset(r.left * k, y * k), Offset(r.right * k, y * k), max(1f, r.height * 0.07f) * k)
                }
            }
        }
    }

    private fun DrawScope.text(e: TextEdit, k: Float) {
        textPaint.color = e.color
        textPaint.textSize = e.size * k
        e.text.split('\n').forEachIndexed { i, line ->
            val w = textPaint.measureText(line) / k
            val x = when (e.align) {
                TextAlign.LEFT -> e.box.left
                TextAlign.CENTER -> e.box.center.x - w / 2
                TextAlign.RIGHT -> e.box.right - w
            }
            val baseline = e.box.top + e.size * TextLayout.ASCENT + i * e.size * TextLayout.LINE_HEIGHT
            drawContext.canvas.nativeCanvas.drawText(line, x * k, baseline * k, textPaint)
        }
    }

    private fun DrawScope.note(e: NoteEdit, k: Float) {
        val f = e.frame()
        drawRect(Color(e.color), f.topLeft(k), f.size(k))
        val border = Color(0xFF5D4037)
        drawRect(border, f.topLeft(k), f.size(k), style = Stroke(1f * k))
        for (i in 1..3) {
            val y = (f.top + f.height * i / 4.2f) * k
            drawLine(border, Offset((f.left + 4f) * k, y), Offset((f.right - 4f) * k, y), 1f * k)
        }
    }

    private fun DrawScope.image(e: ImageEdit, img: ImageBitmap, k: Float) {
        val rotated = e.rotation == 90 || e.rotation == 270
        val w = (if (rotated) e.box.height else e.box.width) * k
        val h = (if (rotated) e.box.width else e.box.height) * k
        val c = e.box.center.offset(k)
        rotate(e.rotation.toFloat(), pivot = c) {
            drawImage(
                img,
                dstOffset = IntOffset((c.x - w / 2).roundToInt(), (c.y - h / 2).roundToInt()),
                dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
            )
        }
    }

    private fun DrawScope.stamp(e: StampEdit, k: Float) {
        val size = e.fontSize
        val color = Color(e.color)
        val inner = e.box.inset(size * 0.1f)
        val radius = minOf(size * 0.35f, inner.width / 2, inner.height / 2) * k
        drawRoundRect(color, inner.topLeft(k), inner.size(k), CornerRadius(radius), style = Stroke(max(1.5f, size * 0.12f) * k))
        textPaint.color = e.color
        textPaint.textSize = size * k
        val w = textPaint.measureText(e.text)
        withTransform({}) {
            drawContext.canvas.nativeCanvas.drawText(e.text, e.box.center.x * k - w / 2, (e.box.center.y + size * 0.35f) * k, textPaint)
        }
    }

    companion object {
        const val HANDLE_DP = 6f
        val SelectionColor = Color(0xFF1E88E5)
    }
}

internal fun Pt.offset(k: Float) = Offset(x * k, y * k)
internal fun Box.topLeft(k: Float) = Offset(left * k, top * k)
internal fun Box.size(k: Float) = Size(width * k, height * k)
