package com.example.myapplication.data.reader.importer

import com.example.myapplication.data.local.dao.ReaderJobDao
import com.example.myapplication.data.local.entity.ReaderJobEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Room-backed persistence for the import coordinator. The durable job row is
 * the source of truth across process death; UI state must not own this data.
 */
class RoomImportJobStore(
    private val dao: ReaderJobDao,
) : ImportJobStore {
    override suspend fun findJob(jobId: String): ImportJobSnapshot? =
        dao.find(jobId)?.toSnapshot()

    override suspend fun findByRequestKey(userId: Long, requestKey: String): ImportJobSnapshot? =
        dao.findByRequestKey(userId, requestKey)?.toSnapshot()

    override suspend fun insert(job: ImportJobSnapshot) {
        dao.insert(job.toEntity())
    }

    override suspend fun update(job: ImportJobSnapshot) {
        val changed = dao.updateProgress(
            jobId = job.jobId,
            stage = job.stage,
            status = job.status,
            stagedPath = job.stagedPath,
            targetRevision = job.targetRevision,
            errorCode = job.errorCode,
            progressJson = job.progressJson,
            updatedAt = job.updatedAt,
        )
        check(changed == 1) { "import job no longer exists: ${job.jobId}" }
    }

    private fun ImportJobSnapshot.toEntity(): ReaderJobEntity = ReaderJobEntity(
        id = jobId,
        userId = userId,
        kind = "IMPORT",
        documentId = null,
        requestKey = requestKey,
        stage = stage,
        status = status,
        stagedPath = stagedPath,
        targetRevision = targetRevision,
        errorCode = errorCode,
        progressJson = progressJson,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun ReaderJobEntity.toSnapshot(): ImportJobSnapshot = ImportJobSnapshot(
        jobId = id,
        userId = userId,
        requestKey = requestKey,
        stage = stage,
        status = status,
        stagedPath = stagedPath,
        targetRevision = targetRevision,
        format = parseFormat(progressJson),
        displayName = parseDisplayName(progressJson),
        errorCode = errorCode,
        progressJson = progressJson,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun parseDisplayName(progressJson: String): String? {
        val root = runCatching { Json.parseToJsonElement(progressJson) }.getOrNull() as? JsonObject ?: return null
        return (root["displayName"] as? JsonPrimitive)?.content
    }

    private fun parseFormat(progressJson: String): ReaderFormat? {
        val root = runCatching { Json.parseToJsonElement(progressJson) }.getOrNull() as? JsonObject
            ?: return null
        val value = (root["format"] as? JsonPrimitive)?.content ?: return null
        return ReaderFormat.entries.firstOrNull { it.name == value }
    }
}