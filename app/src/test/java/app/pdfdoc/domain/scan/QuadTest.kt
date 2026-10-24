package app.pdfdoc.domain.scan

import app.pdfdoc.domain.edit.Pt
import org.junit.Assert.assertEquals
import org.junit.Test

class QuadTest {

    @Test
    fun orderedSortsAnyPermutationClockwiseFromTopLeft() {
        val expected = Quad(Pt(10f, 12f), Pt(90f, 8f), Pt(95f, 120f), Pt(5f, 110f))
        val shuffled = listOf(expected.bottomRight, expected.topLeft, expected.bottomLeft, expected.topRight)
        assertEquals(expected, Quad.ordered(shuffled))
    }

    @Test
    fun draggingCornerPastNeighbourKeepsOutlineValid() {
        val q = Quad.full(100, 100)
        // Drag top-left far to the right of top-right: corners get re-ordered.
        val moved = q.with(0, Pt(150f, 5f))
        assertEquals(Pt(100f, 0f), moved.topLeft)
        assertEquals(Pt(150f, 5f), moved.topRight)
    }

    @Test
    fun outputSizeUsesLongestOppositeEdges() {
        val q = Quad(Pt(0f, 0f), Pt(100f, 0f), Pt(120f, 200f), Pt(-10f, 210f))
        val (w, h) = q.outputSize()
        assertEquals(130, w)
        assertEquals(210, h)
    }

    @Test
    fun rotatingImageRotatesOutline() {
        val q = Quad.full(100, 50)
        val r = q.rotatedClockwise(height = 50)
        assertEquals(Quad.full(50, 100), r)
    }
}
