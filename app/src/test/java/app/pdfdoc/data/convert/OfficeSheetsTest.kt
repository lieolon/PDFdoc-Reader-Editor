package app.pdfdoc.data.convert

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class OfficeSheetsTest {

    private fun zip(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            files.forEach { (name, text) ->
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
    }.toByteArray()

    @Test
    fun xlsxRowsWithSharedStringsAndGaps() {
        val bytes = zip(
            "xl/workbook.xml" to """<workbook><sheets><sheet name="Счёт" sheetId="1"/></sheets></workbook>""",
            "xl/sharedStrings.xml" to """<sst><si><t>Товар</t></si><si><r><t>Цена</t></r></si><si><t>Чай &amp; кофе</t></si></sst>""",
            "xl/worksheets/sheet1.xml" to """<worksheet><sheetData>
                <row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1" t="s"><v>1</v></c></row>
                <row r="2"><c r="A2" t="s"><v>2</v></c><c r="C2"><v>120.5</v></c></row>
                </sheetData></worksheet>""",
        )
        val blocks = OfficeText.xlsx(bytes)
        assertEquals("Счёт", blocks[0].text)
        assertEquals(2, blocks[0].heading)
        assertEquals("Товар |  | Цена", blocks[1].text)
        assertEquals("Чай & кофе |  | 120.5", blocks[2].text)
    }

    @Test
    fun pptxSlidesInNumericOrder() {
        val bytes = zip(
            "ppt/slides/slide10.xml" to """<p:sld><a:p><a:r><a:t>Десятый</a:t></a:r></a:p></p:sld>""",
            "ppt/slides/slide2.xml" to """<p:sld><a:p><a:r><a:t>Второй </a:t></a:r><a:r><a:t>слайд</a:t></a:r></a:p></p:sld>""",
        )
        val blocks = OfficeText.pptx(bytes) { "Слайд $it" }
        assertEquals(listOf("Слайд 1", "Второй слайд", "Слайд 2", "Десятый"), blocks.map { it.text })
    }
}
