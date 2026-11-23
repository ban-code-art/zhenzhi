package com.example.myapplication.data.reader.importer

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.room.withTransaction
import com.example.myapplication.AppContainer
import com.example.myapplication.LearningApplication
import com.example.myapplication.data.reader.storage.DocumentStorage
import com.example.myapplication.data.reader.storage.DocumentStorageException
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 一期导入后台 Worker（P1-12a 清理 / P1-12b 有界重试）。
 *
 * 成功路径保持原行为：解析 → 校验 → 原子发布 → 删本 job staging。
 * 失败/取消路径补齐两件事：
 *  1. 清理本 job 的 staging（重试还需要 source，所以可重试失败只删派生目录；
 *     终态失败与取消删整个 job staging），清理异常只吞掉，不掩盖原始失败；
 *  2. 每次尝试前把尝试次数自增并持久化，超过上限转 FAILED 不再 `Result.retry()`；
 *     取消（协程取消）把 job 置为既有终态 CANCELLED 并清理 staging。
 */
class ReaderImportWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val application = applicationContext as? LearningApplication ?: return Result.failure()
        val container = application.container
        val storage = container.documentStorage
        val dao = container.database.readerJobDao()
        var stage = ImportStage.PARSING.name
        var attempt = 1
        return try {
            val record = dao.find(jobId) ?: return Result.failure()
            if (record.kind != "IMPORT") return Result.failure()
            val job = container.importJobStore.findJob(jobId) ?: return Result.failure()
            if (job.status !in ACTIVE_STATUSES) return stoppedResult(container, jobId)
            attempt = ReaderImportRetryPolicy.attemptOf(job.progressJson)
            if (dao.updateAttemptIfActive(jobId, ReaderImportRetryPolicy.progressJsonForAttempt(job.progressJson, attempt),
                    System.currentTimeMillis()) != 1) return stoppedResult(container, jobId)
            if (dao.markRunning(jobId, stage, System.currentTimeMillis()) != 1) return stoppedResult(container, jobId)
            cleanupStaging(storage, jobId, keepSource = true)
            val stagedFile = job.stagedPath?.let(::File)
                ?: return fail(container, jobId, stage, "SOURCE_PERMISSION_LOST", true, attempt)
            if (!stagedFile.isFile) return fail(container, jobId, stage, "SOURCE_PERMISSION_LOST", true, attempt)
            val format = job.format
                ?: return fail(container, jobId, ImportStage.VALIDATING.name, "UNSUPPORTED_FORMAT", false, attempt)
            val buildDirectory = File(storage.stagingJobDirectory(jobId), "build-${UUID.randomUUID()}").apply { mkdirs() }
            val sourceHash = withContext(Dispatchers.IO) { storage.sha256Of(stagedFile) }
            val existing = container.database.readerCatalogDao()
                .findImportedByHashIncludingDeleted(job.userId, sourceHash)
                ?.takeIf { it.importStatus == "READY" }
            if (existing != null) {
                withContext(NonCancellable) { container.database.withTransaction {
                    val active = dao.find(jobId)
                    if (active == null || active.userId != job.userId || active.kind != "IMPORT" || active.status !in ACTIVE_STATUSES)
                        throw ImportPublicationStoppedException(active?.status)
                    val current = container.database.readerCatalogDao().findDocumentIncludingDeleted(existing.id)
                        ?: throw ImportPublicationStoppedException("DOCUMENT_CHANGED")
                    check(current.ownerUserId == job.userId && current.importStatus == "READY" && current.fileSha256 == sourceHash)
                    if (current.deletedAt != null) container.database.readerCatalogDao().restore(current.id, job.userId, System.currentTimeMillis())
                    check(dao.markPublished(jobId, current.id, current.contentRevision, System.currentTimeMillis()) == 1)
                } }
                // 同 hash 恢复既有文档：索引登记可能缺失（旧 schema/被删除后重建），
                // 这里只唤醒 runner 补齐；userId<=0 时 scheduleIndexing 直接 return，不外发。
                container.scheduleIndexing(job.userId)
                cleanupStaging(storage, jobId, keepSource = false)
                return Result.success()
            }
            val parsed = withContext(Dispatchers.IO) {
                ReaderImportParserSelection.parserFor(storage, jobId, format)
                    .parse(stagedFile, buildDirectory, job.displayName)
            }
            // 发布前复核一次 job 终态：取消/被别的路径结掉就不发布（设计 §11.1）。
            stage = ImportStage.PUBLISHING.name
            if (dao.markRunning(jobId, stage, System.currentTimeMillis()) != 1) return stoppedResult(container, jobId)
            withContext(Dispatchers.IO) {
                container.importPublisher.publish(
                    job = job,
                    parsed = parsed,
                    sourceFile = stagedFile,
                    sourceSha256 = sourceHash,
                    buildDirectory = buildDirectory,
                    requireActiveJob = true,
                )
            }
            // Publisher committed document and COMPLETED together; never write a stale snapshot here.
            // 发布事务内已登记 knowledge_document + LOCAL outbox（设计 §6），
            // 这里只需唤醒 Worker 消费；未登录/无用户（userId<=0）不外发。
            container.scheduleIndexing(job.userId)
            cleanupStaging(storage, jobId, keepSource = false)
            Result.success()
        } catch (cancelled: CancellationException) {
            // 协程取消 = WorkManager 停止该 work：落既有终态 CANCELLED 并清理 staging。
            // NonCancellable：取消后仍要完成落库与清理，且清理失败不能覆盖取消本身。
            withContext(NonCancellable) {
                runCatching { markCancelled(container, jobId) }.onFailure {
                    android.util.Log.w("ReaderImportWorker", "Could not persist cancellation: ${it.javaClass.simpleName}")
                }
                cleanupStaging(storage, jobId, keepSource = false)
            }
            throw cancelled
        } catch (_: ImportPublicationStoppedException) {
            stoppedResult(container, jobId)
        } catch (error: DocumentParseException) {
            fail(container, jobId, stage, error.code, error.code in RETRYABLE_CODES, attempt)
        } catch (error: DocumentStorageException) {
            fail(container, jobId, stage, "STORAGE_FAILURE", retryable = true, attempt = attempt)
        } catch (error: IOException) {
            fail(container, jobId, stage, "STORAGE_FAILURE", retryable = true, attempt = attempt)
        } catch (error: Exception) {
            fail(container, jobId, stage, "CORRUPT_INPUT", retryable = false, attempt = attempt)
        }
    }

    /**
     * 失败落库 + 有界重试（P1-12a / P1-12b）。
     *
     * - 还能重试：保留 source（否则下一轮无源可读），删除本次派生目录；
     * - 已达上限或不可重试：job 落 FAILED（既有状态机），删除整个 job staging。
     */
    private suspend fun fail(
        container: AppContainer,
        jobId: String,
        stage: String,
        code: String,
        retryable: Boolean,
        attempt: Int,
    ): Result {
        val willRetry = ReaderImportRetryPolicy.canRetry(attempt, retryable)
        val dao = container.database.readerJobDao()
        val changed = if (willRetry) dao.markRetryWait(jobId, stage, code, System.currentTimeMillis())
            else dao.markFailed(jobId, stage, code, System.currentTimeMillis())
        if (changed != 1) return stoppedResult(container, jobId)
        cleanupStaging(container.documentStorage, jobId, keepSource = willRetry)
        return if (willRetry) Result.retry() else Result.failure()
    }

    /** 取消落既有终态 CANCELLED（ImportStatus 已有该枚举），已完成的 job 不回退。 */
    private suspend fun markCancelled(container: AppContainer, jobId: String) {
        val dao = container.database.readerJobDao()
        val current = dao.find(jobId) ?: return
        dao.cancelImport(jobId, current.userId, System.currentTimeMillis())
    }

    /** A terminal CAS winner is never reverted by a stale worker. */
    private suspend fun stoppedResult(container: AppContainer, jobId: String): Result {
        val dao = container.database.readerJobDao()
        val current = dao.find(jobId)
        if (current != null && current.kind != "IMPORT") return Result.failure()
        val status = current?.status
        if (status in ACTIVE_STATUSES) dao.markFailed(jobId, ImportStage.PUBLISHING.name, "IMPORT_JOB_INVALID", System.currentTimeMillis())
        cleanupStaging(container.documentStorage, jobId, keepSource = false)
        return if (status == "COMPLETED") Result.success() else Result.failure()
    }

    /**
     * 清理本 job 的 staging（P1-12a）。异常已在 [ReaderImportStagingCleanup] 内吞掉：
     * 清理失败不得掩盖原始失败/取消。
     */
    private suspend fun cleanupStaging(storage: DocumentStorage, jobId: String, keepSource: Boolean) {
        withContext(Dispatchers.IO) { ReaderImportStagingCleanup.clean(storage, jobId, keepSource) }
    }

    companion object {
        const val KEY_JOB_ID = "reader_import_job_id"
        private val RETRYABLE_CODES = setOf("SOURCE_PERMISSION_LOST", "STORAGE_FAILURE", "NETWORK_TIMEOUT")
        private val ACTIVE_STATUSES = setOf("PENDING", "RUNNING", "RETRY_WAIT")
    }
}

/**
 * M04：Worker 的解析器选择（纯函数，便于 JVM 单测）。Markdown 只读导入入口已采集到本 job staging
 * 的附件副本（`attachments/`），不再访问外部 URI；旧 job 没有附件索引时保持原解析行为。
 */
object ReaderImportParserSelection {
    fun parserFor(storage: DocumentStorage, jobId: String, format: ReaderFormat): DocumentParser =
        if (format == ReaderFormat.MARKDOWN) {
            MarkdownDocumentParser(
                StagedMarkdownAttachments.load(StagedMarkdownAttachments.directoryFor(storage.stagingJobDirectory(jobId))),
            )
        } else {
            DocumentParserFactory.create(format)
        }
}

/**
 * P1-12a：导入 staging 清理（纯文件操作，便于 JVM 单测）。
 *
 * 只碰本 job 的 staging 目录（`staging/<sha256(jobId)>/`），不触碰其它 job 与已发布文档。
 */
object ReaderImportStagingCleanup {
    /**
     * @param keepSource 为 true 时保留 `source-<sha>.bin` 与 M04 Markdown 附件副本目录 `attachments/`
     *   （附件只在导入入口授权期内采集，重试时外部授权可能已失效）。可重试失败必须保源，否则下一轮
     *   retry 无源可读），只删除 `build-*`/`revisions` 等派生目录；为 false 时删除整个
     *   job staging（终态失败 / 取消 / 成功发布后）。
     * 任何清理异常都被吞掉：清理失败不得掩盖原始失败或取消。
     */
    fun clean(storage: DocumentStorage, jobId: String, keepSource: Boolean) {
        try {
            val jobDirectory = storage.stagingJobDirectory(jobId)
            if (!jobDirectory.exists()) return
            if (!keepSource) {
                runCatching { storage.deleteStagingJob(jobId) }
                return
            }
            jobDirectory.listFiles()?.forEach { child ->
                val isSource = child.isFile &&
                    child.name.startsWith(SOURCE_FILE_PREFIX) &&
                    child.name.endsWith(SOURCE_FILE_SUFFIX)
                val isAttachments = child.isDirectory && child.name == StagedMarkdownAttachments.DIRECTORY
                if (!isSource && !isAttachments) {
                    runCatching { if (child.isDirectory) child.deleteRecursively() else child.delete() }
                }
            }
        } catch (_: Exception) {
            // 清理失败不改变原始结果。
        }
    }

    private const val SOURCE_FILE_PREFIX = "source-"
    private const val SOURCE_FILE_SUFFIX = ".bin"
}

/**
 * 有界重试策略（P1-12b，纯函数，便于 JVM 单测）。
 *
 * 关于 attempts 的落点：`reader_jobs.attempts` 列已存在，但既有写入入口
 * （`ImportJobStore.update` → `ReaderJobDao.updateProgress`）不写该列，
 * 因此尝试次数仍落在既有 `progress_json` 扩展槽；Worker通过活跃状态CAS自增，
 * 缺省 0 表示"尚未尝试"。若之后给 `updateProgress` 增加 `attempts` 参数，可无缝迁移。
 *
 * 上限语义：每轮尝试前先自增；attempt 为 1 起。`attempt < MAX_ATTEMPTS` 时才允许
 * `Result.retry()`，第 MAX_ATTEMPTS 次失败即转 FAILED，不会无界重试。
 */
object ReaderImportRetryPolicy {
    const val MAX_ATTEMPTS = 3
    private const val ATTEMPTS_KEY = "attempts"

    /** 已持久化的尝试次数（缺省/损坏时为 0）。 */
    fun attemptsOf(progressJson: String): Int {
        val root = parseObject(progressJson) ?: return 0
        val value = (root[ATTEMPTS_KEY] as? JsonPrimitive)?.content?.toIntOrNull() ?: return 0
        return value.coerceAtLeast(0)
    }

    /** 本次尝试的序号（1 起）。 */
    fun attemptOf(progressJson: String): Int = attemptsOf(progressJson) + 1

    /** 把本次尝试序号写回 progress_json，保留 bytes/sha256/format/displayName。 */
    fun progressJsonForAttempt(progressJson: String, attempt: Int): String {
        val root = parseObject(progressJson) ?: JsonObject(emptyMap())
        val merged = JsonObject(
            root.toMutableMap().apply { put(ATTEMPTS_KEY, JsonPrimitive(attempt.coerceAtLeast(1))) },
        )
        return merged.toString()
    }

    /** 可重试失败是否还能再试一次；超过上限一律终态失败。 */
    fun canRetry(attempt: Int, retryable: Boolean): Boolean =
        retryable && attempt in 1 until MAX_ATTEMPTS

    /**
     * 取消时应该写入的终态：已完成/已取消不回退（null 表示保持现状），
     * 其余按既有 `ImportStatus.CANCELLED` 落库（P1-12b ③）。
     */
    fun cancelledStatusFor(currentStatus: String): String? = when (currentStatus) {
        ImportStatus.PENDING.name, ImportStatus.RUNNING.name, ImportStatus.RETRY_WAIT.name -> ImportStatus.CANCELLED.name
        else -> null
    }

    private fun parseObject(progressJson: String): JsonObject? =
        runCatching { Json.parseToJsonElement(progressJson) as? JsonObject }.getOrNull()
}
