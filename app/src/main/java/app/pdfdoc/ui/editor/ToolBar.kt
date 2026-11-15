package app.pdfdoc.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Approval
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BackHand
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pdfdoc.R

/**
 * Everything the editor's bottom bar can hold: tools that stay selected, and actions
 * (field kinds, image, signature, stamp) that open a menu or a picker.
 */
enum class BarItem(val icon: ImageVector, val label: Int, val tool: Tool?) {
    SELECT(Icons.Default.TouchApp, R.string.tool_select, Tool.SELECT),
    HAND(Icons.Default.BackHand, R.string.tool_hand, Tool.HAND),
    LASSO(Icons.Default.HighlightAlt, R.string.tool_lasso, Tool.LASSO),
    HIGHLIGHT(Icons.Default.BorderColor, R.string.tool_highlight, Tool.HIGHLIGHT),
    UNDERLINE(Icons.Default.FormatUnderlined, R.string.tool_underline, Tool.UNDERLINE),
    STRIKEOUT(Icons.Default.FormatStrikethrough, R.string.tool_strikeout, Tool.STRIKEOUT),
    REPLACE(Icons.Default.FindReplace, R.string.tool_replace, Tool.REPLACE),
    PEN(Icons.Default.Draw, R.string.tool_pen, Tool.PEN),
    SMART_PEN(Icons.Default.AutoFixHigh, R.string.tool_smart_pen, Tool.SMART_PEN),
    ERASER(Icons.Default.CleaningServices, R.string.tool_eraser, Tool.ERASER),
    RECT(Icons.Default.CropSquare, R.string.tool_rect, Tool.RECT),
    OVAL(Icons.Default.RadioButtonUnchecked, R.string.tool_oval, Tool.OVAL),
    LINE(Icons.Default.HorizontalRule, R.string.tool_line, Tool.LINE),
    ARROW(Icons.AutoMirrored.Filled.ArrowForward, R.string.tool_arrow, Tool.ARROW),
    TEXT(Icons.Default.TextFields, R.string.tool_text, Tool.TEXT),
    NOTE(Icons.Default.EditNote, R.string.tool_note, Tool.NOTE),
    REDACT(Icons.Default.VisibilityOff, R.string.tool_redact, Tool.REDACT),
    LINK(Icons.Default.Link, R.string.tool_link, Tool.LINK),
    MEASURE_DISTANCE(Icons.Default.Straighten, R.string.tool_measure, Tool.MEASURE_DISTANCE),
    MEASURE_AREA(Icons.Default.SquareFoot, R.string.tool_measure_area, Tool.MEASURE_AREA),
    IMAGE_EDIT(Icons.Default.ImageSearch, R.string.tool_image_edit, Tool.IMAGE_EDIT),
    FIELD(Icons.Default.CheckBox, R.string.tool_field, null),
    IMAGE(Icons.Default.Image, R.string.tool_image, null),
    SIGNATURE(Icons.Default.Gesture, R.string.tool_signature, null),
    STAMP(Icons.Default.Approval, R.string.tool_stamp, null),
    ;

    companion object {
        fun of(tool: Tool): BarItem? = entries.firstOrNull { it.tool == tool } ?: if (tool == Tool.FIELD) FIELD else null
    }
}

/** The user's order of the bar and the items moved to "More". */
data class BarLayout(val order: List<BarItem> = BarItem.entries, val hidden: Set<BarItem> = emptySet()) {
    val visible: List<BarItem> get() = order.filterNot { it in hidden }
    val more: List<BarItem> get() = order.filter { it in hidden }

    fun encode(): String = if (this == BarLayout()) "" else order.joinToString(",") { if (it in hidden) "-${it.name}" else it.name }

    fun move(from: Int, to: Int): BarLayout {
        if (to !in order.indices) return this
        val list = order.toMutableList()
        list.add(to, list.removeAt(from))
        return copy(order = list)
    }

    fun toggle(item: BarItem): BarLayout {
        val nowHidden = if (item in hidden) hidden - item else hidden + item
        // Keep at least one tool on the bar so it never ends up empty.
        return if (order.all { it in nowHidden }) this else copy(hidden = nowHidden)
    }

    companion object {
        /** Unknown names are dropped; items added in later versions go to the end, shown. */
        fun decode(raw: String): BarLayout {
            if (raw.isBlank()) return BarLayout()
            val hidden = mutableSetOf<BarItem>()
            val order = raw.split(',').mapNotNull { token ->
                val name = token.trim().removePrefix("-")
                val item = BarItem.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
                if (token.trim().startsWith("-")) hidden += item
                item
            }.distinct()
            return BarLayout(order + BarItem.entries.filterNot { it in order }, hidden)
        }
    }
}

/** Reorder the bar with arrows, move items to "More" and back, or reset it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarSettingsSheet(
    layout: BarLayout,
    showRecent: Boolean,
    onChange: (BarLayout) -> Unit,
    onShowRecent: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.bar_settings), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { onChange(BarLayout()) }) { Text(stringResource(R.string.bar_reset)) }
            }
            Text(
                stringResource(R.string.bar_settings_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.bar_recent)) },
                supportingContent = { Text(stringResource(R.string.bar_recent_hint)) },
                trailingContent = { Switch(checked = showRecent, onCheckedChange = onShowRecent) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                itemsIndexed(layout.order, key = { _, item -> item.name }) { i, item ->
                    val hidden = item in layout.hidden
                    ListItem(
                        leadingContent = {
                            Icon(item.icon, null, tint = if (hidden) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary)
                        },
                        headlineContent = {
                            Text(
                                stringResource(item.label),
                                color = if (hidden) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        supportingContent = if (hidden) ({ Text(stringResource(R.string.bar_in_more)) }) else null,
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { onChange(layout.move(i, i - 1)) }, enabled = i > 0) {
                                    Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.bar_move_up))
                                }
                                IconButton(onClick = { onChange(layout.move(i, i + 1)) }, enabled = i < layout.order.size - 1) {
                                    Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.bar_move_down))
                                }
                                Switch(checked = !hidden, onCheckedChange = { onChange(layout.toggle(item)) })
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}
