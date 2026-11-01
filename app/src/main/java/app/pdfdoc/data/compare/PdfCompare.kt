package app.pdfdoc.data.compare

import app.pdfdoc.data.pdf.AnnotationIO
import app.pdfdoc.data.pdf.TextExtractor
import app.pdfdoc.domain.edit.Box
import app.pdfdoc.domain.edit.MarkupEdit
import app.pdfdoc.domain.edit.MarkupKind
import app.pdfdoc.domain.edit.NoteEdit
import app.pdfdoc.domain.edit.Pt
import app.pdfdoc.domain.text.PageText
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import javax.inject.Inject

/** One difference: text only in the old document, only in the new one, or replaced. */
data class TextChange(
    val oldPage: Int,
    val newPage: Int,
    val removed: String,
    val added: String,
    /** Where the added text is in the new document, or where the removal happened. */
    val newRects: List<Box>,
)

data class CompareResult(val changes: List<TextChange>, val oldPages: Int, val newPages: Int)

/** Word-level comparison of the text of two PDFs. */
class PdfCompare @Inject constructor(
    private val annotations: AnnotationIO,
) {
    private data class Word(val text: String, val page: Int, val range: IntRange)
    private data class Line(val key: String, val words: List<Word>)

    fun compare(old: File, new: File, progress: (Int) -> Unit = {}): CompareResult {
        val (oldLines, oldTexts) = lines(old)
        progress(40)
        val (newLines, newTexts) = lines(new)
        progress(60)
        val changes = mutableListOf<TextChange>()
        val ops = Diff.diff(oldLines.map { it.key }, newLines.map { it.key }, MAX_LINE_EDITS)
        progress(80)
        // Consecutive deleted and inserted lines form a block compared word by word.
        var i = 0
        while (i < ops.size) {
            if (ops[i] is Diff.Op.Equal) {
                i++
                continue
            }
            val removed = mutableListOf<Word>()
            val added = mutableListOf<Word>()
            var lastOld = (ops.getOrNull(i - 1) as? Diff.Op.Equal)?.let { oldLines[it.a].words.lastOrNull() }
            var lastNew = (ops.getOrNull(i - 1) as? Diff.Op.Equal)?.let { newLines[it.b].words.lastOrNull() }
            while (i < ops.size && ops[i] !is Diff.Op.Equal) {
                when (val op = ops[i]) {
                    is Diff.Op.Delete -> removed += oldLines[op.a].words
                    is Diff.Op.Insert -> added += newLines[op.b].words
                    else -> Unit
                }
                i++
            }
            if (lastOld == null) lastOld = removed.firstOrNull()
            if (lastNew == null) lastNew = added.firstOrNull() ?: newLines.firstOrNull()?.words?.firstOrNull()
            changes += wordChanges(removed, added, lastOld, lastNew, newTexts)
        }
        progress(100)
        return CompareResult(changes, oldTexts.size, newTexts.size)
    }

    private fun wordChanges(removed: List<Word>, added: List<Word>, anchorOld: Word?, anchorNew: Word?, newTexts: List<PageText?>): List<TextChange> {
        val result = mutableListOf<TextChange>()
        val ops = if (removed.size.toLong() * added.size <= MAX_WORD_CELLS) Diff.diff(removed.map { it.text }, added.map { it.text }, Int.MAX_VALUE)
        else removed.indices.map { Diff.Op.Delete(it) } + added.indices.map { Diff.Op.Insert(it) }
        var i = 0
        var previousOld = anchorOld
        var previousNew = anchorNew
        while (i < ops.size) {
            val op = ops[i]
            if (op is Diff.Op.Equal) {
                previousOld = removed[op.a]
                previousNew = added[op.b]
                i++
                continue
            }
            val del = mutableListOf<Word>()
            val ins = mutableListOf<Word>()
            while (i < ops.size && ops[i] !is Diff.Op.Equal) {
                when (val o = ops[i]) {
                    is Diff.Op.Delete -> del += removed[o.a]
                    is Diff.Op.Insert -> ins += added[o.b]
                    else -> Unit
                }
                i++
            }
            val rects = if (ins.isNotEmpty()) {
                ins.groupBy { it.page }.entries.first().let { (page, words) ->
                    newTexts.getOrNull(page)?.rects(words.first().range.first..words.last().range.last).orEmpty()
                }
            } else {
                previousNew?.let { w -> newTexts.getOrNull(w.page)?.rects(w.range).orEmpty() }.orEmpty()
            }
            result += TextChange(
                oldPage = (del.firstOrNull() ?: previousOld)?.page ?: 0,
                newPage = (ins.firstOrNull() ?: previousNew)?.page ?: 0,
                removed = del.joinToString(" ") { it.text },
                added = ins.joinToString(" ") { it.text },
                newRects = rects,
            )
        }
        return result
    }

    /** Writes a copy of [new] where additions are highlighted and removals are noted. */
    fun annotate(new: File, target: File, result: CompareResult, removedLabel: String, imageDir: File) {
        PDDocument.load(new, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            val edits = annotations.read(doc, imageDir).toMutableList()
            var id = (edits.maxOfOrNull { it.id } ?: 0) + 1
            result.changes.forEach { c ->
                if (c.added.isNotEmpty() && c.newRects.isNotEmpty()) {
                    edits += MarkupEdit(id++, c.newPage, MarkupKind.HIGHLIGHT, c.newRects, ADDED_COLOR)
                }
                if (c.removed.isNotEmpty()) {
                    val anchor = c.newRects.firstOrNull()
                    val at = anchor?.let { Pt((it.right + 2f).coerceAtMost(it.right + 2f), (it.top - 4f).coerceAtLeast(0f)) } ?: Pt(8f, 8f)
                    edits += NoteEdit(id++, c.newPage, at, "$removedLabel: ${c.removed}", REMOVED_COLOR)
                }
            }
            annotations.write(doc, edits)
            doc.save(target)
        }
    }

    private fun lines(file: File): Pair<List<Line>, List<PageText?>> =
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            val texts = (0 until doc.numberOfPages).map { runCatching { TextExtractor.extract(doc, it) }.getOrNull() }
            val lines = mutableListOf<Line>()
            texts.forEachIndexed { page, text ->
                text ?: return@forEachIndexed
                var words = mutableListOf<Word>()
                var start = -1
                fun endWord(end: Int) {
                    if (start >= 0) words += Word(text.text.substring(start, end + 1), page, start..end)
                    start = -1
                }
                fun endLine() {
                    if (words.isNotEmpty()) lines += Line(words.joinToString(" ") { it.text }, words)
                    words = mutableListOf()
                }
                text.glyphs.forEachIndexed { i, g ->
                    when {
                        g.ch == '\n' -> { endWord(i - 1); endLine() }
                        g.box == null || g.ch.isWhitespace() -> endWord(i - 1)
                        else -> if (start < 0) start = i
                    }
                }
                endWord(text.glyphs.lastIndex)
                endLine()
            }
            lines to texts
        }

    private companion object {
        const val MAX_LINE_EDITS = 3000
        const val MAX_WORD_CELLS = 4_000_000L
        const val ADDED_COLOR = 0xFF66BB6A.toInt()
        const val REMOVED_COLOR = 0xFFEF9A9A.toInt()
    }
}

/** Myers' O(ND) difference algorithm. */
object Diff {
    sealed interface Op {
        data class Equal(val a: Int, val b: Int) : Op
        data class Delete(val a: Int) : Op
        data class Insert(val b: Int) : Op
    }

    /**
     * Edit script turning [a] into [b]. When more than [maxEdits] edits would be needed,
     * the differing middle part is reported as fully replaced.
     */
    fun <T> diff(a: List<T>, b: List<T>, maxEdits: Int): List<Op> {
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++
        val midA = a.subList(prefix, a.size - suffix)
        val midB = b.subList(prefix, b.size - suffix)
        val result = ArrayList<Op>()
        repeat(prefix) { result += Op.Equal(it, it) }
        val middle = myers(midA, midB, maxEdits)
            ?: (midA.indices.map { Op.Delete(it) } + midB.indices.map { Op.Insert(it) })
        middle.forEach { op ->
            result += when (op) {
                is Op.Equal -> Op.Equal(op.a + prefix, op.b + prefix)
                is Op.Delete -> Op.Delete(op.a + prefix)
                is Op.Insert -> Op.Insert(op.b + prefix)
            }
        }
        repeat(suffix) { result += Op.Equal(a.size - suffix + it, b.size - suffix + it) }
        return result
    }

    private fun <T> myers(a: List<T>, b: List<T>, maxEdits: Int): List<Op>? {
        val n = a.size
        val m = b.size
        val max = n + m
        if (max == 0) return emptyList()
        val limit = minOf(max, maxEdits)
        val offset = limit + 1
        var v = IntArray(2 * limit + 3)
        val trace = ArrayList<IntArray>()
        var found = -1
        for (d in 0..limit) {
            trace += v.copyOf()
            for (k in -d..d step 2) {
                var x = if (k == -d || (k != d && v[k - 1 + offset] < v[k + 1 + offset])) v[k + 1 + offset] else v[k - 1 + offset] + 1
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) {
                    x++
                    y++
                }
                v[k + offset] = x
                if (x >= n && y >= m) {
                    found = d
                    break
                }
            }
            if (found >= 0) break
        }
        if (found < 0) return null
        trace += v
        // Walk back through the saved frontiers.
        val ops = ArrayList<Op>()
        var x = n
        var y = m
        for (d in found downTo 1) {
            val prev = trace[d]
            val k = x - y
            val prevK = if (k == -d || (k != d && prev[k - 1 + offset] < prev[k + 1 + offset])) k + 1 else k - 1
            val prevX = prev[prevK + offset]
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) {
                ops += Op.Equal(--x, --y)
            }
            if (x == prevX) ops += Op.Insert(--y) else ops += Op.Delete(--x)
        }
        while (x > 0 && y > 0) ops += Op.Equal(--x, --y)
        ops.reverse()
        return ops
    }
}
