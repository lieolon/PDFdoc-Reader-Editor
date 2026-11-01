package app.pdfdoc.ui.editor

import app.pdfdoc.ui.common.rememberCreateDocument
import android.graphics.Paint
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Approval
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pdfdoc.R
import app.pdfdoc.domain.edit.Edit
import app.pdfdoc.domain.edit.ImageEdit
import app.pdfdoc.domain.edit.InkEdit
import app.pdfdoc.domain.edit.MarkupKind
import app.pdfdoc.domain.edit.NoteEdit
import app.pdfdoc.domain.edit.Pt
import app.pdfdoc.domain.edit.ShapeEdit
import app.pdfdoc.domain.edit.ShapeKind
import app.pdfdoc.domain.edit.StampEdit
import app.pdfdoc.domain.edit.TextEdit
import app.pdfdoc.domain.text.PageText
import app.pdfdoc.ui.common.BusyOverlay
import app.pdfdoc.ui.common.MessageView
import app.pdfdoc.ui.signature.SignatureSheet

private sealed interface TextPrompt {
    data class NewText(val page: Int, val at: Pt) : TextPrompt
    data class NewNote(val page: Int, val at: Pt) : TextPrompt
    data class Edit(val id: Long, val initial: String) : TextPrompt
    data object CustomStamp : TextPrompt
}

private data class ToolItem(val tool: Tool, val icon: ImageVector, val label: Int)

private val Tools = listOf(
    ToolItem(Tool.SELECT, Icons.Default.TouchApp, R.string.tool_select),
    ToolItem(Tool.HIGHLIGHT, Icons.Default.BorderColor, R.string.tool_highlight),
    ToolItem(Tool.UNDERLINE, Icons.Default.FormatUnderlined, R.string.tool_underline),
    ToolItem(Tool.STRIKEOUT, Icons.Default.FormatStrikethrough, R.string.tool_strikeout),
    ToolItem(Tool.REPLACE, Icons.Default.FindReplace, R.string.tool_replace),
    ToolItem(Tool.PEN, Icons.Default.Draw, R.string.tool_pen),
    ToolItem(Tool.SMART_PEN, Icons.Default.AutoFixHigh, R.string.tool_smart_pen),
    ToolItem(Tool.ERASER, Icons.Default.CleaningServices, R.string.tool_eraser),
    ToolItem(Tool.RECT, Icons.Default.CropSquare, R.string.tool_rect),
    ToolItem(Tool.OVAL, Icons.Default.RadioButtonUnchecked, R.string.tool_oval),
    ToolItem(Tool.LINE, Icons.Default.HorizontalRule, R.string.tool_line),
    ToolItem(Tool.ARROW, Icons.AutoMirrored.Filled.ArrowForward, R.string.tool_arrow),
    ToolItem(Tool.TEXT, Icons.Default.TextFields, R.string.tool_text),
    ToolItem(Tool.NOTE, Icons.Default.EditNote, R.string.tool_note),
    ToolItem(Tool.REDACT, Icons.Default.VisibilityOff, R.string.tool_redact),
    ToolItem(Tool.LINK, Icons.Default.Link, R.string.tool_link),
    ToolItem(Tool.MEASURE_DISTANCE, Icons.Default.Straighten, R.string.tool_measure),
    ToolItem(Tool.MEASURE_AREA, Icons.Default.SquareFoot, R.string.tool_measure_area),
    ToolItem(Tool.IMAGE_EDIT, Icons.Default.ImageSearch, R.string.tool_image_edit),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    onBack: () -> Unit,
    onOpenDocument: (Uri) -> Unit,
    viewModel: EditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var prompt by remember { mutableStateOf<TextPrompt?>(null) }
    var signatures by remember { mutableStateOf(false) }
    var stampMenu by remember { mutableStateOf(false) }
    var fieldMenu by remember { mutableStateOf(false) }
    var scaleDialog by remember { mutableStateOf(false) }
    var stampDialog by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var pageText by remember { mutableStateOf<PageText?>(null) }
    val scope = rememberCoroutineScope()

    val textPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = viewModel.typeface } }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::addImage)
    }
    val saveAs = rememberCreateDocument("application/pdf") { uri ->
        uri?.let(viewModel::saveAs)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is EditorEvent.Message -> snackbar.showSnackbar(context.getString(event.text))
                is EditorEvent.Open -> onOpenDocument(event.uri)
                EditorEvent.Close -> onBack()
            }
        }
    }
    LaunchedEffect(state.page, state.tool, state.loading) {
        val markup = state.tool == Tool.HIGHLIGHT || state.tool == Tool.UNDERLINE || state.tool == Tool.STRIKEOUT || state.tool == Tool.REPLACE
        pageText = if (markup && !state.loading) viewModel.pageText(state.page) else null
    }

    val tryExit = { if (state.dirty) confirmExit = true else onBack() }
    BackHandler(onBack = tryExit)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            stringResource(R.string.editor_title),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            state.name,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = tryExit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::undo, enabled = state.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo))
                    }
                    IconButton(onClick = viewModel::redo, enabled = state.canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo))
                    }
                    IconButton(onClick = { viewModel.save() }, enabled = state.dirty && !state.busy) {
                        Icon(Icons.Default.Save, stringResource(R.string.save))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.save_as)) },
                                onClick = { menu = false; saveAs.launch(state.name) },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!state.loading && !state.failed) {
                Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainer).navigationBarsPadding()) {
                    state.selected?.let { selected ->
                        SelectionBar(
                            edit = selected,
                            onDelete = viewModel::deleteSelected,
                            onRotate = viewModel::rotateSelected,
                            onEditText = { prompt = TextPrompt.Edit(selected.id, textOf(selected)) },
                        )
                        HorizontalDivider()
                    }
                    ToolHint(state, onScale = { scaleDialog = true }, viewModel = viewModel)
                    StyleBar(state, viewModel)
                    HorizontalDivider()
                    LazyRow(contentPadding = PaddingValues(horizontal = 4.dp)) {
                        items(Tools) { item ->
                            ToolButton(item.icon, stringResource(item.label), state.tool == item.tool) {
                                viewModel.selectTool(item.tool)
                            }
                        }
                        item {
                            Box {
                                ToolButton(Icons.Default.CheckBox, stringResource(R.string.tool_field), state.tool == Tool.FIELD) { fieldMenu = true }
                                FieldKindMenu(expanded = fieldMenu, onDismiss = { fieldMenu = false }) { kind ->
                                    fieldMenu = false
                                    viewModel.selectFieldKind(kind)
                                }
                            }
                        }
                        item {
                            ToolButton(Icons.Default.Image, stringResource(R.string.tool_image), false) {
                                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        }
                        item {
                            ToolButton(Icons.Default.Gesture, stringResource(R.string.tool_signature), false) { signatures = true }
                        }
                        item {
                            Box {
                                ToolButton(Icons.Default.Approval, stringResource(R.string.tool_stamp), false) { stampMenu = true }
                                StampMenu(
                                    expanded = stampMenu,
                                    onDismiss = { stampMenu = false },
                                    onStamp = { text, color -> stampMenu = false; viewModel.addStamp(text, color) },
                                    onDate = { stampMenu = false; viewModel.addDate() },
                                    onCustom = { stampMenu = false; stampDialog = true },
                                    saved = state.customStamps,
                                    onSaved = { text -> stampMenu = false; viewModel.addStamp(text, Palette.RED) },
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.failed -> MessageView(stringResource(R.string.error_open))
                else -> {
                    val pageSize = state.pageSizes[state.page]
                    EditorCanvas(
                        page = state.page,
                        pageSize = pageSize,
                        edits = state.pageEdits,
                        selected = state.selected,
                        tool = state.tool,
                        previewColor = state.style.color(state.tool),
                        previewWidth = state.style.width,
                        textPaint = textPaint,
                        pageText = pageText,
                        pageBitmap = viewModel::pageBitmap,
                        generation = state.generation,
                        vertices = if (state.tool == Tool.MEASURE_AREA) state.vertices else emptyList(),
                        gestures = object : EditorGestures {
                            override fun onStroke(points: List<Pt>) = viewModel.addStroke(state.page, points)
                            override fun onShape(kind: ShapeKind, start: Pt, end: Pt) = viewModel.addShape(state.page, kind, start, end)
                            override fun onMarkup(kind: MarkupKind, from: Pt, to: Pt) = viewModel.addMarkup(state.page, kind, from, to)
                            override fun onReplace(from: Pt, to: Pt) = viewModel.pickTextToReplace(state.page, from, to)
                            override fun onTap(at: Pt) {
                                when (state.tool) {
                                    Tool.MEASURE_AREA -> viewModel.addVertex(at)
                                    Tool.IMAGE_EDIT -> viewModel.detachImageAt(state.page, at)
                                    Tool.NOTE -> prompt = TextPrompt.NewNote(state.page, at)
                                    else -> prompt = TextPrompt.NewText(state.page, at)
                                }
                            }
                            override fun onSmartStroke(points: List<Pt>) = viewModel.addSmartStroke(state.page, points)
                            override fun onErase(path: List<Pt>) = viewModel.erase(state.page, path)
                            override fun onArea(start: Pt, end: Pt) = viewModel.addArea(state.page, start, end)
                            override fun onMeasureLine(start: Pt, end: Pt) = viewModel.addMeasureLine(state.page, start, end)
                            override fun onSelect(id: Long?) = viewModel.select(id)
                            override fun onPreview(edit: Edit) = viewModel.preview(edit)
                            override fun onCommitPreview() = viewModel.commitPreview()
                        },
                    )
                    PageNavigator(
                        page = state.page,
                        count = state.pageSizes.size,
                        onPage = viewModel::goToPage,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                    )
                }
            }
            if (state.busy) BusyOverlay(stringResource(R.string.working))
        }
    }

    prompt?.let { p ->
        TextPromptDialog(
            title = stringResource(
                when (p) {
                    is TextPrompt.NewNote -> R.string.editor_note_title
                    is TextPrompt.CustomStamp -> R.string.editor_stamp_title
                    else -> R.string.editor_text_title
                },
            ),
            initial = (p as? TextPrompt.Edit)?.initial.orEmpty(),
            singleLine = p is TextPrompt.CustomStamp,
            onDismiss = { prompt = null },
            onConfirm = { text ->
                prompt = null
                when (p) {
                    is TextPrompt.NewText -> viewModel.addText(p.page, p.at, text)
                    is TextPrompt.NewNote -> viewModel.addNote(p.page, p.at, text)
                    is TextPrompt.Edit -> viewModel.updateText(p.id, text)
                    TextPrompt.CustomStamp -> viewModel.addStamp(text.uppercase(), state.style.color(Tool.SELECT))
                }
            },
        )
    }

    state.replace?.let { replace ->
        TextPromptDialog(
            title = stringResource(R.string.editor_replace_title),
            initial = replace.text,
            singleLine = false,
            onDismiss = viewModel::cancelReplace,
            onConfirm = viewModel::confirmReplace,
            note = stringResource(R.string.editor_replace_note),
        )
    }

    state.area?.let { area ->
        when (area.tool) {
            Tool.LINK -> LinkDialog(pageCount = state.pageSizes.size, onDismiss = viewModel::cancelArea, onConfirm = viewModel::confirmLink)
            else -> FieldDialog(kind = state.fieldKind, onDismiss = viewModel::cancelArea, onConfirm = viewModel::confirmField)
        }
    }
    if (scaleDialog) {
        ScaleDialog(state.scale, onDismiss = { scaleDialog = false }) { scaleDialog = false; viewModel.setScale(it) }
    }
    if (stampDialog) {
        CustomStampDialog(
            saved = state.customStamps,
            hasAuthor = state.author.isNotBlank(),
            onRemove = viewModel::removeCustomStamp,
            onDismiss = { stampDialog = false },
            onConfirm = { text, color, name, date -> stampDialog = false; viewModel.addCustomStamp(text, color, name, date) },
        )
    }

    if (signatures) {
        val error = stringResource(R.string.editor_image_error)
        SignatureSheet(
            onPick = { id -> signatures = false; viewModel.addSignature(id) },
            onDismiss = { signatures = false },
            onError = {
                signatures = false
                scope.launch { snackbar.showSnackbar(error) }
            },
        )
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(stringResource(R.string.pages_unsaved_title)) },
            text = { Text(stringResource(R.string.pages_unsaved_text)) },
            confirmButton = {
                TextButton(onClick = { confirmExit = false; viewModel.save(closeAfter = true) }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false; onBack() }) { Text(stringResource(R.string.pages_discard)) }
            },
        )
    }
}

private fun textOf(edit: Edit) = when (edit) {
    is TextEdit -> edit.text
    is NoteEdit -> edit.text
    is StampEdit -> edit.text
    else -> ""
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .width(68.dp)
            .clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                RoundedCornerShape(12.dp),
            )
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StyleBar(state: EditorUiState, viewModel: EditorViewModel) {
    val selected = state.selected
    val tool = state.tool
    val plainTools = setOf(Tool.SELECT, Tool.ERASER, Tool.REDACT, Tool.LINK, Tool.FIELD, Tool.IMAGE_EDIT, Tool.MEASURE_DISTANCE, Tool.MEASURE_AREA)
    if (selected is ImageEdit || selected is app.pdfdoc.domain.edit.LinkEdit || selected is app.pdfdoc.domain.edit.RedactEdit ||
        selected is app.pdfdoc.domain.edit.FieldEdit || (selected == null && tool in plainTools)
    ) return
    val currentColor = when (selected) {
        is InkEdit -> selected.color
        is ShapeEdit -> selected.color
        is TextEdit -> selected.color
        is NoteEdit -> selected.color
        is StampEdit -> selected.color
        is app.pdfdoc.domain.edit.MarkupEdit -> selected.color
        is app.pdfdoc.domain.edit.CoverEdit -> selected.color
        else -> state.style.color(tool)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Palette.all.forEach { c ->
                Box(
                    Modifier
                        .size(28.dp)
                        .background(Color(c), CircleShape)
                        .border(
                            if (c == currentColor) 3.dp else 1.dp,
                            if (c == currentColor) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            CircleShape,
                        )
                        .clickable { viewModel.setColor(c) },
                )
            }
        }
        val width = (selected as? InkEdit)?.width ?: (selected as? ShapeEdit)?.width
            ?: state.style.width.takeIf { selected == null && tool.usesWidth }
        width?.let {
            LabeledSlider(stringResource(R.string.editor_width), it, 0.5f..20f, viewModel::setWidth)
        }
        val alpha = (selected as? InkEdit)?.alpha ?: state.style.alpha.takeIf { selected == null && (tool == Tool.PEN || tool == Tool.SMART_PEN) }
        alpha?.let {
            LabeledSlider(stringResource(R.string.editor_opacity), it, 0.1f..1f, viewModel::setAlpha)
        }
        val textSize = (selected as? TextEdit)?.size ?: state.style.textSize.takeIf { selected == null && tool == Tool.TEXT }
        textSize?.let {
            LabeledSlider(stringResource(R.string.editor_text_size), it, 6f..72f, viewModel::setTextSize)
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    // Local state keeps dragging smooth; the value is applied once on release (one undo step).
    var local by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(96.dp))
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SelectionBar(edit: Edit, onDelete: () -> Unit, onRotate: () -> Unit, onEditText: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, null)
            Text(stringResource(R.string.pages_delete), Modifier.padding(start = 6.dp))
        }
        if (edit is TextEdit || edit is NoteEdit || edit is StampEdit) {
            TextButton(onClick = onEditText) {
                Icon(Icons.Outlined.Edit, null)
                Text(stringResource(R.string.editor_edit_text), Modifier.padding(start = 6.dp))
            }
        }
        if (edit is ImageEdit) {
            TextButton(onClick = onRotate) {
                Icon(Icons.Default.RotateRight, null)
                Text(stringResource(R.string.pages_rotate), Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun StampMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onStamp: (String, Int) -> Unit,
    onDate: () -> Unit,
    onCustom: () -> Unit,
    saved: List<String> = emptyList(),
    onSaved: (String) -> Unit = {},
) {
    val approved = stringResource(R.string.stamp_approved)
    val rejected = stringResource(R.string.stamp_rejected)
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(approved, color = Color(Palette.GREEN)) }, onClick = { onStamp(approved, Palette.GREEN) })
        DropdownMenuItem(text = { Text(rejected, color = Color(Palette.RED)) }, onClick = { onStamp(rejected, Palette.RED) })
        DropdownMenuItem(
            text = { Text(stringResource(R.string.stamp_date)) },
            leadingIcon = { Icon(Icons.Default.Event, null) },
            onClick = onDate,
        )
        saved.take(6).forEach { text ->
            DropdownMenuItem(text = { Text(text, color = Color(Palette.RED)) }, onClick = { onSaved(text) })
        }
        DropdownMenuItem(text = { Text(stringResource(R.string.stamp_custom)) }, onClick = onCustom)
    }
}

@Composable
private fun PageNavigator(page: Int, count: Int, onPage: (Int) -> Unit, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onPage(page - 1) }, enabled = page > 0) {
                Icon(Icons.Default.ChevronLeft, stringResource(R.string.editor_previous_page))
            }
            Text(stringResource(R.string.viewer_page_indicator, page + 1, count))
            IconButton(onClick = { onPage(page + 1) }, enabled = page < count - 1) {
                Icon(Icons.Default.ChevronRight, stringResource(R.string.editor_next_page))
            }
        }
    }
}

@Composable
private fun TextPromptDialog(
    title: String,
    initial: String,
    singleLine: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    note: String? = null,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = singleLine,
                    minLines = if (singleLine) 1 else 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
