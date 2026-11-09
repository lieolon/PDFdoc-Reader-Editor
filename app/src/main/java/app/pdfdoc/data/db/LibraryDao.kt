package app.pdfdoc.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM collections ORDER BY name COLLATE NOCASE")
    fun observeCollections(): Flow<List<CollectionEntity>>

    @Insert
    suspend fun insertCollection(entity: CollectionEntity): Long

    @Query("UPDATE collections SET name = :name WHERE id = :id")
    suspend fun renameCollection(id: Long, name: String)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun deleteCollection(id: Long)

    @Query("SELECT * FROM document_collections")
    fun observeMemberships(): Flow<List<DocumentCollectionEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addToCollection(entity: DocumentCollectionEntity)

    @Query("DELETE FROM document_collections WHERE document_id = :documentId AND collection_id = :collectionId")
    suspend fun removeFromCollection(documentId: Long, collectionId: Long)

    @Query("UPDATE documents SET is_secret = :secret WHERE id = :id")
    suspend fun setSecret(id: Long, secret: Boolean)

    @Query("UPDATE documents SET is_favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("SELECT signature FROM document_text WHERE document_id = :documentId")
    suspend fun textSignature(documentId: Long): String?

    @Upsert
    suspend fun upsertText(entity: DocumentTextEntity)

    /** Documents whose text contains [needle] (already lower-cased). */
    @Query("SELECT * FROM document_text WHERE instr(text, :needle) > 0 LIMIT 50")
    suspend fun searchText(needle: String): List<DocumentTextEntity>
}
