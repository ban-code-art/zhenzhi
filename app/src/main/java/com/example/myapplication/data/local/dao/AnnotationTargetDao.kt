package com.example.myapplication.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myapplication.data.local.entity.AnnotationTargetEntity
import kotlinx.coroutines.flow.Flow

private const val READABLE_TARGETS =
    "SELECT t.* FROM reader_annotation_targets t " +
        "JOIN reader_annotations a ON a.id = t.annotation_id " +
        "JOIN reader_documents d ON d.id = a.document_id " +
        "WHERE a.id = :annotationId AND a.user_id = :userId AND a.deleted_at IS NULL " +
        "AND d.deleted_at IS NULL AND d.import_status = 'READY' " +
        "AND (d.kind = 'BUILTIN' OR (d.kind = 'IMPORTED' AND d.owner_user_id = :userId)) " +
        "ORDER BY t.ordinal"

@Dao
interface AnnotationTargetDao {
    /** Call only inside the parent annotation's authorized mutation transaction (M08). */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTargets(targets: List<AnnotationTargetEntity>)

    @Query(READABLE_TARGETS)
    suspend fun listTargets(userId: Long, annotationId: String): List<AnnotationTargetEntity>

    @Query(READABLE_TARGETS)
    fun observeTargets(userId: Long, annotationId: String): Flow<List<AnnotationTargetEntity>>
}
