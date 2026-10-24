package app.pdfdoc

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pdfdoc.data.settings.SettingsRepository
import app.pdfdoc.domain.model.ThemeMode
import app.pdfdoc.ui.common.LocalDefaultFolder
import app.pdfdoc.ui.navigation.AppNavHost
import app.pdfdoc.ui.theme.PdfDocTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var settings: SettingsRepository

    private var incomingUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) incomingUri = intent.pdfUri()
        setContent {
            val themeMode by settings.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
            val folder by settings.defaultFolder.collectAsStateWithLifecycle(null)
            CompositionLocalProvider(LocalDefaultFolder provides folder?.let(Uri::parse)) {
                PdfDocTheme(themeMode) {
                    AppNavHost(incomingUri = incomingUri, onIncomingHandled = { incomingUri = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.pdfUri()?.let { incomingUri = it }
    }

    private fun Intent.pdfUri(): Uri? = if (action == Intent.ACTION_VIEW) data else null
}
