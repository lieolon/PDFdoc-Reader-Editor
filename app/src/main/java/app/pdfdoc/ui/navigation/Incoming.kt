package app.pdfdoc.ui.navigation

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/** What the app was started for from outside: a file, a share, a shortcut, a widget or the quick settings tile. */
sealed interface Incoming {
    data class Open(val uri: Uri) : Incoming
    data class Images(val uris: List<Uri>) : Incoming
    data object Scan : Incoming
    data object ImagesToPdf : Incoming
    data object Pick : Incoming

    companion object {
        const val ACTION_SCAN = "app.pdfdoc.action.SCAN"
        const val ACTION_IMAGES = "app.pdfdoc.action.IMAGES_TO_PDF"
        const val ACTION_OPEN = "app.pdfdoc.action.OPEN"

        fun of(intent: Intent?): Incoming? {
            intent ?: return null
            return when (intent.action) {
                Intent.ACTION_VIEW -> intent.data?.let(::Open)
                ACTION_SCAN -> Scan
                ACTION_IMAGES -> ImagesToPdf
                ACTION_OPEN -> Pick
                Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uri ->
                    if (intent.type?.startsWith("image/") == true) Images(listOf(uri)) else Open(uri)
                }
                Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.takeIf { it.isNotEmpty() }?.let { Images(it) }
                else -> null
            }
        }
    }
}
