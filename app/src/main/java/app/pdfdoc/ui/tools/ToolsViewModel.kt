package app.pdfdoc.ui.tools

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.StringRes
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.pdfdoc.R
import app.pdfdoc.core.IoDispatcher
import app.pdfdoc.data.files.DocumentEvents
import app.pdfdoc.data.files.DocumentStore
import app.pdfdoc.data.files.FileStore
import app.pdfdoc.data.files.Images
import app.pdfdoc.data.pdf.PdfPermissions
import app.pdfdoc.data.pdf.PdfRenderSessionFactory
import app.pdfdoc.data.pdf.PdfSecurity
import app.pdfdoc.data.pdf.PdfTools
import app.pdfdoc.data.pdf.RenderCopyBuilder
import app.pdfdoc.data.settings.SettingsRepository
import app.pdfdoc.domain.pdf.PageOperations
import app.pdfdoc.domain.repo.DocumentRepository
import app.pdfdoc.domain.tools.CompressLevel
import app.pdfdoc.domain.tools.TextStamp
import app.pdfdoc.domain.tools.Watermark
import app.pdfdoc.ui.common.formatSize
import app.pdfdoc.ui.navigation.ToolsRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A configured transformation that produces a new version of the document. */
sealed interface DocumentJob {
    data class Stamps(val stamps: List<TextStamp>, val firstNumber: Int) : DocumentJob
    data class AddWatermark(val watermark: Watermark) : DocumentJob
    data class Compress(val level: CompressLevel) : DocumentJob
    data class Protect(val openPassword: String, val ownerPassword: String, val permissions: PdfPermissions) : DocumentJob
    data object RemovePassword : DocumentJob
    data class CropMargins(val top: Float, val right: Float, val bottom: Float, val left: Float) : DocumentJob
    data object AutoCrop : DocumentJob
    data class Resize(val paper: app.pdfdoc.data.pdf.PaperSize) : DocumentJob
    data object Flatten : DocumentJob
    data class Properties(val properties: app.pdfdoc.data.pdf.DocProperties) : DocumentJob
    data class Sanitize(val options: app.pdfdoc.data.pdf.SanitizeOptions) : DocumentJob
    data class Outline(val entries: List<app.pdfdoc.data.pdf.OutlineEntry>) : DocumentJob
    data object PdfA : DocumentJob
    /** Removes every match of [query] and of the selected [patterns] for good. */
    data class RedactText(val query: String, val patterns: Set<RedactPattern>) : DocumentJob
    data class Sign(
        val certificate: String,
        val password: CharArray,
        val reason: String,
        val location: String,
        val visiblePage: Int,
    ) : DocumentJob
}

enum class RedactPattern(val regex: Regex) {
    EMAIL(Regex("""[\w.+-]+@[\w-]+\.[\w.-]+""")),
    PHONE(Regex("""(?<![\w])\+?\d[\d ()\-]{8,}\d""")),
    CARD(Regex("""(?<!\d)(?:\d[ -]?){12,18}\d(?!\d)""")),
    IBAN(Regex("""\b[A-Z]{2}\d{2}(?: ?[A-Z0-9]){11,30}\b""")),
    PASSPORT(Regex("""(?<!\d)\d{2} ?\d{2} ?\d{6}(?!\d)""")),
}

enum class ImageFormat { JPG, PNG }

data class ToolsUiState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val name: String = "",
    val pageCount: Int = 0,
    val protected: Boolean = false,
    val busy: Boolean = false,
    @StringRes val progressText: Int? = null,
    val progressArgs: List<Int> = emptyList(),
)

sealed interface ToolsEvent {
    data class Message(@StringRes val text: Int, val args: List<String> = emptyList()) : ToolsEvent
    data class Open(val uri: Uri) : ToolsEvent
}

@HiltViewModel
class ToolsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val files: FileStore,
    private val docs: DocumentStore,
    private val tools: PdfTools,
    private val security: PdfSecurity,
    private val ops: PageOperations,
    private val renderCopies: RenderCopyBuilder,
    private val renderers: PdfRenderSessionFactory,
    private val documents: DocumentRepository,
    private val docEvents: DocumentEvents,
    private val settings: SettingsRepository,
    private val docx: app.pdfdoc.data.convert.DocxWriter,
    private val docTools: app.pdfdoc.data.pdf.PdfDocumentTools,
    private val textExports: app.pdfdoc.data.convert.TextExports,
    private val redactor: app.pdfdoc.data.pdf.Redactor,
    val certificates: app.pdfdoc.data.sign.CertificateStore,
    private val signer: app.pdfdoc.data.sign.PdfSigner,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    val uri: Uri = Uri.parse(savedStateHandle.toRoute<ToolsRoute>().uri)

    private val _state = MutableStateFlow(ToolsUiState())
    val state: StateFlow<ToolsUiState> = _state.asStateFlow()

    private val _events = Channel<ToolsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var working: File? = null

    /** A job waiting for the user to pick where to save a copy. */
    var pending: DocumentJob? = null
        private set

    init {
        viewModelScope.launch {
            runCatching {
                withContext(io) {
                    val work = docs.openWorkingCopy(uri)
                    working = work
                    Triple(files.query(uri).name, ops.pageCount(work), docs.password(uri) != null)
                }
            }.onSuccess { (name, pages, protected) ->
                _state.update { it.copy(loading = false, name = name, pageCount = pages, protected = protected) }
            }.onFailure {
                _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    val defaultCompress: StateFlow<CompressLevel> =
        settings.compressLevel.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, CompressLevel.MEDIUM)

    val baseName: String get() = state.value.name.removeSuffix(".pdf").removeSuffix(".PDF")

    fun prepareCopy(job: DocumentJob) {
        pending = job
    }

    /** Applies [job] and overwrites the original document. */
    fun applyInPlace(job: DocumentJob) = run(job, target = uri)

    /** Applies the pending job and writes the result to [target]. */
    fun applyPendingTo(target: Uri) {
        val job = pending ?: return
        pending = null
        run(job, target)
    }

    private fun run(job: DocumentJob, target: Uri) {
        val source = working ?: return
        launchBusy {
            val out = files.newWorkFile()
            try {
                var message: ToolsEvent.Message = ToolsEvent.Message(R.string.saved)
                when (job) {
                    is DocumentJob.Stamps -> tools.addTextStamps(source, out, job.stamps, state.value.name, job.firstNumber)
                    is DocumentJob.AddWatermark -> tools.addWatermark(source, out, job.watermark)
                    is DocumentJob.Compress -> {
                        val r = tools.compress(source, out, job.level)
                        message = if (r.imagesChanged == 0 || r.after >= r.before) {
                            ToolsEvent.Message(R.string.tools_compress_nothing)
                        } else {
                            val percent = (100 - r.after * 100.0 / r.before).roundToInt()
                            ToolsEvent.Message(R.string.tools_compress_done, listOf(formatSize(context, r.before), formatSize(context, r.after), percent.toString()))
                        }
                    }
                    is DocumentJob.Protect ->
                        security.protect(source, out, job.openPassword, job.ownerPassword, job.permissions)
                    DocumentJob.RemovePassword -> source.copyTo(out, overwrite = true)
                    is DocumentJob.CropMargins -> docTools.cropMargins(source, out, job.top, job.right, job.bottom, job.left, null)
                    DocumentJob.AutoCrop -> {
                        val n = docTools.autoCrop(source, out)
                        message = ToolsEvent.Message(R.string.tool_crop_auto_done, listOf(n.toString()))
                    }
                    is DocumentJob.Resize -> docTools.resizePages(source, out, job.paper)
                    DocumentJob.Flatten -> if (!docTools.flatten(source, out)) message = ToolsEvent.Message(R.string.tool_flatten_nothing)
                    is DocumentJob.Properties -> docTools.setProperties(source, out, job.properties)
                    is DocumentJob.Sanitize -> docTools.sanitize(source, out, job.options)
                    is DocumentJob.Outline -> docTools.setOutline(source, out, job.entries)
                    DocumentJob.PdfA -> {
                        val missing = docTools.toPdfA(source, out, baseName)
                        message = if (missing > 0) ToolsEvent.Message(R.string.tool_pdfa_fonts, listOf(missing.toString()))
                        else ToolsEvent.Message(R.string.tool_pdfa_done)
                    }
                    is DocumentJob.RedactText -> {
                        val areas = findAreas(source, job)
                        if (areas.isEmpty()) {
                            _events.send(ToolsEvent.Message(R.string.tool_redact_none))
                            return@launchBusy
                        }
                        redactor.redact(source, out, areas)
                        message = ToolsEvent.Message(R.string.tool_redact_done, listOf(areas.values.sumOf { it.size }.toString()))
                    }
                    is DocumentJob.Sign -> {
                        val (key, chain) = try {
                            certificates.unlock(job.certificate, job.password)
                        } catch (e: app.pdfdoc.data.sign.WrongPasswordException) {
                            _events.send(ToolsEvent.Message(R.string.sign_wrong_password))
                            return@launchBusy
                        }
                        signer.sign(source, out, key, chain, job.reason, job.location, job.visiblePage, context.getString(R.string.sign_block_label))
                        message = ToolsEvent.Message(R.string.sign_done)
                    }
                }
                if (job is DocumentJob.Protect || job is DocumentJob.RemovePassword || job is DocumentJob.Sign) {
                    // Security output is final: never re-encrypt it with the old password.
                    files.writeTo(target, out)
                    when (job) {
                        is DocumentJob.Protect -> if (job.openPassword.isNotEmpty()) docs.rememberPassword(target, job.openPassword) else docs.forgetPassword(target)
                        is DocumentJob.Sign -> Unit
                        else -> docs.forgetPassword(target)
                    }
                } else {
                    docs.save(out, target, uri)
                }
                if (target == uri) {
                    // The working copy stays decrypted so further tools can read it.
                    if (job !is DocumentJob.Protect) out.copyTo(source, overwrite = true)
                    _state.update { it.copy(protected = docs.password(uri) != null) }
                    docEvents.notifyChanged(uri.toString())
                    _events.send(message)
                } else {
                    runCatching { documents.recordOpened(target, if (job is DocumentJob.Protect) source else out) }
                    _events.send(message)
                    _events.send(ToolsEvent.Open(target))
                }
            } finally {
                out.delete()
            }
        }
    }

    private fun findAreas(source: File, job: DocumentJob.RedactText): Map<Int, List<app.pdfdoc.domain.edit.Box>> =
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(source, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            (0 until doc.numberOfPages).associateWith { page ->
                val text = runCatching { app.pdfdoc.data.pdf.TextExtractor.extract(doc, page) }.getOrNull() ?: return@associateWith emptyList()
                val hits = mutableListOf<app.pdfdoc.domain.edit.Box>()
                if (job.query.isNotBlank()) hits += text.find(job.query).flatten()
                job.patterns.forEach { hits += text.findRegex(it.regex).flatten() }
                hits.map { it.inset(-1f) }
            }.filterValues { it.isNotEmpty() }
        }

    // region Document info, attachments, outline, signatures

    suspend fun properties(): app.pdfdoc.data.pdf.DocProperties? = withContext(io) {
        working?.let { runCatching { docTools.properties(it) }.getOrNull() }
    }

    suspend fun outline(): List<app.pdfdoc.data.pdf.OutlineEntry> = withContext(io) {
        working?.let { runCatching { docTools.outline(it) }.getOrNull() }.orEmpty()
    }

    private val _attachments = MutableStateFlow<List<app.pdfdoc.data.pdf.Attachment>?>(null)
    val attachments: StateFlow<List<app.pdfdoc.data.pdf.Attachment>?> = _attachments.asStateFlow()

    fun loadAttachments() {
        viewModelScope.launch {
            _attachments.value = withContext(io) { working?.let { runCatching { docTools.attachments(it) }.getOrNull() }.orEmpty() }
        }
    }

    fun addAttachment(file: Uri) = changeInPlace { source, out ->
        val info = files.query(file)
        val mime = context.contentResolver.getType(file)
        context.contentResolver.openInputStream(file)?.use { docTools.addAttachment(source, out, info.name, mime, it) } ?: error("read")
    }

    fun removeAttachment(name: String) = changeInPlace { source, out -> docTools.removeAttachment(source, out, name) }

    fun saveAttachment(name: String, target: Uri) {
        val source = working ?: return
        launchBusy {
            val bytes = docTools.readAttachment(source, name) ?: error("missing")
            context.contentResolver.openOutputStream(target, "wt")?.use { it.write(bytes) } ?: error("write")
            _events.send(ToolsEvent.Message(R.string.saved))
        }
    }

    /** Applies a structural change and saves it into the document right away. */
    private fun changeInPlace(change: (File, File) -> Unit) {
        val source = working ?: return
        launchBusy {
            val out = files.newWorkFile()
            try {
                change(source, out)
                docs.save(out, uri, uri)
                out.copyTo(source, overwrite = true)
                docEvents.notifyChanged(uri.toString())
            } finally {
                out.delete()
            }
            _attachments.value = docTools.attachments(source)
            _events.send(ToolsEvent.Message(R.string.saved))
        }
    }

    suspend fun verifySignatures(): List<app.pdfdoc.data.sign.SignatureCheck>? = withContext(io) {
        working?.let { runCatching { signer.verify(it) }.getOrNull() }
    }

    fun exportAs(format: app.pdfdoc.data.convert.TextFormat, target: Uri) {
        val source = working ?: return
        launchBusy {
            context.contentResolver.openOutputStream(target, "wt")?.use { textExports.export(source, format, baseName, it) } ?: error("write")
            _events.send(ToolsEvent.Message(R.string.saved))
        }
    }

    // endregion

    /** Splits into files written into the folder [tree]. */
    fun split(ranges: List<IntRange>, tree: Uri) {
        val source = working ?: return
        launchBusy {
            val folder = DocumentFile.fromTreeUri(context, tree) ?: error("folder")
            ranges.forEachIndexed { i, range ->
                progress(R.string.tools_progress_file, i + 1, ranges.size)
                val out = files.newWorkFile()
                try {
                    ops.extractPages(source, out, range.toList())
                    val label = if (range.first == range.last) "${range.first + 1}" else "${range.first + 1}-${range.last + 1}"
                    val file = folder.createFile("application/pdf", "$baseName ($label)") ?: error("create")
                    docs.save(out, file.uri, uri)
                } finally {
                    out.delete()
                }
            }
            _events.send(ToolsEvent.Message(R.string.tool_split_done, listOf(ranges.size.toString())))
        }
    }

    fun exportText(target: Uri) {
        val source = working ?: return
        launchBusy {
            val text = tools.extractText(source) { context.getString(R.string.tool_text_page, it) }
            context.contentResolver.openOutputStream(target, "wt")?.use { it.write(text.toByteArray()) } ?: error("write")
            _events.send(ToolsEvent.Message(R.string.saved))
        }
    }

    fun exportDocx(target: Uri) {
        val source = working ?: return
        launchBusy {
            context.contentResolver.openOutputStream(target, "wt")?.use { docx.convert(source, it) } ?: error("write")
            _events.send(ToolsEvent.Message(R.string.saved))
        }
    }

    /** Renders every page (with annotations and form values) into [tree] as images. */
    fun exportImages(tree: Uri, format: ImageFormat) {
        val source = working ?: return
        launchBusy {
            val dpi = settings.exportDpi.first()
            val quality = settings.jpegQuality.first()
            val folder = DocumentFile.fromTreeUri(context, tree) ?: error("folder")
            val flat = files.newWorkFile()
            val render = if (renderCopies.build(source, flat, excludeOwn = false)) flat else source
            try {
                renderers.open(render).use { session ->
                    session.pageSizes.forEachIndexed { i, size ->
                        progress(R.string.scan_progress_page, i + 1, session.pageCount)
                        val width = (size.width * dpi / 72f).roundToInt().coerceIn(1, MAX_EXPORT_SIDE)
                        val bitmap = session.render(i, width) ?: return@forEachIndexed
                        val (mime, compress) = when (format) {
                            ImageFormat.JPG -> "image/jpeg" to Bitmap.CompressFormat.JPEG
                            ImageFormat.PNG -> "image/png" to Bitmap.CompressFormat.PNG
                        }
                        val file = folder.createFile(mime, "$baseName ${i + 1}") ?: error("create")
                        context.contentResolver.openOutputStream(file.uri)?.use { bitmap.compress(compress, quality, it) }
                        bitmap.recycle()
                    }
                }
            } finally {
                flat.delete()
            }
            _events.send(ToolsEvent.Message(R.string.scan_images_saved))
        }
    }

    /** Copies a picked image for use as a watermark; returns its local path. */
    suspend fun importWatermarkImage(source: Uri): String? = withContext(io) {
        runCatching {
            Images.save(Images.decode(context, source, 1600), File(files.workDir, "wm"), UUID.randomUUID().toString()).absolutePath
        }.getOrNull()
    }

    private fun progress(@StringRes text: Int, page: Int, total: Int) =
        _state.update { it.copy(progressText = text, progressArgs = listOf(page, total)) }

    private fun launchBusy(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = runCatching { withContext(io, block) }
            _state.update { it.copy(busy = false, progressText = null) }
            result.onFailure { _events.send(ToolsEvent.Message(R.string.error_save)) }
        }
    }

    override fun onCleared() {
        working?.delete()
    }

    private companion object {
        const val MAX_EXPORT_SIDE = 5000
    }
}
