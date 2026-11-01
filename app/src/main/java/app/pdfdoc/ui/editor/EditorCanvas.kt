package app.pdfdoc.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import app.pdfdoc.data.pdf.AnnotationIO
import app.pdfdoc.domain.edit.Box
import app.pdfdoc.domain.edit.Edit
import app.pdfdoc.domain.edit.MarkupKind
import app.pdfdoc.domain.edit.Pt
import app.pdfdoc.domain.edit.ShapeKind
import app.pdfdoc.domain.edit.distance
import app.pdfdoc.domain.edit.resized
import app.pdfdoc.domain.text.PageText
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_ZOOM = 5f
private const val MAX_RENDER_PX = 2048

/** Callbacks from page gestures; all coordinates are in page display space (points). */
interface EditorGestures {
    fun onStroke(points: List<Pt>)
    fun onShape(kind: ShapeKind, start: Pt, end: Pt)
    fun onMarkup(kind: MarkupKind, from: Pt, to: Pt)
    fun onReplace(from: Pt, to: Pt)
    fun onTap(at: Pt)
    fun onSmartStroke(points: List<Pt>)
    fun onErase(path: List<Pt>)
    fun onArea(start: Pt, end: Pt)
    fun onMeasureLine(start: Pt, end: Pt)
    fun onSelect(id: Long?)
    fun onPreview(edit: Edit)
    fun onCommitPreview()
}

private enum class Mode { NONE, PEN, ERASE, SHAPE, AREA, MARKUP, TAP, MOVE, RESIZE, PAN, TRANSFORM }

@Composable
fun EditorCanvas(
    page: Int,
    pageSize: Size,
    edits: List<Edit>,
    selected: Edit?,
    tool: Tool,
    previewColor: Int,
    previewWidth: Float,
    textPaint: Paint,
    pageText: PageText?,
    pageBitmap: suspend (Int, Int) -> Bitmap?,
    gestures: EditorGestures,
    modifier: Modifier = Modifier,
    generation: Int = 0,
    vertices: List<Pt> = emptyList(),
) {
    val renderer = remember(textPaint) { EditRenderer(textPaint) }
    // Zoom and pan reset when the page changes.
    var zoom by remember(page) { mutableFloatStateOf(1f) }
    var pan by remember(page) { mutableStateOf(Offset.Zero) }

    // In-progress drawing, in page points.
    var stroke by remember { mutableStateOf<List<Pt>>(emptyList()) }
    var dragStart by remember { mutableStateOf<Pt?>(null) }
    var dragEnd by remember { mutableStateOf<Pt?>(null) }

    val images = rememberImages(edits)
    val currentTool by rememberUpdatedState(tool)
    val currentEdits by rememberUpdatedState(edits)
    val currentSelected by rememberUpdatedState(selected)
    val currentGestures by rememberUpdatedState(gestures)
    val density = LocalDensity.current.density

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .background(Color(0xFF9E9E9E)),
    ) {
        val viewportW = constraints.maxWidth.toFloat()
        val viewportH = constraints.maxHeight.toFloat()
        val pw = pageSize.width.toFloat()
        val ph = pageSize.height.toFloat()
        // Pixels per point at zoom 1: fit the whole page with a small margin.
        val ppp = minOf(viewportW / pw, viewportH / ph) * 0.96f
        val origin = Offset((viewportW - pw * ppp) / 2, (viewportH - ph * ppp) / 2)

        fun toPage(pos: Offset): Pt {
            val content = (pos - pan) / zoom
            return Pt((content.x - origin.x) / ppp, (content.y - origin.y) / ppp)
        }

        val gestureModifier = Modifier.pointerInput(page, ppp, origin) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val start = toPage(down.position)
                val touchTolerance = 18f * density / (zoom * ppp)
                var mode = Mode.NONE
                var origEdit: Edit? = null
                var handle = -1
                var moved = false
                var last = down.position

                when (val t = currentTool) {
                    Tool.PEN, Tool.SMART_PEN -> { mode = Mode.PEN; stroke = listOf(start) }
                    Tool.ERASER -> { mode = Mode.ERASE; stroke = listOf(start) }
                    Tool.RECT, Tool.OVAL, Tool.LINE, Tool.ARROW, Tool.HIGHLIGHT, Tool.UNDERLINE, Tool.STRIKEOUT, Tool.REPLACE, Tool.MEASURE_DISTANCE -> {
                        mode = if (t.usesWidth || t == Tool.MEASURE_DISTANCE) Mode.SHAPE else Mode.MARKUP
                        dragStart = start
                        dragEnd = start
                    }
                    Tool.REDACT, Tool.LINK, Tool.FIELD -> {
                        mode = Mode.AREA
                        dragStart = start
                        dragEnd = start
                    }
                    Tool.TEXT, Tool.NOTE, Tool.MEASURE_AREA, Tool.IMAGE_EDIT -> mode = Mode.TAP
                    Tool.SELECT -> {
                        val sel = currentSelected
                        handle = if (sel != null && sel.resizable) {
                            sel.frame().corners().indexOfFirst { distance(it, start) < touchTolerance * 1.4f }
                        } else -1
                        if (handle >= 0) {
                            mode = Mode.RESIZE
                            origEdit = sel
                        } else {
                            val hit = currentEdits.lastOrNull { AnnotationIO.paddedFrame(it).contains(start, touchTolerance) }
                            currentGestures.onSelect(hit?.id)
                            mode = if (hit != null) Mode.MOVE else Mode.PAN
                            origEdit = hit
                        }
                    }
                }

                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.count { it.pressed }
                    if (pressed >= 2) {
                        if (mode == Mode.MOVE || mode == Mode.RESIZE) currentGestures.onCommitPreview()
                        mode = Mode.TRANSFORM
                        stroke = emptyList()
                        dragStart = null
                        val factor = event.calculateZoom()
                        val centroid = event.calculateCentroid()
                        val newZoom = (zoom * factor).coerceIn(1f, MAX_ZOOM)
                        val ratio = newZoom / zoom
                        pan = if (newZoom <= 1f) Offset.Zero else centroid - (centroid - pan) * ratio + event.calculatePan()
                        zoom = newZoom
                        event.changes.forEach { it.consume() }
                        continue
                    }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                    if (!change.pressed || pressed == 0) break
                    if (mode == Mode.TRANSFORM) continue
                    if (!moved && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                    val p = toPage(change.position)
                    when (mode) {
                        Mode.PEN, Mode.ERASE -> stroke = stroke + p
                        Mode.SHAPE, Mode.MARKUP, Mode.AREA -> dragEnd = p
                        Mode.MOVE -> if (moved) origEdit?.let { currentGestures.onPreview(it.translate(p.x - start.x, p.y - start.y)) }
                        Mode.RESIZE -> origEdit?.let { e ->
                            val corners = e.frame().corners()
                            val opposite = corners[(handle + 2) % 4]
                            val box = Box.of(opposite, p)
                            if (box.width > 4f && box.height > 4f) currentGestures.onPreview(resized(e, box))
                        }
                        Mode.PAN -> if (zoom > 1f) pan += change.position - last
                        else -> Unit
                    }
                    last = change.position
                    change.consume()
                }

                val end = toPage(last)
                when (mode) {
                    Mode.PEN -> if (stroke.isNotEmpty()) {
                        if (currentTool == Tool.SMART_PEN) currentGestures.onSmartStroke(stroke) else currentGestures.onStroke(stroke)
                    }
                    Mode.ERASE -> currentGestures.onErase(stroke)
                    Mode.AREA -> if (moved) currentGestures.onArea(start, end)
                    Mode.SHAPE -> if (moved) {
                        if (currentTool == Tool.MEASURE_DISTANCE) currentGestures.onMeasureLine(start, end)
                        else currentGestures.onShape(shapeKind(currentTool), start, end)
                    }
                    Mode.MARKUP -> if (moved) {
                        if (currentTool == Tool.REPLACE) currentGestures.onReplace(start, end)
                        else currentGestures.onMarkup(markupKind(currentTool), start, end)
                    }
                    Mode.TAP -> if (!moved) currentGestures.onTap(start)
                    Mode.MOVE, Mode.RESIZE -> if (moved) currentGestures.onCommitPreview()
                    else -> Unit
                }
                stroke = emptyList()
                dragStart = null
                dragEnd = null
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .then(gestureModifier)
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = pan.x
                    translationY = pan.y
                    transformOrigin = TransformOrigin(0f, 0f)
                },
        ) {
            val pageWidthPx = (pw * ppp)
            val renderWidth = (pageWidthPx * 2).roundToInt().coerceIn(1, MAX_RENDER_PX)
            var bitmap by remember(page) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(page, renderWidth, generation) { pageBitmap(page, renderWidth)?.let { bitmap = it } }
            val sizeDp = with(LocalDensity.current) { androidx.compose.ui.unit.DpSize((pw * ppp).toDp(), (ph * ppp).toDp()) }

            Box(
                Modifier
                    .offset { IntOffset(origin.x.roundToInt(), origin.y.roundToInt()) }
                    .size(sizeDp)
                    .background(Color.White),
            ) {
                bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
                Canvas(Modifier.fillMaxSize()) {
                    val k = size.width / pw
                    with(renderer) {
                        edits.forEach { draw(it, k, images::get) }
                        selected?.let { selection(it, k, zoom) }
                    }
                    // Previews of the gesture in progress.
                    val color = Color(previewColor)
                    if (vertices.isNotEmpty()) {
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(vertices[0].x * k, vertices[0].y * k)
                            vertices.drop(1).forEach { lineTo(it.x * k, it.y * k) }
                        }
                        drawPath(path, Color(0xFF1565C0), style = Stroke(1.5f * k))
                        vertices.forEach { drawCircle(Color(0xFF1565C0), 3f * k, it.offset(k)) }
                    }
                    if (tool == Tool.ERASER && stroke.isNotEmpty()) {
                        stroke.forEach { drawCircle(Color(0x55E53935), 6f * k, it.offset(k)) }
                    } else if (stroke.size > 1) {
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(stroke[0].x * k, stroke[0].y * k)
                            stroke.drop(1).forEach { lineTo(it.x * k, it.y * k) }
                        }
                        drawPath(path, color, style = Stroke(previewWidth * k, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                    }
                    val a = dragStart
                    val b = dragEnd
                    if (a != null && b != null) {
                        val markupRects = if (tool == Tool.HIGHLIGHT || tool == Tool.UNDERLINE || tool == Tool.STRIKEOUT || tool == Tool.REPLACE) {
                            pageText?.select(a, b).orEmpty().ifEmpty { listOf(Box.of(a, b)) }
                        } else emptyList()
                        when (tool) {
                            Tool.RECT -> drawRect(color, Box.of(a, b).topLeft(k), Box.of(a, b).size(k), style = Stroke(previewWidth * k))
                            Tool.OVAL -> drawOval(color, Box.of(a, b).topLeft(k), Box.of(a, b).size(k), style = Stroke(previewWidth * k))
                            Tool.LINE, Tool.ARROW -> drawLine(color, a.offset(k), b.offset(k), previewWidth * k)
                            Tool.MEASURE_DISTANCE -> drawLine(Color(0xFF1565C0), a.offset(k), b.offset(k), 1.5f * k)
                            Tool.REDACT -> drawRect(Color.Black.copy(alpha = 0.7f), Box.of(a, b).topLeft(k), Box.of(a, b).size(k))
                            Tool.LINK, Tool.FIELD -> drawRect(Color(0xFF1565C0), Box.of(a, b).topLeft(k), Box.of(a, b).size(k), style = Stroke(1.5f * k))
                            else -> markupRects.forEach {
                                drawRect(color.copy(alpha = 0.4f), it.topLeft(k), it.size(k))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun shapeKind(tool: Tool) = when (tool) {
    Tool.RECT -> ShapeKind.RECT
    Tool.OVAL -> ShapeKind.OVAL
    Tool.LINE -> ShapeKind.LINE
    else -> ShapeKind.ARROW
}

private fun markupKind(tool: Tool) = when (tool) {
    Tool.UNDERLINE -> MarkupKind.UNDERLINE
    Tool.STRIKEOUT -> MarkupKind.STRIKEOUT
    else -> MarkupKind.HIGHLIGHT
}

/** Decodes the image files used by image edits, off the main thread. */
@Composable
private fun rememberImages(edits: List<Edit>): Map<String, ImageBitmap> {
    val images = remember { mutableStateMapOf<String, ImageBitmap>() }
    val paths = edits.mapNotNull { (it as? app.pdfdoc.domain.edit.ImageEdit)?.imagePath }.toSet()
    LaunchedEffect(paths) {
        paths.filterNot(images::containsKey).forEach { path ->
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }?.let { images[path] = it.asImageBitmap() }
        }
    }
    return images
}
