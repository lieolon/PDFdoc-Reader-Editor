package app.pdfdoc.ui.tools

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pdfdoc.R
import app.pdfdoc.data.pdf.DocProperties
import app.pdfdoc.data.pdf.OutlineEntry
import app.pdfdoc.data.pdf.PaperSize
import app.pdfdoc.data.pdf.SanitizeOptions
import app.pdfdoc.data.sign.CertificateInfo
import app.pdfdoc.ui.common.formatSize
import app.pdfdoc.ui.common.rememberCreateDocument
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MM = 72f / 25.4f

@Composable
private fun CheckRow(checked: Boolean, label: Int, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(stringResource(label))
    }
}

@Composable
private fun Note(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
}

@Composable
internal fun CropOptions(save: SaveActions) {
    var auto by remember { mutableStateOf(true) }
    val values = remember { mutableStateListOf("10", "10", "10", "10") }
    SheetTitle(R.string.tool_crop)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = auto, onClick = { auto = true }, label = { Text(stringResource(R.string.crop_auto)) })
        FilterChip(selected = !auto, onClick = { auto = false }, label = { Text(stringResource(R.string.crop_manual)) })
    }
    if (auto) {
        Note(R.string.crop_auto_hint)
    } else {
        Label(R.string.crop_margins)
        listOf(R.string.crop_top, R.string.crop_right, R.string.crop_bottom, R.string.crop_left).forEachIndexed { i, label ->
            OutlinedTextField(
                values[i], { v -> values[i] = v.filter { it.isDigit() || it == '.' || it == ',' }.take(5) },
                label = { Text(stringResource(label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
    val mm = values.map { it.replace(',', '.').toFloatOrNull() }
    val job = if (auto) DocumentJob.AutoCrop else mm.takeIf { m -> m.all { it != null } }?.let { m ->
        DocumentJob.CropMargins(m[0]!! * MM, m[1]!! * MM, m[2]!! * MM, m[3]!! * MM)
    }
    SaveButtons(job != null, onCopy = { job?.let { save.copy(it, "cropped") } }, onReplace = { job?.let(save.replace) })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ResizeOptions(save: SaveActions) {
    var paper by remember { mutableStateOf(PaperSize.A4) }
    SheetTitle(R.string.tool_resize)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PaperSize.entries.forEach { p ->
            FilterChip(selected = paper == p, onClick = { paper = p }, label = { Text(p.name.lowercase().replaceFirstChar(Char::uppercase)) })
        }
    }
    Note(R.string.resize_hint)
    val job = DocumentJob.Resize(paper)
    SaveButtons(true, onCopy = { save.copy(job, paper.name) }, onReplace = { save.replace(job) })
}

@Composable
internal fun NUpOptions(save: SaveActions) {
    var perSheet by remember { mutableStateOf(2) }
    var paper by remember { mutableStateOf(PaperSize.A4) }
    SheetTitle(R.string.tool_nup)
    Label(R.string.tool_nup_count)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(2, 4, 6, 9).forEach { n -> FilterChip(selected = perSheet == n, onClick = { perSheet = n }, label = { Text("$n") }) }
    }
    Label(R.string.tool_nup_paper)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PaperSize.entries.forEach { p ->
            FilterChip(selected = paper == p, onClick = { paper = p }, label = { Text(p.name.lowercase().replaceFirstChar(Char::uppercase)) })
        }
    }
    Note(R.string.tool_nup_hint)
    val job = DocumentJob.NUp(perSheet, paper)
    SaveButtons(true, onCopy = { save.copy(job, "$perSheet-up") }, onReplace = { save.replace(job) })
}

@Composable
internal fun SimpleJobOptions(title: Int, note: Int, job: DocumentJob, suffix: String, save: SaveActions) {
    SheetTitle(title)
    Text(stringResource(note), style = MaterialTheme.typography.bodyMedium)
    SaveButtons(true, onCopy = { save.copy(job, suffix) }, onReplace = { save.replace(job) })
}

@Composable
internal fun PropertiesOptions(viewModel: ToolsViewModel, save: SaveActions) {
    val loaded by produceState<DocProperties?>(null) { value = viewModel.properties() }
    SheetTitle(R.string.tool_properties)
    val p = loaded
    if (p == null) {
        CircularProgressIndicator(Modifier.padding(24.dp))
        return
    }
    var title by remember(p) { mutableStateOf(p.title) }
    var author by remember(p) { mutableStateOf(p.author) }
    var subject by remember(p) { mutableStateOf(p.subject) }
    var keywords by remember(p) { mutableStateOf(p.keywords) }
    listOf(
        Triple(title, { v: String -> title = v }, R.string.prop_title),
        Triple(author, { v: String -> author = v }, R.string.prop_author),
        Triple(subject, { v: String -> subject = v }, R.string.prop_subject),
        Triple(keywords, { v: String -> keywords = v }, R.string.prop_keywords),
    ).forEach { (value, set, label) ->
        OutlinedTextField(value, set, label = { Text(stringResource(label)) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
    val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    Column(Modifier.padding(top = 12.dp)) {
        listOfNotNull(
            stringResource(R.string.prop_pages) to p.pageCount.toString(),
            stringResource(R.string.prop_version) to p.version.toString(),
            p.creator.takeIf { it.isNotBlank() }?.let { stringResource(R.string.prop_creator) to it },
            p.producer.takeIf { it.isNotBlank() }?.let { stringResource(R.string.prop_producer) to it },
            p.created?.let { stringResource(R.string.prop_created) to format.format(it.time) },
            p.modified?.let { stringResource(R.string.prop_modified) to format.format(it.time) },
        ).forEach { (k, v) ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(k, Modifier.width(130.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(v, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    val job = DocumentJob.Properties(p.copy(title = title, author = author, subject = subject, keywords = keywords))
    SaveButtons(true, onCopy = { save.copy(job, "info") }, onReplace = { save.replace(job) })
}

@Composable
internal fun OutlineOptions(viewModel: ToolsViewModel, pageCount: Int, save: SaveActions) {
    val entries = remember { mutableStateListOf<OutlineEntry>() }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        entries.addAll(viewModel.outline())
        loaded = true
    }
    var editing by remember { mutableStateOf<Int?>(null) }
    SheetTitle(R.string.tool_outline)
    if (!loaded) {
        CircularProgressIndicator(Modifier.padding(24.dp))
        return
    }
    if (entries.isEmpty()) Note(R.string.outline_empty)
    entries.forEachIndexed { i, e ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = (e.level * 14).dp)) {
            Column(Modifier.weight(1f).clickable { editing = i }.padding(vertical = 6.dp)) {
                Text(e.title.ifBlank { "—" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.form_page, e.page + 1), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { if (e.level > 0) entries[i] = e.copy(level = e.level - 1) }, enabled = e.level > 0) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.outline_outdent))
            }
            IconButton(onClick = { entries[i] = e.copy(level = e.level + 1) }, enabled = i > 0 && e.level <= entries[i - 1].level) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.outline_indent))
            }
            IconButton(onClick = { if (i > 0) entries.add(i - 1, entries.removeAt(i)) }, enabled = i > 0) {
                Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.outline_up))
            }
            IconButton(onClick = { if (i < entries.lastIndex) entries.add(i + 1, entries.removeAt(i)) }, enabled = i < entries.lastIndex) {
                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.outline_down))
            }
            IconButton(onClick = { entries.removeAt(i) }) { Icon(Icons.Default.Delete, stringResource(R.string.pages_delete)) }
        }
    }
    OutlinedButton(onClick = { editing = -1 }, modifier = Modifier.padding(top = 8.dp)) {
        Icon(Icons.Default.Add, null)
        Text(stringResource(R.string.outline_add), Modifier.padding(start = 6.dp))
    }
    val job = DocumentJob.Outline(entries.toList())
    SaveButtons(true, onCopy = { save.copy(job, "toc") }, onReplace = { save.replace(job) })

    editing?.let { index ->
        val current = entries.getOrNull(index)
        var title by remember(index) { mutableStateOf(current?.title.orEmpty()) }
        var page by remember(index) { mutableStateOf(((current?.page ?: 0) + 1).toString()) }
        val number = page.toIntOrNull()?.takeIf { it in 1..pageCount }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(if (current == null) R.string.outline_add else R.string.outline_edit)) },
            text = {
                Column {
                    OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.prop_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        page, { v -> page = v.filter(Char::isDigit).take(6) },
                        label = { Text(stringResource(R.string.viewer_page_number, pageCount)) },
                        singleLine = true,
                        isError = number == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank() && number != null, onClick = {
                    if (current == null) entries += OutlineEntry(title.trim(), number!! - 1, 0)
                    else entries[index] = current.copy(title = title.trim(), page = number!! - 1)
                    editing = null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
internal fun AttachmentsOptions(viewModel: ToolsViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val items by viewModel.attachments.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadAttachments() }
    var saving by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::addAttachment) }
    val saveLauncher = rememberCreateDocument("*/*") { uri -> val name = saving; if (uri != null && name != null) viewModel.saveAttachment(name, uri); saving = null }
    SheetTitle(R.string.tool_attachments)
    when (val list = items) {
        null -> CircularProgressIndicator(Modifier.padding(24.dp))
        else -> {
            if (list.isEmpty()) Note(R.string.attachments_empty)
            list.forEach { a ->
                ListItem(
                    headlineContent = { Text(a.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(listOfNotNull(formatSize(context, a.size), a.description).joinToString(" · ")) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { saving = a.name; saveLauncher.launch(a.name) }) { Icon(Icons.Default.Save, stringResource(R.string.save)) }
                            IconButton(onClick = { removing = a.name }) { Icon(Icons.Default.Delete, stringResource(R.string.pages_delete)) }
                        }
                    },
                )
            }
        }
    }
    Button(onClick = { pick.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Icon(Icons.Default.Add, null)
        Text(stringResource(R.string.attachments_add), Modifier.padding(start = 6.dp))
    }
    removing?.let { name ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.attachments_remove_title)) },
            text = { Text(name) },
            confirmButton = { TextButton(onClick = { removing = null; viewModel.removeAttachment(name) }) { Text(stringResource(R.string.pages_delete)) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
internal fun RedactTextOptions(save: SaveActions) {
    var query by remember { mutableStateOf("") }
    val patterns = remember { mutableStateListOf<RedactPattern>() }
    SheetTitle(R.string.tool_redact_text)
    OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.redact_words)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Label(R.string.redact_patterns)
    listOf(
        RedactPattern.EMAIL to R.string.redact_email,
        RedactPattern.PHONE to R.string.redact_phone,
        RedactPattern.CARD to R.string.redact_card,
        RedactPattern.IBAN to R.string.redact_iban,
        RedactPattern.PASSPORT to R.string.redact_passport,
    ).forEach { (p, label) ->
        CheckRow(p in patterns, label) { if (it) patterns += p else patterns -= p }
    }
    Note(R.string.redact_text_note)
    val job = DocumentJob.RedactText(query.trim(), patterns.toSet())
    SaveButtons(query.isNotBlank() || patterns.isNotEmpty(), onCopy = { save.copy(job, "redacted") }, onReplace = { save.replace(job) })
}

@Composable
internal fun SanitizeOptionsSheet(save: SaveActions) {
    var o by remember { mutableStateOf(SanitizeOptions()) }
    SheetTitle(R.string.tool_sanitize)
    CheckRow(o.metadata, R.string.sanitize_metadata) { o = o.copy(metadata = it) }
    CheckRow(o.scripts, R.string.sanitize_scripts) { o = o.copy(scripts = it) }
    CheckRow(o.attachments, R.string.sanitize_attachments) { o = o.copy(attachments = it) }
    CheckRow(o.thumbnails, R.string.sanitize_thumbnails) { o = o.copy(thumbnails = it) }
    CheckRow(o.comments, R.string.sanitize_comments) { o = o.copy(comments = it) }
    val job = DocumentJob.Sanitize(o)
    SaveButtons(o != SanitizeOptions(false, false, false, false, false), onCopy = { save.copy(job, "clean") }, onReplace = { save.replace(job) })
}

@Composable
internal fun SignOptions(viewModel: ToolsViewModel, pageCount: Int, save: SaveActions) {
    val scope = rememberCoroutineScope()
    var certificates by remember { mutableStateOf(viewModel.certificates.list()) }
    var selected by remember { mutableStateOf(certificates.firstOrNull()?.id) }
    var password by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(true) }
    var visiblePage by remember { mutableStateOf(pageCount.toString()) }
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf<ByteArray?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { importing = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() } }
    }
    fun refresh(id: String?) {
        certificates = viewModel.certificates.list()
        selected = id ?: certificates.firstOrNull()?.id
    }

    SheetTitle(R.string.tool_sign)
    Note(R.string.sign_intro)
    Label(R.string.sign_certificate)
    if (certificates.isEmpty()) Note(R.string.sign_no_certificates)
    certificates.forEach { c ->
        CertificateRow(c, selected == c.id, onSelect = { selected = c.id }, onDelete = {
            viewModel.certificates.delete(c.id)
            refresh(null)
        })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        OutlinedButton(onClick = { pick.launch(arrayOf("application/x-pkcs12", "application/octet-stream", "*/*")) }) { Text(stringResource(R.string.sign_import)) }
        OutlinedButton(onClick = { creating = true }) { Text(stringResource(R.string.sign_create)) }
    }
    if (selected != null) {
        OutlinedTextField(
            password, { password = it },
            label = { Text(stringResource(R.string.sign_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(reason, { reason = it }, label = { Text(stringResource(R.string.sign_reason)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        OutlinedTextField(location, { location = it }, label = { Text(stringResource(R.string.sign_location)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        CheckRow(visible, R.string.sign_visible) { visible = it }
        if (visible) {
            OutlinedTextField(
                visiblePage, { v -> visiblePage = v.filter(Char::isDigit).take(6) },
                label = { Text(stringResource(R.string.viewer_page_number, pageCount)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Note(R.string.sign_note)
    }
    error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
    val page = if (visible) visiblePage.toIntOrNull()?.takeIf { it in 1..pageCount }?.minus(1) else -1
    val job = selected?.takeIf { password.isNotEmpty() && page != null }?.let {
        DocumentJob.Sign(it, password.toCharArray(), reason, location, page ?: -1)
    }
    SaveButtons(job != null, onCopy = { job?.let { save.copy(it, "signed") } }, onReplace = { job?.let(save.replace) })

    importing?.let { data ->
        PasswordDialog(R.string.sign_import, onDismiss = { importing = null }) { pass ->
            importing = null
            scope.launch {
                val info = withContext(Dispatchers.IO) { runCatching { viewModel.certificates.import(data, pass.toCharArray()) }.getOrNull() }
                if (info == null) error = R.string.sign_import_failed else { error = null; refresh(info.id) }
            }
        }
    }
    if (creating) {
        CreateCertificateDialog(onDismiss = { creating = false }) { name, email, org, pass ->
            creating = false
            scope.launch {
                val info = withContext(Dispatchers.Default) { runCatching { viewModel.certificates.create(name, email, org, pass.toCharArray()) }.getOrNull() }
                if (info != null) {
                    password = pass
                    refresh(info.id)
                }
            }
        }
    }
}

@Composable
private fun CertificateRow(c: CertificateInfo, selected: Boolean, onSelect: () -> Unit, onDelete: () -> Unit) {
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(c.validTo))
    ListItem(
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        headlineContent = { Text(c.name) },
        supportingContent = {
            Text(
                if (c.selfSigned) stringResource(R.string.sign_self_signed, date) else stringResource(R.string.sign_issued_by, c.issuer, date),
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = { IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.pages_delete)) } },
        modifier = Modifier.clickable(onClick = onSelect),
    )
}

@Composable
private fun PasswordDialog(title: Int, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(
                password, { password = it },
                label = { Text(stringResource(R.string.sign_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(password) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun CreateCertificateDialog(onDismiss: () -> Unit, onConfirm: (String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var org by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sign_create)) },
        text = {
            Column {
                Text(stringResource(R.string.sign_create_note), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.sign_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(email, { email = it }, label = { Text(stringResource(R.string.sign_email)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(org, { org = it }, label = { Text(stringResource(R.string.sign_organization)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.sign_password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    confirm, { confirm = it },
                    label = { Text(stringResource(R.string.protect_confirm)) },
                    singleLine = true,
                    isError = confirm.isNotEmpty() && confirm != password,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && password.length >= 4 && password == confirm, onClick = { onConfirm(name.trim(), email.trim(), org.trim(), password) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun VerifyOptions(viewModel: ToolsViewModel) {
    val checks by produceState<List<app.pdfdoc.data.sign.SignatureCheck>?>(null) { value = viewModel.verifySignatures() ?: emptyList() }
    SheetTitle(R.string.tool_verify)
    val list = checks
    when {
        list == null -> CircularProgressIndicator(Modifier.padding(24.dp))
        list.isEmpty() -> Note(R.string.verify_none)
        else -> list.forEach { c ->
            val ok = c.intact && c.coversDocument
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(c.signer.ifBlank { "—" }, style = MaterialTheme.typography.titleSmall)
                c.date?.let { Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(it.time), style = MaterialTheme.typography.bodySmall) }
                c.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(
                    stringResource(
                        when {
                            !c.intact -> R.string.verify_broken
                            !c.coversDocument -> R.string.verify_changed_after
                            else -> R.string.verify_ok
                        },
                    ),
                    color = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(if (c.selfSigned) R.string.verify_self_signed else R.string.verify_not_checked_trust),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!c.certificateValidAtSigning) Text(stringResource(R.string.verify_cert_expired), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            HorizontalDivider()
        }
    }
    Note(R.string.verify_note)
    androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 16.dp))
}

@Composable
internal fun DataOptions(viewModel: ToolsViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val data by produceState<DocumentData?>(null) { value = viewModel.findData() }
    SheetTitle(R.string.tool_data)
    val found = data
    if (found == null) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 16.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Text(stringResource(R.string.data_searching), Modifier.padding(start = 12.dp))
        }
        return
    }
    if (found.items.isEmpty() && found.codes.isEmpty()) {
        Note(R.string.data_none)
        Spacer(Modifier.height(24.dp))
        return
    }
    fun start(intent: android.content.Intent) {
        runCatching { context.startActivity(intent) }.onFailure {
            android.widget.Toast.makeText(context, R.string.no_app_for_action, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    fun copy(text: String) {
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(text, text))
        android.widget.Toast.makeText(context, R.string.copied, android.widget.Toast.LENGTH_SHORT).show()
    }
    fun open(text: String) {
        val url = if (text.startsWith("www.", ignoreCase = true)) "https://$text" else text
        start(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }
    @Composable
    fun Row(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, page: Int, actions: @Composable () -> Unit) {
        ListItem(
            headlineContent = { Text(text, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(stringResource(R.string.form_page, page + 1)) },
            leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = { androidx.compose.foundation.layout.Row { actions() } },
            colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            modifier = Modifier.clickable { copy(text) },
        )
    }
    if (found.codes.isNotEmpty()) Label(R.string.data_codes)
    found.codes.forEach { c ->
        val isLink = Regex("^(https?://|www\\.)", RegexOption.IGNORE_CASE).containsMatchIn(c.text)
        Row(if (c.qr) Icons.Default.QrCode2 else Icons.Default.ViewWeek, c.text, c.page) {
            if (isLink) IconButton(onClick = { open(c.text) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.data_open)) }
            IconButton(onClick = { copy(c.text) }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.copy)) }
        }
    }
    if (found.items.isNotEmpty()) Label(R.string.data_found)
    found.items.forEach { d ->
        when (d.kind) {
            app.pdfdoc.domain.text.DataKind.PHONE -> Row(Icons.Default.Phone, d.text, d.page) {
                IconButton(onClick = { start(android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + d.text.filter { it.isDigit() || it == '+' }))) }) {
                    Icon(Icons.Default.Call, stringResource(R.string.data_call))
                }
                IconButton(onClick = {
                    start(android.content.Intent(android.content.Intent.ACTION_INSERT).setType(android.provider.ContactsContract.Contacts.CONTENT_TYPE)
                        .putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, d.text))
                }) { Icon(Icons.Default.PersonAdd, stringResource(R.string.data_add_contact)) }
            }
            app.pdfdoc.domain.text.DataKind.EMAIL -> Row(Icons.Default.Email, d.text, d.page) {
                IconButton(onClick = { start(android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:" + d.text))) }) {
                    Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.data_write))
                }
                IconButton(onClick = {
                    start(android.content.Intent(android.content.Intent.ACTION_INSERT).setType(android.provider.ContactsContract.Contacts.CONTENT_TYPE)
                        .putExtra(android.provider.ContactsContract.Intents.Insert.EMAIL, d.text))
                }) { Icon(Icons.Default.PersonAdd, stringResource(R.string.data_add_contact)) }
            }
            app.pdfdoc.domain.text.DataKind.LINK -> Row(Icons.Default.Link, d.text, d.page) {
                IconButton(onClick = { open(d.text) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.data_open)) }
            }
            app.pdfdoc.domain.text.DataKind.DATE -> Row(Icons.Default.Event, d.text, d.page) {
                val ymd = app.pdfdoc.domain.text.DataDetector.parseDate(d.text)
                if (ymd != null) IconButton(onClick = {
                    val cal = java.util.Calendar.getInstance().apply { set(ymd.first, ymd.second - 1, ymd.third, 9, 0, 0) }
                    start(android.content.Intent(android.content.Intent.ACTION_INSERT).setData(android.provider.CalendarContract.Events.CONTENT_URI)
                        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, cal.timeInMillis)
                        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                        .putExtra(android.provider.CalendarContract.Events.TITLE, viewModel.baseName))
                }) { Icon(Icons.Default.EventAvailable, stringResource(R.string.data_add_event)) }
            }
            app.pdfdoc.domain.text.DataKind.AMOUNT -> Row(Icons.Default.Payments, d.text, d.page) {
                IconButton(onClick = { copy(d.text) }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.copy)) }
            }
        }
    }
    Note(R.string.data_hint)
    Spacer(Modifier.height(24.dp))
}
