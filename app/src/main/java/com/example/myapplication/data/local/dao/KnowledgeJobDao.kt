package com.example.myapplication.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myapplication.data.local.entity.KnowledgeIndexJobEntity

/** 用户级任务统计（SQL 聚合，不逐条加载）。 */
data class KnowledgeStatsRow(
    val pending: Int,
    val blocked: Int,
    val failed: Int,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** 失败原因聚合（统计页显示 errorCode 与数量，不逐条加载任务）。 */
data class KnowledgeFailureRow(
    val errorCode: String?,
    val count: Int,
)

@Dao
interface KnowledgeJobDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(job: KnowledgeIndexJobEntity)

    @Query("SELECT * FROM knowledge_index_jobs WHERE id = :jobId LIMIT 1")
    suspend fun find(jobId: String): KnowledgeIndexJobEntity?

    @Query(
        "SELECT * FROM knowledge_index_jobs WHERE knowledge_document_id = :knowledgeDocumentId " +
            "AND requested_generation = :generation AND phase = :phase AND profile_fingerprint = :profileFingerprint LIMIT 1",
    )
    suspend fun findByKey(
        knowledgeDocumentId: String,
        generation: Long,
        phase: String,
        profileFingerprint: String,
    ): KnowledgeIndexJobEntity?

    @Query("SELECT * FROM knowledge_index_jobs WHERE user_id = :userId ORDER BY created_at DESC, id DESC")
    suspend fun findForUser(userId: Long): List<KnowledgeIndexJobEntity>

    @Query("SELECT * FROM knowledge_index_jobs WHERE knowledge_document_id = :knowledgeDocumentId ORDER BY requested_generation DESC, phase ASC")
    suspend fun findForDocument(knowledgeDocumentId: String): List<KnowledgeIndexJobEntity>

    @Query(
        "SELECT * FROM knowledge_index_jobs WHERE status = :status AND (next_retry_at IS NULL OR next_retry_at <= :now) " +
            "ORDER BY created_at ASC, id ASC LIMIT :limit",
    )
    suspend fun findDue(status: String, now: Long, limit: Int): List<KnowledgeIndexJobEntity>

    @Query("SELECT * FROM knowledge_index_jobs WHERE status IN ('PENDING', 'RETRY_WAIT', 'CLAIMED', 'RUNNING') ORDER BY created_at ASC, id ASC")
    suspend fun findRecoverable(): List<KnowledgeIndexJobEntity>

    /**
     * CAS claim（设计 §5.3）：只有 PENDING/RETRY_WAIT、到达 next_retry_at 且旧租约已过期
     * 才能领取。过期 RUNNING 必须先由 [requeueStaleLeases] 事务置回 RETRY_WAIT 并清 lease，
     * 不允许两个消费者沿同一旧 claim 继续运行。
     */
    @Query(
        "UPDATE knowledge_index_jobs SET lease_token = :leaseToken, lease_until = :leaseUntil, status = 'CLAIMED', " +
            "attempts = attempts + 1, updated_at = :now " +
            "WHERE id = :jobId AND status IN ('PENDING', 'RETRY_WAIT') " +
            "AND (next_retry_at IS NULL OR next_retry_at <= :now) " +
            "AND (lease_until IS NULL OR lease_until <= :now)",
    )
    suspend fun claim(jobId: String, leaseToken: String, leaseUntil: Long, now: Long): Int

    @Query(
        "UPDATE knowledge_index_jobs SET status = 'RUNNING', updated_at = :now " +
            "WHERE id = :jobId AND lease_token = :leaseToken AND status = 'CLAIMED'",
    )
    suspend fun markRunning(jobId: String, leaseToken: String, now: Long): Int

    @Query(
        "UPDATE knowledge_index_jobs SET status = :status, progress_json = :progressJson, lease_token = NULL, " +
            "lease_until = NULL, last_error_code = :errorCode, updated_at = :now " +
            "WHERE id = :jobId AND lease_token = :leaseToken",
    )
    suspend fun finish(
        jobId: String,
        leaseToken: String,
        status: String,
        progressJson: String,
        errorCode: String?,
        now: Long,
    ): Int

    @Query(
        "UPDATE knowledge_index_jobs SET status = 'RETRY_WAIT', next_retry_at = :retryAt, last_error_code = :errorCode, " +
            "lease_token = NULL, lease_until = NULL, progress_json = :progressJson, updated_at = :now " +
            "WHERE id = :jobId AND lease_token = :leaseToken",
    )
    suspend fun retryLater(
        jobId: String,
        leaseToken: String,
        retryAt: Long,
        errorCode: String,
        progressJson: String,
        now: Long,
    ): Int

    @Query(
        "UPDATE knowledge_index_jobs SET status = 'BLOCKED', last_error_code = :errorCode, lease_token = NULL, " +
            "lease_until = NULL, updated_at = :now WHERE id = :jobId",
    )
    suspend fun markBlocked(jobId: String, errorCode: String, now: Long): Int

    @Query("UPDATE knowledge_index_jobs SET status = 'CANCELLED', lease_token = NULL, lease_until = NULL, updated_at = :now WHERE id = :jobId")
    suspend fun cancel(jobId: String, now: Long): Int

    /**
     * 进程重启回收（启动时调用，此时本进程尚无活跃 worker）：
     * 把全部非终态租约重新排队，不依赖系统时钟回拨。
     */
    @Query(
        "UPDATE knowledge_index_jobs SET status = 'RETRY_WAIT', next_retry_at = :now, lease_token = NULL, " +
            "lease_until = NULL, updated_at = :now WHERE status IN ('CLAIMED', 'RUNNING')",
    )
    suspend fun requeueStaleLeases(now: Long): Int

    /**
     * 运行期周期回收：只回收**已过期**的租约，不动仍在有效期内的活跃 worker，
     * 避免同一进程内误抢正在运行的 claim。
     */
    @Query(
        "UPDATE knowledge_index_jobs SET status = 'RETRY_WAIT', next_retry_at = :now, lease_token = NULL, " +
            "lease_until = NULL, updated_at = :now WHERE status IN ('CLAIMED', 'RUNNING') " +
            "AND (lease_until IS NULL OR lease_until <= :now)",
    )
    suspend fun requeueExpiredLeases(now: Long): Int

    @Query("UPDATE knowledge_index_jobs SET progress_json = :progressJson, updated_at = :now WHERE id = :jobId AND lease_token = :leaseToken")
    suspend fun updateProgress(jobId: String, leaseToken: String, progressJson: String, now: Long): Int

    /** 统计页聚合：PENDING/RETRY_WAIT/CLAIMED/RUNNING 计为进行中，BLOCKED/FAILED 分别计。 */
    @Query(
        """
        SELECT
          COALESCE(SUM(CASE WHEN status IN ('PENDING','RETRY_WAIT','CLAIMED','RUNNING') THEN 1 ELSE 0 END), 0) AS pending,
          COALESCE(SUM(CASE WHEN status = 'BLOCKED' THEN 1 ELSE 0 END), 0) AS blocked,
          COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed
        FROM knowledge_index_jobs WHERE user_id = :userId
        """,
    )
    suspend fun statsForUser(userId: Long): KnowledgeStatsRow

    /** 失败原因聚合：只算终态失败（BLOCKED/FAILED），按数量降序。 */
    @Query(
        """
        SELECT last_error_code AS errorCode, COUNT(*) AS count
        FROM knowledge_index_jobs
        WHERE user_id = :userId AND status IN ('BLOCKED', 'FAILED')
        GROUP BY last_error_code
        ORDER BY count DESC, errorCode ASC
        """,
    )
    suspend fun failureReasons(userId: Long): List<KnowledgeFailureRow>

    /**
     * 统计页“重试”：只把终态失败（BLOCKED/FAILED）放回 RETRY_WAIT，
     * 复用原任务（幂等键不变），不造重复作业（设计 §6 “人工重试不造重复作业”）。
     */
    @Query(
        "UPDATE knowledge_index_jobs SET status = 'RETRY_WAIT', next_retry_at = :now, lease_token = NULL, " +
            "lease_until = NULL, updated_at = :now WHERE user_id = :userId AND status IN ('BLOCKED', 'FAILED')",
    )
    suspend fun requeueFailedForUser(userId: Long, now: Long): Int

    /**
     * 统计页“取消待处理”：只取消尚未开始的任务（PENDING/RETRY_WAIT）。
     * 运行中的 claim、已发布代次与原文不受影响；不删除知识库内容。
     */
    @Query(
        "UPDATE knowledge_index_jobs SET status = 'CANCELLED', next_retry_at = NULL, lease_token = NULL, " +
            "lease_until = NULL, updated_at = :now WHERE user_id = :userId AND status IN ('PENDING', 'RETRY_WAIT')",
    )
    suspend fun cancelPendingForUser(userId: Long, now: Long): Int

    /** 续租：只有仍持有同一 token 且未终态才延长 lease_until。 */
    @Query(
        "UPDATE knowledge_index_jobs SET lease_until = :leaseUntil, updated_at = :now " +
            "WHERE id = :jobId AND lease_token = :leaseToken AND status IN ('CLAIMED', 'RUNNING')",
    )
    suspend fun heartbeat(jobId: String, leaseToken: String, leaseUntil: Long, now: Long): Int
}
