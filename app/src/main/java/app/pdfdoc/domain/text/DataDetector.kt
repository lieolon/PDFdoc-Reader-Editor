package app.pdfdoc.domain.text

/** Kinds of useful data found in document text, each with its own action (call, write, add to calendar…). */
enum class DataKind { PHONE, EMAIL, LINK, DATE, AMOUNT }

data class FoundData(val kind: DataKind, val text: String, val page: Int)

/** Finds phones, e-mails, links, dates and money amounts in plain text, offline. */
object DataDetector {
    private val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val link = Regex("""\b(?:https?://|www\.)[^\s<>"')\]]+""", RegexOption.IGNORE_CASE)
    // International or local numbers with 7–15 digits, allowing spaces, dashes, dots and brackets.
    private val phone = Regex("""(?<![\w.])\+?\d[\d\s().-]{5,18}\d(?![\w])""")
    private val date = Regex(
        """\b(?:\d{1,2}[./-]\d{1,2}[./-](?:\d{4}|\d{2})|\d{4}-\d{2}-\d{2}|\d{1,2}\s+(?:января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря|January|February|March|April|May|June|July|August|September|October|November|December)\s+\d{4})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val amount = Regex(
        """(?:[$€£₽₴]\s?\d[\d\s.,]*\d|\b\d[\d\s.,]*\d?\s?(?:₽|руб\.?|рублей|грн\.?|USD|EUR|€|\$|₴))""",
        RegexOption.IGNORE_CASE,
    )

    /** [pages] is the text of each page; results keep page order and drop duplicates. */
    fun find(pages: List<String>): List<FoundData> {
        val seen = HashSet<Pair<DataKind, String>>()
        val out = mutableListOf<FoundData>()
        fun add(kind: DataKind, raw: String, page: Int) {
            val text = raw.trim().trimEnd('.', ',', ';', ':')
            if (text.isNotEmpty() && seen.add(kind to normalize(kind, text))) out += FoundData(kind, text, page)
        }
        pages.forEachIndexed { page, text ->
            val emails = email.findAll(text).map { it.value }.toList()
            emails.forEach { add(DataKind.EMAIL, it, page) }
            link.findAll(text).forEach { m -> if (emails.none { m.value.contains(it) }) add(DataKind.LINK, m.value, page) }
            val dates = date.findAll(text).map { it.range }.toList()
            dates.forEach { add(DataKind.DATE, text.substring(it), page) }
            amount.findAll(text).forEach { add(DataKind.AMOUNT, it.value, page) }
            phone.findAll(text).forEach { m ->
                val digits = m.value.count(Char::isDigit)
                val overlapsDate = dates.any { it.first <= m.range.last && m.range.first <= it.last }
                if (digits in 7..15 && !overlapsDate && !looksLikeAmount(m.value)) add(DataKind.PHONE, m.value, page)
            }
        }
        return out
    }

    private fun looksLikeAmount(s: String) = Regex("""\d[.,]\d{2}$""").containsMatchIn(s.trim()) && !s.trim().startsWith("+")

    private fun normalize(kind: DataKind, text: String) = when (kind) {
        DataKind.PHONE -> text.filter { it.isDigit() || it == '+' }
        else -> text.lowercase()
    }

    /** Parses a found date into (year, month 1–12, day), or null. */
    fun parseDate(text: String): Triple<Int, Int, Int>? {
        Regex("""^(\d{4})-(\d{2})-(\d{2})$""").matchEntire(text)?.let { m ->
            return Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        Regex("""^(\d{1,2})[./-](\d{1,2})[./-](\d{2,4})$""").matchEntire(text)?.let { m ->
            val y = m.groupValues[3].toInt().let { if (it < 100) 2000 + it else it }
            val mo = m.groupValues[2].toInt()
            val d = m.groupValues[1].toInt()
            return if (mo in 1..12 && d in 1..31) Triple(y, mo, d) else null
        }
        val months = listOf(
            listOf("января", "january"), listOf("февраля", "february"), listOf("марта", "march"), listOf("апреля", "april"),
            listOf("мая", "may"), listOf("июня", "june"), listOf("июля", "july"), listOf("августа", "august"),
            listOf("сентября", "september"), listOf("октября", "october"), listOf("ноября", "november"), listOf("декабря", "december"),
        )
        Regex("""^(\d{1,2})\s+(\p{L}+)\s+(\d{4})$""").matchEntire(text)?.let { m ->
            val mo = months.indexOfFirst { m.groupValues[2].lowercase() in it } + 1
            return if (mo > 0) Triple(m.groupValues[3].toInt(), mo, m.groupValues[1].toInt()) else null
        }
        return null
    }
}
