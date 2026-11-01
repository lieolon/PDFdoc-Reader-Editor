package app.pdfdoc.data.convert

import app.pdfdoc.domain.edit.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextExportsTest {

    @Test
    fun splitsTableRowsIntoCells() {
        fun cell(text: String, x: Float, y: Float) = text.mapIndexed { i, c -> Box(x + i * 6f, y, x + i * 6f + 5f, y + 10f) to c }
        val glyphs = cell("Item", 0f, 0f) + cell("Price", 100f, 0f) + cell("Tea", 0f, 20f) + cell("1,50", 100f, 20f)
        assertEquals(listOf(listOf("Item", "Price"), listOf("Tea", "1,50")), TextExports.rows(glyphs))
    }

    @Test
    fun recognizesNumbersButKeepsCodes() {
        assertEquals(1.5, TextExports.numberOf("1,50")!!, 0.0001)
        assertEquals(1000.0, TextExports.numberOf("1 000")!!, 0.0001)
        assertNull(TextExports.numberOf("007"))
        assertNull(TextExports.numberOf("01.11.2026"))
    }

    @Test
    fun columnNames() {
        assertEquals("A", TextExports.column(0))
        assertEquals("Z", TextExports.column(25))
        assertEquals("AA", TextExports.column(26))
    }

    @Test
    fun parsesMarkdown() {
        val blocks = TextToPdf.markdown("# Title\n\nSome **bold** text\ncontinues.\n\n- one\n- two\n")
        assertEquals(TextBlock("Title", heading = 1), blocks[0])
        assertEquals("Some bold text continues.", blocks[1].text)
        assertEquals(listOf("one", "two"), blocks.filter { it.bullet }.map { it.text })
    }
}
