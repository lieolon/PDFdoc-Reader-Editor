package app.pdfdoc.domain.model

data class PdfDocument(
    val id: Long,
    val uri: String,
    val name: String,
    val sizeBytes: Long,
    val pageCount: Int,
    val lastOpenedAt: Long,
    val lastPage: Int,
    val thumbnailPath: String?,
    val isFavorite: Boolean = false,
    val isSecret: Boolean = false,
)

enum class SortOrder { DATE, NAME, SIZE }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** How the viewer lays out pages. In [SPREAD] one list item holds two pages side by side. */
enum class ViewMode {
    CONTINUOUS, SINGLE, SPREAD;

    fun itemOf(page: Int) = if (this == SPREAD) page / 2 else page
    fun pageOf(item: Int) = if (this == SPREAD) item * 2 else item
}
