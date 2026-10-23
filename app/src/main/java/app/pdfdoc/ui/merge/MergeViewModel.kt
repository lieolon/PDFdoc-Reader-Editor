package app.pdfdoc.ui.merge

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pdfdoc.core.IoDispatcher
import app.pdfdoc.data.files.FileStore
import app.pdfdoc.domain.pdf.PageOperations
import app.pdfdoc.domain.repo.DocumentRepository
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

data class MergeItem(val uri: Uri, val name: String)

data class MergeUiState(
    val items: List<MergeItem> = emptyList(),
    val busy: Boolean = false,
    val failed: Boolean = false,
)

@HiltViewModel
class MergeViewModel @Inject constructor(
    private val files: FileStore,
    private val ops: PageOperations,
    private val documents: DocumentRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val _state = MutableStateFlow(MergeUiState())
    val state: StateFlow<MergeUiState> = _state.asStateFlow()

    private val _merged = Channel<Uri>(Channel.BUFFERED)
    val merged = _merged.receiveAsFlow()

    fun add(uris: List<Uri>) {
        viewModelScope.launch {
            val added = withContext(io) { uris.map { MergeItem(it, files.query(it).name) } }
            _state.update { it.copy(items = it.items + added, failed = false) }
        }
    }

    fun remove(index: Int) = _state.update { it.copy(items = it.items.toMutableList().apply { removeAt(index) }) }

    fun move(index: Int, delta: Int) = _state.update {
        val target = index + delta
        if (target !in it.items.indices) return@update it
        it.copy(items = it.items.toMutableList().apply { add(target, removeAt(index)) })
    }

    fun mergeInto(target: Uri) {
        val items = state.value.items
        if (items.size < 2) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, failed = false) }
            val inputs = mutableListOf<File>()
            val result = runCatching {
                withContext(io) {
                    items.forEach { inputs += files.copyToWork(it.uri) }
                    val out = files.newWorkFile()
                    try {
                        ops.merge(inputs, out)
                        files.writeTo(target, out)
                    } finally {
                        out.delete()
                    }
                }
                documents.recordOpened(target)
            }
            withContext(io) { inputs.forEach(File::delete) }
            _state.update { it.copy(busy = false, failed = result.isFailure) }
            if (result.isSuccess) _merged.send(target)
        }
    }
}
