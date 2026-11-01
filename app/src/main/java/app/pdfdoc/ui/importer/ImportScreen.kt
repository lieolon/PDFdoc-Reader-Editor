package app.pdfdoc.ui.importer

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.pdfdoc.R
import app.pdfdoc.core.IoDispatcher
import app.pdfdoc.data.convert.DocumentImporter
import app.pdfdoc.ui.navigation.ImportRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ImportState {
    data object Converting : ImportState
    data object Failed : ImportState
    data class Done(val pdf: Uri) : ImportState
}

@HiltViewModel
class ImportViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val importer: DocumentImporter,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val source: Uri = Uri.parse(savedStateHandle.toRoute<ImportRoute>().uri)

    private val _state = MutableStateFlow<ImportState>(ImportState.Converting)
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = runCatching {
                withContext(io) {
                    val format = importer.formatOf(source)
                    ImportState.Done(if (format == null) source else importer.import(source, format))
                }
            }.getOrDefault(ImportState.Failed)
        }
    }
}

/** Converts a non-PDF file to PDF, then hands it to the viewer. */
@Composable
fun ImportScreen(onBack: () -> Unit, onOpenDocument: (Uri) -> Unit, viewModel: ImportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) { (state as? ImportState.Done)?.let { onOpenDocument(it.pdf) } }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (state == ImportState.Failed) {
                Text(stringResource(R.string.import_failed), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onBack) { Text(stringResource(R.string.back)) }
            } else {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.import_converting), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
