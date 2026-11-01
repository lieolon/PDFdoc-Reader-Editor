package app.pdfdoc.ui.tools

import app.pdfdoc.ui.common.rememberCreateDocument
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.BrandingWatermark
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ViewHeadline
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pdfdoc.R
import app.pdfdoc.data.pdf.PdfPermissions
import app.pdfdoc.domain.tools.CompressLevel
import app.pdfdoc.domain.tools.ImageWatermark
import app.pdfdoc.domain.tools.PageRanges
import app.pdfdoc.domain.tools.StampPosition
import app.pdfdoc.domain.tools.TextStamp
import app.pdfdoc.domain.tools.TextWatermark
import app.pdfdoc.domain.tools.WatermarkLayout
import app.pdfdoc.ui.common.BusyOverlay
import app.pdfdoc.ui.common.MessageView
import app.pdfdoc.ui.editor.Palette
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private enum class Section(val title: Int) { PAGES(R.string.tools_section_pages), DOCUMENT(R.string.tools_section_document), SECURITY(R.string.tools_section_security), EXPORT(R.string.tools_section_export) }

private enum class Tool(val icon: ImageVector, val title: Int, val description: Int, val section: Section) {
    NUMBERS(Icons.Default.FormatListNumbered, R.string.tool_numbers, R.string.tool_numbers_desc, Section.PAGES),
    HEADERS(Icons.Default.ViewHeadline, R.string.tool_headers, R.string.tool_headers_desc, Section.PAGES),
    WATERMARK(Icons.Default.BrandingWatermark, R.string.tool_watermark, R.string.tool_watermark_desc, Section.PAGES),
    CROP(Icons.Default.Crop, R.string.tool_crop, R.string.tool_crop_desc, Section.PAGES),
    RESIZE(Icons.Default.AspectRatio, R.string.tool_resize, R.string.tool_resize_desc, Section.PAGES),
    SPLIT(Icons.AutoMirrored.Filled.CallSplit, R.string.tool_split, R.string.tool_split_desc, Section.PAGES),
    PROPERTIES(Icons.Default.Info, R.string.tool_properties, R.string.tool_properties_desc, Section.DOCUMENT),
    OUTLINE(Icons.AutoMirrored.Filled.List, R.string.tool_outline, R.string.tool_outline_desc, Section.DOCUMENT),
    ATTACHMENTS(Icons.Default.AttachFile, R.string.tool_attachments, R.string.tool_attachments_desc, Section.DOCUMENT),
    FLATTEN(Icons.Default.Layers, R.string.tool_flatten, R.string.tool_flatten_desc, Section.DOCUMENT),
    COMPRESS(Icons.Default.Compress, R.string.tool_compress, R.string.tool_compress_desc, Section.DOCUMENT),
    PDFA(Icons.Default.Archive, R.string.tool_pdfa, R.string.tool_pdfa_desc, Section.DOCUMENT),
    PROTECT(Icons.Default.Lock, R.string.tool_protect, R.string.tool_protect_desc, Section.SECURITY),
    REDACT(Icons.Default.VisibilityOff, R.string.tool_redact_text, R.string.tool_redact_text_desc, Section.SECURITY),
    SANITIZE(Icons.Default.CleaningServices, R.string.tool_sanitize, R.string.tool_sanitize_desc, Section.SECURITY),
    SIGN(Icons.Default.VerifiedUser, R.string.tool_sign, R.string.tool_sign_desc, Section.SECURITY),
    VERIFY(Icons.Default.FactCheck, R.string.tool_verify, R.string.tool_verify_desc, Section.SECURITY),
    TEXT(Icons.AutoMirrored.Filled.TextSnippet, R.string.tool_text_export, R.string.tool_text_export_desc, Section.EXPORT),
    DOCX(Icons.Default.Description, R.string.tool_docx, R.string.tool_docx_desc, Section.EXPORT),
    XLSX(Icons.Default.TableChart, R.string.tool_xlsx, R.string.tool_xlsx_desc, Section.EXPORT),
    HTML(Icons.Default.Code, R.string.tool_html, R.string.tool_html_desc, Section.EXPORT),
    MARKDOWN(Icons.Default.Notes, R.string.tool_markdown, R.string.tool_markdown_desc, Section.EXPORT),
    EPUB(Icons.Default.MenuBook, R.string.tool_epub, R.string.tool_epub_desc, Section.EXPORT),
    IMAGES(Icons.Default.Image, R.string.tool_images_export, R.string.tool_images_export_desc, Section.EXPORT),
    COMPARE(Icons.Default.Difference, R.string.tool_compare, R.string.tool_compare_desc, Section.EXPORT),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    onBack: () -> Unit,
    onOpenDocument: (Uri) -> Unit,
    onCompare: (Uri, Uri) -> Unit,
    viewModel: ToolsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var active by remember { mutableStateOf<Tool?>(null) }
    var folderAction by remember { mutableStateOf<((Uri) -> Unit)?>(null) }

    val copyLauncher = rememberCreateDocument("application/pdf") { uri ->
        uri?.let(viewModel::applyPendingTo)
    }
    val textLauncher = rememberCreateDocument("text/plain") { uri ->
        uri?.let(viewModel::exportText)
    }
    val docxLauncher = rememberCreateDocument(DOCX_MIME) { uri -> uri?.let(viewModel::exportDocx) }
    val xlsxLauncher = rememberCreateDocument(app.pdfdoc.data.convert.TextFormat.XLSX.mime) { uri -> uri?.let { viewModel.exportAs(app.pdfdoc.data.convert.TextFormat.XLSX, it) } }
    val htmlLauncher = rememberCreateDocument(app.pdfdoc.data.convert.TextFormat.HTML.mime) { uri -> uri?.let { viewModel.exportAs(app.pdfdoc.data.convert.TextFormat.HTML, it) } }
    val markdownLauncher = rememberCreateDocument(app.pdfdoc.data.convert.TextFormat.MARKDOWN.mime) { uri -> uri?.let { viewModel.exportAs(app.pdfdoc.data.convert.TextFormat.MARKDOWN, it) } }
    val epubLauncher = rememberCreateDocument(app.pdfdoc.data.convert.TextFormat.EPUB.mime) { uri -> uri?.let { viewModel.exportAs(app.pdfdoc.data.convert.TextFormat.EPUB, it) } }
    val compareLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { other ->
        if (other != null) onCompare(viewModel.uri, other)
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) folderAction?.invoke(uri)
        folderAction = null
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ToolsEvent.Message -> snackbar.showSnackbar(context.getString(event.text, *event.args.toTypedArray()))
                is ToolsEvent.Open -> onOpenDocument(event.uri)
            }
        }
    }

    // Shared footer actions for tools that produce a new version of the document.
    val save = SaveActions(
        replace = { job -> active = null; viewModel.applyInPlace(job) },
        copy = { job, suffix ->
            active = null
            viewModel.prepareCopy(job)
            copyLauncher.launch("${viewModel.baseName} ($suffix).pdf")
        },
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.viewer_tools)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.failed -> MessageView(stringResource(R.string.error_open))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    Section.entries.forEach { section ->
                        item(key = section.name) {
                            Text(
                                stringResource(section.title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                        items(Tool.entries.filter { it.section == section }, key = { it.name }) { tool ->
                            ListItem(
                                headlineContent = { Text(stringResource(tool.title)) },
                                supportingContent = { Text(stringResource(tool.description)) },
                                leadingContent = { Icon(tool.icon, null, tint = MaterialTheme.colorScheme.primary) },
                                modifier = Modifier.clickable {
                                    when (tool) {
                                        Tool.TEXT -> textLauncher.launch("${viewModel.baseName}.txt")
                                        Tool.XLSX -> xlsxLauncher.launch("${viewModel.baseName}.xlsx")
                                        Tool.HTML -> htmlLauncher.launch("${viewModel.baseName}.html")
                                        Tool.MARKDOWN -> markdownLauncher.launch("${viewModel.baseName}.md")
                                        Tool.EPUB -> epubLauncher.launch("${viewModel.baseName}.epub")
                                        Tool.COMPARE -> compareLauncher.launch(arrayOf("application/pdf"))
                                        else -> active = tool
                                    }
                                },
                            )
                        }
                    }
                }
            }
            if (state.busy) {
                val text = state.progressText?.let { stringResource(it, *state.progressArgs.toTypedArray()) }
                    ?: stringResource(R.string.working)
                BusyOverlay(text)
            }
        }
    }

    active?.let { tool ->
        ToolSheet(onDismiss = { active = null }) {
            when (tool) {
                Tool.NUMBERS -> PageNumbersOptions(save)
                Tool.HEADERS -> HeadersOptions(save)
                Tool.WATERMARK -> WatermarkOptions(save, viewModel)
                Tool.COMPRESS -> CompressOptions(viewModel.defaultCompress.collectAsStateWithLifecycle().value, save)
                Tool.PROTECT -> ProtectOptions(state.protected, save)
                Tool.SPLIT -> SplitOptions(state.pageCount) { ranges ->
                    active = null
                    folderAction = { viewModel.split(ranges, it) }
                    folderLauncher.launch(null)
                }
                Tool.IMAGES -> ImagesOptions { format ->
                    active = null
                    folderAction = { viewModel.exportImages(it, format) }
                    folderLauncher.launch(null)
                }
                Tool.DOCX -> {
                    SheetTitle(R.string.tool_docx)
                    Text(stringResource(R.string.tool_docx_warning), style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = { active = null; docxLauncher.launch("${viewModel.baseName}.docx") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    ) { Text(stringResource(R.string.tool_docx_continue)) }
                }
                Tool.CROP -> CropOptions(save)
                Tool.RESIZE -> ResizeOptions(save)
                Tool.PROPERTIES -> PropertiesOptions(viewModel, save)
                Tool.OUTLINE -> OutlineOptions(viewModel, state.pageCount, save)
                Tool.ATTACHMENTS -> AttachmentsOptions(viewModel)
                Tool.FLATTEN -> SimpleJobOptions(R.string.tool_flatten, R.string.tool_flatten_note, DocumentJob.Flatten, "flat", save)
                Tool.PDFA -> SimpleJobOptions(R.string.tool_pdfa, R.string.tool_pdfa_note, DocumentJob.PdfA, "PDF-A", save)
                Tool.REDACT -> RedactTextOptions(save)
                Tool.SANITIZE -> SanitizeOptionsSheet(save)
                Tool.SIGN -> SignOptions(viewModel, state.pageCount, save)
                Tool.VERIFY -> VerifyOptions(viewModel)
                Tool.TEXT, Tool.XLSX, Tool.HTML, Tool.MARKDOWN, Tool.EPUB, Tool.COMPARE -> Unit
            }
        }
    }
}

private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

internal class SaveActions(val replace: (DocumentJob) -> Unit, val copy: (DocumentJob, String) -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            content = content,
        )
    }
}

@Composable
internal fun SheetTitle(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
}

@Composable
internal fun Label(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
internal fun SaveButtons(enabled: Boolean, onCopy: () -> Unit, onReplace: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onCopy, enabled = enabled, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.tools_save_copy)) }
        Button(onClick = onReplace, enabled = enabled, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.tools_replace)) }
    }
}

@Composable
internal fun SliderRow(label: Int, value: Float, range: ClosedFloatingPointRange<Float>, display: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.width(110.dp), style = MaterialTheme.typography.bodyMedium)
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(display, Modifier.width(48.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PositionPicker(value: StampPosition, top: Boolean?, onChange: (StampPosition) -> Unit) {
    val options = StampPosition.entries.filter { top == null || it.name.startsWith(if (top) "TOP" else "BOTTOM") }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { p ->
            FilterChip(selected = value == p, onClick = { onChange(p) }, label = { Text(stringResource(positionLabel(p))) })
        }
    }
}

private fun positionLabel(p: StampPosition) = when (p) {
    StampPosition.TOP_LEFT -> R.string.pos_top_left
    StampPosition.TOP_CENTER -> R.string.pos_top_center
    StampPosition.TOP_RIGHT -> R.string.pos_top_right
    StampPosition.BOTTOM_LEFT -> R.string.pos_bottom_left
    StampPosition.BOTTOM_CENTER -> R.string.pos_bottom_center
    StampPosition.BOTTOM_RIGHT -> R.string.pos_bottom_right
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PageNumbersOptions(save: SaveActions) {
    val formats = listOf("{n}", "{n} / {total}", stringResource(R.string.tool_numbers_format_long))
    var format by remember { mutableStateOf(formats[0]) }
    var position by remember { mutableStateOf(StampPosition.BOTTOM_CENTER) }
    var size by remember { mutableFloatStateOf(10f) }
    var first by remember { mutableStateOf("1") }
    SheetTitle(R.string.tool_numbers)
    Label(R.string.tool_numbers_format)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        formats.forEach { f ->
            FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.replace("{n}", "3").replace("{total}", "12")) })
        }
    }
    Label(R.string.tool_position)
    PositionPicker(position, null) { position = it }
    SliderRow(R.string.editor_text_size, size, 6f..24f, "${size.roundToInt()} pt") { size = it }
    OutlinedTextField(
        value = first,
        onValueChange = { v -> first = v.filter(Char::isDigit).take(5) },
        label = { Text(stringResource(R.string.tool_numbers_first)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    val job = DocumentJob.Stamps(listOf(TextStamp(format, position, size)), first.toIntOrNull() ?: 1)
    SaveButtons(first.toIntOrNull() != null, onCopy = { save.copy(job, "pages") }, onReplace = { save.replace(job) })
}

@Composable
private fun HeadersOptions(save: SaveActions) {
    var header by remember { mutableStateOf("") }
    var headerPos by remember { mutableStateOf(StampPosition.TOP_CENTER) }
    var footer by remember { mutableStateOf("") }
    var footerPos by remember { mutableStateOf(StampPosition.BOTTOM_CENTER) }
    var size by remember { mutableFloatStateOf(9f) }
    SheetTitle(R.string.tool_headers)
    Text(stringResource(R.string.tool_headers_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(header, { header = it }, label = { Text(stringResource(R.string.tool_header)) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
    PositionPicker(headerPos, top = true) { headerPos = it }
    OutlinedTextField(footer, { footer = it }, label = { Text(stringResource(R.string.tool_footer)) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
    PositionPicker(footerPos, top = false) { footerPos = it }
    SliderRow(R.string.editor_text_size, size, 6f..20f, "${size.roundToInt()} pt") { size = it }
    val stamps = listOfNotNull(
        header.takeIf { it.isNotBlank() }?.let { TextStamp(it, headerPos, size) },
        footer.takeIf { it.isNotBlank() }?.let { TextStamp(it, footerPos, size) },
    )
    val job = DocumentJob.Stamps(stamps, 1)
    SaveButtons(stamps.isNotEmpty(), onCopy = { save.copy(job, "header") }, onReplace = { save.replace(job) })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WatermarkOptions(save: SaveActions, viewModel: ToolsViewModel) {
    var image by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val draft = stringResource(R.string.tool_watermark_default)
    var color by remember { mutableStateOf(Palette.RED) }
    var size by remember { mutableFloatStateOf(60f) }
    var opacity by remember { mutableFloatStateOf(0.25f) }
    var angle by remember { mutableFloatStateOf(45f) }
    var layout by remember { mutableStateOf(WatermarkLayout.CENTER) }
    var imagePath by remember { mutableStateOf<String?>(null) }
    var width by remember { mutableFloatStateOf(0.5f) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { scope.launch { imagePath = viewModel.importWatermarkImage(it) } }
    }

    SheetTitle(R.string.tool_watermark)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !image, onClick = { image = false }, label = { Text(stringResource(R.string.tool_text)) })
        FilterChip(selected = image, onClick = { image = true }, label = { Text(stringResource(R.string.tool_image)) })
    }
    if (!image) {
        OutlinedTextField(
            text, { text = it },
            placeholder = { Text(draft) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Palette.all.forEach { c ->
                Box(
                    Modifier.size(28.dp).background(Color(c), CircleShape)
                        .border(if (c == color) 3.dp else 1.dp, if (c == color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .clickable { color = c },
                )
            }
        }
        SliderRow(R.string.editor_text_size, size, 16f..140f, "${size.roundToInt()}") { size = it }
    } else {
        OutlinedButton(
            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            modifier = Modifier.padding(top = 8.dp),
        ) { Text(stringResource(if (imagePath == null) R.string.tool_watermark_pick else R.string.tool_watermark_repick)) }
        SliderRow(R.string.tool_watermark_width, width, 0.1f..1f, "${(width * 100).roundToInt()}%") { width = it }
    }
    SliderRow(R.string.editor_opacity, opacity, 0.05f..1f, "${(opacity * 100).roundToInt()}%") { opacity = it }
    Label(R.string.tool_watermark_angle)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0f, 30f, 45f, 90f).forEach { a ->
            FilterChip(selected = angle == a, onClick = { angle = a }, label = { Text("${a.roundToInt()}°") })
        }
    }
    Label(R.string.tool_watermark_layout)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = layout == WatermarkLayout.CENTER, onClick = { layout = WatermarkLayout.CENTER }, label = { Text(stringResource(R.string.tool_watermark_center)) })
        FilterChip(selected = layout == WatermarkLayout.TILED, onClick = { layout = WatermarkLayout.TILED }, label = { Text(stringResource(R.string.tool_watermark_tiled)) })
    }
    val job = if (image) {
        imagePath?.let { DocumentJob.AddWatermark(ImageWatermark(it, width, opacity, angle, layout)) }
    } else {
        DocumentJob.AddWatermark(TextWatermark(text.ifBlank { draft }, size, color, opacity, angle, layout))
    }
    SaveButtons(job != null, onCopy = { job?.let { save.copy(it, "watermark") } }, onReplace = { job?.let(save.replace) })
}

@Composable
private fun CompressOptions(initial: CompressLevel, save: SaveActions) {
    var level by remember { mutableStateOf(initial) }
    SheetTitle(R.string.tool_compress)
    listOf(
        CompressLevel.LIGHT to (R.string.compress_light to R.string.compress_light_desc),
        CompressLevel.MEDIUM to (R.string.compress_medium to R.string.compress_medium_desc),
        CompressLevel.STRONG to (R.string.compress_strong to R.string.compress_strong_desc),
    ).forEach { (l, labels) ->
        ListItem(
            headlineContent = { Text(stringResource(labels.first)) },
            supportingContent = { Text(stringResource(labels.second)) },
            leadingContent = { RadioButton(selected = level == l, onClick = null) },
            modifier = Modifier.clickable { level = l },
        )
    }
    Text(stringResource(R.string.compress_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val job = DocumentJob.Compress(level)
    SaveButtons(true, onCopy = { save.copy(job, "compressed") }, onReplace = { save.replace(job) })
}

@Composable
private fun ProtectOptions(protected: Boolean, save: SaveActions) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var noPrint by remember { mutableStateOf(false) }
    var noCopy by remember { mutableStateOf(false) }
    var noModify by remember { mutableStateOf(false) }
    var owner by remember { mutableStateOf("") }
    SheetTitle(R.string.tool_protect)
    if (protected) {
        Text(stringResource(R.string.protect_current), style = MaterialTheme.typography.bodyMedium)
        SaveButtons(true, onCopy = { save.copy(DocumentJob.RemovePassword, "unlocked") }, onReplace = { save.replace(DocumentJob.RemovePassword) })
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.protect_change), style = MaterialTheme.typography.titleSmall)
    }
    OutlinedTextField(
        password, { password = it },
        label = { Text(stringResource(R.string.protect_password)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    OutlinedTextField(
        confirm, { confirm = it },
        label = { Text(stringResource(R.string.protect_confirm)) },
        singleLine = true,
        isError = confirm.isNotEmpty() && confirm != password,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    Label(R.string.protect_restrict)
    listOf(
        Triple(noPrint, { v: Boolean -> noPrint = v }, R.string.protect_no_print),
        Triple(noCopy, { v: Boolean -> noCopy = v }, R.string.protect_no_copy),
        Triple(noModify, { v: Boolean -> noModify = v }, R.string.protect_no_modify),
    ).forEach { (checked, set, label) ->
        Row(Modifier.fillMaxWidth().clickable { set(!checked) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = set)
            Text(stringResource(label))
        }
    }
    val restricted = noPrint || noCopy || noModify
    if (restricted) {
        OutlinedTextField(
            owner, { owner = it },
            label = { Text(stringResource(R.string.protect_owner)) },
            supportingText = { Text(stringResource(R.string.protect_owner_hint)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
    Text(stringResource(R.string.protect_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    val valid = password == confirm && (password.isNotEmpty() || (restricted && owner.isNotEmpty()))
    val job = DocumentJob.Protect(password, owner.ifEmpty { password }, PdfPermissions(print = !noPrint, copy = !noCopy, modify = !noModify, annotate = !noModify))
    SaveButtons(valid, onCopy = { save.copy(job, "protected") }, onReplace = { save.replace(job) })
}

@Composable
private fun SplitOptions(pageCount: Int, onSplit: (List<IntRange>) -> Unit) {
    var byRanges by remember { mutableStateOf(false) }
    var every by remember { mutableStateOf("1") }
    var ranges by remember { mutableStateOf("") }
    SheetTitle(R.string.tool_split)
    Text(stringResource(R.string.tool_split_pages, pageCount), style = MaterialTheme.typography.bodyMedium)
    Row(Modifier.fillMaxWidth().clickable { byRanges = false }, verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = !byRanges, onClick = { byRanges = false })
        Text(stringResource(R.string.tool_split_every))
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            every, { v -> every = v.filter(Char::isDigit).take(4) },
            singleLine = true,
            enabled = !byRanges,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(88.dp),
        )
    }
    Row(Modifier.fillMaxWidth().clickable { byRanges = true }, verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = byRanges, onClick = { byRanges = true })
        Text(stringResource(R.string.tool_split_ranges))
    }
    val parsed = if (byRanges) PageRanges.parse(ranges, pageCount) else every.toIntOrNull()?.takeIf { it > 0 }?.let { PageRanges.every(it, pageCount) }
    OutlinedTextField(
        ranges, { ranges = it },
        enabled = byRanges,
        placeholder = { Text("1-3, 4-6, 7-") },
        isError = byRanges && ranges.isNotEmpty() && parsed == null,
        supportingText = { Text(stringResource(R.string.tool_split_ranges_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    parsed?.let { Text(stringResource(R.string.tool_split_result, it.size), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
    Button(onClick = { parsed?.let(onSplit) }, enabled = parsed != null && parsed.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
        Text(stringResource(R.string.tool_choose_folder))
    }
}

@Composable
private fun ImagesOptions(onExport: (ImageFormat) -> Unit) {
    var format by remember { mutableStateOf(ImageFormat.JPG) }
    SheetTitle(R.string.tool_images_export)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ImageFormat.entries.forEach { f -> FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.name) }) }
    }
    Text(stringResource(R.string.tool_images_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    Button(onClick = { onExport(format) }, modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
        Text(stringResource(R.string.tool_choose_folder))
    }
}
