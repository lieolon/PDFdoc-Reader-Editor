package app.pdfdoc.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        DocumentEntity::class, BookmarkEntity::class, SignatureEntity::class,
        CollectionEntity::class, DocumentCollectionEntity::class, DocumentTextEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun signatureDao(): SignatureDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun libraryDao(): LibraryDao

    companion object {
        /** Version 2 adds folders and the full-text search table. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `collections` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `document_collections` (`document_id` INTEGER NOT NULL, `collection_id` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`document_id`, `collection_id`), " +
                        "FOREIGN KEY(`document_id`) REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`collection_id`) REFERENCES `collections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_document_collections_collection_id` ON `document_collections` (`collection_id`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `document_text` (`document_id` INTEGER NOT NULL, `signature` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                        "PRIMARY KEY(`document_id`), FOREIGN KEY(`document_id`) REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
            }
        }
    }
}
