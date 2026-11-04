package com.wearos.ancsbridge.contacts

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.wearos.ancsbridge.messages.MessageDao
import com.wearos.ancsbridge.messages.MessageEntity

/**
 * Room database instance for WearBridge Wear OS application.
 */
@Database(
    entities = [SyncedContact::class, MessageEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun syncedContactDao(): SyncedContactDao
    abstract fun messageDao(): MessageDao

    companion object {
        private const val DB_NAME = "wearbridge_database"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        ancsUid INTEGER NOT NULL,
                        threadKey TEXT NOT NULL,
                        appBundleId TEXT NOT NULL,
                        appDisplayName TEXT NOT NULL,
                        senderName TEXT NOT NULL,
                        senderIdentifier TEXT NOT NULL,
                        body TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        isOutgoing INTEGER NOT NULL,
                        deliveryStatus TEXT NOT NULL,
                        isRead INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_threadKey ON messages (threadKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_timestamp ON messages (timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_ancsUid ON messages (ancsUid)")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

