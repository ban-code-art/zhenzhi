package com.example.myapplication.data.review

sealed interface ReviewScope {
    data object All : ReviewScope
    data class Book(val bookKey: String) : ReviewScope {
        init {
            require(bookKey.isNotBlank() && bookKey.length <= 256)
        }
    }
}

data class ReviewCandidate(
    val sourceNamespace: String,
    val sourceKey: String,
    val sourceRefJson: String,
    val sourceTags: Set<String>,
    val prompt: String,
    val answerSnapshot: String,
    val sourceRevision: String,
    val bookKey: String? = null,
    val sourceTitle: String = "",
    val priority: Int = 2,
    val creationOperationId: String? = null,
    val excerptFingerprint: String? = null,
    val materialExcerpt: String? = null,
)

object ReviewAvailability {
    const val AVAILABLE = "AVAILABLE"
    const val SOURCE_CHANGED = "SOURCE_CHANGED"
    const val SOURCE_MISSING = "SOURCE_MISSING"
    const val SOURCE_HIDDEN = "SOURCE_HIDDEN"
    const val SOURCE_REVOKED = "SOURCE_REVOKED"
    const val CONTENT_INCOMPLETE = "CONTENT_INCOMPLETE"
}

object ReviewSessionStatus {
    const val ACTIVE = "ACTIVE"
    const val FINISHED = "FINISHED"
    const val CLOSED_IMPORTED = "CLOSED_IMPORTED"
}

object ReviewSessionItemStatus {
    const val PENDING = "PENDING"
    const val COMPLETED = "COMPLETED"
    const val SKIPPED = "SKIPPED"
    const val UNAVAILABLE = "UNAVAILABLE"
    const val SOURCE_DELETED = "SOURCE_DELETED"
    const val ABANDONED = "ABANDONED"
}

data class ReviewSummary(
    val dueCount: Int,
    val nextDueAtUtc: Long?,
    val todayReviewedCount: Int,
    val totalItemCount: Int,
    val activeSessionId: Long?,
    val remainingCount: Int,
    val preview: List<ReviewItemEntity>,
    val estimatedMinutes: Int,
)

data class ReviewCardTicket(val sessionId: Long, val ordinal: Int, val snapshotRevision: Long)

val ReviewSessionItemEntity.ticket: ReviewCardTicket
    get() = ReviewCardTicket(sessionId, ordinal, snapshotRevision)

data class ReviewSessionState(
    val session: ReviewSessionEntity,
    val items: List<ReviewSessionItemEntity>,
    val events: List<ReviewEventEntity>,
    val currentSourceAvailability: String? = null,
) {
    val current: ReviewSessionItemEntity? get() = items.firstOrNull { it.status == ReviewSessionItemStatus.PENDING }
    val remainingCount: Int get() = items.count { it.status == ReviewSessionItemStatus.PENDING }
}

class ReviewIdentityExpiredException : IllegalStateException("Review account or session identity has changed")
class ReviewConflictException(message: String) : IllegalStateException(message)
