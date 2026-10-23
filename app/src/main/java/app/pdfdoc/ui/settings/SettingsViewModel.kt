package app.pdfdoc.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pdfdoc.core.IoDispatcher
import app.pdfdoc.data.files.FileStore
import app.pdfdoc.data.settings.SettingsRepository
import app.pdfdoc.domain.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val files: FileStore,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> =
        settings.themeMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun clearCache(onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(io) { files.clearCache() }
            onDone()
        }
    }
}
