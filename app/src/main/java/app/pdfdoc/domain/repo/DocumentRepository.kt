package app.pdfdoc.domain.repo

import android.net.Uri
import app.pdfdoc.domain.model.PdfDocument
import kotlinx.coroutines.flow.Flow

interface DocumentRepository {
    fun observeRecent(): Flow<List<PdfDocument>>

    /** Registers a document as opened (metadata, thumbnail) and returns it. */
    suspend fun recordOpened(uri: Uri): PdfDocument

    suspend fun updateLastPage(uri: Uri, page: Int)

    suspend fun remove(id: Long)
}
