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
import app.pdfdoc.data.ocr.DocumentOcr
import app.pdfdoc.data.ocr.OcrEngine
import kotlinx.coroutines.flow.receiveAsFlow
import app.pdfdoc.data.files.DocumentEvents
import app.pdfdoc.data.files.FileStore
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface ViewerUiState {
    data object Loading : ViewerUiState
    data object Error : ViewerUiState
    data class Ready(
        val name: String,
        val pageSizes: List<Size>,
        val initialPage: Int,
        /** Bumped when the document is reloaded so page images re-render. */
        val generation: Int,
        val hasForm: Boolean,
    ) : ViewerUiState
}

data class OcrRun(val page: Int, val total: Int)

data class ViewerMessage(@androidx.annotation.StringRes val text: Int, val args: List<Int> = emptyList())

data class SearchHit(val page: Int, val rects: List<Box>)

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
    private val renderCopies: RenderCopyBuilder,
    private val forms: PdfForms,
    private val documentOcr: DocumentOcr,
    private val ocrEngine: OcrEngine,
    private val events: DocumentEvents,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    val uri: Uri = Uri.parse(savedStateHandle.toRoute<ViewerRoute>().uri)

    private val _state = MutableStateFlow<ViewerUiState>(ViewerUiState.Loading)
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

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

    init {
        load()
        viewModelScope.launch {
            currentPage.filterNotNull().debounce(500).collect { documents.updateLastPage(uri, it) }
        }
        viewModelScope.launch {
            events.changed.filter { it == uri.toString() }.collect { load() }
        }
    }

    private fun load() {
        searchJob?.cancel()
        viewModelScope.launch {
            val keepPage = currentPage.value
            runCatching {
                val doc = documents.recordOpened(uri)
                val (newSession, hasForm) = withContext(io) {
                    val work = files.copyToWork(uri)
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
                generation++
                ViewerUiState.Ready(
                    name = doc.name,
                    pageSizes = newSession.pageSizes,
                    initialPage = (keepPage ?: doc.lastPage).coerceIn(0, newSession.pageCount - 1),
                    generation = generation,
                    hasForm = hasForm,
                )
            }.onSuccess {
                _state.value = it
                if (_search.value.searched) runSearch(_search.value.query)
            }.onFailure { _state.value = ViewerUiState.Error }
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

    /** Adds an OCR text layer to scanned pages and writes the document back. */
    fun runOcr() {
        val source = workFile ?: return
        if (ocrJob?.isActive == true) return
        ocrJob = viewModelScope.launch {
            _ocr.value = OcrRun(0, 0)
            val result = runCatching {
                withContext(io) {
                    val out = files.newWorkFile()
                    try {
                        val pages = documentOcr.makeSearchable(source, out) { page, total -> _ocr.value = OcrRun(page, total) }
                        if (pages > 0) files.writeTo(uri, out)
                        pages
                    } finally {
                        out.delete()
                    }
                }
            }
            _ocr.value = null
            result.onSuccess { pages ->
                if (pages > 0) {
                    events.notifyChanged(uri.toString())
                    _messages.send(ViewerMessage(R.string.ocr_done, listOf(pages)))
                } else {
                    _messages.send(ViewerMessage(R.string.ocr_nothing))
                }
            }.onFailure {
                if (it !is kotlinx.coroutines.CancellationException) _messages.send(ViewerMessage(R.string.error_save))
            }
        }
    }

    fun cancelOcr() {
        ocrEngine.cancel()
        ocrJob?.cancel()
        _ocr.value = null
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

    /** Call with [textMutex] held, or when no coroutine can use the text document anymore. */
    private fun releaseFiles() {
        textDoc?.close()
        textDoc = null
        pageTexts = emptyArray()
        tempFiles.forEach(File::delete)
        tempFiles = emptyList()
    }

    override fun onCleared() {
        session?.close()
        cache.evictAll()
        releaseFiles()
    }

    private companion object {
        fun cacheBytes(): Int = (Runtime.getRuntime().maxMemory() / 6).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
