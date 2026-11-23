package com.example.myapplication.data.reader.importer

import com.example.myapplication.data.reader.storage.DocumentStorage
import com.example.myapplication.data.reader.storage.DocumentStorageException
import com.example.myapplication.data.reader.storage.InvalidStoragePathException
import com.example.myapplication.data.reader.storage.SourceTooLargeException
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Coordinates the durable, restart-safe beginning of an import:
 *
 * SELECTED -> COPYING -> VALIDATING
 *
 * The source is copied into private staging and hashed before format detection.
 * Parsing and publishing are deliberately separate stages so a worker can
 * resume after a process death without re-reading an external SAF URI.
 */
class ImportCoordinator(
    private val storage: DocumentStorage,
    private val store: ImportJobStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newJobId: () -> String = { UUID.randomUUID().toString() },
    private val attachmentCollector: MarkdownAttachmentCollector = MarkdownAttachmentCollector(),
) {
    suspend fun enqueue(
        userId: Long,
        requestKey: String,
        source: ImportSource,
    ): ImportEnqueueResult {
        require(userId > 0) { "userId must be positive" }
        require(requestKey.isNotBlank()) { "requestKey must not be blank" }
        require(requestKey.length <= 256) { "requestKey is too long" }

        val normalizedKey = requestKey.trim()
        val existing = store.findByRequestKey(userId, normalizedKey)
        if (existing != null) return ImportEnqueueResult.Duplicate(existing)

        val now = clock()
        val jobId = newJobId()
        var job = ImportJobSnapshot(
            jobId = jobId,
            userId = userId,
            requestKey = normalizedKey,
            stage = ImportStage.SELECTED.name,
            status = ImportStatus.PENDING.name,
            createdAt = now,
            updatedAt = now,
        )
        return try {
            store.insert(job)
            job = job.copy(stage = ImportStage.COPYING.name, status = ImportStatus.RUNNING.name, updatedAt = clock())
            updateActive(job)
            val staged = withContext(Dispatchers.IO) {
                source.openStream().use { input -> storage.stageSource(jobId, input) }
            }
            job = job.copy(
                stage = ImportStage.VALIDATING.name,
                status = ImportStatus.PENDING.name,
                stagedPath = staged.file.absolutePath,
                progressJson = progressJson(staged.bytes, staged.sha256, null, source.displayName),
                errorCode = null,
                updatedAt = clock(),
            )
            updateActive(job)

            val detection = withContext(Dispatchers.IO) {
                // 用文件级嗅探：ZIP 容器（EPUB/FB2.zip/CBZ）必须读中央目录才能判定，
                // 只看前 4 KiB 会把它们一律判成 UNKNOWN（P5-01）。
                FormatDetector.detect(File(staged.file.absolutePath), source.displayName)
            }
            if (detection.format == ReaderFormat.UNKNOWN) {
                val failed = job.copy(
                    status = ImportStatus.FAILED.name,
                    errorCode = "UNSUPPORTED_FORMAT",
                    updatedAt = clock(),
                )
                updateActive(failed)
                ImportEnqueueResult.Failed(failed, "UNSUPPORTED_FORMAT", retryable = false)
            } else {
                if (detection.format == ReaderFormat.MARKDOWN) {
                    // M04：仍持有授权时采集相对图片；失败项逐条记录为显式状态，不影响正文导入。
                    withContext(Dispatchers.IO) {
                        try {
                            attachmentCollector.collect(
                                markdownFile = staged.file,
                                resolver = source.attachmentResolver,
                                directory = StagedMarkdownAttachments.directoryFor(storage.stagingJobDirectory(jobId)),
                            )
                        } catch (error: DocumentStorageException) {
                            throw error
                        } catch (error: IOException) {
                            // 读取私有 staging 副本失败属于存储问题，不是外部授权丢失。
                            throw DocumentStorageException("could not collect markdown attachments", error)
                        }
                    }
                }
                val accepted = job.copy(
                    format = detection.format,
                    progressJson = progressJson(staged.bytes, staged.sha256, detection.format, source.displayName),
                    updatedAt = clock(),
                )
                updateActive(accepted)
                ImportEnqueueResult.Accepted(accepted, detection)
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val latest = runCatching { store.findJob(jobId) }.getOrNull()
                if (latest != null && latest.status in ACTIVE_STATUSES) {
                    // RoomImportJobStore's DAO update is a CAS against active states.
                    runCatching { store.update(latest.copy(status = ImportStatus.CANCELLED.name,
                        stagedPath = null, errorCode = null, updatedAt = clock())) }
                }
            }
            throw cancelled
        } catch (stopped: ImportStoppedException) {
            ImportEnqueueResult.Failed(stopped.job, stopped.job.status, retryable = false)
        } catch (error: SourceTooLargeException) {
            fail(job, "LIMIT_EXCEEDED", retryable = false)
        } catch (error: InvalidStoragePathException) {
            fail(job, "INVALID_ARGUMENT", retryable = false)
        } catch (error: DocumentStorageException) {
            fail(job, "STORAGE_FAILURE", retryable = true)
        } catch (error: SecurityException) {
            fail(job, "SOURCE_PERMISSION_LOST", retryable = true)
        } catch (error: IOException) {
            fail(job, "SOURCE_PERMISSION_LOST", retryable = true)
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val latest = store.findJob(jobId)
            if (latest != null && latest.status !in ACTIVE_STATUSES)
                ImportEnqueueResult.Failed(latest, latest.status, retryable = false)
            else throw error
        } finally {
            // COPYING can be a foreground coroutine, so WorkManager cannot own this cleanup.
            withContext(NonCancellable + Dispatchers.IO) {
                val latest = runCatching { store.findJob(jobId) }.getOrNull()
                if (latest?.status in setOf(ImportStatus.CANCELLED.name, ImportStatus.FAILED.name)) {
                    runCatching { storage.deleteStagingJob(jobId) }
                }
            }
        }
    }

    private suspend fun updateActive(job: ImportJobSnapshot) {
        currentCoroutineContext().ensureActive()
        val latest = store.findJob(job.jobId) ?: throw IllegalStateException("import job no longer exists")
        if (latest.status !in ACTIVE_STATUSES) throw ImportStoppedException(latest)
        store.update(job)
        currentCoroutineContext().ensureActive()
    }

    private fun progressJson(
        bytes: Long,
        sha256: String,
        format: ReaderFormat?,
        displayName: String?,
    ): String {
        val formatPart = if (format != null) ",\"format\":\"${format.name}\"" else ""
        val displayPart = displayName?.let { ",\"displayName\":${kotlinx.serialization.json.JsonPrimitive(it)}" }.orEmpty()
        return """{"bytes":$bytes,"sha256":"$sha256"$formatPart$displayPart}"""
    }

    private suspend fun fail(
        job: ImportJobSnapshot,
        code: String,
        retryable: Boolean,
    ): ImportEnqueueResult.Failed {
        val latest = store.findJob(job.jobId)
        if (latest != null && latest.status !in ACTIVE_STATUSES)
            return ImportEnqueueResult.Failed(latest, latest.status, retryable = false)
        val failed = job.copy(
            status = ImportStatus.FAILED.name,
            errorCode = code,
            updatedAt = clock(),
        )
        try { updateActive(failed) }
        catch (stopped: ImportStoppedException) { return ImportEnqueueResult.Failed(stopped.job, stopped.job.status, false) }
        catch (failure: IllegalStateException) {
            val current = store.findJob(job.jobId)
            if (current != null && current.status !in ACTIVE_STATUSES)
                return ImportEnqueueResult.Failed(current, current.status, false)
            throw failure
        }
        return ImportEnqueueResult.Failed(failed, code, retryable)
    }

    private class ImportStoppedException(val job: ImportJobSnapshot) : Exception("Import stopped: ${job.status}")
    companion object { private val ACTIVE_STATUSES = setOf("PENDING", "RUNNING", "RETRY_WAIT") }
}
