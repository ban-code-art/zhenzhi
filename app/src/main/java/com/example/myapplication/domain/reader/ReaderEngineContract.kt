package com.example.myapplication.domain.reader

import kotlinx.coroutines.flow.Flow

data class ReaderSessionHandle(
    val userId: Long,
    val sessionEpoch: Long,
    val documentId: String,
    val contentRevision: String,
    val sessionId: String,
)

data class ReaderSessionToken(
    val userId: Long,
    val sessionEpoch: Long,
    val documentId: String,
    val contentRevision: String,
    val sessionId: String,
    val layoutGeneration: Long,
) {
    fun handle() = ReaderSessionHandle(userId, sessionEpoch, documentId, contentRevision, sessionId)
}

enum class ReaderTurnDirection { PREVIOUS, NEXT }
enum class ReaderTurnOrigin { EDGE_TAP, BOTTOM_BUTTON, SWIPE }
enum class ReaderOcrState { NOT_REQUIRED, REQUIRED, RUNNING, NEEDS_REVIEW, READY, FAILED, UNAVAILABLE }
enum class ReaderFlowMode { PAGINATED, SCROLL }
enum class ReaderSelectionPhase { DRAGGING, STABLE, CLEARED }

enum class ReaderPlacement { START, END, AT_LOCATOR }

data class ReaderTarget(
    val documentId: String,
    val contentRevision: String,
    val unitId: String,
    val firstSectionId: String,
    val lastSectionId: String,
    val startLocator: ReaderLocator? = null,
    val endLocator: ReaderLocator? = null,
    val courseBookId: String? = null,
    val chapterKey: String? = null,
)

data class ReaderOpenRequest(
    val target: ReaderTarget,
    val placement: ReaderPlacement,
    val locator: ReaderLocator? = null,
)

data class ReaderFeatureReport(
    val token: ReaderSessionToken,
    val currentLocator: ReaderLocator,
    val capabilities: ReaderCapabilities,
    val renderable: Boolean,
    val preciseSelectionGeometry: Boolean,
    val reflowable: Boolean,
    val ocrState: ReaderOcrState,
    val eventSequence: Long = 0L,
)

sealed interface ReaderTurnResult {
    data class Moved(val locator: ReaderLocator) : ReaderTurnResult
    data class UnitBoundary(val direction: ReaderTurnDirection) : ReaderTurnResult
    data object Busy : ReaderTurnResult
    data class Failed(val code: String, val retryable: Boolean) : ReaderTurnResult
}

sealed interface ReaderNavigationResult {
    data class Exact(val locator: ReaderLocator) : ReaderNavigationResult
    data class Approximate(val locator: ReaderLocator?, val reason: String) : ReaderNavigationResult
    data class Unavailable(val code: String) : ReaderNavigationResult
}

data class ReaderScreenRect(val left: Float, val top: Float, val right: Float, val bottom: Float)
data class ReaderSelectionFragment(val locator: ReaderLocator, val quote: String)
data class ReaderSelectionEvent(
    val token: ReaderSessionToken,
    val selectionId: String,
    val phase: ReaderSelectionPhase,
    val fragments: List<ReaderSelectionFragment>,
    val screenRects: List<ReaderScreenRect>,
)

data class ReaderLayoutPreferences(
    val fontScale: Float,
    val lineHeight: Float,
    val marginDp: Int,
    val fontFamily: String?,
    val foregroundHex: String?,
    val backgroundHex: String,
    val flowMode: ReaderFlowMode,
)

data class ReaderDecoration(
    val annotationId: String,
    val revision: Long,
    val targets: List<ReaderLocator>,
    val colorHex: String,
    val underline: Boolean,
)

interface ReaderEngineAdapter {
    suspend fun open(
        context: AccessContext,
        request: ReaderOpenRequest,
    ): OperationResult<ReaderFeatureReport>

    suspend fun turn(
        token: ReaderSessionToken,
        direction: ReaderTurnDirection,
        origin: ReaderTurnOrigin,
    ): ReaderTurnResult

    suspend fun navigate(token: ReaderSessionToken, locator: ReaderLocator): ReaderNavigationResult
    fun selectionEvents(token: ReaderSessionToken): Flow<ReaderSelectionEvent>
    fun featureEvents(token: ReaderSessionToken): Flow<ReaderFeatureReport>
    suspend fun clearSelection(token: ReaderSessionToken): OperationResult<Unit>
    suspend fun decorate(token: ReaderSessionToken, items: List<ReaderDecoration>): OperationResult<Unit>
    suspend fun applyPreferences(
        token: ReaderSessionToken,
        preferences: ReaderLayoutPreferences,
    ): OperationResult<ReaderFeatureReport>
    suspend fun close(handle: ReaderSessionHandle)
}
