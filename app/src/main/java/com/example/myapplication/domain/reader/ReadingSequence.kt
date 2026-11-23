package com.example.myapplication.domain.reader

import java.util.Collections

/**
 * Versioned snapshot of the logical reading order, including courses spanning documents.
 *
 * Unit identity is (documentId, contentRevision, unitId). Section existence, locator ranges
 * and access rights must still be checked by the engine when opening a target.
 */
class ReadingSequence(val version: String, orderedTargets: List<ReaderTarget>) {
    val orderedTargets: List<ReaderTarget> = Collections.unmodifiableList(
        orderedTargets.map { target ->
            target.copy(
                startLocator = target.startLocator?.snapshot(),
                endLocator = target.endLocator?.snapshot(),
            )
        },
    )

    private val indices: Map<UnitIdentity, Int>

    init {
        require(version.isNotBlank()) { "sequence version must not be blank" }
        val positions = mutableMapOf<UnitIdentity, Int>()
        this.orderedTargets.forEachIndexed { index, target ->
            require(target.documentId.isNotBlank()) { "documentId must not be blank" }
            require(target.contentRevision.isNotBlank()) { "contentRevision must not be blank" }
            require(target.unitId.isNotBlank()) { "unitId must not be blank" }
            require(target.firstSectionId.isNotBlank()) { "firstSectionId must not be blank" }
            require(target.lastSectionId.isNotBlank()) { "lastSectionId must not be blank" }
            require(target.courseBookId == null || target.courseBookId.isNotBlank()) {
                "courseBookId must be null or nonblank"
            }
            require(target.chapterKey == null || target.chapterKey.isNotBlank()) {
                "chapterKey must be null or nonblank"
            }
            require(positions.put(target.identity(), index) == null) {
                "duplicate reading unit identity: ${target.identity()}"
            }
        }
        indices = positions
    }

    /** Returns -1 when the exact document/revision/unit identity is absent. */
    fun indexOf(target: ReaderTarget): Int = indices[target.identity()] ?: -1

    /** Null means the first unit; an unknown target throws [IllegalArgumentException]. */
    fun previous(target: ReaderTarget): ReaderTarget? = orderedTargets.getOrNull(knownIndex(target) - 1)

    /** Null means the last unit; an unknown target throws [IllegalArgumentException]. */
    fun next(target: ReaderTarget): ReaderTarget? = orderedTargets.getOrNull(knownIndex(target) + 1)

    private fun knownIndex(target: ReaderTarget): Int = indexOf(target).also {
        require(it >= 0) { "target is not present in reading sequence $version" }
    }

    private data class UnitIdentity(val documentId: String, val contentRevision: String, val unitId: String)

    private fun ReaderTarget.identity() = UnitIdentity(documentId, contentRevision, unitId)

    private fun ReaderLocator.snapshot(): ReaderLocator = when (this) {
        is ReaderLocator.Fixed -> copy(rects = Collections.unmodifiableList(rects.toList()))
        is ReaderLocator.Reflow -> this
        is ReaderLocator.Note -> this
    }
}
