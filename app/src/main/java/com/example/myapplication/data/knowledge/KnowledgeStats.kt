package com.example.myapplication.data.knowledge

import com.example.myapplication.data.knowledge.index.IndexOutbox
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.dao.KnowledgeStatsRow
import java.io.File

/**
 * 知识库统计与维护（设计 §6 / W3-11）。
 *
 * 全部走 SQL 聚合，不逐条加载 embedding、不调用检索“顺便算个数”。
 * 计数只反映当前用户当前 profile 的覆盖，不把“分块数>0”当“全库向量完成”。
 *
 * 维护动作只改 `knowledge_index_jobs` 账本状态或登记新代次 LOCAL 任务：
 * 不删除原文、不删除已发布代次，取消/重试不破坏原文与知识库一致性。
 */
class KnowledgeStats(
    private val database: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Summary(
        val documentCount: Int,
        val lexicalReadyCount: Int,
        val chunkCount: Int,
        val embeddedCount: Int,
        val pendingJobs: Int,
        val blockedJobs: Int,
        val failedJobs: Int,
        val failureReasons: List<FailureReason>,
    )

    /**
     * 失败原因聚合（设计与 §8 “失败原因”）：BLOCKED 等用户修复，FAILED 可显式重试。
     */
    data class FailureReason(
        val errorCode: String,
        val count: Int,
    )

    suspend fun summary(userId: Long, profileFingerprint: String?): Summary {
        val documents = database.knowledgeDocumentDao().findDocuments(userId)
        val lexicalReady = documents.count { it.lexicalState == "READY" }
        val chunkCount = database.knowledgeChunkDao().countActiveChunks(userId)
        val embedded = if (profileFingerprint.isNullOrBlank()) {
            0
        } else {
            database.knowledgeEmbeddingDao().countActiveForUser(userId, profileFingerprint)
        }
        val stats: KnowledgeStatsRow = database.knowledgeJobDao().statsForUser(userId)
        val failures = database.knowledgeJobDao().failureReasons(userId).map { row ->
            FailureReason(errorCode = row.errorCode ?: UNKNOWN_ERROR_CODE, count = row.count)
        }
        return Summary(
            documentCount = documents.size,
            lexicalReadyCount = lexicalReady,
            chunkCount = chunkCount,
            embeddedCount = embedded,
            pendingJobs = stats.pending,
            blockedJobs = stats.blocked,
            failedJobs = stats.failed,
            failureReasons = failures,
        )
    }

    /**
     * 显式重试终态失败任务：BLOCKED/FAILED → RETRY_WAIT（原任务原地复活，
     * 不新建作业）。调用方随后 [com.example.myapplication.AppContainer.scheduleIndexing]。
     */
    suspend fun retryFailed(userId: Long): Int {
        if (userId <= 0L) return 0
        return database.knowledgeJobDao().requeueFailedForUser(userId, clock())
    }

    /**
     * 取消尚未开始的任务：只动 PENDING/RETRY_WAIT，不打断运行中的 claim，
     * 已发布代次、原文与笔记不受影响。
     */
    suspend fun cancelPending(userId: Long): Int {
        if (userId <= 0L) return 0
        return database.knowledgeJobDao().cancelPendingForUser(userId, clock())
    }

    /**
     * 重建：为每个可索引来源按当前源 revision 登记**新代次** LOCAL 任务。
     *
     * - 新代次发布前旧 active generation 仍可查询（设计 §6）；
     * - 同一源 revision 已有在途 LOCAL 任务时跳过，人工重复点击不造重复作业；
     * - 只登记 LOCAL（无网络），VECTOR 由发布事务按当前 profile 补齐。
     */
    suspend fun rebuild(userId: Long): Int {
        if (userId <= 0L) return 0
        val documents = database.knowledgeDocumentDao().findDocuments(userId)
        val catalog = database.readerCatalogDao()
        val jobDao = database.knowledgeJobDao()
        val outbox = IndexOutbox(database, clock)
        var created = 0
        for (document in documents) {
            val source = catalog.findDocumentIncludingDeleted(document.documentId) ?: continue
            if (source.deletedAt != null) continue
            if (source.importStatus != "READY") continue
            if (source.kind != BUILTIN_KIND && source.ownerUserId != userId) continue
            val revision = source.contentRevision
            if (revision.isBlank()) continue
            val jobs = jobDao.findForDocument(document.id)
            val inFlight = jobs.any { job ->
                job.phase == IndexOutbox.PHASE_LOCAL &&
                    job.targetRevision == revision &&
                    job.status in IN_FLIGHT_STATUSES
            }
            if (inFlight) continue
            val nextGeneration = maxOf(
                document.requestedGeneration,
                jobs.maxOfOrNull { it.requestedGeneration } ?: 0L,
            ) + 1
            when (outbox.registerDocument(userId, document.documentId, revision, nextGeneration)) {
                is IndexOutbox.RegisterResult.Registered -> created += 1
                is IndexOutbox.RegisterResult.AlreadyExists,
                is IndexOutbox.RegisterResult.Failed,
                -> Unit
            }
        }
        return created
    }

    companion object {
        private const val UNKNOWN_ERROR_CODE = "UNKNOWN"
        private const val BUILTIN_KIND = "BUILTIN"
        private val IN_FLIGHT_STATUSES = setOf("PENDING", "RETRY_WAIT", "CLAIMED", "RUNNING")
    }
}

/**
 * 统计页“存储占用”：全部来自真实文件大小（只读，不删除）。
 *
 * - [Usage.databaseBytes]：SQLite 主库 + wal/shm/journal；
 * - [Usage.readerDirectoryBytes]：`files/reader`（导入原文、派生文件、墨迹、ANN manifest）；
 * - [Usage.annDirectoryBytes]：`files/reader/ann` 子集，便于判断可重建派生物占用。
 */
object KnowledgeStorageUsage {
    data class Usage(
        val databaseBytes: Long,
        val readerDirectoryBytes: Long,
        val annDirectoryBytes: Long,
    ) {
        val totalBytes: Long get() = databaseBytes + readerDirectoryBytes
    }

    fun measure(databaseFile: File?, readerDirectory: File?, annDirectory: File?): Usage {
        val databaseBytes = fileBytes(databaseFile) +
            fileBytes(databaseFile?.let { File("${it.path}$WAL_SUFFIX") }) +
            fileBytes(databaseFile?.let { File("${it.path}$SHM_SUFFIX") }) +
            fileBytes(databaseFile?.let { File("${it.path}$JOURNAL_SUFFIX") })
        return Usage(
            databaseBytes = databaseBytes,
            readerDirectoryBytes = directoryBytes(readerDirectory),
            annDirectoryBytes = directoryBytes(annDirectory),
        )
    }

    private fun fileBytes(file: File?): Long =
        if (file != null && file.isFile) file.length() else 0L

    private fun directoryBytes(root: File?): Long {
        if (root == null || !root.exists()) return 0L
        if (root.isFile) return root.length()
        return root.listFiles()?.sumOf { directoryBytes(it) } ?: 0L
    }

    private const val WAL_SUFFIX = "-wal"
    private const val SHM_SUFFIX = "-shm"
    private const val JOURNAL_SUFFIX = "-journal"
}
