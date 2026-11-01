package app.pdfdoc.domain.edit

import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A clickable area that opens a web address or jumps to a page of the document. */
@Serializable
@SerialName("link")
data class LinkEdit(
    override val id: Long,
    override val page: Int,
    val box: Box,
    val url: String? = null,
    /** Zero-based target page when [url] is null. */
    val targetPage: Int = -1,
) : Edit {
    override fun frame() = box
    override fun translate(dx: Float, dy: Float) = copy(box = box.translate(dx, dy))
    override fun withColor(color: Int) = this
    override fun withId(id: Long) = copy(id = id)
    override val resizable get() = true
}

enum class MeasureKind { DISTANCE, PERIMETER, AREA }

/**
 * A measurement drawn on the page. [unitsPerPoint] converts page points to real units
 * (from the scale "1 cm on paper = N units").
 */
@Serializable
@SerialName("measure")
data class MeasureEdit(
    override val id: Long,
    override val page: Int,
    val kind: MeasureKind,
    val points: List<Pt>,
    val color: Int,
    val unitsPerPoint: Float,
    val unit: String,
) : Edit {
    override fun frame() = Box.bounding(points)!!
    override fun translate(dx: Float, dy: Float) = copy(points = points.map { Pt(it.x + dx, it.y + dy) })
    override fun withColor(color: Int) = copy(color = color)
    override fun withId(id: Long) = copy(id = id)

    /** Value in real units: length for distance and perimeter, area for area. */
    val value: Float get() = when (kind) {
        MeasureKind.DISTANCE, MeasureKind.PERIMETER -> Measure.length(points, closed = false) * unitsPerPoint
        MeasureKind.AREA -> Measure.area(points) * unitsPerPoint * unitsPerPoint
    }

    val label: String get() = Measure.format(value) + " " + unit + if (kind == MeasureKind.AREA) "²" else ""
}

/**
 * A region whose content is removed for good on save: the page is re-rendered with the
 * region blacked out, so the hidden text and images cannot be recovered.
 */
@Serializable
@SerialName("redact")
data class RedactEdit(
    override val id: Long,
    override val page: Int,
    val box: Box,
) : Edit {
    override fun frame() = box
    override fun translate(dx: Float, dy: Float) = copy(box = box.translate(dx, dy))
    override fun withColor(color: Int) = this
    override fun withId(id: Long) = copy(id = id)
    override val resizable get() = true
}

enum class FieldKind { TEXT, MULTILINE, CHECKBOX, RADIO, DROPDOWN, DATE, SIGNATURE }

/** A new form field; it becomes a real AcroForm field when the document is saved. */
@Serializable
@SerialName("field")
data class FieldEdit(
    override val id: Long,
    override val page: Int,
    val box: Box,
    val kind: FieldKind,
    val name: String,
    /** Choices of a drop-down list, or the export value of a radio button. */
    val options: List<String> = emptyList(),
    /** Radio buttons with the same group form one choice. */
    val group: String = "",
) : Edit {
    override fun frame() = box
    override fun translate(dx: Float, dy: Float) = copy(box = box.translate(dx, dy))
    override fun withColor(color: Int) = this
    override fun withId(id: Long) = copy(id = id)
    override val resizable get() = true
}

/** Edits applied once when saving (they change page content or the form), not kept as annotations. */
val Edit.appliedOnSave: Boolean get() = this is RedactEdit || this is FieldEdit

object Measure {
    const val POINTS_PER_CM = 72f / 2.54f

    fun length(points: List<Pt>, closed: Boolean): Float {
        if (points.size < 2) return 0f
        var sum = 0f
        for (i in 1 until points.size) sum += distance(points[i - 1], points[i])
        if (closed) sum += distance(points.last(), points.first())
        return sum
    }

    /** Shoelace formula. */
    fun area(points: List<Pt>): Float {
        if (points.size < 3) return 0f
        var s = 0f
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2f
    }

    fun format(v: Float): String = when {
        v >= 100f -> "%.0f".format(v)
        v >= 10f -> "%.1f".format(v)
        else -> "%.2f".format(v)
    }
}

/**
 * Turns a hand-drawn stroke into a straight line, rectangle or ellipse when it is close enough;
 * returns null to keep the stroke as drawn.
 */
object ShapeRecognizer {

    fun recognize(points: List<Pt>): Pair<ShapeKind, Pair<Pt, Pt>>? {
        if (points.size < 4) return null
        val box = Box.bounding(points) ?: return null
        val size = maxOf(box.width, box.height)
        if (size < 12f) return null
        val first = points.first()
        val last = points.last()
        val pathLength = Measure.length(points, closed = false)

        // A line: the path is barely longer than the distance between its ends.
        val chord = distance(first, last)
        if (chord > size * 0.6f && pathLength < chord * 1.08f) return ShapeKind.LINE to (first to last)

        val closed = distance(first, last) < size * 0.25f
        if (!closed) return null

        // Mean distance from the box border, relative to the box size.
        val rectError = points.map { p ->
            minOf(abs(p.x - box.left), abs(p.x - box.right), abs(p.y - box.top), abs(p.y - box.bottom))
        }.average().toFloat() / size
        val c = box.center
        val rx = box.width / 2
        val ry = box.height / 2
        if (rx < 4f || ry < 4f) return null
        val ellipseError = points.map { p ->
            val d = hypot((p.x - c.x) / rx, (p.y - c.y) / ry)
            abs(d - 1f)
        }.average().toFloat()

        val corners = Pt(box.left, box.top) to Pt(box.right, box.bottom)
        return when {
            rectError < 0.045f && rectError * 2.2f < ellipseError -> ShapeKind.RECT to corners
            ellipseError < 0.14f -> ShapeKind.OVAL to corners
            rectError < 0.06f -> ShapeKind.RECT to corners
            else -> null
        }
    }
}

/** Removes from [strokes] every stroke that passes within [radius] of the eraser path. */
fun eraseStrokes(strokes: List<List<Pt>>, path: List<Pt>, radius: Float): List<List<Pt>> =
    strokes.filterNot { stroke -> stroke.any { p -> path.any { distance(it, p) <= radius } } || segmentsHit(stroke, path, radius) }

private fun segmentsHit(stroke: List<Pt>, path: List<Pt>, radius: Float): Boolean {
    for (i in 1 until stroke.size) {
        val a = stroke[i - 1]
        val b = stroke[i]
        if (path.any { distanceToSegment(it, a, b) <= radius }) return true
    }
    return false
}

fun distanceToSegment(p: Pt, a: Pt, b: Pt): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len2 = dx * dx + dy * dy
    if (len2 == 0f) return distance(p, a)
    val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
    return distance(p, Pt(a.x + t * dx, a.y + t * dy))
}
