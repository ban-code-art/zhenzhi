package com.example.myapplication.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myapplication.data.local.entity.ContentPackageEntity
import com.example.myapplication.data.local.entity.ReaderJobEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReaderJobDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(job: ReaderJobEntity)


    @Query("SELECT * FROM reader_jobs WHERE id = :jobId LIMIT 1")
    suspend fun find(jobId: String): ReaderJobEntity?

    @Query("SELECT * FROM reader_jobs WHERE user_id = :userId AND request_key = :requestKey LIMIT 1")
    suspend fun findByRequestKey(userId: Long, requestKey: String): ReaderJobEntity?

    @Query("SELECT * FROM reader_jobs WHERE status = :status AND (next_retry_at IS NULL OR next_retry_at <= :now) ORDER BY created_at ASC, id ASC")
    suspend fun findDue(status: String, now: Long): List<ReaderJobEntity>

    @Query("SELECT * FROM content_packages WHERE package_id = :packageId LIMIT 1")
    suspend fun findPackage(packageId: String): ContentPackageEntity?

    @Query(
        "UPDATE reader_jobs SET stage = :stage, status = :status, staged_path = :stagedPath, " +
            "target_revision = :targetRevision, error_code = :errorCode, progress_json = :progressJson, " +
            "updated_at = :updatedAt WHERE id = :jobId AND (kind != 'IMPORT' OR status IN ('PENDING', 'RUNNING', 'RETRY_WAIT'))",
    )
    suspend fun updateProgress(
        jobId: String,
        stage: String,
        status: String,
        stagedPath: String?,
        targetRevision: String?,
        errorCode: String?,
        progressJson: String,
        updatedAt: Long,
    ): Int

    @Query("SELECT * FROM reader_jobs WHERE user_id = :userId ORDER BY created_at DESC, id DESC")
    fun observeJobs(userId: Long): Flow<List<ReaderJobEntity>>

    @Query("SELECT * FROM reader_jobs WHERE kind = 'IMPORT' AND status IN ('PENDING', 'RUNNING', 'RETRY_WAIT') ORDER BY created_at ASC, id ASC")
    suspend fun findRecoverable(): List<ReaderJobEntity>

    @Query("UPDATE reader_jobs SET stage = :stage, status = 'RUNNING', error_code = NULL, updated_at = :now WHERE id = :jobId AND kind = 'IMPORT' AND status IN ('PENDING', 'RUNNING', 'RETRY_WAIT')")
    suspend fun markRunning(jobId: String, stage: String, now: Long): Int

    @Query(
        "UPDATE reader_jobs SET document_id = :documentId, target_revision = :revision, " +
            "stage = 'PUBLISHING', status = 'COMPLETED', error_code = NULL, staged_path = NULL, updated_at = :now " +
            "WHERE id = :jobId AND kind = 'IMPORT' AND status IN ('PENDING', 'RUNNING', 'RETRY_WAIT')",
    )
    suspend fun markPublished(jobId: String, documentId: String, revision: String, now: Long): Int

    @Query("UPDATE reader_jobs SET stage = :stage, status = 'FAILED', error_code = :errorCode, updated_at = :now WHERE id = :jobId AND kind = 'IMPORT' AND status IN ('PENDING', 'RUNNING', 'RETRY_WAIT')")
    suspend fun markFailed(jobId: String, stage: String, errorCode: String, now: Long): Int

    @Query("UPDATE reader_jobs SET status = 'CANCELLED', error_code = NULL, staged_path = NULL, next_retry_at = NULL, updated_at = :now WHERE id = :jobId AND user_id = :userId AND kind = 'IMPORT' AND status IN ('PENDING', 'RUNNING', 'RETRY_WAIT')")
    suspend fun cancelImport(jobId: String, userId: Long, now: Long): Int

    @Query("UPDATE reader_jobs SET stage = :stage, status = 'RETRY_WAIT', error_code = :errorCode, updated_at = :now WHERE id = :jobId AND kind = 'IMPORT' AND status IN ('PENDING', 'RUNNING', 'RETRY_WAIT')")
    suspend fun markRetryWait(jobId: String, stage: String, errorCode: String, now: Long): Int

    @Query("UPDATE reader_jobs SET progress_json = :progressJson, updated_at = :now WHERE id = :jobId AND kind = 'IMPORT' AND status IN ('PENDING', 'RUNNING', 'RETRY_WAIT')")
    suspend fun updateAttemptIfActive(jobId: String, progressJson: String, now: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPackage(packageEntity: ContentPackageEntity)

    @Query("UPDATE content_packages SET version = :version, manifest_hash = :manifestHash, status = :status, applied_at = :appliedAt WHERE package_id = :packageId")
    suspend fun updatePackage(packageId: String, version: Int, manifestHash: String, status: String, appliedAt: Long): Int
}
