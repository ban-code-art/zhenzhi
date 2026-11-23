package com.example.myapplication.data.ocr

import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.reader.storage.DocumentStorage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * OCR 一次完整处理：选页 → 逐页识别/检查点 → 发布新代次。
 *
 * 只处理当前代次里明确标 `no-text` 的页；有文字层的页不送 OCR，避免重复索引与覆盖。
 * 失败/取消不会清掉检查点，下一次从已完成页继续；发布失败时检查点仍在，可重试发布。
 */
class OcrDocumentProcessor(
    private val database: AppDatabase,
    private val storage: DocumentStorage,
    private val worker: OcrWorker,
    private val publisher: OcrRevisionPublisher,
    private val store: OcrCheckpointStore,
) {
    sealed interface Outcome {
        data class Published(
            val report: OcrWorker.Report,
            val revision: String,
            val indexGeneration: Long?,
            val publishedPages: Int = report.processed.size,
        ) : Outcome

        data class AlreadyPublished(val report: OcrWorker.Report, val revision: String) : Outcome
        data class CompletedWithoutPublication(val report: OcrWorker.Report, val reason: String) : Outcome
        data class NothingToDo(val reason: String) : Outcome
        data class Unavailable(val code: String, val message: String) : Outcome
        data class Failed(val code: String, val message: String) : Outcome
    }

    suspend fun process(
        userId: Long,
        documentId: String,
        onProgress: suspend (done: Int, total: Int, pageIndex: Int) -> Unit = { _, _, _ -> },
    ): Outcome {
        val document = database.readerCatalogDao().findDocument(documentId)
            ?: return Outcome.Failed("DOCUMENT_NOT_FOUND", "文档不存在或已删除")
        if (document.ownerUserId != userId) {
            return Outcome.Failed("DOCUMENT_NOT_OWNED", "只能处理当前用户的文档")
        }
        if (document.importStatus != "READY") {
            return Outcome.Failed("DOCUMENT_NOT_READY", "文档尚未导入完成")
        }
        val sourcePath = document.blobPath
            ?: return Outcome.Failed("SOURCE_MISSING", "文档没有源文件路径")
        val source = File(sourcePath)
        if (!source.isFile) return Outcome.Failed("SOURCE_MISSING", "源文件不存在")
        val revision = try {
            storage.openRevision(documentId, document.contentRevision)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return Outcome.Failed("REVISION_UNREADABLE", error.javaClass.simpleName)
        } ?: return Outcome.Failed("REVISION_MISSING", "当前内容代次不可读")

        val pages = mutableListOf<Int>()
        try {
            revision.manifest.sections.forEach { section ->
                currentCoroutineContext().ensureActive()
                val pageIndex = section.pageStart ?: return@forEach
                val file = File(revision.directory, section.resourceHref.replace('/', File.separatorChar))
                if (!file.isFile) return@forEach
                if (ScannedPageDetector.needsOcr(file.readText(Charsets.UTF_8))) pages += pageIndex
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return Outcome.Failed("REVISION_UNREADABLE", error.javaClass.simpleName)
        }
        val selected = pages.distinct().sorted()
        if (selected.isEmpty()) return Outcome.NothingToDo("当前代次没有缺文字层的页")

        val run = worker.run(
            OcrWorker.Request(
                documentId = documentId,
                sourceFile = source,
                extractorVersion = OcrRevisionPublisher.EXTRACTOR_VERSION,
                pages = selected,
            ),
            onProgress = onProgress,
        )
        val report = when (run) {
            is OcrWorker.Outcome.Unavailable -> return Outcome.Unavailable(run.code, run.message)
            is OcrWorker.Outcome.Failed -> return Outcome.Failed(run.code, run.message)
            is OcrWorker.Outcome.Completed -> run.report
        }
        val checkpoint = store.load(documentId)
            ?: return Outcome.CompletedWithoutPublication(report, "没有生成检查点")
        return when (val publish = publisher.publish(documentId, checkpoint)) {
            is OcrRevisionPublisher.Outcome.Published -> Outcome.Published(
                report = report,
                revision = publish.revision,
                indexGeneration = publish.indexGeneration,
                publishedPages = publish.pages.size,
            )

            is OcrRevisionPublisher.Outcome.AlreadyPublished ->
                Outcome.AlreadyPublished(report, publish.revision)

            is OcrRevisionPublisher.Outcome.NothingToDo ->
                Outcome.CompletedWithoutPublication(report, publish.reason)

            is OcrRevisionPublisher.Outcome.Failed ->
                Outcome.Failed("OCR_PUBLISH_FAILED", "${publish.code}: ${publish.message}")
        }
    }
}
