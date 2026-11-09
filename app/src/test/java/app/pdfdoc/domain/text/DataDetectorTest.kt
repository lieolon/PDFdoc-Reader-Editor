package app.pdfdoc.domain.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataDetectorTest {

    @Test
    fun findsEachKindOnce() {
        val found = DataDetector.find(listOf(
            "Счёт № 15 от 12.03.2026. Итого: 12 500,00 ₽. Звоните +7 (912) 345-67-89 или пишите info@lielon.com",
            "Подробнее: https://lielon.com/projects/pdfdoc/. Повтор: info@lielon.com, +7 912 345 67 89",
        ))
        val kinds = found.groupBy { it.kind }
        assertEquals(listOf("+7 (912) 345-67-89"), kinds[DataKind.PHONE]?.map { it.text })
        assertEquals(listOf("info@lielon.com"), kinds[DataKind.EMAIL]?.map { it.text })
        assertEquals(listOf("12.03.2026"), kinds[DataKind.DATE]?.map { it.text })
        assertEquals(listOf("https://lielon.com/projects/pdfdoc/"), kinds[DataKind.LINK]?.map { it.text })
        assertTrue(kinds[DataKind.AMOUNT].orEmpty().any { it.text.startsWith("12 500,00") })
        assertEquals(1, kinds[DataKind.LINK]!!.single().page)
    }

    @Test
    fun parsesDates() {
        assertEquals(Triple(2026, 3, 12), DataDetector.parseDate("12.03.2026"))
        assertEquals(Triple(2026, 11, 9), DataDetector.parseDate("2026-11-09"))
        assertEquals(Triple(2026, 5, 1), DataDetector.parseDate("1 мая 2026"))
        assertEquals(null, DataDetector.parseDate("31.13.2026"))
    }
}
