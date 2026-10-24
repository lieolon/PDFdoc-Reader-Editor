package app.pdfdoc.domain.repo

import android.net.Uri
import app.pdfdoc.domain.model.PdfDocument
import kotlinx.coroutines.flow.Flow

interface DocumentRepository {
    fun observeRecent(): Flow<List<PdfDocument>>

    /**
     * Registers a document as opened (metadata, thumbnail) and returns it. [source] is a readable
     * unencrypted copy to render the thumbnail from; by default the document itself is read.
     */
    suspend fun recordOpened(uri: Uri, source: java.io.File? = null): PdfDocument

    suspend fun updateLastPage(uri: Uri, page: Int)

    suspend fun remove(id: Long)

    suspend fun removeByUri(uri: Uri)
}
