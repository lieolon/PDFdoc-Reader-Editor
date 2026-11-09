package app.pdfdoc.data.pdf

import app.pdfdoc.domain.pdf.PageOperations
import app.pdfdoc.domain.pdf.Paper
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import java.io.File
import java.io.IOException
import javax.inject.Inject

class PdfBoxPageOperations @Inject constructor() : PageOperations {

    override fun pageCount(source: File): Int = load(source).use { it.numberOfPages }

    override fun deletePages(source: File, target: File, pages: Set<Int>) = edit(source, target) { doc ->
        require(pages.size < doc.numberOfPages) { "Cannot delete every page" }
        pages.sortedDescending().forEach { doc.removePage(it) }
    }

    override fun rotatePages(source: File, target: File, pages: Set<Int>, degrees: Int) = edit(source, target) { doc ->
        pages.forEach { i ->
            val page = doc.getPage(i)
            page.rotation = (page.rotation + degrees).mod(360)
        }
    }

    override fun movePage(source: File, target: File, from: Int, to: Int) = edit(source, target) { doc ->
        val order = (0 until doc.numberOfPages).toMutableList()
        order.add(to, order.removeAt(from))
        reorder(doc, order)
    }

    override fun extractPages(source: File, target: File, pages: List<Int>) = edit(source, target) { doc ->
        require(pages.isNotEmpty()) { "Nothing to extract" }
        reorder(doc, pages)
    }

    override fun merge(sources: List<File>, target: File) {
        require(sources.isNotEmpty()) { "Nothing to merge" }
        val tmp = tempFor(target)
        PDFMergerUtility().apply {
            sources.forEach { addSource(it) }
            destinationFileName = tmp.absolutePath
            mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
        }
        replace(tmp, target)
    }

    override fun duplicatePage(source: File, target: File, index: Int) = edit(source, target) { doc ->
        val original = doc.getPage(index)
        materializeInherited(original)
        // Shallow copy shares content and resources; an annotation can belong to one page only.
        val copy = PDPage(COSDictionary(original.cosObject).apply { removeItem(com.tom_roush.pdfbox.cos.COSName.ANNOTS) })
        doc.addPage(copy)
        val order = (0 until doc.numberOfPages - 1).toMutableList()
        order.add(index + 1, doc.numberOfPages - 1)
        reorder(doc, order)
    }

    override fun insertBlankPage(source: File, target: File, at: Int, paper: Paper) = edit(source, target) { doc ->
        val neighbour = doc.getPage((at - 1).coerceIn(0, doc.numberOfPages - 1))
        val blank = PDPage(neighbour.mediaBox).apply { rotation = neighbour.rotation }
        doc.addPage(blank)
        if (paper != Paper.BLANK) rule(doc, blank, paper)
        val order = (0 until doc.numberOfPages - 1).toMutableList()
        order.add(at.coerceIn(0, order.size), doc.numberOfPages - 1)
        reorder(doc, order)
    }

    override fun insertDocument(source: File, other: File, target: File, at: Int) {
        load(other).use { inserted ->
            edit(source, target) { doc ->
                val before = doc.numberOfPages
                inserted.pages.forEach { page ->
                    materializeInherited(page)
                    doc.importPage(page)
                }
                val added = (before until doc.numberOfPages).toList()
                val order = (0 until before).toMutableList()
                order.addAll(at.coerceIn(0, before), added)
                reorder(doc, order)
            }
        }
    }

    /** Draws notebook ruling in light blue-grey, in display space so rotated neighbours look right. */
    private fun rule(doc: PDDocument, page: PDPage, paper: Paper) {
        val g = PageGeometry.of(page)
        val w = g.displayWidth
        val h = g.displayHeight
        val margin = 36f
        PDPageContentStream(doc, page).use { cs ->
            cs.transform(g.matrix())
            cs.setStrokingColor(0.62f, 0.70f, 0.80f)
            cs.setNonStrokingColor(0.55f, 0.62f, 0.72f)
            cs.setLineWidth(0.5f)
            when (paper) {
                Paper.LINED -> {
                    var y = margin + 36f
                    while (y < h - margin) { cs.moveTo(margin, y); cs.lineTo(w - margin, y); y += 22f }
                    cs.stroke()
                    // Red margin line, like a school notebook.
                    cs.setStrokingColor(0.90f, 0.55f, 0.55f)
                    cs.moveTo(margin + 48f, margin); cs.lineTo(margin + 48f, h - margin)
                    cs.stroke()
                }
                Paper.SQUARED -> {
                    val step = 14.17f // 5 mm
                    var x = margin
                    while (x <= w - margin) { cs.moveTo(x, margin); cs.lineTo(x, h - margin); x += step }
                    var y = margin
                    while (y <= h - margin) { cs.moveTo(margin, y); cs.lineTo(w - margin, y); y += step }
                    cs.stroke()
                }
                Paper.DOTTED -> {
                    val step = 14.17f
                    var y = margin
                    while (y <= h - margin) {
                        var x = margin
                        while (x <= w - margin) { cs.addRect(x - 0.6f, y - 0.6f, 1.2f, 1.2f); x += step }
                        y += step
                    }
                    cs.fill()
                }
                Paper.MUSIC -> {
                    // Staves of five lines, 7 pt apart, with room between them.
                    var top = margin + 24f
                    while (top + 28f < h - margin) {
                        for (i in 0 until 5) {
                            val y = top + i * 7f
                            cs.moveTo(margin, y); cs.lineTo(w - margin, y)
                        }
                        top += 64f
                    }
                    cs.stroke()
                }
                Paper.BLANK -> Unit
            }
        }
    }

    /** Leaves exactly [order] pages in the document, in that order. */
    private fun reorder(doc: PDDocument, order: List<Int>) {
        val pages = doc.pages.toList()
        pages.forEach(::materializeInherited)
        pages.forEach { doc.pages.remove(it) }
        order.forEach { doc.pages.add(pages[it]) }
    }

    /**
     * Pages may inherit Resources/MediaBox/Rotate from intermediate page-tree nodes.
     * Copy them onto the page itself before re-parenting it, or they would be lost.
     */
    private fun materializeInherited(page: PDPage) {
        page.resources = page.resources
        page.mediaBox = page.mediaBox
        page.cropBox = page.cropBox
        page.rotation = page.rotation
    }

    private fun load(file: File): PDDocument =
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())

    /** PDFBox reads lazily from the source, so always save to a temp file first. */
    private inline fun edit(source: File, target: File, block: (PDDocument) -> Unit) {
        val tmp = tempFor(target)
        load(source).use { doc ->
            block(doc)
            doc.save(tmp)
        }
        replace(tmp, target)
    }

    private fun tempFor(target: File) = File(target.parentFile, "${target.name}.tmp")

    private fun replace(tmp: File, target: File) {
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        if (!target.exists()) throw IOException("Failed to write $target")
    }
}
