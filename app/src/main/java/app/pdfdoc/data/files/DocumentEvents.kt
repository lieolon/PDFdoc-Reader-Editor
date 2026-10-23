package app.pdfdoc.data.files

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Notifies open screens that a document's content was rewritten on disk. */
@Singleton
class DocumentEvents @Inject constructor() {
    private val _changed = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val changed: SharedFlow<String> = _changed

    fun notifyChanged(uri: String) {
        _changed.tryEmit(uri)
    }
}
