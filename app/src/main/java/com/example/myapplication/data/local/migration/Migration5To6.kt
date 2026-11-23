package com.example.myapplication.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * schema v5 → v6：随堂测与学习活动（P4-01 / 契约 §4 v5→v6 DDL）。
 *
 * 只新增 quiz_papers、quiz_items、quiz_attempts、quiz_attempt_answers、
 * quiz_grade_events、learning_activity_events、learning_activity_days 七张表；
 * 不修改 v1→v5 的任何既有表与数据（无破坏性回退）。
 * DDL 由 Room 导出的 6.json createSql/indices 逐字生成，保证新装与升级后 TableInfo 一致。
 */
object QuizMigrations {
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (statement in SCHEMA_V6) {
                db.execSQL(statement)
            }
        }
    }

    private val SCHEMA_V6 = listOf(
        "CREATE TABLE IF NOT EXISTS `quiz_papers` (`id` TEXT NOT NULL, `user_id` INTEGER NOT NULL, `document_id` TEXT, `scope_json` TEXT NOT NULL, `source_revision` TEXT NOT NULL, `template_version` TEXT NOT NULL, `question_count` INTEGER NOT NULL, `max_score` INTEGER NOT NULL, `status` TEXT NOT NULL, `generation_run_id` TEXT, `generation_coverage_json` TEXT NOT NULL, `generated_by` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`user_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`document_id`) REFERENCES `reader_documents`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`generation_run_id`) REFERENCES `ai_runs`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        "CREATE INDEX IF NOT EXISTS `idx_quiz_paper_recent` ON `quiz_papers` (`user_id`, `created_at`, `id`)",
        "CREATE INDEX IF NOT EXISTS `idx_quiz_paper_document` ON `quiz_papers` (`document_id`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `idx_quiz_paper_generation` ON `quiz_papers` (`generation_run_id`)",
        "CREATE TABLE IF NOT EXISTS `quiz_items` (`id` TEXT NOT NULL, `paper_id` TEXT NOT NULL, `question_id` INTEGER NOT NULL, `ordinal` INTEGER NOT NULL, `question_snapshot_json` TEXT NOT NULL, `rubric_json` TEXT NOT NULL, `evidence_json` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`paper_id`) REFERENCES `quiz_papers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`question_id`) REFERENCES `questions`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `idx_quiz_item_ordinal` ON `quiz_items` (`paper_id`, `ordinal`)",
        "CREATE INDEX IF NOT EXISTS `idx_quiz_item_question` ON `quiz_items` (`question_id`)",
        "CREATE TABLE IF NOT EXISTS `quiz_attempts` (`id` TEXT NOT NULL, `paper_id` TEXT NOT NULL, `user_id` INTEGER NOT NULL, `status` TEXT NOT NULL, `current_ordinal` INTEGER NOT NULL DEFAULT 0, `started_at` INTEGER NOT NULL, `active_used_ms` INTEGER NOT NULL DEFAULT 0, `submitted_at` INTEGER, `total_score` INTEGER, `grading_status` TEXT NOT NULL, `submit_key` TEXT NOT NULL, `revision` INTEGER NOT NULL DEFAULT 0, `grade_revision` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`), FOREIGN KEY(`paper_id`) REFERENCES `quiz_papers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`user_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `idx_quiz_attempt_submit` ON `quiz_attempts` (`submit_key`)",
        "CREATE INDEX IF NOT EXISTS `idx_quiz_attempt_paper` ON `quiz_attempts` (`paper_id`, `user_id`, `started_at`, `id`)",
        "CREATE INDEX IF NOT EXISTS `idx_quiz_attempt_user` ON `quiz_attempts` (`user_id`, `status`, `id`)",
        "CREATE TABLE IF NOT EXISTS `quiz_attempt_answers` (`attempt_id` TEXT NOT NULL, `item_id` TEXT NOT NULL, `raw_answer_json` TEXT, `edit_sequence` INTEGER NOT NULL DEFAULT 0, `score` INTEGER, `is_correct` INTEGER, `matched_rubric_json` TEXT NOT NULL DEFAULT '{}', `grading_method` TEXT NOT NULL DEFAULT 'NONE', `feedback` TEXT NOT NULL DEFAULT '', `updated_at` INTEGER NOT NULL, PRIMARY KEY(`attempt_id`, `item_id`), FOREIGN KEY(`attempt_id`) REFERENCES `quiz_attempts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`item_id`) REFERENCES `quiz_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `idx_quiz_answer_item` ON `quiz_attempt_answers` (`item_id`)",
        "CREATE TABLE IF NOT EXISTS `quiz_grade_events` (`id` TEXT NOT NULL, `attempt_id` TEXT NOT NULL, `item_id` TEXT NOT NULL, `assessment_key` TEXT NOT NULL, `grade_revision` INTEGER NOT NULL, `old_assessment_json` TEXT, `new_assessment_json` TEXT NOT NULL, `reason` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`attempt_id`) REFERENCES `quiz_attempts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`item_id`) REFERENCES `quiz_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `idx_quiz_grade_key` ON `quiz_grade_events` (`attempt_id`, `assessment_key`)",
        "CREATE INDEX IF NOT EXISTS `idx_quiz_grade_item` ON `quiz_grade_events` (`item_id`)",
        "CREATE TABLE IF NOT EXISTS `learning_activity_events` (`id` TEXT NOT NULL, `user_id` INTEGER NOT NULL, `dedup_key` TEXT NOT NULL, `kind` TEXT NOT NULL, `local_date` TEXT NOT NULL, `timezone_id` TEXT NOT NULL, `reading_delta_ms` INTEGER NOT NULL DEFAULT 0, `quiz_delta` INTEGER NOT NULL DEFAULT 0, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`user_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `idx_learning_event_dedup` ON `learning_activity_events` (`user_id`, `dedup_key`)",
        "CREATE INDEX IF NOT EXISTS `idx_learning_event_date` ON `learning_activity_events` (`user_id`, `local_date`)",
        "CREATE TABLE IF NOT EXISTS `learning_activity_days` (`user_id` INTEGER NOT NULL, `local_date` TEXT NOT NULL, `timezone_id` TEXT NOT NULL, `reading_ms` INTEGER NOT NULL DEFAULT 0, `quiz_count` INTEGER NOT NULL DEFAULT 0, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`user_id`, `local_date`), FOREIGN KEY(`user_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    )
}
