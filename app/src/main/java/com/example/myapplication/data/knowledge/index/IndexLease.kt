package com.example.myapplication.data.knowledge.index

import com.example.myapplication.data.local.dao.KnowledgeJobDao
import com.example.myapplication.data.local.entity.KnowledgeIndexJobEntity
import java.util.UUID

/**
 * CAS 租约（设计 §6 / FX-08）。
 *
 * 只有 changedRows=1 的 claim 才取得写权；每次 heartbeat 与发布都携带
 * 同一 leaseToken。过期租约可被另一 worker 重新 claim，旧 worker 之后
 * 的 publish 必须得到 STALE_CLAIM。
 */
class IndexLease(
    private val jobDao: KnowledgeJobDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Claimed(val job: KnowledgeIndexJobEntity, val leaseToken: String, val leaseUntil: Long)

    sealed interface PublishCheck {
        data object Ok : PublishCheck
        data object StaleClaim : PublishCheck
        data class NotFound(val reason: String) : PublishCheck
    }

    /** 尝试 claim；失败（已被领取或未到期）返回 null。 */
    suspend fun tryClaim(jobId: String, leaseMillis: Long = DEFAULT_LEASE_MILLIS): Claimed? {
        val now = clock()
        val token = UUID.randomUUID().toString()
        val until = now + leaseMillis
        val changed = jobDao.claim(jobId, token, until, now)
        if (changed != 1) return null
        val job = jobDao.find(jobId) ?: return null
        return Claimed(job, token, until)
    }

    suspend fun markRunning(claimed: Claimed): Boolean =
        jobDao.markRunning(claimed.job.id, claimed.leaseToken, clock()) == 1

    /** 续租：仍持有同一 token 且未终态才成功；旧 worker 失去租约后返回 null。 */
    suspend fun heartbeat(claimed: Claimed, leaseMillis: Long = DEFAULT_LEASE_MILLIS): Claimed? {
        val now = clock()
        val until = now + leaseMillis
        val changed = jobDao.heartbeat(claimed.job.id, claimed.leaseToken, until, now)
        return if (changed == 1) claimed.copy(leaseUntil = until) else null
    }

    /** 发布前检查：token 仍匹配且租约未过期。 */
    suspend fun checkPublishable(claimed: Claimed): PublishCheck {
        val now = clock()
        val job = jobDao.find(claimed.job.id) ?: return PublishCheck.NotFound("job missing")
        if (job.leaseToken != claimed.leaseToken) return PublishCheck.StaleClaim
        if (job.leaseUntil == null || job.leaseUntil <= now) return PublishCheck.StaleClaim
        if (job.status != "CLAIMED" && job.status != "RUNNING") return PublishCheck.StaleClaim
        return PublishCheck.Ok
    }

    suspend fun finish(
        claimed: Claimed,
        status: String = "DONE",
        progressJson: String = "{}",
        errorCode: String? = null,
    ): Boolean = jobDao.finish(claimed.job.id, claimed.leaseToken, status, progressJson, errorCode, clock()) == 1

    suspend fun retryLater(claimed: Claimed, retryAt: Long, errorCode: String, progressJson: String = "{}"): Boolean =
        jobDao.retryLater(claimed.job.id, claimed.leaseToken, retryAt, errorCode, progressJson, clock()) == 1

    /**
     * 进程重启回收：把全部未终态租约重新排队（不依赖系统时钟回拨）。
     * 只在确认本进程没有活跃 worker 时调用（启动 reconcile / 测试）。
     */
    suspend fun requeueStaleLeases(): Int = jobDao.requeueStaleLeases(clock())

    /** 运行期周期回收：只回收已过期租约，不打断活跃 worker。 */
    suspend fun requeueExpiredLeases(): Int = jobDao.requeueExpiredLeases(clock())

    companion object {
        const val DEFAULT_LEASE_MILLIS = 2 * 60_000L
    }
}
