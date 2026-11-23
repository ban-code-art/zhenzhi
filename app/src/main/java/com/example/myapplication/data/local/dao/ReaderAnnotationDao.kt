package com.example.myapplication.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myapplication.data.local.entity.ReaderAnnotationEntity
import com.example.myapplication.data.local.entity.ReaderBookmarkEntity
import com.example.myapplication.data.local.entity.ReaderSourceReferenceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReaderAnnotationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAnnotation(annotation: ReaderAnnotationEntity)

    @Query("SELECT * FROM reader_annotations WHERE id = :annotationId AND user_id = :userId LIMIT 1")
    suspend fun findAnnotation(userId: Long, annotationId: String): ReaderAnnotationEntity?

    @Query("SELECT * FROM reader_annotations WHERE user_id = :userId AND document_id = :documentId AND deleted_at IS NULL")
    suspend fun listActiveForDocument(userId: Long, documentId: String): List<ReaderAnnotationEntity>

    @Query(
        "UPDATE reader_annotations SET section_id = :sectionId, locator_json = :locatorJson, " +
            "revision = revision + 1, updated_at = :now " +
            "WHERE id = :annotationId AND user_id = :userId AND document_id = :documentId " +
            "AND deleted_at IS NULL AND revision = :expectedRevision",
    )
    suspend fun remapSourceAnchor(
        userId: Long,
        documentId: String,
        annotationId: String,
        expectedRevision: Long,
        sectionId: String,
        locatorJson: String,
        now: Long,
    ): Int

    @Query(
        "SELECT a.* FROM reader_annotations a " +
            "LEFT JOIN reader_sections s ON s.id = a.section_id " +
            "WHERE a.user_id = :userId AND a.document_id = :documentId AND a.deleted_at IS NULL " +
            "ORDER BY CASE WHEN s.order_index IS NULL THEN 2147483647 ELSE s.order_index END, " +
            "a.created_at ASC, a.id ASC",
    )
    fun observeAnnotations(userId: Long, documentId: String): Flow<List<ReaderAnnotationEntity>>

    @Query(
        "SELECT a.* FROM reader_annotations a " +
            "LEFT JOIN reader_sections s ON s.id = a.section_id " +
            "WHERE a.user_id = :userId AND a.document_id = :documentId AND a.deleted_at IS NULL " +
            "AND a.kind = :kind " +
            "ORDER BY CASE WHEN s.order_index IS NULL THEN 2147483647 ELSE s.order_index END, " +
            "a.created_at ASC, a.id ASC",
    )
    fun observeAnnotationsByKind(userId: Long, documentId: String, kind: String): Flow<List<ReaderAnnotationEntity>>

    @Query(
        "UPDATE reader_annotations SET locator_json = :locatorJson, quote_snapshot = :quoteSnapshot, " +
            "note_text = :noteText, style_json = :styleJson, ink_blob_path = :inkBlobPath, " +
            "revision = revision + 1, updated_at = :now " +
            "WHERE id = :annotationId AND user_id = :userId AND deleted_at IS NULL AND revision = :expectedRevision",
    )
    suspend fun updateAnnotation(
        userId: Long,
        annotationId: String,
        expectedRevision: Long,
        locatorJson: String,
        quoteSnapshot: String?,
        noteText: String?,
        styleJson: String,
        inkBlobPath: String?,
        now: Long,
    ): Int

    @Query(
        "UPDATE reader_annotations SET locator_json = :locatorJson, ink_blob_path = :inkBlobPath, " +
            "revision = revision + 1, updated_at = :now " +
            "WHERE id = :annotationId AND user_id = :userId AND deleted_at IS NULL " +
            "AND revision = :expectedRevision AND kind = 'INK'",
    )
    suspend fun updateInk(
        userId: Long,
        annotationId: String,
        expectedRevision: Long,
        locatorJson: String,
        inkBlobPath: String,
        now: Long,
    ): Int

    @Query(
        "UPDATE reader_annotations SET deleted_at = :now, revision = revision + 1, updated_at = :now " +
            "WHERE id = :annotationId AND user_id = :userId AND deleted_at IS NULL AND revision = :expectedRevision",
    )
    suspend fun softDeleteAnnotation(userId: Long, annotationId: String, expectedRevision: Long, now: Long): Int

    @Query(
        "UPDATE reader_annotations SET deleted_at = NULL, revision = revision + 1, updated_at = :now " +
            "WHERE id = :annotationId AND user_id = :userId AND deleted_at IS NOT NULL",
    )
    suspend fun restoreAnnotation(userId: Long, annotationId: String, now: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBookmark(bookmark: ReaderBookmarkEntity)

    @Query("SELECT * FROM reader_bookmarks WHERE user_id = :userId AND document_id = :documentId AND anchor_hash = :anchorHash LIMIT 1")
    suspend fun findBookmarkByAnchor(userId: Long, documentId: String, anchorHash: String): ReaderBookmarkEntity?

    @Query("SELECT * FROM reader_bookmarks WHERE id = :bookmarkId AND user_id = :userId LIMIT 1")
    suspend fun findBookmark(userId: Long, bookmarkId: String): ReaderBookmarkEntity?

    @Query("SELECT * FROM reader_bookmarks WHERE user_id = :userId AND document_id = :documentId")
    suspend fun listForDocument(userId: Long, documentId: String): List<ReaderBookmarkEntity>

    @Query(
        "UPDATE reader_bookmarks SET locator_json = :locatorJson, anchor_hash = :anchorHash " +
            "WHERE id = :bookmarkId AND user_id = :userId AND document_id = :documentId",
    )
    suspend fun remapSourceAnchor(
        userId: Long,
        documentId: String,
        bookmarkId: String,
        locatorJson: String,
        anchorHash: String,
    ): Int

    @Query("DELETE FROM reader_bookmarks WHERE id = :bookmarkId AND user_id = :userId")
    suspend fun deleteBookmark(userId: Long, bookmarkId: String): Int

    @Query(
        "SELECT b.* FROM reader_bookmarks b " +
            "WHERE b.user_id = :userId AND b.document_id = :documentId " +
            "ORDER BY b.created_at ASC, b.id ASC",
    )
    fun observeBookmarks(userId: Long, documentId: String): Flow<List<ReaderBookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSourceReference(reference: ReaderSourceReferenceEntity)

    @Query("SELECT * FROM reader_source_references WHERE user_id = :userId AND reference_key = :referenceKey LIMIT 1")
    suspend fun findSourceReferenceByKey(userId: Long, referenceKey: String): ReaderSourceReferenceEntity?

    @Query("SELECT * FROM reader_source_references WHERE user_id = :userId AND id = :referenceId LIMIT 1")
    suspend fun findSourceReference(userId: Long, referenceId: String): ReaderSourceReferenceEntity?
}




