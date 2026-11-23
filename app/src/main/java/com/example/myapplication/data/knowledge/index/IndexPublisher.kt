package com.example.myapplication.data.knowledge.index

import androidx.room.withTransaction
import com.example.myapplication.core.AppLog
import com.example.myapplication.data.knowledge.chunk.StructuredChunker
import com.example.myapplication.data.knowledge.lexical.ChineseTokenizer
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.KnowledgeChunkEntity
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.DocumentAccess
import com.example.myapplication.domain.reader.OperationResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * LOCAL 索引发布（设计 §6 / FX-08 / FX-11）。
 *
 * 流程：
 *  1. claim 租约（CAS，changedRows=1）
 *  2. 固定 revision 读取 blocks（DocumentAccess）
 *  3. 分块 + 写 staging generation 的 chunks/FTS（不可见：active 查询只 join active_generation）
 *  4. 发布前复核：租约 token、源 revision、access_epoch、requested_generation
 *  5. 全匹配才在同一事务切 active_generation、结束 LOCAL 并登记 VECTOR；任何不符整体回滚
 *
 * 网络/IO 不持 DB 写锁；发布事务只做校验 + 切换 + 登记。
 */
class IndexPublisher(
    private val database: AppDatabase,
    private val documentAccess: DocumentAccess,
    private val lease: IndexLease,
    private val chunker: StructuredChunker = StructuredChunker(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** VECTOR 任务登记与 LOCAL 发布共用同一事务与同一时钟。 */
    private val outbox: IndexOutbox = IndexOutbox(database, clock)

    sealed interface Outcome {
        data class Published(val chunkCount: Int, val generation: Long) : Outcome
        data class StaleClaim(val reason: String) : Outcome
        data class Retryable(val errorCode: String, val retryAt: Long) : Outcome
        data class Blocked(val errorCode: String) : Outcome
        data class Cancelled(val reason: String) : Outcome
    }

    /** 事务内回滚哨兵：Room 的 withTransaction 只在抛异常时回滚，返回 null 会照常提交。 */
    private class PublishRollback(val outcome: Outcome) : Exception()

    /**
     * 处理一个 LOCAL 任务。
     *
     * [profileFingerprint] 是发布时要登记的 VECTOR 增强目标 profile（当前选中的
     * embedding 配置）。空串表示用户尚未配置 embedding：只发布词法，不外发任何内容。
     */
    suspend fun processLocalJob(
        jobId: String,
        ctx: AccessContext,
        profileFingerprint: String = "",
    ): Outcome {
        val claimed = lease.tryClaim(jobId) ?: return Outcome.StaleClaim("CLAIM_FAILED")
        return try {
            runLocal(claimed, ctx, profileFingerprint)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rollback: PublishRollback) {
            rollback.outcome
        } catch (error: Exception) {
            // 静默吞异常会让"索引一直重试但没人知道为什么"变成不可诊断的黑洞（第四期 review P1 同类问题）。
            AppLog.e("索引发布", "LOCAL 任务失败，转为可重试：jobId=$jobId", error)
            Outcome.Retryable("IO_FAILURE", clock() + RETRY_DELAY_MILLIS)
        }
    }

    private suspend fun runLocal(
        claimed: IndexLease.Claimed,
        ctx: AccessContext,
        profileFingerprint: String,
    ): Outcome {
        val job = claimed.job
        if (job.phase != IndexOutbox.PHASE_LOCAL) return Outcome.StaleClaim("WRONG_PHASE")
        val knowledgeDoc = database.knowledgeDocumentDao().find(job.knowledgeDocumentId)
            ?: return Outcome.StaleClaim("KNOWLEDGE_DOC_MISSING")
        if (knowledgeDoc.userId != ctx.userId) return Outcome.StaleClaim("OWNER_MISMATCH")
        if (knowledgeDoc.requestedGeneration != job.requestedGeneration) {
            return Outcome.StaleClaim("GENERATION_CHANGED")
        }
        lease.markRunning(claimed)

        // 固定 revision 读取：open 会复核 owner/kind/READY/deleted_at
        val snapshot = when (val opened = documentAccess.open(ctx, knowledgeDoc.documentId, job.targetRevision)) {
            is OperationResult.Ok -> opened.value
            is OperationResult.Rejected -> return Outcome.StaleClaim(opened.code)
            is OperationResult.Failed -> return Outcome.Retryable(opened.code, clock() + RETRY_DELAY_MILLIS)
            is OperationResult.Conflict -> return Outcome.StaleClaim("CONFLICT")
        }
        if (snapshot.contentRevision != job.targetRevision) return Outcome.StaleClaim("REVISION_CHANGED")

        // 分块（IO/CPU，不持 DB 写锁）
        val drafts = mutableListOf<Pair<String, com.example.myapplication.data.knowledge.chunk.StructuredChunker.ChunkDraft>>()
        for (descriptor in snapshot.sections) {
            var cursor: String? = null
            val blocks = mutableListOf<com.example.myapplication.domain.reader.ContentBlock>()
            do {
                val page = when (val result = documentAccess.readBlocks(ctx, snapshot.snapshotId, descriptor.id, cursor)) {
                    is OperationResult.Ok -> result.value
                    is OperationResult.Rejected -> return Outcome.StaleClaim(result.code)
                    is OperationResult.Failed -> return Outcome.Retryable(result.code, clock() + RETRY_DELAY_MILLIS)
                    is OperationResult.Conflict -> return Outcome.StaleClaim("CONFLICT")
                }
                blocks += page.items
                cursor = page.nextCursor
            } while (cursor != null && blocks.size < MAX_BLOCKS_PER_SECTION)
            chunker.chunk(
                documentId = snapshot.documentId,
                sectionId = descriptor.id,
                contentRevision = snapshot.contentRevision,
                blocks = blocks,
            ).forEach { draft -> drafts += descriptor.id to draft }
        }
        if (drafts.isEmpty()) {
            return Outcome.Blocked("NO_INDEXABLE_CONTENT")
        }

        // 发布前复核：源与 epoch 未变
        val readerDoc = database.readerCatalogDao().findDocumentIncludingDeleted(snapshot.documentId)
            ?: return Outcome.StaleClaim("SOURCE_MISSING")
        if (readerDoc.deletedAt != null) return Outcome.StaleClaim("SOURCE_DELETED")
        if (readerDoc.contentRevision != job.targetRevision) return Outcome.StaleClaim("SOURCE_CHANGED")
        if (readerDoc.accessEpoch != snapshot.accessEpoch) return Outcome.StaleClaim("ACCESS_EPOCH_CHANGED")
        when (lease.checkPublishable(claimed)) {
            is IndexLease.PublishCheck.Ok -> Unit
            is IndexLease.PublishCheck.StaleClaim -> return Outcome.StaleClaim("STALE_CLAIM")
            is IndexLease.PublishCheck.NotFound -> return Outcome.StaleClaim("JOB_MISSING")
        }

        // 原子发布：staging chunks/FTS + 切 active + 结束 LOCAL + 登记 VECTOR。
        // 任一步 changedRows != 1 都抛出回滚哨兵，绝不留下半代数据。
        val generation = job.requestedGeneration
        val published = database.withTransaction {
            val chunkDao = database.knowledgeChunkDao()
            val docDao = database.knowledgeDocumentDao()
            val jobDao = database.knowledgeJobDao()
            // 事务内再复核一次租约：并发 worker 可能在分块期间抢走 claim
            when (lease.checkPublishable(claimed)) {
                is IndexLease.PublishCheck.Ok -> Unit
                is IndexLease.PublishCheck.StaleClaim -> throw PublishRollback(Outcome.StaleClaim("STALE_CLAIM"))
                is IndexLease.PublishCheck.NotFound -> throw PublishRollback(Outcome.StaleClaim("JOB_MISSING"))
            }
            // 清掉同代次残留（重试场景），保持幂等
            chunkDao.deleteGeneration(job.knowledgeDocumentId, generation)
            // 编号必须**跨节全局唯一**：knowledge_chunks 上有
            // (knowledge_document_id, generation, ordinal) 唯一索引，而 StructuredChunker.chunk()
            // 是按节调用的，每节都从 ordinal=0 开始。直接用 draft.ordinal 会让"每节都有 0 号块"的
            // 多节书一插入就撞唯一约束，整本索引永远停在可重试的 IO_FAILURE 上（此前所有索引测试
            // 都是单节书，所以一直没暴露）。这里按节序+块序重编全局编号。
            val ids = drafts.mapIndexed { ordinal, (sectionId, draft) ->
                val id = chunkDao.insert(
                    KnowledgeChunkEntity(
                        knowledgeDocumentId = job.knowledgeDocumentId,
                        generation = generation,
                        sectionId = sectionId,
                        blockIdsJson = encodeBlockIds(draft.blockIds),
                        parentKey = draft.parentKey,
                        ordinal = ordinal,
                        rawText = draft.rawText,
                        searchText = draft.searchText,
                        embeddingText = draft.embeddingText,
                        contentHash = draft.contentHash,
                        locatorJson = com.example.myapplication.domain.reader.ReaderLocatorCodec.encode(draft.locator),
                        tokenCount = draft.tokenCount,
                        extractorVersion = snapshot.extractorVersion,
                        chunkerVersion = chunker.chunkerVersion,
                    ),
                )
                chunkDao.insertFts(
                    chunkId = id,
                    titleTerms = ChineseTokenizer.toIndexText(readerDoc.title),
                    headingTerms = ChineseTokenizer.toIndexText(draft.embeddingText.substringBefore('\n')),
                    bodyTerms = ChineseTokenizer.toIndexText(draft.rawText),
                    userScope = "u${knowledgeDoc.userId}",
                )
                id
            }
            // 只有 changedRows=1 才发布；0 表示 epoch 已被并发修改
            val changed = docDao.publishGeneration(
                id = job.knowledgeDocumentId,
                generation = generation,
                sourceRevision = job.targetRevision,
                lexicalState = "READY",
                vectorState = "PENDING",
                accessEpoch = snapshot.accessEpoch,
                expectedAccessEpoch = snapshot.accessEpoch,
            )
            if (changed != 1) throw PublishRollback(Outcome.StaleClaim("PUBLISH_CAS_FAILED"))
            val finished = jobDao.finish(job.id, claimed.leaseToken, "DONE", "{}", null, clock())
            if (finished != 1) throw PublishRollback(Outcome.StaleClaim("JOB_FINISH_FAILED"))
            // 同事务登记 VECTOR：LOCAL 与 VECTOR 要么一起存在，要么都不存在
            if (profileFingerprint.isNotBlank()) {
                when (
                    outbox.enqueueVectorJob(
                        userId = knowledgeDoc.userId,
                        knowledgeDocumentId = job.knowledgeDocumentId,
                        targetRevision = job.targetRevision,
                        generation = generation,
                        profileFingerprint = profileFingerprint,
                    )
                ) {
                    is IndexOutbox.RegisterResult.Registered,
                    is IndexOutbox.RegisterResult.AlreadyExists,
                    -> Unit

                    is IndexOutbox.RegisterResult.Failed ->
                        throw PublishRollback(Outcome.Retryable("VECTOR_ENQUEUE_FAILED", clock() + RETRY_DELAY_MILLIS))
                }
            }
            ids.size
        }
        return Outcome.Published(chunkCount = published, generation = generation)
    }

    private fun encodeBlockIds(ids: List<String>) = buildJsonArray {
        ids.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
    }.toString()

    companion object {
        const val MAX_BLOCKS_PER_SECTION = 4096
        const val RETRY_DELAY_MILLIS = 60_000L
    }
}
