package app.pdfdoc.ui.reader

import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import android.view.WindowManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material.icons.filled.Timer
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.pdfdoc.R
import app.pdfdoc.core.IoDispatcher
import app.pdfdoc.data.convert.TextExports
import app.pdfdoc.data.files.DocumentStore
import app.pdfdoc.data.files.FileStore
import app.pdfdoc.data.pdf.PdfRenderSession
import app.pdfdoc.data.pdf.PdfRenderSessionFactory
import app.pdfdoc.data.pdf.RenderCopyBuilder
import app.pdfdoc.data.settings.SettingsRepository
import app.pdfdoc.ui.common.MessageView
import app.pdfdoc.ui.common.findActivity
import app.pdfdoc.ui.navigation.PresentRoute
import app.pdfdoc.ui.navigation.ReaderRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ReaderTheme(val background: Color, val text: Color) {
    LIGHT(Color(0xFFFFFFFF), Color(0xFF1B1B1B)),
    SEPIA(Color(0xFFF4ECD8), Color(0xFF5B4636)),
    DARK(Color(0xFF121212), Color(0xFFDADADA)),
}

data class ReaderUiState(val pages: List<List<String>>? = null, val failed: Boolean = false, val name: String = "")

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val files: FileStore,
    private val docs: DocumentStore,
    private val exports: TextExports,
    private val settings: SettingsRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<ReaderRoute>()
    val startPage = route.page
    private val _state = MutableStateFlow(ReaderUiState())
    val state = _state.asStateFlow()

    val fontSize = settings.readerFontSize.stateIn(viewModelScope, SharingStarted.Eagerly, 18f)
    val theme = settings.readerTheme.stateIn(viewModelScope, SharingStarted.Eagerly, "LIGHT")
    val serif = settings.readerSerif.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    init {
        viewModelScope.launch {
            runCatching {
                withContext(io) {
                    val uri = Uri.parse(route.uri)
                    val work = docs.openWorkingCopy(uri)
                    try {
                        files.query(uri).name to exports.paragraphs(work)
                    } finally {
                        work.delete()
                    }
                }
            }.onSuccess { (name, pages) -> _state.value = ReaderUiState(pages, name = name) }
                .onFailure { _state.value = ReaderUiState(failed = true) }
        }
    }

    fun update(size: Float = fontSize.value, theme: String = this.theme.value, serif: Boolean = this.serif.value) {
        viewModelScope.launch { settings.setReader(size.coerceIn(12f, 34f), theme, serif) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(onBack: () -> Unit, viewModel: ReaderViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val size by viewModel.fontSize.collectAsStateWithLifecycle()
    val themeName by viewModel.theme.collectAsStateWithLifecycle()
    val serif by viewModel.serif.collectAsStateWithLifecycle()
    val theme = runCatching { ReaderTheme.valueOf(themeName) }.getOrDefault(ReaderTheme.LIGHT)
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = theme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = theme.background, titleContentColor = theme.text, navigationIconContentColor = theme.text, actionIconContentColor = theme.text),
                title = { Text(state.name.ifEmpty { stringResource(R.string.reader_mode) }, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    IconButton(onClick = { viewModel.update(size = size - 2f) }) { Icon(Icons.Default.TextDecrease, stringResource(R.string.reader_smaller)) }
                    IconButton(onClick = { viewModel.update(size = size + 2f) }) { Icon(Icons.Default.TextIncrease, stringResource(R.string.reader_larger)) }
                    Box {
                        Text(
                            "Aa",
                            color = theme.text,
                            modifier = Modifier.padding(horizontal = 12.dp).pointerInput(Unit) { detectTapGestures { menu = true } },
                        )
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            ReaderTheme.entries.forEach { t ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(themeLabel(t))) },
                                    onClick = { menu = false; viewModel.update(theme = t.name) },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(if (serif) R.string.reader_sans else R.string.reader_serif)) },
                                onClick = { menu = false; viewModel.update(serif = !serif) },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val pages = state.pages
        when {
            state.failed -> MessageView(stringResource(R.string.error_open), modifier = Modifier.padding(padding))
            pages == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            pages.all { it.isEmpty() } -> MessageView(stringResource(R.string.reader_no_text), modifier = Modifier.padding(padding))
            else -> {
                val list = rememberLazyListState()
                // Each page is a header item followed by its paragraphs.
                val firstItem = remember(pages) { pages.take(viewModel.startPage).sumOf { it.size + 1 } }
                LaunchedEffect(pages) { if (firstItem > 0) list.scrollToItem(firstItem) }
                LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                    pages.forEachIndexed { i, paragraphs ->
                        item(key = "p$i") {
                            Text(
                                stringResource(R.string.form_page, i + 1),
                                color = theme.text.copy(alpha = 0.45f),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                            )
                        }
                        paragraphs.forEachIndexed { j, text ->
                            item(key = "p$i-$j") {
                                Text(
                                    text,
                                    color = theme.text,
                                    fontSize = size.sp,
                                    lineHeight = (size * 1.5f).sp,
                                    fontFamily = if (serif) FontFamily.Serif else FontFamily.SansSerif,
                                    modifier = Modifier.padding(bottom = (size * 0.7f).dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun themeLabel(t: ReaderTheme) = when (t) {
    ReaderTheme.LIGHT -> R.string.reader_light
    ReaderTheme.SEPIA -> R.string.reader_sepia
    ReaderTheme.DARK -> R.string.reader_dark
}

@HiltViewModel
class PresentViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val files: FileStore,
    private val docs: DocumentStore,
    private val renderCopies: RenderCopyBuilder,
    private val renderers: PdfRenderSessionFactory,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<PresentRoute>()
    val startPage = route.page
    private val _sizes = MutableStateFlow<List<Size>?>(null)
    val sizes = _sizes.asStateFlow()
    private var session: PdfRenderSession? = null
    private val temp = mutableListOf<File>()

    init {
        viewModelScope.launch {
            runCatching {
                withContext(io) {
                    val work = docs.openWorkingCopy(Uri.parse(route.uri))
                    val flat = files.newWorkFile()
                    temp += listOf(work, flat)
                    val render = if (renderCopies.build(work, flat, excludeOwn = false)) flat else work
                    renderers.open(render)
                }
            }.onSuccess {
                session = it
                _sizes.value = it.pageSizes
            }.onFailure { _sizes.value = emptyList() }
        }
    }

    suspend fun page(index: Int, width: Int): Bitmap? = withContext(io) { session?.render(index, width) }

    override fun onCleared() {
        session?.close()
        temp.forEach(File::delete)
    }
}

/** Pages one at a time on black, full screen; tap the sides to move, optional automatic advance. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PresentScreen(onBack: () -> Unit, viewModel: PresentViewModel = hiltViewModel()) {
    val sizes by viewModel.sizes.collectAsStateWithLifecycle()
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    val list = sizes
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            list.isEmpty() -> MessageView(stringResource(R.string.error_open))
            else -> {
                val pager = rememberPagerState(initialPage = viewModel.startPage.coerceIn(0, list.size - 1)) { list.size }
                val scope = rememberCoroutineScope()
                var controls by remember { mutableStateOf(true) }
                var seconds by remember { mutableIntStateOf(0) }
                var laser by remember { mutableStateOf(false) }
                var laserAt by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
                LaunchedEffect(seconds, pager.currentPage) {
                    if (seconds > 0 && pager.currentPage < list.size - 1) {
                        delay(seconds * 1000L)
                        pager.animateScrollToPage(pager.currentPage + 1)
                    }
                }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceAtMost(2048)
                    HorizontalPager(state = pager, userScrollEnabled = !laser, modifier = Modifier.fillMaxSize()) { index ->
                        val bitmap by produceState<Bitmap?>(null, index) { value = viewModel.page(index, widthPx) }
                        val size = list[index]
                        Box(
                            Modifier.fillMaxSize().pointerInput(index) {
                                detectTapGestures { pos ->
                                    when {
                                        pos.x < this.size.width * 0.3f -> scope.launch { pager.animateScrollToPage((index - 1).coerceAtLeast(0)) }
                                        pos.x > this.size.width * 0.7f -> scope.launch { pager.animateScrollToPage((index + 1).coerceAtMost(list.size - 1)) }
                                        else -> controls = !controls
                                    }
                                }
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            bitmap?.let {
                                Image(
                                    it.asImageBitmap(), null,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(size.width.toFloat() / size.height, matchHeightConstraintsFirst = size.width < size.height),
                                )
                            }
                        }
                    }
                }
                if (laser) {
                    // Laser pointer: drag to point, tap the sides to change slides as usual.
                    Box(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                laserAt = down.position
                                var moved = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    if (!change.pressed) break
                                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                                    laserAt = change.position
                                    change.consume()
                                }
                                laserAt = null
                                if (!moved) {
                                    val x = down.position.x
                                    when {
                                        x < size.width * 0.3f -> scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) }
                                        x > size.width * 0.7f -> scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(list.size - 1)) }
                                        else -> controls = !controls
                                    }
                                }
                            }
                        },
                    ) {
                        laserAt?.let { at ->
                            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                                drawCircle(Color(0x55FF1744), 28.dp.toPx(), at)
                                drawCircle(Color(0xAAFF1744), 14.dp.toPx(), at)
                                drawCircle(Color(0xFFFF5252), 7.dp.toPx(), at)
                            }
                        }
                    }
                }
                if (controls) {
                    Row(
                        Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0x99000000)).padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        IconButton(onClick = onBack) { Icon(Icons.Default.Close, stringResource(R.string.back), tint = Color.White) }
                        Text(stringResource(R.string.viewer_page_indicator, pager.currentPage + 1, list.size), color = Color.White)
                        Row(
                            Modifier.weight(1f).horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                        ) {
                        FilterChip(
                            selected = laser,
                            onClick = { laser = !laser },
                            label = { Text(stringResource(R.string.laser_pointer), color = Color.White) },
                            leadingIcon = { Icon(Icons.Default.Adjust, null, tint = Color(0xFFFF5252)) },
                        )
                        Icon(Icons.Default.Timer, null, tint = Color.White)
                        listOf(0, 5, 10, 30).forEach { s ->
                            FilterChip(
                                selected = seconds == s,
                                onClick = { seconds = s },
                                label = { Text(if (s == 0) "—" else "${s}s", color = Color.White) },
                            )
                        }
                        }
                    }
                }
            }
        }
    }
}
