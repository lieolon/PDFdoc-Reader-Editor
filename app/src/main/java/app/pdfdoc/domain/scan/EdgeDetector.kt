package app.pdfdoc.domain.scan

import app.pdfdoc.domain.edit.Pt
import kotlin.math.abs

/**
 * Finds a document on a photo without OpenCV: the page is usually the largest bright region.
 * Works on a small image (about 256 px), so it runs in milliseconds; returns null when unsure,
 * and the caller then falls back to a default frame.
 */
object EdgeDetector {

    fun detect(pixels: IntArray, width: Int, height: Int): Quad? {
        if (width < 16 || height < 16) return null
        val lum = blur(blur(IntArray(pixels.size) { Filters.luminance(pixels[it]) }, width, height), width, height)
        val threshold = otsu(lum)
        val mask = BooleanArray(lum.size) { lum[it] > threshold }
        val component = largestComponent(mask, width, height) ?: return null
        val area = component.size
        val total = width * height
        // Too small is noise; almost everything bright means the page fills the frame.
        if (area < total * 0.12 || area > total * 0.97) return null

        // Row extremes of the region are enough to find its corners.
        val left = IntArray(height) { Int.MAX_VALUE }
        val right = IntArray(height) { -1 }
        component.forEach { i ->
            val x = i % width
            val y = i / width
            if (x < left[y]) left[y] = x
            if (x > right[y]) right[y] = x
        }
        val points = (0 until height).filter { right[it] >= 0 }.flatMap {
            listOf(Pt(left[it].toFloat(), it.toFloat()), Pt(right[it] + 1f, it.toFloat()))
        }
        val quad = Quad(
            topLeft = points.minBy { it.x + it.y },
            topRight = points.maxBy { it.x - it.y },
            bottomRight = points.maxBy { it.x + it.y },
            bottomLeft = points.minBy { it.x - it.y },
        )
        // A real page fills most of its outline; odd shapes (hands, shadows) don't.
        return quad.takeIf { quadArea(it) > 0 && area >= quadArea(it) * 0.8 && quadArea(it) <= area * 1.35 }
    }

    /** Shoelace formula. */
    fun quadArea(q: Quad): Double {
        val c = q.corners
        var s = 0.0
        for (i in c.indices) {
            val a = c[i]
            val b = c[(i + 1) % c.size]
            s += a.x.toDouble() * b.y - b.x.toDouble() * a.y
        }
        return abs(s) / 2
    }

    private fun blur(src: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) {
                    sum += src[yy * w + xx]
                    n++
                }
            }
            out[y * w + x] = sum / n
        }
        return out
    }

    /** Otsu's threshold: best split of the histogram into dark and bright classes. */
    private fun otsu(lum: IntArray): Int {
        val hist = IntArray(256)
        lum.forEach { hist[it]++ }
        val total = lum.size.toDouble()
        val sumAll = hist.indices.sumOf { it.toDouble() * hist[it] }
        var sumB = 0.0
        var wB = 0.0
        var best = 0.0
        var threshold = 127
        for (t in 0 until 256) {
            wB += hist[t]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += t * hist[t].toDouble()
            val mB = sumB / wB
            val mF = (sumAll - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                threshold = t
            }
        }
        return threshold
    }

    /** Pixel indices of the largest 4-connected true region. */
    private fun largestComponent(mask: BooleanArray, w: Int, h: Int): IntArray? {
        val label = IntArray(mask.size)
        val queue = IntArray(mask.size)
        var best: IntArray? = null
        var next = 1
        for (start in mask.indices) {
            if (!mask[start] || label[start] != 0) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = next
            while (head < tail) {
                val i = queue[head++]
                val x = i % w
                val y = i / w
                fun visit(j: Int) {
                    if (mask[j] && label[j] == 0) {
                        label[j] = next
                        queue[tail++] = j
                    }
                }
                if (x > 0) visit(i - 1)
                if (x < w - 1) visit(i + 1)
                if (y > 0) visit(i - w)
                if (y < h - 1) visit(i + w)
            }
            if (best == null || tail > best.size) best = queue.copyOf(tail)
            next++
        }
        return best
    }
}
