package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.data.reader.ink.InkManifest
import com.example.myapplication.data.reader.ink.InkStroke

/** Includes the physical page: a section may span more than one PDF page. */
internal data class ReaderInkPageKey(
    val userId: Long,
    val documentId: String,
    val contentRevision: String,
    val sectionId: String,
    val pageIndex: Int,
) {
    init {
        require(userId > 0)
        require(documentId.isNotBlank() && contentRevision.isNotBlank() && sectionId.isNotBlank())
        require(pageIndex >= 0)
    }

    fun accepts(manifest: InkManifest): Boolean = manifest.documentId == documentId &&
        manifest.contentRevision == contentRevision && manifest.sectionId == sectionId && manifest.pageIndex == pageIndex
}

internal data class ReaderInkPageSource(val annotationId: String, val revision: Long, val manifestPath: String)

internal data class ReaderInkPageSnapshot(
    val strokes: List<InkStroke>,
    val source: ReaderInkPageSource?,
    val version: Long,
    val committedVersion: Long,
)

/**
 * Main-thread page cache. Local edits and successful saves stay visible while Room catches up.
 * A commit carries its page/version, so an old debounce cannot replace a newer edit or another page.
 */
internal class ReaderInkPageDisplay(private val capacity: Int = 8) {
    private data class Entry(
        var strokes: List<InkStroke> = emptyList(),
        var source: ReaderInkPageSource? = null,
        var version: Long = 0,
        var committedVersion: Long = 0,
        var awaitingSource: ReaderInkPageSource? = null,
    ) {
        val dirty: Boolean get() = version > committedVersion
        fun snapshot() = ReaderInkPageSnapshot(strokes, source, version, committedVersion)
    }

    private val entries = LinkedHashMap<ReaderInkPageKey, Entry>(16, .75f, true)

    init { require(capacity > 0) }

    fun read(
        key: ReaderInkPageKey,
        observed: ReaderInkPageSource?,
        load: (ReaderInkPageSource) -> InkManifest?,
    ): ReaderInkPageSnapshot {
        val entry = entries.getOrPut(key) { Entry() }
        val awaiting = entry.awaitingSource
        if (awaiting != null) {
            if (observed == null || observed.annotationId != awaiting.annotationId || observed.revision < awaiting.revision) {
                return finish(entry)
            }
            entry.awaitingSource = null
            if (observed == awaiting) {
                // This is the first Room acknowledgement of the exact file already in memory.
                entry.source = observed
                return finish(entry)
            }
        }
        if (entry.dirty || observed == entry.source) return finish(entry)
        val previous = entry.source
        if (observed != null && previous != null && observed.annotationId == previous.annotationId &&
            observed.revision < previous.revision) return finish(entry)
        if (observed == null) {
            entry.strokes = emptyList()
            entry.source = null
        } else {
            val manifest = load(observed)
            if (manifest != null && key.accepts(manifest)) {
                entry.strokes = manifest.strokes.toList()
                entry.source = observed
            } else {
                // Do not memoize a missing/unrelated file as successfully loaded. A later
                // Room emission or revisit may supply the correct manifest.
                entry.source = null
                entry.strokes = emptyList()
            }
        }
        return finish(entry)
    }

    fun edit(key: ReaderInkPageKey, strokes: List<InkStroke>): Long {
        val entry = entries.getOrPut(key) { Entry() }
        entry.strokes = strokes.toList()
        entry.version++
        trim()
        return entry.version
    }

    fun shouldCommit(key: ReaderInkPageKey, version: Long): Boolean = entries[key]?.let {
        version == it.version && version > it.committedVersion
    } == true

    fun committed(key: ReaderInkPageKey, version: Long, source: ReaderInkPageSource) {
        val entry = entries[key] ?: return
        if (version < entry.committedVersion || version > entry.version) return
        entry.committedVersion = version
        entry.source = source
        entry.awaitingSource = source
        trim()
    }

    fun snapshot(key: ReaderInkPageKey): ReaderInkPageSnapshot? = entries[key]?.snapshot()

    fun snapshots(): Map<ReaderInkPageKey, ReaderInkPageSnapshot> = entries.mapValues { it.value.snapshot() }

    fun clear() = entries.clear()

    private fun finish(entry: Entry): ReaderInkPageSnapshot = entry.snapshot().also { trim() }

    private fun trim() {
        val iterator = entries.entries.iterator()
        while (entries.size > capacity && iterator.hasNext()) {
            val entry = iterator.next().value
            if (!entry.dirty && entry.awaitingSource == null) iterator.remove()
        }
    }
}

/**
 * Existing fixed-page ink was normalized to the root reader Canvas, including its info strip.
 * Keep that representation when moving the overlay into the body/page transform. PDF padding
 * and ContentScale.Fit affect only the image; they are not the manifest's coordinate system.
 */
internal data class PdfInkCanvasSpace(val viewportWidth: Float, val legacyHeight: Float, val bodyHeight: Float) {
    init {
        require(viewportWidth.isFinite() && viewportWidth > 0f)
        require(legacyHeight.isFinite() && legacyHeight > 0f)
        require(bodyHeight.isFinite() && bodyHeight > 0f)
    }

    private val bodyOffsetY = (legacyHeight - bodyHeight).coerceAtLeast(0f)
    val referenceWidth: Float get() = minOf(viewportWidth, legacyHeight)

    fun toViewport(x: Float, y: Float): Pair<Float, Float> =
        x * viewportWidth to y * legacyHeight - bodyOffsetY

    fun toNormalized(x: Float, y: Float): Pair<Float, Float> =
        (x / viewportWidth).coerceIn(0f, 1f) to ((y + bodyOffsetY) / legacyHeight).coerceIn(0f, 1f)
}
