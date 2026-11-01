package app.pdfdoc

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

@AndroidEntryPoint
open class MainActivity : AppCompatActivity() {

    @Inject lateinit var settings: SettingsRepository

    private var incomingUri by mutableStateOf<Uri?>(null)
    private var locked by mutableStateOf(false)
    private var lockEnabled = false
    private var backgroundSince = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) incomingUri = intent.pdfUri()
        lockEnabled = kotlinx.coroutines.runBlocking { settings.appLock.first() }
        locked = lockEnabled && (savedInstanceState == null || savedInstanceState.getBoolean(KEY_LOCKED, true))
        lifecycleScope.launch {
            settings.appLock.collect { lockEnabled = it }
        }
        setContent {
            val themeMode by settings.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
            val folder by settings.defaultFolder.collectAsStateWithLifecycle(null)
            CompositionLocalProvider(LocalDefaultFolder provides folder?.let(Uri::parse)) {
                PdfDocTheme(themeMode) {
                    // Edge-to-edge windows are not resized for the keyboard: pad every screen above it so text fields stay visible.
                    Box(Modifier.fillMaxSize().imePadding()) {
                        if (locked) {
                            app.pdfdoc.ui.lock.LockScreen(onUnlock = { app.pdfdoc.ui.lock.AppLock.prompt(this@MainActivity) { locked = false } })
                        } else {
                            AppNavHost(incomingUri = incomingUri, onIncomingHandled = { incomingUri = null })
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        backgroundSince = System.currentTimeMillis()
    }

    override fun onStart() {
        super.onStart()
        if (lockEnabled && backgroundSince > 0 && System.currentTimeMillis() - backgroundSince > app.pdfdoc.ui.lock.AppLock.TIMEOUT_MS) locked = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LOCKED, locked)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.pdfUri()?.let { incomingUri = it }
    }

    private fun Intent.pdfUri(): Uri? = if (action == Intent.ACTION_VIEW) data else null

    private companion object {
        const val KEY_LOCKED = "locked"
    }
}

/** A second window for viewing two documents side by side in split screen. */
@AndroidEntryPoint
class SecondWindowActivity : MainActivity()
