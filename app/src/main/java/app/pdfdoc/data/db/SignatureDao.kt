package app.pdfdoc.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SignatureDao {
    @Query("SELECT * FROM signatures ORDER BY created_at DESC")
    fun observeAll(): Flow<List<SignatureEntity>>

    @Query("SELECT * FROM signatures WHERE id = :id LIMIT 1")
    suspend fun find(id: Long): SignatureEntity?

    @Insert
    suspend fun insert(entity: SignatureEntity): Long

    @Query("DELETE FROM signatures WHERE id = :id")
    suspend fun delete(id: Long)
}
