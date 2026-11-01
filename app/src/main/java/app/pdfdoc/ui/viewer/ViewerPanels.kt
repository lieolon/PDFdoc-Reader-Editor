package app.pdfdoc.ui.viewer

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pdfdoc.R
import app.pdfdoc.data.pdf.Comment
import app.pdfdoc.data.pdf.CommentKind
import app.pdfdoc.ui.common.MessageView
import java.text.DateFormat
import kotlin.math.roundToInt

fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("PDFdoc", text))
    Toast.makeText(context, R.string.select_copied, Toast.LENGTH_SHORT).show()
}

/** Opens a link from a document in another app; PDFdoc itself has no internet access. */
fun openExternalLink(context: Context, link: String) {
    val uri = Uri.parse(if (link.contains("://") || link.startsWith("mailto:") || link.startsWith("tel:")) link else "https://$link")
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(context, R.string.no_app_for_action, Toast.LENGTH_SHORT).show() }
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.viewer_share)))
}

/** Hands the text to translators and other apps that handle "process text". */
private fun processText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
        .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
    try {
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.select_translate)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_app_for_action, Toast.LENGTH_SHORT).show()
    }
}

private fun webSearch(context: Context, text: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, text))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_app_for_action, Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun SelectionBar(text: String, onDismiss: () -> Unit, onRead: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Surface(
        modifier = modifier.padding(horizontal = 12.dp),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            BarAction(Icons.Default.ContentCopy, R.string.select_copy) { copyToClipboard(context, text); onDismiss() }
            BarAction(Icons.Default.Share, R.string.viewer_share) { shareText(context, text) }
            BarAction(Icons.Default.Translate, R.string.select_translate) { processText(context, text) }
            BarAction(Icons.Default.Search, R.string.select_web_search) { webSearch(context, text) }
            BarAction(Icons.Default.RecordVoiceOver, R.string.read_aloud) { onRead() }
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
        }
    }
}

@Composable
private fun BarAction(icon: ImageVector, label: Int, onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null)
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
fun ReadAloudBar(
    state: ReadAloudState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSkip: (Int) -> Unit,
    onRate: (Float) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.padding(horizontal = 12.dp), shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onSkip(-1) }) { Icon(Icons.Default.FastRewind, stringResource(R.string.read_aloud_previous)) }
            if (state.playing) {
                IconButton(onClick = onPause) { Icon(Icons.Default.Pause, stringResource(R.string.read_aloud_pause)) }
            } else {
                IconButton(onClick = onResume) { Icon(Icons.Default.PlayArrow, stringResource(R.string.read_aloud_play)) }
            }
            IconButton(onClick = { onSkip(1) }) { Icon(Icons.Default.FastForward, stringResource(R.string.read_aloud_next)) }
            val rates = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
            TextButton(onClick = { onRate(rates[(rates.indexOf(state.rate) + 1) % rates.size]) }) {
                Text("×" + if (state.rate == state.rate.roundToInt().toFloat()) "${state.rate.roundToInt()}" else "${state.rate}")
            }
            Text(stringResource(R.string.form_page, state.page + 1), style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = onStop) { Icon(Icons.Default.Stop, stringResource(R.string.read_aloud_stop)) }
        }
    }
}

private enum class CommentFilter(val label: Int, val kinds: Set<CommentKind>?) {
    ALL(R.string.comments_all, null),
    NOTES(R.string.comments_notes, setOf(CommentKind.NOTE, CommentKind.TEXT)),
    MARKUP(R.string.comments_markup, setOf(CommentKind.MARKUP)),
    DRAWINGS(R.string.comments_drawings, setOf(CommentKind.DRAWING, CommentKind.STAMP, CommentKind.OTHER)),
}

/** Items of the "Comments" tab: every annotation of the document with its replies. */
fun LazyListScope.commentItems(
    comments: List<Comment>?,
    filter: Int,
    onFilter: (Int) -> Unit,
    onOpen: (Comment) -> Unit,
    onReply: (Comment) -> Unit,
    onDelete: (Comment) -> Unit,
) {
    item {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CommentFilter.entries.forEachIndexed { i, f ->
                FilterChip(selected = filter == i, onClick = { onFilter(i) }, label = { Text(stringResource(f.label)) })
            }
        }
    }
    when {
        comments == null -> item { CircularProgressIndicator(Modifier.padding(24.dp)) }
        else -> {
            val kinds = CommentFilter.entries[filter].kinds
            val shown = comments.filter { kinds == null || it.kind in kinds }
            if (shown.isEmpty()) item { MessageView(stringResource(R.string.comments_empty), modifier = Modifier.heightIn(max = 200.dp)) }
            shown.forEach { c ->
                item(key = "${c.page}:${c.index}") {
                    ListItem(
                        overlineContent = {
                            Text(
                                listOfNotNull(
                                    stringResource(R.string.form_page, c.page + 1),
                                    stringResource(kindLabel(c.kind)),
                                    c.author,
                                    c.date?.let { DateFormat.getDateInstance(DateFormat.SHORT).format(it.time) },
                                ).joinToString(" · "),
                            )
                        },
                        headlineContent = {
                            Column {
                                Text(c.text.ifEmpty { "—" }, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                c.replies.forEach { r ->
                                    Text(
                                        "↳ " + listOfNotNull(r.author, r.text).joinToString(": "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { onReply(c) }) { Icon(Icons.AutoMirrored.Filled.Reply, stringResource(R.string.comments_reply)) }
                                IconButton(onClick = { onDelete(c) }) { Icon(Icons.Default.Delete, stringResource(R.string.pages_delete)) }
                            }
                        },
                        modifier = Modifier.clickable { onOpen(c) },
                    )
                }
            }
        }
    }
}

private fun kindLabel(kind: CommentKind) = when (kind) {
    CommentKind.NOTE -> R.string.comment_kind_note
    CommentKind.TEXT -> R.string.comment_kind_text
    CommentKind.MARKUP -> R.string.comment_kind_markup
    CommentKind.DRAWING -> R.string.comment_kind_drawing
    CommentKind.STAMP -> R.string.comment_kind_stamp
    CommentKind.OTHER -> R.string.comment_kind_other
}

@Composable
fun ReplyDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.comments_reply)) },
        text = { OutlinedTextField(text, { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onSend(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String?, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Opens [uri] in a second window next to this one (split screen on phones, a new window on tablets). */
fun openSideBySide(context: Context, uri: Uri) {
    val intent = Intent(context, app.pdfdoc.SecondWindowActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/pdf")
        .addFlags(
            Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, R.string.no_app_for_action, Toast.LENGTH_SHORT).show() }
}

@Composable
fun KeepScreenOnEffect(on: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
fun TabsRow(tabs: List<OpenTab>, current: Uri, onSelect: (Uri) -> Unit, onClose: (Uri) -> Unit) {
    val selected = tabs.indexOfFirst { it.uri == current }.coerceAtLeast(0)
    androidx.compose.material3.ScrollableTabRow(
        selectedTabIndex = selected,
        edgePadding = 8.dp,
        // Tab positions can lag one frame behind a newly opened tab; the default indicator would index past them.
        indicator = { positions ->
            positions.getOrNull(selected)?.let {
                androidx.compose.material3.TabRowDefaults.SecondaryIndicator(
                    with(androidx.compose.material3.TabRowDefaults) { Modifier.tabIndicatorOffset(it) },
                )
            }
        },
    ) {
        tabs.forEachIndexed { i, tab ->
            androidx.compose.material3.Tab(
                selected = i == selected,
                onClick = { onSelect(tab.uri) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tab.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                        Icon(
                            Icons.Default.Close,
                            stringResource(R.string.tab_close),
                            modifier = Modifier.padding(start = 6.dp).size(16.dp).clickable { onClose(tab.uri) },
                        )
                    }
                },
            )
        }
    }
}

@Composable
fun AutoScrollBar(speed: Float, onSpeed: (Float) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.padding(horizontal = 12.dp), shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onSpeed((speed / 1.5f).coerceAtLeast(10f)) }) { Icon(Icons.Default.FastRewind, stringResource(R.string.auto_scroll_slower)) }
            Text(stringResource(R.string.auto_scroll), style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = { onSpeed((speed * 1.5f).coerceAtMost(400f)) }) { Icon(Icons.Default.FastForward, stringResource(R.string.auto_scroll_faster)) }
            IconButton(onClick = { onSpeed(0f) }) { Icon(Icons.Default.Stop, stringResource(R.string.read_aloud_stop)) }
        }
    }
}
