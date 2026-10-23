package app.pdfdoc.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pdfdoc.R
import app.pdfdoc.domain.model.PdfDocument
import app.pdfdoc.domain.model.SortOrder
import app.pdfdoc.ui.common.MessageView
import app.pdfdoc.ui.common.formatDate
import app.pdfdoc.ui.common.formatSize
import app.pdfdoc.ui.common.rememberFileBitmap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenDocument: (Uri) -> Unit,
    onMerge: () -> Unit,
    onSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onOpenDocument)
    }
    var sortMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.home_sort))
                        }
                        SortMenu(sortMenu, state.sortOrder, onDismiss = { sortMenu = false }) {
                            viewModel.onSortChange(it)
                            sortMenu = false
                        }
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, stringResource(R.string.home_settings))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    QuickAction(Icons.Default.FolderOpen, stringResource(R.string.home_open), Modifier.weight(1f)) {
                        openLauncher.launch(arrayOf("application/pdf"))
                    }
                    QuickAction(Icons.Default.MergeType, stringResource(R.string.home_merge), Modifier.weight(1f), onMerge)
                }
            }
            if (state.hasAnyDocuments) {
                item {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::onQueryChange,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text(stringResource(R.string.home_search_hint)) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (state.query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.onQueryChange("") }) {
                                    Icon(Icons.Default.Clear, stringResource(R.string.cancel))
                                }
                            }
                        },
                        singleLine = true,
                    )
                }
                item {
                    Text(
                        stringResource(R.string.home_recent),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            when {
                !state.hasAnyDocuments -> item {
                    MessageView(
                        stringResource(R.string.home_empty_title),
                        stringResource(R.string.home_empty_text),
                        Modifier.padding(top = 48.dp),
                    )
                }
                state.documents.isEmpty() -> item {
                    MessageView(stringResource(R.string.home_nothing_found), modifier = Modifier.padding(top = 48.dp))
                }
                else -> items(state.documents, key = { it.id }) { doc ->
                    DocumentRow(
                        doc = doc,
                        onClick = { onOpenDocument(Uri.parse(doc.uri)) },
                        onRemove = { viewModel.remove(doc.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = modifier) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun SortMenu(expanded: Boolean, current: SortOrder, onDismiss: () -> Unit, onSelect: (SortOrder) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        listOf(
            SortOrder.DATE to R.string.sort_date,
            SortOrder.NAME to R.string.sort_name,
            SortOrder.SIZE to R.string.sort_size,
        ).forEach { (order, label) ->
            DropdownMenuItem(
                text = { Text(stringResource(label)) },
                leadingIcon = { RadioButton(selected = order == current, onClick = null) },
                onClick = { onSelect(order) },
            )
        }
    }
}

@Composable
private fun DocumentRow(doc: PdfDocument, onClick: () -> Unit, onRemove: () -> Unit) {
    val thumb by rememberFileBitmap(doc.thumbnailPath)
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Box(
                Modifier
                    .size(width = 48.dp, height = 64.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = thumb
                if (bitmap != null) {
                    Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(Icons.Default.PictureAsPdf, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
        headlineContent = { Text(doc.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val parts = listOfNotNull(
                formatSize(doc.sizeBytes).ifEmpty { null },
                stringResource(R.string.pages_count, doc.pageCount),
                formatDate(doc.lastOpenedAt),
            )
            Text(parts.joinToString(" · "))
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_remove_from_list)) },
                        onClick = {
                            menu = false
                            onRemove()
                        },
                    )
                }
            }
        },
    )
}
