package com.example.myapplication.data.ocr

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.myapplication.LearningApplication

/**
 * 后台 OCR（W5-03）：导入后可排队，处理器内部自己选 no-text 页、按页检查点、发布新代次。
 *
 * 约束：电量不低 + 存储不低（设计 §3 电池/温度策略的最小实现）；本 worker 不联网。
 */
class OcrBackgroundWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val documentId = inputData.getString(KEY_DOCUMENT_ID) ?: return Result.failure()
        val userId = inputData.getLong(KEY_USER_ID, 0L)
        if (userId <= 0L) return Result.failure()
        val application = applicationContext as? LearningApplication ?: return Result.failure()
        val outcome = application.container.ocrDocumentProcessor.process(userId, documentId) { done, total, pageIndex ->
            setProgress(
                workDataOf(
                    KEY_PROGRESS_DONE to done,
                    KEY_PROGRESS_TOTAL to total,
                    KEY_PROGRESS_PAGE to pageIndex,
                ),
            )
        }
        val output = when (outcome) {
            is OcrDocumentProcessor.Outcome.Published -> workDataOf(
                KEY_OUTCOME_CODE to "PUBLISHED",
                KEY_PUBLISHED_PAGES to outcome.publishedPages,
                KEY_REVIEW_REQUIRED_PAGES to outcome.report.checkpoint.pages.count {
                    it.quality == com.example.myapplication.domain.reader.ExtractionQuality.OCR_REVIEW_REQUIRED
                },
            )
            is OcrDocumentProcessor.Outcome.AlreadyPublished -> workDataOf(KEY_OUTCOME_CODE to "ALREADY_PUBLISHED")
            is OcrDocumentProcessor.Outcome.CompletedWithoutPublication -> workDataOf(
                KEY_OUTCOME_CODE to "COMPLETED_WITHOUT_PUBLICATION",
                KEY_OUTCOME_MESSAGE to outcome.reason,
            )
            is OcrDocumentProcessor.Outcome.NothingToDo -> workDataOf(
                KEY_OUTCOME_CODE to "NOTHING_TO_DO",
                KEY_OUTCOME_MESSAGE to outcome.reason,
            )
            is OcrDocumentProcessor.Outcome.Unavailable -> workDataOf(
                KEY_OUTCOME_CODE to outcome.code,
                KEY_OUTCOME_MESSAGE to outcome.message,
            )
            is OcrDocumentProcessor.Outcome.Failed -> workDataOf(
                KEY_OUTCOME_CODE to outcome.code,
                KEY_OUTCOME_MESSAGE to outcome.message,
            )
        }
        return when (OcrWorkPolicy.dispositionOf(outcome)) {
            OcrWorkPolicy.Disposition.SUCCESS -> Result.success(output)
            OcrWorkPolicy.Disposition.RETRY -> {
                if (runAttemptCount + 1 >= MAX_ATTEMPTS) Result.failure(output) else Result.retry()
            }

            OcrWorkPolicy.Disposition.FAILURE -> Result.failure(output)
        }
    }

    companion object {
        const val KEY_DOCUMENT_ID = "ocr_document_id"
        const val KEY_USER_ID = "ocr_user_id"
        const val KEY_PROGRESS_DONE = "ocr_progress_done"
        const val KEY_PROGRESS_TOTAL = "ocr_progress_total"
        const val KEY_PROGRESS_PAGE = "ocr_progress_page"
        const val KEY_OUTCOME_CODE = "ocr_outcome_code"
        const val KEY_OUTCOME_MESSAGE = "ocr_outcome_message"
        const val KEY_PUBLISHED_PAGES = "ocr_published_pages"
        const val KEY_REVIEW_REQUIRED_PAGES = "ocr_review_required_pages"
        const val MAX_ATTEMPTS = 3
    }
}
