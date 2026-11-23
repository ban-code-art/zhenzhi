package com.example.myapplication.domain.reader

/**
 * Immutable content view returned by [DocumentAccess.open]. It deliberately
 * contains descriptors rather than full book content so callers cannot load a
 * whole library into memory by accident.
 */
data class ReaderPage<T>(
    val items: List<T>,
    val nextCursor: String? = null,
) {
    init {
        require(items.size <= 64) { "reader page is limited to 64 descriptors" }
    }
}

data class ContentBlock(
    val blockId: String,
    val sectionId: String,
    val kind: BlockKind,
    val canonicalStart: Int,
    val canonicalEnd: Int,
    val text: String,
    val headingPath: List<String>,
    val nativeLocator: String,
    val extractionQuality: ExtractionQuality,
    /** Zero-based fixed page for OCR/native PDF blocks; null for ordinary reflow content. */
    val fixedPageIndex: Int? = null,
    /** Normalized source-page rectangle when the extractor has an exact box. */
    val fixedRect: NormalizedRect? = null,
) {
    init {
        require(blockId.isNotBlank()) { "blockId must not be blank" }
        require(sectionId.isNotBlank()) { "sectionId must not be blank" }
        require(canonicalStart >= 0) { "canonicalStart must be >= 0" }
        require(canonicalEnd >= canonicalStart) { "canonicalEnd must be >= canonicalStart" }
        require(nativeLocator.isNotBlank()) { "nativeLocator must not be blank" }
        require(fixedPageIndex == null || fixedPageIndex >= 0) { "fixedPageIndex must be >= 0" }
        require(fixedRect == null || fixedPageIndex != null) { "fixedRect requires fixedPageIndex" }
    }
}

enum class BlockKind {
    HEADING,
    PARAGRAPH,
    LIST,
    TABLE,
    CAPTION,
    FOOTNOTE,
    IMAGE,
}

enum class ExtractionQuality {
    VERIFIED_TEXT,
    OCR_REVIEW_REQUIRED,
    NO_TEXT,
}

data class SourceEvidence(
    val evidenceId: String,
    val documentId: String,
    val sectionId: String,
    val contentRevision: String,
    val locator: ReaderLocator,
    val quote: String,
    val quoteSha256: String,
    val sourceTitleSnapshot: String,
    val extractorVersion: String,
)

data class ScopeRequest(
    val documentIds: List<String>,
    val readingScope: ReadingScope,
)

enum class ReadingScope {
    CURRENT_SECTION,
    CURRENT_DOCUMENT,
    LIBRARY,
}

data class ResolvedScope(
    val allowedDocumentIds: List<String>,
    val accessEpochByDocument: Map<String, Long>,
    val scopeHash: String,
    val selection: ReadingScope,
)

/**
 * Read-only content port. Implementations must enforce owner, revision and
 * access-epoch checks on every call; a successful SQL select alone is not an
 * authorization decision.
 */
interface DocumentAccess {
    /** Explicit capability; implementations must account for raw reads before parsing. */
    suspend fun readBlocksBounded(
        ctx: AccessContext,
        snapshotId: String,
        sectionId: String,
        cursor: String? = null,
        maxBytes: Int = 64 * 1024,
        sourceBudget: SourceReadBudget,
    ): OperationResult<ReaderPage<ContentBlock>> =
        OperationResult.Failed("BOUNDED_READ_UNSUPPORTED", retryable = false)

    suspend fun open(
        ctx: AccessContext,
        documentId: String,
        requestedRevision: String? = null,
    ): OperationResult<DocumentSnapshot>

    suspend fun readSectionDescriptors(
        ctx: AccessContext,
        snapshotId: String,
        cursor: String? = null,
        limit: Int = 64,
    ): OperationResult<ReaderPage<SectionDescriptor>>

    suspend fun readBlocks(
        ctx: AccessContext,
        snapshotId: String,
        sectionId: String,
        cursor: String? = null,
        maxBytes: Int = 64 * 1024,
    ): OperationResult<ReaderPage<ContentBlock>>

    suspend fun readRange(
        ctx: AccessContext,
        snapshotId: String,
        locator: ReaderLocator,
        maxTokens: Int = 4_096,
    ): OperationResult<SourceEvidence>
}
