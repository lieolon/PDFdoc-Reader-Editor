package app.pdfdoc.domain.edit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

enum class ShapeKind { RECT, OVAL, LINE, ARROW }

enum class MarkupKind { HIGHLIGHT, UNDERLINE, STRIKEOUT }

enum class TextAlign { LEFT, CENTER, RIGHT }

/**
 * A user change to a page, stored in page display space (see [Pt]).
 * Colors are opaque ARGB ints; transparency is kept separately in [alpha] fields.
 */
@Serializable
sealed interface Edit {
    val id: Long
    val page: Int

    /** Geometric frame used for selection and resizing (without stroke width). */
    fun frame(): Box

    fun translate(dx: Float, dy: Float): Edit
    fun withColor(color: Int): Edit
    fun withId(id: Long): Edit

    /** Whether the edit can be resized with corner handles. */
    val resizable: Boolean get() = false
}

@Serializable
@SerialName("ink")
data class InkEdit(
    override val id: Long,
    override val page: Int,
    val strokes: List<List<Pt>>,
    val color: Int,
    val width: Float,
    val alpha: Float = 1f,
) : Edit {
    override fun frame() = Box.bounding(strokes.flatten())!!
    override fun translate(dx: Float, dy: Float) =
        copy(strokes = strokes.map { s -> s.map { Pt(it.x + dx, it.y + dy) } })
    override fun withColor(color: Int) = copy(color = color)
    override fun withId(id: Long) = copy(id = id)
    override val resizable get() = true
}

@Serializable
@SerialName("shape")
data class ShapeEdit(
    override val id: Long,
    override val page: Int,
    val kind: ShapeKind,
    val start: Pt,
    val end: Pt,
    val color: Int,
    val width: Float,
    val alpha: Float = 1f,
) : Edit {
    override fun frame() = Box.of(start, end)
    override fun translate(dx: Float, dy: Float) = copy(start = Pt(start.x + dx, start.y + dy), end = Pt(end.x + dx, end.y + dy))
    override fun withColor(color: Int) = copy(color = color)
    override fun withId(id: Long) = copy(id = id)
    override val resizable get() = true
}

@Serializable
@SerialName("markup")
data class MarkupEdit(
    override val id: Long,
    override val page: Int,
    val kind: MarkupKind,
    val rects: List<Box>,
    val color: Int,
) : Edit {
    override fun frame() = rects.reduce(Box::union)
    override fun translate(dx: Float, dy: Float) = copy(rects = rects.map { it.translate(dx, dy) })
    override fun withColor(color: Int) = copy(color = color)
    override fun withId(id: Long) = copy(id = id)
}

@Serializable
@SerialName("text")
data class TextEdit(
    override val id: Long,
    override val page: Int,
    /** Top-left corner plus measured text size. */
    val box: Box,
    val text: String,
    val size: Float,
    val color: Int,
    val align: TextAlign = TextAlign.LEFT,
) : Edit {
    override fun frame() = box
    override fun translate(dx: Float, dy: Float) = copy(box = box.translate(dx, dy))
    override fun withColor(color: Int) = copy(color = color)
    override fun withId(id: Long) = copy(id = id)
    override val resizable get() = true
}

@Serializable
@SerialName("note")
data class NoteEdit(
    override val id: Long,
    override val page: Int,
    val at: Pt,
    val text: String,
    val color: Int,
) : Edit {
    override fun frame() = Box(at.x, at.y, at.x + ICON_SIZE, at.y + ICON_SIZE)
    override fun translate(dx: Float, dy: Float) = copy(at = Pt(at.x + dx, at.y + dy))
    override fun withColor(color: Int) = copy(color = color)
    override fun withId(id: Long) = copy(id = id)

    companion object {
        const val ICON_SIZE = 22f
    }
}

@Serializable
@SerialName("image")
data class ImageEdit(
    override val id: Long,
    override val page: Int,
    val box: Box,
    /** Clockwise rotation of the image inside [box]: 0, 90, 180 or 270. */
    val rotation: Int = 0,
    /** Local PNG file with the pixels; restored from the PDF when a document is reopened. */
    @Transient val imagePath: String = "",
) : Edit {
    override fun frame() = box
    override fun translate(dx: Float, dy: Float) = copy(box = box.translate(dx, dy))
    override fun withColor(color: Int) = this
    override fun withId(id: Long) = copy(id = id)
    override val resizable get() = true
}

@Serializable
@SerialName("stamp")
data class StampEdit(
    override val id: Long,
    override val page: Int,
    val box: Box,
    val text: String,
    val color: Int,
) : Edit {
    override fun frame() = box
    override fun translate(dx: Float, dy: Float) = copy(box = box.translate(dx, dy))
    override fun withColor(color: Int) = copy(color = color)
    override fun withId(id: Long) = copy(id = id)
    override val resizable get() = true

    /** Font size that fits the stamp text into its box. */
    val fontSize: Float get() = box.height / STAMP_HEIGHT_RATIO

    companion object {
        const val STAMP_HEIGHT_RATIO = 1.7f
        const val PADDING_RATIO = 0.6f
    }
}

/** Shared text layout constants so the on-screen overlay and the PDF appearance match. */
object TextLayout {
    const val LINE_HEIGHT = 1.2f
    const val ASCENT = 0.93f
}

/** Returns [edit] scaled so that its frame becomes [target]. */
fun resized(edit: Edit, target: Box): Edit {
    val from = edit.frame()
    return when (edit) {
        is InkEdit -> edit.copy(strokes = edit.strokes.map { s -> s.map { from.mapTo(target, it) } })
        is ShapeEdit -> edit.copy(start = from.mapTo(target, edit.start), end = from.mapTo(target, edit.end))
        is ImageEdit -> edit.copy(box = target)
        is StampEdit -> edit.copy(box = target)
        is TextEdit -> {
            // Text keeps its proportions: scale by height and anchor at the new top-left.
            val k = if (from.height > 0f) target.height / from.height else 1f
            edit.copy(
                size = edit.size * k,
                box = Box(target.left, target.top, target.left + from.width * k, target.top + from.height * k),
            )
        }
        is MarkupEdit, is NoteEdit -> edit
    }
}

/** Moves [edit] to another page index (used when pages were reordered outside the editor). */
fun onPage(edit: Edit, page: Int): Edit = when (edit) {
    is InkEdit -> edit.copy(page = page)
    is ShapeEdit -> edit.copy(page = page)
    is MarkupEdit -> edit.copy(page = page)
    is TextEdit -> edit.copy(page = page)
    is NoteEdit -> edit.copy(page = page)
    is ImageEdit -> edit.copy(page = page)
    is StampEdit -> edit.copy(page = page)
}

/** Applies a point transform to every coordinate of [edit]; boxes are re-normalized. */
fun mapPoints(edit: Edit, f: (Pt) -> Pt): Edit {
    fun box(b: Box) = Box.bounding(b.corners().map(f))!!
    return when (edit) {
        is InkEdit -> edit.copy(strokes = edit.strokes.map { s -> s.map(f) })
        is ShapeEdit -> edit.copy(start = f(edit.start), end = f(edit.end))
        is MarkupEdit -> edit.copy(rects = edit.rects.map(::box))
        is TextEdit -> {
            val moved = box(edit.box)
            edit.copy(box = Box(moved.left, moved.top, moved.left + edit.box.width, moved.top + edit.box.height))
        }
        is NoteEdit -> edit.copy(at = f(edit.at))
        is ImageEdit -> edit.copy(box = box(edit.box))
        is StampEdit -> edit.copy(box = box(edit.box))
    }
}
