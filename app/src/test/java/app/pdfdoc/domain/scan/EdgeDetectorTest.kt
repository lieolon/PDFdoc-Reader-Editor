package app.pdfdoc.domain.scan

import app.pdfdoc.domain.edit.Pt
import app.pdfdoc.domain.edit.distance
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeDetectorTest {
    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    /** Point-in-convex-polygon test for drawing the synthetic page. */
    private fun inside(p: Pt, q: Quad): Boolean {
        val c = q.corners
        return c.indices.all { i ->
            val a = c[i]
            val b = c[(i + 1) % 4]
            (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x) >= 0
        }
    }

    /** Dark table, light page with dark text lines, a bit of noise. */
    private fun photo(w: Int, h: Int, page: Quad): IntArray {
        val rnd = java.util.Random(7)
        return IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val p = Pt(x + 0.5f, y + 0.5f)
            val base = if (inside(p, page)) {
                if (y % 12 == 6 && x % 40 < 30) 60 else 225
            } else {
                70
            }
            gray((base + rnd.nextInt(21) - 10).coerceIn(0, 255))
        }
    }

    private fun assertClose(expected: Quad, actual: Quad?, tolerance: Float) {
        assertNotNull(actual)
        expected.corners.zip(actual!!.corners).forEach { (e, a) ->
            assertTrue("expected $e got $a", distance(e, a) <= tolerance)
        }
    }

    @Test
    fun findsStraightPage() {
        val page = Quad(Pt(40f, 30f), Pt(200f, 30f), Pt(200f, 230f), Pt(40f, 230f))
        assertClose(page, EdgeDetector.detect(photo(240, 260, page), 240, 260), 4f)
    }

    @Test
    fun findsTiltedPageInPerspective() {
        val page = Quad(Pt(60f, 20f), Pt(210f, 45f), Pt(190f, 240f), Pt(25f, 215f))
        assertClose(page, EdgeDetector.detect(photo(240, 260, page), 240, 260), 6f)
    }

    @Test
    fun pageFillingTheFrameGivesTheWholeFrame() {
        val full = Quad(Pt(0f, 0f), Pt(240f, 0f), Pt(240f, 260f), Pt(0f, 260f))
        EdgeDetector.detect(photo(240, 260, full), 240, 260)?.let { assertClose(full, it, 6f) }
    }

    @Test
    fun givesUpWithoutAPage() {
        val uniform = IntArray(240 * 260) { gray(90) }
        assertNull(EdgeDetector.detect(uniform, 240, 260))
    }
}
