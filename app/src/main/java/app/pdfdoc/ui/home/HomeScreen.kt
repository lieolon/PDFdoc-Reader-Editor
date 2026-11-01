package app.pdfdoc.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.platform.LocalFocusManager
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.filled.PostAdd
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
import androidx.compose.ui.platform.LocalContext
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
    /** A file picked by the user: PDF, or another format to convert first. */
    onOpenFile: (Uri) -> Unit,
    onMerge: () -> Unit,
    onScan: () -> Unit,
    onImagesToPdf: () -> Unit,
    onSettings: () -> Unit,
    onBatch: () -> Unit,
    onTextToPdf: () -> Unit,
    /** A PDF picked to work on with page numbers, watermark, compression, password and export tools. */
    onTools: (Uri) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onOpenFile)
    }
    val toolsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.prepareForTools(it, onTools) }
    }
    var sortMenu by remember { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // While searching, the action cards step aside so the field and results fit above the keyboard.
    val searching = searchFocused || state.query.isNotEmpty()
    BackHandler(enabled = searching) {
        viewModel.onQueryChange("")
        focusManager.clearFocus()
    }
    var folderDialog by remember { mutableStateOf<FolderDialog?>(null) }
    var membershipFor by remember { mutableStateOf<PdfDocument?>(null) }

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
            if (!searching) {
                item {
                    Row(
                        Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        QuickAction(Icons.Default.FolderOpen, stringResource(R.string.home_open), Modifier.weight(1f)) {
                            openLauncher.launch(app.pdfdoc.data.convert.ImportFormats.MIME_TYPES)
                        }
                        QuickAction(Icons.Default.DocumentScanner, stringResource(R.string.home_scan), Modifier.weight(1f), onScan)
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        QuickAction(Icons.Default.AddPhotoAlternate, stringResource(R.string.home_images_to_pdf), Modifier.weight(1f), onImagesToPdf)
                        QuickAction(Icons.Default.MergeType, stringResource(R.string.home_merge), Modifier.weight(1f), onMerge)
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        QuickAction(Icons.Default.DynamicFeed, stringResource(R.string.batch_title), Modifier.weight(1f), onBatch)
                        QuickAction(Icons.Default.PostAdd, stringResource(R.string.text_to_pdf), Modifier.weight(1f), onTextToPdf)
                    }
                }
                item {
                    ToolsAction { toolsLauncher.launch(arrayOf("application/pdf")) }
                }
            }
            if (state.hasAnyDocuments) {
                // A stable key keeps the field and its focus when the cards above are hidden.
                item(key = "search") {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::onQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .onFocusChanged { searchFocused = it.isFocused },
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
                    FilterRow(
                        state = state,
                        onFilter = viewModel::setFilter,
                        onNewFolder = { folderDialog = FolderDialog.Create(null) },
                        onManage = { id -> state.folders.firstOrNull { it.id == id }?.let { folderDialog = FolderDialog.Manage(it) } },
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
                state.documents.isEmpty() && state.textHits.isEmpty() -> item {
                    MessageView(stringResource(R.string.home_nothing_found), modifier = Modifier.padding(top = 48.dp))
                }
                else -> items(state.documents, key = { it.id }) { doc ->
                    DocumentRow(
                        doc = doc,
                        onClick = { onOpenDocument(Uri.parse(doc.uri)) },
                        onRemove = { viewModel.remove(doc.id) },
                        onFavorite = { viewModel.toggleFavorite(doc) },
                        onFolders = { membershipFor = doc },
                    )
                }
            }
            if (state.textHits.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.home_found_in_text),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(state.textHits, key = { "t" + it.first.id }) { (doc, snippet) ->
                    ListItem(
                        headlineContent = { Text(doc.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(snippet, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) },
                        leadingContent = { Icon(Icons.Default.PictureAsPdf, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { onOpenDocument(Uri.parse(doc.uri)) },
                    )
                }
            }
        }
    }

    folderDialog?.let { d ->
        FolderDialogView(
            dialog = d,
            onDismiss = { folderDialog = null },
            onCreate = { name, docId -> folderDialog = null; viewModel.createFolder(name, docId) },
            onRename = { id, name -> folderDialog = null; viewModel.renameFolder(id, name) },
            onDelete = { id -> folderDialog = null; viewModel.deleteFolder(id) },
        )
    }
    membershipFor?.let { doc ->
        MembershipDialog(
            folders = state.folders,
            member = state.membership[doc.id].orEmpty(),
            onToggle = { folder, on -> viewModel.setInFolder(doc.id, folder, on) },
            onNew = { membershipFor = null; folderDialog = FolderDialog.Create(doc.id) },
            onDismiss = { membershipFor = null },
        )
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = modifier.fillMaxHeight()) {
        Column(
            Modifier.fillMaxSize().padding(vertical = 16.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ToolsAction(onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Build, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(stringResource(R.string.viewer_tools), style = MaterialTheme.typography.labelLarge)
                Text(
                    stringResource(R.string.home_tools_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun DocumentRow(doc: PdfDocument, onClick: () -> Unit, onRemove: () -> Unit, onFavorite: () -> Unit, onFolders: () -> Unit) {
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
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (doc.isFavorite) Icon(Icons.Default.Star, null, Modifier.size(16.dp).padding(end = 4.dp), tint = MaterialTheme.colorScheme.tertiary)
                Text(doc.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
        supportingContent = {
            val parts = listOfNotNull(
                formatSize(LocalContext.current, doc.sizeBytes).ifEmpty { null },
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
                        text = { Text(stringResource(if (doc.isFavorite) R.string.home_unfavorite else R.string.home_favorite)) },
                        onClick = { menu = false; onFavorite() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_folders)) },
                        onClick = { menu = false; onFolders() },
                    )
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
