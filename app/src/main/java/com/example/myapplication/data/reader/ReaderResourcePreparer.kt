package com.example.myapplication.data.reader

import com.example.myapplication.data.reader.storage.DocumentStorage
import com.example.myapplication.data.reader.storage.ManifestFileKind
import com.example.myapplication.domain.reader.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Verified inputs for a future runtime. Preparing resources does not open or paginate a renderer. */
data class PreparedReaderResources(
    val snapshot: DocumentSnapshot,
    val sequence: ReadingSequence,
    val target: ReaderTarget,
    val sectionFiles: Map<String, File>,
    val pdfSource: File?,
    val sections: List<SectionDescriptor> = emptyList(),
)

/** Manifest-backed resources only; caller derives fixedLayout from imported PDF format. */
class ReaderResourcePreparer(
    private val access: DocumentAccess,
    private val storage: DocumentStorage,
) {
    suspend fun prepare(
        context: AccessContext,
        target: ReaderTarget,
        fixedLayout: Boolean,
    ): OperationResult<PreparedReaderResources> {
        context.requireValid()
        val opened = access.open(context, target.documentId, target.contentRevision)
        if (opened !is OperationResult.Ok) return opened.failure()
        val snapshot = opened.value
        val sections = snapshot.sections.toMutableList()
        val seenCursors = mutableSetOf<String>()
        var cursor = snapshot.sectionsNextCursor
        while (cursor != null) {
            if (!seenCursors.add(cursor)) return OperationResult.Rejected("DESCRIPTOR_CURSOR_CYCLE")
            val page = access.readSectionDescriptors(context, snapshot.snapshotId, cursor)
            if (page !is OperationResult.Ok) return page.failure()
            if (page.value.items.isEmpty()) return OperationResult.Rejected("EMPTY_DESCRIPTOR_PAGE")
            sections += page.value.items
            cursor = page.value.nextCursor
        }
        val prepared = try {
            withContext(Dispatchers.IO) {
                require(snapshot.documentId == target.documentId && snapshot.contentRevision == target.contentRevision)
                require(snapshot.ownerUserId == null || snapshot.ownerUserId == context.userId)
                val revision = storage.openRevision(target.documentId, target.contentRevision, verifyFiles = true)
                    ?: return@withContext OperationResult.Rejected("REVISION_RESOURCES_MISSING")
                val manifest = revision.manifest
                if (target.documentId.startsWith("builtin:")) {
                    require(!fixedLayout && snapshot.ownerUserId == null && manifest.extractorVersion == "content-schema2-v1")
                    val body = manifest.files.single { it.kind == ManifestFileKind.SECTION }
                    require(body.sha256 == manifest.sourceSha256 && manifest.sections.size == 1)
                }
                val sequence = ReaderSequenceMapper.map(snapshot, manifest, fixedLayout, sections)
                // Exact equality also rejects forged section bounds, course scope and locator ranges.
                require(target in sequence.orderedTargets) { "target differs from production sequence" }
                // SQL callers need not return sorted pages; use the validated logical order.
                val ordered = sections.sortedBy { it.orderIndex }
                val selected = ordered.subList(ordered.indexOfFirst { it.id == target.firstSectionId },
                    ordered.indexOfFirst { it.id == target.lastSectionId } + 1)
                val files = manifest.files.associateBy { it.relativePath }
                val resources = selected.associate { section ->
                    require(files[section.resourceHref]?.kind == ManifestFileKind.SECTION) {
                        "section resource is not a declared section file"
                    }
                    section.id to storage.resolveFile(target.documentId, target.contentRevision, section.resourceHref)
                }
                val source = if (fixedLayout) {
                    val sourceFile = manifest.files.single { it.kind == ManifestFileKind.SOURCE }
                    require(sourceFile.sha256 == manifest.sourceSha256) { "PDF source differs from imported source" }
                    storage.resolveFile(target.documentId, target.contentRevision, sourceFile.relativePath)
                } else null
                OperationResult.Ok(PreparedReaderResources(snapshot, sequence, target, resources, source, sections.toList()), context.requestId)
            }
        } catch (_: IOException) {
            return OperationResult.Failed("REVISION_RESOURCES_INVALID", retryable = false)
        } catch (_: IllegalArgumentException) {
            return OperationResult.Rejected("RESOURCE_IDENTITY_MISMATCH")
        } catch (_: NoSuchElementException) {
            return OperationResult.Rejected("PDF_SOURCE_MISSING")
        }
        if (prepared !is OperationResult.Ok) return prepared
        // File verification may take time: recheck access and revision before exposing paths.
        val recheck = access.open(context, target.documentId, target.contentRevision)
        if (recheck !is OperationResult.Ok) return recheck.failure()
        val current = recheck.value
        if (current != snapshot) {
            return OperationResult.Rejected("SNAPSHOT_CHANGED_DURING_PREPARATION")
        }
        return prepared
    }

    private fun OperationResult<*>.failure(): OperationResult<Nothing> = when (this) {
        is OperationResult.Conflict -> this
        is OperationResult.Rejected -> this
        is OperationResult.Failed -> this
        is OperationResult.Ok -> error("expected failure")
    }
}




/** Verifies a schema2 BUILTIN against the pinned Room revision, never the mutable document head. */
internal fun verifiedBuiltInProjection(
    storage: DocumentStorage,
    documentId: String,
    revision: com.example.myapplication.data.local.entity.ReaderContentRevisionEntity,
    sections: List<com.example.myapplication.data.local.entity.ReaderSectionEntity>,
    sourceBudget: com.example.myapplication.domain.reader.SourceReadBudget? = null,
): com.example.myapplication.data.reader.storage.PublishedRevision {
    require(revision.documentId == documentId && revision.extractorVersion == "content-schema2-v1")
    require(revision.status == "READY" && sections.size == 1)
    val published = requireNotNull(storage.openRevision(documentId, revision.revision, verifyFiles = true, sourceBudget = sourceBudget))
    val manifest = published.manifest
    require(manifest.extractorVersion == revision.extractorVersion && manifest.sourceSha256 == revision.sourceSha256)
    require(File(published.directory, "manifest.json").canonicalPath == File(revision.manifestPath).canonicalPath)
    val section = sections.single()
    require(section.documentId == documentId && section.contentRevision == revision.revision)
    require(section.id == "$documentId:${revision.revision}:section-1" && section.orderIndex == 0 && section.parentId == null)
    require(section.pageStart == null && section.pageEnd == null && section.canonicalPath == section.resourceHref)
    require(!section.resourceHref.startsWith("asset:"))
    val descriptor = manifest.sections.single()
    require(descriptor.id == section.id && descriptor.orderIndex == section.orderIndex && descriptor.title == section.title &&
        descriptor.resourceHref == section.resourceHref && descriptor.charCount == section.charCount &&
        descriptor.pageStart == section.pageStart && descriptor.pageEnd == section.pageEnd)
    val body = manifest.files.single { it.relativePath == section.resourceHref }
    require(body.kind == ManifestFileKind.SECTION && body.sha256 == manifest.sourceSha256)
    require(manifest.files.count { it.kind == ManifestFileKind.SECTION } == 1)
    val bodyFile = storage.resolveFile(documentId, revision.revision, section.resourceHref)
    require(revision.sourceBlobPath != null && File(revision.sourceBlobPath).canonicalPath == bodyFile.canonicalPath)
    return published
}
