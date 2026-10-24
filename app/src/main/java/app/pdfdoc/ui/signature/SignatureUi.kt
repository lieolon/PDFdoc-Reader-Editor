package app.pdfdoc.ui.signature

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pdfdoc.R
import app.pdfdoc.data.files.Images

/** Bottom sheet with saved signatures; picking one calls [onPick] with its id. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignatureSheet(
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
    onError: () -> Unit,
    viewModel: SignatureViewModel = hiltViewModel(),
) {
    val ids by viewModel.ids.collectAsStateWithLifecycle()
    var drawing by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.import(it) { id -> if (id != null) onPick(id) else onError() } }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.signature_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            if (ids.isEmpty()) {
                Text(
                    stringResource(R.string.signature_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(ids, key = { it }) { id ->
                        SignatureCard(id, viewModel, onClick = { onPick(id) }, onDelete = { viewModel.delete(id) })
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { drawing = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Draw, null)
                    Text(stringResource(R.string.signature_draw), Modifier.padding(start = 8.dp))
                }
                OutlinedButton(
                    onClick = { importLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Image, null)
                    Text(stringResource(R.string.signature_import), Modifier.padding(start = 8.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (drawing) {
        SignaturePadDialog(
            onDismiss = { drawing = false },
            onDone = { bitmap ->
                drawing = false
                viewModel.save(bitmap, onPick)
            },
        )
    }
}

@Composable
private fun SignatureCard(id: Long, viewModel: SignatureViewModel, onClick: () -> Unit, onDelete: () -> Unit) {
    val bitmap by produceState<Bitmap?>(null, id) { value = viewModel.load(id) }
    Box {
        Box(
            Modifier
                .size(width = 160.dp, height = 90.dp)
                .background(Color.White, RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.signature_title), Modifier.fillMaxSize()) }
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.align(Alignment.TopEnd).size(28.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
        ) {
            Icon(Icons.Default.Close, stringResource(R.string.signature_delete), Modifier.size(16.dp))
        }
    }
}

private val InkColors = listOf(Color(0xFF0D1B5E), Color(0xFF212121), Color(0xFF1565C0))

/** Full-screen pad for drawing a signature with a finger or stylus. Produces a trimmed transparent PNG. */
@Composable
fun SignaturePadDialog(onDismiss: () -> Unit, onDone: (Bitmap) -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var color by remember { mutableStateOf(InkColors[0]) }
    var padSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    val strokePx = with(LocalDensity.current) { 3.dp.toPx() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text(stringResource(R.string.signature_draw_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.signature_draw_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                        .pointerInput(Unit) {
                            // Raw events so the stroke starts at the first touch, not after the drag slop.
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                current = listOf(down.position)
                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.pressed) {
                                        current = current + change.position
                                        change.consume()
                                    }
                                } while (change.pressed)
                                strokes += if (current.size > 1) current else current + (current[0] + Offset(0.5f, 0.5f))
                                current = emptyList()
                            }
                        },
                ) {
                    padSize = size
                    (strokes + listOf(current)).filter { it.size > 1 }.forEach { s ->
                        val path = Path().apply {
                            moveTo(s[0].x, s[0].y)
                            s.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                        drawPath(path, color, style = Stroke(strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    drawLine(Color.LightGray, Offset(24f, size.height * 0.75f), Offset(size.width - 24f, size.height * 0.75f), 2f)
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InkColors.forEach { c ->
                        Box(
                            Modifier
                                .padding(end = 8.dp)
                                .size(32.dp)
                                .background(c, CircleShape)
                                .border(if (c == color) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                .clickable { color = c },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { strokes.clear() }) { Text(stringResource(R.string.signature_clear)) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Button(
                        onClick = { renderSignature(strokes, padSize, color.toArgb(), strokePx)?.let(onDone) },
                        enabled = strokes.isNotEmpty(),
                    ) { Text(stringResource(R.string.save)) }
                }
            }
        }
    }
}

private fun renderSignature(
    strokes: List<List<Offset>>,
    size: androidx.compose.ui.geometry.Size,
    color: Int,
    strokePx: Float,
): Bitmap? {
    if (strokes.isEmpty() || size.width <= 0f) return null
    val bitmap = Bitmap.createBitmap(size.width.toInt(), size.height.toInt(), Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    strokes.forEach { s ->
        val path = android.graphics.Path().apply {
            moveTo(s[0].x, s[0].y)
            s.drop(1).forEach { lineTo(it.x, it.y) }
        }
        canvas.drawPath(path, paint)
    }
    return Images.trim(bitmap)
}
