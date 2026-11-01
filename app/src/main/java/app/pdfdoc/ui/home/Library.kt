package app.pdfdoc.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pdfdoc.R
import app.pdfdoc.data.db.CollectionEntity

sealed interface FolderDialog {
    /** [addDocument] is put into the new folder right away. */
    data class Create(val addDocument: Long?) : FolderDialog
    data class Manage(val folder: CollectionEntity) : FolderDialog
}

@Composable
fun FilterRow(state: HomeUiState, onFilter: (LibraryFilter) -> Unit, onNewFolder: () -> Unit, onManage: (Long) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(selected = state.filter == LibraryFilter.All, onClick = { onFilter(LibraryFilter.All) }, label = { Text(stringResource(R.string.comments_all)) })
        FilterChip(
            selected = state.filter == LibraryFilter.Favorites,
            onClick = { onFilter(LibraryFilter.Favorites) },
            label = { Text(stringResource(R.string.home_favorites)) },
            leadingIcon = { Icon(Icons.Default.Star, null) },
        )
        state.folders.forEach { folder ->
            val selected = (state.filter as? LibraryFilter.Folder)?.id == folder.id
            FilterChip(
                selected = selected,
                onClick = { onFilter(if (selected) LibraryFilter.All else LibraryFilter.Folder(folder.id)) },
                label = { Text(folder.name) },
                leadingIcon = { Icon(Icons.Default.Folder, null) },
                trailingIcon = if (selected) ({ Icon(Icons.Default.Edit, stringResource(R.string.folder_manage), Modifier.clickable { onManage(folder.id) }) }) else null,
            )
        }
        AssistChip(onClick = onNewFolder, label = { Text(stringResource(R.string.folder_new)) }, leadingIcon = { Icon(Icons.Default.Add, null) })
    }
}

@Composable
fun FolderDialogView(
    dialog: FolderDialog,
    onDismiss: () -> Unit,
    onCreate: (String, Long?) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var name by remember { mutableStateOf((dialog as? FolderDialog.Manage)?.folder?.name.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (dialog is FolderDialog.Create) R.string.folder_new else R.string.folder_manage)) },
        text = { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.folder_name)) }, singleLine = true) },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                when (dialog) {
                    is FolderDialog.Create -> onCreate(name, dialog.addDocument)
                    is FolderDialog.Manage -> onRename(dialog.folder.id, name)
                }
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            Row {
                if (dialog is FolderDialog.Manage) {
                    TextButton(onClick = { onDelete(dialog.folder.id) }) { Text(stringResource(R.string.folder_delete)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
fun MembershipDialog(
    folders: List<CollectionEntity>,
    member: Set<Long>,
    onToggle: (Long, Boolean) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_folders)) },
        text = {
            Column {
                if (folders.isEmpty()) Text(stringResource(R.string.folder_none))
                folders.forEach { f ->
                    val checked = f.id in member
                    Row(Modifier.fillMaxWidth().clickable { onToggle(f.id, !checked) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = { onToggle(f.id, it) })
                        Text(f.name)
                    }
                }
                TextButton(onClick = onNew) {
                    Icon(Icons.Default.Add, null)
                    Text(stringResource(R.string.folder_new), Modifier.padding(start = 6.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

