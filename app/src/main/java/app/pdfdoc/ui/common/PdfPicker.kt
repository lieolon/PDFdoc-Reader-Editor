package app.pdfdoc.ui.common

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.pdfdoc.R
import app.pdfdoc.domain.model.PdfDocument
import app.pdfdoc.domain.repo.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class RecentPdfsViewModel @Inject constructor(documents: DocumentRepository) : ViewModel() {
    val recent = documents.observeRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/**
 * Chooses PDFs for a multi-file tool: recent library files first, the system file picker only on request.
 * The system picker has no cancel button of its own, so the user comes back here and can always close the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfPickerSheet(
    onDismiss: () -> Unit,
    onPicked: (List<Uri>) -> Unit,
    viewModel: RecentPdfsViewModel = hiltViewModel(),
) {
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val chosen = remember { mutableStateListOf<String>() }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onPicked(uris)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                stringResource(R.string.picker_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            if (recent.isEmpty()) {
                Text(
                    stringResource(R.string.picker_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            } else {
                Text(
                    stringResource(R.string.home_recent),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(recent, key = { it.id }) { doc ->
                        val checked = doc.uri in chosen
                        RecentRow(doc, checked) { if (checked) chosen.remove(doc.uri) else chosen.add(doc.uri) }
                    }
                }
            }
            TextButton(
                onClick = { files.launch(arrayOf("application/pdf")) },
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                Icon(Icons.Default.FolderOpen, null)
                Text(stringResource(R.string.picker_browse), Modifier.padding(start = 8.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cancel)) }
                Button(
                    onClick = { onPicked(chosen.map(Uri::parse)) },
                    enabled = chosen.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.picker_add, chosen.size))
                }
            }
        }
    }
}

@Composable
private fun RecentRow(doc: PdfDocument, checked: Boolean, onToggle: () -> Unit) {
    val thumb by rememberFileBitmap(doc.thumbnailPath)
    ListItem(
        headlineContent = { Text(doc.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(stringResource(R.string.pages_count, doc.pageCount)) },
        leadingContent = {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                val bitmap = thumb
                if (bitmap != null) {
                    Image(bitmap.asImageBitmap(), null, Modifier.size(40.dp), contentScale = ContentScale.Fit)
                } else {
                    Icon(Icons.Default.PictureAsPdf, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
        trailingContent = { Checkbox(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onToggle),
    )
}
