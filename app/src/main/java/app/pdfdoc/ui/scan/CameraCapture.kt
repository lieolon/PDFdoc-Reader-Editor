package app.pdfdoc.ui.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.pdfdoc.R

@Composable
fun CameraCapture(
    pageCount: Int,
    mode: ScanMode,
    autoCapture: Boolean,
    waitingForBack: Boolean,
    onMode: (ScanMode) -> Unit,
    onAutoCapture: (Boolean) -> Unit,
    onCaptured: (Bitmap) -> Unit,
    onImport: (List<Uri>) -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) onImport(uris)
    }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraPreview(
                onCaptured = onCaptured, pageCount = pageCount, onDone = onDone,
                onGallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                mode = mode, autoCapture = autoCapture, waitingForBack = waitingForBack, onMode = onMode, onAutoCapture = onAutoCapture,
            )
        } else {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.scan_camera_permission),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                if (asked) {
                    Button(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                        )
                    }) { Text(stringResource(R.string.scan_open_settings)) }
                } else {
                    Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) {
                        Text(stringResource(R.string.scan_allow_camera))
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text(stringResource(R.string.scan_from_gallery), color = Color.White) }
            }
        }
        IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White)
        }
    }
}

@Composable
private fun CameraPreview(
    onCaptured: (Bitmap) -> Unit,
    pageCount: Int,
    onDone: () -> Unit,
    onGallery: () -> Unit,
    mode: ScanMode,
    autoCapture: Boolean,
    waitingForBack: Boolean,
    onMode: (ScanMode) -> Unit,
    onAutoCapture: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
    }
    var flash by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    val currentAuto by androidx.compose.runtime.rememberUpdatedState(autoCapture)
    var steady by remember { mutableStateOf(0f) }
    fun capture() {
        if (capturing) return
        capturing = true
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = image.toUprightBitmap()
                    image.close()
                    capturing = false
                    onCaptured(bitmap)
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                }
            },
        )
    }
    // Automatic capture: shoot once the detected page outline stays still for a moment.
    val analysis = remember {
        androidx.camera.core.ImageAnalysis.Builder()
            .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
    }
    val stability = remember { SteadyPage() }
    DisposableEffect(analysis) {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        analysis.setAnalyzer(executor) { image ->
            val level = if (currentAuto && !capturing) runCatching { stability.update(image.toBitmap()) }.getOrDefault(0f) else 0f
            image.close()
            ContextCompat.getMainExecutor(context).execute {
                steady = level
                if (level >= 1f && currentAuto) {
                    stability.reset()
                    capture()
                }
            }
        }
        onDispose {
            analysis.clearAnalyzer()
            executor.shutdown()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            runCatching {
                provider.unbindAll()
                runCatching { provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis) }
                    .getOrElse { provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture) }
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { future.get().unbindAll() } }
    }
    LaunchedEffect(flash) { imageCapture.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val hint = when {
                waitingForBack -> R.string.scan_id_back
                mode == ScanMode.ID_CARD -> R.string.scan_id_front
                mode == ScanMode.BOOK -> R.string.scan_book_hint
                autoCapture -> R.string.scan_auto_hint
                else -> null
            }
            hint?.let {
                Text(
                    stringResource(it),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.55f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            if (autoCapture && steady > 0f) {
                androidx.compose.material3.LinearProgressIndicator(progress = { steady }, modifier = Modifier.padding(top = 6.dp).size(width = 160.dp, height = 4.dp))
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ScanMode.DOCUMENT to R.string.scan_mode_document, ScanMode.ID_CARD to R.string.scan_mode_id, ScanMode.BOOK to R.string.scan_mode_book).forEach { (m, label) ->
                    androidx.compose.material3.FilterChip(
                        selected = mode == m,
                        onClick = { onMode(m) },
                        label = { Text(stringResource(label), color = Color.White) },
                    )
                }
                androidx.compose.material3.FilterChip(
                    selected = autoCapture,
                    onClick = { onAutoCapture(!autoCapture) },
                    label = { Text(stringResource(R.string.scan_auto), color = Color.White) },
                )
            }
        }
        IconButton(
            onClick = { flash = !flash },
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
        ) {
            Icon(
                if (flash) Icons.Default.FlashOn else Icons.Default.FlashOff,
                stringResource(R.string.scan_flash),
                tint = Color.White,
            )
        }
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .navigationBarsPadding()
                .padding(vertical = 20.dp, horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onGallery) {
                Icon(Icons.Default.PhotoLibrary, stringResource(R.string.scan_from_gallery), tint = Color.White)
            }
            Box(
                Modifier
                    .size(72.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(8.dp)
                    .background(if (capturing) Color.Gray else Color.White, CircleShape)
                    .clickable(enabled = !capturing) { capture() },
            )
            TextButton(onClick = onDone, enabled = pageCount > 0) {
                Text(
                    if (pageCount > 0) stringResource(R.string.scan_done_count, pageCount) else stringResource(R.string.scan_done),
                    color = if (pageCount > 0) Color.White else Color.Gray,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

private fun ImageProxy.toUprightBitmap(): Bitmap {
    val bitmap = toBitmap()
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return bitmap
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
}

/**
 * Tracks the page outline across camera frames. Returns progress towards a steady shot:
 * 1 when the outline has stayed in place for several frames in a row.
 */
private class SteadyPage {
    private var last: List<app.pdfdoc.domain.edit.Pt>? = null
    private var count = 0

    fun reset() {
        last = null
        count = 0
    }

    fun update(frame: Bitmap): Float {
        val side = 200
        val k = side.toFloat() / maxOf(frame.width, frame.height)
        val small = Bitmap.createScaledBitmap(frame, (frame.width * k).toInt().coerceAtLeast(1), (frame.height * k).toInt().coerceAtLeast(1), true)
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        val quad = app.pdfdoc.domain.scan.EdgeDetector.detect(pixels, small.width, small.height)
        if (quad == null) {
            reset()
            return 0f
        }
        val corners = quad.corners.map { app.pdfdoc.domain.edit.Pt(it.x / small.width, it.y / small.height) }
        val previous = last
        last = corners
        val still = previous != null && corners.zip(previous).all { (a, b) -> app.pdfdoc.domain.edit.distance(a, b) < 0.015f }
        count = if (still) count + 1 else 0
        return (count / STEADY_FRAMES.toFloat()).coerceAtMost(1f)
    }

    private companion object {
        const val STEADY_FRAMES = 8
    }
}
