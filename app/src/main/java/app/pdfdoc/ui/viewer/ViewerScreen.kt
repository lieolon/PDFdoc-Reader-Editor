package app.pdfdoc.ui.viewer

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.text.input.ImeAction
import app.pdfdoc.domain.edit.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pdfdoc.R
import app.pdfdoc.ui.common.MessageView
import app.pdfdoc.ui.common.findActivity
import app.pdfdoc.ui.common.openPdfWith
import app.pdfdoc.ui.common.printPdf
import app.pdfdoc.ui.common.sharePdf
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val MAX_RENDER_WIDTH_PX = 2048

private val InvertColors = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    onBack: () -> Unit,
    onPages: (String) -> Unit,
    onEdit: (String) -> Unit,
    onFillForm: (String) -> Unit,
    viewModel: ViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val ocr by viewModel.ocr.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmOcr by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { m -> snackbar.showSnackbar(context.getString(m.text, *m.args.toTypedArray())) }
    }
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var nightMode by rememberSaveable { mutableStateOf(false) }
    var goToDialog by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val title = (state as? ViewerUiState.Ready)?.name.orEmpty()

    FullscreenEffect(fullscreen = !chromeVisible)
    BackHandler(enabled = !chromeVisible) { chromeVisible = true }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            AnimatedVisibility(chromeVisible || search.active, enter = fadeIn(), exit = fadeOut()) {
                if (search.active) {
                    SearchBar(
                        state = search,
                        onQueryChange = viewModel::onQueryChange,
                        onSubmit = viewModel::submitSearch,
                        onNext = viewModel::nextHit,
                        onPrevious = viewModel::previousHit,
                        onClose = viewModel::closeSearch,
                    )
                } else {
                    TopAppBar(
                        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                            }
                        },
                        actions = {
                            val ready = state is ViewerUiState.Ready
                            IconButton(onClick = viewModel::openSearch, enabled = ready) {
                                Icon(Icons.Default.Search, stringResource(R.string.viewer_search))
                            }
                            IconButton(onClick = { onEdit(viewModel.uri.toString()) }, enabled = ready) {
                                Icon(Icons.Default.Edit, stringResource(R.string.viewer_edit))
                            }
                            IconButton(onClick = { onPages(viewModel.uri.toString()) }, enabled = ready) {
                                Icon(Icons.Default.GridView, stringResource(R.string.viewer_pages))
                            }
                            Box {
                                IconButton(onClick = { menu = true }) {
                                    Icon(Icons.Default.MoreVert, stringResource(R.string.more))
                                }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    val share = stringResource(R.string.viewer_share)
                                    val openWith = stringResource(R.string.viewer_open_with)
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.viewer_night_mode)) },
                                        leadingIcon = {
                                            Icon(if (nightMode) Icons.Default.LightMode else Icons.Default.DarkMode, null)
                                        },
                                        onClick = { menu = false; nightMode = !nightMode },
                                    )
                                    if ((state as? ViewerUiState.Ready)?.hasForm == true) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.viewer_fill_form)) },
                                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Assignment, null) },
                                            onClick = { menu = false; onFillForm(viewModel.uri.toString()) },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.viewer_ocr)) },
                                        leadingIcon = { Icon(Icons.Default.DocumentScanner, null) },
                                        onClick = { menu = false; confirmOcr = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(share) },
                                        leadingIcon = { Icon(Icons.Default.Share, null) },
                                        onClick = { menu = false; sharePdf(context, viewModel.uri, share) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(openWith) },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                                        onClick = { menu = false; openPdfWith(context, viewModel.uri, openWith) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.viewer_print)) },
                                        leadingIcon = { Icon(Icons.Default.Print, null) },
                                        onClick = { menu = false; printPdf(context, viewModel.uri, title) },
                                    )
                                }
                            }
                        },
                    )
                }
            }
        },
    ) { padding ->
        when (val s = state) {
            ViewerUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            ViewerUiState.Error -> MessageView(stringResource(R.string.error_open), modifier = Modifier.padding(padding))
            is ViewerUiState.Ready -> {
                LaunchedEffect(s.generation) {
                    if (s.generation == 1) listState.scrollToItem(s.initialPage)
                }
                LaunchedEffect(listState) {
                    snapshotFlow { listState.firstVisibleItemIndex }.collect(viewModel::onPageVisible)
                }
                val currentHit = search.currentHit
                LaunchedEffect(currentHit) {
                    currentHit?.let { listState.animateScrollToItem(it.page) }
                }
                Box(Modifier.fillMaxSize()) {
                    PdfPages(
                        state = s,
                        listState = listState,
                        nightMode = nightMode,
                        topPadding = padding.calculateTopPadding(),
                        onTap = { chromeVisible = !chromeVisible },
                        pageBitmap = viewModel::pageBitmap,
                        search = search,
                    )
                    AnimatedVisibility(
                        chromeVisible,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        val current by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
                            modifier = Modifier
                                .navigationBarsPadding()
                                .padding(bottom = 16.dp)
                                .clickable { goToDialog = true },
                        ) {
                            Text(
                                stringResource(R.string.viewer_page_indicator, current, s.pageSizes.size),
                                color = MaterialTheme.colorScheme.inverseOnSurface,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
                if (goToDialog) {
                    GoToPageDialog(
                        pageCount = s.pageSizes.size,
                        onDismiss = { goToDialog = false },
                        onGo = { page ->
                            goToDialog = false
                            scope.launch { listState.scrollToItem(page) }
                        },
                    )
                }
            }
        }
    }

    if (confirmOcr) {
        AlertDialog(
            onDismissRequest = { confirmOcr = false },
            title = { Text(stringResource(R.string.ocr_confirm_title)) },
            text = { Text(stringResource(R.string.ocr_confirm_text)) },
            confirmButton = {
                TextButton(onClick = { confirmOcr = false; viewModel.runOcr() }) { Text(stringResource(R.string.ocr_start)) }
            },
            dismissButton = { TextButton(onClick = { confirmOcr = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    ocr?.let { run ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.viewer_ocr)) },
            text = {
                Column {
                    if (run.total > 0) {
                        Text(stringResource(R.string.ocr_progress, run.page, run.total))
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { run.page / run.total.toFloat() }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = viewModel::cancelOcr) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun PdfPages(
    state: ViewerUiState.Ready,
    listState: LazyListState,
    nightMode: Boolean,
    topPadding: Dp,
    onTap: () -> Unit,
    pageBitmap: suspend (Int, Int) -> Bitmap?,
    search: SearchState,
) {
    var scale by rememberSaveable { mutableFloatStateOf(1f) }
    val hScroll = rememberScrollState()
    var pendingHScroll by remember { mutableStateOf<Float?>(null) }
    val listTopPadding = topPadding + 8.dp
    val listTopPaddingPx = with(LocalDensity.current) { listTopPadding.toPx() }

    // Keeps the point under the fingers (in viewport coordinates) fixed while zooming.
    fun zoomAround(target: Float, centroid: Offset) {
        val old = scale
        val new = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val ratio = new / old
        if (ratio == 1f) return
        scale = new
        // The list content starts below the top padding, which does not scale.
        val y = centroid.y - listTopPaddingPx
        val offset = listState.firstVisibleItemScrollOffset
        listState.requestScrollToItem(
            listState.firstVisibleItemIndex,
            ((offset + y) * ratio - y).roundToInt().coerceAtLeast(0),
        )
        pendingHScroll = (hScroll.value + centroid.x) * ratio - centroid.x
    }

    LaunchedEffect(scale) {
        val target = pendingHScroll ?: return@LaunchedEffect
        withFrameNanos { } // wait for the relayout so the new scroll range is known
        hScroll.scrollTo(target.roundToInt())
        pendingHScroll = null
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(if (nightMode) Color.Black else MaterialTheme.colorScheme.surfaceContainerHighest)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) zoomAround(scale * zoom, event.calculateCentroid())
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { zoomAround(if (scale > 1.05f) 1f else DOUBLE_TAP_ZOOM, it) },
                )
            },
    ) {
        val pageWidth = maxWidth * scale
        val density = LocalDensity.current
        val basePx = with(density) { maxWidth.roundToPx() }
        val bucket = when {
            scale < 1.5f -> 1
            scale < 2.5f -> 2
            else -> 3
        }
        val renderWidth = (basePx * bucket).coerceAtMost(MAX_RENDER_WIDTH_PX)

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(hScroll),
            contentPadding = PaddingValues(top = listTopPadding, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.pageSizes.size, key = { it }) { index ->
                val size = state.pageSizes[index]
                PageImage(
                    index = index,
                    pageWidthPt = size.width.toFloat(),
                    highlights = search.hits.filter { it.page == index }.flatMap { it.rects },
                    currentHit = search.currentHit?.takeIf { it.page == index }?.rects.orEmpty(),
                    aspectRatio = size.width.toFloat() / size.height,
                    width = pageWidth,
                    renderWidth = renderWidth,
                    generation = state.generation,
                    nightMode = nightMode,
                    pageBitmap = pageBitmap,
                )
            }
        }
    }
}

@Composable
private fun PageImage(
    index: Int,
    pageWidthPt: Float,
    highlights: List<Box>,
    currentHit: List<Box>,
    aspectRatio: Float,
    width: Dp,
    renderWidth: Int,
    generation: Int,
    nightMode: Boolean,
    pageBitmap: suspend (Int, Int) -> Bitmap?,
) {
    // Keep showing the previous bitmap while a sharper one renders.
    var bitmap by remember(index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(index, renderWidth, generation) {
        pageBitmap(index, renderWidth)?.let { bitmap = it }
    }
    Box(
        Modifier
            .width(width)
            .padding(horizontal = 8.dp)
            .aspectRatio(aspectRatio)
            .background(if (nightMode) Color.Black else Color.White),
        contentAlignment = Alignment.Center,
    ) {
        val current = bitmap
        if (current != null) {
            Image(
                current.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                colorFilter = if (nightMode) InvertColors else null,
            )
        } else {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        if (highlights.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                val k = size.width / pageWidthPt
                fun draw(b: Box, color: Color) = drawRect(
                    color,
                    topLeft = Offset(b.left * k, b.top * k),
                    size = androidx.compose.ui.geometry.Size(b.width * k, b.height * k),
                    blendMode = BlendMode.Multiply,
                )
                highlights.forEach { draw(it, SearchHighlight) }
                currentHit.forEach { draw(it, SearchCurrent) }
            }
        }
    }
}

private val SearchHighlight = Color(0x66FFEB3B)
private val SearchCurrent = Color(0xAAFF9800)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(
    state: SearchState,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
        },
        title = {
            TextField(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.viewer_search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); onSubmit() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        actions = {
            when {
                state.running && state.hits.isEmpty() ->
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                state.searched -> Text(
                    if (state.hits.isEmpty()) stringResource(R.string.viewer_search_none)
                    else stringResource(R.string.viewer_search_count, state.current + 1, state.hits.size),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            IconButton(onClick = onPrevious, enabled = state.hits.size > 1) {
                Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.viewer_search_previous))
            }
            IconButton(onClick = onNext, enabled = state.hits.size > 1) {
                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.viewer_search_next))
            }
        },
    )
}

@Composable
private fun GoToPageDialog(pageCount: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val page = text.toIntOrNull()?.takeIf { it in 1..pageCount }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.viewer_go_to_page)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter(Char::isDigit).take(6) },
                label = { Text(stringResource(R.string.viewer_page_number, pageCount)) },
                singleLine = true,
                isError = text.isNotEmpty() && page == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(onClick = { page?.let { onGo(it - 1) } }, enabled = page != null) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun FullscreenEffect(fullscreen: Boolean) {
    val view = LocalView.current
    DisposableEffect(fullscreen) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (fullscreen) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
