package com.example.myapplication.data.reader.ink

import com.example.myapplication.domain.reader.NormalizedRect
import com.example.myapplication.domain.reader.ReaderLocator

/**
 * 一页墨迹会话的落盘锚点（二期 P1-15 / D2）。
 *
 * 会话一旦建立就固定 document/section/page/revision；翻页或换节时必须先用**旧**
 * target 收尾待提交笔画，再为新页建立新 target。这样正在进行的笔画不会写进新页的
 * manifest，也不会把上一页的 INK annotation 改锚到新页。
 *
 * 这里只放纯数据与纯构造（manifest/locator），便于 JVM 单测覆盖"切换后仍锚在原页"。
 */
data class InkPageTarget(
    val documentId: String,
    val sectionId: String,
    val sectionTitle: String,
    val contentRevision: String,
    val pageIndex: Int,
    val isPdf: Boolean,
    val progression: Float,
) {
    init {
        require(documentId.isNotBlank()) { "documentId must not be blank" }
        require(sectionId.isNotBlank()) { "sectionId must not be blank" }
        require(contentRevision.isNotBlank()) { "contentRevision must not be blank" }
        require(pageIndex >= 0) { "pageIndex must be >= 0" }
    }

    val layoutKey: String get() = "$sectionId:$pageIndex"

    /** 会话锚点身份：只有 document/section/page 变化才需要重建会话。 */
    val anchorKey: String get() = "$documentId|$sectionId|$pageIndex"

    /** 既有 INK annotation 是否属于本会话的页；不属于就**不能**复用其 id（否则等于改锚）。 */
    fun acceptsAnnotation(annotationSectionId: String?, annotationPageIndex: Int): Boolean =
        annotationSectionId == sectionId && annotationPageIndex == pageIndex

    fun manifest(strokes: List<InkStroke>, revision: Long, now: Long): InkManifest = InkManifest(
        documentId = documentId,
        sectionId = sectionId,
        contentRevision = contentRevision,
        pageIndex = pageIndex,
        pageBox = NormalizedRect(0f, 0f, 1f, 1f),
        rotationDegrees = 0,
        anchorKind = if (isPdf) InkAnchorKind.FIXED_PAGE else InkAnchorKind.BLOCK_RECT,
        layoutKey = layoutKey,
        revision = revision,
        strokes = strokes,
        createdAt = now,
        updatedAt = now,
    )

    fun locator(): ReaderLocator = if (isPdf) {
        ReaderLocator.Fixed(
            documentId = documentId,
            sectionId = sectionId,
            contentRevision = contentRevision,
            progression = progression,
            pageIndex = pageIndex,
            pageBox = NormalizedRect(0f, 0f, 1f, 1f),
            rotationDegrees = 0,
            rects = listOf(NormalizedRect(0f, 0f, 1f, 1f)),
            textQuote = sectionTitle,
        )
    } else {
        ReaderLocator.Reflow(
            documentId = documentId,
            sectionId = sectionId,
            contentRevision = contentRevision,
            progression = progression,
            nativeLocator = "ink:page:$pageIndex",
            exactQuote = sectionTitle,
            prefix = null,
            suffix = null,
            canonicalStart = 0,
            canonicalEnd = 0,
            blockId = sectionId,
        )
    }

    companion object {
        /** 页面/章节变化是否必须结束旧会话并重建（null 表示尚未建立会话）。 */
        fun restartRequired(current: InkPageTarget?, next: InkPageTarget): Boolean =
            current != null && current.anchorKey != next.anchorKey
    }
}
