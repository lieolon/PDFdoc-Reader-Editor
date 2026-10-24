package app.pdfdoc.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pdfdoc.R
import app.pdfdoc.core.IoDispatcher
import app.pdfdoc.data.files.FileStore
import app.pdfdoc.data.settings.SettingsRepository
import app.pdfdoc.domain.model.ThemeMode
import app.pdfdoc.domain.tools.CompressLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppLanguage(val tag: String) { SYSTEM(""), RUSSIAN("ru"), ENGLISH("en") }

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val files: FileStore,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = settings.themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)
    val exportDpi: StateFlow<Int> = settings.exportDpi.stateIn(viewModelScope, SharingStarted.Eagerly, 150)
    val jpegQuality: StateFlow<Int> = settings.jpegQuality.stateIn(viewModelScope, SharingStarted.Eagerly, 90)
    val compressLevel: StateFlow<CompressLevel> = settings.compressLevel.stateIn(viewModelScope, SharingStarted.Eagerly, CompressLevel.MEDIUM)
    val defaultFolderName: StateFlow<String?> = settings.defaultFolder.map { uri ->
        uri?.let { withContext(io) { runCatching { DocumentFile.fromTreeUri(context, Uri.parse(it))?.name }.getOrNull() ?: it } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _messages = Channel<Int>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    val language: AppLanguage
        get() {
            val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            return AppLanguage.entries.firstOrNull { it.tag.isNotEmpty() && tag.startsWith(it.tag) } ?: AppLanguage.SYSTEM
        }

    fun setLanguage(language: AppLanguage) = applyLanguage(language.tag)

    private fun applyLanguage(tag: String) {
        AppCompatDelegate.setApplicationLocales(
            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
        )
    }

    fun setTheme(mode: ThemeMode) = launch { settings.setThemeMode(mode) }
    fun setExportDpi(dpi: Int) = launch { settings.setExportDpi(dpi) }
    fun setJpegQuality(q: Int) = launch { settings.setJpegQuality(q) }
    fun setCompressLevel(level: CompressLevel) = launch { settings.setCompressLevel(level) }

    fun setDefaultFolder(tree: Uri?) = launch {
        tree?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        settings.setDefaultFolder(tree?.toString())
    }

    fun clearCache() = launch {
        withContext(io) { files.clearCache() }
        _messages.send(R.string.settings_cache_cleared)
    }

    fun exportSettings(target: Uri) = launch {
        val json = settings.exportJson(language.tag)
        val ok = withContext(io) {
            runCatching { context.contentResolver.openOutputStream(target, "wt")?.use { it.write(json.toByteArray()) } }.isSuccess
        }
        _messages.send(if (ok) R.string.saved else R.string.error_save)
    }

    fun importSettings(source: Uri) = launch {
        val text = withContext(io) {
            runCatching { context.contentResolver.openInputStream(source)?.use { it.readBytes().decodeToString() } }.getOrNull()
        }
        val language = text?.let { settings.importJson(it) }
        if (language == null) {
            _messages.send(R.string.settings_import_invalid)
        } else {
            _messages.send(R.string.settings_imported)
            applyLanguage(language)
        }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
