package app.pdfdoc.ui.viewer

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.pdfdoc.R
import app.pdfdoc.core.IoDispatcher
import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.pdfdoc.data.db.BookmarkDao
import app.pdfdoc.data.db.BookmarkEntity
import app.pdfdoc.data.ocr.OcrWorker
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.receiveAsFlow
import app.pdfdoc.data.files.DocumentEvents
import app.pdfdoc.data.files.DocumentStore
import app.pdfdoc.data.files.FileStore
import app.pdfdoc.data.files.PasswordRequiredException
import app.pdfdoc.data.pdf.PdfForms
import app.pdfdoc.data.pdf.PdfRenderSession
import app.pdfdoc.data.pdf.PdfRenderSessionFactory
import app.pdfdoc.data.pdf.RenderCopyBuilder
import app.pdfdoc.data.pdf.TextExtractor
import app.pdfdoc.domain.edit.Box
import app.pdfdoc.domain.repo.DocumentRepository
import app.pdfdoc.domain.text.PageText
import app.pdfdoc.ui.navigation.ViewerRoute
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface ViewerUiState {
    data object Loading : ViewerUiState
    data object Error : ViewerUiState
    data class NeedsPassword(val wrong: Boolean) : ViewerUiState
    /** The file was moved, deleted or access to it was revoked. */
    data object Unavailable : ViewerUiState
    data class Ready(
        val name: String,
        val pageSizes: List<Size>,
        val initialPage: Int,
        /** Bumped when the document is reloaded so page images re-render. */
        val generation: Int,
        val hasForm: Boolean,
        val documentId: Long,
    ) : ViewerUiState
}

data class OcrRun(val page: Int, val total: Int)

data class OutlineItem(val title: String, val page: Int, val level: Int)

data class ViewerMessage(@androidx.annotation.StringRes val text: Int, val args: List<Int> = emptyList())

data class SearchHit(val page: Int, val rects: List<Box>)

/** Text selected on one page with a long press; [range] indexes the page's glyphs. */
data class TextSelection(val page: Int, val anchor: Int, val range: IntRange, val rects: List<Box>, val text: String)

data class SearchState(
    val active: Boolean = false,
    val query: String = "",
    val running: Boolean = false,
    val searched: Boolean = false,
    val hits: List<SearchHit> = emptyList(),
    val current: Int = 0,
) {
    val currentHit: SearchHit? get() = hits.getOrNull(current)
}

@OptIn(FlowPreview::class)
@HiltViewModel
class ViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val documents: DocumentRepository,
    private val renderers: PdfRenderSessionFactory,
    private val files: FileStore,
    private val docs: DocumentStore,
    private val renderCopies: RenderCopyBuilder,
    private val forms: PdfForms,
    private val bookmarkDao: BookmarkDao,
    private val settings: app.pdfdoc.data.settings.SettingsRepository,
    @ApplicationContext private val context: Context,
    private val events: DocumentEvents,
    private val comments: app.pdfdoc.data.pdf.PdfComments,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    val uri: Uri = Uri.parse(savedStateHandle.toRoute<ViewerRoute>().uri)

    private val _state = MutableStateFlow<ViewerUiState>(ViewerUiState.Loading)
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    val viewMode: StateFlow<app.pdfdoc.domain.model.ViewMode> =
        settings.viewMode.stateIn(viewModelScope, SharingStarted.Eagerly, app.pdfdoc.domain.model.ViewMode.CONTINUOUS)

    fun setViewMode(mode: app.pdfdoc.domain.model.ViewMode) {
        viewModelScope.launch { settings.setViewMode(mode) }
    }

    private val _ocr = MutableStateFlow<OcrRun?>(null)
    val ocr: StateFlow<OcrRun?> = _ocr.asStateFlow()
    private var ocrJob: Job? = null

    private val _messages = kotlinx.coroutines.channels.Channel<ViewerMessage>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var session: PdfRenderSession? = null
    private var generation = 0
    private val currentPage = MutableStateFlow<Int?>(null)

    /** Private files backing the current session: the working copy and, if needed, its flattened copy. */
    private var tempFiles: List<File> = emptyList()
    private var renderFile: File? = null
    private var workFile: File? = null

    private val textMutex = Mutex()
    private var textDoc: PDDocument? = null
    private var pageTexts: Array<PageText?> = emptyArray()
    private var searchJob: Job? = null

    private val cache = object : LruCache<String, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    val readAloud = ReadAloud(
        context = context,
        scope = viewModelScope,
        pageCount = { (state.value as? ViewerUiState.Ready)?.pageSizes?.size ?: 0 },
        pageText = ::pageText,
    )

    private val _selection = MutableStateFlow<TextSelection?>(null)
    val selection: StateFlow<TextSelection?> = _selection.asStateFlow()

    private val links = HashMap<Int, List<app.pdfdoc.data.pdf.PageLink>>()

    init {
        load()
        viewModelScope.launch {
            currentPage.filterNotNull().debounce(500).collect { documents.updateLastPage(uri, it) }
        }
        viewModelScope.launch {
            events.changed.filter { it == uri.toString() }.collect { load() }
        }
        observeOcr()
    }

    private fun load() {
        searchJob?.cancel()
        viewModelScope.launch {
            val keepPage = currentPage.value
            runCatching {
                val work = withContext(io) { docs.openWorkingCopy(uri) }
                val doc = documents.recordOpened(uri, work)
                val (newSession, hasForm) = withContext(io) {
                    val flat = files.newWorkFile()
                    val render = if (renderCopies.build(work, flat, excludeOwn = false)) flat else work.also { flat.delete() }
                    val opened = renderers.open(render)
                    textMutex.withLock {
                        releaseFiles()
                        tempFiles = listOf(work, flat)
                        renderFile = render
                        workFile = work
                    }
                    opened to forms.hasFields(work)
                }
                session?.close()
                session = newSession
                cache.evictAll()
                links.clear()
                _selection.value = null
                generation++
                ViewerUiState.Ready(
                    name = doc.name,
                    pageSizes = newSession.pageSizes,
                    initialPage = (keepPage ?: doc.lastPage).coerceIn(0, newSession.pageCount - 1),
                    generation = generation,
                    hasForm = hasForm,
                    documentId = doc.id,
                )
            }.onSuccess {
                _state.value = it
                documentId.value = it.documentId
                if (_search.value.searched) runSearch(_search.value.query)
            }.onFailure { e ->
                if (e !is PasswordRequiredException) android.util.Log.w("PDFdoc", "Cannot open $uri", e)
                _state.value = when (e) {
                    is PasswordRequiredException -> ViewerUiState.NeedsPassword(wrong = docs.password(uri) != null)
                    is SecurityException, is java.io.FileNotFoundException -> ViewerUiState.Unavailable
                    else -> ViewerUiState.Error
                }
                if (e is PasswordRequiredException) docs.forgetPassword(uri)
            }
        }
    }

    suspend fun pageBitmap(index: Int, widthPx: Int): Bitmap? {
        val key = "$generation:$index:$widthPx"
        cache.get(key)?.let { return it }
        val current = session ?: return null
        return withContext(io) { current.render(index, widthPx) }?.also { cache.put(key, it) }
    }

    fun onPageVisible(page: Int) {
        currentPage.value = page
    }

    /** Starts background OCR of pages without a text layer; progress is observed in [ocr]. */
    fun runOcr() {
        val name = (state.value as? ViewerUiState.Ready)?.name.orEmpty()
        watchingOcr = true
        OcrWorker.start(context, uri, name)
    }

    fun cancelOcr() {
        WorkManager.getInstance(context).cancelUniqueWork(OcrWorker.uniqueName(uri))
        _ocr.value = null
    }

    /** Reports the result only for runs this screen started or saw running, not old finished ones. */
    private var watchingOcr = false

    private fun observeOcr() = viewModelScope.launch {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(OcrWorker.uniqueName(uri)).collect { infos ->
            val info = infos.lastOrNull() ?: return@collect
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> {
                    watchingOcr = true
                    _ocr.value = OcrRun(info.progress.getInt(OcrWorker.KEY_PAGE, 0), info.progress.getInt(OcrWorker.KEY_TOTAL, 0))
                }
                WorkInfo.State.SUCCEEDED -> {
                    _ocr.value = null
                    if (watchingOcr) {
                        watchingOcr = false
                        val pages = info.outputData.getInt(OcrWorker.KEY_RECOGNIZED, 0)
                        _messages.send(if (pages > 0) ViewerMessage(R.string.ocr_done, listOf(pages)) else ViewerMessage(R.string.ocr_nothing))
                    }
                }
                WorkInfo.State.FAILED -> {
                    _ocr.value = null
                    if (watchingOcr) {
                        watchingOcr = false
                        val locked = info.outputData.getString(OcrWorker.KEY_ERROR) == OcrWorker.ERROR_LOCKED
                        _messages.send(ViewerMessage(if (locked) R.string.ocr_locked else R.string.error_save))
                    }
                }
                WorkInfo.State.CANCELLED -> {
                    _ocr.value = null
                    watchingOcr = false
                }
            }
        }
    }

    // region Outline and bookmarks

    private val documentId = MutableStateFlow<Long?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val bookmarks: StateFlow<List<BookmarkEntity>> = documentId.filterNotNull()
        .flatMapLatest { bookmarkDao.observe(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addBookmark(page: Int, title: String) {
        val id = documentId.value ?: return
        viewModelScope.launch {
            bookmarkDao.insert(BookmarkEntity(documentId = id, page = page, title = title, createdAt = System.currentTimeMillis()))
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { bookmarkDao.delete(id) }
    }

    /** The document's own table of contents (PDF outline), flattened with nesting levels. */
    suspend fun outline(): List<OutlineItem> = withContext(io) {
        textMutex.withLock {
            val file = renderFile ?: return@withContext emptyList()
            val doc = textDoc ?: PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).also {
                textDoc = it
                pageTexts = arrayOfNulls(it.numberOfPages)
            }
            val pages = doc.pages.toList()
            val items = mutableListOf<OutlineItem>()
            fun walk(node: PDOutlineNode, level: Int) {
                node.children().forEach { item ->
                    val page = runCatching { item.findDestinationPage(doc) }.getOrNull()?.let(pages::indexOf) ?: -1
                    items += OutlineItem(item.title.orEmpty().trim(), page, level)
                    if (level < 6) walk(item, level + 1)
                }
            }
            doc.documentCatalog.documentOutline?.let { walk(it, 0) }
            items
        }
    }

    // endregion

    fun removeFromRecent(onDone: () -> Unit) {
        viewModelScope.launch {
            documents.removeByUri(uri)
            onDone()
        }
    }

    fun submitPassword(password: String) {
        docs.rememberPassword(uri, password)
        _state.value = ViewerUiState.Loading
        load()
    }

    fun openSearch() = _search.update { it.copy(active = true) }

    fun closeSearch() {
        searchJob?.cancel()
        _search.value = SearchState()
    }

    fun onQueryChange(query: String) = _search.update { it.copy(query = query) }

    fun submitSearch() = runSearch(_search.value.query)

    fun nextHit() = _search.update { s -> if (s.hits.isEmpty()) s else s.copy(current = (s.current + 1) % s.hits.size) }

    fun previousHit() = _search.update { s ->
        if (s.hits.isEmpty()) s else s.copy(current = (s.current - 1 + s.hits.size) % s.hits.size)
    }

    private fun runSearch(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _search.update { it.copy(hits = emptyList(), searched = false, running = false) }
            return
        }
        val pageCount = (state.value as? ViewerUiState.Ready)?.pageSizes?.size ?: return
        _search.update { it.copy(running = true, searched = true, hits = emptyList(), current = 0) }
        searchJob = viewModelScope.launch {
            val hits = mutableListOf<SearchHit>()
            for (page in 0 until pageCount) {
                val text = pageText(page) ?: continue
                ensureActive()
                text.find(query).forEach { hits += SearchHit(page, it) }
                // Publish progressively so the first results show up on large documents.
                if (hits.isNotEmpty()) _search.update { it.copy(hits = hits.toList()) }
            }
            _search.update { it.copy(hits = hits.toList(), running = false) }
        }
    }

    private suspend fun pageText(page: Int): PageText? = withContext(io) {
        textMutex.withLock {
            val file = renderFile ?: return@withContext null
            val doc = textDoc ?: PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).also {
                textDoc = it
                pageTexts = arrayOfNulls(it.numberOfPages)
            }
            pageTexts.getOrNull(page) ?: runCatching { TextExtractor.extract(doc, page) }.getOrNull()
                ?.also { pageTexts[page] = it }
        }
    }

    // region Links, selection, comments

    /** The link under [at] on [page], if any. */
    suspend fun linkAt(page: Int, at: app.pdfdoc.domain.edit.Pt): app.pdfdoc.data.pdf.LinkTarget? {
        val pageLinks = links[page] ?: withContext(io) {
            textMutex.withLock {
                val doc = textDocument() ?: return@withContext emptyList()
                runCatching { app.pdfdoc.data.pdf.PdfLinks.read(doc, page) }.getOrDefault(emptyList())
            }
        }.also { links[page] = it }
        return pageLinks.lastOrNull { it.box.contains(at, 4f) }?.target
    }

    /** Starts a selection at the word under [at]. Returns false when the page has no text there. */
    suspend fun beginSelection(page: Int, at: app.pdfdoc.domain.edit.Pt): Boolean {
        val text = pageText(page) ?: return false
        if (text.isEmpty) return false
        val i = text.indexAt(at) ?: return false
        val word = text.wordRange(i)
        _selection.value = TextSelection(page, i, word, text.rects(word), text.text(word))
        return true
    }

    fun extendSelection(page: Int, at: app.pdfdoc.domain.edit.Pt) {
        val current = _selection.value ?: return
        if (page != current.page) return
        val text = pageTexts.getOrNull(page) ?: return
        val i = text.indexAt(at) ?: return
        val range = minOf(current.anchor, i)..maxOf(current.anchor, i)
        _selection.value = current.copy(range = range, rects = text.rects(range), text = text.text(range))
    }

    fun clearSelection() {
        _selection.value = null
    }

    /** Reads aloud starting at the selected sentence, or at the top of [page]. */
    fun startReading(page: Int) {
        val sel = _selection.value
        viewModelScope.launch {
            val startSentence = sel?.let { s ->
                pageText(s.page)?.sentences()?.indexOfFirst { s.range.first <= it.last }?.coerceAtLeast(0)
            } ?: 0
            _selection.value = null
            readAloud.start(sel?.page ?: page, startSentence)
        }
    }

    suspend fun comments(): List<app.pdfdoc.data.pdf.Comment> = withContext(io) {
        val file = textMutex.withLock { workFile } ?: return@withContext emptyList()
        runCatching { comments.read(file) }.getOrDefault(emptyList())
    }

    fun deleteComment(comment: app.pdfdoc.data.pdf.Comment) = changeDocument { source, target -> comments.delete(source, target, comment) }

    fun replyToComment(comment: app.pdfdoc.data.pdf.Comment, text: String) = changeDocument { source, target ->
        comments.reply(source, target, comment, text, settings.authorName.first().ifBlank { context.getString(R.string.comments_default_author) })
    }

    /** Applies a change to the working copy and writes it back to the document. */
    private fun changeDocument(change: suspend (File, File) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching {
                withContext(io) {
                    val source = textMutex.withLock { workFile } ?: error("no file")
                    val out = files.newWorkFile()
                    try {
                        change(source, out)
                        docs.save(out, uri, uri)
                    } finally {
                        out.delete()
                    }
                }
            }.isSuccess
            if (ok) events.notifyChanged(uri.toString()) else _messages.send(ViewerMessage(R.string.error_save))
        }
    }

    /** Call with [textMutex] held. */
    private fun textDocument(): PDDocument? {
        val file = renderFile ?: return null
        return textDoc ?: PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).also {
            textDoc = it
            pageTexts = arrayOfNulls(it.numberOfPages)
        }
    }

    // endregion

    /** Call with [textMutex] held, or when no coroutine can use the text document anymore. */
    private fun releaseFiles() {
        textDoc?.close()
        textDoc = null
        pageTexts = emptyArray()
        tempFiles.forEach(File::delete)
        tempFiles = emptyList()
    }

    override fun onCleared() {
        readAloud.release()
        session?.close()
        cache.evictAll()
        releaseFiles()
    }

    private companion object {
        fun cacheBytes(): Int = (Runtime.getRuntime().maxMemory() / 6).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
