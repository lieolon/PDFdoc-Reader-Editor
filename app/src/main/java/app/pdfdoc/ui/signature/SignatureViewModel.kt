package app.pdfdoc.ui.signature

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pdfdoc.core.IoDispatcher
import app.pdfdoc.data.files.Images
import app.pdfdoc.data.signature.SignatureRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class SignatureViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SignatureRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    val ids: StateFlow<List<Long>> =
        repository.observeIds().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun load(id: Long): Bitmap? = repository.load(id)

    fun save(bitmap: Bitmap, onSaved: (Long) -> Unit) {
        viewModelScope.launch { onSaved(repository.save(bitmap)) }
    }

    /** Imports a photo or scan of a signature, removing the paper background. */
    fun import(uri: Uri, onSaved: (Long?) -> Unit) {
        viewModelScope.launch {
            val bitmap = runCatching {
                withContext(io) {
                    val decoded = Images.decode(context, uri, maxSide = 1200)
                    Images.trim(Images.removeLightBackground(decoded))
                }
            }.getOrNull()
            onSaved(bitmap?.let { repository.save(it) })
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }
}
