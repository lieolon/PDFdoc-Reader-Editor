package app.pdfdoc.data.convert

import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

/** Files other than PDF that open in the viewer after being converted to PDF. */
enum class ImportFormat { TEXT, MARKDOWN, HTML, IMAGE, DOCX, ODT, XLSX, PPTX, EPUB, FB2, RTF }

object ImportFormats {
    /** MIME types offered by the "Open" file picker and accepted from other apps. */
    val MIME_TYPES = arrayOf(
        "application/pdf",
        "text/*",
        "image/*",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.oasis.opendocument.text",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/epub+zip",
        "application/rtf",
        "application/x-fictionbook+xml",
        "application/x-fictionbook",
        "application/json",
        "application/xml",
    )

    private val textExtensions = setOf(
        "txt", "text", "log", "csv", "tsv", "json", "xml", "ini", "cfg", "conf", "yaml", "yml", "properties",
        "srt", "kt", "java", "py", "js", "ts", "c", "h", "cpp", "cs", "sh", "sql", "css",
    )
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")

    /** The format of a file, or null for PDF and unknown files (those are opened as PDF). */
    fun detect(name: String, mime: String?): ImportFormat? {
        val ext = name.substringAfterLast('.', "").lowercase()
        val type = mime?.lowercase().orEmpty()
        if (ext == "pdf" || type == "application/pdf") return null
        return when {
            ext == "md" || ext == "markdown" || type == "text/markdown" || type == "text/x-markdown" -> ImportFormat.MARKDOWN
            ext == "html" || ext == "htm" || ext == "xhtml" || type == "text/html" || type == "application/xhtml+xml" -> ImportFormat.HTML
            ext == "docx" || type.endsWith("wordprocessingml.document") -> ImportFormat.DOCX
            ext == "odt" || type == "application/vnd.oasis.opendocument.text" -> ImportFormat.ODT
            ext == "xlsx" || type.endsWith("spreadsheetml.sheet") -> ImportFormat.XLSX
            ext == "pptx" || type.endsWith("presentationml.presentation") -> ImportFormat.PPTX
            ext == "epub" || type == "application/epub+zip" -> ImportFormat.EPUB
            ext == "fb2" || type.startsWith("application/x-fictionbook") -> ImportFormat.FB2
            ext == "rtf" || type == "application/rtf" || type == "text/rtf" -> ImportFormat.RTF
            ext in imageExtensions || type.startsWith("image/") -> ImportFormat.IMAGE
            ext in textExtensions || type.startsWith("text/") || type == "application/json" || type == "application/xml" -> ImportFormat.TEXT
            else -> null
        }
    }
}

/** Decodes text files in UTF-8/UTF-16 (with or without BOM) or the legacy Cyrillic or Western code pages. */
object TextDecoding {
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        utf16WithoutBom(bytes)?.let { return it }
        strict(bytes, Charsets.UTF_8)?.let { return it }
        // Cyrillic text in windows-1251 is mostly bytes 0xC0..0xFF; Western text has a few accents among ASCII letters.
        val high = bytes.count { (it.toInt() and 0xFF) >= 0xC0 }
        val ascii = bytes.count { it in 'A'.code..'Z'.code || it in 'a'.code..'z'.code }
        return String(bytes, Charset.forName(if (high > ascii * 0.3) "windows-1251" else "windows-1252"))
    }

    private fun strict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** UTF-16 text has one byte of each pair almost always small (0x00 for Latin, 0x04 for Cyrillic). */
    private fun utf16WithoutBom(bytes: ByteArray): String? {
        if (bytes.size < 4) return null
        val sample = bytes.size.coerceAtMost(4096) and 1.inv()
        var evenSmall = 0
        var oddSmall = 0
        for (i in 0 until sample) if ((bytes[i].toInt() and 0xFF) < 0x06) if (i % 2 == 0) evenSmall++ else oddSmall++
        val half = sample / 2
        return when {
            oddSmall > half * 0.7 && evenSmall < half * 0.1 -> String(bytes, Charsets.UTF_16LE)
            evenSmall > half * 0.7 && oddSmall < half * 0.1 -> String(bytes, Charsets.UTF_16BE)
            else -> null
        }
    }
}

/** A forgiving tokenizer for XML and HTML: tags and the text between them, entities decoded. */
internal object Markup {
    sealed interface Token
    data class Tag(val name: String, val attrs: Map<String, String>, val closing: Boolean, val selfClosing: Boolean) : Token
    data class Text(val text: String) : Token

    private val tagRegex = Regex("<(/?)([A-Za-z_][\\w:.-]*)([^>]*?)(/?)>", RegexOption.DOT_MATCHES_ALL)
    private val attrRegex = Regex("([\\w:.-]+)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s\"'>]+)")
    private val skipRegex = Regex("<!--.*?-->|<!\\[CDATA\\[(.*?)]]>|<\\?.*?\\?>|<!DOCTYPE[^>]*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    fun tokens(source: String): List<Token> {
        val text = skipRegex.replace(source) { m -> m.groups[1]?.value?.let(::escape) ?: "" }
        val out = mutableListOf<Token>()
        var pos = 0
        for (m in tagRegex.findAll(text)) {
            if (m.range.first > pos) out += Text(decode(text.substring(pos, m.range.first)))
            val attrs = attrRegex.findAll(m.groupValues[3]).associate { a ->
                a.groupValues[1].lowercase() to decode(a.groupValues[2].trim('"', '\''))
            }
            out += Tag(m.groupValues[2].lowercase(), attrs, m.groupValues[1] == "/", m.groupValues[4] == "/")
            pos = m.range.last + 1
        }
        if (pos < text.length) out += Text(decode(text.substring(pos)))
        return out
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "shy" to "",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "laquo" to "«", "raquo" to "»", "bdquo" to "„",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’", "copy" to "©", "reg" to "®",
        "trade" to "™", "deg" to "°", "middot" to "·", "bull" to "•", "times" to "×", "euro" to "€", "sect" to "§",
    )
    private val entityRegex = Regex("&(#[xX][0-9a-fA-F]+|#\\d+|[a-zA-Z]+);")

    fun decode(s: String): String = if ('&' !in s) s else entityRegex.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let(::codePoint)
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let(::codePoint)
            else -> named[e]
        } ?: m.value
    }

    private fun codePoint(c: Int) = runCatching { String(Character.toChars(c)) }.getOrNull()
}

/** Builds paragraphs from a stream of text pieces; whitespace inside a paragraph collapses like in HTML. */
private class BlockBuilder(private val collapse: Boolean = true) {
    val blocks = mutableListOf<TextBlock>()
    private val text = StringBuilder()
    var heading = 0
    var bullet = false

    fun append(s: String) {
        if (!collapse) {
            text.append(s)
            return
        }
        for (c in s) {
            val space = c == ' ' || c == '\t' || c == '\n' || c == '\r'
            if (space) {
                if (text.isNotEmpty() && text.last() != ' ' && text.last() != '\n') text.append(' ')
            } else {
                text.append(c)
            }
        }
    }

    /** A hard line break that survives whitespace collapsing. */
    fun lineBreak() {
        while (text.isNotEmpty() && text.last() == ' ') text.setLength(text.length - 1)
        text.append('\n')
    }

    fun flush() {
        val value = text.toString().lines().joinToString("\n") { it.trim() }.trim('\n', ' ')
        if (value.isNotBlank()) blocks += TextBlock(value, heading = heading, bullet = bullet)
        text.clear()
        heading = 0
        bullet = false
    }
}

object HtmlText {
    private val blockTags = setOf(
        "p", "div", "section", "article", "header", "footer", "blockquote", "pre", "table", "tr", "ul", "ol",
        "dl", "dt", "dd", "figure", "figcaption", "aside", "nav", "main", "hr", "address", "body",
    )
    private val skipTags = setOf("head", "script", "style", "title", "noscript", "svg", "template")

    fun blocks(html: String): List<TextBlock> {
        val b = BlockBuilder()
        var skip = 0
        var pre = 0
        for (t in Markup.tokens(html)) when (t) {
            is Markup.Text -> if (skip == 0) {
                if (pre > 0) t.text.split('\n').forEachIndexed { i, line -> if (i > 0) b.lineBreak(); b.append(line.replace(' ', '\u00A0')) }
                else b.append(t.text)
            }
            is Markup.Tag -> {
                val n = t.name.substringAfter(':')
                when {
                    n in skipTags -> if (!t.selfClosing) skip = (skip + if (t.closing) -1 else 1).coerceAtLeast(0)
                    skip > 0 -> Unit
                    n == "br" -> b.lineBreak()
                    n.length == 2 && n[0] == 'h' && n[1] in '1'..'6' -> {
                        b.flush()
                        if (!t.closing) b.heading = (n[1] - '0').coerceAtMost(3)
                    }
                    n == "li" -> {
                        b.flush()
                        if (!t.closing) b.bullet = true
                    }
                    n == "td" || n == "th" -> if (t.closing) b.append("   ")
                    n in blockTags -> {
                        b.flush()
                        if (n == "pre") pre = (pre + if (t.closing) -1 else 1).coerceAtLeast(0)
                    }
                }
            }
        }
        b.flush()
        return b.blocks.map { it.copy(text = it.text.replace('\u00A0', ' ')) }
    }
}

/** Text of Word (.docx) and OpenDocument (.odt) files: paragraphs, headings and list items. */
object OfficeText {
    fun docx(zip: ByteArray): List<TextBlock> = docxXml(String(entry(zip, "word/document.xml") ?: error("Not a DOCX file"), Charsets.UTF_8))

    fun odt(zip: ByteArray): List<TextBlock> = odtXml(String(entry(zip, "content.xml") ?: error("Not an ODT file"), Charsets.UTF_8))

    /**
     * Excel workbook: a heading per sheet and one monospaced line per row, cells separated by " | ".
     * Formulas show their last calculated value, as Excel saved it.
     */
    fun xlsx(zip: ByteArray, sheetLabel: (Int) -> String = { "Sheet $it" }): List<TextBlock> {
        val files = entries(zip)
        val shared = files["xl/sharedStrings.xml"]?.let { sharedStrings(String(it, Charsets.UTF_8)) }.orEmpty()
        val names = files["xl/workbook.xml"]?.let { wb ->
            Markup.tokens(String(wb, Charsets.UTF_8)).filterIsInstance<Markup.Tag>().filter { it.name == "sheet" && !it.closing }
                .mapNotNull { it.attrs["name"] }
        }.orEmpty()
        val sheets = files.keys.filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .sortedBy { it.removePrefix("xl/worksheets/sheet").removeSuffix(".xml").toIntOrNull() ?: Int.MAX_VALUE }
        if (sheets.isEmpty()) error("Not an XLSX file")
        val out = mutableListOf<TextBlock>()
        sheets.forEachIndexed { i, name ->
            val rows = sheetRows(String(files.getValue(name), Charsets.UTF_8), shared)
            if (rows.isEmpty()) return@forEachIndexed
            out += TextBlock(names.getOrNull(i) ?: sheetLabel(i + 1), heading = 2)
            rows.forEach { out += TextBlock(it.joinToString(" | ").trimEnd(' ', '|'), code = true) }
        }
        return out
    }

    private fun sharedStrings(xml: String): List<String> {
        val list = mutableListOf<String>()
        val current = StringBuilder()
        var inText = false
        for (t in Markup.tokens(xml)) when (t) {
            is Markup.Text -> if (inText) current.append(Markup.decode(t.text))
            is Markup.Tag -> when (t.name) {
                "t" -> inText = !t.closing && !t.selfClosing
                "si" -> if (t.closing) { list += current.toString(); current.clear() } else current.clear()
            }
        }
        return list
    }

    private fun sheetRows(xml: String, shared: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = sortedMapOf<Int, String>()
        var col = 0
        var type = ""
        var inValue = false
        val value = StringBuilder()
        for (t in Markup.tokens(xml)) when (t) {
            is Markup.Text -> if (inValue) value.append(Markup.decode(t.text))
            is Markup.Tag -> when (t.name) {
                "row" -> if (t.closing) {
                    if (row.values.any { it.isNotBlank() }) {
                        val last = row.lastKey()
                        rows += (0..last).map { row[it].orEmpty() }
                    }
                    row = sortedMapOf()
                } else if (!t.selfClosing) row = sortedMapOf()
                "c" -> if (!t.closing) {
                    col = column(t.attrs["r"].orEmpty()) ?: (row.keys.maxOrNull()?.plus(1) ?: 0)
                    type = t.attrs["t"].orEmpty()
                    value.clear()
                } else {
                    val raw = value.toString()
                    row[col] = when (type) {
                        "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                        "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
                        else -> raw
                    }
                }
                "v", "t" -> { inValue = !t.closing && !t.selfClosing }
            }
        }
        return rows
    }

    /** "B12" -> 1 (zero-based column). */
    private fun column(ref: String): Int? {
        val letters = ref.takeWhile { it.isLetter() }.uppercase()
        if (letters.isEmpty()) return null
        return letters.fold(0) { acc, c -> acc * 26 + (c - 'A' + 1) } - 1
    }

    /** PowerPoint: a heading per slide, then its text paragraphs in order. */
    fun pptx(zip: ByteArray, slideLabel: (Int) -> String = { "Slide $it" }): List<TextBlock> {
        val files = entries(zip)
        val slides = files.keys.filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
            .sortedBy { it.removePrefix("ppt/slides/slide").removeSuffix(".xml").toIntOrNull() ?: Int.MAX_VALUE }
        if (slides.isEmpty()) error("Not a PPTX file")
        val out = mutableListOf<TextBlock>()
        slides.forEachIndexed { i, name ->
            out += TextBlock(slideLabel(i + 1), heading = 2)
            val b = BlockBuilder(collapse = false)
            var inText = false
            for (t in Markup.tokens(String(files.getValue(name), Charsets.UTF_8))) when (t) {
                is Markup.Text -> if (inText) b.append(t.text)
                is Markup.Tag -> when (t.name) {
                    "a:p" -> if (t.closing || t.selfClosing) b.flush()
                    "a:t" -> inText = !t.closing && !t.selfClosing
                    "a:br" -> if (!t.closing) b.lineBreak()
                    "a:bunone" -> Unit
                    "a:buchar", "a:buautonum" -> if (!t.closing) b.bullet = true
                }
            }
            b.flush()
            out += b.blocks
        }
        return out
    }

    fun docxXml(xml: String): List<TextBlock> {
        val b = BlockBuilder(collapse = false)
        var inText = false
        var deleted = 0
        for (t in Markup.tokens(xml)) when (t) {
            is Markup.Text -> if (inText && deleted == 0) b.append(t.text)
            is Markup.Tag -> when (t.name) {
                "w:p" -> if (t.closing || t.selfClosing) b.flush()
                "w:t" -> inText = !t.closing && !t.selfClosing
                "w:del" -> if (!t.selfClosing) deleted = (deleted + if (t.closing) -1 else 1).coerceAtLeast(0)
                "w:tab" -> if (!t.closing) b.append("\t")
                "w:br", "w:cr" -> if (!t.closing) b.lineBreak()
                "w:tc" -> if (t.closing) b.append("   ")
                "w:pstyle" -> {
                    val style = t.attrs["w:val"].orEmpty().lowercase()
                    b.heading = when {
                        style == "title" -> 1
                        style.startsWith("heading") -> style.removePrefix("heading").trim().toIntOrNull()?.coerceIn(1, 3) ?: 0
                        else -> b.heading
                    }
                }
                "w:numpr" -> if (!t.closing) b.bullet = true
            }
        }
        b.flush()
        return b.blocks
    }

    fun odtXml(xml: String): List<TextBlock> {
        val b = BlockBuilder(collapse = false)
        var list = 0
        var inBody = false
        var pending = 0
        for (t in Markup.tokens(xml)) when (t) {
            is Markup.Text -> if (inBody && pending > 0) b.append(t.text.replace(Regex("\\s+"), " "))
            is Markup.Tag -> when (t.name) {
                "office:text" -> inBody = !t.closing
                "text:h", "text:p" -> {
                    if (t.selfClosing) continue
                    if (t.closing) {
                        pending--
                        b.flush()
                    } else {
                        b.flush()
                        pending++
                        if (t.name == "text:h") b.heading = t.attrs["text:outline-level"]?.toIntOrNull()?.coerceIn(1, 3) ?: 1
                        if (list > 0) b.bullet = true
                    }
                }
                "text:list" -> if (!t.selfClosing) list = (list + if (t.closing) -1 else 1).coerceAtLeast(0)
                "text:s" -> b.append(" ".repeat(t.attrs["text:c"]?.toIntOrNull() ?: 1))
                "text:tab" -> b.append("\t")
                "text:line-break" -> b.lineBreak()
                "table:table-cell" -> if (t.closing) b.append("   ")
            }
        }
        b.flush()
        return b.blocks
    }

    internal fun entries(zip: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) out[e.name] = z.readBytes()
            }
        }
        return out
    }

    private fun entry(zip: ByteArray, name: String): ByteArray? {
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: return null
                if (e.name == name) return z.readBytes()
            }
        }
    }
}

/** E-books: EPUB chapters in reading order, and FictionBook (.fb2). */
object BookText {
    data class Book(val title: String?, val blocks: List<TextBlock>)

    fun epub(zip: ByteArray): Book {
        val files = OfficeText.entries(zip)
        fun text(name: String) = files[name]?.let { TextDecoding.decode(it) }
        val container = text("META-INF/container.xml") ?: error("Not an EPUB file")
        val opfPath = Markup.tokens(container).filterIsInstance<Markup.Tag>().firstOrNull { it.name == "rootfile" }?.attrs?.get("full-path")
            ?: error("No package file")
        val opf = text(opfPath) ?: error("No package file")
        val base = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val tags = Markup.tokens(opf)
        val manifest = tags.filterIsInstance<Markup.Tag>().filter { it.name.substringAfter(':') == "item" }
            .associate { it.attrs["id"].orEmpty() to it.attrs["href"].orEmpty() }
        val spine = tags.filterIsInstance<Markup.Tag>().filter { it.name.substringAfter(':') == "itemref" }.mapNotNull { manifest[it.attrs["idref"]] }
        val title = tags.indexOfFirst { it is Markup.Tag && it.name == "dc:title" && !it.closing }
            .takeIf { it >= 0 }?.let { (tags.getOrNull(it + 1) as? Markup.Text)?.text?.trim() }?.takeIf { it.isNotEmpty() }
        val blocks = spine.flatMap { href ->
            val path = resolve(base, URLDecoder.decode(href.substringBefore('#'), "UTF-8"))
            text(path)?.let(HtmlText::blocks).orEmpty()
        }
        return Book(title, blocks)
    }

    fun fb2(xml: String): Book {
        val b = BlockBuilder()
        var inBody = false
        var skip = 0
        var depth = 0
        var titleLevel = 0
        var bookTitle: String? = null
        var inBookTitle = false
        for (t in Markup.tokens(xml)) when (t) {
            is Markup.Text -> {
                if (inBookTitle) bookTitle = (bookTitle.orEmpty() + t.text).trim()
                if (inBody && skip == 0) b.append(t.text)
            }
            is Markup.Tag -> when (t.name) {
                "book-title" -> inBookTitle = !t.closing && !t.selfClosing
                "body" -> {
                    b.flush()
                    inBody = !t.closing
                }
                "binary", "description" -> if (!t.selfClosing) skip = (skip + if (t.closing) -1 else 1).coerceAtLeast(0)
                "section" -> {
                    b.flush()
                    depth = (depth + if (t.closing) -1 else 1).coerceAtLeast(0)
                }
                "title" -> {
                    b.flush()
                    titleLevel = if (t.closing) 0 else (depth + 1).coerceIn(1, 3)
                }
                "subtitle" -> {
                    b.flush()
                    if (!t.closing) b.heading = 3
                }
                "p", "v", "text-author" -> {
                    b.flush()
                    if (titleLevel > 0 && !t.closing) b.heading = titleLevel
                }
                "empty-line" -> b.flush()
            }
        }
        b.flush()
        return Book(bookTitle, b.blocks)
    }

    private fun resolve(base: String, href: String): String {
        val parts = ArrayDeque<String>()
        (base + href).split('/').forEach {
            when (it) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(it)
            }
        }
        return parts.joinToString("/")
    }
}

/** Plain text of an RTF document: paragraphs, tabs, Unicode and code-page characters; formatting is dropped. */
object RtfText {
    private val ignoredDestinations = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "header", "footer", "headerl", "headerr",
        "footerl", "footerr", "xmlnstbl", "listtable", "listoverridetable", "rsidtbl", "generator", "themedata",
        "colorschememapping", "latentstyles", "datastore", "fldinst", "filetbl", "revtbl", "pgdsctbl",
    )

    fun text(rtf: String): String {
        val out = StringBuilder()
        var charset = Charset.forName("windows-1252")
        val bytes = java.io.ByteArrayOutputStream()
        fun flushBytes() {
            if (bytes.size() > 0) {
                out.append(String(bytes.toByteArray(), charset))
                bytes.reset()
            }
        }
        // Each group remembers whether it is skipped and how many fallback characters follow \u.
        data class Group(val skip: Boolean, val uc: Int)
        val stack = ArrayDeque<Group>()
        var skip = false
        var uc = 1
        var pendingSkip = 0
        var i = 0
        while (i < rtf.length) {
            val c = rtf[i]
            when {
                c == '{' -> {
                    flushBytes()
                    stack.addLast(Group(skip, uc))
                    i++
                }
                c == '}' -> {
                    flushBytes()
                    stack.removeLastOrNull()?.let { skip = it.skip; uc = it.uc }
                    i++
                }
                c == '\\' && i + 1 < rtf.length -> {
                    val next = rtf[i + 1]
                    when {
                        next == '\'' && i + 3 < rtf.length -> {
                            val value = rtf.substring(i + 2, i + 4).toIntOrNull(16)
                            if (pendingSkip > 0) pendingSkip-- else if (!skip && value != null) bytes.write(value)
                            i += 4
                        }
                        next == '*' -> {
                            skip = true
                            i += 2
                        }
                        next == '\\' || next == '{' || next == '}' -> {
                            if (!skip) { flushBytes(); out.append(next) }
                            i += 2
                        }
                        next == '~' -> { if (!skip) { flushBytes(); out.append(' ') }; i += 2 }
                        next == '-' || next == '_' -> { if (!skip && next == '_') { flushBytes(); out.append('-') }; i += 2 }
                        next == '\n' || next == '\r' -> { if (!skip) { flushBytes(); out.append('\n') }; i += 2 }
                        next.isLetter() -> {
                            var j = i + 1
                            while (j < rtf.length && rtf[j].isLetter()) j++
                            val word = rtf.substring(i + 1, j)
                            var k = j
                            if (k < rtf.length && (rtf[k] == '-' || rtf[k].isDigit())) {
                                k++
                                while (k < rtf.length && rtf[k].isDigit()) k++
                            }
                            val param = rtf.substring(j, k).toIntOrNull()
                            if (k < rtf.length && rtf[k] == ' ') k++
                            i = k
                            if (word in ignoredDestinations) skip = true
                            if (skip) {
                                if (word == "uc" && param != null) uc = param
                                continue
                            }
                            when (word) {
                                "par", "line", "sect", "page" -> { flushBytes(); out.append('\n') }
                                "tab", "cell" -> { flushBytes(); out.append('\t') }
                                "row" -> { flushBytes(); out.append('\n') }
                                "emdash" -> { flushBytes(); out.append('—') }
                                "endash" -> { flushBytes(); out.append('–') }
                                "bullet" -> { flushBytes(); out.append('•') }
                                "lquote" -> { flushBytes(); out.append('‘') }
                                "rquote" -> { flushBytes(); out.append('’') }
                                "ldblquote" -> { flushBytes(); out.append('“') }
                                "rdblquote" -> { flushBytes(); out.append('”') }
                                "uc" -> uc = param ?: 1
                                "u" -> if (param != null) {
                                    flushBytes()
                                    out.append((if (param < 0) param + 65536 else param).toChar())
                                    pendingSkip = uc
                                }
                                "ansicpg" -> if (param != null) charset = runCatching { Charset.forName("windows-$param") }.getOrDefault(charset)
                            }
                        }
                        else -> i += 2
                    }
                }
                c == '\r' || c == '\n' -> i++
                else -> {
                    if (pendingSkip > 0) pendingSkip-- else if (!skip) { flushBytes(); out.append(c) }
                    i++
                }
            }
        }
        flushBytes()
        return out.toString().lines().joinToString("\n") { it.trimEnd() }.trim()
    }

    /** Lines become paragraphs; RTF has no blank-line convention. */
    fun blocks(rtf: String): List<TextBlock> = text(rtf).split('\n').filter { it.isNotBlank() }.map { TextBlock(it.replace('\t', ' ').trim()) }
}
