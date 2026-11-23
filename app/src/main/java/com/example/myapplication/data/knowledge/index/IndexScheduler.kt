package com.example.myapplication.data.knowledge.index

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * 索引唤醒调度（设计 §6 / FX-29）。
 *
 * - 唯一工作名按用户命名空间：`knowledge-index-u<userId>`；
 * - 使用 APPEND_OR_REPLACE 而不是 KEEP：旧 worker 即将结束时新 job 入队不会被吞掉；
 * - 任务状态权威是 outbox，WorkManager 只负责唤醒；重复唤醒由 lease CAS 去重。
 */
object IndexScheduler {
    fun enqueue(context: Context, userId: Long) {
        if (userId <= 0L) return
        val request = OneTimeWorkRequestBuilder<IndexWorker>()
            .setInputData(workDataOf(IndexWorker.KEY_USER_ID to userId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(workName(userId), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun workName(userId: Long): String = "knowledge-index-u$userId"
}
