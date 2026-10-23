package app.pdfdoc.ui.pages

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.pdfdoc.R
import app.pdfdoc.core.IoDispatcher
import app.pdfdoc.data.files.DocumentEvents
import app.pdfdoc.data.files.FileStore
import app.pdfdoc.data.pdf.PdfRenderSession
import app.pdfdoc.data.pdf.PdfRenderSessionFactory
import app.pdfdoc.domain.pdf.PageOperations
import app.pdfdoc.domain.repo.DocumentRepository
import app.pdfdoc.ui.navigation.PagesRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val THUMB_WIDTH_PX = 360
private const val MAX_UNDO = 20

data class PagesUiState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val busy: Boolean = false,
    val name: String = "",
    val pageCount: Int = 0,
    val selected: Set<Int> = emptySet(),
    val dirty: Boolean = false,
    val canUndo: Boolean = false,
    val generation: Int = 0,
)

sealed interface PagesEvent {
    data class Message(@StringRes val text: Int) : PagesEvent
    data class Open(val uri: Uri) : PagesEvent
    data object Close : PagesEvent
}

@HiltViewModel
class PagesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val files: FileStore,
    private val ops: PageOperations,
    private val renderers: PdfRenderSessionFactory,
    private val documents: DocumentRepository,
    private val docEvents: DocumentEvents,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val uri: Uri = Uri.parse(savedStateHandle.toRoute<PagesRoute>().uri)

    private val _state = MutableStateFlow(PagesUiState())
    val state: StateFlow<PagesUiState> = _state.asStateFlow()

    private val _events = Channel<PagesEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var working: File? = null
    private var session: PdfRenderSession? = null
    private val undoStack = ArrayDeque<File>()
    private val thumbs = LruCache<String, Bitmap>(64)

    init {
        viewModelScope.launch {
            runCatching {
                withContext(io) {
                    val info = files.query(uri)
                    val file = files.copyToWork(uri)
                    working = file
                    reopen(file)
                    info.name
                }
            }.onSuccess { name ->
                _state.update { it.copy(loading = false, name = name, pageCount = session!!.pageCount) }
            }.onFailure {
                _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    suspend fun thumbnail(index: Int, generation: Int): Bitmap? {
        val key = "$generation:$index"
        thumbs.get(key)?.let { return it }
        val current = session ?: return null
        return withContext(io) { current.render(index, THUMB_WIDTH_PX) }?.also { thumbs.put(key, it) }
    }

    fun toggle(index: Int) = _state.update {
        it.copy(selected = if (index in it.selected) it.selected - index else it.selected + index)
    }

    fun selectAll() = _state.update {
        it.copy(selected = if (it.selected.size == it.pageCount) emptySet() else (0 until it.pageCount).toSet())
    }

    fun rotateSelected() {
        val pages = state.value.selected
        if (pages.isEmpty()) return
        mutate(keepSelection = true) { ops.rotatePages(it, it, pages, 90) }
    }

    fun deleteSelected() {
        val s = state.value
        if (s.selected.isEmpty()) return
        if (s.selected.size >= s.pageCount) {
            _events.trySend(PagesEvent.Message(R.string.pages_cant_delete_all))
            return
        }
        mutate { ops.deletePages(it, it, s.selected) }
    }

    fun move(from: Int, to: Int) {
        if (from == to) return
        mutate { ops.movePage(it, it, from, to) }
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        viewModelScope.launch {
            busy {
                working?.delete()
                working = previous
                reopen(previous)
            }
            _state.update {
                it.copy(
                    pageCount = session?.pageCount ?: 0,
                    selected = emptySet(),
                    canUndo = undoStack.isNotEmpty(),
                    dirty = true,
                    generation = it.generation + 1,
                )
            }
        }
    }

    fun extractSelected(target: Uri) {
        val pages = state.value.selected.sorted()
        val source = working ?: return
        if (pages.isEmpty()) return
        viewModelScope.launch {
            val ok = busy {
                val out = files.newWorkFile()
                ops.extractPages(source, out, pages)
                files.writeTo(target, out)
                out.delete()
            }
            if (ok) {
                runCatching { documents.recordOpened(target) }
                _events.send(PagesEvent.Open(target))
            }
        }
    }

    fun save(closeAfter: Boolean = false) {
        val file = working ?: return
        viewModelScope.launch {
            if (busy { files.writeTo(uri, file) }) {
                _state.update { it.copy(dirty = false) }
                docEvents.notifyChanged(uri.toString())
                _events.send(if (closeAfter) PagesEvent.Close else PagesEvent.Message(R.string.saved))
            }
        }
    }

    fun saveAs(target: Uri) {
        val file = working ?: return
        viewModelScope.launch {
            if (busy { files.writeTo(target, file) }) {
                _state.update { it.copy(dirty = false) }
                runCatching { documents.recordOpened(target) }
                _events.send(PagesEvent.Open(target))
            }
        }
    }

    private fun mutate(keepSelection: Boolean = false, op: (File) -> Unit) {
        val file = working ?: return
        viewModelScope.launch {
            val ok = busy {
                val snapshot = files.newWorkFile()
                file.copyTo(snapshot, overwrite = true)
                try {
                    op(file)
                } catch (e: Exception) {
                    snapshot.copyTo(file, overwrite = true)
                    snapshot.delete()
                    throw e
                }
                undoStack.addLast(snapshot)
                while (undoStack.size > MAX_UNDO) undoStack.removeFirst().delete()
                reopen(file)
            }
            if (ok) {
                _state.update {
                    it.copy(
                        pageCount = session?.pageCount ?: 0,
                        selected = if (keepSelection) it.selected else emptySet(),
                        dirty = true,
                        canUndo = true,
                        generation = it.generation + 1,
                    )
                }
            }
        }
    }

    /** Runs [block] on the IO dispatcher with the busy indicator; reports failure as a message. */
    private suspend fun busy(block: suspend () -> Unit): Boolean {
        _state.update { it.copy(busy = true) }
        val result = runCatching { withContext(io) { block() } }
        _state.update { it.copy(busy = false) }
        result.onFailure { _events.send(PagesEvent.Message(R.string.error_save)) }
        return result.isSuccess
    }

    private fun reopen(file: File) {
        session?.close()
        session = renderers.open(file)
        thumbs.evictAll()
    }

    override fun onCleared() {
        session?.close()
        working?.delete()
        undoStack.forEach { it.delete() }
    }
}
