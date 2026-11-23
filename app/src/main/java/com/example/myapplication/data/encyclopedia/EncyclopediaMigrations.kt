package com.example.myapplication.data.encyclopedia

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object EncyclopediaMigrations {
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `encyclopedia_favorites` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `user_id` INTEGER NOT NULL,
                    `point_code` TEXT NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    FOREIGN KEY(`user_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_encyclopedia_favorites_user_id_point_code` " +
                    "ON `encyclopedia_favorites` (`user_id`, `point_code`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_encyclopedia_favorites_user_id` " +
                    "ON `encyclopedia_favorites` (`user_id`)",
            )
        }
    }
}
