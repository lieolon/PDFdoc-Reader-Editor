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
