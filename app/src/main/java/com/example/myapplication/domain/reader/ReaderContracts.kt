package com.example.myapplication.domain.reader

data class AccessContext(
    val userId: Long,
    val sessionEpoch: Long,
    val requestId: String,
) {
    init {
        require(userId > 0) { "userId must be positive" }
        require(sessionEpoch >= 0) { "sessionEpoch must be >= 0" }
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        require(requestId.length <= 128) { "requestId is too long" }
    }
}

sealed interface OperationResult<out T> {
    data class Ok<T>(val value: T, val operationId: String) : OperationResult<T>
    data class Conflict(val currentRevision: Int, val recoverable: Boolean = true) : OperationResult<Nothing>
    data class Rejected(val code: String) : OperationResult<Nothing>
    data class Failed(val code: String, val retryable: Boolean) : OperationResult<Nothing>
}

data class SectionDescriptor(
    val id: String,
    val documentId: String,
    val contentRevision: String,
    val orderIndex: Int,
    val title: String,
    val resourceHref: String,
    val charCount: Int,
    val pageStart: Int?,
    val pageEnd: Int?,
)

data class DocumentSnapshot(
    val snapshotId: String,
    val documentId: String,
    val ownerUserId: Long?,
    val contentRevision: String,
    val metadataRevision: Long,
    val accessEpoch: Long,
    val extractorVersion: String,
    val sections: List<SectionDescriptor>,
    val sectionsNextCursor: String? = null,
) {
    init {
        require(snapshotId.isNotBlank()) { "snapshotId must not be blank" }
        require(documentId.isNotBlank()) { "documentId must not be blank" }
        require(contentRevision.isNotBlank()) { "contentRevision must not be blank" }
        require(extractorVersion.isNotBlank()) { "extractorVersion must not be blank" }
        require(sections.size <= 64) { "snapshot descriptors are limited to 64 entries" }
        require(sections.all { it.documentId == documentId }) {
            "all section descriptors must belong to the snapshot document"
        }
        require(sections.all { it.contentRevision == contentRevision }) {
            "all section descriptors must use the snapshot content revision"
        }
    }
}

data class ReaderCapabilities(
    val selectableText: Boolean,
    val images: Boolean,
    val fixedLayout: Boolean,
    val textExtraction: Boolean,
    val ink: Boolean,
    val search: Boolean,
)