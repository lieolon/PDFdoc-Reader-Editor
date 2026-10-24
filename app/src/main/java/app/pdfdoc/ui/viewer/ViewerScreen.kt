package app.pdfdoc.ui.viewer

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.RadioButton
import app.pdfdoc.domain.model.ViewMode
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.produceState
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
    onTools: (String) -> Unit,
    viewModel: ViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val mode by viewModel.viewMode.collectAsStateWithLifecycle()
    var modeDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val ocr by viewModel.ocr.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmOcr by remember { mutableStateOf(false) }
    var ocrHidden by remember { mutableStateOf(false) }
    var navigation by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // OCR runs either way; without the permission there is just no progress notification.
        ocrHidden = false
        viewModel.runOcr()
    }
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
                                        text = { Text(stringResource(R.string.viewer_mode)) },
                                        leadingIcon = { Icon(Icons.Default.ViewAgenda, null) },
                                        onClick = { menu = false; modeDialog = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.viewer_navigation)) },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                                        onClick = { menu = false; navigation = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.viewer_tools)) },
                                        leadingIcon = { Icon(Icons.Default.Build, null) },
                                        onClick = { menu = false; onTools(viewModel.uri.toString()) },
                                    )
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
            ViewerUiState.Unavailable -> Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.error_unavailable), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { viewModel.removeFromRecent(onBack) }) { Text(stringResource(R.string.home_remove_from_list)) }
            }
            is ViewerUiState.NeedsPassword -> PasswordPrompt(
                wrong = s.wrong,
                onSubmit = viewModel::submitPassword,
                modifier = Modifier.padding(padding),
            )
            is ViewerUiState.Ready -> {
                LaunchedEffect(s.generation) {
                    if (s.generation == 1) listState.scrollToItem(mode.itemOf(s.initialPage))
                }
                LaunchedEffect(listState, mode) {
                    snapshotFlow { currentPage(listState, mode) }.collect(viewModel::onPageVisible)
                }
                // Keep the current page when switching between layouts.
                var previousMode by remember { mutableStateOf(mode) }
                LaunchedEffect(mode) {
                    if (mode != previousMode) {
                        val page = previousMode.pageOf(listState.firstVisibleItemIndex)
                        previousMode = mode
                        listState.scrollToItem(mode.itemOf(page))
                    }
                }
                val currentHit = search.currentHit
                LaunchedEffect(currentHit) {
                    currentHit?.let { listState.animateScrollToItem(mode.itemOf(it.page)) }
                }
                Box(Modifier.fillMaxSize()) {
                    PdfPages(
                        state = s,
                        listState = listState,
                        mode = mode,
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
                        val current by remember(mode) { derivedStateOf { currentPage(listState, mode) + 1 } }
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
                            scope.launch { listState.scrollToItem(mode.itemOf(page)) }
                        },
                    )
                }
            }
        }
    }

    if (modeDialog) {
        AlertDialog(
            onDismissRequest = { modeDialog = false },
            title = { Text(stringResource(R.string.viewer_mode)) },
            text = {
                Column {
                    listOf(
                        ViewMode.CONTINUOUS to R.string.mode_continuous,
                        ViewMode.SINGLE to R.string.mode_single,
                        ViewMode.SPREAD to R.string.mode_spread,
                    ).forEach { (m, label) ->
                        ListItem(
                            headlineContent = { Text(stringResource(label)) },
                            leadingContent = { RadioButton(selected = mode == m, onClick = null) },
                            modifier = Modifier.clickable { modeDialog = false; viewModel.setViewMode(m) },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { modeDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (navigation) {
        val ready = state as? ViewerUiState.Ready
        NavigationSheet(
            viewModel = viewModel,
            currentPage = currentPage(listState, mode),
            onDismiss = { navigation = false },
            onPage = { page ->
                navigation = false
                if (ready != null && page in ready.pageSizes.indices) scope.launch { listState.scrollToItem(mode.itemOf(page)) }
            },
        )
    }
    if (confirmOcr) {
        AlertDialog(
            onDismissRequest = { confirmOcr = false },
            title = { Text(stringResource(R.string.ocr_confirm_title)) },
            text = { Text(stringResource(R.string.ocr_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmOcr = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        ocrHidden = false
                        viewModel.runOcr()
                    }
                }) { Text(stringResource(R.string.ocr_start)) }
            },
            dismissButton = { TextButton(onClick = { confirmOcr = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    ocr?.takeUnless { ocrHidden }?.let { run ->
        AlertDialog(
            onDismissRequest = { ocrHidden = true },
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
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.ocr_background_hint), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { ocrHidden = true }) { Text(stringResource(R.string.ocr_hide)) } },
            dismissButton = { TextButton(onClick = viewModel::cancelOcr) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * The page the user is looking at: in single-page mode pages snap to the center, so the one
 * nearest to it; otherwise the first visible one.
 */
private fun currentPage(listState: LazyListState, mode: ViewMode): Int {
    if (mode != ViewMode.SINGLE) return mode.pageOf(listState.firstVisibleItemIndex)
    val info = listState.layoutInfo
    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo.minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - center) }?.index
        ?: listState.firstVisibleItemIndex
}

@Composable
private fun PdfPages(
    state: ViewerUiState.Ready,
    listState: LazyListState,
    mode: ViewMode,
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

        // Height available for one page in single-page mode (below the top bar, above the indicator).
        val viewportHeight = maxHeight - listTopPadding - 16.dp
        val snap = rememberSnapFlingBehavior(lazyListState = listState)

        @Composable
        fun Page(index: Int, width: Dp) {
            val size = state.pageSizes[index]
            PageImage(
                index = index,
                pageWidthPt = size.width.toFloat(),
                highlights = search.hits.filter { it.page == index }.flatMap { it.rects },
                currentHit = search.currentHit?.takeIf { it.page == index }?.rects.orEmpty(),
                aspectRatio = size.width.toFloat() / size.height,
                width = width,
                renderWidth = renderWidth,
                generation = state.generation,
                nightMode = nightMode,
                pageBitmap = pageBitmap,
            )
        }

        val pageCount = state.pageSizes.size
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(hScroll),
            contentPadding = PaddingValues(top = listTopPadding, bottom = if (mode == ViewMode.SINGLE) 16.dp else 96.dp),
            verticalArrangement = Arrangement.spacedBy(if (mode == ViewMode.SINGLE) 16.dp else 8.dp),
            flingBehavior = if (mode == ViewMode.SINGLE && scale <= 1.01f) snap else ScrollableDefaults.flingBehavior(),
        ) {
            when (mode) {
                ViewMode.CONTINUOUS -> items(pageCount, key = { it }) { index -> Page(index, pageWidth) }
                ViewMode.SINGLE -> items(pageCount, key = { it }) { index ->
                    // Each page fits the screen; the box keeps it centered horizontally.
                    val size = state.pageSizes[index]
                    val fit = minOf(maxWidth, viewportHeight * (size.width.toFloat() / size.height))
                    Box(Modifier.width(pageWidth), contentAlignment = Alignment.TopCenter) { Page(index, fit * scale) }
                }
                ViewMode.SPREAD -> items((pageCount + 1) / 2, key = { it }) { row ->
                    Row(Modifier.width(pageWidth), horizontalArrangement = Arrangement.Center) {
                        Page(row * 2, pageWidth / 2)
                        if (row * 2 + 1 < pageCount) Page(row * 2 + 1, pageWidth / 2)
                    }
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NavigationSheet(viewModel: ViewerViewModel, currentPage: Int, onDismiss: () -> Unit, onPage: (Int) -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val outline by produceState<List<OutlineItem>?>(null) { value = viewModel.outline() }
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    var newBookmark by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.viewer_outline)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.viewer_bookmarks)) })
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 480.dp).navigationBarsPadding()) {
            if (tab == 0) {
                val items = outline
                when {
                    items == null -> item { CircularProgressIndicator(Modifier.padding(24.dp)) }
                    items.isEmpty() -> item { MessageView(stringResource(R.string.viewer_outline_empty), modifier = Modifier.heightIn(max = 200.dp)) }
                    else -> items(items.size) { i ->
                        val item = items[i]
                        ListItem(
                            headlineContent = { Text(item.title.ifEmpty { "—" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            trailingContent = { if (item.page >= 0) Text("${item.page + 1}") },
                            modifier = Modifier
                                .padding(start = (item.level * 16).dp)
                                .clickable(enabled = item.page >= 0) { onPage(item.page) },
                        )
                    }
                }
            } else {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.viewer_bookmark_add, currentPage + 1)) },
                        leadingContent = { Icon(Icons.Default.BookmarkAdd, null) },
                        modifier = Modifier.clickable { newBookmark = true },
                    )
                }
                if (bookmarks.isEmpty()) {
                    item { MessageView(stringResource(R.string.viewer_bookmarks_empty), modifier = Modifier.heightIn(max = 160.dp)) }
                }
                items(bookmarks.size) { i ->
                    val b = bookmarks[i]
                    ListItem(
                        headlineContent = { Text(b.title) },
                        supportingContent = { Text(stringResource(R.string.form_page, b.page + 1)) },
                        leadingContent = { Icon(Icons.Default.Bookmark, null) },
                        trailingContent = {
                            IconButton(onClick = { viewModel.deleteBookmark(b.id) }) {
                                Icon(Icons.Default.Close, stringResource(R.string.pages_delete))
                            }
                        },
                        modifier = Modifier.clickable { onPage(b.page) },
                    )
                }
            }
        }
    }
    if (newBookmark) {
        val default = stringResource(R.string.form_page, currentPage + 1)
        var title by remember { mutableStateOf(default) }
        AlertDialog(
            onDismissRequest = { newBookmark = false },
            title = { Text(stringResource(R.string.viewer_bookmark_title)) },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    newBookmark = false
                    viewModel.addBookmark(currentPage, title.ifBlank { default })
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { newBookmark = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun PasswordPrompt(wrong: Boolean, onSubmit: (String) -> Unit, modifier: Modifier) {
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Lock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.password_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.password_label)) },
            singleLine = true,
            isError = wrong,
            supportingText = if (wrong) ({ Text(stringResource(R.string.password_wrong)) }) else null,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (password.isNotEmpty()) onSubmit(password) }),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        stringResource(R.string.password_show),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onSubmit(password) }, enabled = password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.password_open))
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
