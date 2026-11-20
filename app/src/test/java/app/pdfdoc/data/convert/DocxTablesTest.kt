package app.pdfdoc.data.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocxTablesTest {

    private val xml = """
        <w:document><w:body>
        <w:p><w:pPr><w:jc w:val="both"/></w:pPr><w:r><w:t>Intro text.</w:t></w:r></w:p>
        <w:tbl>
          <w:tblGrid><w:gridCol w:w="4000"/><w:gridCol w:w="6000"/></w:tblGrid>
          <w:tr>
            <w:tc><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Agreement details</w:t></w:r></w:p></w:tc>
            <w:tc><w:p><w:r><w:t>Amount</w:t></w:r></w:p></w:tc>
          </w:tr>
          <w:tr>
            <w:tc><w:p><w:r><w:t>Currency</w:t></w:r></w:p></w:tc>
            <w:tc><w:p><w:r><w:t>XXXX</w:t></w:r></w:p><w:p><w:r><w:t>second line</w:t></w:r></w:p></w:tc>
          </w:tr>
          <w:tr>
            <w:tc><w:tcPr><w:gridSpan w:val="2"/></w:tcPr><w:p><w:r><w:t>Merged</w:t></w:r></w:p></w:tc>
          </w:tr>
        </w:tbl>
        <w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:rPr><w:b/></w:rPr><w:t>Title</w:t></w:r></w:p>
        </w:body></w:document>
    """.trimIndent()

    @Test
    fun tablesKeepRowsCellsAndWidths() {
        val blocks = OfficeText.docxXml(xml)
        assertEquals(3, blocks.size)
        assertEquals(TextAlign.JUSTIFY, blocks[0].align)
        val table = assertNotNullTable(blocks[1].table)
        assertEquals(listOf(4000f, 6000f), table.widths)
        assertEquals(3, table.rows.size)
        assertEquals("Agreement details", table.rows[0][0].text)
        assertTrue(table.rows[0][0].bold)
        assertEquals("XXXX\nsecond line", table.rows[1][1].text)
        assertEquals(2, table.rows[2][0].span)
        assertEquals(TextAlign.CENTER, blocks[2].align)
        assertTrue(blocks[2].bold)
    }

    private fun assertNotNullTable(t: Table?): Table { assertNotNull(t); return t!! }
}
