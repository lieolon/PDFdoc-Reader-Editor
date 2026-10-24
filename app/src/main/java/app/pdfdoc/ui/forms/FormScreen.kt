package app.pdfdoc.ui.forms

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pdfdoc.R
import app.pdfdoc.domain.form.CheckFormField
import app.pdfdoc.domain.form.ChoiceFormField
import app.pdfdoc.domain.form.FormField
import app.pdfdoc.domain.form.RadioFormField
import app.pdfdoc.domain.form.TextFormField
import app.pdfdoc.domain.form.UnsupportedFormField
import app.pdfdoc.ui.common.BusyOverlay
import app.pdfdoc.ui.common.MessageView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormScreen(
    onBack: () -> Unit,
    onOpenDocument: (Uri) -> Unit,
    viewModel: FormViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let(viewModel::saveAs)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is FormEvent.Message -> snackbar.showSnackbar(context.getString(event.text))
                is FormEvent.Open -> onOpenDocument(event.uri)
                FormEvent.Close -> onBack()
            }
        }
    }
    val tryExit = { if (state.dirty) confirmExit = true else onBack() }
    BackHandler(onBack = tryExit)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.form_title)) },
                navigationIcon = {
                    IconButton(onClick = tryExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = { viewModel.save() }, enabled = state.dirty && !state.busy) {
                        Icon(Icons.Default.Save, stringResource(R.string.save))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.save_as)) },
                                onClick = { menu = false; saveAs.launch(state.name) },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.failed -> MessageView(stringResource(R.string.error_open))
                state.fields.isEmpty() -> MessageView(stringResource(R.string.form_empty))
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                    var lastPage = -1
                    state.fields.forEach { field ->
                        if (field.page != lastPage) {
                            lastPage = field.page
                            item(key = "page-${field.page}") {
                                Text(
                                    stringResource(R.string.form_page, field.page + 1),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                )
                            }
                        }
                        item(key = field.name) {
                            FieldEditor(field, state.values[field.name].orEmpty()) { viewModel.setValue(field.name, it) }
                        }
                    }
                }
            }
            if (state.busy) BusyOverlay(stringResource(R.string.working))
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(stringResource(R.string.pages_unsaved_title)) },
            text = { Text(stringResource(R.string.pages_unsaved_text)) },
            confirmButton = {
                TextButton(onClick = { confirmExit = false; viewModel.save() }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false; onBack() }) { Text(stringResource(R.string.pages_discard)) }
            },
        )
    }
}

@Composable
private fun FieldEditor(field: FormField, value: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        when (field) {
            is TextFormField -> OutlinedTextField(
                value = value,
                onValueChange = { v -> onChange(field.maxLength?.let { v.take(it) } ?: v) },
                label = { Text(field.label) },
                singleLine = !field.multiline,
                minLines = if (field.multiline) 3 else 1,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                supportingText = field.maxLength?.let { max -> { Text("${value.length} / $max") } },
                modifier = Modifier.fillMaxWidth(),
            )
            is CheckFormField -> Row(
                Modifier.fillMaxWidth().clickable { onChange((!value.toBoolean()).toString()) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = value.toBoolean(), onCheckedChange = { onChange(it.toString()) })
                Text(field.label)
            }
            is RadioFormField -> {
                Text(field.label, style = MaterialTheme.typography.labelLarge)
                field.options.forEach { option ->
                    Row(Modifier.fillMaxWidth().clickable { onChange(option) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = value == option, onClick = { onChange(option) })
                        Text(option)
                    }
                }
            }
            is ChoiceFormField -> ChoiceEditor(field, value, onChange)
            is UnsupportedFormField -> {
                Text(field.label, style = MaterialTheme.typography.labelLarge)
                Text(
                    stringResource(R.string.form_unsupported),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceEditor(field: ChoiceFormField, value: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val display = field.options.firstOrNull { it.export == value }?.display ?: value
    Box {
        OutlinedTextField(
            value = display,
            onValueChange = { if (field.editable) onChange(it) },
            readOnly = !field.editable,
            label = { Text(field.label) },
            placeholder = { Text(stringResource(R.string.form_choose)) },
            trailingIcon = {
                IconButton(onClick = { expanded = true }) { Icon(Icons.Default.ArrowDropDown, stringResource(R.string.form_choose)) }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!field.editable) {
            Box(Modifier.matchParentSize().clickable { expanded = true })
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            field.options.forEach { option ->
                DropdownMenuItem(text = { Text(option.display) }, onClick = { expanded = false; onChange(option.export) })
            }
        }
    }
}
