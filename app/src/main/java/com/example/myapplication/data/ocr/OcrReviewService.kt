package com.example.myapplication.data.ocr

import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.domain.reader.ExtractionQuality
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Current-owner gate for promoting one reviewed OCR page into searchable evidence. */
class OcrReviewService(
    private val database: AppDatabase,
    private val store: OcrCheckpointStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface Outcome {
        data class Confirmed(val checkpoint: OcrCheckpoint) : Outcome
        data class AlreadyVerified(val checkpoint: OcrCheckpoint) : Outcome
        data class Rejected(val code: String, val message: String) : Outcome
    }

    suspend fun confirmPage(userId: Long, documentId: String, pageIndex: Int): Outcome {
        if (userId <= 0L || documentId.isBlank() || pageIndex < 0) {
            return Outcome.Rejected("INVALID_REVIEW_REQUEST", "复核请求无效")
        }
        val document = database.readerCatalogDao().findDocument(documentId)
            ?: return Outcome.Rejected("DOCUMENT_NOT_FOUND", "文档不存在或已删除")
        if (document.ownerUserId != userId || document.deletedAt != null) {
            return Outcome.Rejected("DOCUMENT_NOT_OWNED", "只能复核当前账号拥有的文档")
        }
        if (document.format != "PDF" || document.importStatus != "READY") {
            return Outcome.Rejected("DOCUMENT_NOT_READY", "当前文档不能进行 OCR 复核")
        }
        val source = document.blobPath?.let(::File)
            ?: return Outcome.Rejected("SOURCE_MISSING", "源 PDF 不存在")
        if (!source.isFile) return Outcome.Rejected("SOURCE_MISSING", "源 PDF 不存在")

        val checkpoint = store.load(documentId)
            ?: return Outcome.Rejected("OCR_CHECKPOINT_MISSING", "OCR 检查点不存在，请重新识别")
        val actualHash = try {
            withContext(Dispatchers.IO) { FileHashes.sha256(source) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return Outcome.Rejected("SOURCE_HASH_FAILED", "无法校验源 PDF")
        }
        if (checkpoint.documentId != documentId ||
            checkpoint.sourceFileHash != actualHash ||
            checkpoint.extractorVersion != OcrRevisionPublisher.EXTRACTOR_VERSION
        ) {
            return Outcome.Rejected("OCR_CHECKPOINT_STALE", "源文件或抽取版本已变化，请重新识别")
        }

        val page = checkpoint.page(pageIndex)
            ?: return Outcome.Rejected("OCR_PAGE_MISSING", "该页尚无 OCR 结果")
        if (page.humanVerifiedByUserId == userId && page.humanVerifiedAt != null) {
            return Outcome.AlreadyVerified(checkpoint)
        }
        if (page.quality != ExtractionQuality.OCR_REVIEW_REQUIRED || page.text.isBlank() || page.boxes.isEmpty()) {
            return Outcome.Rejected("OCR_PAGE_NOT_REVIEWABLE", "该页没有可复核的 OCR 文本")
        }

        val verified = page.copy(
            quality = ExtractionQuality.VERIFIED_TEXT,
            humanVerifiedByUserId = userId,
            humanVerifiedAt = clock(),
        )
        val updated = checkpoint.copy(
            updatedAt = clock(),
            pages = checkpoint.pages.map { if (it.pageIndex == pageIndex) verified else it },
        )
        return try {
            withContext(Dispatchers.IO) { store.save(updated) }
            Outcome.Confirmed(updated)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Outcome.Rejected("OCR_CHECKPOINT_WRITE_FAILED", "无法保存复核结果")
        }
    }
}
