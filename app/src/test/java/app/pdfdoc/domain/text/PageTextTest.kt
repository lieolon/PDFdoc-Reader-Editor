package app.pdfdoc.domain.text

import app.pdfdoc.domain.edit.Box
import app.pdfdoc.domain.edit.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTextTest {

    /** Lays out [lines] with 10pt-wide glyphs, 20pt line pitch. */
    private fun page(vararg lines: String): PageText {
        val glyphs = mutableListOf<Glyph>()
        lines.forEachIndexed { row, line ->
            line.forEachIndexed { col, ch ->
                val box = if (ch == ' ') null else Box(col * 10f, row * 20f, col * 10f + 10f, row * 20f + 12f)
                glyphs += Glyph(ch, box)
            }
            glyphs += Glyph('\n', null)
        }
        return PageText(glyphs)
    }

    @Test
    fun findsCaseInsensitiveCyrillicAndYo() {
        val text = page("Договор подписан", "ЁЛКА ёлка")
        assertEquals(1, text.find("ДОГОВОР").size)
        assertEquals(2, text.find("елка").size)
    }

    @Test
    fun hitAcrossLinesGivesOneRectPerLine() {
        val text = page("abc de", "fgh")
        val hits = text.find("de fgh")
        assertEquals(1, hits.size)
        assertEquals(2, hits[0].size)
        assertEquals(Box(40f, 0f, 60f, 12f), hits[0][0])
        assertEquals(Box(0f, 20f, 30f, 32f), hits[0][1])
    }

    @Test
    fun selectionUsesNearestGlyphsInReadingOrder() {
        val text = page("hello world")
        val rects = text.select(Pt(85f, 5f), Pt(1f, 5f))
        assertEquals(listOf(Box(0f, 0f, 90f, 12f)), rects)
        assertEquals("hello world", text.selectedText(Pt(1f, 5f), Pt(105f, 5f)))
    }

    @Test
    fun emptyPage() {
        val text = PageText(emptyList())
        assertTrue(text.isEmpty)
        assertTrue(text.find("x").isEmpty())
        assertTrue(text.select(Pt(0f, 0f), Pt(1f, 1f)).isEmpty())
    }
}
