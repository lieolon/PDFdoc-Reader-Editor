package app.pdfdoc.data.pdf

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import java.io.File
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject

enum class CommentKind { NOTE, TEXT, MARKUP, DRAWING, STAMP, OTHER }

data class CommentReply(val author: String?, val text: String, val date: Calendar?)

/**
 * One annotation of the document as shown in the comments panel.
 * [page] and [index] address it inside the file it was read from.
 */
data class Comment(
    val page: Int,
    val index: Int,
    val kind: CommentKind,
    val subtype: String,
    val text: String,
    val author: String?,
    val date: Calendar?,
    val replies: List<CommentReply>,
)

/** Reads, deletes and answers annotations ("comments") of any origin. */
class PdfComments @Inject constructor() {

    fun read(file: File): List<Comment> = load(file).use { doc ->
        val result = mutableListOf<Comment>()
        doc.pages.forEachIndexed { pageIndex, page ->
            val annotations = runCatching { page.annotations }.getOrDefault(emptyList())
            val replies = annotations.filter { it.cosObject.containsKey(IRT) }
                .groupBy { it.cosObject.getDictionaryObject(IRT) }
            annotations.forEachIndexed { i, a ->
                if (!isComment(a) || a.cosObject.containsKey(IRT)) return@forEachIndexed
                result += Comment(
                    page = pageIndex,
                    index = i,
                    kind = kindOf(a.subtype),
                    subtype = a.subtype.orEmpty(),
                    text = contents(a),
                    author = a.cosObject.getString(COSName.T),
                    date = runCatching { a.modifiedDate?.let(::parseDate) }.getOrNull(),
                    replies = replies[a.cosObject].orEmpty().map { r ->
                        CommentReply(r.cosObject.getString(COSName.T), contents(r), runCatching { r.modifiedDate?.let(::parseDate) }.getOrNull())
                    },
                )
            }
        }
        result
    }

    /** Removes the comment with its replies and popup window. */
    fun delete(source: File, target: File, comment: Comment) = edit(source, target) { doc ->
        val page = doc.getPage(comment.page)
        val all = page.annotations
        val victim = all.getOrNull(comment.index) ?: return@edit
        val popup = victim.cosObject.getDictionaryObject(COSName.getPDFName("Popup"))
        page.annotations = ArrayList(all.filterNot {
            it === victim || it.cosObject.getDictionaryObject(IRT) == victim.cosObject || it.cosObject == popup
        })
    }

    /** Adds a reply in the form Acrobat and other readers show as a thread. */
    fun reply(source: File, target: File, comment: Comment, text: String, author: String) = edit(source, target) { doc ->
        val page = doc.getPage(comment.page)
        val parent = page.annotations.getOrNull(comment.index) ?: return@edit
        val reply = PDAnnotationText()
        reply.rectangle = parent.rectangle
        reply.contents = text
        reply.cosObject.setString(COSName.T, author)
        reply.cosObject.setItem(IRT, parent.cosObject)
        reply.cosObject.setName(COSName.getPDFName("RT"), "R")
        reply.annotationName = "pdfdoc-${UUID.randomUUID()}"
        reply.setModifiedDate(Calendar.getInstance())
        reply.isPrinted = true
        reply.isNoZoom = true
        reply.isNoRotate = true
        reply.page = page
        page.annotations = ArrayList(page.annotations + reply)
    }

    private fun isComment(a: PDAnnotation) =
        a !is PDAnnotationLink && a !is PDAnnotationPopup && a !is PDAnnotationWidget

    private fun contents(a: PDAnnotation): String = a.contents.orEmpty().trim()

    private fun kindOf(subtype: String?) = when (subtype) {
        "Text" -> CommentKind.NOTE
        "FreeText" -> CommentKind.TEXT
        "Highlight", "Underline", "StrikeOut", "Squiggly" -> CommentKind.MARKUP
        "Ink", "Square", "Circle", "Line", "Polygon", "PolyLine" -> CommentKind.DRAWING
        "Stamp" -> CommentKind.STAMP
        else -> CommentKind.OTHER
    }

    private fun parseDate(raw: String): Calendar? = com.tom_roush.pdfbox.util.DateConverter.toCalendar(raw)

    private fun load(file: File) = PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())

    private inline fun edit(source: File, target: File, block: (PDDocument) -> Unit) {
        load(source).use { doc ->
            block(doc)
            doc.save(target)
        }
    }

    private companion object {
        val IRT: COSName = COSName.getPDFName("IRT")
    }
}
