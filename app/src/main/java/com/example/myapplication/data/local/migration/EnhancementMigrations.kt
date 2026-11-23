package com.example.myapplication.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Schema 6 to 7: add structure only. No assets, network, or legacy-row updates. */
object EnhancementMigrations {
    val MIGRATION_6_7: Migration = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (statement in SCHEMA_V7) db.execSQL(statement)
        }
    }

    private val SCHEMA_V7 = listOf(
        "CREATE TABLE IF NOT EXISTS course_books ( book_id TEXT NOT NULL PRIMARY KEY, content_key TEXT NOT NULL, title TEXT NOT NULL, summary TEXT, cover_asset TEXT, order_index INTEGER NOT NULL, package_id TEXT NOT NULL, content_version INTEGER NOT NULL, review_status TEXT NOT NULL )",
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_course_book_key ON course_books(content_key)",
        "CREATE TABLE IF NOT EXISTS course_book_chapters ( book_id TEXT NOT NULL, chapter_key TEXT NOT NULL, parent_key TEXT, order_index INTEGER NOT NULL, document_id TEXT NOT NULL, section_id TEXT, legacy_chapter_id INTEGER, enabled INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(book_id, chapter_key), FOREIGN KEY(book_id) REFERENCES course_books(book_id) ON DELETE CASCADE, FOREIGN KEY(document_id) REFERENCES reader_documents(id) ON DELETE RESTRICT, FOREIGN KEY(section_id) REFERENCES reader_sections(id) ON DELETE SET NULL, FOREIGN KEY(legacy_chapter_id) REFERENCES chapters(id) ON DELETE RESTRICT )",
        "CREATE INDEX IF NOT EXISTS idx_course_chapter_order ON course_book_chapters(book_id, order_index, chapter_key)",
        "CREATE INDEX IF NOT EXISTS idx_course_chapter_document ON course_book_chapters(document_id)",
        "CREATE INDEX IF NOT EXISTS idx_course_chapter_section ON course_book_chapters(section_id)",
        "CREATE INDEX IF NOT EXISTS idx_course_chapter_legacy ON course_book_chapters(legacy_chapter_id)",
        "CREATE TABLE IF NOT EXISTS reader_annotation_targets ( target_id TEXT NOT NULL PRIMARY KEY, annotation_id TEXT NOT NULL, ordinal INTEGER NOT NULL, locator_json TEXT NOT NULL, anchor_version INTEGER NOT NULL DEFAULT 1, precision TEXT NOT NULL, FOREIGN KEY(annotation_id) REFERENCES reader_annotations(id) ON DELETE CASCADE )",
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_annotation_target_order ON reader_annotation_targets(annotation_id, ordinal)",
    )
}
