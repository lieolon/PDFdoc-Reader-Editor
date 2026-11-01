package app.pdfdoc.domain.text

import app.pdfdoc.domain.edit.Box
import app.pdfdoc.domain.edit.Pt
import app.pdfdoc.domain.edit.distance

/** One character of a page's text layer. Separators (spaces, line breaks) have no box. */
data class Glyph(val ch: Char, val box: Box?)

/** Text of a single page with character positions, used for search and text selection. */
class PageText(val glyphs: List<Glyph>) {

    /** Lower-cased text, index-aligned with [glyphs]. */
    private val normalized: String = buildString(glyphs.size) {
        glyphs.forEach { append(normalize(it.ch)) }
    }

    val isEmpty: Boolean get() = glyphs.none { it.box != null }

    /** Every occurrence of [query] as a list of per-line rectangles. */
    fun find(query: String): List<List<Box>> {
        val q = buildString { query.trim().forEach { append(normalize(it)) } }
        if (q.isEmpty()) return emptyList()
        val hits = mutableListOf<List<Box>>()
        var from = 0
        while (true) {
            val i = normalized.indexOf(q, from)
            if (i < 0) break
            val rects = lineRects(i, i + q.length - 1)
            if (rects.isNotEmpty()) hits += rects
            from = i + 1
        }
        return hits
    }

    /** Rectangles covering the text between the glyphs nearest to [from] and [to]. */
    fun select(from: Pt, to: Pt): List<Box> {
        val a = nearestGlyph(from) ?: return emptyList()
        val b = nearestGlyph(to) ?: return emptyList()
        return lineRects(minOf(a, b), maxOf(a, b))
    }

    /** The selected text between [from] and [to], or an empty string. */
    fun selectedText(from: Pt, to: Pt): String {
        val a = nearestGlyph(from) ?: return ""
        val b = nearestGlyph(to) ?: return ""
        return glyphs.subList(minOf(a, b), maxOf(a, b) + 1).joinToString("") { it.ch.toString() }.trim()
    }

    /** The text as it is laid out, index-aligned with [glyphs]. */
    val text: String = buildString(glyphs.size) { glyphs.forEach { append(it.ch) } }

    /** Index of the glyph nearest to [p], or null for a page without text. */
    fun indexAt(p: Pt): Int? = nearestGlyph(p)

    /** The word (letters and digits) around glyph [i]; a single glyph for punctuation. */
    fun wordRange(i: Int): IntRange {
        if (!isWordChar(i)) return i..i
        var start = i
        var end = i
        while (start > 0 && isWordChar(start - 1)) start--
        while (end < glyphs.lastIndex && isWordChar(end + 1)) end++
        return start..end
    }

    private fun isWordChar(i: Int) = glyphs[i].box != null && glyphs[i].ch.isLetterOrDigit()

    fun rects(range: IntRange): List<Box> =
        if (range.isEmpty() || glyphs.isEmpty()) emptyList()
        else lineRects(range.first.coerceAtLeast(0), range.last.coerceAtMost(glyphs.lastIndex))

    fun text(range: IntRange): String =
        if (range.isEmpty()) "" else text.substring(range.first.coerceAtLeast(0), (range.last + 1).coerceAtMost(text.length)).trim()

    /** Every match of [regex] in the page text, as per-line rectangles. */
    fun findRegex(regex: Regex): List<List<Box>> =
        regex.findAll(text).mapNotNull { m -> rects(m.range).takeIf { it.isNotEmpty() } }.toList()

    /**
     * Sentences for reading aloud: split at sentence ends and paragraph breaks, long runs cut at
     * word boundaries so the speech engine gets manageable chunks.
     */
    fun sentences(maxLength: Int = 280): List<IntRange> {
        val result = mutableListOf<IntRange>()
        var start = -1
        fun close(end: Int) {
            if (start >= 0 && text.substring(start, end + 1).any(Char::isLetterOrDigit)) result += start..end
            start = -1
        }
        for (i in text.indices) {
            val c = text[i]
            if (start < 0) {
                if (!c.isWhitespace()) start = i else continue
            }
            val next = text.getOrNull(i + 1)
            val sentenceEnd = (c == '.' || c == '!' || c == '?' || c == '…') && (next == null || next.isWhitespace())
            val paragraphEnd = c == '\n' && next == '\n'
            when {
                sentenceEnd -> close(i)
                paragraphEnd -> close(i - 1)
                i - start >= maxLength && c == ' ' -> close(i - 1)
            }
        }
        if (start >= 0) close(text.lastIndex)
        return result
    }

    private fun nearestGlyph(p: Pt): Int? {
        var best: Int? = null
        var bestDist = Float.MAX_VALUE
        glyphs.forEachIndexed { i, g ->
            val box = g.box ?: return@forEachIndexed
            val d = if (box.contains(p)) 0f else distance(box.center, p)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    /** Merges glyph boxes in [start, end] into one rectangle per text line. */
    private fun lineRects(start: Int, end: Int): List<Box> {
        val rects = mutableListOf<Box>()
        for (i in start..end) {
            val box = glyphs[i].box ?: continue
            val last = rects.lastOrNull()
            if (last != null && last.verticalOverlap(box) > 0.5f && box.left >= last.left - box.width) {
                rects[rects.lastIndex] = last.union(box)
            } else {
                rects += box
            }
        }
        return rects
    }

    private companion object {
        fun normalize(c: Char): Char = when (c) {
            '\n', '\r', '\t', ' ' -> ' '
            'ё', 'Ё' -> 'е'
            else -> c.lowercaseChar()
        }
    }
}
