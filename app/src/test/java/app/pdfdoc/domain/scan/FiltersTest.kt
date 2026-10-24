package app.pdfdoc.domain.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FiltersTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun lum(c: Int) = Filters.luminance(c)

    @Test
    fun enhanceStretchesDullImageToFullRange() {
        val pixels = IntArray(100) { if (it < 50) rgb(90, 90, 90) else rgb(170, 170, 170) }
        val out = Filters.enhance(pixels)
        assertEquals(0, lum(out[0]))
        assertEquals(255, lum(out[99]))
    }

    @Test
    fun grayscaleHasEqualChannels() {
        val out = Filters.apply(intArrayOf(rgb(200, 30, 30)), 1, 1, ScanFilter.GRAYSCALE)
        val c = out[0]
        assertEquals(c shr 16 and 0xFF, c and 0xFF)
    }

    @Test
    fun blackWhiteSurvivesShadowGradient() {
        // Paper gets darker left to right (shadow); a dark stroke runs down the middle.
        val w = 64
        val h = 64
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val paper = 230 - x * 2
            val v = if (x in 30..32) paper - 120 else paper
            rgb(v, v, v)
        }
        val out = Filters.blackWhite(pixels, w, h)
        val row = 32 * w
        assertEquals("ink is black", 0, lum(out[row + 31]))
        assertEquals("bright paper is white", 255, lum(out[row + 2]))
        assertEquals("shadowed paper is still white", 255, lum(out[row + 60]))
        assertTrue(out.all { lum(it) == 0 || lum(it) == 255 })
    }
}
