package com.bennybar.mypapers.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents WHERE deleted = 0")
    fun observeAll(): Flow<List<Document>>

    @Query("SELECT * FROM documents WHERE id = :id AND deleted = 0")
    fun observe(id: String): Flow<Document?>

    /** Including rows deleted locally but not yet pushed. */
    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun get(id: String): Document?

    @Query("SELECT * FROM documents WHERE deleted = 0 AND fileHash = :hash LIMIT 1")
    suspend fun findByHash(hash: String): Document?

    @Query("SELECT * FROM documents WHERE deleted = 0")
    suspend fun all(): List<Document>

    @Query("SELECT * FROM documents WHERE dirty = 1")
    suspend fun dirty(): List<Document>

    @Query("SELECT * FROM documents WHERE deleted = 0 AND fileUploaded = 1 AND fileLocal = 0")
    suspend fun missingFiles(): List<Document>

    @Upsert
    suspend fun upsert(doc: Document)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun remove(id: String)

    /**
     * After a push: record the server's revision, and clear [Document.dirty]
     * only if nothing was edited meanwhile (the edit gets the next push).
     */
    @Query("UPDATE documents SET rev = :rev, fileUploaded = :fileUploaded, dirty = CASE WHEN updatedAt = :pushedUpdatedAt THEN 0 ELSE dirty END WHERE id = :id")
    suspend fun markPushed(id: String, rev: String, fileUploaded: Boolean, pushedUpdatedAt: Long)

    @Query("UPDATE documents SET rev = :rev WHERE id = :id")
    suspend fun setRev(id: String, rev: String?)

    @Query("UPDATE documents SET fileLocal = 1 WHERE id = :id")
    suspend fun markFileLocal(id: String)

    @Query("DELETE FROM documents")
    suspend fun clear()

    /** Syncing into another database: everything here is new to it. */
    @Query("UPDATE documents SET rev = NULL, dirty = 1, fileUploaded = 0")
    suspend fun resetSync()
}

class Converters {
    @TypeConverter fun tagsToJson(tags: List<String>): String = Json.encodeToString(tags)
    @TypeConverter fun tagsFromJson(json: String): List<String> = Json.decodeFromString(json)
}

@Database(entities = [Document::class], version = 2)
@TypeConverters(Converters::class)
abstract class PapersDatabase : RoomDatabase() {
    abstract fun documents(): DocumentDao

    companion object {
        fun open(context: Context): PapersDatabase =
            Room.databaseBuilder(context, PapersDatabase::class.java, "papers.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        /** v2: AI reading state and duplicate marking. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN aiState TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE documents ADD COLUMN aiError TEXT")
                db.execSQL("ALTER TABLE documents ADD COLUMN duplicateOf TEXT")
            }
        }
    }
}
