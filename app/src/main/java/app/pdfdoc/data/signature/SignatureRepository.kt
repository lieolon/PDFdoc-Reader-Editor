package app.pdfdoc.data.signature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.pdfdoc.core.IoDispatcher
import app.pdfdoc.data.db.SignatureDao
import app.pdfdoc.data.db.SignatureEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Saved handwritten signatures: transparent PNGs encrypted at rest. */
@Singleton
class SignatureRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: SignatureDao,
    private val cipher: SignatureCipher,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val dir get() = File(context.filesDir, "signatures").apply { mkdirs() }

    fun observeIds(): Flow<List<Long>> = dao.observeAll().map { list -> list.map { it.id } }

    suspend fun save(bitmap: Bitmap): Long = withContext(io) {
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val name = "${UUID.randomUUID()}.bin"
        File(dir, name).writeBytes(cipher.encrypt(png))
        dao.insert(SignatureEntity(fileName = name, createdAt = System.currentTimeMillis()))
    }

    suspend fun load(id: Long): Bitmap? = withContext(io) {
        val entity = dao.find(id) ?: return@withContext null
        runCatching {
            val bytes = cipher.decrypt(File(dir, entity.fileName).readBytes())
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    suspend fun delete(id: Long) = withContext(io) {
        dao.find(id)?.let { File(dir, it.fileName).delete() }
        dao.delete(id)
    }
}
