package app.pdfdoc.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pdfdoc.R
import app.pdfdoc.domain.model.ThemeMode
import app.pdfdoc.domain.tools.CompressLevel
import app.pdfdoc.ui.common.rememberCreateDocument
import app.pdfdoc.ui.signature.SignatureSheet
import kotlin.math.roundToInt

private data class Choice<T>(val value: T, val label: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val theme by viewModel.themeMode.collectAsStateWithLifecycle()
    val dpi by viewModel.exportDpi.collectAsStateWithLifecycle()
    val jpeg by viewModel.jpegQuality.collectAsStateWithLifecycle()
    val compress by viewModel.compressLevel.collectAsStateWithLifecycle()
    val folder by viewModel.defaultFolderName.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<String?>(null) }
    var signatures by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(viewModel::setDefaultFolder) }
    val exportLauncher = rememberCreateDocument("application/json") { it?.let(viewModel::exportSettings) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(viewModel::importSettings) }

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(context.getString(it)) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Section(R.string.settings_section_look)
            Item(Icons.Default.Palette, R.string.settings_theme, stringResource(themeLabel(theme))) { dialog = "theme" }
            Item(Icons.Default.Language, R.string.settings_language, stringResource(languageLabel(viewModel.language))) { dialog = "language" }

            Section(R.string.settings_section_files)
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_folder)) },
                supportingContent = { Text(folder ?: stringResource(R.string.settings_folder_none)) },
                leadingContent = { Icon(Icons.Default.Folder, null) },
                trailingContent = {
                    if (folder != null) {
                        IconButton(onClick = { viewModel.setDefaultFolder(null) }) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
                    }
                },
                modifier = Modifier.clickable { folderPicker.launch(null) },
            )
            Item(Icons.Default.Compress, R.string.settings_compress, stringResource(compressLabel(compress))) { dialog = "compress" }
            Item(Icons.Default.Image, R.string.settings_dpi, "$dpi dpi") { dialog = "dpi" }
            var quality by remember(jpeg) { mutableFloatStateOf(jpeg.toFloat()) }
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_jpeg, quality.roundToInt())) },
                supportingContent = {
                    Slider(
                        value = quality,
                        onValueChange = { quality = it },
                        onValueChangeFinished = { viewModel.setJpegQuality(quality.roundToInt()) },
                        valueRange = 50f..100f,
                    )
                },
                leadingContent = { Icon(Icons.Default.HighQuality, null) },
            )

            Section(R.string.settings_section_data)
            Item(Icons.Default.Gesture, R.string.settings_signatures, stringResource(R.string.settings_signatures_desc)) { signatures = true }
            Item(Icons.Default.CleaningServices, R.string.settings_clear_cache, null, viewModel::clearCache)
            Item(Icons.Default.Upload, R.string.settings_export, stringResource(R.string.settings_export_desc)) {
                exportLauncher.launch("pdfdoc-settings.json")
            }
            Item(Icons.Default.Download, R.string.settings_import, null) { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }

            Section(R.string.settings_section_about)
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_privacy)) },
                leadingContent = { Icon(Icons.Default.Lock, null) },
            )
            Item(Icons.Default.Info, R.string.settings_licenses, null) { dialog = "licenses" }
        }
    }

    when (dialog) {
        "theme" -> ChoiceDialog(
            R.string.settings_theme,
            listOf(Choice(ThemeMode.SYSTEM, R.string.theme_system), Choice(ThemeMode.LIGHT, R.string.theme_light), Choice(ThemeMode.DARK, R.string.theme_dark)),
            theme, onDismiss = { dialog = null },
        ) { viewModel.setTheme(it) }
        "language" -> ChoiceDialog(
            R.string.settings_language,
            AppLanguage.entries.map { Choice(it, languageLabel(it)) },
            viewModel.language, onDismiss = { dialog = null },
        ) { viewModel.setLanguage(it) }
        "compress" -> ChoiceDialog(
            R.string.settings_compress,
            CompressLevel.entries.map { Choice(it, compressLabel(it)) },
            compress, onDismiss = { dialog = null },
        ) { viewModel.setCompressLevel(it) }
        "dpi" -> ChoiceDialog(
            R.string.settings_dpi,
            listOf(Choice(96, R.string.dpi_96), Choice(150, R.string.dpi_150), Choice(300, R.string.dpi_300)),
            dpi, onDismiss = { dialog = null },
        ) { viewModel.setExportDpi(it) }
        "licenses" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.settings_licenses)) },
            text = { Text(stringResource(R.string.licenses_text)) },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.ok)) } },
        )
    }

    if (signatures) {
        // Picking does nothing here; the sheet is used to review and delete saved signatures.
        SignatureSheet(onPick = {}, onDismiss = { signatures = false }, onError = {})
    }
}

@Composable
private fun Section(title: Int) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Item(icon: ImageVector, title: Int, value: String?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = value?.let { { Text(it) } },
        leadingContent = { Icon(icon, null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun <T> ChoiceDialog(title: Int, options: List<Choice<T>>, selected: T, onDismiss: () -> Unit, onSelect: (T) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column {
                options.forEach { option ->
                    ListItem(
                        headlineContent = { Text(stringResource(option.label)) },
                        leadingContent = { RadioButton(selected = option.value == selected, onClick = null) },
                        modifier = Modifier.clickable { onSelect(option.value); onDismiss() },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun themeLabel(t: ThemeMode) = when (t) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

private fun languageLabel(l: AppLanguage) = when (l) {
    AppLanguage.SYSTEM -> R.string.language_system
    AppLanguage.RUSSIAN -> R.string.language_russian
    AppLanguage.ENGLISH -> R.string.language_english
}

private fun compressLabel(c: CompressLevel) = when (c) {
    CompressLevel.LIGHT -> R.string.compress_light
    CompressLevel.MEDIUM -> R.string.compress_medium
    CompressLevel.STRONG -> R.string.compress_strong
}
