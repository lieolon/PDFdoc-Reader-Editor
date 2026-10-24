package app.pdfdoc.domain.tools

enum class StampPosition { TOP_LEFT, TOP_CENTER, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT }

/**
 * Text placed on every page. [template] supports {n} (page number), {total}, {date} and {file}.
 */
data class TextStamp(
    val template: String,
    val position: StampPosition,
    val size: Float = 10f,
    val color: Int = 0xFF000000.toInt(),
    val margin: Float = 28f,
)

enum class WatermarkLayout { CENTER, TILED }

sealed interface Watermark {
    val opacity: Float
    /** Counter-clockwise rotation in degrees, as users expect (45 = bottom-left to top-right). */
    val angle: Float
    val layout: WatermarkLayout
}

data class TextWatermark(
    val text: String,
    val size: Float = 60f,
    val color: Int = 0xFFC62828.toInt(),
    override val opacity: Float = 0.25f,
    override val angle: Float = 45f,
    override val layout: WatermarkLayout = WatermarkLayout.CENTER,
) : Watermark

data class ImageWatermark(
    val imagePath: String,
    /** Width relative to the page width. */
    val widthFraction: Float = 0.5f,
    override val opacity: Float = 0.3f,
    override val angle: Float = 0f,
    override val layout: WatermarkLayout = WatermarkLayout.CENTER,
) : Watermark

enum class CompressLevel(val maxSide: Int, val quality: Float) {
    LIGHT(2400, 0.85f),
    MEDIUM(1600, 0.7f),
    STRONG(1100, 0.5f),
}

data class CompressResult(val before: Long, val after: Long, val imagesChanged: Int)

object Templates {
    fun expand(template: String, page: Int, total: Int, date: String, file: String): String =
        template.replace("{n}", page.toString())
            .replace("{total}", total.toString())
            .replace("{date}", date)
            .replace("{file}", file)
}

object PageRanges {
    /**
     * Parses "1-3, 5, 8-" into zero-based ranges for a document of [pageCount] pages.
     * Returns null if the text is not valid or refers to pages outside the document.
     */
    fun parse(text: String, pageCount: Int): List<IntRange>? {
        val parts = text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return parts.map { part ->
            val m = Regex("""^(\d+)?\s*[-–—]?\s*(\d+)?$""").matchEntire(part) ?: return null
            val dash = part.contains(Regex("[-–—]"))
            val from = m.groupValues[1].toIntOrNull() ?: if (dash) 1 else return null
            val to = m.groupValues[2].toIntOrNull() ?: if (dash) pageCount else from
            if (from < 1 || to > pageCount || from > to) return null
            (from - 1)..(to - 1)
        }
    }

    /** Consecutive chunks of [size] pages. */
    fun every(size: Int, pageCount: Int): List<IntRange> =
        (0 until pageCount step size.coerceAtLeast(1)).map { it..minOf(it + size - 1, pageCount - 1) }
}
