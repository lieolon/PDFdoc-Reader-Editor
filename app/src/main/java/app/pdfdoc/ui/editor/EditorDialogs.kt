package app.pdfdoc.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.pdfdoc.R
import app.pdfdoc.domain.edit.FieldKind
import app.pdfdoc.domain.edit.MeasureKind

/** One-line help for tools that need it, plus the controls of the measuring tools. */
@Composable
fun ToolHint(state: EditorUiState, onScale: () -> Unit, viewModel: EditorViewModel) {
    val hint = when (state.tool) {
        Tool.REDACT -> R.string.hint_redact
        Tool.LINK -> R.string.hint_link
        Tool.FIELD -> R.string.hint_field
        Tool.IMAGE_EDIT -> R.string.hint_image_edit
        Tool.ERASER -> R.string.hint_eraser
        Tool.SMART_PEN -> R.string.hint_smart_pen
        Tool.LASSO -> if (state.group.isEmpty()) R.string.lasso_hint else return
        Tool.HAND -> R.string.hint_hand
        Tool.MEASURE_DISTANCE -> R.string.hint_measure
        Tool.MEASURE_AREA -> R.string.hint_measure_area
        else -> return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.tool == Tool.MEASURE_DISTANCE || state.tool == Tool.MEASURE_AREA) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onScale) {
                    Text(stringResource(R.string.measure_scale_value, app.pdfdoc.domain.edit.Measure.format(state.scale.value), state.scale.unit))
                }
                if (state.tool == Tool.MEASURE_AREA) {
                    TextButton(onClick = { viewModel.finishMeasure(MeasureKind.PERIMETER) }, enabled = state.vertices.size >= 2) {
                        Text(stringResource(R.string.measure_perimeter))
                    }
                    TextButton(onClick = { viewModel.finishMeasure(MeasureKind.AREA) }, enabled = state.vertices.size >= 3) {
                        Text(stringResource(R.string.measure_area))
                    }
                    TextButton(onClick = viewModel::cancelVertices, enabled = state.vertices.isNotEmpty()) {
                        Text(stringResource(R.string.measure_reset))
                    }
                }
            }
        }
    }
}

@Composable
fun FieldKindMenu(expanded: Boolean, onDismiss: () -> Unit, onPick: (FieldKind) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        FieldKind.entries.forEach { kind ->
            DropdownMenuItem(text = { Text(stringResource(fieldLabel(kind))) }, onClick = { onPick(kind) })
        }
    }
}

fun fieldLabel(kind: FieldKind) = when (kind) {
    FieldKind.TEXT -> R.string.field_text
    FieldKind.MULTILINE -> R.string.field_multiline
    FieldKind.CHECKBOX -> R.string.field_checkbox
    FieldKind.RADIO -> R.string.field_radio
    FieldKind.DROPDOWN -> R.string.field_dropdown
    FieldKind.DATE -> R.string.field_date
    FieldKind.SIGNATURE -> R.string.field_signature
}

@Composable
fun LinkDialog(pageCount: Int, onDismiss: () -> Unit, onConfirm: (String?, Int) -> Unit) {
    var toPage by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("https://") }
    var page by remember { mutableStateOf("1") }
    val pageNumber = page.toIntOrNull()?.takeIf { it in 1..pageCount }
    val valid = if (toPage) pageNumber != null else url.trim().length > 8 || url.contains('@')
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tool_link)) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !toPage, onClick = { toPage = false }, label = { Text(stringResource(R.string.link_to_web)) })
                    FilterChip(selected = toPage, onClick = { toPage = true }, label = { Text(stringResource(R.string.link_to_page)) })
                }
                if (toPage) {
                    OutlinedTextField(
                        page, { v -> page = v.filter(Char::isDigit).take(6) },
                        label = { Text(stringResource(R.string.viewer_page_number, pageCount)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                } else {
                    OutlinedTextField(
                        url, { url = it },
                        label = { Text(stringResource(R.string.link_address)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                if (toPage) onConfirm(null, pageNumber!! - 1)
                else onConfirm(url.trim().let { if (it.contains('@') && !it.contains(':')) "mailto:$it" else it }, -1)
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun FieldDialog(kind: FieldKind, onDismiss: () -> Unit, onConfirm: (String, List<String>, String) -> Unit) {
    val default = stringResource(fieldLabel(kind))
    var name by remember { mutableStateOf(default) }
    var options by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(fieldLabel(kind))) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.field_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                when (kind) {
                    FieldKind.DROPDOWN -> OutlinedTextField(
                        options, { options = it },
                        label = { Text(stringResource(R.string.field_options)) },
                        supportingText = { Text(stringResource(R.string.field_options_hint)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    FieldKind.RADIO -> {
                        OutlinedTextField(
                            group, { group = it },
                            label = { Text(stringResource(R.string.field_group)) },
                            supportingText = { Text(stringResource(R.string.field_group_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        OutlinedTextField(
                            options, { options = it },
                            label = { Text(stringResource(R.string.field_value)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                    else -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val list = options.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                onConfirm(name.trim(), list, group.trim())
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScaleDialog(initial: MeasureScale, onDismiss: () -> Unit, onConfirm: (MeasureScale) -> Unit) {
    var value by remember { mutableStateOf(app.pdfdoc.domain.edit.Measure.format(initial.value).replace(',', '.')) }
    var unit by remember { mutableStateOf(initial.unit) }
    val number = value.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.measure_scale)) },
        text = {
            Column {
                Text(stringResource(R.string.measure_scale_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value, { value = it.take(10) },
                    label = { Text(stringResource(R.string.measure_scale_per_cm)) },
                    singleLine = true,
                    isError = number == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    listOf("mm", "cm", "m", "km", "in", "ft").forEach { u ->
                        FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = number != null, onClick = { onConfirm(MeasureScale(number!!, unit)) }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomStampDialog(
    saved: List<String>,
    hasAuthor: Boolean,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (text: String, color: Int, withName: Boolean, withDate: Boolean) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(Palette.RED) }
    var withName by remember { mutableStateOf(hasAuthor) }
    var withDate by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_stamp_title)) },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (saved.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                        saved.forEach { s ->
                            InputChip(
                                selected = false,
                                onClick = { text = s },
                                label = { Text(s) },
                                trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp).clickable { onRemove(s) }) },
                            )
                        }
                    }
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(Palette.RED, Palette.BLUE, Palette.GREEN, Palette.BLACK, Palette.PURPLE).forEach { c ->
                        Box(
                            Modifier.size(28.dp).background(Color(c), CircleShape)
                                .border(if (c == color) 3.dp else 1.dp, if (c == color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .clickable { color = c },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().clickable(enabled = hasAuthor) { withName = !withName }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = withName && hasAuthor, onCheckedChange = { withName = it }, enabled = hasAuthor)
                    Text(stringResource(if (hasAuthor) R.string.stamp_with_name else R.string.stamp_with_name_unset))
                }
                Row(Modifier.fillMaxWidth().clickable { withDate = !withDate }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = withDate, onCheckedChange = { withDate = it })
                    Text(stringResource(R.string.stamp_with_date))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text, color, withName && hasAuthor, withDate) }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
