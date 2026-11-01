package app.pdfdoc.ui.viewer

import android.net.Uri
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class OpenTab(val uri: Uri, val name: String)

/** Documents opened in this session, shown as tabs above the viewer. */
@Singleton
class OpenTabs @Inject constructor() {
    private val _tabs = MutableStateFlow<List<OpenTab>>(emptyList())
    val tabs: StateFlow<List<OpenTab>> = _tabs.asStateFlow()

    fun open(uri: Uri, name: String) = _tabs.update { list ->
        if (list.any { it.uri == uri }) list.map { if (it.uri == uri) it.copy(name = name) else it }
        else (list + OpenTab(uri, name)).takeLast(MAX_TABS)
    }

    fun close(uri: Uri) = _tabs.update { list -> list.filterNot { it.uri == uri } }

    private companion object {
        const val MAX_TABS = 8
    }
}
