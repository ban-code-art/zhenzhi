package com.example.myapplication.data.knowledge.retrieval

import com.example.myapplication.core.AppLog
import com.example.myapplication.data.knowledge.index.VectorEncodingPreference
import com.example.myapplication.data.knowledge.lexical.LexicalIndex
import com.example.myapplication.data.local.dao.KnowledgeDocumentDao
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.DocumentAccess
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.PinnedDocument
import com.example.myapplication.domain.reader.RetrievalBranchStatus
import com.example.myapplication.domain.reader.RetrievalHit
import com.example.myapplication.domain.reader.RetrievalOutcome
import com.example.myapplication.domain.reader.RetrievalRequest
import com.example.myapplication.domain.reader.SourceEvidence
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Frozen scope → lexical/vector → RRF/MMR → source readback → optional reranking. */
class HybridRetriever(
    private val lexicalIndex: LexicalIndex,
    private val exactBackend: ExactVectorBackend,
    private val annBackend: AnnVectorBackend?,
    private val evidenceAssembler: EvidenceAssembler,
    private val documentAccess: DocumentAccess,
    private val queryEmbedding: QueryEmbeddingProvider,
    private val clock: () -> Long = System::currentTimeMillis,
    private val vectorEncodingPreference: VectorEncodingPreference? = null,
    private val reranker: CandidateReranker? = null,
    private val scopePinVerifier: ScopePinVerifier? = null,
) {
    fun interface QueryEmbeddingProvider {
        suspend fun embed(userId: Long, query: String, profileFingerprint: String): FloatArray?
    }

    /** Only source-verified scoped candidates are supplied; providers may reorder or change scores. */
    fun interface CandidateReranker {
        suspend fun rerank(request: RetrievalRequest, candidates: List<RetrievalHit>): List<RetrievalHit>
    }

    /** Independent index identity check; optional only for legacy fixture compatibility. */
    fun interface ScopePinVerifier {
        suspend fun verify(ctx: AccessContext, pin: PinnedDocument): Boolean
    }

    /** Index metadata is independent authority; stored chunk/vector rows cannot publish a pin. */
    class RoomScopePinVerifier(private val knowledgeDocuments: KnowledgeDocumentDao) : ScopePinVerifier {
        override suspend fun verify(ctx: AccessContext, pin: PinnedDocument): Boolean {
            val document = knowledgeDocuments.find(pin.knowledgeDocumentId) ?: return false
            return document.id == pin.knowledgeDocumentId && document.userId == ctx.userId &&
                document.documentId == pin.documentId && document.sourceRevision == pin.sourceRevision &&
                document.accessEpoch == pin.accessEpoch && document.activeGeneration == pin.generation &&
                document.lexicalState == "READY"
        }
    }

    data class Result(
        val outcome: RetrievalOutcome,
        val evidence: List<SourceEvidence>,
        val droppedCandidates: List<EvidenceAssembler.DroppedCandidate>,
    )

    suspend fun retrieve(request: RetrievalRequest): Result {
        val started = clock()
        val requestId = UUID.randomUUID().toString()
        val timings = linkedMapOf<String, Long>()
        val warnings = linkedSetOf<String>()
        // Detach caller-owned lists before the first suspended operation.
        val scope = request.scopeSnapshot.copy(documents = request.scopeSnapshot.documents.toList())
        val pins = scope.documents.associateBy { it.documentId }
        val snapshots = linkedMapOf<String, String>()

        fun refused(reason: String): Result {
            warnings += reason
            timings["total"] = clock() - started
            return Result(RetrievalOutcome(
                requestId = requestId, results = emptyList(), pinnedGenerationMap = emptyMap(),
                lexicalStatus = RetrievalBranchStatus.UNAVAILABLE, vectorStatus = RetrievalBranchStatus.UNAVAILABLE,
                scannedCount = 0, eligibleCount = 0, partial = false, timedOut = false,
                degradedReason = reason, stageTimings = timings.toMap(), warnings = warnings.toList(),
            ), emptyList(), emptyList())
        }

        if (scope.userId != request.ctx.userId) return refused("SCOPE_USER_MISMATCH")
        if (scope.documents.isEmpty()) return refused("EMPTY_SCOPE")
        if (scope.documents.size > LexicalIndex.MAX_SCOPE_DOCUMENTS) return refused("SCOPE_TOO_LARGE")
        if (pins.size != scope.documents.size || scope.documents.map { it.knowledgeDocumentId }.distinct().size != pins.size ||
            scope.documents.any { it.documentId.isBlank() || it.knowledgeDocumentId.isBlank() ||
                it.sourceRevision.isBlank() || it.generation <= 0 || it.accessEpoch <= 0 }) {
            return refused("SCOPE_PIN_INVALID")
        }
        if (scopePinVerifier == null) warnings += "SCOPE_PIN_VERIFICATION_UNAVAILABLE"

        suspend fun validateScope(captureSnapshots: Boolean = false): Boolean {
            for (pin in pins.values) {
                val opened = try {
                    documentAccess.open(request.ctx, pin.documentId, pin.sourceRevision)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    warnings += "SOURCE_ACCESS_FAILED"
                    return false
                }
                if (opened !is OperationResult.Ok || opened.value.documentId != pin.documentId ||
                    opened.value.contentRevision != pin.sourceRevision || opened.value.accessEpoch != pin.accessEpoch) {
                    return false
                }
                // Source open may suspend; check published index identity after that boundary.
                if (scopePinVerifier != null) {
                    val valid = try {
                        scopePinVerifier.verify(request.ctx, pin)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        warnings += "SCOPE_PIN_VERIFICATION_FAILED"
                        false
                    }
                    if (!valid) return false
                }
                if (captureSnapshots) snapshots[pin.documentId] = opened.value.snapshotId
            }
            return true
        }

        fun scoped(hits: List<RetrievalHit>): List<RetrievalHit> = hits.filter { hit ->
            val pin = pins[hit.documentId]
            val valid = pin != null && hit.knowledgeDocumentId == pin.knowledgeDocumentId &&
                hit.sourceRevision == pin.sourceRevision && hit.generation == pin.generation && hit.score.isFinite()
            if (!valid) warnings += "SCOPE_PIN_INVALID"
            valid
        }

        if (!validateScope(captureSnapshots = true)) return refused("SCOPE_PIN_INVALID")
        timings["open"] = clock() - started

        var lexicalStatus = RetrievalBranchStatus.COMPLETE
        val lexicalStarted = clock()
        val lexicalHits = try {
            scoped(lexicalIndex.search(
                userId = request.ctx.userId, query = request.query,
                documentIds = pins.keys.toList(), candidateLimit = CANDIDATE_LIMIT, topK = CANDIDATE_LIMIT,
            ).map { candidate -> RetrievalHit(
                chunkId = candidate.chunkId, knowledgeDocumentId = candidate.knowledgeDocumentId,
                documentId = candidate.documentId, sectionId = candidate.sectionId, sectionOrder = 0, ordinal = 0,
                rawText = candidate.rawText, locatorJson = candidate.locatorJson,
                sourceRevision = candidate.sourceRevision, generation = candidate.generation, score = candidate.score,
            ) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            lexicalStatus = RetrievalBranchStatus.UNAVAILABLE
            warnings += "LEXICAL_FAILED"
            emptyList()
        }
        timings["lexical"] = clock() - lexicalStarted
        if (!validateScope()) return refused("SCOPE_PIN_INVALID")

        var vectorStatus = RetrievalBranchStatus.UNAVAILABLE
        var vectorHits: List<RetrievalHit> = emptyList()
        var approximate = false
        var vectorScanned = 0
        var vectorEligible = 0
        var vectorTimedOut = false
        val vectorStarted = clock()
        val profile = request.profileFingerprint
        if (profile.isNullOrBlank()) {
            warnings += "VECTOR_PROFILE_MISSING"
        } else {
            var failureCode = "VECTOR_PROFILE_FAILED"
            try {
                val indexProfile = vectorEncodingPreference?.activeIndexFingerprint(request.ctx.userId, profile) ?: profile
                failureCode = "QUERY_EMBEDDING_FAILED"
                val queryVector = queryEmbedding.embed(request.ctx.userId, request.query, profile)
                // Revocation during provider IO must also invalidate already retrieved lexical text.
                if (!validateScope()) return refused("SCOPE_PIN_INVALID")
                if (queryVector == null) {
                    warnings += "QUERY_EMBEDDING_UNAVAILABLE"
                } else {
                    failureCode = "VECTOR_BACKEND_FAILED"
                    val backendResult = if (annBackend != null && pins.size > 1) {
                        val ann = annBackend.search(
                            userId = request.ctx.userId, documents = pins.values.toList(), profileFingerprint = indexProfile,
                            queryVector = queryVector, topK = CANDIDATE_LIMIT,
                            workBudget = request.workBudget, deadlineAtMs = request.deadlineAtMs,
                        )
                        approximate = ann.approximate
                        ExactVectorBackend.Result(ann.hits, ann.status, ann.scannedCount, ann.eligibleCount,
                            0, ann.timedOut, ann.warnings, 0)
                    } else {
                        exactBackend.search(
                            userId = request.ctx.userId, documents = pins.values.toList(), profileFingerprint = indexProfile,
                            queryVector = queryVector, topK = CANDIDATE_LIMIT,
                            workBudget = request.workBudget, deadlineAtMs = request.deadlineAtMs,
                        )
                    }
                    vectorHits = scoped(backendResult.hits)
                    vectorStatus = backendResult.status
                    vectorScanned = backendResult.scannedCount
                    vectorEligible = backendResult.eligibleCount
                    vectorTimedOut = backendResult.timedOut
                    warnings += backendResult.warnings
                    if (vectorStatus == RetrievalBranchStatus.COMPLETE && vectorEligible == 0) {
                        vectorStatus = RetrievalBranchStatus.UNAVAILABLE
                        warnings += "VECTOR_INDEX_EMPTY"
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                warnings += failureCode
                vectorStatus = RetrievalBranchStatus.UNAVAILABLE
                vectorHits = emptyList()
            }
        }
        timings["vector"] = clock() - vectorStarted
        if (!validateScope()) return refused("SCOPE_PIN_INVALID")

        val fusionStarted = clock()
        val diversified = scoped(RrfFusion.mmr(RrfFusion.fuse(
            lexical = lexicalHits, vector = vectorHits, topK = CANDIDATE_LIMIT,
            preferDocumentId = request.preferDocumentId, preferSectionId = request.preferSectionId,
        ), topK = request.limit))
        timings["fusion"] = clock() - fusionStarted

        val evidenceStarted = clock()
        val assembled = evidenceAssembler.assemble(request.ctx, diversified, snapshots, pinnedDocuments = pins)
        timings["evidence"] = clock() - evidenceStarted
        if (assembled.dropped.isNotEmpty()) warnings += "DROPPED_" + assembled.dropped.size + "_CANDIDATES"
        if (assembled.truncatedQuoteCount > 0) warnings += "TRUNCATED_" + assembled.truncatedQuoteCount + "_EVIDENCE"
        var selected = diversified.filter { it.chunkId in assembled.evidenceByChunkId }
        if (!validateScope()) return refused("SCOPE_PIN_INVALID")

        val rerankStarted = clock()
        if (reranker != null && selected.isNotEmpty()) {
            val authorized = selected.associateBy { it.chunkId }
            try {
                val reranked = reranker.rerank(
                    request.copy(scopeSnapshot = scope.copy(documents = pins.values.toList())), selected.toList(),
                )
                val seen = hashSetOf<Long>()
                selected = reranked.mapNotNull { hit ->
                    val original = authorized[hit.chunkId]
                    if (original == null || !hit.score.isFinite() || hit.copy(score = original.score) != original ||
                        !seen.add(hit.chunkId)) {
                        warnings += "RERANK_SCOPE_VIOLATION"
                        null
                    } else original.copy(score = hit.score)
                }.take(request.limit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                warnings += "RERANK_FAILED"
                // Keep independently verified pre-rerank results when the real provider fails.
            }
        }
        timings["rerank"] = clock() - rerankStarted
        selected = scoped(selected)
        if (!validateScope()) return refused("SCOPE_PIN_INVALID")

        val degradedReason = when {
            "RERANK_FAILED" in warnings || "RERANK_SCOPE_VIOLATION" in warnings -> "RERANK_UNAVAILABLE"
            "SCOPE_PIN_INVALID" in warnings -> "SCOPE_PIN_INVALID"
            lexicalStatus == RetrievalBranchStatus.UNAVAILABLE && vectorStatus == RetrievalBranchStatus.UNAVAILABLE -> "RETRIEVAL_UNAVAILABLE"
            "VECTOR_PROFILE_MISSING" in warnings -> "VECTOR_NOT_CONFIGURED"
            vectorStatus == RetrievalBranchStatus.UNAVAILABLE -> "VECTOR_UNAVAILABLE"
            lexicalStatus == RetrievalBranchStatus.UNAVAILABLE -> "LEXICAL_UNAVAILABLE"
            else -> null
        }
        if (warnings.isNotEmpty()) AppLog.w("retrieval", "warnings=" + warnings.joinToString(",") + " hits=" + selected.size)
        timings["total"] = clock() - started
        return Result(
            RetrievalOutcome(
                requestId = requestId, results = selected, pinnedGenerationMap = pins.toMap(),
                lexicalStatus = lexicalStatus, vectorStatus = vectorStatus,
                scannedCount = vectorScanned, eligibleCount = vectorEligible,
                partial = vectorStatus == RetrievalBranchStatus.PARTIAL, timedOut = vectorTimedOut,
                degradedReason = degradedReason, approximate = approximate,
                // A backend row cursor is not a scope-bound continuation token.
                continuation = null, stageTimings = timings.toMap(), warnings = warnings.toList(),
            ),
            selected.mapNotNull { assembled.evidenceByChunkId[it.chunkId] }, assembled.dropped,
        )
    }

    companion object { const val CANDIDATE_LIMIT = 80 }
}
