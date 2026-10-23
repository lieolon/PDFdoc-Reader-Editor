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
)

enum class SortOrder { DATE, NAME, SIZE }

enum class ThemeMode { SYSTEM, LIGHT, DARK }
