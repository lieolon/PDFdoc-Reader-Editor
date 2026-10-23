package app.pdfdoc.ui.home

import app.pdfdoc.domain.model.PdfDocument
import app.pdfdoc.domain.model.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class FilterAndSortTest {

    private val docs = listOf(
        doc(1, "Договор.pdf", size = 300, opened = 10),
        doc(2, "акт сверки.pdf", size = 100, opened = 30),
        doc(3, "Book.pdf", size = 200, opened = 20),
    )

    @Test
    fun sortsByDateNewestFirst() {
        assertEquals(listOf(2L, 3L, 1L), filterAndSort(docs, "", SortOrder.DATE).map { it.id })
    }

    @Test
    fun sortsByNameIgnoringCase() {
        assertEquals(listOf(3L, 2L, 1L), filterAndSort(docs, "", SortOrder.NAME).map { it.id })
    }

    @Test
    fun sortsBySizeLargestFirst() {
        assertEquals(listOf(1L, 3L, 2L), filterAndSort(docs, "", SortOrder.SIZE).map { it.id })
    }

    @Test
    fun filtersCyrillicCaseInsensitive() {
        assertEquals(listOf(1L), filterAndSort(docs, "  договор ", SortOrder.DATE).map { it.id })
        assertEquals(listOf(2L), filterAndSort(docs, "АКТ", SortOrder.DATE).map { it.id })
    }

    private fun doc(id: Long, name: String, size: Long, opened: Long) = PdfDocument(
        id = id, uri = "content://$id", name = name, sizeBytes = size, pageCount = 1,
        lastOpenedAt = opened, lastPage = 0, thumbnailPath = null,
    )
}
