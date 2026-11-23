package com.example.myapplication.data.reader

import com.example.myapplication.data.local.entity.CourseBookChapterEntity
import com.example.myapplication.data.local.entity.CourseBookEntity
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import com.example.myapplication.domain.reader.ReaderTarget
import com.example.myapplication.domain.reader.ReadingSequence
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Structural mapping only. Publication, review evidence and read authorization remain caller-owned. */
object CourseReadingSequenceMapper {
    /**
     * [documents] must contain complete, validated per-document sequences from production descriptors.
     * A section mapping requires a real single-section unit; null requires an unambiguous single unit.
     * This never splits fixed-layout units or invents locator offsets for course chapter boundaries.
     */
    fun map(
        book: CourseBookEntity,
        chapters: List<CourseBookChapterEntity>,
        documents: Map<String, ReadingSequence>,
    ): ReadingSequence {
        require(book.bookId.isNotBlank() && book.contentKey.isNotBlank() && book.packageId.isNotBlank()) {
            "invalid course book identity"
        }
        require(book.contentVersion >= 0 && book.orderIndex >= 0 && book.reviewStatus.isNotBlank()) {
            "invalid course book metadata"
        }
        val mappings = chapters.toList()
        require(mappings.map { it.chapterKey }.distinct().size == mappings.size) { "duplicate course chapter key" }
        mappings.forEach {
            require(it.bookId == book.bookId && it.chapterKey.isNotBlank() && it.documentId.isNotBlank()) {
                "course chapter identity does not match book"
            }
            require(it.orderIndex >= 0 && (it.sectionId == null || it.sectionId.isNotBlank()) &&
                (it.legacyChapterId == null || it.legacyChapterId > 0)) { "invalid course chapter mapping" }
        }
        val byKey = mappings.associateBy { it.chapterKey }
        // Validate disabled rows too: re-enabling a row must not introduce a dangling or cyclic graph.
        val complete = mutableSetOf<String>()
        mappings.forEach { chapter ->
            var node: CourseBookChapterEntity? = chapter
            val path = mutableSetOf<String>()
            while (node != null && node.chapterKey !in complete) {
                require(path.add(node.chapterKey)) { "cyclic course chapter parent graph" }
                val parentKey = node.parentKey
                val parent = if (parentKey == null) null else requireNotNull(byKey[parentKey]) {
                    "missing course chapter parent"
                }
                require(!node.enabled || parent == null || parent.enabled) { "enabled course chapter has disabled parent" }
                node = parent
            }
            complete.addAll(path)
        }
        val active = mappings.filter { it.enabled }.sortedBy { it.orderIndex }
        require(active.isNotEmpty()) { "course book has no enabled chapters" }
        require(active.map { it.orderIndex }.distinct().size == active.size) { "duplicate course chapter order" }
        val sources = active.map { chapter ->
            val sequence = requireNotNull(documents[chapter.documentId]) { "missing course document sequence" }
            require(sequence.orderedTargets.isNotEmpty() && sequence.orderedTargets.all {
                it.documentId == chapter.documentId && it.contentRevision == sequence.orderedTargets.first().contentRevision &&
                    it.courseBookId == null && it.chapterKey == null
            }) { "invalid course document sequence identity" }
            val target = if (chapter.sectionId == null) {
                require(sequence.orderedTargets.size == 1) { "ambiguous whole-document course chapter" }
                sequence.orderedTargets.single()
            } else {
                requireNotNull(sequence.orderedTargets.singleOrNull {
                    it.firstSectionId == chapter.sectionId && it.lastSectionId == chapter.sectionId
                }) { "course section does not identify a single real reading unit" }
            }
            target.copy(courseBookId = book.bookId, chapterKey = chapter.chapterKey)
        }
        // ReadingSequence rejects duplicate physical unit identities even across distinct chapter keys.
        return ReadingSequence(version(book, mappings, active, documents, sources), sources)
    }

    private fun version(
        book: CourseBookEntity,
        chapters: List<CourseBookChapterEntity>,
        active: List<CourseBookChapterEntity>,
        documents: Map<String, ReadingSequence>,
        targets: List<ReaderTarget>,
    ): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun field(value: Any?) {
                val encoded = value?.toString()?.toByteArray(Charsets.UTF_8)
                out.writeInt(encoded?.size ?: -1)
                if (encoded != null) out.write(encoded)
            }
            field("course-reading-sequence-v1")
            field(book.bookId); field(book.contentKey); field(book.title); field(book.summary)
            field(book.coverAsset); field(book.orderIndex); field(book.packageId)
            field(book.contentVersion); field(book.reviewStatus)
            field(chapters.size)
            chapters.sortedBy { it.chapterKey }.forEach {
                field(it.bookId); field(it.chapterKey); field(it.parentKey); field(it.orderIndex)
                field(it.documentId); field(it.sectionId); field(it.legacyChapterId); field(it.enabled)
            }
            field(active.size)
            active.zip(targets).forEach { (chapter, target) ->
                field(documents.getValue(chapter.documentId).version)
                field(target.documentId); field(target.contentRevision); field(target.unitId)
                field(target.firstSectionId); field(target.lastSectionId)
                field(target.startLocator?.let(ReaderLocatorCodec::encode))
                field(target.endLocator?.let(ReaderLocatorCodec::encode))
                field(target.courseBookId); field(target.chapterKey)
            }
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return "course-reading-sequence-v1:$hash"
    }
}
