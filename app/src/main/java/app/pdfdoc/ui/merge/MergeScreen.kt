package app.pdfdoc.ui.merge

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pdfdoc.R
import app.pdfdoc.ui.common.BusyOverlay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeScreen(
    onBack: () -> Unit,
    onMerged: (Uri) -> Unit,
    viewModel: MergeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.add(uris)
    }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let(viewModel::mergeInto)
    }
    val defaultName = stringResource(R.string.merge_default_name)

    LaunchedEffect(Unit) { viewModel.merged.collect(onMerged) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.merge_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(16.dp)) {
                OutlinedButton(onClick = { pick.launch(arrayOf("application/pdf")) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, null)
                    Text(stringResource(R.string.merge_add), Modifier.padding(start = 8.dp))
                }
                Button(
                    onClick = { create.launch(defaultName) },
                    enabled = state.items.size >= 2 && !state.busy,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                ) {
                    Icon(Icons.Default.MergeType, null)
                    Text(stringResource(R.string.merge_run), Modifier.padding(start = 8.dp))
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.merge_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.failed) {
                            Text(
                                stringResource(R.string.error_save),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
                itemsIndexed(state.items, key = { i, item -> "$i:${item.uri}" }) { index, item ->
                    ListItem(
                        headlineContent = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Text("${index + 1}.", style = MaterialTheme.typography.titleMedium) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { viewModel.move(index, -1) }, enabled = index > 0) {
                                    Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.merge_move_up))
                                }
                                IconButton(
                                    onClick = { viewModel.move(index, 1) },
                                    enabled = index < state.items.lastIndex,
                                ) {
                                    Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.merge_move_down))
                                }
                                IconButton(onClick = { viewModel.remove(index) }) {
                                    Icon(Icons.Default.Close, stringResource(R.string.merge_remove))
                                }
                            }
                        },
                    )
                }
            }
            if (state.busy) BusyOverlay(stringResource(R.string.working))
        }
    }
}
