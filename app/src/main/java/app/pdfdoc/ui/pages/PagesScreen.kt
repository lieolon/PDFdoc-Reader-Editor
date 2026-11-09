package app.pdfdoc.ui.pages

import app.pdfdoc.ui.common.rememberCreateDocument
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import app.pdfdoc.domain.pdf.Paper
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.geometry.center
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pdfdoc.R
import app.pdfdoc.ui.common.BusyOverlay
import app.pdfdoc.ui.common.MessageView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagesScreen(
    onBack: () -> Unit,
    onOpenDocument: (Uri) -> Unit,
    viewModel: PagesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var confirmExit by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val extractName = stringResource(R.string.pages_extract_name, state.name.removeSuffix(".pdf"))

    val extractLauncher = rememberCreateDocument("application/pdf") { uri -> uri?.let(viewModel::extractSelected) }
    var inserting by remember { mutableStateOf(false) }
    if (inserting) {
        app.pdfdoc.ui.common.PdfPickerSheet(
            onDismiss = { inserting = false },
            onPicked = { uris -> inserting = false; uris.firstOrNull()?.let(viewModel::insertFrom) },
            multiple = false,
            title = R.string.picker_pdf_title,
        )
    }
    val saveAsLauncher = rememberCreateDocument("application/pdf") { uri -> uri?.let(viewModel::saveAs) }
    var choosingPaper by remember { mutableStateOf(false) }
    if (choosingPaper) {
        AlertDialog(
            onDismissRequest = { choosingPaper = false },
            title = { Text(stringResource(R.string.pages_insert_blank)) },
            text = {
                Column {
                    Paper.entries.forEach { paper ->
                        Text(
                            stringResource(paper.title()),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { choosingPaper = false; viewModel.insertBlank(paper) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosingPaper = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is PagesEvent.Message -> snackbar.showSnackbar(context.getString(event.text))
                is PagesEvent.Open -> onOpenDocument(event.uri)
                PagesEvent.Close -> onBack()
            }
        }
    }

    val tryExit = { if (state.dirty) confirmExit = true else onBack() }
    BackHandler(onBack = tryExit)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.selected.isEmpty()) stringResource(R.string.pages_title)
                        else stringResource(R.string.pages_selected, state.selected.size),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = tryExit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::undo, enabled = state.canUndo && !state.busy) {
                        Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo))
                    }
                    IconButton(onClick = { viewModel.save() }, enabled = state.dirty && !state.busy) {
                        Icon(Icons.Default.Save, stringResource(R.string.save))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.pages_select_all)) },
                                leadingIcon = { Icon(Icons.Default.SelectAll, null) },
                                onClick = { menu = false; viewModel.selectAll() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.pages_insert_blank)) },
                                leadingIcon = { Icon(Icons.Default.NoteAdd, null) },
                                onClick = { menu = false; choosingPaper = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.pages_insert_pdf)) },
                                leadingIcon = { Icon(Icons.Default.PostAdd, null) },
                                onClick = { menu = false; inserting = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.save_as)) },
                                leadingIcon = { Icon(Icons.Default.Save, null) },
                                onClick = { menu = false; saveAsLauncher.launch(state.name) },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (state.selected.isNotEmpty()) {
                BottomAppBar {
                    BottomAction(Icons.Default.RotateRight, stringResource(R.string.pages_rotate), viewModel::rotateSelected)
                    BottomAction(Icons.Default.Delete, stringResource(R.string.pages_delete), viewModel::deleteSelected)
                    BottomAction(Icons.Default.ContentCopy, stringResource(R.string.pages_duplicate), viewModel::duplicateSelected)
                    BottomAction(Icons.Default.FileOpen, stringResource(R.string.pages_extract)) {
                        extractLauncher.launch(extractName)
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.failed -> MessageView(stringResource(R.string.error_open))
                else -> Column {
                    Text(
                        stringResource(R.string.pages_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    PageGrid(state, viewModel)
                }
            }
            if (state.busy) BusyOverlay(stringResource(R.string.working))
        }
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
                TextButton(onClick = { confirmExit = false; onBack() }) {
                    Text(stringResource(R.string.pages_discard))
                }
            },
        )
    }
}

@Composable
private fun BottomAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null)
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PageGrid(state: PagesUiState, viewModel: PagesViewModel) {
    val gridState = rememberLazyGridState()
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragTarget by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    fun reset() {
        dragFrom = null
        dragTarget = null
        dragOffset = Offset.Zero
    }

    fun onMove(delta: Offset) {
        dragOffset += delta
        val from = dragFrom ?: return
        val item = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == from } ?: return
        val center = item.offset.toOffset() + item.size.toSize().center + dragOffset
        gridState.itemAt(center)?.let { dragTarget = it.index }
        // Auto-scroll when dragging near the edges; keep the item under the finger.
        val viewport = gridState.layoutInfo.viewportSize.height
        val edge = 120f
        val scroll = when {
            center.y > viewport - edge -> 24f
            center.y < edge -> -24f
            else -> 0f
        }
        if (scroll != 0f) dragOffset += Offset(0f, gridState.dispatchRawDelta(scroll))
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        state = gridState,
        contentPadding = PaddingValues(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(state.pageCount, key = { "${state.generation}:$it" }) { index ->
            val dragging = index == dragFrom
            // The drag detector sits on the item, below the grid's own scroll handling,
            // otherwise the grid consumes the moves and cancels the drag.
            val drag = Modifier.pointerInput(state.generation, index) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        dragFrom = index
                        dragTarget = index
                        dragOffset = Offset.Zero
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        onMove(amount)
                    },
                    onDragEnd = {
                        val from = dragFrom
                        val to = dragTarget
                        reset()
                        if (from != null && to != null) viewModel.move(from, to)
                    },
                    onDragCancel = ::reset,
                )
            }
            PageThumb(
                index = index,
                generation = state.generation,
                selected = index in state.selected,
                dropTarget = index == dragTarget && !dragging,
                modifier = if (dragging) {
                    drag
                        .zIndex(1f)
                        .graphicsLayer {
                            translationX = dragOffset.x
                            translationY = dragOffset.y
                            scaleX = 1.06f
                            scaleY = 1.06f
                            shadowElevation = 16f
                        }
                } else {
                    drag
                },
                onClick = { viewModel.toggle(index) },
                load = viewModel::thumbnail,
            )
        }
    }
}

/** Item offsets are relative to the content start, so content padding is ignored here. */
private fun LazyGridState.itemAt(point: Offset): LazyGridItemInfo? =
    layoutInfo.visibleItemsInfo.firstOrNull { item ->
        val o = item.offset
        point.x >= o.x && point.x < o.x + item.size.width && point.y >= o.y && point.y < o.y + item.size.height
    }

@Composable
private fun PageThumb(
    index: Int,
    generation: Int,
    selected: Boolean,
    dropTarget: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    load: suspend (Int, Int) -> Bitmap?,
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(index, generation) { bitmap = load(index, generation) }
    val borderColor = when {
        selected -> MaterialTheme.colorScheme.primary
        dropTarget -> MaterialTheme.colorScheme.tertiary
        else -> Color.Transparent
    }
    Column(modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // A clickable Card would consume the press and block the grid's long-press drag,
        // so taps are detected without consuming the down event.
        Card(
            border = BorderStroke(3.dp, borderColor),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
                .semantics {
                    this.selected = selected
                    onClick { onClick(); true }
                },
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(bitmap?.let { it.width / it.height.toFloat() } ?: 0.707f)) {
                bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
                    ?: CircularProgressIndicator(Modifier.align(Alignment.Center).size(20.dp), strokeWidth = 2.dp)
                if (selected) {
                    Icon(
                        Icons.Default.CheckCircle,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .background(Color.White, RoundedCornerShape(50)),
                    )
                }
            }
        }
        Text(
            "${index + 1}",
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun Paper.title() = when (this) {
    Paper.BLANK -> R.string.paper_blank
    Paper.LINED -> R.string.paper_lined
    Paper.SQUARED -> R.string.paper_squared
    Paper.DOTTED -> R.string.paper_dotted
    Paper.MUSIC -> R.string.paper_music
}
