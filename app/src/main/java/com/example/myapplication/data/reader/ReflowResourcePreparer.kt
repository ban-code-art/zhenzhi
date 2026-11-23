package com.example.myapplication.data.reader

import com.example.myapplication.data.reader.storage.DocumentStorage
import com.example.myapplication.data.reader.storage.ManifestFileKind
import com.example.myapplication.domain.reader.*
import com.example.myapplication.ui.knowledge.reader.PreparedReflowDocument
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Canonical identity comes exclusively from complete production readBlocks pages. */
class ReflowResourcePreparer(
    private val resources: ReaderResourcePreparer,
    private val access: DocumentAccess,
    private val storage: DocumentStorage,
    private val builtInPrepare: (suspend (AccessContext, ReaderTarget) -> OperationResult<PreparedReflowDocument>)? = null,
) {
    suspend fun prepare(context: AccessContext, target: ReaderTarget): OperationResult<PreparedReflowDocument> {
        if (target.documentId.startsWith("builtin:")) {
            val opened = access.open(context, target.documentId, target.contentRevision)
            if (opened !is OperationResult.Ok) return opened.failure()
            if (opened.value.extractorVersion != "content-schema2-v1") {
                val prepare = builtInPrepare ?: return OperationResult.Rejected("BUILTIN_RESOURCES_UNAVAILABLE")
                return prepare(context, target)
            }
        }
        if (target.firstSectionId != target.lastSectionId) return OperationResult.Rejected("REFLOW_SINGLE_SECTION_REQUIRED")
        val prepared = resources.prepare(context, target, false)
        if (prepared !is OperationResult.Ok) return prepared.failure()
        val source = prepared.value
        val section = source.sections.singleOrNull { it.id == target.firstSectionId }
            ?: return OperationResult.Rejected("REFLOW_SECTION_MISSING")
        if (section.pageStart != null || section.pageEnd != null) return OperationResult.Rejected("REFLOW_FIXED_LAYOUT_UNSUPPORTED")
        val blocks = mutableListOf<ContentBlock>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            if (cursor != null && !cursors.add(cursor)) return OperationResult.Rejected("REFLOW_BLOCK_CURSOR_CYCLE")
            val page = access.readBlocks(context, source.snapshot.snapshotId, section.id, cursor)
            if (page !is OperationResult.Ok) return page.failure()
            if (page.value.items.isEmpty() && page.value.nextCursor != null) return OperationResult.Rejected("REFLOW_EMPTY_BLOCK_PAGE")
            if (page.value.items.any { it.sectionId != section.id }) return OperationResult.Rejected("REFLOW_BLOCK_IDENTITY_MISMATCH")
            blocks += page.value.items
            if (blocks.size > 100_000) return OperationResult.Rejected("REFLOW_SECTION_TOO_LARGE")
            cursor = page.value.nextCursor
        } while (cursor != null)
        val result = try {
            withContext(Dispatchers.IO) {
                val revision = storage.openRevision(target.documentId, target.contentRevision, verifyFiles = true)
                    ?: return@withContext OperationResult.Rejected("REVISION_RESOURCES_MISSING")
                val file = source.sectionFiles[section.id] ?: return@withContext OperationResult.Rejected("REFLOW_SECTION_MISSING")
                val html = ReflowDomProjector.project(file.readText(Charsets.UTF_8), blocks)
                val allowed = setOf(ManifestFileKind.SECTION, ManifestFileKind.IMAGE, ManifestFileKind.STYLE, ManifestFileKind.FONT, ManifestFileKind.COVER)
                val files = revision.manifest.files.filter { it.kind in allowed }.associate {
                    it.relativePath to storage.resolveFile(target.documentId, target.contentRevision, it.relativePath)
                }
                OperationResult.Ok(PreparedReflowDocument(source, html, blocks.toList(), section.resourceHref, files), context.requestId)
            }
        } catch (_: IOException) {
            return OperationResult.Failed("REFLOW_RESOURCES_INVALID", false)
        } catch (_: IllegalArgumentException) {
            return OperationResult.Rejected("REFLOW_DOM_IDENTITY_MISMATCH")
        }
        val recheck = access.open(context, target.documentId, target.contentRevision)
        if (recheck !is OperationResult.Ok) return recheck.failure()
        if (recheck.value != source.snapshot) return OperationResult.Rejected("REFLOW_ACCESS_CHANGED")
        return result
    }
    private fun OperationResult<*>.failure(): OperationResult<Nothing> = when (this) {
        is OperationResult.Ok -> error("expected failure")
        is OperationResult.Rejected -> this
        is OperationResult.Failed -> this
        is OperationResult.Conflict -> this
    }
}
