package app.pdfdoc.data.pdf

import app.pdfdoc.domain.edit.Pt
import org.junit.Assert.assertEquals
import org.junit.Test

/** Crop box 100x200 at (10, 20). */
class PageGeometryTest {
    private fun geo(rotation: Int) = PageGeometry(10f, 20f, 100f, 200f, rotation)

    private fun check(rotation: Int, display: Pt, pdf: Pt) {
        val g = geo(rotation)
        assertEquals(pdf, g.toPdf(display))
        assertEquals(display, g.toDisplay(pdf))
    }

    @Test
    fun rotation0_topLeftIsPdfTopLeft() {
        check(0, Pt(0f, 0f), Pt(10f, 220f))
        check(0, Pt(100f, 200f), Pt(110f, 20f))
    }

    @Test
    fun rotation90_topLeftIsPdfBottomLeft() {
        check(90, Pt(0f, 0f), Pt(10f, 20f))
        check(90, Pt(200f, 0f), Pt(10f, 220f))
        check(90, Pt(0f, 100f), Pt(110f, 20f))
        assertEquals(200f, geo(90).displayWidth)
    }

    @Test
    fun rotation180_topLeftIsPdfBottomRight() {
        check(180, Pt(0f, 0f), Pt(110f, 20f))
        check(180, Pt(100f, 200f), Pt(10f, 220f))
    }

    @Test
    fun rotation270_topLeftIsPdfTopRight() {
        check(270, Pt(0f, 0f), Pt(110f, 220f))
        check(270, Pt(200f, 100f), Pt(10f, 20f))
    }
}
