package com.example.myapplication.data.reader

import com.example.myapplication.data.reader.importer.FormatRegistry
import com.example.myapplication.domain.reader.DocumentSnapshot
import com.example.myapplication.domain.reader.ReadingSequence
import com.example.myapplication.domain.reader.SectionDescriptor

/** Identity bridge for a session already authorized by ReaderDocumentRepository.openSession.
 * Does not authorize access, verify file bytes, create locators or enable an engine.
 */
fun ReaderSession.readingSequence(): ReadingSequence {
    require(document.id == revision.documentId && document.contentRevision == revision.revision) {
        "session revision does not belong to document"
    }
    require(document.extractorVersion == revision.extractorVersion) { "session extractor mismatch" }
    require(document.kind != "IMPORTED" || manifest != null) { "imported session requires manifest" }
    if (manifest != null && revision.sourceSha256 != null) {
        require(manifest.sourceSha256 == revision.sourceSha256) { "session source hash mismatch" }
    }
    val descriptors = sections.map { section ->
        SectionDescriptor(section.id, section.documentId, section.contentRevision, section.orderIndex,
            section.title, section.resourceHref, section.charCount, section.pageStart, section.pageEnd)
    }.sortedBy { it.orderIndex }
    val snapshot = DocumentSnapshot(
        // Local identity only; deliberately not a DocumentAccess snapshot handle.
        snapshotId = "reader-session:${document.id.length}:${document.id}:${revision.revision}",
        documentId = document.id, ownerUserId = document.ownerUserId,
        contentRevision = revision.revision, metadataRevision = document.metadataRevision,
        accessEpoch = document.accessEpoch, extractorVersion = revision.extractorVersion,
        sections = descriptors.take(64), sectionsNextCursor = if (descriptors.size > 64) "64" else null,
    )
    return ReaderSequenceMapper.map(snapshot, manifest, FormatRegistry.capabilitiesOf(format).fixedLayout, descriptors)
}
