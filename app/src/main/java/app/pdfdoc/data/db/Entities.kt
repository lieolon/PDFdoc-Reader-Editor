package app.pdfdoc.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "documents", indices = [Index(value = ["uri"], unique = true)])
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val name: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "page_count") val pageCount: Int,
    @ColumnInfo(name = "last_opened_at") val lastOpenedAt: Long,
    @ColumnInfo(name = "last_page") val lastPage: Int = 0,
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String? = null,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean = false,
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("document_id")],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "document_id") val documentId: Long,
    val page: Int,
    val title: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "signatures")
data class SignatureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** A user folder for organizing documents; a document can be in several. */
@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Entity(
    tableName = "document_collections",
    primaryKeys = ["document_id", "collection_id"],
    foreignKeys = [
        ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["document_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CollectionEntity::class, parentColumns = ["id"], childColumns = ["collection_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("collection_id")],
)
data class DocumentCollectionEntity(
    @ColumnInfo(name = "document_id") val documentId: Long,
    @ColumnInfo(name = "collection_id") val collectionId: Long,
)

/** Lower-cased text of a document for searching across the library. */
@Entity(
    tableName = "document_text",
    foreignKeys = [
        ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["document_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class DocumentTextEntity(
    @PrimaryKey @ColumnInfo(name = "document_id") val documentId: Long,
    /** Changes when the file changes, so the text is extracted again. */
    val signature: String,
    val text: String,
)
