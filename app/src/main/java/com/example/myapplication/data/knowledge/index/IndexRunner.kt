package com.example.myapplication.data.knowledge.index

import androidx.room.withTransaction
import com.example.myapplication.data.knowledge.chunk.StructuredChunker
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.ai.config.EndpointResolver
import com.example.myapplication.domain.ai.WireProtocol
import com.example.myapplication.domain.reader.AccessContext
import java.util.UUID

/**
 * 索引运行的容器级编排（设计 §6 / W3-11 / FX-29）。
 *
 * 职责：
 *  - 解析用户当前选中的 embedding profile fingerprint（未配置/禁用返回空串）；
 *  - 为已发布 active generation 补齐当前 profile 的 VECTOR 任务（配置 embedding 后重新增强，
 *    旧 profile 向量保留并存，不删除）；
 *  - 调用 [IndexProcessor] 消费到期任务。
 *
 * Worker 与前台执行都走这里，共享同一 claim 服务，不双写。
 */
class IndexRunner(
    private val database: AppDatabase,
    private val processor: IndexProcessor,
    private val vectorEncodingPreference: VectorEncodingPreference? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 当前选中的 embedding profile fingerprint；未配置/禁用/不可解析返回空串（不外发）。 */
    suspend fun activeEmbeddingFingerprint(userId: Long): String {
        val settings = database.aiProfileDao().findSettings(userId) ?: return ""
        val profileId = settings.activeEmbeddingProfileId ?: return ""
        val entity = database.aiProfileDao().findProfile(userId, profileId) ?: return ""
        if (!entity.enabled) return ""
        val protocol = WireProtocol.fromStorage(entity.protocol) ?: return ""
        return EndpointResolver.embeddingFingerprint(
            userId = userId,
            endpoint = entity.endpoint,
            protocol = protocol,
            model = entity.model,
            paramsJson = entity.paramsJson,
        ).orEmpty()
    }

    /**
     * 补齐缺失的知识库登记：内置章节与历史导入文档在 schema v5 之前没有
     * knowledge_documents 行，启动/唤醒时按当前源 revision 补建 LOCAL 任务。
     *
     * 规则：
     *  - 已有同 revision 的非取消 LOCAL 任务：跳过（幂等，不造重复作业）；
     *  - 用户显式取消的当前源 revision：同一 revision 不自动复活，只有源改版后
     *    才按新代次重新登记（否则统计页“取消待处理”会被下一次唤醒撤销）；
     *  - 源 revision 已变化：用新的 requested_generation 登记目标为当前源的任务，
     *    不就地改写 source_revision（那是发布事务的职责）；
     *  - 已取消/终态且源已改版：同样按新代次重新登记。
     */
    suspend fun ensureRegisteredDocuments(userId: Long): Int {
        val documents = database.readerCatalogDao().findIndexableDocuments(userId)
        var created = 0
        database.withTransaction {
            val outbox = IndexOutbox(database, clock)
            val documentDao = database.knowledgeDocumentDao()
            val jobDao = database.knowledgeJobDao()
            for (document in documents) {
                val existing = documentDao.findByDocument(userId, document.id)
                if (existing == null) {
                    val result = outbox.registerDocument(
                        userId = userId,
                        documentId = document.id,
                        targetRevision = document.contentRevision,
                        generation = 1,
                    )
                    if (result is IndexOutbox.RegisterResult.Registered) created += 1
                    continue
                }
                val jobs = jobDao.findForDocument(existing.id)
                val matching = jobs.firstOrNull { job ->
                    job.phase == IndexOutbox.PHASE_LOCAL &&
                        job.targetRevision == document.contentRevision &&
                        job.status != "CANCELLED"
                }
                // G-01：源 revision 没变、但解析器/分块器升级过时，已发布代次会用旧口径。
                // 只有"同 revision 的任务已经 DONE"才允许按新代次重建；排队/运行中的任务先让它跑完。
                val versionReason = versionDrift(existing, document)
                if (matching != null && !(versionReason != null && matching.status == "DONE")) continue
                // 同一 revision 上只剩用户显式取消的 LOCAL 任务：保持取消，不自动复活；
                // 取消优先级高于版本漂移（否则每次唤醒都会撤销用户决定）。
                val cancelledForCurrentRevision = jobs.any { job ->
                    job.phase == IndexOutbox.PHASE_LOCAL &&
                        job.targetRevision == document.contentRevision &&
                        job.status == "CANCELLED"
                }
                if (cancelledForCurrentRevision) continue
                val nextGeneration = maxOf(
                    existing.requestedGeneration,
                    jobs.maxOfOrNull { it.requestedGeneration } ?: 0L,
                ) + 1
                val result = outbox.registerDocument(
                    userId = userId,
                    documentId = document.id,
                    targetRevision = document.contentRevision,
                    generation = nextGeneration,
                )
                if (result is IndexOutbox.RegisterResult.Registered) created += 1
            }
        }
        return created
    }

    /**
     * 为每个已发布 active generation 补齐当前 profile 的 VECTOR 任务。
     * 幂等：已有同 (kd, generation, VECTOR, fingerprint) 任务时不重复登记。
     */
    suspend fun ensureVectorJobs(userId: Long, profileFingerprint: String): Int {
        if (profileFingerprint.isBlank()) return 0
        var created = 0
        database.withTransaction {
            val outbox = IndexOutbox(database, clock)
            val documents = database.knowledgeDocumentDao().findDocuments(userId)
            for (document in documents) {
                val generation = document.activeGeneration ?: continue
                if (document.lexicalState != "READY") continue
                val result = outbox.enqueueVectorJob(
                    userId = userId,
                    knowledgeDocumentId = document.id,
                    targetRevision = document.sourceRevision,
                    generation = generation,
                    profileFingerprint = profileFingerprint,
                )
                if (result is IndexOutbox.RegisterResult.Registered) created += 1
            }
        }
        return created
    }

    /** 消费该用户到期任务；返回本次报告。 */
    suspend fun runForUser(
        userId: Long,
        maxJobsPerRun: Int = IndexProcessor.DEFAULT_MAX_JOBS,
    ): IndexProcessor.Report {
        val providerFingerprint = activeEmbeddingFingerprint(userId)
        val indexFingerprint = if (providerFingerprint.isBlank()) {
            ""
        } else {
            vectorEncodingPreference?.desiredIndexFingerprint(userId, providerFingerprint) ?: providerFingerprint
        }
        ensureRegisteredDocuments(userId)
        ensureVectorJobs(userId, indexFingerprint)
        val ctx = AccessContext(
            userId = userId,
            sessionEpoch = 0,
            requestId = "index-${UUID.randomUUID()}",
        )
        val report = processor.runDue(ctx, indexFingerprint, maxJobsPerRun)
        if (vectorEncodingPreference != null && providerFingerprint.isNotBlank()) {
            val identity = VectorIndexProfile.parse(indexFingerprint)
            if (identity != null && isIndexReady(userId, indexFingerprint)) {
                vectorEncodingPreference.activate(userId, providerFingerprint, identity.encoding)
            }
        }
        return report
    }

    /**
     * G-01：已发布活动代次的生成口径是否落后于当前源（解析器/分块器版本）。
     *
     * 只在 `lexical_state = READY` 且有活动代次时判定；历史行缺版本列、或取不到块版本时
     * 一律返回 null（不重建），避免每次唤醒都重排全库。
     *
     * @return null 表示无需重建；否则返回 [IndexVersioning] 的说明码。
     */
    private suspend fun versionDrift(
        existing: com.example.myapplication.data.local.entity.KnowledgeDocumentEntity,
        source: com.example.myapplication.data.local.entity.ReaderDocumentEntity,
    ): String? {
        val generation = existing.activeGeneration ?: return null
        if (existing.lexicalState != "READY") return null
        val stored = database.knowledgeChunkDao().findGenerationVersions(existing.id, generation) ?: return null
        val verdict = IndexVersioning.evaluate(
            storedExtractorVersion = stored.extractorVersion,
            storedChunkerVersion = stored.chunkerVersion,
            expectedExtractorVersion = source.extractorVersion,
            expectedChunkerVersion = StructuredChunker.CHUNKER_VERSION,
        )
        return if (verdict.needsRebuild) verdict.reason else null
    }

    /** Only flip the active encoding after every published generation has a complete replacement. */
    private suspend fun isIndexReady(userId: Long, indexFingerprint: String): Boolean {
        val identity = VectorIndexProfile.parse(indexFingerprint) ?: return false
        val documents = database.knowledgeDocumentDao().findDocuments(userId)
        data class ActiveSnapshot(
            val knowledgeDocumentId: String,
            val generation: Long?,
            val sourceRevision: String,
            val lexicalState: String,
        )
        fun List<com.example.myapplication.data.local.entity.KnowledgeDocumentEntity>.snapshot() =
            map { ActiveSnapshot(it.id, it.activeGeneration, it.sourceRevision, it.lexicalState) }
                .sortedBy { it.knowledgeDocumentId }
        val initialSnapshot = documents.snapshot()
        val chunkDao = database.knowledgeChunkDao()
        val stateDao = database.knowledgeVectorStateDao()
        val embeddingDao = database.knowledgeEmbeddingDao()
        for (document in documents) {
            val generation = document.activeGeneration ?: continue
            if (document.lexicalState != "READY") continue
            val expected = chunkDao.countGeneration(document.id, generation)
            if (expected == 0) continue
            val state = stateDao.find(document.id, generation, indexFingerprint) ?: return false
            if (state.state != "READY" || state.readyCount != expected || state.expectedCount != expected) return false

            var afterChunkId = 0L
            var decodedCount = 0
            while (true) {
                val rows = embeddingDao.scanGenerationRows(
                    knowledgeDocumentId = document.id,
                    generation = generation,
                    profileFingerprint = indexFingerprint,
                    afterChunkId = afterChunkId,
                    limit = VALIDATION_BATCH_SIZE,
                )
                if (rows.isEmpty()) break
                for (row in rows) {
                    if (row.encoding != identity.encoding || !row.norm.isFinite() || row.norm <= VectorCodec.NORM_EPSILON) {
                        return false
                    }
                    if (VectorCodec.decode(
                        blob = row.vectorBlob,
                        dim = row.dim,
                        encoding = row.encoding,
                        quantizationParams = row.quantizationParams,
                    ) !is VectorCodec.Decoded.Ok
                    ) {
                        return false
                    }
                }
                decodedCount += rows.size
                if (decodedCount > expected) return false
                afterChunkId = rows.last().chunkId
            }
            if (decodedCount != expected) return false
        }
        // A new generation may have published while a large staged index was being scanned.
        // Require the same generation set at the end before flipping the device-local pointer.
        return database.knowledgeDocumentDao().findDocuments(userId).snapshot() == initialSnapshot
    }

    /** 是否仍有未终态任务（用于决定是否继续唤醒 worker）。 */
    suspend fun hasPending(userId: Long): Boolean =
        database.knowledgeJobDao().statsForUser(userId).pending > 0

    companion object {
        private const val VALIDATION_BATCH_SIZE = 128
    }
}
