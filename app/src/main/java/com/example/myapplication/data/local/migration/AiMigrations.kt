package com.example.myapplication.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * schema v4 → v5：AI 配置、会话/运行、知识库索引与向量。
 *
 * DDL 由 Room 5.json 导出（createSql + indices），与《数据库迁移与查询契约》§3 v4→v5 一致。
 * knowledge_chunks_fts 为 FTS4 虚拟表，rowid 与 knowledge_chunks.id 对齐；
 * 删除触发器保证 chunk 删除时同步清理倒排索引。
 */
object AiMigrations {
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (statement in SCHEMA_V5) {
                db.execSQL(statement)
            }
            db.execSQL(DELETE_CHUNK_FTS_TRIGGER)
        }
    }

    /** 新装库同样需要该触发器（Room 不会为 FTS 实体生成触发器）。 */
    fun installFtsTriggers(db: SupportSQLiteDatabase) {
        db.execSQL(DELETE_CHUNK_FTS_TRIGGER)
    }

    private const val DELETE_CHUNK_FTS_TRIGGER = """
        CREATE TRIGGER IF NOT EXISTS delete_knowledge_chunk_fts
        AFTER DELETE ON knowledge_chunks
        BEGIN
            DELETE FROM knowledge_chunks_fts WHERE rowid = OLD.id;
        END
    """

    private val SCHEMA_V5 = listOf(
            "CREATE TABLE IF NOT EXISTS `ai_profiles` (`id` TEXT NOT NULL, `user_id` INTEGER NOT NULL, `purpose` TEXT NOT NULL, `protocol` TEXT NOT NULL, `endpoint` TEXT NOT NULL, `model` TEXT NOT NULL, `secret_ref` TEXT, `params_json` TEXT NOT NULL DEFAULT '{}', `capabilities_json` TEXT NOT NULL DEFAULT '{}', `capability_profile_hash` TEXT NOT NULL DEFAULT '', `enabled` INTEGER NOT NULL DEFAULT 0, `config_revision` INTEGER NOT NULL DEFAULT 1, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`user_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `idx_ai_profile_owner` ON `ai_profiles` (`user_id`, `purpose`, `updated_at`, `id`)",
            "CREATE TABLE IF NOT EXISTS `ai_user_settings` (`user_id` INTEGER NOT NULL, `active_chat_profile_id` TEXT, `active_embedding_profile_id` TEXT, `active_rerank_profile_id` TEXT, `active_search_profile_id` TEXT, `network_search_enabled` INTEGER NOT NULL DEFAULT 0, `budget_json` TEXT NOT NULL DEFAULT '{}', `settings_revision` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`user_id`), FOREIGN KEY(`user_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`active_chat_profile_id`) REFERENCES `ai_profiles`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`active_embedding_profile_id`) REFERENCES `ai_profiles`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`active_rerank_profile_id`) REFERENCES `ai_profiles`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`active_search_profile_id`) REFERENCES `ai_profiles`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
            "CREATE INDEX IF NOT EXISTS `idx_ai_settings_chat` ON `ai_user_settings` (`active_chat_profile_id`)",
            "CREATE INDEX IF NOT EXISTS `idx_ai_settings_embed` ON `ai_user_settings` (`active_embedding_profile_id`)",
            "CREATE INDEX IF NOT EXISTS `idx_ai_settings_rerank` ON `ai_user_settings` (`active_rerank_profile_id`)",
            "CREATE INDEX IF NOT EXISTS `idx_ai_settings_search` ON `ai_user_settings` (`active_search_profile_id`)",
            "CREATE TABLE IF NOT EXISTS `ai_consents` (`user_id` INTEGER NOT NULL, `purpose` TEXT NOT NULL, `endpoint_hash` TEXT NOT NULL, `allowed` INTEGER NOT NULL DEFAULT 0, `scope_json` TEXT NOT NULL, `policy_revision` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`user_id`, `purpose`, `endpoint_hash`), FOREIGN KEY(`user_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `ai_conversations` (`id` TEXT NOT NULL, `user_id` INTEGER NOT NULL, `document_id` TEXT, `title` TEXT NOT NULL, `workflow` TEXT NOT NULL DEFAULT 'CHAT', `scope_json` TEXT NOT NULL, `summary_json` TEXT, `archived_at` INTEGER, `last_order_key` INTEGER NOT NULL DEFAULT 0, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`user_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`document_id`) REFERENCES `reader_documents`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
            "CREATE INDEX IF NOT EXISTS `idx_ai_conversation_recent` ON `ai_conversations` (`user_id`, `workflow`, `updated_at`, `id`)",
            "CREATE INDEX IF NOT EXISTS `idx_ai_conversation_document` ON `ai_conversations` (`document_id`)",
            "CREATE TABLE IF NOT EXISTS `ai_messages` (`id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `message_json` TEXT NOT NULL DEFAULT '{}', `status` TEXT NOT NULL, `order_key` INTEGER NOT NULL, `sources_json` TEXT, `usage_json` TEXT, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`conversation_id`) REFERENCES `ai_conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `idx_ai_message_order` ON `ai_messages` (`conversation_id`, `order_key`)",
            "CREATE TABLE IF NOT EXISTS `ai_runs` (`id` TEXT NOT NULL, `user_id` INTEGER NOT NULL, `conversation_id` TEXT NOT NULL, `request_key` TEXT NOT NULL, `config_revision` INTEGER NOT NULL, `profile_snapshot_json` TEXT NOT NULL DEFAULT '{}', `scope_snapshot_json` TEXT NOT NULL, `status` TEXT NOT NULL, `active_slot` INTEGER, `rounds` INTEGER NOT NULL DEFAULT 0, `token_usage` INTEGER NOT NULL DEFAULT 0, `deadline_at` INTEGER, `last_event_seq` INTEGER NOT NULL DEFAULT 0, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`user_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`conversation_id`) REFERENCES `ai_conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `idx_ai_run_slot` ON `ai_runs` (`conversation_id`, `active_slot`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `idx_ai_run_request` ON `ai_runs` (`user_id`, `request_key`)",
            "CREATE INDEX IF NOT EXISTS `idx_ai_run_owner` ON `ai_runs` (`user_id`, `status`, `id`)",
            "CREATE TABLE IF NOT EXISTS `ai_run_events` (`run_id` TEXT NOT NULL, `seq` INTEGER NOT NULL, `type` TEXT NOT NULL, `tool_call_id` TEXT, `payload_json` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`run_id`, `seq`), FOREIGN KEY(`run_id`) REFERENCES `ai_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `ai_tool_executions` (`run_id` TEXT NOT NULL, `tool_call_id` TEXT NOT NULL, `tool_name` TEXT NOT NULL, `arguments_hash` TEXT NOT NULL, `status` TEXT NOT NULL, `result_json` TEXT, `side_effect_key` TEXT, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`run_id`, `tool_call_id`), FOREIGN KEY(`run_id`) REFERENCES `ai_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `ai_note_proposals` (`id` TEXT NOT NULL, `run_id` TEXT NOT NULL, `user_id` INTEGER NOT NULL, `target_kind` TEXT NOT NULL, `target_id` TEXT NOT NULL, `base_revision` INTEGER NOT NULL, `read_receipt_id` TEXT NOT NULL, `read_receipt_json` TEXT NOT NULL DEFAULT '{}', `patch_json` TEXT NOT NULL, `preview_hash` TEXT NOT NULL, `status` TEXT NOT NULL, `resolved_at` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`run_id`) REFERENCES `ai_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`user_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `idx_ai_proposal_run` ON `ai_note_proposals` (`run_id`)",
            "CREATE INDEX IF NOT EXISTS `idx_ai_proposal_owner` ON `ai_note_proposals` (`user_id`, `status`, `id`)",
            "CREATE TABLE IF NOT EXISTS `knowledge_documents` (`id` TEXT NOT NULL, `user_id` INTEGER NOT NULL, `document_id` TEXT NOT NULL, `source_revision` TEXT NOT NULL, `requested_generation` INTEGER NOT NULL DEFAULT 1, `active_generation` INTEGER, `lexical_state` TEXT NOT NULL DEFAULT 'PENDING', `vector_state` TEXT NOT NULL DEFAULT 'PENDING', `access_epoch` INTEGER NOT NULL DEFAULT 1, `error_code` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`user_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`document_id`) REFERENCES `reader_documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `idx_knowledge_document_owner` ON `knowledge_documents` (`user_id`, `document_id`)",
            "CREATE INDEX IF NOT EXISTS `idx_knowledge_document_source` ON `knowledge_documents` (`document_id`)",
            "CREATE INDEX IF NOT EXISTS `idx_knowledge_document_state` ON `knowledge_documents` (`user_id`, `lexical_state`, `id`)",
            "CREATE TABLE IF NOT EXISTS `knowledge_chunks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `knowledge_document_id` TEXT NOT NULL, `generation` INTEGER NOT NULL, `section_id` TEXT NOT NULL, `block_ids_json` TEXT NOT NULL, `parent_key` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `raw_text` TEXT NOT NULL, `search_text` TEXT NOT NULL, `embedding_text` TEXT NOT NULL, `content_hash` TEXT NOT NULL, `locator_json` TEXT NOT NULL, `token_count` INTEGER NOT NULL, `extractor_version` TEXT NOT NULL, `chunker_version` TEXT NOT NULL, FOREIGN KEY(`knowledge_document_id`) REFERENCES `knowledge_documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`section_id`) REFERENCES `reader_sections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `idx_knowledge_chunk_ordinal` ON `knowledge_chunks` (`knowledge_document_id`, `generation`, `ordinal`)",
            "CREATE INDEX IF NOT EXISTS `idx_knowledge_chunk_scan` ON `knowledge_chunks` (`knowledge_document_id`, `generation`, `id`)",
            "CREATE INDEX IF NOT EXISTS `idx_knowledge_chunk_section` ON `knowledge_chunks` (`section_id`)",
            "CREATE VIRTUAL TABLE IF NOT EXISTS `knowledge_chunks_fts` USING FTS4(`title_terms` TEXT NOT NULL, `heading_terms` TEXT NOT NULL, `body_terms` TEXT NOT NULL, `user_scope` TEXT NOT NULL, tokenize=unicode61)",
            "CREATE TABLE IF NOT EXISTS `knowledge_embeddings` (`chunk_id` INTEGER NOT NULL, `profile_fingerprint` TEXT NOT NULL, `dim` INTEGER NOT NULL, `encoding` TEXT NOT NULL DEFAULT 'FLOAT32', `vector_blob` BLOB NOT NULL, `norm` REAL NOT NULL, `quantization_params` TEXT, `created_at` INTEGER NOT NULL, PRIMARY KEY(`chunk_id`, `profile_fingerprint`), FOREIGN KEY(`chunk_id`) REFERENCES `knowledge_chunks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `idx_knowledge_embedding_profile` ON `knowledge_embeddings` (`profile_fingerprint`, `chunk_id`)",
            "CREATE TABLE IF NOT EXISTS `knowledge_vector_states` (`knowledge_document_id` TEXT NOT NULL, `generation` INTEGER NOT NULL, `profile_fingerprint` TEXT NOT NULL, `expected_count` INTEGER NOT NULL DEFAULT 0, `ready_count` INTEGER NOT NULL DEFAULT 0, `state` TEXT NOT NULL DEFAULT 'PENDING', `updated_at` INTEGER NOT NULL, PRIMARY KEY(`knowledge_document_id`, `generation`, `profile_fingerprint`), FOREIGN KEY(`knowledge_document_id`) REFERENCES `knowledge_documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `knowledge_index_jobs` (`id` TEXT NOT NULL, `user_id` INTEGER NOT NULL, `knowledge_document_id` TEXT NOT NULL, `phase` TEXT NOT NULL, `target_revision` TEXT NOT NULL, `requested_generation` INTEGER NOT NULL, `profile_fingerprint` TEXT NOT NULL DEFAULT '', `status` TEXT NOT NULL, `lease_token` TEXT, `lease_until` INTEGER, `attempts` INTEGER NOT NULL DEFAULT 0, `next_retry_at` INTEGER, `last_error_code` TEXT, `progress_json` TEXT NOT NULL DEFAULT '{}', `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`user_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`knowledge_document_id`) REFERENCES `knowledge_documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `idx_knowledge_job_identity` ON `knowledge_index_jobs` (`knowledge_document_id`, `requested_generation`, `phase`, `profile_fingerprint`)",
            "CREATE INDEX IF NOT EXISTS `idx_knowledge_job_owner` ON `knowledge_index_jobs` (`user_id`, `status`, `id`)",
            "CREATE INDEX IF NOT EXISTS `idx_knowledge_job_due` ON `knowledge_index_jobs` (`status`, `next_retry_at`, `id`)",
            "CREATE TABLE IF NOT EXISTS `knowledge_ann_manifests` (`id` TEXT NOT NULL, `user_id` INTEGER NOT NULL, `profile_fingerprint` TEXT NOT NULL, `dim` INTEGER NOT NULL, `source_generation_hash` TEXT NOT NULL, `blob_path` TEXT NOT NULL, `checksum` TEXT NOT NULL, `status` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`user_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `idx_ann_manifest_profile` ON `knowledge_ann_manifests` (`user_id`, `profile_fingerprint`, `status`)",
    )
}
