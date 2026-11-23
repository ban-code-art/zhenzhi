package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.data.reader.PreparedReaderResources
import com.example.myapplication.domain.reader.ContentBlock
import com.example.myapplication.domain.reader.ReaderLayoutPreferences
import com.example.myapplication.domain.reader.ReaderPlacement
import com.example.myapplication.domain.reader.ReaderTurnDirection
import java.io.File

/** Original HTML annotated with verified production block identities, never a text-only substitute. */
data class PreparedReflowDocument(
    val resources: PreparedReaderResources,
    val html: String,
    val blocks: List<ContentBlock>,
    val sectionHref: String,
    val resourceFiles: Map<String, File>,
)

/** Geometry measured from columns or the native scroll viewport; boundaries reflect real edges. */
data class ReflowViewport(
    val blockId: String,
    val pageIndex: Int,
    val pageCount: Int,
    val scrollProgression: Float? = null,
    val atStart: Boolean = pageIndex == 0,
    val atEnd: Boolean = pageIndex == pageCount - 1,
) {
    init {
        require(pageCount > 0 && pageIndex in 0 until pageCount && blockId.isNotBlank())
        require(scrollProgression == null || (scrollProgression.isFinite() && scrollProgression in 0f..1f))
    }
    val progression: Float get() = scrollProgression ?: if (pageCount == 1) 0f else pageIndex.toFloat() / (pageCount - 1)
}

/** One surface per adapter; the host attaches its native view before opening. No hidden scope. */
interface ReflowReaderSurface {
    /** Re-measure native scrolling; null for surfaces without continuous movement. */
    suspend fun refreshViewport(): ReflowViewport? = null
    val viewportNeedsRefresh: Boolean get() = true
    suspend fun decorate(items: List<com.example.myapplication.domain.reader.ReaderDecoration>): Boolean = false
    val supportsExactLocations: Boolean get() = false
    suspend fun locate(locator: com.example.myapplication.domain.reader.ReaderLocator.Reflow): ReflowViewport = locate(locator.blockId)
    fun currentPosition(): com.example.myapplication.domain.reader.ReaderLocator.Reflow? = null
    suspend fun open(document: PreparedReflowDocument, placement: ReaderPlacement, blockId: String?): ReflowViewport
    suspend fun turn(direction: ReaderTurnDirection): ReflowViewport
    suspend fun locate(blockId: String): ReflowViewport
    suspend fun applyPreferences(preferences: ReaderLayoutPreferences, blockId: String): ReflowViewport
    suspend fun applyOpeningPreferences(preferences: ReaderLayoutPreferences, placement: ReaderPlacement, blockId: String): ReflowViewport {
        var viewport = applyPreferences(preferences, blockId)
        if (placement == ReaderPlacement.AT_LOCATOR) return viewport
        val direction = if (placement == ReaderPlacement.START) ReaderTurnDirection.PREVIOUS else ReaderTurnDirection.NEXT
        val destination = if (placement == ReaderPlacement.START) 0 else viewport.pageCount - 1
        repeat(viewport.pageCount) {
            if (viewport.pageIndex == destination) return viewport
            val next = turn(direction)
            check(next.pageCount == viewport.pageCount && next.pageIndex != viewport.pageIndex) { "REFLOW_PLACEMENT_STALLED" }
            viewport = next
        }
        error("REFLOW_PLACEMENT_FAILED")
    }
    suspend fun close()
}
