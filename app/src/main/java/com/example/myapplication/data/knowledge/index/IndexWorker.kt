package com.example.myapplication.data.knowledge.index

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.myapplication.LearningApplication
import kotlinx.coroutines.CancellationException

/**
 * 索引 Worker（设计 §6 / FX-29）。
 *
 * 只持 userId，通过同一个 [LearningApplication.container] 访问账本；
 * 不自己构造 Room/容器（避免双初始化）；租约失效时发布会被拒（STALE_CLAIM）。
 * 未登录/无用户输入直接失败，不执行任何外发。
 */
class IndexWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val userId = inputData.getLong(KEY_USER_ID, -1L)
        if (userId <= 0L) return Result.failure()
        val application = applicationContext as? LearningApplication ?: return Result.failure()
        val container = application.container
        return try {
            // 运行期只回收已过期租约，不打断其他活跃 worker
            container.indexProcessor.reclaimExpired()
            container.indexRunner.runForUser(userId)
            if (container.indexRunner.hasPending(userId)) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val KEY_USER_ID = "knowledge_index_user_id"
    }
}
