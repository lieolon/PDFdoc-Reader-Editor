package app.pdfdoc.domain.edit

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.Serializable

/** A point in page display space: PDF points, origin at the top-left of the rotated page. */
@Serializable
data class Pt(val x: Float, val y: Float) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
}

/** An axis-aligned rectangle in page display space. Always normalized (left <= right, top <= bottom). */
@Serializable
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val center get() = Pt((left + right) / 2, (top + bottom) / 2)

    fun contains(p: Pt, tolerance: Float = 0f) =
        p.x >= left - tolerance && p.x <= right + tolerance && p.y >= top - tolerance && p.y <= bottom + tolerance

    fun union(o: Box) = Box(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))
    fun inset(d: Float) = Box(left + d, top + d, right - d, bottom - d)
    fun translate(dx: Float, dy: Float) = Box(left + dx, top + dy, right + dx, bottom + dy)

    /** Corners in order: top-left, top-right, bottom-right, bottom-left. */
    fun corners() = listOf(Pt(left, top), Pt(right, top), Pt(right, bottom), Pt(left, bottom))

    /** Maps a point inside this box to the same relative position inside [target]. */
    fun mapTo(target: Box, p: Pt): Pt {
        val fx = if (width == 0f) 0f else (p.x - left) / width
        val fy = if (height == 0f) 0f else (p.y - top) / height
        return Pt(target.left + fx * target.width, target.top + fy * target.height)
    }

    /** Vertical overlap with [o] as a fraction of the smaller height. */
    fun verticalOverlap(o: Box): Float {
        val overlap = min(bottom, o.bottom) - max(top, o.top)
        val h = min(height, o.height)
        return if (h <= 0f) 0f else overlap / h
    }

    companion object {
        fun of(a: Pt, b: Pt) = Box(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))

        fun bounding(points: Iterable<Pt>): Box? {
            val it = points.iterator()
            if (!it.hasNext()) return null
            val first = it.next()
            var box = Box(first.x, first.y, first.x, first.y)
            while (it.hasNext()) {
                val p = it.next()
                box = Box(min(box.left, p.x), min(box.top, p.y), max(box.right, p.x), max(box.bottom, p.y))
            }
            return box
        }
    }
}

fun distance(a: Pt, b: Pt): Float = kotlin.math.hypot(a.x - b.x, a.y - b.y)

fun Float.approx(o: Float, eps: Float = 0.01f) = abs(this - o) <= eps
