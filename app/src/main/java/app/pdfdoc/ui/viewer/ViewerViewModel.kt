package app.pdfdoc.ui.viewer

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.pdfdoc.core.IoDispatcher
import app.pdfdoc.data.files.DocumentEvents
import app.pdfdoc.data.pdf.PdfRenderSession
import app.pdfdoc.data.pdf.PdfRenderSessionFactory
import app.pdfdoc.domain.repo.DocumentRepository
import app.pdfdoc.ui.navigation.ViewerRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
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
    ) : ViewerUiState
}

@OptIn(FlowPreview::class)
@HiltViewModel
class ViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val documents: DocumentRepository,
    private val renderers: PdfRenderSessionFactory,
    events: DocumentEvents,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    val uri: Uri = Uri.parse(savedStateHandle.toRoute<ViewerRoute>().uri)

    private val _state = MutableStateFlow<ViewerUiState>(ViewerUiState.Loading)
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()

    private var session: PdfRenderSession? = null
    private var generation = 0
    private val currentPage = MutableStateFlow<Int?>(null)

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
        viewModelScope.launch {
            val keepPage = currentPage.value
            runCatching {
                val doc = documents.recordOpened(uri)
                val newSession = withContext(io) { renderers.open(uri) }
                session?.close()
                session = newSession
                cache.evictAll()
                generation++
                ViewerUiState.Ready(
                    name = doc.name,
                    pageSizes = newSession.pageSizes,
                    initialPage = (keepPage ?: doc.lastPage).coerceIn(0, newSession.pageCount - 1),
                    generation = generation,
                )
            }.onSuccess { _state.value = it }
                .onFailure { _state.value = ViewerUiState.Error }
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

    override fun onCleared() {
        session?.close()
        cache.evictAll()
    }

    private companion object {
        fun cacheBytes(): Int = (Runtime.getRuntime().maxMemory() / 6).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
