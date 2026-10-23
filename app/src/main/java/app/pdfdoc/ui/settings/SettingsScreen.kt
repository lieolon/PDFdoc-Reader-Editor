package app.pdfdoc.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pdfdoc.R
import app.pdfdoc.domain.model.ThemeMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val theme by viewModel.themeMode.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val cleared = stringResource(R.string.settings_cache_cleared)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.settings_theme),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
            )
            listOf(
                ThemeMode.SYSTEM to R.string.theme_system,
                ThemeMode.LIGHT to R.string.theme_light,
                ThemeMode.DARK to R.string.theme_dark,
            ).forEach { (mode, label) ->
                ListItem(
                    modifier = Modifier.clickable { viewModel.setTheme(mode) },
                    headlineContent = { Text(stringResource(label)) },
                    leadingContent = { RadioButton(selected = theme == mode, onClick = null) },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ListItem(
                modifier = Modifier.clickable {
                    viewModel.clearCache { scope.launch { snackbar.showSnackbar(cleared) } }
                },
                headlineContent = { Text(stringResource(R.string.settings_clear_cache)) },
                leadingContent = { Icon(Icons.Default.CleaningServices, null) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_privacy)) },
                leadingContent = { Icon(Icons.Default.Lock, null) },
            )
        }
    }
}
