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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class Tool { SELECT, HIGHLIGHT, UNDERLINE, STRIKEOUT, REPLACE, PEN, RECT, OVAL, LINE, ARROW, TEXT, NOTE }

val Tool.usesWidth get() = this == Tool.PEN || this == Tool.RECT || this == Tool.OVAL || this == Tool.LINE || this == Tool.ARROW

object Palette {
    const val BLACK = 0xFF212121.toInt()
    const val RED = 0xFFC62828.toInt()
    const val BLUE = 0xFF1565C0.toInt()
    const val GREEN = 0xFF2E7D32.toInt()
    const val YELLOW = 0xFFFFEB3B.toInt()
    const val ORANGE = 0xFFEF6C00.toInt()
    const val PURPLE = 0xFF6A1B9A.toInt()
    val all = listOf(BLACK, RED, BLUE, GREEN, YELLOW, ORANGE, PURPLE)
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
    val tool: Tool = Tool.SELECT,
    val style: EditStyle = EditStyle(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val dirty: Boolean = false,
    val generation: Int = 0,
    val replace: ReplacePrompt? = null,
) {
    val selected: Edit? get() = edits.firstOrNull { it.id == selectedId }
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

    fun selectTool(tool: Tool) = _state.update { it.copy(tool = tool, selectedId = null) }

    fun select(id: Long?) = _state.update { it.copy(selectedId = id) }

    fun setColor(color: Int) {
        val selected = state.value.selected
        if (selected != null) {
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
        add(measured(TextEdit(0, page, Box(at.x, at.y, at.x, at.y), text, s.textSize, s.color(Tool.TEXT))))
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
                    PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                        annotations.write(doc, edits)
                        doc.save(out)
                    }
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
            _state.update { it.copy(busy = false, dirty = if (result.isSuccess) false else it.dirty) }
            if (result.isSuccess) after() else _events.send(EditorEvent.Message(R.string.error_save))
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
