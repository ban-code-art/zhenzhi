package com.example.myapplication.data.reader.notes

import com.example.myapplication.data.encyclopedia.EncyclopediaSourceLocator
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.SourceEvidence

/**
 * Stable application boundary for long-lived source citations. The store
 * deliberately delegates authorization and persistence to AnnotationRepository
 * so callers cannot bypass owner/document checks by constructing a reference id.
 */
class SourceReferenceStore(
    private val repository: AnnotationRepository,
) {
    suspend fun register(
        context: AccessContext,
        evidence: SourceEvidence,
        extractionQuality: String = "VERIFIED_TEXT",
    ): OperationResult<SourceReferenceView> =
        repository.registerSourceReference(context, evidence, extractionQuality)

    suspend fun registerEncyclopedia(
        context: AccessContext,
        locator: EncyclopediaSourceLocator,
        title: String,
        quote: String,
    ): OperationResult<SourceReferenceView> =
        repository.registerEncyclopediaSourceReference(context, locator, title, quote)

    suspend fun lookup(
        context: AccessContext,
        referenceId: String,
    ): OperationResult<SourceReferenceView> =
        repository.lookupSourceReference(context, referenceId)
}
