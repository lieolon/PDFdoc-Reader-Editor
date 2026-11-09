package app.pdfdoc.ui.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.toRoute
import app.pdfdoc.ui.navigation.ScanRoute
import androidx.annotation.StringRes
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pdfdoc.R
import app.pdfdoc.core.IoDispatcher
import app.pdfdoc.data.files.FileStore
import app.pdfdoc.data.files.Images
import app.pdfdoc.data.scan.ExportProgress
import app.pdfdoc.data.scan.ScanExporter
import app.pdfdoc.data.scan.ScanImages
import app.pdfdoc.domain.edit.Pt
import app.pdfdoc.domain.repo.DocumentRepository
import app.pdfdoc.domain.scan.Quad
import app.pdfdoc.domain.scan.ScanFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ScanStep { CAMERA, CROP, REVIEW }

/** DOCUMENT: one photo, one page. ID_CARD: front and back on one page. BOOK: a spread split into two pages. */
enum class ScanMode { DOCUMENT, ID_CARD, BOOK }

/** A captured page: the original photo, the chosen outline and the straightened result. */
data class ScanPage(val id: Long, val raw: File, val width: Int, val height: Int, val quad: Quad, val processed: File)

/** The photo whose outline is being adjusted; [replaceIndex] is null for a new page. */
data class CropState(val raw: File, val width: Int, val height: Int, val quad: Quad, val replaceIndex: Int?)

data class ScanUiState(
    val step: ScanStep = ScanStep.CAMERA,
    val pages: List<ScanPage> = emptyList(),
    val crop: CropState? = null,
    val filter: ScanFilter = ScanFilter.ENHANCED,
    val recognize: Boolean = true,
    val mode: ScanMode = ScanMode.DOCUMENT,
    val autoCapture: Boolean = false,
    /** Straightened front side of an ID card, waiting for the back. */
    val idFront: File? = null,
    val busy: Boolean = false,
    @StringRes val progressText: Int? = null,
    val progressArgs: List<Int> = emptyList(),
)

sealed interface ScanEvent {
    data class Message(@StringRes val text: Int) : ScanEvent
    data class Open(val uri: Uri) : ScanEvent
}

@HiltViewModel
class ScanViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val files: FileStore,
    private val exporter: ScanExporter,
    private val documents: DocumentRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    /** Started as "images to PDF": open the gallery first, keep colors and skip OCR by default. */
    val fromImages: Boolean = savedStateHandle.toRoute<ScanRoute>().fromImages

    /** Images shared to the app ("Make PDF" from the share sheet), imported right away. */
    private val sharedImages = savedStateHandle.toRoute<ScanRoute>().images

    private val _state = MutableStateFlow(
        if (fromImages) ScanUiState(filter = ScanFilter.COLOR, recognize = false) else ScanUiState(),
    )
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private val _events = Channel<ScanEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        if (sharedImages.isNotEmpty() && savedStateHandle.get<Boolean>(SHARED_DONE) != true) {
            savedStateHandle[SHARED_DONE] = true
            import(sharedImages.map(Uri::parse))
        }
    }

    private val dir = File(files.workDir, "scan-${UUID.randomUUID()}").apply { mkdirs() }
    private var nextId = 1L
    private val previews = LruCache<String, Bitmap>(32)
    private var exportJob: Job? = null

    val defaultName: String
        get() = context.getString(R.string.scan_default_name, SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(Date()))

    // region Capture and import

    fun onCaptured(bitmap: Bitmap) = busy {
        val raw = ScanImages.saveJpeg(bitmap, File(dir, "raw-${UUID.randomUUID()}.jpg"))
        _state.update {
            it.copy(
                step = ScanStep.CROP,
                crop = CropState(
                    raw, bitmap.width, bitmap.height,
                    ScanImages.detectPage(bitmap) ?: Quad.full(bitmap.width, bitmap.height, 0.04f),
                    null,
                ),
            )
        }
    }

    fun import(uris: List<Uri>) = busy {
        val detected = HashMap<File, Quad?>()
        val imported = uris.mapNotNull { uri ->
            runCatching {
                val bitmap = Images.decode(context, uri, ScanImages.MAX_PAGE_SIDE)
                val raw = ScanImages.saveJpeg(bitmap, File(dir, "raw-${UUID.randomUUID()}.jpg"))
                detected[raw] = ScanImages.detectPage(bitmap)
                Triple(raw, bitmap.width, bitmap.height)
            }.getOrNull()
        }
        if (imported.isEmpty()) {
            _events.send(ScanEvent.Message(R.string.editor_image_error))
        } else if (imported.size == 1) {
            val (raw, w, h) = imported.single()
            _state.update { it.copy(step = ScanStep.CROP, crop = CropState(raw, w, h, detected[raw] ?: Quad.full(w, h), null)) }
        } else {
            // Gallery images are usually already cropped: add them as they are, each can be re-cropped.
            val pages = imported.map { (raw, w, h) -> ScanPage(nextId++, raw, w, h, Quad.full(w, h), raw) }
            _state.update { it.copy(step = ScanStep.REVIEW, pages = it.pages + pages) }
        }
    }

    // endregion

    // region Crop

    fun moveCorner(index: Int, p: Pt) = _state.update { s ->
        val crop = s.crop ?: return@update s
        val clamped = Pt(p.x.coerceIn(0f, crop.width.toFloat()), p.y.coerceIn(0f, crop.height.toFloat()))
        s.copy(crop = crop.copy(quad = crop.quad.with(index, clamped)))
    }

    fun resetCrop() = _state.update { s ->
        val crop = s.crop ?: return@update s
        s.copy(crop = crop.copy(quad = Quad.full(crop.width, crop.height)))
    }

    fun rotateCrop() = busy {
        val crop = state.value.crop ?: return@busy
        val source = BitmapFactory.decodeFile(crop.raw.absolutePath) ?: return@busy
        val rotated = ScanImages.rotateClockwise(source)
        val raw = ScanImages.saveJpeg(rotated, File(dir, "raw-${UUID.randomUUID()}.jpg"))
        _state.update {
            it.copy(crop = CropState(raw, rotated.width, rotated.height, crop.quad.rotatedClockwise(crop.height), crop.replaceIndex))
        }
    }

    fun applyCrop() = busy {
        val crop = state.value.crop ?: return@busy
        val source = BitmapFactory.decodeFile(crop.raw.absolutePath) ?: return@busy
        val warped = ScanImages.warp(source, crop.quad)
        val mode = if (crop.replaceIndex != null) ScanMode.DOCUMENT else state.value.mode
        when (mode) {
            ScanMode.ID_CARD -> {
                val front = state.value.idFront
                if (front == null) {
                    val file = ScanImages.saveJpeg(warped, File(dir, "id-${UUID.randomUUID()}.jpg"))
                    _state.update { it.copy(idFront = file, crop = null, step = ScanStep.CAMERA) }
                    _events.send(ScanEvent.Message(R.string.scan_id_back))
                    return@busy
                }
                val frontBitmap = BitmapFactory.decodeFile(front.absolutePath) ?: return@busy
                val sheet = ScanImages.saveJpeg(ScanImages.idSheet(frontBitmap, warped), File(dir, "page-${UUID.randomUUID()}.jpg"))
                _state.update { s ->
                    val page = ScanPage(nextId++, sheet, ScanImages.ID_SHEET_WIDTH, ScanImages.ID_SHEET_HEIGHT, Quad.full(ScanImages.ID_SHEET_WIDTH, ScanImages.ID_SHEET_HEIGHT), sheet)
                    s.copy(step = ScanStep.REVIEW, pages = s.pages + page, crop = null, idFront = null)
                }
            }
            ScanMode.BOOK -> {
                val (left, right) = ScanImages.splitSpread(warped)
                val pages = listOf(left, right).map { half ->
                    val file = ScanImages.saveJpeg(half, File(dir, "page-${UUID.randomUUID()}.jpg"))
                    ScanPage(nextId++, file, half.width, half.height, Quad.full(half.width, half.height), file)
                }
                _state.update { s -> s.copy(step = ScanStep.REVIEW, pages = s.pages + pages, crop = null) }
            }
            ScanMode.DOCUMENT -> {
                val processed = ScanImages.saveJpeg(warped, File(dir, "page-${UUID.randomUUID()}.jpg"))
                _state.update { s ->
                    val page = ScanPage(nextId++, crop.raw, crop.width, crop.height, crop.quad, processed)
                    val pages = crop.replaceIndex?.let { i -> s.pages.toMutableList().also { it[i] = page } } ?: (s.pages + page)
                    s.copy(step = ScanStep.REVIEW, pages = pages, crop = null)
                }
            }
        }
    }

    fun setMode(mode: ScanMode) = _state.update { it.copy(mode = mode, idFront = null) }

    fun setAutoCapture(on: Boolean) = _state.update { it.copy(autoCapture = on) }

    /** Back from cropping: a re-crop returns to the review, a new photo is discarded. */
    fun cancelCrop() = _state.update {
        val newPhoto = it.crop?.replaceIndex == null
        it.copy(crop = null, step = if (newPhoto) ScanStep.CAMERA else ScanStep.REVIEW)
    }

    fun recrop(index: Int) = _state.update { s ->
        val page = s.pages.getOrNull(index) ?: return@update s
        s.copy(step = ScanStep.CROP, crop = CropState(page.raw, page.width, page.height, page.quad, index))
    }

    // endregion

    // region Review

    fun showCamera() = _state.update { it.copy(step = ScanStep.CAMERA) }

    fun showReview() = _state.update { it.copy(step = ScanStep.REVIEW) }

    fun deletePage(index: Int) = _state.update { s ->
        val pages = s.pages.toMutableList().apply { removeAt(index) }
        s.copy(pages = pages, step = if (pages.isEmpty()) ScanStep.CAMERA else s.step)
    }

    fun movePage(index: Int, delta: Int) = _state.update { s ->
        val target = index + delta
        if (target !in s.pages.indices) return@update s
        s.copy(pages = s.pages.toMutableList().apply { add(target, removeAt(index)) })
    }

    fun setFilter(filter: ScanFilter) = _state.update { it.copy(filter = filter) }

    fun setRecognize(value: Boolean) = _state.update { it.copy(recognize = value) }

    suspend fun preview(page: ScanPage, filter: ScanFilter): Bitmap? {
        val key = "${page.id}:$filter"
        previews.get(key)?.let { return it }
        return withContext(io) {
            ScanImages.decodePreview(page.processed, PREVIEW_SIDE)?.let { ScanImages.filter(it, filter) }
        }?.also { previews.put(key, it) }
    }

    // endregion

    // region Export

    fun exportPdf(target: Uri) {
        val s = state.value
        exportJob = viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = runCatching {
                withContext(io) {
                    val out = files.newWorkFile()
                    try {
                        exporter.writePdf(s.pages.map { it.processed }, s.filter, s.recognize, out) { p ->
                            val (text, page, total) = when (p) {
                                is ExportProgress.Processing -> Triple(R.string.scan_progress_page, p.page, p.total)
                                is ExportProgress.Recognizing -> Triple(R.string.scan_progress_ocr, p.page, p.total)
                            }
                            _state.update { it.copy(progressText = text, progressArgs = listOf(page, total)) }
                        }
                        files.writeTo(target, out)
                    } finally {
                        out.delete()
                    }
                }
                documents.recordOpened(target)
            }
            _state.update { it.copy(busy = false, progressText = null) }
            if (result.isSuccess) _events.send(ScanEvent.Open(target))
            else if (result.exceptionOrNull() !is kotlinx.coroutines.CancellationException) {
                _events.send(ScanEvent.Message(R.string.error_save))
            }
        }
    }

    /** Saves every page as a JPEG into a folder the user picked. */
    fun exportImages(tree: Uri) {
        val s = state.value
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val ok = runCatching {
                withContext(io) {
                    val folder = DocumentFile.fromTreeUri(context, tree) ?: error("tree")
                    s.pages.forEachIndexed { i, page ->
                        _state.update { it.copy(progressText = R.string.scan_progress_page, progressArgs = listOf(i + 1, s.pages.size)) }
                        val bitmap = exporter.filtered(page.processed, s.filter)
                        val file = folder.createFile("image/jpeg", "$defaultName ${i + 1}") ?: error("create")
                        context.contentResolver.openOutputStream(file.uri)?.use {
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)
                        }
                    }
                }
            }.isSuccess
            _state.update { it.copy(busy = false, progressText = null) }
            _events.send(ScanEvent.Message(if (ok) R.string.scan_images_saved else R.string.error_save))
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
    }

    // endregion

    private fun busy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            runCatching { withContext(io) { block() } }
                .onFailure { _events.send(ScanEvent.Message(R.string.error_generic)) }
            _state.update { it.copy(busy = false) }
        }
    }

    override fun onCleared() {
        dir.deleteRecursively()
    }

    private companion object {
        const val PREVIEW_SIDE = 400
    }
}

private const val SHARED_DONE = "shared_images_imported"
