package app.pdfdoc.ui.batch

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.pdfdoc.R
import app.pdfdoc.core.IoDispatcher
import app.pdfdoc.data.convert.DocxWriter
import app.pdfdoc.data.convert.SourceFormat
import app.pdfdoc.data.convert.TextToPdf
import app.pdfdoc.data.files.DocumentStore
import app.pdfdoc.data.files.FileStore
import app.pdfdoc.data.ocr.DocumentOcr
import app.pdfdoc.data.pdf.PdfDocumentTools
import app.pdfdoc.data.pdf.PdfPermissions
import app.pdfdoc.data.pdf.PdfSecurity
import app.pdfdoc.data.pdf.PdfTools
import app.pdfdoc.data.pdf.SanitizeOptions
import app.pdfdoc.domain.repo.DocumentRepository
import app.pdfdoc.domain.tools.CompressLevel
import app.pdfdoc.domain.tools.StampPosition
import app.pdfdoc.domain.tools.TextStamp
import app.pdfdoc.domain.tools.TextWatermark
import app.pdfdoc.ui.common.BusyOverlay
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BatchOperation(val label: Int, val suffix: String, val extension: String = "pdf") {
    COMPRESS(R.string.tool_compress, "compressed"),
    NUMBERS(R.string.tool_numbers, "numbered"),
    WATERMARK(R.string.tool_watermark, "watermark"),
    OCR(R.string.viewer_ocr, "ocr"),
    PROTECT(R.string.tool_protect, "protected"),
    FLATTEN(R.string.tool_flatten, "flat"),
    SANITIZE(R.string.tool_sanitize, "clean"),
    PDFA(R.string.tool_pdfa, "PDF-A"),
    TEXT(R.string.tool_text_export, "", "txt"),
    DOCX(R.string.tool_docx, "", "docx"),
}

data class BatchUiState(
    val files: List<Pair<Uri, String>> = emptyList(),
    val operation: BatchOperation = BatchOperation.COMPRESS,
    val running: Boolean = false,
    val current: Int = 0,
    val done: Int = 0,
    val failed: List<String> = emptyList(),
    val finished: Boolean = false,
)

@HiltViewModel
class BatchViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val files: FileStore,
    private val docs: DocumentStore,
    private val tools: PdfTools,
    private val docTools: PdfDocumentTools,
    private val security: PdfSecurity,
    private val ocr: DocumentOcr,
    private val docx: DocxWriter,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val _state = MutableStateFlow(BatchUiState())
    val state = _state.asStateFlow()
    private var job: Job? = null

    fun addFiles(uris: List<Uri>) {
        viewModelScope.launch {
            val named = withContext(io) { uris.map { it to runCatching { files.query(it).name }.getOrDefault(it.lastPathSegment.orEmpty()) } }
            _state.update { s -> s.copy(files = (s.files + named).distinctBy { it.first }, finished = false) }
        }
    }

    fun remove(uri: Uri) = _state.update { s -> s.copy(files = s.files.filterNot { it.first == uri }) }

    fun select(op: BatchOperation) = _state.update { it.copy(operation = op, finished = false) }

    fun run(folder: Uri, text: String, password: String) {
        val s = state.value
        job = viewModelScope.launch {
            _state.update { it.copy(running = true, current = 0, done = 0, failed = emptyList(), finished = false) }
            withContext(io) {
                val dir = DocumentFile.fromTreeUri(context, folder)
                s.files.forEachIndexed { i, (uri, name) ->
                    _state.update { it.copy(current = i + 1) }
                    val ok = runCatching { process(uri, name, s.operation, dir!!, text, password) }.isSuccess
                    _state.update { if (ok) it.copy(done = it.done + 1) else it.copy(failed = it.failed + name) }
                }
            }
            _state.update { it.copy(running = false, finished = true) }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.update { it.copy(running = false) }
    }

    private suspend fun process(uri: Uri, name: String, op: BatchOperation, dir: DocumentFile, text: String, password: String) {
        val work = docs.openWorkingCopy(uri)
        val out = files.newWorkFile()
        try {
            val base = name.removeSuffix(".pdf").removeSuffix(".PDF")
            when (op) {
                BatchOperation.COMPRESS -> tools.compress(work, out, CompressLevel.MEDIUM)
                BatchOperation.NUMBERS -> tools.addTextStamps(work, out, listOf(TextStamp("{n} / {total}", StampPosition.BOTTOM_CENTER)), name)
                BatchOperation.WATERMARK -> tools.addWatermark(work, out, TextWatermark(text.ifBlank { context.getString(R.string.tool_watermark_default) }))
                BatchOperation.OCR -> if (ocr.makeSearchable(work, out) { _, _ -> } == 0) work.copyTo(out, overwrite = true)
                BatchOperation.PROTECT -> security.protect(work, out, password, password, PdfPermissions())
                BatchOperation.FLATTEN -> docTools.flatten(work, out)
                BatchOperation.SANITIZE -> docTools.sanitize(work, out, SanitizeOptions())
                BatchOperation.PDFA -> docTools.toPdfA(work, out, base)
                BatchOperation.TEXT -> out.writeText(tools.extractText(work) { context.getString(R.string.tool_text_page, it) })
                BatchOperation.DOCX -> out.outputStream().use { docx.convert(work, it) }
            }
            val mime = when (op.extension) {
                "txt" -> "text/plain"
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                else -> "application/pdf"
            }
            val fileName = if (op.suffix.isEmpty()) "$base.${op.extension}" else "$base (${op.suffix}).${op.extension}"
            val created = dir.createFile(mime, fileName) ?: error("create")
            files.writeTo(created.uri, out)
        } finally {
            work.delete()
            out.delete()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BatchScreen(onBack: () -> Unit, viewModel: BatchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.run(it, text, password) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.batch_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(R.string.batch_files, state.files.size), style = MaterialTheme.typography.titleSmall)
            state.files.forEach { (uri, name) ->
                ListItem(
                    headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        IconButton(onClick = { viewModel.remove(uri) }, enabled = !state.running) { Icon(Icons.Default.Close, stringResource(R.string.pages_delete)) }
                    },
                )
            }
            OutlinedButton(onClick = { picking = true }, enabled = !state.running) { Text(stringResource(R.string.batch_add)) }
            Text(stringResource(R.string.batch_operation), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BatchOperation.entries.forEach { op ->
                    FilterChip(selected = state.operation == op, onClick = { viewModel.select(op) }, label = { Text(stringResource(op.label)) })
                }
            }
            when (state.operation) {
                BatchOperation.WATERMARK -> OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.tool_text)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                BatchOperation.PROTECT -> OutlinedTextField(
                    password, { password = it },
                    label = { Text(stringResource(R.string.protect_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                else -> Unit
            }
            Text(stringResource(R.string.batch_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            val ready = state.files.isNotEmpty() && !state.running && (state.operation != BatchOperation.PROTECT || password.isNotEmpty())
            Button(onClick = { folder.launch(null) }, enabled = ready, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text(stringResource(R.string.tool_choose_folder))
            }
            if (state.running) {
                Text(stringResource(R.string.tools_progress_file, state.current, state.files.size), modifier = Modifier.padding(top = 16.dp))
                LinearProgressIndicator(progress = { state.current / state.files.size.coerceAtLeast(1).toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedButton(onClick = viewModel::cancel, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.cancel)) }
            }
            if (state.finished) {
                Text(stringResource(R.string.batch_done, state.done), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                if (state.failed.isNotEmpty()) {
                    Text(stringResource(R.string.batch_failed, state.failed.joinToString(", ")), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (picking) {
        app.pdfdoc.ui.common.PdfPickerSheet(onDismiss = { picking = false }, onPicked = { picking = false; viewModel.addFiles(it) })
    }
}

@HiltViewModel
class TextToPdfViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val files: FileStore,
    private val converter: TextToPdf,
    private val documents: DocumentRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    val busy = MutableStateFlow(false)
    private val _events = Channel<Uri?>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    var source: Uri? = null
        private set
    var sourceName = MutableStateFlow("")

    fun pick(uri: Uri) {
        source = uri
        viewModelScope.launch { sourceName.value = withContext(io) { runCatching { files.query(uri).name }.getOrDefault("") } }
    }

    fun convert(format: SourceFormat, target: Uri) {
        val from = source ?: return
        viewModelScope.launch {
            busy.value = true
            val ok = runCatching {
                withContext(io) {
                    val text = context.contentResolver.openInputStream(from)?.use { app.pdfdoc.data.convert.TextDecoding.decode(it.readBytes()) } ?: error("read")
                    val out = files.newWorkFile()
                    try {
                        converter.convert(text, format, sourceName.value.substringBeforeLast('.'), out)
                        files.writeTo(target, out)
                        runCatching { documents.recordOpened(target, out) }
                    } finally {
                        out.delete()
                    }
                }
            }.isSuccess
            busy.value = false
            _events.send(if (ok) target else null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextToPdfScreen(onBack: () -> Unit, onOpenDocument: (Uri) -> Unit, viewModel: TextToPdfViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val name by viewModel.sourceName.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var format by remember { mutableStateOf(SourceFormat.TEXT) }
    val snackbar = remember { SnackbarHostState() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.pick(uri)
        val lower = (runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull() ?: "").lowercase()
        format = when {
            lower.endsWith(".md") || lower.endsWith(".markdown") -> SourceFormat.MARKDOWN
            lower.endsWith(".html") || lower.endsWith(".htm") -> SourceFormat.HTML
            else -> SourceFormat.TEXT
        }
    }
    val save = app.pdfdoc.ui.common.rememberCreateDocument("application/pdf") { uri -> uri?.let { viewModel.convert(format, it) } }
    LaunchedEffect(Unit) {
        viewModel.events.collect { uri ->
            if (uri != null) onOpenDocument(uri) else snackbar.showSnackbar(context.getString(R.string.error_save))
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.text_to_pdf)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(stringResource(R.string.text_to_pdf_intro), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { pick.launch(arrayOf("text/*", "application/octet-stream")) }, modifier = Modifier.padding(top = 12.dp)) {
                    Text(if (name.isEmpty()) stringResource(R.string.text_to_pdf_pick) else name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                listOf(SourceFormat.TEXT to R.string.format_text, SourceFormat.MARKDOWN to R.string.format_markdown, SourceFormat.HTML to R.string.format_html).forEach { (f, label) ->
                    Row(Modifier.fillMaxWidth().clickable { format = f }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = format == f, onClick = { format = f })
                        Text(stringResource(label))
                    }
                }
                Button(
                    onClick = { save.launch(name.substringBeforeLast('.').ifEmpty { "document" } + ".pdf") },
                    enabled = name.isNotEmpty() && !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                ) { Text(stringResource(R.string.text_to_pdf_create)) }
            }
            if (busy) BusyOverlay(stringResource(R.string.working))
        }
    }
}
