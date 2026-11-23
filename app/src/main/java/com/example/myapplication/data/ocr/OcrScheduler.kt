package com.example.myapplication.data.ocr

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow

/**
 * OCR 排队：每本书一个 unique work，重复入队保持已有任务（KEEP）。
 * 用户在阅读时后台补 OCR；取消按文档取消即可（WorkManager cancelUniqueWork）。
 */
object OcrScheduler {
    fun observe(context: Context, documentId: String): Flow<List<WorkInfo>> =
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosForUniqueWorkFlow(uniqueName(documentId))

    fun enqueue(context: Context, userId: Long, documentId: String) {
        if (userId <= 0L || documentId.isBlank()) return
        val request = OneTimeWorkRequestBuilder<OcrBackgroundWorker>()
            .setInputData(
                workDataOf(
                    OcrBackgroundWorker.KEY_DOCUMENT_ID to documentId,
                    OcrBackgroundWorker.KEY_USER_ID to userId,
                ),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(uniqueName(documentId), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context, documentId: String) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(uniqueName(documentId))
    }

    fun uniqueName(documentId: String): String = "ocr-$documentId"
}
