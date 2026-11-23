package com.example.myapplication.data.ocr

/**
 * WorkManager 结果策略（纯函数，便于 JVM 单测）。
 *
 * - 已发布/已存在/无扫描页/无已验证文本 → 成功，不重试；
 * - 引擎不可用 → 失败（等模型配置后由用户重试，不在后台无限重试）；
 * - 源文件缺失/非本人/未就绪 → 失败；
 * - 存储/渲染/发布这类瞬时问题 → 重试（检查点保证不重复识别）。
 */
object OcrWorkPolicy {
    enum class Disposition { SUCCESS, RETRY, FAILURE }

    fun dispositionOf(outcome: OcrDocumentProcessor.Outcome): Disposition = when (outcome) {
        is OcrDocumentProcessor.Outcome.Published,
        is OcrDocumentProcessor.Outcome.AlreadyPublished,
        is OcrDocumentProcessor.Outcome.NothingToDo,
        is OcrDocumentProcessor.Outcome.CompletedWithoutPublication,
        -> Disposition.SUCCESS

        is OcrDocumentProcessor.Outcome.Unavailable -> Disposition.FAILURE
        is OcrDocumentProcessor.Outcome.Failed -> when (outcome.code) {
            "DOCUMENT_NOT_FOUND",
            "DOCUMENT_NOT_OWNED",
            "DOCUMENT_NOT_READY",
            "SOURCE_MISSING",
            -> Disposition.FAILURE

            else -> Disposition.RETRY
        }
    }
}
