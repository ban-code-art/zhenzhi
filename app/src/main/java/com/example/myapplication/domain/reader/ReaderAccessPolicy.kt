package com.example.myapplication.domain.reader

data class ReaderDocumentAccessRecord(
    val documentId: String,
    val kind: String,
    val ownerUserId: Long?,
    val contentRevision: String,
    val accessEpoch: Long,
    val importStatus: String,
    val deletedAt: Long? = null,
)

sealed interface ReaderAccessDecision {
    data object Allowed : ReaderAccessDecision
    data class Denied(val code: String, val recoverable: Boolean = false) : ReaderAccessDecision
}

/**
 * Pure authorization policy shared by repositories and DocumentAccess
 * implementations. Built-in content is readable by every local user; imported
 * content is readable only by its owner. Deleted or non-READY imported content
 * must not be opened as a live reader document.
 */
object ReaderAccessPolicy {
    const val ERROR_NOT_FOUND = "NOT_FOUND"
    const val ERROR_ACCESS_REVOKED = "ACCESS_REVOKED"
    const val ERROR_REVISION_MISSING = "REVISION_MISSING"

    fun canRead(userId: Long, record: ReaderDocumentAccessRecord): ReaderAccessDecision {
        if (record.deletedAt != null) return ReaderAccessDecision.Denied(ERROR_ACCESS_REVOKED)
        return when (record.kind) {
            "BUILTIN" -> {
                if (record.importStatus != "READY") {
                    ReaderAccessDecision.Denied(ERROR_NOT_FOUND, recoverable = true)
                } else {
                    ReaderAccessDecision.Allowed
                }
            }

            "IMPORTED" -> {
                val owner = record.ownerUserId
                if (owner == null || owner != userId) {
                    ReaderAccessDecision.Denied(ERROR_NOT_FOUND)
                } else if (record.importStatus != "READY") {
                    ReaderAccessDecision.Denied(ERROR_NOT_FOUND, recoverable = true)
                } else {
                    ReaderAccessDecision.Allowed
                }
            }

            else -> ReaderAccessDecision.Denied(ERROR_NOT_FOUND)
        }
    }

    fun checkRevision(
        record: ReaderDocumentAccessRecord,
        requestedRevision: String?,
    ): ReaderAccessDecision {
        if (requestedRevision != null && requestedRevision != record.contentRevision) {
            return ReaderAccessDecision.Denied(ERROR_REVISION_MISSING)
        }
        return ReaderAccessDecision.Allowed
    }

    fun checkAccessEpoch(
        record: ReaderDocumentAccessRecord,
        expectedEpoch: Long,
    ): ReaderAccessDecision {
        return if (record.accessEpoch == expectedEpoch) {
            ReaderAccessDecision.Allowed
        } else {
            ReaderAccessDecision.Denied(ERROR_ACCESS_REVOKED)
        }
    }
}

fun AccessContext.requireValid(): AccessContext {
    require(userId > 0) { "userId must be positive" }
    require(sessionEpoch >= 0) { "sessionEpoch must be >= 0" }
    require(requestId.isNotBlank()) { "requestId must not be blank" }
    require(requestId.length <= 128) { "requestId is too long" }
    return this
}