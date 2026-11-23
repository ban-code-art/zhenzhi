package com.example.myapplication.data.knowledge.index

import androidx.room.withTransaction
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.KnowledgeDocumentEntity
import com.example.myapplication.data.local.entity.KnowledgeIndexJobEntity
import java.util.UUID

/**
 * 索引任务 outbox（设计 §6）。
 *
 * 内容登记与 LOCAL 任务入队在同一 Room 事务：要么两者都成功，要么都不写，
 * 不允许“有文档没有索引任务”或反之。任务身份
 * (knowledgeDocumentId, requestedGeneration, phase, profileFingerprint) 唯一，
 * 重复登记返回既有任务（幂等键）。
 */
class IndexOutbox(
    private val database: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface RegisterResult {
        data class Registered(val knowledgeDocumentId: String, val jobId: String) : RegisterResult
        data class AlreadyExists(val knowledgeDocumentId: String, val jobId: String) : RegisterResult
        data class Failed(val reason: String) : RegisterResult
    }

    /**
     * 登记文档并排队 LOCAL 词法任务。
     *
     * [targetRevision] 是本次要索引的源 revision；不就地改写已发布的
     * knowledge_documents.source_revision（发布成功后才切换）。
     */
    suspend fun registerDocument(
        userId: Long,
        documentId: String,
        targetRevision: String,
        generation: Long = 1,
        knowledgeDocumentId: String = "kd-${UUID.randomUUID()}",
    ): RegisterResult {
        if (targetRevision.isBlank()) return RegisterResult.Failed("targetRevision 为空")
        return runCatching {
            database.withTransaction {
                val documentDao = database.knowledgeDocumentDao()
                val jobDao = database.knowledgeJobDao()
                val existing = documentDao.findByDocument(userId, documentId)
                val kdId = existing?.id ?: knowledgeDocumentId
                if (existing == null) {
                    documentDao.insert(
                        KnowledgeDocumentEntity(
                            id = kdId,
                            userId = userId,
                            documentId = documentId,
                            sourceRevision = targetRevision,
                            requestedGeneration = generation,
                            activeGeneration = null,
                            lexicalState = "PENDING",
                            vectorState = "PENDING",
                            accessEpoch = 1,
                        ),
                    )
                } else if (existing.requestedGeneration != generation) {
                    documentDao.updateRequestedGeneration(kdId, generation)
                }
                val existingJob = jobDao.findByKey(kdId, generation, PHASE_LOCAL, "")
                if (existingJob != null) {
                    RegisterResult.AlreadyExists(kdId, existingJob.id)
                } else {
                    val now = clock()
                    val jobId = "job-local-${UUID.randomUUID()}"
                    jobDao.insert(
                        KnowledgeIndexJobEntity(
                            id = jobId,
                            userId = userId,
                            knowledgeDocumentId = kdId,
                            phase = PHASE_LOCAL,
                            targetRevision = targetRevision,
                            requestedGeneration = generation,
                            profileFingerprint = "",
                            status = STATUS_PENDING,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                    RegisterResult.Registered(kdId, jobId)
                }
            }
        }.getOrElse { RegisterResult.Failed(it.javaClass.simpleName) }
    }

    /** LOCAL 发布成功后，在同一事务登记 VECTOR 增强任务。 */
    suspend fun enqueueVectorJob(
        userId: Long,
        knowledgeDocumentId: String,
        targetRevision: String,
        generation: Long,
        profileFingerprint: String,
    ): RegisterResult {
        if (profileFingerprint.isBlank()) return RegisterResult.Failed("profileFingerprint 为空")
        return runCatching {
            database.withTransaction {
                val jobDao = database.knowledgeJobDao()
                val existing = jobDao.findByKey(knowledgeDocumentId, generation, PHASE_VECTOR, profileFingerprint)
                if (existing != null) {
                    RegisterResult.AlreadyExists(knowledgeDocumentId, existing.id)
                } else {
                    val now = clock()
                    val jobId = "job-vector-${UUID.randomUUID()}"
                    jobDao.insert(
                        KnowledgeIndexJobEntity(
                            id = jobId,
                            userId = userId,
                            knowledgeDocumentId = knowledgeDocumentId,
                            phase = PHASE_VECTOR,
                            targetRevision = targetRevision,
                            requestedGeneration = generation,
                            profileFingerprint = profileFingerprint,
                            status = STATUS_PENDING,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                    RegisterResult.Registered(knowledgeDocumentId, jobId)
                }
            }
        }.getOrElse { RegisterResult.Failed(it.javaClass.simpleName) }
    }

    companion object {
        const val PHASE_LOCAL = "LOCAL"
        const val PHASE_VECTOR = "VECTOR"
        const val STATUS_PENDING = "PENDING"
    }
}
