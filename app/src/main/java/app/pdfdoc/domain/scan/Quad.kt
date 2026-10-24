package app.pdfdoc.domain.scan

import app.pdfdoc.domain.edit.Pt
import app.pdfdoc.domain.edit.distance
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToInt

/** Document outline in image pixels: top-left, top-right, bottom-right, bottom-left. */
data class Quad(val topLeft: Pt, val topRight: Pt, val bottomRight: Pt, val bottomLeft: Pt) {

    val corners: List<Pt> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    fun with(index: Int, p: Pt): Quad = ordered(corners.toMutableList().also { it[index] = p })

    /** Size of the rectangle the quad is straightened into. */
    fun outputSize(): Pair<Int, Int> {
        val w = max(distance(topLeft, topRight), distance(bottomLeft, bottomRight))
        val h = max(distance(topLeft, bottomLeft), distance(topRight, bottomRight))
        return w.roundToInt().coerceAtLeast(1) to h.roundToInt().coerceAtLeast(1)
    }

    /** Same outline in an image rotated 90° clockwise, whose width was [height] before rotating. */
    fun rotatedClockwise(height: Int): Quad =
        ordered(corners.map { Pt(height - it.y, it.x) })

    companion object {
        fun full(width: Int, height: Int, inset: Float = 0f): Quad {
            val dx = width * inset
            val dy = height * inset
            return Quad(Pt(dx, dy), Pt(width - dx, dy), Pt(width - dx, height - dy), Pt(dx, height - dy))
        }

        private fun fromCorners(c: List<Pt>) = Quad(c[0], c[1], c[2], c[3])

        /**
         * Orders four arbitrary points clockwise starting from the top-left, so dragging a corner
         * past another never produces a self-intersecting outline.
         */
        fun ordered(points: List<Pt>): Quad {
            require(points.size == 4)
            val cx = points.sumOf { it.x.toDouble() } / 4
            val cy = points.sumOf { it.y.toDouble() } / 4
            val clockwise = points.sortedBy { atan2(it.y - cy, it.x - cx) }
            // In y-down coordinates increasing atan2 goes clockwise; start at the smallest x + y.
            val start = clockwise.indices.minWith(compareBy({ clockwise[it].x + clockwise[it].y }, { clockwise[it].y }))
            return fromCorners(List(4) { clockwise[(start + it) % 4] })
        }
    }
}
