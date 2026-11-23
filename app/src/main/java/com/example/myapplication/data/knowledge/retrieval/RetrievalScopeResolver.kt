package com.example.myapplication.data.knowledge.retrieval

import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.PinnedDocument
import com.example.myapplication.domain.reader.ReadingScope
import com.example.myapplication.domain.reader.ScopeSnapshot
import com.example.myapplication.domain.reader.ScopeRequest

/**
 * scope 解析与 pin（设计 §7.1 / 接口明细 §14）。
 *
 * 打开检索时固定每文档 (generation, sourceRevision, accessEpoch)，之后所有批次
 * 按该映射查询；不授权/未就绪/未索引的文档直接不进入 scope。
 * scope 默认当前文档；跨书必须用户显式选 LIBRARY。
 */
class RetrievalScopeResolver(
    private val database: AppDatabase,
) {
    sealed interface Result {
        data class Ok(val snapshot: ScopeSnapshot) : Result
        data class Rejected(val code: String) : Result
    }

    suspend fun resolve(ctx: AccessContext, request: ScopeRequest): Result {
        val requested = request.documentIds.distinct().take(MAX_SCOPE_DOCUMENTS)
        if (requested.isEmpty()) return Result.Rejected("EMPTY_SCOPE")
        val pinned = mutableListOf<PinnedDocument>()
        for (documentId in requested) {
            val knowledgeDoc = database.knowledgeDocumentDao().findByDocument(ctx.userId, documentId)
                ?: continue
            if (knowledgeDoc.lexicalState != "READY") continue
            val generation = knowledgeDoc.activeGeneration ?: continue
            val readerDoc = database.readerCatalogDao().findDocumentIncludingDeleted(documentId)
                ?: continue
            if (readerDoc.deletedAt != null) continue
            if (readerDoc.importStatus != "READY") continue
            if (readerDoc.kind != "BUILTIN" && readerDoc.ownerUserId != ctx.userId) continue
            // 源已改版：旧 active 不能用于 CURRENT_ONLY 回答
            if (knowledgeDoc.sourceRevision != readerDoc.contentRevision) continue
            pinned += PinnedDocument(
                documentId = documentId,
                knowledgeDocumentId = knowledgeDoc.id,
                generation = generation,
                sourceRevision = knowledgeDoc.sourceRevision,
                accessEpoch = readerDoc.accessEpoch,
            )
        }
        if (pinned.isEmpty()) return Result.Rejected("NO_INDEXED_SCOPE")
        return Result.Ok(
            ScopeSnapshot(
                userId = ctx.userId,
                selection = request.readingScope,
                documents = pinned,
            ),
        )
    }

    /** 校验快照仍然有效：每文档 epoch/revision/active generation 未变。 */
    suspend fun stillValid(ctx: AccessContext, snapshot: ScopeSnapshot): Boolean {
        if (snapshot.userId != ctx.userId) return false
        for (pin in snapshot.documents) {
            val knowledgeDoc = database.knowledgeDocumentDao().find(pin.knowledgeDocumentId) ?: return false
            if (knowledgeDoc.userId != ctx.userId) return false
            if (knowledgeDoc.activeGeneration != pin.generation) return false
            if (knowledgeDoc.sourceRevision != pin.sourceRevision) return false
            val readerDoc = database.readerCatalogDao().findDocumentIncludingDeleted(pin.documentId) ?: return false
            if (readerDoc.deletedAt != null) return false
            if (readerDoc.contentRevision != pin.sourceRevision) return false
            if (readerDoc.accessEpoch != pin.accessEpoch) return false
        }
        return true
    }

    companion object {
        const val MAX_SCOPE_DOCUMENTS = 64
    }
}
