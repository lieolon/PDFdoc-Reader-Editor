package app.pdfdoc.ui.ask

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.pdfdoc.R
import app.pdfdoc.core.IoDispatcher
import app.pdfdoc.data.convert.TextExports
import app.pdfdoc.data.files.DocumentStore
import app.pdfdoc.data.files.FileStore
import app.pdfdoc.domain.text.Passage
import app.pdfdoc.domain.text.Summarizer
import app.pdfdoc.ui.common.MessageView
import app.pdfdoc.ui.navigation.AskRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AskDocument(val name: String, val uri: String, val summary: List<Passage>)

data class AskUiState(
    val loading: Boolean = true,
    val documents: List<AskDocument> = emptyList(),
    val question: String = "",
    val answers: List<Passage>? = null,
)

@HiltViewModel
class AskViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val files: FileStore,
    private val docs: DocumentStore,
    private val exports: TextExports,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val uris = savedStateHandle.toRoute<AskRoute>().uris
    private val _state = MutableStateFlow(AskUiState())
    val state = _state.asStateFlow()
    private var passages: List<Passage> = emptyList()

    init {
        viewModelScope.launch {
            val loaded = withContext(io) {
                uris.mapIndexedNotNull { d, raw ->
                    runCatching {
                        val uri = Uri.parse(raw)
                        val work = docs.openWorkingCopy(uri)
                        try {
                            val pages = exports.paragraphs(work)
                            val list = pages.flatMapIndexed { p, paragraphs ->
                                paragraphs.flatMap { Summarizer.sentences(it) }.map { Passage(d, p, it) }
                            }
                            Triple(files.query(uri).name, raw, list)
                        } finally {
                            work.delete()
                        }
                    }.getOrNull()
                }
            }
            passages = loaded.flatMap { it.third }
            _state.update {
                it.copy(
                    loading = false,
                    documents = loaded.map { (name, uri, list) -> AskDocument(name, uri, Summarizer.summary(list, if (loaded.size > 1) 3 else 6)) },
                )
            }
        }
    }

    fun setQuestion(q: String) = _state.update { it.copy(question = q) }

    fun ask() {
        val q = state.value.question
        viewModelScope.launch {
            val found = withContext(io) { Summarizer.answer(q, passages, count = 4) }
            _state.update { it.copy(answers = found) }
        }
    }
}

/** Summary of one or several documents and answers to questions about them, found in their text offline. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskScreen(onBack: () -> Unit, onOpen: (String, Int) -> Unit, viewModel: AskViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ask_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    if (state.documents.isNotEmpty()) IconButton(onClick = {
                        val text = state.documents.joinToString("\n\n") { d -> d.name + "\n" + d.summary.joinToString("\n") { "• " + it.text } }
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
                    }) { Icon(Icons.Default.Share, stringResource(R.string.viewer_share)) }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.documents.all { it.summary.isEmpty() } -> MessageView(stringResource(R.string.reader_no_text), modifier = Modifier.padding(padding))
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                item {
                    OutlinedTextField(
                        value = state.question,
                        onValueChange = viewModel::setQuestion,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.ask_hint)) },
                        trailingIcon = {
                            IconButton(onClick = viewModel::ask, enabled = state.question.isNotBlank()) {
                                Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.ask_find))
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { viewModel.ask() }),
                        singleLine = true,
                    )
                }
                state.answers?.let { answers ->
                    item { Heading(R.string.ask_answers) }
                    if (answers.isEmpty()) item { Text(stringResource(R.string.ask_nothing), style = MaterialTheme.typography.bodyMedium) }
                    items(answers) { p -> PassageCard(p, state, highlight = Summarizer.terms(state.question).toSet(), onOpen) }
                }
                state.documents.forEach { d ->
                    item { Heading(R.string.ask_summary, d.name.takeIf { state.documents.size > 1 }) }
                    items(d.summary) { p -> PassageCard(p, state, highlight = emptySet(), onOpen) }
                }
                item {
                    Text(
                        stringResource(R.string.ask_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Heading(title: Int, subtitle: String? = null) {
    Column(Modifier.padding(top = 20.dp, bottom = 8.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun PassageCard(p: Passage, state: AskUiState, highlight: Set<String>, onOpen: (String, Int) -> Unit) {
    val doc = state.documents.getOrNull(p.document) ?: return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpen(doc.uri, p.page) },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                buildAnnotatedString {
                    // Bold the words of the question, matched by stem like the search itself.
                    Regex("""[\p{L}\p{N}]+|[^\p{L}\p{N}]+""").findAll(p.text).forEach { m ->
                        val stem = Summarizer.terms(m.value).firstOrNull()
                        if (stem != null && stem in highlight) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.value) } else append(m.value)
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row {
                Text(
                    listOfNotNull(doc.name.takeIf { state.documents.size > 1 }, stringResource(R.string.form_page, p.page + 1)).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
