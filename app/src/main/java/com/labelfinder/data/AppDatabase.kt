package com.labelfinder.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SearchHistory::class, AppSettings::class],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun appSettingsDao(): AppSettingsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_settings ADD COLUMN prefixes TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN suffixes TEXT NOT NULL DEFAULT ''")
                // Migrate old stripChars: convert each character to a prefix and suffix entry
                val cursor = db.query("SELECT id, stripChars FROM app_settings")
                while (cursor.moveToNext()) {
                    val id = cursor.getInt(0)
                    val oldChars = cursor.getString(1) ?: ""
                    if (oldChars.isNotEmpty()) {
                        val entries = oldChars.toList().map { it.toString() }.joinToString("|")
                        db.execSQL("UPDATE app_settings SET prefixes = ?, suffixes = ? WHERE id = ?",
                            arrayOf<Any>(entries, entries, id))
                    }
                }
                cursor.close()
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_settings ADD COLUMN partialMatch INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "labelfinder.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { INSTANCE = it }
            }
        }
    }
}
