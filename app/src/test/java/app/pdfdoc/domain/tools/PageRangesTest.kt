package app.pdfdoc.domain.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageRangesTest {
    @Test
    fun parsesListsAndOpenRanges() {
        assertEquals(listOf(0..2, 4..4, 7..9), PageRanges.parse("1-3, 5; 8-", 10))
        assertEquals(listOf(0..3), PageRanges.parse(" -4 ", 10))
        assertEquals(listOf(1..2), PageRanges.parse("2–3", 10))
    }

    @Test
    fun rejectsInvalidInput() {
        assertNull(PageRanges.parse("", 10))
        assertNull(PageRanges.parse("0-2", 10))
        assertNull(PageRanges.parse("5-3", 10))
        assertNull(PageRanges.parse("1-11", 10))
        assertNull(PageRanges.parse("abc", 10))
    }

    @Test
    fun everySplitsIntoChunks() {
        assertEquals(listOf(0..2, 3..5, 6..6), PageRanges.every(3, 7))
    }

    @Test
    fun templatesExpandPlaceholders() {
        assertEquals("Стр. 2 из 9 — a.pdf", Templates.expand("Стр. {n} из {total} — {file}", 2, 9, "d", "a.pdf"))
    }
}
