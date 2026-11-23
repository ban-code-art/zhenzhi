package com.example.myapplication.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object ReaderMigrations {
    private val CREATE_READER_TABLES = """
        CREATE TABLE IF NOT EXISTS reader_documents (
            id TEXT NOT NULL PRIMARY KEY,
            owner_user_id INTEGER,
            kind TEXT NOT NULL,
            legacy_chapter_id INTEGER,
            format TEXT NOT NULL,
            title TEXT NOT NULL,
            author TEXT,
            blob_path TEXT,
            file_sha256 TEXT,
            content_revision TEXT NOT NULL,
            extractor_version TEXT NOT NULL,
            metadata_revision INTEGER NOT NULL DEFAULT 1,
            access_epoch INTEGER NOT NULL DEFAULT 1,
            import_status TEXT NOT NULL DEFAULT 'PENDING',
            deleted_at INTEGER,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            FOREIGN KEY(owner_user_id) REFERENCES users(id) ON DELETE CASCADE,
            FOREIGN KEY(legacy_chapter_id) REFERENCES chapters(id) ON DELETE RESTRICT
        );

        CREATE UNIQUE INDEX IF NOT EXISTS idx_reader_document_legacy
            ON reader_documents(legacy_chapter_id);
        CREATE UNIQUE INDEX IF NOT EXISTS idx_reader_document_hash
            ON reader_documents(owner_user_id, file_sha256);
        CREATE INDEX IF NOT EXISTS idx_reader_document_owner
            ON reader_documents(owner_user_id, deleted_at, updated_at, id);

        CREATE TABLE IF NOT EXISTS reader_content_revisions (
            document_id TEXT NOT NULL,
            revision TEXT NOT NULL,
            source_sha256 TEXT,
            source_blob_path TEXT,
            manifest_path TEXT NOT NULL,
            extractor_version TEXT NOT NULL,
            status TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            PRIMARY KEY(document_id, revision),
            FOREIGN KEY(document_id) REFERENCES reader_documents(id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS index_reader_content_revisions_document_id
            ON reader_content_revisions(document_id);

        CREATE TABLE IF NOT EXISTS reader_sections (
            id TEXT NOT NULL PRIMARY KEY,
            document_id TEXT NOT NULL,
            content_revision TEXT NOT NULL,
            parent_id TEXT,
            order_index INTEGER NOT NULL,
            title TEXT NOT NULL,
            depth INTEGER NOT NULL DEFAULT 0,
            resource_href TEXT NOT NULL,
            canonical_path TEXT NOT NULL,
            char_count INTEGER NOT NULL DEFAULT 0,
            page_start INTEGER,
            page_end INTEGER,
            FOREIGN KEY(document_id, content_revision)
                REFERENCES reader_content_revisions(document_id, revision) ON DELETE CASCADE,
            FOREIGN KEY(parent_id) REFERENCES reader_sections(id) ON DELETE SET NULL
        );
        CREATE UNIQUE INDEX IF NOT EXISTS idx_reader_section_resource
            ON reader_sections(document_id, content_revision, resource_href);
        CREATE INDEX IF NOT EXISTS idx_reader_section_order
            ON reader_sections(document_id, content_revision, order_index, id);
        CREATE INDEX IF NOT EXISTS idx_reader_section_parent
            ON reader_sections(parent_id);
        CREATE INDEX IF NOT EXISTS index_reader_sections_document_id_content_revision
            ON reader_sections(document_id, content_revision);

        CREATE TABLE IF NOT EXISTS reader_outline_nodes (
            id TEXT NOT NULL PRIMARY KEY,
            document_id TEXT NOT NULL,
            content_revision TEXT NOT NULL,
            node_key TEXT NOT NULL,
            parent_id TEXT,
            order_index INTEGER NOT NULL,
            title TEXT NOT NULL,
            target_section_id TEXT,
            target_locator_json TEXT,
            FOREIGN KEY(document_id, content_revision)
                REFERENCES reader_content_revisions(document_id, revision) ON DELETE CASCADE,
            FOREIGN KEY(parent_id) REFERENCES reader_outline_nodes(id) ON DELETE SET NULL,
            FOREIGN KEY(target_section_id) REFERENCES reader_sections(id) ON DELETE SET NULL
        );
        CREATE UNIQUE INDEX IF NOT EXISTS idx_reader_outline_key
            ON reader_outline_nodes(document_id, content_revision, node_key);
        CREATE INDEX IF NOT EXISTS idx_reader_outline_order
            ON reader_outline_nodes(document_id, content_revision, order_index, id);
        CREATE INDEX IF NOT EXISTS idx_reader_outline_parent
            ON reader_outline_nodes(parent_id);
        CREATE INDEX IF NOT EXISTS idx_reader_outline_section
            ON reader_outline_nodes(target_section_id);
        CREATE INDEX IF NOT EXISTS index_reader_outline_nodes_document_id_content_revision
            ON reader_outline_nodes(document_id, content_revision);

        CREATE TABLE IF NOT EXISTS reader_states (
            user_id INTEGER NOT NULL,
            document_id TEXT NOT NULL,
            locator_json TEXT,
            settings_json TEXT,
            is_completed INTEGER NOT NULL DEFAULT 0,
            is_hidden INTEGER NOT NULL DEFAULT 0,
            display_title_override TEXT,
            last_read_at INTEGER NOT NULL DEFAULT 0,
            writer_epoch INTEGER NOT NULL DEFAULT 0,
            last_sequence INTEGER NOT NULL DEFAULT 0,
            revision INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(user_id, document_id),
            FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
            FOREIGN KEY(document_id) REFERENCES reader_documents(id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS idx_reader_state_recent
            ON reader_states(user_id, last_read_at, document_id);
        CREATE INDEX IF NOT EXISTS idx_reader_state_document
            ON reader_states(document_id);

        CREATE TABLE IF NOT EXISTS reader_annotations (
            id TEXT NOT NULL PRIMARY KEY,
            user_id INTEGER NOT NULL,
            document_id TEXT NOT NULL,
            section_id TEXT,
            kind TEXT NOT NULL,
            locator_json TEXT NOT NULL,
            quote_snapshot TEXT,
            note_text TEXT,
            style_json TEXT NOT NULL DEFAULT '{}',
            ink_blob_path TEXT,
            revision INTEGER NOT NULL DEFAULT 1,
            deleted_at INTEGER,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
            FOREIGN KEY(document_id) REFERENCES reader_documents(id) ON DELETE CASCADE,
            FOREIGN KEY(section_id) REFERENCES reader_sections(id) ON DELETE SET NULL
        );
        CREATE INDEX IF NOT EXISTS idx_reader_annotation_scope
            ON reader_annotations(user_id, document_id, deleted_at, kind);
        CREATE INDEX IF NOT EXISTS idx_reader_annotation_document
            ON reader_annotations(document_id);
        CREATE INDEX IF NOT EXISTS idx_reader_annotation_section
            ON reader_annotations(section_id);

        CREATE TABLE IF NOT EXISTS reader_bookmarks (
            id TEXT NOT NULL PRIMARY KEY,
            user_id INTEGER NOT NULL,
            document_id TEXT NOT NULL,
            locator_json TEXT NOT NULL,
            anchor_hash TEXT NOT NULL,
            preview TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL,
            FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
            FOREIGN KEY(document_id) REFERENCES reader_documents(id) ON DELETE CASCADE
        );
        CREATE UNIQUE INDEX IF NOT EXISTS idx_reader_bookmark_anchor
            ON reader_bookmarks(user_id, document_id, anchor_hash);
        CREATE INDEX IF NOT EXISTS idx_reader_bookmark_document
            ON reader_bookmarks(document_id);

        CREATE TABLE IF NOT EXISTS reader_source_references (
            id TEXT NOT NULL PRIMARY KEY,
            user_id INTEGER NOT NULL,
            reference_key TEXT NOT NULL,
            source_document_id TEXT,
            section_id TEXT,
            source_revision TEXT NOT NULL,
            locator_json TEXT NOT NULL,
            quote_snapshot TEXT NOT NULL,
            quote_sha256 TEXT NOT NULL,
            bibliographic_json TEXT NOT NULL DEFAULT '{}',
            evidence_kind TEXT NOT NULL,
            extraction_quality TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
            FOREIGN KEY(source_document_id) REFERENCES reader_documents(id) ON DELETE SET NULL,
            FOREIGN KEY(section_id) REFERENCES reader_sections(id) ON DELETE SET NULL
        );
        CREATE UNIQUE INDEX IF NOT EXISTS idx_reader_source_reference_key
            ON reader_source_references(user_id, reference_key);
        CREATE INDEX IF NOT EXISTS idx_reader_source_reference_document
            ON reader_source_references(source_document_id);
        CREATE INDEX IF NOT EXISTS idx_reader_source_reference_section
            ON reader_source_references(section_id);

        CREATE TABLE IF NOT EXISTS reader_notebooks (
            id TEXT NOT NULL PRIMARY KEY,
            user_id INTEGER NOT NULL,
            document_id TEXT NOT NULL,
            body_markdown TEXT NOT NULL DEFAULT '',
            revision INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
            FOREIGN KEY(document_id) REFERENCES reader_documents(id) ON DELETE CASCADE
        );
        CREATE UNIQUE INDEX IF NOT EXISTS idx_reader_notebook_owner
            ON reader_notebooks(user_id, document_id);
        CREATE INDEX IF NOT EXISTS idx_reader_notebook_document
            ON reader_notebooks(document_id);

        CREATE TABLE IF NOT EXISTS reader_note_versions (
            id TEXT NOT NULL PRIMARY KEY,
            notebook_id TEXT NOT NULL,
            revision INTEGER NOT NULL,
            operation_id TEXT NOT NULL,
            body_markdown TEXT NOT NULL,
            actor TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            FOREIGN KEY(notebook_id) REFERENCES reader_notebooks(id) ON DELETE CASCADE
        );
        CREATE UNIQUE INDEX IF NOT EXISTS idx_reader_note_version
            ON reader_note_versions(notebook_id, revision);
        CREATE UNIQUE INDEX IF NOT EXISTS idx_reader_note_operation
            ON reader_note_versions(notebook_id, operation_id);

        CREATE TABLE IF NOT EXISTS reader_mutation_receipts (
            user_id INTEGER NOT NULL,
            operation_id TEXT NOT NULL,
            target_kind TEXT NOT NULL,
            target_id TEXT NOT NULL,
            request_hash TEXT NOT NULL,
            before_revision INTEGER NOT NULL,
            after_revision INTEGER NOT NULL,
            result_json TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            PRIMARY KEY(user_id, operation_id),
            FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS idx_reader_mutation_target
            ON reader_mutation_receipts(user_id, target_kind, target_id);

        CREATE TABLE IF NOT EXISTS reader_drafts (
            user_id INTEGER NOT NULL,
            notebook_id TEXT NOT NULL,
            editor_id TEXT NOT NULL,
            editor_sequence INTEGER NOT NULL DEFAULT 0,
            base_revision INTEGER NOT NULL,
            body_markdown TEXT NOT NULL,
            updated_at INTEGER NOT NULL,
            PRIMARY KEY(user_id, notebook_id, editor_id),
            FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
            FOREIGN KEY(notebook_id) REFERENCES reader_notebooks(id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS idx_reader_draft_notebook
            ON reader_drafts(notebook_id);

        CREATE TABLE IF NOT EXISTS reader_jobs (
            id TEXT NOT NULL PRIMARY KEY,
            user_id INTEGER NOT NULL,
            kind TEXT NOT NULL,
            document_id TEXT,
            request_key TEXT NOT NULL,
            stage TEXT NOT NULL,
            status TEXT NOT NULL,
            staged_path TEXT,
            target_revision TEXT,
            lease_token TEXT,
            lease_until INTEGER,
            attempts INTEGER NOT NULL DEFAULT 0,
            next_retry_at INTEGER,
            error_code TEXT,
            progress_json TEXT NOT NULL DEFAULT '{}',
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
            FOREIGN KEY(document_id) REFERENCES reader_documents(id) ON DELETE SET NULL
        );
        CREATE UNIQUE INDEX IF NOT EXISTS idx_reader_job_request
            ON reader_jobs(user_id, request_key);
        CREATE INDEX IF NOT EXISTS idx_reader_job_due
            ON reader_jobs(status, next_retry_at, id);
        CREATE INDEX IF NOT EXISTS idx_reader_job_document
            ON reader_jobs(document_id);
    """.trimIndent()

    private val REBUILD_QUESTIONS = """
        CREATE TABLE questions_new (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            type TEXT NOT NULL,
            chapter_id INTEGER,
            stem TEXT NOT NULL,
            options TEXT,
            answer TEXT NOT NULL,
            synonyms TEXT,
            knowledge_point TEXT,
            analysis TEXT NOT NULL,
            score INTEGER NOT NULL,
            enabled INTEGER NOT NULL,
            created_at INTEGER NOT NULL,
            owner_user_id INTEGER,
            origin TEXT NOT NULL DEFAULT 'BUILTIN',
            source_document_id TEXT,
            source_locator_json TEXT,
            source_excerpt TEXT,
            source_revision TEXT,
            FOREIGN KEY(chapter_id) REFERENCES chapters(id) ON DELETE SET NULL,
            FOREIGN KEY(owner_user_id) REFERENCES users(id) ON DELETE CASCADE
        );

        INSERT INTO questions_new (
            id, type, chapter_id, stem, options, answer, synonyms,
            knowledge_point, analysis, score, enabled, created_at,
            owner_user_id, origin, source_document_id, source_locator_json,
            source_excerpt, source_revision
        )
        SELECT
            id, type, chapter_id, stem, options, answer, synonyms,
            knowledge_point, analysis, score, enabled, created_at,
            NULL, 'BUILTIN', NULL, NULL, NULL, NULL
        FROM questions;

        DROP TABLE questions;
        ALTER TABLE questions_new RENAME TO questions;

        CREATE INDEX IF NOT EXISTS index_questions_type ON questions(type);
        CREATE INDEX IF NOT EXISTS index_questions_chapter_id ON questions(chapter_id);
        CREATE INDEX IF NOT EXISTS index_questions_type_enabled ON questions(type, enabled);
        CREATE INDEX IF NOT EXISTS index_questions_owner_user_id_id ON questions(owner_user_id, id);
        CREATE INDEX IF NOT EXISTS index_questions_source_document_id ON questions(source_document_id);
    """.trimIndent()

    private val CREATE_CONTENT_TABLES = """
        CREATE TABLE IF NOT EXISTS content_packages (
            package_id TEXT NOT NULL PRIMARY KEY,
            version INTEGER NOT NULL,
            manifest_hash TEXT NOT NULL,
            status TEXT NOT NULL,
            applied_at INTEGER
        );

        CREATE TABLE IF NOT EXISTS content_question_bindings (
            package_id TEXT NOT NULL,
            question_key TEXT NOT NULL,
            question_id INTEGER NOT NULL,
            PRIMARY KEY(package_id, question_key),
            FOREIGN KEY(package_id) REFERENCES content_packages(package_id) ON DELETE CASCADE,
            FOREIGN KEY(question_id) REFERENCES questions(id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS idx_content_question_id
            ON content_question_bindings(question_id);
    """.trimIndent()

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Keep old child-table references valid while questions is rebuilt.
            db.execSQL("PRAGMA defer_foreign_keys = ON")
            db.execSqlScript(CREATE_READER_TABLES)
            db.execSqlScript(REBUILD_QUESTIONS)
            db.execSqlScript(CREATE_CONTENT_TABLES)
            installQuestionGuards(db)
        }
    }

    /**
     * SupportSQLiteDatabase.execSQL only prepares the first statement in a
     * multi-statement script on Android. Split migration DDL explicitly so
     * every table and index is created, while keeping triggers as single
     * statements (they contain semicolons inside BEGIN ... END).
     */
    private fun SupportSQLiteDatabase.execSqlScript(script: String) {
        script.split(';')
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach(::execSQL)
    }
    fun installQuestionGuards(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS guard_ai_question_insert
            BEFORE INSERT ON questions
            WHEN NEW.origin = 'AI' AND (NEW.owner_user_id IS NULL OR NEW.enabled <> 0)
            BEGIN
                SELECT RAISE(ABORT, 'AI_QUESTION_MUST_BE_PRIVATE_DISABLED');
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS guard_ai_question_update
            BEFORE UPDATE ON questions
            WHEN NEW.origin = 'AI' AND (NEW.owner_user_id IS NULL OR NEW.enabled <> 0)
            BEGIN
                SELECT RAISE(ABORT, 'AI_QUESTION_MUST_BE_PRIVATE_DISABLED');
            END
            """.trimIndent(),
        )
    }
}