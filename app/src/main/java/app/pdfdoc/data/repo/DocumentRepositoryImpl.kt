package app.pdfdoc.data.repo

import android.graphics.Bitmap
import android.net.Uri
import app.pdfdoc.core.IoDispatcher
import app.pdfdoc.data.db.DocumentDao
import app.pdfdoc.data.db.DocumentEntity
import app.pdfdoc.data.files.FileStore
import app.pdfdoc.data.pdf.PdfRenderSessionFactory
import app.pdfdoc.domain.model.PdfDocument
import app.pdfdoc.domain.repo.DocumentRepository
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private const val THUMB_WIDTH_PX = 320

class DocumentRepositoryImpl @Inject constructor(
    private val dao: DocumentDao,
    private val files: FileStore,
    private val renderers: PdfRenderSessionFactory,
    @IoDispatcher private val io: CoroutineDispatcher,
) : DocumentRepository {

    override fun observeRecent(): Flow<List<PdfDocument>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun recordOpened(uri: Uri): PdfDocument = withContext(io) {
        files.takePersistablePermission(uri)
        val info = files.query(uri)
        val existing = dao.findByUri(uri.toString())
        val (pageCount, thumb) = renderers.open(uri).use { session ->
            session.pageCount to session.render(0, THUMB_WIDTH_PX)
        }
        val entity = DocumentEntity(
            id = existing?.id ?: 0,
            uri = uri.toString(),
            name = info.name,
            sizeBytes = info.sizeBytes,
            pageCount = pageCount,
            lastOpenedAt = System.currentTimeMillis(),
            lastPage = existing?.lastPage?.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) ?: 0,
            thumbnailPath = existing?.thumbnailPath,
            isFavorite = existing?.isFavorite ?: false,
        )
        val id = dao.upsert(entity).takeIf { it > 0 } ?: entity.id
        val thumbPath = thumb?.let { saveThumbnail(id, it) } ?: entity.thumbnailPath
        val saved = entity.copy(id = id, thumbnailPath = thumbPath)
        dao.upsert(saved)
        saved.toDomain()
    }

    override suspend fun updateLastPage(uri: Uri, page: Int) = withContext(io) {
        dao.updateLastPage(uri.toString(), page)
    }

    override suspend fun remove(id: Long) = withContext(io) {
        dao.findById(id)?.thumbnailPath?.let { File(it).delete() }
        dao.delete(id)
    }

    private fun saveThumbnail(id: Long, bitmap: Bitmap): String {
        val file = File(files.thumbDir, "$id.webp")
        file.outputStream().use {
            @Suppress("DEPRECATION")
            bitmap.compress(Bitmap.CompressFormat.WEBP, 80, it)
        }
        bitmap.recycle()
        return file.absolutePath
    }
}

private fun DocumentEntity.toDomain() = PdfDocument(
    id = id,
    uri = uri,
    name = name,
    sizeBytes = sizeBytes,
    pageCount = pageCount,
    lastOpenedAt = lastOpenedAt,
    lastPage = lastPage,
    thumbnailPath = thumbnailPath,
)
