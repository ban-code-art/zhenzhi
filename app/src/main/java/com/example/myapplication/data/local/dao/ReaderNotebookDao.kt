package com.example.myapplication.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myapplication.data.local.entity.ReaderNotebookEntity
import com.example.myapplication.data.local.entity.ReaderNoteVersionEntity
import com.example.myapplication.data.local.entity.ReaderDraftEntity
import com.example.myapplication.data.local.entity.ReaderMutationReceiptEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReaderNotebookDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNotebook(notebook: ReaderNotebookEntity)

    @Query("SELECT * FROM reader_notebooks WHERE id = :notebookId AND user_id = :userId LIMIT 1")
    suspend fun findNotebook(userId: Long, notebookId: String): ReaderNotebookEntity?

    @Query("SELECT * FROM reader_notebooks WHERE user_id = :userId AND document_id = :documentId LIMIT 1")
    suspend fun findNotebookForDocument(userId: Long, documentId: String): ReaderNotebookEntity?

    @Query(
        "UPDATE reader_notebooks SET body_markdown = :body, revision = revision + 1, updated_at = :now " +
            "WHERE id = :notebookId AND user_id = :userId AND revision = :baseRevision",
    )
    suspend fun compareAndSetBody(
        userId: Long,
        notebookId: String,
        baseRevision: Long,
        body: String,
        now: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVersion(version: ReaderNoteVersionEntity)

    @Query("SELECT * FROM reader_note_versions WHERE notebook_id = :notebookId AND revision = :revision LIMIT 1")
    suspend fun findVersion(notebookId: String, revision: Long): ReaderNoteVersionEntity?

    @Query("SELECT * FROM reader_note_versions WHERE notebook_id = :notebookId AND operation_id = :operationId LIMIT 1")
    suspend fun findVersionByOperation(notebookId: String, operationId: String): ReaderNoteVersionEntity?

    @Query("SELECT * FROM reader_note_versions WHERE notebook_id = :notebookId ORDER BY revision DESC LIMIT :limit")
    suspend fun listVersions(notebookId: String, limit: Int = 50): List<ReaderNoteVersionEntity>

    @Query(
        "DELETE FROM reader_note_versions WHERE notebook_id = :notebookId AND revision NOT IN " +
            "(SELECT revision FROM reader_note_versions WHERE notebook_id = :notebookId ORDER BY revision DESC LIMIT :keep)",
    )
    suspend fun trimVersions(notebookId: String, keep: Int = 50): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDraftIfAbsent(draft: ReaderDraftEntity): Long

    @Query(
        "UPDATE reader_drafts SET base_revision = :baseRevision, body_markdown = :body, " +
            "editor_sequence = :editorSequence, updated_at = :now " +
            "WHERE user_id = :userId AND notebook_id = :notebookId AND editor_id = :editorId " +
            "AND editor_sequence < :editorSequence",
    )
    suspend fun updateDraftIfNewer(
        userId: Long,
        notebookId: String,
        editorId: String,
        editorSequence: Long,
        baseRevision: Long,
        body: String,
        now: Long,
    ): Int

    @Query("SELECT * FROM reader_drafts WHERE user_id = :userId AND notebook_id = :notebookId ORDER BY updated_at DESC, editor_id ASC")
    suspend fun listDrafts(userId: Long, notebookId: String): List<ReaderDraftEntity>

    @Query("SELECT * FROM reader_drafts WHERE user_id = :userId AND notebook_id = :notebookId AND editor_id = :editorId LIMIT 1")
    suspend fun findDraft(userId: Long, notebookId: String, editorId: String): ReaderDraftEntity?

    @Query("DELETE FROM reader_drafts WHERE user_id = :userId AND notebook_id = :notebookId AND editor_id = :editorId")
    suspend fun deleteDraft(userId: Long, notebookId: String, editorId: String): Int

    @Query(
        "DELETE FROM reader_drafts WHERE user_id = :userId AND notebook_id = :notebookId AND editor_id = :editorId " +
            "AND editor_sequence <= :throughSequence",
    )
    suspend fun deleteDraftThrough(
        userId: Long,
        notebookId: String,
        editorId: String,
        throughSequence: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReceipt(receipt: ReaderMutationReceiptEntity)

    @Query("SELECT * FROM reader_mutation_receipts WHERE user_id = :userId AND operation_id = :operationId LIMIT 1")
    suspend fun findReceipt(userId: Long, operationId: String): ReaderMutationReceiptEntity?

    @Query(
        "SELECT * FROM reader_mutation_receipts WHERE user_id = :userId AND target_kind = :targetKind " +
            "AND target_id = :targetId ORDER BY created_at DESC, operation_id DESC LIMIT 1",
    )
    suspend fun findLatestReceipt(userId: Long, targetKind: String, targetId: String): ReaderMutationReceiptEntity?

    /**
     * 按编辑器查最近一条 NOTE receipt，用于恢复该编辑器的保存序列。
     *
     * receipt 的 result_json 里带 savedEditorId；这里用 LIKE 粗筛（editorPattern 形如
     * `%"savedEditorId":"<editorId>"%`），调用方必须再用 savedEditorId 精确校验。
     */
    @Query(
        "SELECT * FROM reader_mutation_receipts WHERE user_id = :userId AND target_kind = 'NOTE' " +
            "AND target_id = :notebookId AND result_json LIKE :editorPattern " +
            "ORDER BY created_at DESC, operation_id DESC LIMIT 1",
    )
    suspend fun findLatestNoteReceiptForEditor(
        userId: Long,
        notebookId: String,
        editorPattern: String,
    ): ReaderMutationReceiptEntity?

    @Query("SELECT * FROM reader_notebooks WHERE user_id = :userId ORDER BY updated_at DESC, id ASC")
    fun observeNotebooks(userId: Long): Flow<List<ReaderNotebookEntity>>
}
