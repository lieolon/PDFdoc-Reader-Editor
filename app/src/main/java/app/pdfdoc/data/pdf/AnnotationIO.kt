package app.pdfdoc.data.pdf

import android.content.Context
import android.graphics.Bitmap
import app.pdfdoc.domain.edit.Box
import app.pdfdoc.domain.edit.CoverEdit
import app.pdfdoc.domain.edit.Edit
import app.pdfdoc.domain.edit.ImageEdit
import app.pdfdoc.domain.edit.InkEdit
import app.pdfdoc.domain.edit.MarkupEdit
import app.pdfdoc.domain.edit.MarkupKind
import app.pdfdoc.domain.edit.NoteEdit
import app.pdfdoc.domain.edit.ShapeEdit
import app.pdfdoc.domain.edit.ShapeKind
import app.pdfdoc.domain.edit.StampEdit
import app.pdfdoc.domain.edit.TextEdit
import app.pdfdoc.domain.edit.LinkEdit
import app.pdfdoc.domain.edit.MeasureEdit
import app.pdfdoc.domain.edit.MeasureKind
import app.pdfdoc.domain.edit.appliedOnSave
import app.pdfdoc.domain.edit.mapPoints
import app.pdfdoc.domain.edit.onPage
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What is stored inside an annotation so the app can edit it again later. */
@Serializable
private data class StoredEdit(val rotation: Int, val edit: Edit)

/**
 * Writes [Edit]s as standard PDF annotations with their own appearance streams, and reads
 * back annotations previously created by this app (marked with the [KEY] entry).
 */
@Singleton
class AnnotationIO @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Replaces all app-created annotations in [doc] with [edits]. Other annotations are kept. */
    fun write(doc: PDDocument, edits: List<Edit>) {
        val byPage = edits.filterNot { it.appliedOnSave }.groupBy { it.page }
        var font: com.tom_roush.pdfbox.pdmodel.font.PDType0Font? = null
        val painter = AppearancePainter(doc) { font ?: PdfFonts.load(doc, context).also { font = it } }
        doc.pages.forEachIndexed { index, page ->
            val kept = page.annotations.filterNot(::isOwn)
            val added = byPage[index].orEmpty().map { build(doc, page, it, painter) }
            if (kept.size != page.annotations.size || added.isNotEmpty()) {
                page.annotations = ArrayList(kept + added)
            }
        }
    }

    /** Reads app-created annotations back into edits; images are saved into [imageDir]. */
    fun read(doc: PDDocument, imageDir: File): List<Edit> {
        val result = mutableListOf<Edit>()
        doc.pages.forEachIndexed { index, page ->
            val geometry = PageGeometry.of(page)
            page.annotations.filter(::isOwn).forEach { annotation ->
                val stored = annotation.cosObject.getString(KEY)
                    ?.let { runCatching { json.decodeFromString(StoredEdit.serializer(), it) }.getOrNull() }
                    ?: return@forEach
                var edit = onPage(stored.edit, index)
                if (stored.rotation.mod(360) != page.rotation.mod(360)) {
                    // The page was rotated after the edit was made: re-project through PDF space.
                    val crop = page.cropBox
                    val old = PageGeometry(crop.lowerLeftX, crop.lowerLeftY, crop.width, crop.height, stored.rotation)
                    edit = mapPoints(edit) { geometry.toDisplay(old.toPdf(it)) }
                }
                if (edit is ImageEdit) {
                    val file = extractImage(annotation, imageDir) ?: return@forEach
                    edit = edit.copy(imagePath = file.absolutePath)
                }
                result += edit
            }
        }
        return result
    }

    private fun build(doc: PDDocument, page: PDPage, edit: Edit, painter: AppearancePainter): PDAnnotation {
        val geometry = PageGeometry.of(page)
        val drawBox = paddedFrame(edit)
        val rect = geometry.toPdfRectangle(drawBox)

        val dict = COSDictionary()
        dict.setName(COSName.TYPE, "Annot")
        dict.setName(COSName.SUBTYPE, subtype(edit))
        val annotation = PDAnnotation.createAnnotation(dict)
        annotation.rectangle = rect
        annotation.isPrinted = true
        annotation.annotationName = "pdfdoc-${UUID.randomUUID()}"
        annotation.setModifiedDate(Calendar.getInstance())
        annotation.page = page
        colorOf(edit)?.let { annotation.color = PDColor(floatArrayOf(AppearancePainter.r(it), AppearancePainter.g(it), AppearancePainter.b(it)), PDDeviceRGB.INSTANCE) }
        dict.setString(KEY, json.encodeToString(StoredEdit.serializer(), StoredEdit(page.rotation, edit)))
        describe(dict, edit, geometry, doc.pages.toList())

        val appearance = PDAppearanceStream(doc)
        appearance.bBox = rect
        appearance.resources = PDResources()
        PDPageContentStream(doc, appearance).use { cs ->
            cs.transform(geometry.matrix())
            painter.paint(cs, edit)
        }
        annotation.appearance = PDAppearanceDictionary().apply { setNormalAppearance(appearance) }
        return annotation
    }

    /** Standard entries so other PDF readers understand the annotation without our appearance. */
    private fun describe(dict: COSDictionary, edit: Edit, geometry: PageGeometry, pages: List<PDPage>) {
        fun floats(vararg v: Float) = COSArray().apply { v.forEach { add(COSFloat(it)) } }
        fun border(width: Float) = COSDictionary().apply { setFloat(COSName.W, width) }
        when (edit) {
            is InkEdit -> {
                val list = COSArray()
                edit.strokes.forEach { s ->
                    val arr = COSArray()
                    s.map(geometry::toPdf).forEach { arr.add(COSFloat(it.x)); arr.add(COSFloat(it.y)) }
                    list.add(arr)
                }
                dict.setItem(COSName.getPDFName("InkList"), list)
                dict.setItem(COSName.BS, border(edit.width))
                if (edit.alpha < 1f) dict.setFloat(COSName.CA, edit.alpha)
            }
            is ShapeEdit -> {
                dict.setItem(COSName.BS, border(edit.width))
                if (edit.alpha < 1f) dict.setFloat(COSName.CA, edit.alpha)
                if (edit.kind == ShapeKind.LINE || edit.kind == ShapeKind.ARROW) {
                    val a = geometry.toPdf(edit.start)
                    val b = geometry.toPdf(edit.end)
                    dict.setItem(COSName.L, floats(a.x, a.y, b.x, b.y))
                    val end = if (edit.kind == ShapeKind.ARROW) "OpenArrow" else "None"
                    dict.setItem(COSName.LE, COSArray().apply { add(COSName.getPDFName("None")); add(COSName.getPDFName(end)) })
                }
            }
            is MarkupEdit -> {
                val quads = COSArray()
                edit.rects.forEach { r ->
                    // Order used by Acrobat: top-left, top-right, bottom-left, bottom-right.
                    listOf(app.pdfdoc.domain.edit.Pt(r.left, r.top), app.pdfdoc.domain.edit.Pt(r.right, r.top),
                        app.pdfdoc.domain.edit.Pt(r.left, r.bottom), app.pdfdoc.domain.edit.Pt(r.right, r.bottom))
                        .map(geometry::toPdf)
                        .forEach { quads.add(COSFloat(it.x)); quads.add(COSFloat(it.y)) }
                }
                dict.setItem(COSName.getPDFName("QuadPoints"), quads)
            }
            is TextEdit -> {
                dict.setString(COSName.CONTENTS, edit.text)
                val c = edit.color
                dict.setString(COSName.DA, "/Helv %.1f Tf %.3f %.3f %.3f rg".format(
                    java.util.Locale.US, edit.size, AppearancePainter.r(c), AppearancePainter.g(c), AppearancePainter.b(c)))
            }
            is NoteEdit -> {
                dict.setString(COSName.CONTENTS, edit.text)
                dict.setName(COSName.NAME, "Comment")
            }
            is StampEdit -> dict.setString(COSName.CONTENTS, edit.text)
            is CoverEdit -> {
                val c = edit.color
                dict.setItem(COSName.IC, floats(AppearancePainter.r(c), AppearancePainter.g(c), AppearancePainter.b(c)))
                dict.setItem(COSName.BS, border(0f))
            }
            is ImageEdit -> Unit
            is LinkEdit -> {
                dict.setItem(COSName.BORDER, floats(0f, 0f, 0f))
                val action = COSDictionary()
                action.setName(COSName.TYPE, "Action")
                if (edit.url != null) {
                    action.setName(COSName.S, "URI")
                    action.setString(COSName.URI, edit.url)
                    dict.setItem(COSName.A, action)
                } else if (edit.targetPage >= 0 && edit.targetPage < pages.size) {
                    val dest = COSArray().apply {
                        add(pages[edit.targetPage].cosObject)
                        add(COSName.getPDFName("Fit"))
                    }
                    dict.setItem(COSName.DEST, dest)
                }
            }
            is MeasureEdit -> {
                dict.setString(COSName.CONTENTS, edit.label)
                dict.setItem(COSName.BS, border(1.2f))
                val pts = edit.points.map(geometry::toPdf)
                if (edit.kind == MeasureKind.DISTANCE) {
                    dict.setItem(COSName.L, floats(pts[0].x, pts[0].y, pts.last().x, pts.last().y))
                    dict.setItem(COSName.getPDFName("Cap"), com.tom_roush.pdfbox.cos.COSBoolean.TRUE)
                } else {
                    dict.setItem(COSName.getPDFName("Vertices"), floats(*pts.flatMap { listOf(it.x, it.y) }.toFloatArray()))
                }
            }
            is app.pdfdoc.domain.edit.RedactEdit, is app.pdfdoc.domain.edit.FieldEdit -> Unit
        }
    }

    private fun extractImage(annotation: PDAnnotation, dir: File): File? = runCatching {
        val resources = annotation.normalAppearanceStream?.resources ?: return null
        val image = resources.xObjectNames.firstNotNullOfOrNull { resources.getXObject(it) as? PDImageXObject }
            ?: return null
        val bitmap = image.image ?: return null
        val jpeg = image.suffix == "jpg"
        val file = File(dir.apply { mkdirs() }, "${UUID.randomUUID()}.${if (jpeg) "jpg" else "png"}")
        file.outputStream().use {
            bitmap.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, if (jpeg) 92 else 100, it)
        }
        file
    }.getOrNull()

    companion object {
        val KEY: COSName = COSName.getPDFName("PDFdocEdit")

        fun isOwn(annotation: PDAnnotation) = annotation.cosObject.containsKey(KEY)

        private fun subtype(edit: Edit) = when (edit) {
            is InkEdit -> "Ink"
            is ShapeEdit -> when (edit.kind) {
                ShapeKind.RECT -> "Square"
                ShapeKind.OVAL -> "Circle"
                ShapeKind.LINE, ShapeKind.ARROW -> "Line"
            }
            is MarkupEdit -> when (edit.kind) {
                MarkupKind.HIGHLIGHT -> "Highlight"
                MarkupKind.UNDERLINE -> "Underline"
                MarkupKind.STRIKEOUT -> "StrikeOut"
            }
            is TextEdit -> "FreeText"
            is NoteEdit -> "Text"
            is ImageEdit, is StampEdit -> "Stamp"
            is CoverEdit -> "Square"
            is LinkEdit -> "Link"
            is MeasureEdit -> when (edit.kind) {
                MeasureKind.DISTANCE -> "Line"
                MeasureKind.PERIMETER -> "PolyLine"
                MeasureKind.AREA -> "Polygon"
            }
            is app.pdfdoc.domain.edit.RedactEdit -> "Redact"
            is app.pdfdoc.domain.edit.FieldEdit -> "Widget"
        }

        private fun colorOf(edit: Edit): Int? = when (edit) {
            is InkEdit -> edit.color
            is ShapeEdit -> edit.color
            is MarkupEdit -> edit.color
            is TextEdit -> edit.color
            is NoteEdit -> edit.color
            is StampEdit -> edit.color
            is CoverEdit -> edit.color
            is MeasureEdit -> edit.color
            is ImageEdit, is LinkEdit, is app.pdfdoc.domain.edit.RedactEdit, is app.pdfdoc.domain.edit.FieldEdit -> null
        }

        /** Area the appearance may draw into: the frame plus stroke width and arrow heads. */
        fun paddedFrame(edit: Edit): Box {
            val frame = edit.frame()
            val pad = when (edit) {
                is InkEdit -> edit.width / 2 + 1f
                is ShapeEdit -> edit.width / 2 + 1f +
                    if (edit.kind == ShapeKind.ARROW) ArrowHead.length(edit.width) else 0f
                is MarkupEdit -> 1f
                // Room for the measurement label next to the lines.
                is MeasureEdit -> 14f
                else -> 0.5f
            }
            return frame.inset(-pad)
        }
    }
}
