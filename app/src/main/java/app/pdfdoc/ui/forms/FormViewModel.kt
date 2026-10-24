package app.pdfdoc.ui.forms

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.pdfdoc.R
import app.pdfdoc.core.IoDispatcher
import app.pdfdoc.data.files.DocumentEvents
import app.pdfdoc.data.files.FileStore
import app.pdfdoc.data.pdf.PdfForms
import app.pdfdoc.domain.form.CheckFormField
import app.pdfdoc.domain.form.ChoiceFormField
import app.pdfdoc.domain.form.FormField
import app.pdfdoc.domain.form.RadioFormField
import app.pdfdoc.domain.form.TextFormField
import app.pdfdoc.domain.repo.DocumentRepository
import app.pdfdoc.ui.navigation.FormRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FormUiState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val busy: Boolean = false,
    val name: String = "",
    val fields: List<FormField> = emptyList(),
    /** Current values keyed by field name; check boxes use "true"/"false". */
    val values: Map<String, String> = emptyMap(),
    val dirty: Boolean = false,
)

sealed interface FormEvent {
    data class Message(@StringRes val text: Int) : FormEvent
    data class Open(val uri: Uri) : FormEvent
    data object Close : FormEvent
}

@HiltViewModel
class FormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val files: FileStore,
    private val docs: app.pdfdoc.data.files.DocumentStore,
    private val forms: PdfForms,
    private val documents: DocumentRepository,
    private val docEvents: DocumentEvents,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val uri: Uri = Uri.parse(savedStateHandle.toRoute<FormRoute>().uri)

    private val _state = MutableStateFlow(FormUiState())
    val state: StateFlow<FormUiState> = _state.asStateFlow()

    private val _events = Channel<FormEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var working: File? = null

    init {
        viewModelScope.launch {
            runCatching {
                withContext(io) {
                    val name = files.query(uri).name
                    val work = docs.openWorkingCopy(uri)
                    working = work
                    name to forms.read(work)
                }
            }.onSuccess { (name, fields) ->
                _state.update { it.copy(loading = false, name = name, fields = fields, values = initialValues(fields)) }
            }.onFailure {
                _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    fun setValue(name: String, value: String) = _state.update {
        it.copy(values = it.values + (name to value), dirty = true)
    }

    fun save(closeAfter: Boolean = true) = write(uri) {
        docEvents.notifyChanged(uri.toString())
        _events.send(if (closeAfter) FormEvent.Close else FormEvent.Message(R.string.saved))
    }

    fun saveAs(target: Uri) = write(target) {
        _events.send(FormEvent.Open(target))
    }

    private fun write(target: Uri, after: suspend () -> Unit) {
        val source = working ?: return
        val values = state.value.values
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = runCatching {
                withContext(io) {
                    val out = files.newWorkFile()
                    forms.write(source, out, values)
                    docs.save(out, target, uri)
                    if (target != uri) runCatching { documents.recordOpened(target, out) }
                    out.delete()
                }
            }
            _state.update { it.copy(busy = false, dirty = if (result.isSuccess) false else it.dirty) }
            if (result.isSuccess) after() else _events.send(FormEvent.Message(R.string.error_save))
        }
    }

    override fun onCleared() {
        working?.delete()
    }

    private fun initialValues(fields: List<FormField>): Map<String, String> = fields.mapNotNull { f ->
        when (f) {
            is TextFormField -> f.name to f.value
            is CheckFormField -> f.name to f.checked.toString()
            is ChoiceFormField -> f.name to f.value.orEmpty()
            is RadioFormField -> f.name to f.value.orEmpty()
            else -> null
        }
    }.toMap()
}
