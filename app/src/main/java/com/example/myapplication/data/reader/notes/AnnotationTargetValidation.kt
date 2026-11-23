package com.example.myapplication.data.reader.notes

import com.example.myapplication.data.local.entity.ReaderSectionEntity
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import java.util.Collections

internal object AnnotationTargetValidation {
    fun validateAndFreeze(
        parentLocator: ReaderLocator,
        targets: List<AnnotationTargetInput>,
        sections: List<ReaderSectionEntity>,
    ): OperationResult<List<AnnotationTargetInput>> {
        if (targets.isEmpty()) return OperationResult.Rejected("INVALID_ARGUMENT")
        val frozen = freezeTargets(targets)
        try {
            AnnotationMutationCodec.locatorProjection(parentLocator)
            val parentIdentity = ReaderLocatorCodec.encode(ReaderLocatorCodec.decode(ReaderLocatorCodec.encode(parentLocator)))
            frozen.forEach { target ->
                require(target.precision in setOf("EXACT", "PAGE_ONLY", "UNRESOLVED"))
                AnnotationMutationCodec.locatorProjection(target.locator)
                require(target.locator.documentId == parentLocator.documentId && target.locator.contentRevision == parentLocator.contentRevision)
                val actual = sections.filter { it.id == target.locator.sectionId }
                require(actual.size == 1 && actual[0].documentId == parentLocator.documentId && actual[0].contentRevision == parentLocator.contentRevision)
            }
            require(parentIdentity == ReaderLocatorCodec.encode(ReaderLocatorCodec.decode(ReaderLocatorCodec.encode(frozen.first().locator))))
        } catch (_: IllegalArgumentException) {
            return OperationResult.Rejected("INVALID_ARGUMENT")
        }
        for (index in 1 until frozen.size) {
            val previous = frozen[index - 1].locator
            val current = frozen[index].locator
            if (previous::class != current::class) return OperationResult.Rejected("INVALID_TARGET_ORDER")
            if (previous.sectionId != current.sectionId) {
                val before = sections.single { it.id == previous.sectionId }.orderIndex
                val after = sections.single { it.id == current.sectionId }.orderIndex
                if (before >= after || sections.count { it.orderIndex == before } != 1 || sections.count { it.orderIndex == after } != 1) {
                    return OperationResult.Rejected("INVALID_TARGET_ORDER")
                }
            } else {
                if (previous !is ReaderLocator.Reflow || current !is ReaderLocator.Reflow ||
                    previous.canonicalEnd > current.canonicalStart ||
                    previous.canonicalStart >= current.canonicalStart
                ) return OperationResult.Rejected("INVALID_TARGET_ORDER")
                // Both coordinate systems are section-global, but legacy DOM offsets remain
                // UNRESOLVED: a live HTML guard does not make them prepared canonical EXACT.
                // Cross-block fragments must share a coordinate/precision kind and cover their
                // complete UTF-16 quotes. The store requires source proof at both checkpoints.
                if (previous.blockId != current.blockId &&
                    (frozen[index - 1].precision !in setOf("EXACT", "UNRESOLVED") ||
                        frozen[index].precision != frozen[index - 1].precision ||
                        previous.canonicalEnd.toLong() - previous.canonicalStart != previous.exactQuote.length.toLong() ||
                        current.canonicalEnd.toLong() - current.canonicalStart != current.exactQuote.length.toLong())) {
                    return OperationResult.Rejected("INVALID_TARGET_ORDER")
                }
            }
        }
        return OperationResult.Ok(frozen, "annotation-target-validation")
    }

    fun requiresSourceGuard(targets: List<AnnotationTargetInput>): Boolean =
        targets.any { it.precision == "EXACT" } ||
            (targets.size > 1 && targets.any { it.precision == "UNRESOLVED" })

    fun freezeView(view: ReaderAnnotationView): ReaderAnnotationView = view.copy(
        locator = view.locator?.let(::freezeLocator),
        targets = freezeTargets(view.targets),
    )

    fun freezeTargets(targets: List<AnnotationTargetInput>): List<AnnotationTargetInput> = immutable(targets.map {
        AnnotationTargetInput(freezeLocator(it.locator), it.precision)
    })

    fun freezeLocator(locator: ReaderLocator): ReaderLocator = when (locator) {
        is ReaderLocator.Fixed -> locator.copy(rects = immutable(locator.rects.toList()))
        is ReaderLocator.Reflow -> locator.copy()
        is ReaderLocator.Note -> locator.copy()
    }

    private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
}
