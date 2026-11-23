package com.example.myapplication.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myapplication.data.local.entity.ReaderContentRevisionEntity
import com.example.myapplication.data.local.entity.ReaderDocumentEntity
import com.example.myapplication.data.local.entity.ReaderOutlineNodeEntity
import com.example.myapplication.data.local.entity.ReaderSectionEntity
import kotlinx.coroutines.flow.Flow

data class ReaderShelfRow(
    @Embedded val document: ReaderDocumentEntity,
    @ColumnInfo(name = "state_is_completed") val isCompleted: Boolean?,
    @ColumnInfo(name = "state_last_read_at") val lastReadAt: Long?,
    @ColumnInfo(name = "state_is_hidden") val isHidden: Boolean?,
    @ColumnInfo(name = "state_display_title") val displayTitleOverride: String?,
    @ColumnInfo(name = "state_locator_json") val locatorJson: String?,
)

data class ReaderHomeShelfRow(
    @Embedded val shelf: ReaderShelfRow,
    @ColumnInfo(name = "visible_total") val visibleTotal: Int,
)

@Dao
interface ReaderCatalogDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDocument(document: ReaderDocumentEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertContentRevision(revision: ReaderContentRevisionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSections(sections: List<ReaderSectionEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutline(nodes: List<ReaderOutlineNodeEntity>)

    @Query("SELECT * FROM reader_documents WHERE id = :documentId AND deleted_at IS NULL LIMIT 1")
    suspend fun findDocument(documentId: String): ReaderDocumentEntity?

    @Query("SELECT * FROM reader_documents WHERE id = :documentId LIMIT 1")
    suspend fun findDocumentIncludingDeleted(documentId: String): ReaderDocumentEntity?

    @Query(
        "SELECT * FROM reader_documents " +
            "WHERE deleted_at IS NULL AND (kind = 'BUILTIN' OR owner_user_id = :userId) " +
            "ORDER BY updated_at DESC, id ASC",
    )
    fun observeVisibleDocuments(userId: Long): Flow<List<ReaderDocumentEntity>>

    @Query(
        "SELECT d.*, s.is_completed AS state_is_completed, s.last_read_at AS state_last_read_at, " +
            "s.is_hidden AS state_is_hidden, s.display_title_override AS state_display_title, " +
            "s.locator_json AS state_locator_json " +
            "FROM reader_documents d LEFT JOIN reader_states s " +
            "ON s.document_id = d.id AND s.user_id = :userId " +
            "WHERE d.deleted_at IS NULL AND (d.kind = 'BUILTIN' OR d.owner_user_id = :userId) " +
            "AND COALESCE(s.is_hidden, 0) = 0 " +
            "ORDER BY CASE WHEN s.last_read_at IS NULL THEN 1 ELSE 0 END, s.last_read_at DESC, d.updated_at DESC, d.id ASC",
    )
    fun observeShelf(userId: Long): Flow<List<ReaderShelfRow>>

    @Query(
        "WITH visible AS (" +
            "SELECT d.*, s.is_completed AS state_is_completed, s.last_read_at AS state_last_read_at, " +
            "s.is_hidden AS state_is_hidden, s.display_title_override AS state_display_title, " +
            "s.locator_json AS state_locator_json FROM reader_documents d LEFT JOIN reader_states s " +
            "ON s.document_id = d.id AND s.user_id = :userId " +
            "WHERE d.kind = 'IMPORTED' AND d.owner_user_id = :userId AND d.import_status = 'READY' " +
            "AND d.deleted_at IS NULL AND COALESCE(s.is_hidden, 0) = 0) " +
            "SELECT visible.*, (SELECT COUNT(*) FROM visible) AS visible_total FROM visible " +
            "ORDER BY state_last_read_at DESC, updated_at DESC, id ASC LIMIT 24",
    )
    fun observeHomeImports(userId: Long): Flow<List<ReaderHomeShelfRow>>

    @Query(
        "SELECT * FROM reader_documents " +
            "WHERE owner_user_id = :userId AND file_sha256 = :fileSha256 AND deleted_at IS NULL LIMIT 1",
    )
    suspend fun findImportedByHash(userId: Long, fileSha256: String): ReaderDocumentEntity?

    @Query(
        "SELECT * FROM reader_documents " +
            "WHERE owner_user_id = :userId AND file_sha256 = :fileSha256 LIMIT 1",
    )
    suspend fun findImportedByHashIncludingDeleted(userId: Long, fileSha256: String): ReaderDocumentEntity?

    @Query("SELECT * FROM reader_documents WHERE legacy_chapter_id = :chapterId LIMIT 1")
    suspend fun findBuiltInByChapter(chapterId: Long): ReaderDocumentEntity?

    /**
     * 可索引来源：内置章节 + 当前用户已 READY 的导入文档（不含回收站）。
     * 索引登记按用户命名空间补建 knowledge_documents；BUILTIN 的 owner 保持全局。
     */
    @Query(
        "SELECT * FROM reader_documents WHERE deleted_at IS NULL AND import_status = 'READY' " +
            "AND (kind = 'BUILTIN' OR owner_user_id = :userId) ORDER BY id ASC",
    )
    suspend fun findIndexableDocuments(userId: Long): List<ReaderDocumentEntity>

    @Query("SELECT * FROM reader_documents WHERE owner_user_id = :userId AND deleted_at IS NOT NULL ORDER BY deleted_at DESC, id ASC")
    fun observeTrash(userId: Long): Flow<List<ReaderDocumentEntity>>

    @Query("SELECT * FROM reader_content_revisions WHERE document_id = :documentId AND revision = :revision LIMIT 1")
    suspend fun findContentRevision(documentId: String, revision: String): ReaderContentRevisionEntity?

    /**
     * 反查"某个 content revision 属于谁的书"（G-04 评分页来源标题用）。
     *
     * 同一 revision 字符串在 `reader_content_revisions` 里可能有多行（不同文档、不同用户导入
     * 同一份文件时 revision 相同），因此排序即语义：
     *  1. 先取**当前用户自己的**书（`owner_user_id = :userId`）；
     *  2. 再退化到全局内置书（`kind = 'BUILTIN'`，owner 为 null 或别人）；
     *  3. 同等条件下取最近更新的、再按 id 稳定排序，保证结果确定（不随 SQLite 行序漂移）。
     *
     * 只读；回收站里的书（`deleted_at` 非空）不参与匹配，宁可查不到也不拿已删除的书名。
     */
    @Query(
        "SELECT d.* FROM reader_content_revisions r " +
            "JOIN reader_documents d ON d.id = r.document_id " +
            "WHERE r.revision = :revision AND d.deleted_at IS NULL " +
            "AND (d.owner_user_id = :userId OR d.kind = 'BUILTIN') " +
            "ORDER BY CASE WHEN d.owner_user_id = :userId THEN 0 ELSE 1 END, " +
            "d.updated_at DESC, d.id ASC LIMIT 1",
    )
    suspend fun findDocumentByRevisionForUser(userId: Long, revision: String): ReaderDocumentEntity?

    @Query(
        "SELECT * FROM reader_sections WHERE document_id = :documentId AND content_revision = :revision " +
            "ORDER BY order_index ASC, id ASC",
    )
    suspend fun findSections(documentId: String, revision: String): List<ReaderSectionEntity>

    @Query(
        "SELECT * FROM reader_outline_nodes WHERE document_id = :documentId AND content_revision = :revision " +
            "ORDER BY order_index ASC, id ASC",
    )
    suspend fun findOutline(documentId: String, revision: String): List<ReaderOutlineNodeEntity>

    @Query("UPDATE reader_documents SET title = :title, metadata_revision = metadata_revision + 1, updated_at = :now WHERE id = :documentId")
    suspend fun updateTitle(documentId: String, title: String, now: Long): Int

    @Query("UPDATE reader_documents SET deleted_at = :deletedAt, access_epoch = access_epoch + 1, updated_at = :now WHERE id = :documentId AND owner_user_id = :userId")
    suspend fun softDelete(documentId: String, userId: Long, deletedAt: Long, now: Long): Int

    @Query("UPDATE reader_documents SET deleted_at = NULL, access_epoch = access_epoch + 1, updated_at = :now WHERE id = :documentId AND owner_user_id = :userId")
    suspend fun restore(documentId: String, userId: Long, now: Long): Int

    @Query("UPDATE reader_documents SET content_revision = :revision, extractor_version = :extractorVersion, updated_at = :now WHERE id = :documentId")
    suspend fun updateContentRevision(documentId: String, revision: String, extractorVersion: String, now: Long): Int

    @Query("UPDATE reader_documents SET content_revision = :revision, extractor_version = :extractorVersion, " +
        "access_epoch = access_epoch + 1, metadata_revision = metadata_revision + 1, updated_at = :now " +
        "WHERE id = :documentId AND owner_user_id = :userId AND kind = 'IMPORTED' AND format = 'MARKDOWN' " +
        "AND content_revision = :expectedRevision AND access_epoch = :expectedEpoch " +
        "AND metadata_revision = :expectedMetadata AND import_status = 'READY' AND deleted_at IS NULL")
    suspend fun activateMarkdownRevision(documentId: String, userId: Long, expectedRevision: String,
        expectedEpoch: Long, expectedMetadata: Long, revision: String, extractorVersion: String, now: Long): Int

    @Query("UPDATE reader_documents SET import_status = :status, updated_at = :now WHERE id = :documentId")
    suspend fun updateStatus(documentId: String, status: String, now: Long): Int
}
