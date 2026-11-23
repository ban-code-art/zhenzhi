package com.example.myapplication.data.reader.importer

import java.io.InputStream

enum class ImportStage {
    SELECTED,
    COPYING,
    VALIDATING,
    PARSING,
    STAGED,
    PUBLISHING,
}

enum class ImportStatus {
    PENDING,
    RUNNING,
    RETRY_WAIT,
    FAILED,
    CANCELLED,
    COMPLETED,
}

/**
 * [attachmentResolver] 只在用户授权范围内解析 Markdown 引用的相对本地资源（M04 附件采集）。
 * 为 null 表示只拿到了单文件流：相对图片会被明确标为 NOT_ACCESSIBLE，不伪造内容。
 * 参数放在 [openStream] 之前，保证既有 `ImportSource(name) { stream }` 尾随 lambda 写法不变。
 */
class ImportSource(
    val displayName: String?,
    val attachmentResolver: ImportAttachmentResolver? = null,
    val openStream: () -> InputStream,
)

/** 以规范化相对路径（可能以 `../` 开头）查找附件；实现必须自己执行授权/越界检查。 */
fun interface ImportAttachmentResolver {
    fun resolve(path: String): ImportAttachmentLookup
}

sealed interface ImportAttachmentLookup {
    class Found(val openStream: () -> InputStream) : ImportAttachmentLookup
    data object NotFound : ImportAttachmentLookup
    data object OutOfScope : ImportAttachmentLookup
    data object PermissionDenied : ImportAttachmentLookup
    data object Ambiguous : ImportAttachmentLookup
}

data class ImportJobSnapshot(
    val jobId: String,
    val userId: Long,
    val requestKey: String,
    val stage: String,
    val status: String,
    val stagedPath: String? = null,
    val targetRevision: String? = null,
    val format: ReaderFormat? = null,
    val displayName: String? = null,
    val errorCode: String? = null,
    val progressJson: String = "{}",
    val createdAt: Long,
    val updatedAt: Long,
)

interface ImportJobStore {
    suspend fun findJob(jobId: String): ImportJobSnapshot?
    suspend fun findByRequestKey(userId: Long, requestKey: String): ImportJobSnapshot?
    suspend fun insert(job: ImportJobSnapshot)
    suspend fun update(job: ImportJobSnapshot)
}

sealed interface ImportEnqueueResult {
    data class Accepted(val job: ImportJobSnapshot, val detection: FormatDetection) : ImportEnqueueResult
    data class Duplicate(val job: ImportJobSnapshot) : ImportEnqueueResult
    data class Failed(val job: ImportJobSnapshot, val code: String, val retryable: Boolean) : ImportEnqueueResult
}