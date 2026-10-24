package app.pdfdoc.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** Folder chosen in settings as the default place to save files (a SAF tree URI). */
val LocalDefaultFolder = staticCompositionLocalOf<Uri?> { null }

/** "Save as" dialog that opens in [folder] when one is set. */
class CreateDocumentIn(mime: String, private val folder: Uri?) : ActivityResultContracts.CreateDocument(mime) {
    override fun createIntent(context: Context, input: String): Intent = super.createIntent(context, input).apply {
        folder?.let {
            runCatching {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUriUsingTree(it, DocumentsContract.getTreeDocumentId(it)))
            }
        }
    }
}

@Composable
fun rememberCreateDocument(mime: String, onResult: (Uri?) -> Unit): ManagedActivityResultLauncher<String, Uri?> =
    rememberLauncherForActivityResult(CreateDocumentIn(mime, LocalDefaultFolder.current), onResult)
