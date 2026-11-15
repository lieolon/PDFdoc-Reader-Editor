package app.pdfdoc.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.pdfdoc.R
import app.pdfdoc.core.IoDispatcher
import app.pdfdoc.data.files.DocumentEvents
import app.pdfdoc.data.files.FileStore
import app.pdfdoc.data.files.Images
import app.pdfdoc.data.pdf.AnnotationIO
import app.pdfdoc.data.pdf.PdfRenderSession
import app.pdfdoc.data.pdf.PdfRenderSessionFactory
import app.pdfdoc.data.pdf.RenderCopyBuilder
import app.pdfdoc.data.pdf.TextExtractor
import app.pdfdoc.data.pdf.TextMetrics
import app.pdfdoc.data.signature.SignatureRepository
import app.pdfdoc.domain.edit.Box
import app.pdfdoc.domain.edit.CoverEdit
import app.pdfdoc.domain.edit.TextLayout
import app.pdfdoc.domain.edit.Edit
import app.pdfdoc.domain.edit.EditHistory
import app.pdfdoc.domain.edit.ImageEdit
import app.pdfdoc.domain.edit.InkEdit
import app.pdfdoc.domain.edit.MarkupEdit
import app.pdfdoc.domain.edit.MarkupKind
import app.pdfdoc.domain.edit.NoteEdit
import app.pdfdoc.domain.edit.Pt
import app.pdfdoc.domain.edit.ShapeEdit
import app.pdfdoc.domain.edit.ShapeKind
import app.pdfdoc.domain.edit.StampEdit
import app.pdfdoc.domain.edit.TextEdit
import app.pdfdoc.domain.edit.appliedOnSave
import app.pdfdoc.domain.repo.DocumentRepository
import app.pdfdoc.domain.text.PageText
import app.pdfdoc.ui.navigation.EditorRoute
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class Tool {
    SELECT, HAND, LASSO, HIGHLIGHT, UNDERLINE, STRIKEOUT, REPLACE, PEN, SMART_PEN, ERASER, RECT, OVAL, LINE, ARROW, TEXT, NOTE,
    REDACT, LINK, FIELD, MEASURE_DISTANCE, MEASURE_AREA, IMAGE_EDIT,
}

val Tool.usesWidth get() = this == Tool.PEN || this == Tool.SMART_PEN || this == Tool.RECT || this == Tool.OVAL || this == Tool.LINE || this == Tool.ARROW

/** Tools that mark a rectangle by dragging. */
val Tool.marksArea get() = this == Tool.REDACT || this == Tool.LINK || this == Tool.FIELD

/** A measuring scale: [value] [unit] per centimeter on paper. */
data class MeasureScale(val value: Float = 1f, val unit: String = "cm") {
    val unitsPerPoint: Float get() = value / app.pdfdoc.domain.edit.Measure.POINTS_PER_CM
    fun encode() = "$value|$unit"

    companion object {
        fun decode(raw: String) = raw.split('|').let { p ->
            MeasureScale(p.getOrNull(0)?.toFloatOrNull()?.takeIf { it > 0f } ?: 1f, p.getOrNull(1)?.ifBlank { null } ?: "cm")
        }
    }
}

/** A rectangle drawn with the link or field tool, waiting for its settings. */
data class AreaPrompt(val tool: Tool, val page: Int, val box: Box)

object Palette {
    const val BLACK = 0xFF212121.toInt()
    const val RED = 0xFFC62828.toInt()
    const val BLUE = 0xFF1565C0.toInt()
    const val GREEN = 0xFF2E7D32.toInt()
    const val YELLOW = 0xFFFFEB3B.toInt()
    const val ORANGE = 0xFFEF6C00.toInt()
    const val PURPLE = 0xFF6A1B9A.toInt()
    const val GREY = 0xFF757575.toInt()
    const val LIGHT_GREY = 0xFFBDBDBD.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    val all = listOf(BLACK, GREY, LIGHT_GREY, WHITE, RED, BLUE, GREEN, YELLOW, ORANGE, PURPLE)
}

data class EditStyle(
    val colors: Map<Tool, Int> = mapOf(
        Tool.HIGHLIGHT to Palette.YELLOW,
        Tool.UNDERLINE to Palette.BLUE,
        Tool.NOTE to Palette.YELLOW,
        Tool.TEXT to Palette.BLACK,
    ),
    val width: Float = 2.5f,
    val alpha: Float = 1f,
    val textSize: Float = 14f,
) {
    fun color(tool: Tool) = colors[tool] ?: Palette.RED
}

/** Text picked for replacement, waiting for the new wording. */
data class ReplacePrompt(val page: Int, val rects: List<Box>, val text: String)

data class EditorUiState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val busy: Boolean = false,
    val name: String = "",
    val pageSizes: List<Size> = emptyList(),
    val page: Int = 0,
    val edits: List<Edit> = emptyList(),
    val selectedId: Long? = null,
    /** Edits picked together with the lasso. */
    val group: Set<Long> = emptySet(),
    val tool: Tool = Tool.SELECT,
    val style: EditStyle = EditStyle(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val dirty: Boolean = false,
    val generation: Int = 0,
    val replace: ReplacePrompt? = null,
    val area: AreaPrompt? = null,
    val fieldKind: app.pdfdoc.domain.edit.FieldKind = app.pdfdoc.domain.edit.FieldKind.TEXT,
    /** Corners placed so far with the area/perimeter measuring tool. */
    val vertices: List<Pt> = emptyList(),
    val scale: MeasureScale = MeasureScale(),
    val customStamps: List<String> = emptyList(),
    val author: String = "",
    /** Pages one under another instead of one at a time. */
    val strip: Boolean = true,
    val bar: BarLayout = BarLayout(),
    val recent: List<BarItem> = emptyList(),
    val showRecent: Boolean = true,
) {
    val selected: Edit? get() = edits.firstOrNull { it.id == selectedId }
    val grouped: List<Edit> get() = edits.filter { it.id in group }
    val pageEdits: List<Edit> get() = edits.filter { it.page == page }
}

sealed interface EditorEvent {
    data class Message(@StringRes val text: Int) : EditorEvent
    data class Open(val uri: Uri) : EditorEvent
    data object Close : EditorEvent
}

@HiltViewModel
class EditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val files: FileStore,
    private val docs: app.pdfdoc.data.files.DocumentStore,
    private val annotations: AnnotationIO,
    private val renderCopies: RenderCopyBuilder,
    private val renderers: PdfRenderSessionFactory,
    private val metrics: TextMetrics,
    private val signatures: SignatureRepository,
    private val documents: DocumentRepository,
    private val docEvents: DocumentEvents,
    private val redactor: app.pdfdoc.data.pdf.Redactor,
    private val formBuilder: app.pdfdoc.data.pdf.PdfFormBuilder,
    private val pageImages: app.pdfdoc.data.pdf.PageImages,
    private val settings: app.pdfdoc.data.settings.SettingsRepository,
    private val ocr: app.pdfdoc.data.ocr.OcrEngine,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val uri: Uri = Uri.parse(savedStateHandle.toRoute<EditorRoute>().uri)

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _events = Channel<EditorEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val history = EditHistory()
    /** Edits shown while a move/resize gesture is in progress; committed to [history] on release. */
    private var transient: List<Edit>? = null
    private var nextId = 1L

    private var working: File? = null
    private var renderFile: File? = null
    private val imageDir = File(files.workDir, "images-${UUID.randomUUID()}")
    private var session: PdfRenderSession? = null
    private val bitmaps = LruCache<String, Bitmap>(8)

    private val textMutex = Mutex()
    private var textDoc: PDDocument? = null
    private val pageTexts = HashMap<Int, PageText>()

    init {
        viewModelScope.launch {
            runCatching {
                withContext(io) {
                    val name = files.query(uri).name
                    val work = docs.openWorkingCopy(uri)
                    working = work
                    val existing = PDDocument.load(work, MemoryUsageSetting.setupTempFileOnly()).use {
                        annotations.read(it, imageDir)
                    }
                    // Our own annotations are drawn live from the edit model, so leave them out of the page image.
                    val flat = files.newWorkFile()
                    val render = if (renderCopies.build(work, flat, excludeOwn = true)) flat else work.also { flat.delete() }
                    renderFile = render
                    session = renderers.open(render)
                    Triple(name, existing, session!!.pageSizes)
                }
            }.onSuccess { (name, existing, sizes) ->
                history.reset(existing.map { it.withId(nextId++) })
                _state.update {
                    it.copy(loading = false, name = name, pageSizes = sizes, edits = history.current)
                }
            }.onFailure {
                _state.update { it.copy(loading = false, failed = true) }
            }
        }
        viewModelScope.launch {
            settings.measureScale.collect { raw -> _state.update { it.copy(scale = MeasureScale.decode(raw)) } }
        }
        viewModelScope.launch {
            settings.customStamps.collect { list -> _state.update { it.copy(customStamps = list) } }
        }
        viewModelScope.launch {
            settings.authorName.collect { name -> _state.update { it.copy(author = name) } }
        }
        viewModelScope.launch {
            settings.editorStrip.collect { on -> _state.update { it.copy(strip = on) } }
        }
        viewModelScope.launch {
            settings.editorBar.collect { raw -> _state.update { it.copy(bar = BarLayout.decode(raw)) } }
        }
        viewModelScope.launch {
            settings.editorRecent.collect { raw ->
                val items = raw.split(',').mapNotNull { n -> BarItem.entries.firstOrNull { it.name == n } }
                _state.update { it.copy(recent = items) }
            }
        }
        viewModelScope.launch {
            settings.editorShowRecent.collect { on -> _state.update { it.copy(showRecent = on) } }
        }
    }

    /** Re-renders pages after the working copy changed underneath (saved redactions, detached images). */
    private suspend fun reloadRender() {
        val work = working ?: return
        val (render, newSession) = withContext(io) {
            val flat = files.newWorkFile()
            val render = if (renderCopies.build(work, flat, excludeOwn = true)) flat else work.also { flat.delete() }
            render to renderers.open(render)
        }
        textMutex.withLock {
            textDoc?.close()
            textDoc = null
            pageTexts.clear()
            renderFile?.takeIf { it != work && it != render }?.delete()
            renderFile = render
        }
        session?.close()
        session = newSession
        bitmaps.evictAll()
        _state.update { it.copy(pageSizes = newSession.pageSizes, generation = it.generation + 1) }
    }

    // region Rendering

    val typeface get() = metrics.typeface

    suspend fun pageBitmap(page: Int, widthPx: Int): Bitmap? {
        val key = "$page:$widthPx"
        bitmaps.get(key)?.let { return it }
        val current = session ?: return null
        return withContext(io) { current.render(page, widthPx) }?.also { bitmaps.put(key, it) }
    }

    suspend fun pageText(page: Int): PageText? = withContext(io) {
        textMutex.withLock {
            pageTexts[page]?.let { return@withContext it }
            val file = renderFile ?: return@withContext null
            val doc = textDoc ?: PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).also { textDoc = it }
            runCatching { TextExtractor.extract(doc, page) }.getOrNull()?.also { pageTexts[page] = it }
        }
    }

    // endregion

    // region Navigation and style

    fun goToPage(page: Int) = _state.update {
        it.copy(page = page.coerceIn(0, (it.pageSizes.size - 1).coerceAtLeast(0)), selectedId = null)
    }

    /** The page shown in the middle of the page strip; keeps the selection, unlike [goToPage]. */
    fun showPage(page: Int) {
        if (page != state.value.page) _state.update { it.copy(page = page.coerceIn(0, (it.pageSizes.size - 1).coerceAtLeast(0))) }
    }

    fun selectTool(tool: Tool) {
        _state.update { it.copy(tool = tool, selectedId = null, group = emptySet(), vertices = emptyList()) }
        BarItem.of(tool)?.let(::used)
    }

    // region Bar and layout

    /** Remembers [item] as recently used, for the "Recent" group at the start of the bar. */
    fun used(item: BarItem) {
        if (item == BarItem.SELECT || item == BarItem.HAND) return
        val list = (listOf(item) + state.value.recent.filterNot { it == item }).take(RECENT_COUNT)
        if (list == state.value.recent) return
        _state.update { it.copy(recent = list) }
        viewModelScope.launch { settings.setEditorRecent(list.joinToString(",") { it.name }) }
    }

    fun setBar(layout: BarLayout) {
        _state.update { it.copy(bar = layout) }
        viewModelScope.launch { settings.setEditorBar(layout.encode()) }
    }

    fun setShowRecent(on: Boolean) = viewModelScope.launch { settings.setEditorShowRecent(on) }

    fun setStrip(on: Boolean) = viewModelScope.launch { settings.setEditorStrip(on) }

    // endregion

    // region Lasso

    /** Picks every edit on [page] whose centre lies inside the closed [path]. */
    fun lasso(page: Int, path: List<Pt>) {
        if (path.size < 3) {
            _state.update { it.copy(group = emptySet()) }
            return
        }
        val ids = history.current.filter { it.page == page && inside(it.frame().center, path) }.map { it.id }.toSet()
        _state.update { it.copy(group = ids, selectedId = null) }
    }

    private fun inside(p: Pt, poly: List<Pt>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    /** Shows the lasso group moved by ([dx], [dy]) until [commitPreview]. */
    fun previewGroupMove(dx: Float, dy: Float) {
        val ids = state.value.group
        transient = history.current.map { if (it.id in ids) it.translate(dx, dy) else it }
        publish()
    }

    fun deleteGroup() {
        val ids = state.value.group
        if (ids.isEmpty()) return
        commit(history.current.filterNot { it.id in ids })
        _state.update { it.copy(group = emptySet()) }
    }

    fun recolorGroup(color: Int) {
        val ids = state.value.group
        commit(history.current.map { if (it.id in ids) it.withColor(color) else it })
    }

    fun clearGroup() = _state.update { it.copy(group = emptySet()) }

    /**
     * Turns handwriting into typed text: the ink strokes of the lasso group (or the selected ink) are drawn
     * black on white and recognized offline, then replaced with a text box in the same place and colour.
     */
    fun handwritingToText() {
        val s = state.value
        val inks = (s.grouped.ifEmpty { listOfNotNull(s.selected) }).filterIsInstance<InkEdit>()
        if (inks.isEmpty()) {
            viewModelScope.launch { _events.send(EditorEvent.Message(R.string.handwriting_none)) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val strokeHeight = inks.flatMap { it.strokes }.map { Box.bounding(it)!!.height }.sorted().let { it[it.size / 2] }.coerceAtLeast(4f)
            // Tesseract reads best at a letter height of about 60 px with a white margin around the text.
            val scale = (60f / strokeHeight).coerceIn(0.3f, 8f)
            val bounds = Box.bounding(inks.flatMap { it.strokes.flatten() })!!.inset(-strokeHeight * 0.6f)
            val text = withContext(io) {
                runCatching {
                    val bmp = Bitmap.createBitmap((bounds.width * scale).toInt().coerceAtLeast(1), (bounds.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bmp)
                    canvas.drawColor(android.graphics.Color.WHITE)
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.BLACK
                        style = android.graphics.Paint.Style.STROKE
                        strokeCap = android.graphics.Paint.Cap.ROUND
                        strokeJoin = android.graphics.Paint.Join.ROUND
                    }
                    inks.forEach { ink ->
                        paint.strokeWidth = 6f
                        ink.strokes.forEach { stroke ->
                            val path = android.graphics.Path()
                            stroke.forEachIndexed { i, p ->
                                val x = (p.x - bounds.left) * scale
                                val y = (p.y - bounds.top) * scale
                                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            canvas.drawPath(path, paint)
                        }
                    }
                    val words = ocr.recognize(bmp, settings.ocrLanguages.first(), com.googlecode.tesseract.android.TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
                    // Words come in reading order; keep line breaks where the next word starts lower.
                    buildString {
                        var lastBottom = -1f
                        words.filter { it.confidence > 20f }.forEach { w ->
                            if (isNotEmpty()) append(if (w.box.top > lastBottom) '\n' else ' ')
                            append(w.text)
                            lastBottom = w.box.bottom
                        }
                    }
                }.getOrDefault("")
            }
            _state.update { it.copy(busy = false) }
            if (text.isBlank()) {
                _events.send(EditorEvent.Message(R.string.handwriting_failed))
                return@launch
            }
            val ids = inks.map { it.id }.toSet()
            val size = (strokeHeight * 0.9f).coerceIn(10f, 48f)
            val typed = measured(TextEdit(nextId++, inks.first().page, Box(bounds.left + 12f, bounds.top + 12f, bounds.left + 12f, bounds.top + 12f), text, size, inks.first().color))
            commit(history.current.filterNot { it.id in ids } + typed)
            _state.update { it.copy(group = emptySet(), selectedId = typed.id, tool = Tool.SELECT) }
        }
    }

    // endregion

    fun selectFieldKind(kind: app.pdfdoc.domain.edit.FieldKind) =
        _state.update { it.copy(tool = Tool.FIELD, fieldKind = kind, selectedId = null) }

    fun select(id: Long?) = _state.update { it.copy(selectedId = id) }

    fun setColor(color: Int) {
        val selected = state.value.selected
        if (state.value.group.isNotEmpty()) {
            recolorGroup(color)
        } else if (selected != null) {
            replace(selected.withColor(color))
        } else {
            _state.update { it.copy(style = it.style.copy(colors = it.style.colors + (it.tool to color))) }
        }
    }

    fun setWidth(width: Float) {
        when (val selected = state.value.selected) {
            is InkEdit -> replace(selected.copy(width = width))
            is ShapeEdit -> replace(selected.copy(width = width))
            else -> _state.update { it.copy(style = it.style.copy(width = width)) }
        }
    }

    fun setAlpha(alpha: Float) {
        when (val selected = state.value.selected) {
            is InkEdit -> replace(selected.copy(alpha = alpha))
            is ShapeEdit -> replace(selected.copy(alpha = alpha))
            else -> _state.update { it.copy(style = it.style.copy(alpha = alpha)) }
        }
    }

    fun setTextSize(size: Float) {
        when (val selected = state.value.selected) {
            is TextEdit -> replace(measured(selected.copy(size = size)))
            else -> _state.update { it.copy(style = it.style.copy(textSize = size)) }
        }
    }

    // endregion

    // region Adding edits

    fun addStroke(page: Int, points: List<Pt>) {
        val s = state.value.style
        add(InkEdit(0, page, listOf(points), s.color(Tool.PEN), s.width, s.alpha), select = false)
    }

    /** A stroke of the smart pen: straightened into a line, rectangle or ellipse when it looks like one. */
    fun addSmartStroke(page: Int, points: List<Pt>) {
        val shape = app.pdfdoc.domain.edit.ShapeRecognizer.recognize(points)
        if (shape == null) {
            val s = state.value.style
            add(InkEdit(0, page, listOf(points), s.color(Tool.SMART_PEN), s.width, s.alpha), select = false)
            return
        }
        val s = state.value.style
        add(ShapeEdit(0, page, shape.first, shape.second.first, shape.second.second, s.color(Tool.SMART_PEN), s.width, s.alpha), select = false)
    }

    /** Erases ink strokes and shapes touched by the eraser [path]. One undo step. */
    fun erase(page: Int, path: List<Pt>) {
        if (path.isEmpty()) return
        val radius = 6f
        var changed = false
        val next = history.current.mapNotNull { e ->
            if (e.page != page) return@mapNotNull e
            when (e) {
                is InkEdit -> {
                    val left = app.pdfdoc.domain.edit.eraseStrokes(e.strokes, path, radius + e.width / 2)
                    if (left.size != e.strokes.size) changed = true
                    if (left.isEmpty()) null else e.copy(strokes = left)
                }
                is ShapeEdit, is app.pdfdoc.domain.edit.MeasureEdit -> {
                    val hit = path.any { AnnotationIO.paddedFrame(e).contains(it, radius) } && touchesOutline(e, path, radius)
                    if (hit) changed = true
                    if (hit) null else e
                }
                else -> e
            }
        }
        if (changed) commit(next)
    }

    private fun touchesOutline(e: Edit, path: List<Pt>, radius: Float): Boolean {
        val outline: List<Pt> = when (e) {
            is ShapeEdit -> when (e.kind) {
                ShapeKind.LINE, ShapeKind.ARROW -> listOf(e.start, e.end)
                else -> e.frame().corners().let { it + it.first() }
            }
            is app.pdfdoc.domain.edit.MeasureEdit -> e.points
            else -> return true
        }
        return path.any { p -> outline.zipWithNext().any { (a, b) -> app.pdfdoc.domain.edit.distanceToSegment(p, a, b) <= radius } }
    }

    /** A rectangle drawn with the redaction, link or form field tool. */
    fun addArea(page: Int, start: Pt, end: Pt) {
        val box = Box.of(start, end)
        if (box.width < 4f || box.height < 4f) return
        when (state.value.tool) {
            Tool.REDACT -> add(app.pdfdoc.domain.edit.RedactEdit(0, page, box), select = false)
            Tool.LINK, Tool.FIELD -> _state.update { it.copy(area = AreaPrompt(it.tool, page, box)) }
            else -> Unit
        }
    }

    fun cancelArea() = _state.update { it.copy(area = null) }

    fun confirmLink(url: String?, targetPage: Int) {
        val area = state.value.area ?: return
        _state.update { it.copy(area = null) }
        add(app.pdfdoc.domain.edit.LinkEdit(0, area.page, area.box, url?.trim()?.ifEmpty { null }, targetPage), select = false)
    }

    fun confirmField(name: String, options: List<String>, group: String) {
        val area = state.value.area ?: return
        _state.update { it.copy(area = null) }
        add(app.pdfdoc.domain.edit.FieldEdit(0, area.page, area.box, state.value.fieldKind, name, options, group), select = false)
    }

    fun addMeasureLine(page: Int, start: Pt, end: Pt) {
        if (app.pdfdoc.domain.edit.distance(start, end) < 3f) return
        val scale = state.value.scale
        add(app.pdfdoc.domain.edit.MeasureEdit(0, page, app.pdfdoc.domain.edit.MeasureKind.DISTANCE, listOf(start, end), Palette.BLUE, scale.unitsPerPoint, scale.unit), select = false)
    }

    fun addVertex(at: Pt) = _state.update { it.copy(vertices = it.vertices + at) }

    fun cancelVertices() = _state.update { it.copy(vertices = emptyList()) }

    fun finishMeasure(kind: app.pdfdoc.domain.edit.MeasureKind) {
        val points = state.value.vertices
        val needed = if (kind == app.pdfdoc.domain.edit.MeasureKind.AREA) 3 else 2
        if (points.size < needed) return
        val scale = state.value.scale
        _state.update { it.copy(vertices = emptyList()) }
        add(app.pdfdoc.domain.edit.MeasureEdit(0, state.value.page, kind, points, Palette.BLUE, scale.unitsPerPoint, scale.unit), select = false)
    }

    fun setScale(scale: MeasureScale) {
        viewModelScope.launch { settings.setMeasureScale(scale.encode()) }
    }

    /** Turns the picture under [at] into an image object that can be moved, resized or deleted. */
    fun detachImageAt(page: Int, at: Pt) {
        val source = working ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = runCatching {
                withContext(io) {
                    val placement = pageImages.find(source, page).lastOrNull { it.removable && it.box.contains(at) }
                        ?: return@withContext null
                    val out = files.newWorkFile()
                    val bitmap = pageImages.detach(source, out, page, placement) ?: run { out.delete(); return@withContext null }
                    val file = Images.save(bitmap, imageDir, UUID.randomUUID().toString())
                    out.copyTo(source, overwrite = true)
                    out.delete()
                    placement to file
                }
            }.getOrNull()
            if (result == null) {
                _state.update { it.copy(busy = false) }
                _events.send(EditorEvent.Message(R.string.editor_no_image_here))
                return@launch
            }
            reloadRender()
            val (placement, file) = result
            _state.update { it.copy(busy = false) }
            add(ImageEdit(0, page, placement.box, placement.rotation, imagePath = file.absolutePath))
            // The page content changed: saving is needed even if the image is left as it is.
            _state.update { it.copy(dirty = true) }
        }
    }

    fun addShape(page: Int, kind: ShapeKind, start: Pt, end: Pt) {
        val tool = when (kind) {
            ShapeKind.RECT -> Tool.RECT
            ShapeKind.OVAL -> Tool.OVAL
            ShapeKind.LINE -> Tool.LINE
            ShapeKind.ARROW -> Tool.ARROW
        }
        val s = state.value.style
        add(ShapeEdit(0, page, kind, start, end, s.color(tool), s.width, s.alpha), select = false)
    }

    fun addMarkup(page: Int, kind: MarkupKind, from: Pt, to: Pt) {
        viewModelScope.launch {
            val text = pageText(page)
            val rects = text?.select(from, to).orEmpty().ifEmpty {
                // No text layer (e.g. a scan): mark the dragged rectangle instead.
                if (text == null || text.isEmpty) _events.send(EditorEvent.Message(R.string.editor_no_text_layer))
                listOf(Box.of(from, to)).filter { it.width > 2f && it.height > 2f }
            }
            if (rects.isEmpty()) return@launch
            val tool = when (kind) {
                MarkupKind.HIGHLIGHT -> Tool.HIGHLIGHT
                MarkupKind.UNDERLINE -> Tool.UNDERLINE
                MarkupKind.STRIKEOUT -> Tool.STRIKEOUT
            }
            add(MarkupEdit(0, page, kind, rects, state.value.style.color(tool)), select = false)
        }
    }

    /** Starts replacing the text between [from] and [to]; the UI then asks for the new text. */
    fun pickTextToReplace(page: Int, from: Pt, to: Pt) {
        viewModelScope.launch {
            val text = pageText(page)
            val rects = text?.select(from, to).orEmpty()
            if (rects.isEmpty()) {
                _events.send(EditorEvent.Message(R.string.editor_no_text_layer_replace))
                return@launch
            }
            _state.update { it.copy(replace = ReplacePrompt(page, rects, text!!.selectedText(from, to))) }
        }
    }

    fun cancelReplace() = _state.update { it.copy(replace = null) }

    /**
     * Covers the old text with the background color and writes [newText] on the first line,
     * at a size matching the original line height. One undo step.
     */
    fun confirmReplace(newText: String) {
        val prompt = state.value.replace ?: return
        _state.update { it.copy(replace = null) }
        val covers = prompt.rects.map { CoverEdit(nextId++, prompt.page, it.inset(-1f)) }
        val added = covers.toMutableList<Edit>()
        if (newText.isNotBlank()) {
            val first = prompt.rects.first()
            // Glyph boxes span 0.75 em above and 0.22 em below the baseline (see TextExtractor).
            val size = first.height / 0.97f
            val baseline = first.bottom - size * 0.22f
            val top = baseline - size * TextLayout.ASCENT
            val color = state.value.style.color(Tool.TEXT)
            added += measured(TextEdit(nextId++, prompt.page, Box(first.left, top, first.left, top), newText, size, color))
        }
        commit(history.current + added)
    }

    fun addText(page: Int, at: Pt, text: String) {
        if (text.isBlank()) return
        val s = state.value.style
        viewModelScope.launch {
            // Tapping on document text picks up its size and colour, so additions blend in.
            val match = matchTextStyle(page, at)
            val size = match?.first ?: s.textSize
            val color = match?.second ?: s.color(Tool.TEXT)
            add(measured(TextEdit(0, page, Box(at.x, at.y, at.x, at.y), text, size, color)))
        }
    }

    /** Size and colour of the document text right at [at], or null when the tap is not on text. */
    private suspend fun matchTextStyle(page: Int, at: Pt): Pair<Float, Int>? {
        val text = pageText(page) ?: return null
        val i = text.indexAt(at) ?: return null
        val word = text.rects(text.wordRange(i)).firstOrNull() ?: return null
        if (!word.inset(-6f).contains(at)) return null
        val size = (word.height / 0.97f).coerceIn(4f, 96f)
        val pageWidth = state.value.pageSizes.getOrNull(page)?.width ?: return size to state.value.style.color(Tool.TEXT)
        val scale = 2f
        val bitmap = pageBitmap(page, (pageWidth * scale).toInt()) ?: return size to state.value.style.color(Tool.TEXT)
        return size to (darkestColor(bitmap, word, scale) ?: state.value.style.color(Tool.TEXT))
    }

    /** The darkest pixel in [box] is the ink colour of the glyphs; anti-aliased edges are lighter. */
    private fun darkestColor(bitmap: Bitmap, box: Box, scale: Float): Int? {
        val l = (box.left * scale).toInt().coerceIn(0, bitmap.width - 1)
        val r = (box.right * scale).toInt().coerceIn(l + 1, bitmap.width)
        val t = (box.top * scale).toInt().coerceIn(0, bitmap.height - 1)
        val b = (box.bottom * scale).toInt().coerceIn(t + 1, bitmap.height)
        var best: Int? = null
        var bestLum = Int.MAX_VALUE
        for (y in t until b step 1) for (x in l until r step 1) {
            val c = bitmap.getPixel(x, y)
            val lum = (c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)
            if (lum < bestLum) { bestLum = lum; best = c or 0xFF000000.toInt() }
        }
        // A word with no ink darker than the paper is not worth matching.
        return best.takeIf { bestLum < 1800 }
    }

    fun addNote(page: Int, at: Pt, text: String) {
        if (text.isBlank()) return
        add(NoteEdit(0, page, at, text, state.value.style.color(Tool.NOTE)))
    }

    fun updateText(id: Long, text: String) {
        val edit = state.value.edits.firstOrNull { it.id == id } ?: return
        when {
            text.isBlank() -> deleteSelected()
            edit is TextEdit -> replace(measured(edit.copy(text = text)))
            edit is NoteEdit -> replace(edit.copy(text = text))
            edit is StampEdit -> replace(stampFor(edit.page, text, edit.color).copy(id = edit.id, box = edit.box.let {
                val fresh = stampFor(edit.page, text, edit.color).box
                Box(it.left, it.top, it.left + fresh.width * it.height / fresh.height, it.bottom)
            }))
        }
    }

    fun addStamp(text: String, color: Int) {
        if (text.isBlank()) return
        add(stampFor(state.value.page, text, color))
    }

    /** A user stamp, optionally with the author's name and today's date; it is remembered for next time. */
    fun addCustomStamp(text: String, color: Int, withName: Boolean, withDate: Boolean) {
        if (text.isBlank()) return
        val parts = listOfNotNull(
            text.trim().uppercase(),
            state.value.author.takeIf { withName && it.isNotBlank() },
            SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date()).takeIf { withDate },
        )
        add(stampFor(state.value.page, parts.joinToString(" · "), color))
        viewModelScope.launch { settings.setCustomStamps(listOf(text.trim().uppercase()) + state.value.customStamps) }
    }

    fun removeCustomStamp(text: String) {
        viewModelScope.launch { settings.setCustomStamps(state.value.customStamps - text) }
    }

    fun addDate() {
        val page = state.value.page
        val size = state.value.pageSizes.getOrNull(page) ?: return
        val date = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())
        addText(page, Pt(size.width * 0.4f, size.height * 0.45f), date)
    }

    fun addImage(source: Uri) = placeImage {
        Images.save(Images.decode(context, source), imageDir, UUID.randomUUID().toString())
    }

    fun addSignature(id: Long) = placeImage(widthFraction = 0.35f) {
        val bitmap = signatures.load(id) ?: error("signature $id")
        Images.save(bitmap, imageDir, UUID.randomUUID().toString())
    }

    private fun placeImage(widthFraction: Float = 0.5f, produce: suspend () -> File) {
        val page = state.value.page
        val pageSize = state.value.pageSizes.getOrNull(page) ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val file = runCatching { withContext(io) { produce() } }.getOrNull()
            _state.update { it.copy(busy = false) }
            if (file == null) {
                _events.send(EditorEvent.Message(R.string.editor_image_error))
                return@launch
            }
            val bounds = withContext(io) {
                android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    .also { android.graphics.BitmapFactory.decodeFile(file.absolutePath, it) }
            }
            val w = pageSize.width * widthFraction
            val h = w * bounds.outHeight / bounds.outWidth.coerceAtLeast(1)
            val left = (pageSize.width - w) / 2
            val top = (pageSize.height - h) / 2
            add(ImageEdit(0, page, Box(left, top, left + w, top + h), imagePath = file.absolutePath))
        }
    }

    private fun stampFor(page: Int, text: String, color: Int): StampEdit {
        val size = 18f
        val (tw, _) = metrics.measure(text, size)
        val w = tw + 2 * size * StampEdit.PADDING_RATIO
        val h = size * StampEdit.STAMP_HEIGHT_RATIO
        val pageSize = state.value.pageSizes.getOrNull(page)
        val left = ((pageSize?.width ?: 400) - w) / 2
        val top = ((pageSize?.height ?: 600) - h) / 3
        return StampEdit(0, page, Box(left, top, left + w, top + h), text, color)
    }

    private fun measured(edit: TextEdit): TextEdit {
        val (w, h) = metrics.measure(edit.text, edit.size)
        return edit.copy(box = Box(edit.box.left, edit.box.top, edit.box.left + w, edit.box.top + h))
    }

    private fun add(edit: Edit, select: Boolean = true) {
        val withId = edit.withId(nextId++)
        commit(history.current + withId)
        if (select) _state.update { it.copy(selectedId = withId.id, tool = Tool.SELECT) }
    }

    // endregion

    // region Changing edits

    /** Shows [edit] in place of the edit with the same id until [commitPreview] or [cancelPreview]. */
    fun preview(edit: Edit) {
        transient = history.current.map { if (it.id == edit.id) edit else it }
        publish()
    }

    fun commitPreview() {
        transient?.let { commit(it) }
        transient = null
        publish()
    }

    fun cancelPreview() {
        transient = null
        publish()
    }

    fun deleteSelected() {
        val id = state.value.selectedId ?: return
        commit(history.current.filterNot { it.id == id })
        _state.update { it.copy(selectedId = null) }
    }

    fun rotateSelected() {
        val image = state.value.selected as? ImageEdit ?: return
        val c = image.box.center
        val hw = image.box.height / 2
        val hh = image.box.width / 2
        replace(image.copy(rotation = (image.rotation + 90) % 360, box = Box(c.x - hw, c.y - hh, c.x + hw, c.y + hh)))
    }

    fun undo() {
        transient = null
        if (history.undo()) {
            _state.update { it.copy(selectedId = null) }
            publish(dirty = true)
        }
    }

    fun redo() {
        transient = null
        if (history.redo()) {
            _state.update { it.copy(selectedId = null) }
            publish(dirty = true)
        }
    }

    private fun replace(edit: Edit) = commit(history.current.map { if (it.id == edit.id) edit else it })

    private fun commit(list: List<Edit>) {
        history.push(list)
        publish(dirty = true)
    }

    private fun publish(dirty: Boolean? = null) = _state.update {
        it.copy(
            edits = transient ?: history.current,
            canUndo = history.canUndo,
            canRedo = history.canRedo,
            dirty = dirty ?: it.dirty,
        )
    }

    // endregion

    // region Saving

    fun save(closeAfter: Boolean = false) = write(uri) {
        docEvents.notifyChanged(uri.toString())
        _events.send(if (closeAfter) EditorEvent.Close else EditorEvent.Message(R.string.saved))
    }

    fun saveAs(target: Uri) = write(target) {
        working?.let { runCatching { documents.recordOpened(target, it) } }
        _events.send(EditorEvent.Open(target))
    }

    private fun write(target: Uri, after: suspend () -> Unit) {
        val source = working ?: return
        val edits = history.current
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = runCatching {
                withContext(io) {
                    val out = files.newWorkFile()
                    val redactions = edits.filterIsInstance<app.pdfdoc.domain.edit.RedactEdit>()
                    var input = source
                    var redacted: File? = null
                    if (redactions.isNotEmpty()) {
                        redacted = files.newWorkFile()
                        redactor.redact(source, redacted, redactions.groupBy({ it.page }, { it.box }))
                        input = redacted
                    }
                    PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                        annotations.write(doc, keptAfter(edits))
                        formBuilder.add(doc, edits.filterIsInstance<app.pdfdoc.domain.edit.FieldEdit>())
                        doc.save(out)
                    }
                    redacted?.delete()
                    docs.save(out, target, uri)
                    if (target == uri) {
                        source.delete()
                        working = out
                    } else {
                        // Keep the saved result for the "recently opened" thumbnail of the new file.
                        source.delete()
                        working = out
                    }
                }
            }
            if (result.isSuccess && edits.any { it.appliedOnSave }) {
                // Redactions and new fields are now part of the page; keep editing the rest.
                history.reset(keptAfter(edits))
                transient = null
                publish()
                reloadRender()
            }
            _state.update { it.copy(busy = false, dirty = if (result.isSuccess) false else it.dirty) }
            if (result.isSuccess) after() else _events.send(EditorEvent.Message(R.string.error_save))
        }
    }

    /** Edits that stay annotations after saving: not one-shot, and not inside a redacted region. */
    private fun keptAfter(edits: List<Edit>): List<Edit> {
        val redactions = edits.filterIsInstance<app.pdfdoc.domain.edit.RedactEdit>()
        return edits.filterNot { it.appliedOnSave }.filterNot { e ->
            val f = e.frame()
            redactions.any { r -> r.page == e.page && r.box.left < f.right && f.left < r.box.right && r.box.top < f.bottom && f.top < r.box.bottom }
        }
    }

    // endregion

    override fun onCleared() {
        session?.close()
        textDoc?.close()
        working?.delete()
        renderFile?.delete()
        imageDir.deleteRecursively()
    }
}

private const val RECENT_COUNT = 4
