package com.example.myapplication.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.myapplication.data.local.dao.ChapterDao
import com.example.myapplication.data.local.dao.FavoriteDao
import com.example.myapplication.data.local.dao.QuestionDao
import com.example.myapplication.data.local.dao.StudyProgressDao
import com.example.myapplication.data.local.dao.TestAnswerDao
import com.example.myapplication.data.local.dao.TestRecordDao
import com.example.myapplication.data.local.dao.UserDao
import com.example.myapplication.data.local.entity.ChapterEntity
import com.example.myapplication.data.local.entity.FavoriteEntity
import com.example.myapplication.data.local.entity.QuestionEntity
import com.example.myapplication.data.local.entity.StudyProgressEntity
import com.example.myapplication.data.local.entity.TestAnswerEntity
import com.example.myapplication.data.local.entity.TestRecordEntity
import com.example.myapplication.data.local.entity.UserEntity

@Database(
    entities = [
        UserEntity::class,
        ChapterEntity::class,
        QuestionEntity::class,
        TestRecordEntity::class,
        TestAnswerEntity::class,
        FavoriteEntity::class,
        StudyProgressEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao
    abstract fun chapterDao(): ChapterDao
    abstract fun questionDao(): QuestionDao
    abstract fun testRecordDao(): TestRecordDao
    abstract fun testAnswerDao(): TestAnswerDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun studyProgressDao(): StudyProgressDao

    companion object {
        private const val DB_NAME = "acupuncture.db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE users ADD COLUMN avatar_id INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE users ADD COLUMN avatar_path TEXT")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .addCallback(object : Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            db.execSQL("PRAGMA foreign_keys = ON")
                        }
                    })
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
