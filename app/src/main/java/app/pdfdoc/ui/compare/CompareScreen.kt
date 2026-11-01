package app.pdfdoc.ui.compare

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.pdfdoc.R
import app.pdfdoc.core.IoDispatcher
import app.pdfdoc.data.compare.CompareResult
import app.pdfdoc.data.compare.PdfCompare
import app.pdfdoc.data.files.DocumentStore
import app.pdfdoc.data.files.FileStore
import app.pdfdoc.domain.repo.DocumentRepository
import app.pdfdoc.ui.common.BusyOverlay
import app.pdfdoc.ui.common.MessageView
import app.pdfdoc.ui.common.rememberCreateDocument
import app.pdfdoc.ui.navigation.CompareRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CompareUiState(
    val progress: Int = 0,
    val result: CompareResult? = null,
    val failed: Boolean = false,
    val oldName: String = "",
    val newName: String = "",
    val saving: Boolean = false,
)

@HiltViewModel
class CompareViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val files: FileStore,
    private val docs: DocumentStore,
    private val compare: PdfCompare,
    private val documents: DocumentRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<CompareRoute>()
    private val _state = MutableStateFlow(CompareUiState())
    val state = _state.asStateFlow()
    private val _opened = Channel<Uri>(Channel.BUFFERED)
    val opened = _opened.receiveAsFlow()
    private var newFile: File? = null
    private var oldFile: File? = null

    init {
        viewModelScope.launch {
            runCatching {
                withContext(io) {
                    val oldUri = Uri.parse(route.old)
                    val newUri = Uri.parse(route.new)
                    _state.update { it.copy(oldName = files.query(oldUri).name, newName = files.query(newUri).name) }
                    val a = docs.openWorkingCopy(oldUri).also { oldFile = it }
                    val b = docs.openWorkingCopy(newUri).also { newFile = it }
                    compare.compare(a, b) { p -> _state.update { it.copy(progress = p) } }
                }
            }.onSuccess { r -> _state.update { it.copy(result = r) } }
                .onFailure { _state.update { it.copy(failed = true) } }
        }
    }

    fun saveMarked(target: Uri) {
        val source = newFile ?: return
        val result = state.value.result ?: return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val ok = runCatching {
                withContext(io) {
                    val out = files.newWorkFile()
                    val images = File(files.workDir, "cmp-${UUID.randomUUID()}")
                    try {
                        compare.annotate(source, out, result, context.getString(R.string.compare_removed), images)
                        files.writeTo(target, out)
                        runCatching { documents.recordOpened(target, out) }
                    } finally {
                        out.delete()
                        images.deleteRecursively()
                    }
                }
            }.isSuccess
            _state.update { it.copy(saving = false) }
            if (ok) _opened.send(target)
        }
    }

    override fun onCleared() {
        newFile?.delete()
        oldFile?.delete()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(onBack: () -> Unit, onOpenDocument: (Uri) -> Unit, viewModel: CompareViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val save = rememberCreateDocument("application/pdf") { uri -> uri?.let(viewModel::saveMarked) }
    LaunchedEffect(Unit) { viewModel.opened.collect(onOpenDocument) }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tool_compare)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val result = state.result
            when {
                state.failed -> MessageView(stringResource(R.string.error_open))
                result == null -> Column(Modifier.fillMaxWidth().padding(32.dp)) {
                    Text(stringResource(R.string.compare_running))
                    LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.compare_old, state.oldName), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.compare_new, state.newName), style = MaterialTheme.typography.bodySmall)
                            Text(
                                if (result.changes.isEmpty()) stringResource(R.string.compare_same)
                                else stringResource(R.string.compare_count, result.changes.size),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            if (result.changes.isNotEmpty()) {
                                Button(onClick = { save.launch(state.newName.removeSuffix(".pdf") + " (compare).pdf") }, modifier = Modifier.padding(top = 8.dp)) {
                                    Text(stringResource(R.string.compare_save_marked))
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                    items(result.changes) { c ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(
                                stringResource(R.string.compare_pages, c.oldPage + 1, c.newPage + 1),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (c.removed.isNotEmpty()) {
                                Text("− " + c.removed, modifier = Modifier.padding(top = 4.dp).background(Color(0x33E53935), RoundedCornerShape(4.dp)).padding(4.dp))
                            }
                            if (c.added.isNotEmpty()) {
                                Text("+ " + c.added, modifier = Modifier.padding(top = 4.dp).background(Color(0x3343A047), RoundedCornerShape(4.dp)).padding(4.dp))
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
            if (state.saving) BusyOverlay(stringResource(R.string.working))
        }
    }
}
