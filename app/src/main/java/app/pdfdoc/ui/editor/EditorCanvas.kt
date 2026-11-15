package app.pdfdoc.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.util.Size
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.launch
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
    fun onLasso(path: List<Pt>)
    fun onGroupMove(dx: Float, dy: Float)
}

private enum class Mode { NONE, PEN, ERASE, SHAPE, AREA, MARKUP, TAP, MOVE, RESIZE, PAN, TRANSFORM, LASSO, GROUP_MOVE }

/** The gesture in progress on one page, in page points, drawn as a preview over the page. */
private class PageInk {
    var stroke by mutableStateOf<List<Pt>>(emptyList())
    var dragStart by mutableStateOf<Pt?>(null)
    var dragEnd by mutableStateOf<Pt?>(null)

    fun clear() {
        stroke = emptyList()
        dragStart = null
        dragEnd = null
    }
}

/** What a page needs to draw itself; the same for both layouts. */
class PageContent(
    val page: Int,
    val pageSize: Size,
    val edits: List<Edit>,
    val selected: Edit?,
    val group: List<Edit>,
    val vertices: List<Pt>,
    val pageText: PageText?,
    val gestures: EditorGestures,
)

/**
 * One-finger gestures of the current tool on a page. [toPage] maps a pointer position to page points and
 * [pointsPerPx] gives the current scale, for touch tolerance. Two fingers end the tool gesture and go to
 * [onTransform]; in the page strip a parent consumes them first, which also ends the tool gesture.
 * [onPan] moves the view (hand tool, or Select on an empty spot) and [onPanEnd] receives the fling velocity.
 */
private fun Modifier.pageGestures(
    key: Any,
    ink: PageInk,
    content: () -> PageContent,
    tool: () -> Tool,
    toPage: (Offset) -> Pt,
    pointsPerPx: () -> Float,
    onTransform: (PointerEvent) -> Unit,
    onPan: (Offset) -> Unit,
    onPanEnd: (androidx.compose.ui.unit.Velocity) -> Unit = {},
    /** Where this page currently sits on screen; panning measures the finger there, since the page moves with it. */
    origin: () -> Offset = { Offset.Zero },
): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = toPage(down.position)
        val c = content()
        val gestures = c.gestures
        val touchTolerance = 18f * density * pointsPerPx()
        var mode = Mode.NONE
        var origEdit: Edit? = null
        var handle = -1
        var moved = false
        var last = down.position
        var lastScreen = down.position + origin()
        val velocity = VelocityTracker()
        velocity.addPosition(down.uptimeMillis, lastScreen)

        when (val t = tool()) {
            Tool.HAND -> mode = Mode.PAN
            Tool.PEN, Tool.SMART_PEN -> { mode = Mode.PEN; ink.stroke = listOf(start) }
            Tool.ERASER -> { mode = Mode.ERASE; ink.stroke = listOf(start) }
            Tool.RECT, Tool.OVAL, Tool.LINE, Tool.ARROW, Tool.HIGHLIGHT, Tool.UNDERLINE, Tool.STRIKEOUT, Tool.REPLACE, Tool.MEASURE_DISTANCE -> {
                mode = if (t.usesWidth || t == Tool.MEASURE_DISTANCE) Mode.SHAPE else Mode.MARKUP
                ink.dragStart = start
                ink.dragEnd = start
            }
            Tool.REDACT, Tool.LINK, Tool.FIELD -> {
                mode = Mode.AREA
                ink.dragStart = start
                ink.dragEnd = start
            }
            Tool.TEXT, Tool.NOTE, Tool.MEASURE_AREA, Tool.IMAGE_EDIT -> mode = Mode.TAP
            Tool.LASSO -> {
                val bounds = Box.bounding(c.group.flatMap { it.frame().corners() })
                if (bounds != null && bounds.contains(start, touchTolerance)) {
                    mode = Mode.GROUP_MOVE
                } else {
                    mode = Mode.LASSO
                    ink.stroke = listOf(start)
                }
            }
            Tool.SELECT -> {
                val sel = c.selected
                handle = if (sel != null && sel.resizable) {
                    sel.frame().corners().indexOfFirst { distance(it, start) < touchTolerance * 1.4f }
                } else -1
                if (handle >= 0) {
                    mode = Mode.RESIZE
                    origEdit = sel
                } else {
                    val hit = c.edits.lastOrNull { AnnotationIO.paddedFrame(it).contains(start, touchTolerance) }
                    gestures.onSelect(hit?.id)
                    mode = if (hit != null) Mode.MOVE else Mode.PAN
                    origEdit = hit
                }
            }
        }

        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.count { it.pressed }
            // A second finger (here, or consumed by the page strip) turns the gesture into zoom and scroll.
            if (pressed >= 2 || (mode != Mode.TRANSFORM && event.changes.any { it.isConsumed })) {
                if (mode == Mode.MOVE || mode == Mode.RESIZE || mode == Mode.GROUP_MOVE) gestures.onCommitPreview()
                mode = Mode.TRANSFORM
                ink.clear()
                onTransform(event)
                if (event.changes.none { it.pressed }) break
                continue
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
            if (!change.pressed || pressed == 0) break
            if (mode == Mode.TRANSFORM) continue
            if (!moved && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
            val p = toPage(change.position)
            when (mode) {
                Mode.PEN, Mode.ERASE, Mode.LASSO -> ink.stroke = ink.stroke + p
                Mode.GROUP_MOVE -> if (moved) gestures.onGroupMove(p.x - start.x, p.y - start.y)
                Mode.SHAPE, Mode.MARKUP, Mode.AREA -> ink.dragEnd = p
                Mode.MOVE -> if (moved) origEdit?.let { gestures.onPreview(it.translate(p.x - start.x, p.y - start.y)) }
                Mode.RESIZE -> origEdit?.let { e ->
                    val corners = e.frame().corners()
                    val opposite = corners[(handle + 2) % 4]
                    val box = Box.of(opposite, p)
                    if (box.width > 4f && box.height > 4f) gestures.onPreview(resized(e, box))
                }
                Mode.PAN -> {
                    val screen = change.position + origin()
                    velocity.addPosition(change.uptimeMillis, screen)
                    onPan(screen - lastScreen)
                    lastScreen = screen
                }
                else -> Unit
            }
            last = change.position
            change.consume()
        }

        val end = toPage(last)
        val t = tool()
        when (mode) {
            Mode.PEN -> if (ink.stroke.isNotEmpty()) {
                if (t == Tool.SMART_PEN) gestures.onSmartStroke(ink.stroke) else gestures.onStroke(ink.stroke)
            }
            Mode.ERASE -> gestures.onErase(ink.stroke)
            Mode.LASSO -> gestures.onLasso(if (moved) ink.stroke else emptyList())
            Mode.GROUP_MOVE -> if (moved) gestures.onCommitPreview()
            Mode.AREA -> if (moved) gestures.onArea(start, end)
            Mode.SHAPE -> if (moved) {
                if (t == Tool.MEASURE_DISTANCE) gestures.onMeasureLine(start, end)
                else gestures.onShape(shapeKind(t), start, end)
            }
            Mode.MARKUP -> if (moved) {
                if (t == Tool.REPLACE) gestures.onReplace(start, end)
                else gestures.onMarkup(markupKind(t), start, end)
            }
            Mode.TAP -> if (!moved) gestures.onTap(start)
            Mode.MOVE, Mode.RESIZE -> if (moved) gestures.onCommitPreview()
            Mode.PAN -> if (moved) onPanEnd(velocity.calculateVelocity())
            else -> Unit
        }
        ink.clear()
    }
}

/**
 * The page picture, its edits and the gesture preview, drawn into the full size of this composable.
 * [zoom] keeps selection handles and dashes the same size on screen in the single-page layout.
 */
@Composable
private fun PageLayer(
    c: PageContent,
    ink: PageInk,
    tool: Tool,
    previewColor: Int,
    previewWidth: Float,
    renderer: EditRenderer,
    images: Map<String, ImageBitmap>,
    renderWidth: Int,
    generation: Int,
    pageBitmap: suspend (Int, Int) -> Bitmap?,
    zoom: Float,
    modifier: Modifier,
) {
    val pw = c.pageSize.width.toFloat()
    var bitmap by remember(c.page) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(c.page, renderWidth, generation) { pageBitmap(c.page, renderWidth)?.let { bitmap = it } }
    Box(modifier.background(Color.White)) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
        Canvas(Modifier.fillMaxSize()) {
            val k = size.width / pw
            with(renderer) {
                c.edits.forEach { draw(it, k, images::get) }
                c.selected?.let { selection(it, k, zoom) }
            }
            // Lasso group: a dashed frame around each picked edit and around all of them.
            if (c.group.isNotEmpty()) {
                val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f * k / zoom, 4f * k / zoom))
                val blue = Color(0xFF1565C0)
                c.group.forEach { e ->
                    val f = e.frame().inset(-3f)
                    drawRect(blue.copy(alpha = 0.6f), f.topLeft(k), f.size(k), style = Stroke(1f * k / zoom, pathEffect = dash))
                }
                Box.bounding(c.group.flatMap { it.frame().corners() })?.inset(-8f)?.let { f ->
                    drawRect(blue.copy(alpha = 0.08f), f.topLeft(k), f.size(k))
                    drawRect(blue, f.topLeft(k), f.size(k), style = Stroke(1.5f * k / zoom, pathEffect = dash))
                }
            }
            // Previews of the gesture in progress.
            val color = Color(previewColor)
            val vertices = c.vertices
            if (vertices.isNotEmpty()) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(vertices[0].x * k, vertices[0].y * k)
                    vertices.drop(1).forEach { lineTo(it.x * k, it.y * k) }
                }
                drawPath(path, Color(0xFF1565C0), style = Stroke(1.5f * k))
                vertices.forEach { drawCircle(Color(0xFF1565C0), 3f * k, it.offset(k)) }
            }
            val stroke = ink.stroke
            if (tool == Tool.LASSO && stroke.size > 1) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(stroke[0].x * k, stroke[0].y * k)
                    stroke.drop(1).forEach { lineTo(it.x * k, it.y * k) }
                    close()
                }
                drawPath(path, Color(0x221565C0))
                drawPath(path, Color(0xFF1565C0), style = Stroke(1.5f * k / zoom, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f * k / zoom, 4f * k / zoom))))
            } else if (tool == Tool.ERASER && stroke.isNotEmpty()) {
                stroke.forEach { drawCircle(Color(0x55E53935), 6f * k, it.offset(k)) }
            } else if (stroke.size > 1) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(stroke[0].x * k, stroke[0].y * k)
                    stroke.drop(1).forEach { lineTo(it.x * k, it.y * k) }
                }
                drawPath(path, color, style = Stroke(previewWidth * k, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
            val a = ink.dragStart
            val b = ink.dragEnd
            if (a != null && b != null) {
                val markupRects = if (tool == Tool.HIGHLIGHT || tool == Tool.UNDERLINE || tool == Tool.STRIKEOUT || tool == Tool.REPLACE) {
                    c.pageText?.select(a, b).orEmpty().ifEmpty { listOf(Box.of(a, b)) }
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

/** One page at a time, fitted to the screen; pinch to zoom, drag with the hand tool to move around. */
@Composable
fun EditorCanvas(
    page: Int,
    pageSize: Size,
    edits: List<Edit>,
    selected: Edit?,
    tool: Tool,
    group: List<Edit> = emptyList(),
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
    val ink = remember { PageInk() }
    val images = rememberImages(edits)
    val content = PageContent(page, pageSize, edits, selected, group, vertices, pageText, gestures)
    val currentContent by rememberUpdatedState(content)
    val currentTool by rememberUpdatedState(tool)

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

        val gestureModifier = Modifier.pageGestures(
            key = Triple(page, ppp, origin),
            ink = ink,
            content = { currentContent },
            tool = { currentTool },
            toPage = { pos ->
                val c = (pos - pan) / zoom
                Pt((c.x - origin.x) / ppp, (c.y - origin.y) / ppp)
            },
            pointsPerPx = { 1f / (zoom * ppp) },
            onTransform = { event ->
                val factor = event.calculateZoom()
                val centroid = event.calculateCentroid()
                val newZoom = (zoom * factor).coerceIn(1f, MAX_ZOOM)
                val ratio = newZoom / zoom
                pan = if (newZoom <= 1f) Offset.Zero else centroid - (centroid - pan) * ratio + event.calculatePan()
                zoom = newZoom
                event.changes.forEach { it.consume() }
            },
            onPan = { delta -> if (zoom > 1f) pan += delta },
        )

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
            val renderWidth = (pw * ppp * 2).roundToInt().coerceIn(1, MAX_RENDER_PX)
            val sizeDp = with(LocalDensity.current) { androidx.compose.ui.unit.DpSize((pw * ppp).toDp(), (ph * ppp).toDp()) }
            PageLayer(
                c = content, ink = ink, tool = tool, previewColor = previewColor, previewWidth = previewWidth,
                renderer = renderer, images = images, renderWidth = renderWidth, generation = generation,
                pageBitmap = pageBitmap, zoom = zoom,
                modifier = Modifier.offset { IntOffset(origin.x.roundToInt(), origin.y.roundToInt()) }.size(sizeDp),
            )
        }
    }
}

/**
 * All pages one under another, like the viewer. One finger uses the current tool; two fingers scroll and
 * zoom; the hand tool (or Select on an empty spot) scrolls with one finger. Pages are laid out at the zoomed
 * width, so they are rendered sharp instead of being scaled up.
 * [onVisiblePage] reports the page in the middle of the screen.
 */
@Composable
fun EditorStrip(
    listState: LazyListState,
    pageSizes: List<Size>,
    content: (Int) -> PageContent,
    tool: Tool,
    previewColor: Int,
    previewWidth: Float,
    textPaint: Paint,
    pageBitmap: suspend (Int, Int) -> Bitmap?,
    onVisiblePage: (Int) -> Unit,
    modifier: Modifier = Modifier,
    generation: Int = 0,
    allEdits: List<Edit> = emptyList(),
) {
    val renderer = remember(textPaint) { EditRenderer(textPaint) }
    val images = rememberImages(allEdits)
    val scope = rememberCoroutineScope()
    val hScroll = rememberScrollState()
    var zoom by remember { mutableFloatStateOf(1f) }
    var pendingX by remember { mutableStateOf<Float?>(null) }
    val currentTool by rememberUpdatedState(tool)
    val currentContent by rememberUpdatedState(content)
    val density = LocalDensity.current
    val currentOnVisiblePage by rememberUpdatedState(onVisiblePage)

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.firstOrNull { it.offset <= middle && it.offset + it.size >= middle }?.index
                ?: listState.firstVisibleItemIndex
        }.collect { currentOnVisiblePage(it) }
    }
    // Horizontal position after a zoom step, applied once the wider layout exists.
    LaunchedEffect(pendingX) {
        pendingX?.let {
            withFrame()
            hScroll.scrollTo(it.roundToInt().coerceIn(0, hScroll.maxValue))
            pendingX = null
        }
    }

    fun moveBy(delta: Offset) {
        listState.dispatchRawDelta(-delta.y)
        hScroll.dispatchRawDelta(-delta.x)
    }

    fun fling(velocity: androidx.compose.ui.unit.Velocity) {
        scope.launch {
            listState.scroll {
                var last = 0f
                AnimationState(0f, -velocity.y).animateDecay(exponentialDecay()) {
                    val delta = value - last
                    val consumed = scrollBy(delta)
                    last = value
                    // Stop at the first or last page.
                    if (kotlin.math.abs(delta - consumed) > 0.5f) cancelAnimation()
                }
            }
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .background(Color(0xFF9E9E9E))
            // Two fingers anywhere: zoom and scroll; consumed here so the pages drop their tool gesture.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } < 2) continue
                        val factor = event.calculateZoom()
                        val centroid = event.calculateCentroid()
                        val pan = event.calculatePan()
                        val newZoom = (zoom * factor).coerceIn(1f, MAX_ZOOM)
                        val f = newZoom / zoom
                        if (f != 1f) {
                            // Keep the point between the fingers in place.
                            listState.dispatchRawDelta((f - 1f) * (listState.firstVisibleItemScrollOffset + centroid.y))
                            pendingX = (hScroll.value + centroid.x) * f - centroid.x
                            zoom = newZoom
                        }
                        moveBy(pan)
                        event.changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        val viewportW = constraints.maxWidth.toFloat()
        val gap = with(density) { 8.dp.toPx() }
        val pageWidthPx = viewportW * zoom - 2 * gap
        val stripWidth = with(density) { (viewportW * zoom).toDp() }
        Box(Modifier.fillMaxSize().horizontalScroll(hScroll, enabled = false)) {
            LazyColumn(
                state = listState,
                userScrollEnabled = false,
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                modifier = Modifier.width(stripWidth).fillMaxHeight(),
            ) {
                items(pageSizes.size, key = { it }) { i ->
                    val size = pageSizes[i]
                    val ppp = pageWidthPx / size.width
                    val ink = remember { PageInk() }
                    var root by remember { mutableStateOf(Offset.Zero) }
                    val c = content(i)
                    // Render at a stepped width so zooming does not re-render on every frame.
                    val renderWidth = (((pageWidthPx / 256f).roundToInt().coerceAtLeast(1)) * 256).coerceIn(256, MAX_RENDER_PX)
                    val sizeDp = with(density) { androidx.compose.ui.unit.DpSize(pageWidthPx.toDp(), (size.height * ppp).toDp()) }
                    PageLayer(
                        c = c, ink = ink, tool = tool, previewColor = previewColor, previewWidth = previewWidth,
                        renderer = renderer, images = images, renderWidth = renderWidth, generation = generation,
                        pageBitmap = pageBitmap, zoom = 1f,
                        modifier = Modifier
                            .size(sizeDp)
                            .onGloballyPositioned { root = it.positionInRoot() }
                            .pageGestures(
                                key = Pair(i, ppp),
                                ink = ink,
                                content = { currentContent(i) },
                                tool = { currentTool },
                                toPage = { pos -> Pt(pos.x / ppp, pos.y / ppp) },
                                pointsPerPx = { 1f / ppp },
                                onTransform = {},
                                onPan = ::moveBy,
                                onPanEnd = ::fling,
                                origin = { root },
                            ),
                    )
                }
            }
        }
    }
}

private suspend fun withFrame() = androidx.compose.runtime.withFrameNanos { }

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
