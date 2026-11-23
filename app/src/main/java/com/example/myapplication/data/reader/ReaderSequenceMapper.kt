package com.example.myapplication.data.reader

import com.example.myapplication.data.reader.storage.ContentManifest
import com.example.myapplication.data.reader.storage.ContentManifestValidator
import com.example.myapplication.domain.reader.DocumentSnapshot
import com.example.myapplication.domain.reader.ReaderTarget
import com.example.myapplication.domain.reader.ReadingSequence
import com.example.myapplication.domain.reader.SectionDescriptor
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Maps production descriptors, never outline nodes or guessed locators, to logical units. */
object ReaderSequenceMapper {
    /**
     * [completeSections] is the caller's explicitly fetched complete descriptor set, allowing
     * documents larger than the snapshot's 64-descriptor page. Every snapshot descriptor must
     * match that set exactly. Without it a paginated snapshot cannot authorize book boundaries.
     */
    fun map(
        snapshot: DocumentSnapshot,
        manifest: ContentManifest?,
        fixedLayout: Boolean,
        completeSections: List<SectionDescriptor>? = null,
    ): ReadingSequence {
        require(snapshot.sectionsNextCursor == null || completeSections != null) { "complete descriptors required for paginated snapshot" }
        require(snapshot.sectionsNextCursor == null || checkNotNull(completeSections).size > snapshot.sections.size) {
            "paginated snapshot requires descriptors beyond its current page"
        }
        require(snapshot.accessEpoch >= 0 && snapshot.metadataRevision >= 0) { "invalid snapshot revision/epoch" }
        require(snapshot.ownerUserId == null || snapshot.ownerUserId > 0) { "invalid snapshot owner" }
        val sections = (completeSections ?: snapshot.sections).toList().sortedBy { it.orderIndex }
        require(sections.isNotEmpty()) { "document must contain at least one section" }
        validateSections(snapshot, sections)
        // Validate the snapshot page too: a duplicate page cannot become trusted by supplying a full list.
        validateSections(snapshot, snapshot.sections)
        val byId = sections.associateBy { it.id }
        require(snapshot.sections.all { byId[it.id] == it }) { "snapshot descriptors differ from complete descriptors" }
        if (manifest != null) {
            val validation = ContentManifestValidator.validate(manifest)
            require(validation.isValid) { "invalid content manifest: ${validation.errors.joinToString("; ")}" }
            require(manifest.documentId == snapshot.documentId && manifest.contentRevision == snapshot.contentRevision &&
                manifest.extractorVersion == snapshot.extractorVersion) { "manifest identity does not match snapshot" }
            val descriptors = manifest.sections.map {
                SectionDescriptor(it.id, manifest.documentId, manifest.contentRevision, it.orderIndex,
                    it.title, it.resourceHref, it.charCount, it.pageStart, it.pageEnd)
            }.sortedBy { it.orderIndex }
            // ImportPublisher and MarkdownReparser keep parser-local ids in immutable manifests,
            // while Room keys qualify every id with document and revision. Match that exact scheme
            // as a complete set; never infer aliases from order/href or mix both identity forms.
            val publishedDescriptorsMatch = manifest.documentId.startsWith("imported:") &&
                descriptors.map { it.copy(id = "${manifest.documentId}:${manifest.contentRevision}:${it.id}") } == sections
            require(descriptors == sections || publishedDescriptorsMatch) {
                "manifest descriptors do not match complete snapshot descriptors"
            }
        }
        val targets = if (fixedLayout) listOf(
            ReaderTarget(snapshot.documentId, snapshot.contentRevision, "document:${snapshot.documentId}",
                sections.first().id, sections.last().id),
        ) else sections.map {
            ReaderTarget(snapshot.documentId, snapshot.contentRevision, it.id, it.id, it.id)
        }
        return ReadingSequence(version(snapshot, sections, fixedLayout), targets)
    }

    private fun validateSections(snapshot: DocumentSnapshot, sections: List<SectionDescriptor>) {
        require(sections.map { it.id }.distinct().size == sections.size) { "duplicate section identity" }
        require(sections.map { it.orderIndex }.distinct().size == sections.size) { "duplicate section order" }
        sections.forEach {
            require(it.id.isNotBlank() && it.documentId == snapshot.documentId && it.contentRevision == snapshot.contentRevision) {
                "section identity does not match snapshot"
            }
            require(it.orderIndex >= 0 && it.charCount >= 0) { "invalid section order or character count" }
            require((it.pageStart == null) == (it.pageEnd == null)) { "section page range must be paired" }
            require(it.pageStart == null || (it.pageStart >= 0 && checkNotNull(it.pageEnd) >= it.pageStart)) { "invalid section page range" }
        }
    }

    private fun version(snapshot: DocumentSnapshot, sections: List<SectionDescriptor>, fixedLayout: Boolean): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            // UTF-8 byte lengths and -1 null sentinel avoid separator and null/empty collisions.
            fun field(value: Any?) {
                val encoded = value?.toString()?.toByteArray(Charsets.UTF_8)
                out.writeInt(encoded?.size ?: -1)
                if (encoded != null) out.write(encoded)
            }
            field("reader-sequence-v1")
            field(snapshot.snapshotId); field(snapshot.documentId); field(snapshot.ownerUserId)
            field(snapshot.contentRevision); field(snapshot.metadataRevision); field(snapshot.accessEpoch)
            field(snapshot.extractorVersion); field(fixedLayout); field(sections.size)
            sections.forEach {
                field(it.id); field(it.documentId); field(it.contentRevision); field(it.orderIndex)
                field(it.title); field(it.resourceHref); field(it.charCount); field(it.pageStart); field(it.pageEnd)
            }
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return "reader-sequence-v1:$hash"
    }
}
