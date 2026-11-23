package com.example.myapplication.data.review

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import com.example.myapplication.data.local.entity.QuestionEntity
import com.example.myapplication.data.local.entity.ReaderAnnotationEntity
import com.example.myapplication.data.local.entity.ReaderNotebookEntity

data class ReviewQuestionRow(
    @Embedded val question: QuestionEntity,
    @ColumnInfo(name = "is_wrong") val isWrong: Boolean,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean,
)

@Dao
interface ReviewMaterialDao {
    @Query(
        "SELECT a.* FROM reader_annotations a JOIN reader_documents d ON d.id = a.document_id " +
            "LEFT JOIN reader_states s ON s.document_id = d.id AND s.user_id = :userId " +
            "WHERE a.user_id = :userId AND a.deleted_at IS NULL AND a.kind != 'INK' " +
            "AND TRIM(COALESCE(a.quote_snapshot, '')) != '' " +
            "AND d.deleted_at IS NULL AND d.import_status = 'READY' AND COALESCE(s.is_hidden, 0) = 0 " +
            "AND (d.kind = 'BUILTIN' OR d.owner_user_id = :userId) " +
            "ORDER BY a.updated_at DESC, a.id ASC LIMIT :limit OFFSET :offset",
    )
    suspend fun annotations(userId: Long, limit: Int, offset: Int): List<ReaderAnnotationEntity>

    @Query(
        "SELECT n.* FROM reader_notebooks n JOIN reader_documents d ON d.id = n.document_id " +
            "LEFT JOIN reader_states s ON s.document_id = d.id AND s.user_id = :userId " +
            "WHERE n.user_id = :userId AND TRIM(n.body_markdown) != '' AND d.deleted_at IS NULL " +
            "AND d.import_status = 'READY' AND COALESCE(s.is_hidden, 0) = 0 " +
            "AND (d.kind = 'BUILTIN' OR d.owner_user_id = :userId) " +
            "ORDER BY n.updated_at DESC, n.id ASC LIMIT :limit OFFSET :offset",
    )
    suspend fun notes(userId: Long, limit: Int, offset: Int): List<ReaderNotebookEntity>

    @Query(
        "SELECT q.*, MAX(f.source = 'WRONG') AS is_wrong, MAX(f.source = 'MANUAL') AS is_favorite " +
            "FROM favorites f JOIN questions q ON q.id = f.question_id " +
            "LEFT JOIN reader_documents d ON d.id = q.source_document_id " +
            "LEFT JOIN reader_states s ON s.document_id = d.id AND s.user_id = :userId " +
            "WHERE f.user_id = :userId AND f.source IN ('MANUAL','WRONG') AND q.enabled = 1 " +
            "AND (q.owner_user_id IS NULL OR q.owner_user_id = :userId) " +
            "AND (q.source_document_id IS NULL OR (d.id IS NOT NULL AND d.deleted_at IS NULL " +
            "AND d.import_status = 'READY' AND COALESCE(s.is_hidden, 0) = 0 " +
            "AND (d.kind = 'BUILTIN' OR d.owner_user_id = :userId))) " +
            "GROUP BY q.id ORDER BY is_wrong DESC, MAX(f.created_at) DESC, q.id ASC LIMIT :limit OFFSET :offset",
    )
    suspend fun questions(userId: Long, limit: Int, offset: Int): List<ReviewQuestionRow>

    @Query("SELECT source FROM favorites WHERE user_id = :userId AND question_id = :questionId AND source IN ('MANUAL', 'WRONG')")
    suspend fun questionTags(userId: Long, questionId: Long): List<String>
}
