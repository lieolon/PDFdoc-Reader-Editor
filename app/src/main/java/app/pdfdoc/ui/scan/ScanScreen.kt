package app.pdfdoc.ui.scan

import app.pdfdoc.ui.common.rememberCreateDocument
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pdfdoc.R
import app.pdfdoc.domain.edit.Pt
import app.pdfdoc.domain.edit.distance
import app.pdfdoc.domain.scan.ScanFilter
import app.pdfdoc.ui.common.BusyOverlay
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ScanScreen(
    onBack: () -> Unit,
    onOpenDocument: (Uri) -> Unit,
    viewModel: ScanViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var confirmExit by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ScanEvent.Message -> snackbar.showSnackbar(context.getString(event.text))
                is ScanEvent.Open -> onOpenDocument(event.uri)
            }
        }
    }

    val exit = { if (state.pages.isNotEmpty()) confirmExit = true else onBack() }
    // "Photos to PDF" starts on its own screen, where the user chooses between the gallery and the camera.
    var useCamera by rememberSaveable { mutableStateOf(!viewModel.fromImages) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) viewModel.import(uris)
    }
    val pickImages = {
        gallery.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    BackHandler {
        when (state.step) {
            ScanStep.CROP -> viewModel.cancelCrop()
            ScanStep.CAMERA -> if (state.pages.isNotEmpty()) viewModel.showReview() else onBack()
            ScanStep.REVIEW -> exit()
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (state.step) {
            ScanStep.CAMERA -> if (!useCamera && state.pages.isEmpty()) ImagesStart(
                onPick = pickImages,
                onCamera = { useCamera = true },
                onBack = onBack,
            ) else CameraCapture(
                pageCount = state.pages.size,
                mode = state.mode,
                autoCapture = state.autoCapture,
                waitingForBack = state.idFront != null,
                onMode = viewModel::setMode,
                onAutoCapture = viewModel::setAutoCapture,
                onCaptured = viewModel::onCaptured,
                onImport = viewModel::import,
                onDone = viewModel::showReview,
                onBack = { if (state.pages.isNotEmpty()) viewModel.showReview() else onBack() },
            )
            ScanStep.CROP -> state.crop?.let { CropStep(it, viewModel) }
            ScanStep.REVIEW -> ReviewStep(state, viewModel, snackbar, onExit = exit)
        }
        if (state.busy) {
            val text = state.progressText?.let { stringResource(it, *state.progressArgs.toTypedArray()) }
                ?: stringResource(R.string.working)
            BusyOverlay(text)
        }
        if (state.step != ScanStep.REVIEW) SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(stringResource(R.string.scan_discard_title)) },
            text = { Text(stringResource(R.string.scan_discard_text)) },
            confirmButton = { TextButton(onClick = { confirmExit = false; onBack() }) { Text(stringResource(R.string.pages_discard)) } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImagesStart(onPick: () -> Unit, onCamera: () -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_images_to_pdf)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Default.PhotoLibrary, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(R.string.images_pick_hint),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(vertical = 24.dp),
            )
            Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.images_pick)) }
            OutlinedButton(onClick = onCamera, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(stringResource(R.string.images_use_camera))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CropStep(crop: CropState, viewModel: ScanViewModel) {
    val bitmap by produceState<Bitmap?>(null, crop.raw) {
        value = withContext(Dispatchers.IO) {
            val opts = BitmapFactory.Options().apply { inSampleSize = if (maxOf(crop.width, crop.height) > 2400) 2 else 1 }
            BitmapFactory.decodeFile(crop.raw.absolutePath, opts)
        }
    }
    val currentCrop by rememberUpdatedState(crop)
    val handleRadius = with(LocalDensity.current) { 14.dp.toPx() }
    val touchRadius = with(LocalDensity.current) { 48.dp.toPx() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_crop_title)) },
                navigationIcon = {
                    IconButton(onClick = viewModel::cancelCrop) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).navigationBarsPadding().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = viewModel::rotateCrop) { Icon(Icons.Default.RotateRight, stringResource(R.string.pages_rotate)) }
                IconButton(onClick = viewModel::resetCrop) { Icon(Icons.Default.CropFree, stringResource(R.string.scan_full_frame)) }
                Spacer(Modifier.weight(1f))
                Button(onClick = viewModel::applyCrop) { Text(stringResource(R.string.scan_apply)) }
            }
        },
    ) { padding ->
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(padding).background(Color(0xFF202020)).padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val vw = constraints.maxWidth.toFloat()
            val vh = constraints.maxHeight.toFloat()
            val scale = minOf(vw / crop.width, vh / crop.height)
            val origin = Offset((vw - crop.width * scale) / 2, (vh - crop.height * scale) / 2)
            fun toScreen(p: Pt) = Offset(origin.x + p.x * scale, origin.y + p.y * scale)
            fun toImage(o: Offset) = Pt((o.x - origin.x) / scale, (o.y - origin.y) / scale)
            var dragged by remember { mutableIntStateOf(-1) }

            Box(Modifier.fillMaxSize()) {
                bitmap?.let {
                    val size = with(LocalDensity.current) { androidx.compose.ui.unit.DpSize((crop.width * scale).toDp(), (crop.height * scale).toDp()) }
                    Image(
                        it.asImageBitmap(),
                        null,
                        Modifier.offset { IntOffset(origin.x.roundToInt(), origin.y.roundToInt()) }.size(size),
                    )
                }
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(scale, origin) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    dragged = currentCrop.quad.corners.map { toScreen(it) }
                                        .withIndex()
                                        .filter { (it.value - start).getDistance() < touchRadius }
                                        .minByOrNull { (it.value - start).getDistance() }?.index ?: -1
                                },
                                onDrag = { change, _ ->
                                    if (dragged >= 0) {
                                        change.consume()
                                        val p = toImage(change.position)
                                        // Corners re-order while dragging; follow the moved point.
                                        viewModel.moveCorner(dragged, p)
                                        dragged = currentCrop.quad.corners.indices.minBy { distance(currentCrop.quad.corners[it], p) }
                                    }
                                },
                                onDragEnd = { dragged = -1 },
                                onDragCancel = { dragged = -1 },
                            )
                        },
                ) {
                    val pts = crop.quad.corners.map { toScreen(it) }
                    val outline = Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        pts.drop(1).forEach { lineTo(it.x, it.y) }
                        close()
                    }
                    val shade = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                        addPath(outline)
                    }
                    drawPath(shade, Color.Black.copy(alpha = 0.45f))
                    drawPath(outline, CropColor, style = Stroke(2.dp.toPx()))
                    pts.forEachIndexed { i, p ->
                        drawCircle(Color.White.copy(alpha = 0.9f), handleRadius, p)
                        drawCircle(CropColor, handleRadius, p, style = Stroke(3.dp.toPx()))
                        if (i == dragged) drawCircle(CropColor.copy(alpha = 0.3f), handleRadius * 2, p)
                    }
                }
            }
        }
    }
}

private val CropColor = Color(0xFF42A5F5)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewStep(state: ScanUiState, viewModel: ScanViewModel, snackbar: SnackbarHostState, onExit: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val savePdf = rememberCreateDocument("application/pdf") { uri ->
        uri?.let(viewModel::exportPdf)
    }
    val saveImages = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::exportImages)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_review_title, state.pages.size)) },
                navigationIcon = {
                    IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.scan_save_images)) },
                                onClick = { menu = false; saveImages.launch(null) },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainer).navigationBarsPadding()) {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ScanFilter.entries) { f ->
                        FilterChip(
                            selected = state.filter == f,
                            onClick = { viewModel.setFilter(f) },
                            label = { Text(stringResource(filterLabel(f))) },
                        )
                    }
                }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.scan_ocr)) },
                    supportingContent = { Text(stringResource(R.string.scan_ocr_hint)) },
                    trailingContent = { Switch(checked = state.recognize, onCheckedChange = viewModel::setRecognize) },
                    modifier = Modifier.clickable { viewModel.setRecognize(!state.recognize) },
                )
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = viewModel::showCamera, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.AddAPhoto, null)
                        Text(stringResource(R.string.scan_add_page), Modifier.padding(start = 8.dp))
                    }
                    Button(
                        onClick = { savePdf.launch("${viewModel.defaultName}.pdf") },
                        enabled = state.pages.isNotEmpty() && !state.busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.PictureAsPdf, null)
                        Text(stringResource(R.string.scan_save_pdf), Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(12.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            itemsIndexed(state.pages, key = { _, p -> p.id }) { index, page ->
                val preview by produceState<Bitmap?>(null, page.id, state.filter) {
                    value = viewModel.preview(page, state.filter)
                }
                Card(Modifier.padding(6.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(0.72f).background(Color.White), contentAlignment = Alignment.Center) {
                        preview?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
                            ?: CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Surface(
                            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                        ) {
                            Text("${index + 1}", color = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        IconButton(onClick = { viewModel.movePage(index, -1) }, enabled = index > 0) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.merge_move_up))
                        }
                        IconButton(onClick = { viewModel.recrop(index) }) { Icon(Icons.Default.Crop, stringResource(R.string.scan_crop_title)) }
                        IconButton(onClick = { viewModel.deletePage(index) }) { Icon(Icons.Default.Delete, stringResource(R.string.pages_delete)) }
                        IconButton(onClick = { viewModel.movePage(index, 1) }, enabled = index < state.pages.lastIndex) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.merge_move_down))
                        }
                    }
                }
            }
        }
    }
}

private fun filterLabel(f: ScanFilter) = when (f) {
    ScanFilter.COLOR -> R.string.filter_color
    ScanFilter.ENHANCED -> R.string.filter_enhanced
    ScanFilter.NO_SHADOWS -> R.string.filter_no_shadows
    ScanFilter.GRAYSCALE -> R.string.filter_gray
    ScanFilter.BLACK_WHITE -> R.string.filter_bw
}
