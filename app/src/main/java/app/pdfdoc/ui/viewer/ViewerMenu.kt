package app.pdfdoc.ui.viewer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChromeReaderMode
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.SwipeDown
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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

/** Items of the viewer's "⋮" menu that the user can order and move to "More". */
enum class MenuItem(val icon: ImageVector, val label: Int) {
    NIGHT(Icons.Default.DarkMode, R.string.viewer_night_mode),
    MODE(Icons.Default.ViewAgenda, R.string.viewer_mode),
    NAVIGATION(Icons.AutoMirrored.Filled.List, R.string.viewer_navigation),
    READER(Icons.Default.ChromeReaderMode, R.string.reader_mode),
    ASK(Icons.Default.QuestionAnswer, R.string.ask_title),
    PRESENT(Icons.Default.Slideshow, R.string.present_mode),
    AUTO_SCROLL(Icons.Default.SwipeDown, R.string.auto_scroll),
    KEEP_ON(Icons.Default.LightMode, R.string.keep_screen_on),
    SIDE_BY_SIDE(Icons.Default.VerticalSplit, R.string.open_side_by_side),
    READ_ALOUD(Icons.Default.RecordVoiceOver, R.string.read_aloud),
    TOOLS(Icons.Default.Build, R.string.viewer_tools),
    OCR(Icons.Default.DocumentScanner, R.string.viewer_ocr),
    SHARE(Icons.Default.Share, R.string.viewer_share),
    OPEN_WITH(Icons.AutoMirrored.Filled.OpenInNew, R.string.viewer_open_with),
    PRINT(Icons.Default.Print, R.string.viewer_print),
}

/** Order of the menu items and which of them sit under "More". */
data class MenuLayout(val order: List<MenuItem> = MenuItem.entries, val more: Set<MenuItem> = DEFAULT_MORE) {
    val main: List<MenuItem> get() = order.filterNot { it in more }
    val extra: List<MenuItem> get() = order.filter { it in more }

    fun encode(): String = if (this == MenuLayout()) "" else order.joinToString(",") { if (it in more) "-${it.name}" else it.name }

    /** Moves [item] one place up or down within its own group. */
    fun move(item: MenuItem, up: Boolean): MenuLayout {
        val group = if (item in more) extra else main
        val i = group.indexOf(item)
        val other = group.getOrNull(if (up) i - 1 else i + 1) ?: return this
        val list = order.toMutableList()
        val a = list.indexOf(item)
        val b = list.indexOf(other)
        list[a] = other
        list[b] = item
        return copy(order = list)
    }

    /** Moves [item] between the menu and "More"; the menu always keeps at least one item. */
    fun toggle(item: MenuItem): MenuLayout {
        val next = if (item in more) more - item else more + item
        return if (order.all { it in next }) this else copy(more = next)
    }

    companion object {
        /** What a tester asked to keep out of the way by default. */
        val DEFAULT_MORE = setOf(MenuItem.NIGHT, MenuItem.READER, MenuItem.ASK, MenuItem.PRESENT, MenuItem.AUTO_SCROLL, MenuItem.KEEP_ON, MenuItem.READ_ALOUD)

        /** Unknown names are dropped; items added in later versions go to "More". */
        fun decode(raw: String): MenuLayout {
            if (raw.isBlank()) return MenuLayout()
            val more = mutableSetOf<MenuItem>()
            val order = raw.split(',').mapNotNull { token ->
                val t = token.trim()
                val item = MenuItem.entries.firstOrNull { it.name == t.removePrefix("-") } ?: return@mapNotNull null
                if (t.startsWith("-")) more += item
                item
            }.distinct()
            val added = MenuItem.entries.filterNot { it in order }
            return MenuLayout(order + added, more + added)
        }
    }
}

/** Two groups — in the menu and under "More" — with arrows to reorder and a button to move between them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSettingsSheet(layout: MenuLayout, onChange: (MenuLayout) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.menu_settings), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { onChange(MenuLayout()) }) { Text(stringResource(R.string.bar_reset)) }
            }
            Text(
                stringResource(R.string.menu_settings_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            LazyColumn(Modifier.heightIn(max = 560.dp)) {
                item { GroupTitle(R.string.menu_group_main) }
                items(layout.main, key = { "m" + it.name }) { MenuRow(it, layout, inMore = false, onChange) }
                item { GroupTitle(R.string.menu_group_more) }
                items(layout.extra, key = { "x" + it.name }) { MenuRow(it, layout, inMore = true, onChange) }
            }
        }
    }
}

@Composable
private fun GroupTitle(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun MenuRow(item: MenuItem, layout: MenuLayout, inMore: Boolean, onChange: (MenuLayout) -> Unit) {
    val group = if (inMore) layout.extra else layout.main
    val i = group.indexOf(item)
    ListItem(
        leadingContent = { Icon(item.icon, null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(stringResource(item.label)) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onChange(layout.move(item, up = true)) }, enabled = i > 0) {
                    Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.bar_move_up))
                }
                IconButton(onClick = { onChange(layout.move(item, up = false)) }, enabled = i < group.size - 1) {
                    Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.bar_move_down))
                }
                TextButton(onClick = { onChange(layout.toggle(item)) }, enabled = inMore || layout.main.size > 1) {
                    Text(stringResource(if (inMore) R.string.menu_to_main else R.string.menu_to_more))
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
