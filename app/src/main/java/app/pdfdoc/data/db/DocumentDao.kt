package app.pdfdoc.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY last_opened_at DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): DocumentEntity?

    @Upsert
    suspend fun upsert(entity: DocumentEntity): Long

    @Query("UPDATE documents SET last_page = :page WHERE uri = :uri")
    suspend fun updateLastPage(uri: String, page: Int)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun delete(id: Long)
}
