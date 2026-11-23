package com.example.myapplication.data.knowledge.index

import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.domain.reader.AccessContext
import kotlinx.coroutines.CancellationException

/**
 * 索引任务编排（设计 §6）。
 *
 * 只编排，不决定权限：LOCAL 走 [IndexPublisher]，VECTOR 走 [VectorIndexer]。
 * Worker 与前台执行共享同一 claim 服务，不能双写。
 * 每次运行有界（[maxJobsPerRun]），避免一次唤醒跑满电量。
 */
class IndexProcessor(
    private val database: AppDatabase,
    private val publisher: IndexPublisher,
    private val vectorIndexer: VectorIndexer,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Report(
        val localProcessed: Int,
        val vectorProcessed: Int,
        val blocked: Int,
        val stale: Int,
    )

    /**
     * 处理到期任务。[profileFingerprint] 是当前选中的 embedding profile；
     * 空串表示未配置 embedding：只发布词法，不外发任何内容。
     */
    suspend fun runDue(
        ctx: AccessContext,
        profileFingerprint: String,
        maxJobsPerRun: Int = DEFAULT_MAX_JOBS,
    ): Report {
        var local = 0
        var vector = 0
        var blocked = 0
        var stale = 0
        val now = clock()

        // PENDING 任务按 phase 路由：LOCAL 走 publisher，VECTOR 走 vectorIndexer。
        // 不能把所有 PENDING 都交给 publisher（会错误地重发 LOCAL 发布）。
        val duePending = database.knowledgeJobDao().findDue(IndexOutbox.STATUS_PENDING, now, maxJobsPerRun)
        for (job in duePending) {
            if (job.userId != ctx.userId) continue
            when (job.phase) {
                IndexOutbox.PHASE_LOCAL -> when (publisher.processLocalJob(job.id, ctx, profileFingerprint)) {
                    is IndexPublisher.Outcome.Published -> local += 1
                    is IndexPublisher.Outcome.Blocked -> blocked += 1
                    is IndexPublisher.Outcome.StaleClaim -> stale += 1
                    else -> Unit
                }

                IndexOutbox.PHASE_VECTOR -> when (vectorIndexer.processVectorJob(job.id, ctx.userId)) {
                    is VectorIndexer.Outcome.Done -> vector += 1
                    is VectorIndexer.Outcome.Blocked -> blocked += 1
                    is VectorIndexer.Outcome.StaleClaim -> stale += 1
                    else -> Unit
                }

                else -> Unit
            }
        }

        val dueRetry = database.knowledgeJobDao().findDue("RETRY_WAIT", now, maxJobsPerRun)
        for (job in dueRetry) {
            if (job.userId != ctx.userId) continue
            when (job.phase) {
                IndexOutbox.PHASE_LOCAL -> when (publisher.processLocalJob(job.id, ctx, profileFingerprint)) {
                    is IndexPublisher.Outcome.Published -> local += 1
                    is IndexPublisher.Outcome.Blocked -> blocked += 1
                    is IndexPublisher.Outcome.StaleClaim -> stale += 1
                    else -> Unit
                }

                IndexOutbox.PHASE_VECTOR -> when (vectorIndexer.processVectorJob(job.id, ctx.userId)) {
                    is VectorIndexer.Outcome.Done -> vector += 1
                    is VectorIndexer.Outcome.Blocked -> blocked += 1
                    is VectorIndexer.Outcome.StaleClaim -> stale += 1
                    else -> Unit
                }

                else -> Unit
            }
        }
        return Report(local, vector, blocked, stale)
    }

    /**
     * 启动恢复：回收非终态租约 + 恢复过期任务（不重放用户未确认的写笔记）。
     * 只在应用启动、确认无活跃 worker 时调用。
     */
    suspend fun recover(): Int {
        val requeued = database.knowledgeJobDao().requeueStaleLeases(clock())
        return requeued
    }

    /**
     * 运行期回收：只回收已过期租约，不动仍在有效期内的活跃 worker。
     * Worker 每轮开始调用；启动期恢复用 [recover]。
     */
    suspend fun reclaimExpired(): Int = database.knowledgeJobDao().requeueExpiredLeases(clock())

    companion object {
        const val DEFAULT_MAX_JOBS = 8
    }
}
