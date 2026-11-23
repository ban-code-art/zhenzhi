package com.example.myapplication.data.quiz

import androidx.room.withTransaction
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.AiRunEventEntity
import com.example.myapplication.domain.quiz.*
import com.example.myapplication.domain.reader.*
import com.example.myapplication.data.session.SessionIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.UUID

/** Owns private review records; no content or approval is taken from a model response. */
class QuizReviewService(private val database: AppDatabase, private val store: QuizReviewDraftStore,
    private val documentAccess: DocumentAccess, private val clock: () -> Long = System::currentTimeMillis,
    private val identityProvider: (suspend () -> SessionIdentity)? = null,
    private val supportAssessor: QuizSupportAssessor = QuizSupportAssessor(),
    private val auditedFactProvider: (String) -> QuizAuditedFact? = { null }) {
    data class Summary(val paperId: String, val createdAt: Long)
    data class Session(val paperId: String, val reference: QuizReviewDraftStore.Reference,
        val draft: QuizReviewDraftStore.Draft, val currentOrdinal: Int = 1,
        val supportAssessments: List<QuizSupportAssessment> = emptyList(),
        val sources: List<QuizEvidenceRef> = emptyList())
    sealed interface Outcome {
        data class Published(val paperId: String) : Outcome
        data class Rejected(val code: String) : Outcome
    }
    private val mutation = Mutex()

    private suspend fun requireIdentity(ctx: AccessContext) {
        val current = identityProvider?.invoke() ?: return
        if (current.userId != ctx.userId || current.epoch != ctx.sessionEpoch)
            throw CancellationException("Review session changed")
    }

    /** Compatibility callers bind before joining the write queue; UI supplies its captured context. */
    private suspend fun contextFor(userId: Long): AccessContext {
        val current = identityProvider?.invoke()
        if (current != null && current.userId != userId) throw CancellationException("Review session changed")
        return AccessContext(userId,current?.epoch ?: 0,"review-${UUID.randomUUID()}")
    }

    private fun normalizedClaims(item: QuizItemSnapshot): List<Pair<String,String>> =
        QuizPersonalReview.expectedClaims(item).map { (role,text) ->
            role to text.replace(Regex("[\\s\\p{P}]+"),"").lowercase(java.util.Locale.ROOT)
        }

    /** Known fact provenance remains binding until its original review proves the actual item again. */
    private fun reassessKnownFact(item: QuizItemSnapshot,previous: QuizSupportAssessment,
        refs: List<QuizEvidenceRef>,sourceRevision: String,structureValid: Boolean = true): QuizSupportAssessment {
        val fact = previous.factKey?.let(auditedFactProvider)
        if (fact == null || fact.key != previous.factKey ||
            !fact.reviewSha256.equals(previous.factReviewSha256,ignoreCase=true) || !fact.approved ||
            fact.kind !in setOf("SEQUENCE","DEFINITION","ENUMERATION")) {
            val fresh = supportAssessor.assess(item,refs,sourceRevision,structureValid=structureValid)
            return fresh.copy(state=QuizSupportState.UNCERTAIN,reasonCodes=listOf("FACT_RECHECK_REQUIRED"),authority="NONE",
                factKey=previous.factKey,factReviewSha256=previous.factReviewSha256,
                assertionChecks=fresh.assertionChecks.map { it.copy(judgement="UNCERTAIN",reason="FACT_RECHECK_REQUIRED") })
        }
        val verified = supportAssessor.assess(item,refs,sourceRevision,fact=fact,structureValid=structureValid)
        // Even cosmetic template mismatches cannot turn an audited contradiction into personal approval.
        return if (verified.state == QuizSupportState.UNCERTAIN)
            verified.copy(reasonCodes=listOf("FACT_RECHECK_REQUIRED"),authority="NONE",
                assertionChecks=verified.assertionChecks.map { it.copy(judgement="UNCERTAIN",reason="FACT_RECHECK_REQUIRED") })
        else verified
    }

    private fun reassessEdited(original: QuizItemSnapshot,edited: QuizItemSnapshot,
        previous: QuizSupportAssessment,refs: List<QuizEvidenceRef>,sourceRevision: String,
        structureValid: Boolean): QuizSupportAssessment {
        if (previous.factKey != null || previous.factReviewSha256 != null)
            return reassessKnownFact(edited,previous,refs,sourceRevision,structureValid)
        val fresh = supportAssessor.assess(edited,refs,sourceRevision,structureValid=structureValid)
        val oldClaims = normalizedClaims(original)
        val newClaims = normalizedClaims(edited)
        val sameQuestionAndAnswer = oldClaims.filter { it.first in setOf("STEM","CORRECT_ANSWER") } ==
            newClaims.filter { it.first in setOf("STEM","CORRECT_ANSWER") }
        val sameEvidence = previous.evidenceHashes.map { it.lowercase(java.util.Locale.ROOT) }.toSet() ==
            fresh.evidenceHashes.map { it.lowercase(java.util.Locale.ROOT) }.toSet()
        val preservedReason = when {
            "CONTRADICTS_FACT" in previous.reasonCodes && sameQuestionAndAnswer -> "CONTRADICTS_FACT"
            oldClaims == newClaims && sameEvidence -> previous.reasonCodes.firstOrNull { it in setOf("UNRELATED_EVIDENCE","EVIDENCE_CONFLICT") }
            else -> null
        }
        if (fresh.state == QuizSupportState.UNSUPPORTED) return fresh
        if (preservedReason != null) {
            val contradicted = preservedReason == "CONTRADICTS_FACT"
            return fresh.copy(state=if(contradicted)QuizSupportState.UNSUPPORTED else QuizSupportState.UNCERTAIN,
                reasonCodes=listOf(preservedReason),authority=if(contradicted)"DETERMINISTIC_REJECTION" else "NONE",
                assertionChecks=fresh.assertionChecks.map { it.copy(judgement=if(contradicted)"CONTRADICTED" else "UNCERTAIN",reason=preservedReason) })
        }
        return if (fresh.state == QuizSupportState.UNCERTAIN) fresh.copy(reasonCodes=listOf("USER_EDIT_REQUIRES_REVIEW"),
            assertionChecks=fresh.assertionChecks.map { it.copy(reason="USER_EDIT_REQUIRES_REVIEW") }) else fresh
    }

    suspend fun list(userId: Long): List<Summary> {
        if (userId <= 0) return emptyList()
        val result = mutableListOf<Summary>()
        var offset = 0
        while (true) {
            val page = database.quizPaperDao().listPapers(userId, 64, offset)
            page.filter { it.status == "FAILED" }.forEach { if (load(userId,it.id) != null) result += Summary(it.id,it.createdAt) }
            if (page.size < 64) return result
            offset += page.size
        }
    }

    suspend fun load(userId: Long, paperId: String): Session? {
        val paper = database.quizPaperDao().findPaperForUser(userId,paperId) ?: return null
        if (paper.status != "FAILED") return null
        return try {
            val coverage = Json.parseToJsonElement(paper.generationCoverageJson).jsonObject
            val reference = store.referenceFromCoverage(coverage) ?: return null
            val draft = store.load(userId,paperId,reference,paper.sourceRevision,Json.parseToJsonElement(paper.scopeJson).jsonObject) ?: return null
            if (draft.supportAssessments.size != draft.items.size || draft.items.any { it.ordinal <= 0 } ||
                draft.items.map { it.ordinal }.distinct().size != draft.items.size) return null
            val sources = refs(draft)
            if (sources.map { it.evidenceId }.distinct().size != sources.size || sources.any {
                    !QuizEvidence.isValidEvidenceId(it.evidenceId) || !QuizEvidence.isSelfConsistent(it) ||
                        it.contentRevision != draft.sourceRevision || it.locator.contentRevision != draft.sourceRevision ||
                        it.documentId != it.locator.documentId || it.sectionId != it.locator.sectionId }) return null
            val proofs = draft.supportAssessments.map { decodeAssessment(it.jsonObject) }
            if (proofs.zip(draft.items).any { (proof,item) -> proof.schemaVersion != 1 ||
                    proof.itemDraftHash != QuizSupportAssessor.itemHash(item) || proof.sourceRevision != draft.sourceRevision }) return null
            val savedOrdinal = (coverage["reviewOrdinal"] as? JsonPrimitive)?.intOrNull
            Session(paperId,reference,draft,savedOrdinal?.takeIf { value -> draft.items.any { it.ordinal == value } }
                ?: draft.items.first().ordinal,proofs,sources)
        } catch (_: IllegalArgumentException) { null }
          catch (_: NoSuchElementException) { null }
          catch (_: IllegalStateException) { null }
    }

    suspend fun savePosition(userId: Long,paperId: String,ordinal: Int) = savePosition(contextFor(userId),paperId,ordinal)

    suspend fun savePosition(ctx: AccessContext,paperId: String,ordinal: Int) = mutation.withLock {
        requireIdentity(ctx)
        database.withTransaction {
            requireIdentity(ctx)
            val current = load(ctx.userId,paperId) ?: return@withTransaction
            val paper = database.quizPaperDao().findPaperForUser(ctx.userId,paperId) ?: return@withTransaction
            val json = Json.parseToJsonElement(paper.generationCoverageJson).jsonObject
            val selected = ordinal.takeIf { value -> current.draft.items.any { it.ordinal == value } } ?: current.draft.items.first().ordinal
            val updated = JsonObject(json + ("reviewOrdinal" to JsonPrimitive(selected)))
            requireIdentity(ctx)
            database.openHelper.writableDatabase.execSQL("UPDATE quiz_papers SET generation_coverage_json = ? WHERE id = ? AND user_id = ? AND status = 'FAILED' AND generation_coverage_json = ?",arrayOf(updated.toString(),paperId,ctx.userId,paper.generationCoverageJson))
            requireIdentity(ctx)
        }
    }

    suspend fun saveEdit(userId: Long,paperId: String,expectedDraftSha: String,ordinal: Int,item: QuizItemSnapshot?): Session? =
        saveEdit(contextFor(userId),paperId,expectedDraftSha,ordinal,item)

    suspend fun saveEdit(ctx: AccessContext,paperId: String,expectedDraftSha: String,ordinal: Int,item: QuizItemSnapshot?): Session? = mutation.withLock {
        requireIdentity(ctx)
        val old = load(ctx.userId,paperId) ?: return@withLock null
        if (old.reference.draftSha256 != expectedDraftSha) return@withLock null
        val originalItem = old.draft.items.firstOrNull { it.ordinal == ordinal } ?: return@withLock null
        val items = old.draft.items.mapNotNull { original -> if (original.ordinal != ordinal) original else item?.copy(ordinal=ordinal,itemId=original.itemId) }
        if (items.isEmpty()) return@withLock null
        val allSources = old.sources.associateBy { it.evidenceId }
        val candidate = QuizPaperSnapshot.of(QuizTemplate.chapterQuizV1(),old.draft.sourceRevision,
            old.draft.scopeSnapshot.toString(),items)
        val report = QuizValidator(QuizTemplate.chapterQuizV1(), evidenceResolver = { id ->
            allSources[id]?.let { source -> SourceEvidence(source.evidenceId,source.documentId,source.sectionId,
                source.contentRevision,source.locator,source.quote,source.quoteSha256,source.sourceTitleSnapshot,
                old.draft.scopeSnapshot["extractorVersion"]?.jsonPrimitive?.content.orEmpty()) }
        },expectedSourceRevision=old.draft.sourceRevision).validate(candidate)
        val proofs = items.map { edited ->
            val previous = old.supportAssessments.firstOrNull { it.itemDraftHash == QuizSupportAssessor.itemHash(edited) }
            if (previous != null) previous else {
                val oldProof = old.supportAssessments[old.draft.items.indexOf(originalItem)]
                val suppliedRefs = edited.evidenceIds.mapNotNull(allSources::get)
                val structurallyValid = report.issues.none { it.itemIndex == items.indexOf(edited) &&
                    it.code !in setOf(QuizValidationCode.EVIDENCE_UNVERIFIED,QuizValidationCode.SOURCE_CHANGED,QuizValidationCode.COVERAGE_INSUFFICIENT) }
                reassessEdited(originalItem,edited,oldProof,suppliedRefs,old.draft.sourceRevision,structurallyValid)
            }
        }
        val changed = old.draft.copy(items=items,supportAssessments=JsonArray(proofs.map(QuizSupportAssessor::encodeAssessment)),updatedAtUtc=utc())
        requireIdentity(ctx)
        val reference = store.saveVersioned(changed)
        requireIdentity(ctx)
        database.withTransaction {
            requireIdentity(ctx)
            val paper = database.quizPaperDao().findPaperForUser(ctx.userId,paperId) ?: return@withTransaction null
            val previous = Json.parseToJsonElement(paper.generationCoverageJson).jsonObject
            if (store.referenceFromCoverage(previous) != old.reference) return@withTransaction null
            val updated = store.mergeCoverage(previous,reference)
            requireIdentity(ctx)
            val changedRows = database.openHelper.writableDatabase.compileStatement(
                "UPDATE quiz_papers SET generation_coverage_json = ? WHERE id = ? AND user_id = ? AND status = 'FAILED' AND generation_coverage_json = ?").use { statement ->
                statement.bindString(1,updated.toString());statement.bindString(2,paperId);statement.bindLong(3,ctx.userId);statement.bindString(4,paper.generationCoverageJson)
                statement.executeUpdateDelete()
            }
            if (changedRows != 1) return@withTransaction null
            val loaded = load(ctx.userId,paperId)?.takeIf { it.reference == reference }
            requireIdentity(ctx)
            loaded
        }
    }

    suspend fun discard(userId: Long, paperId: String) = discard(contextFor(userId), paperId)

    suspend fun discard(ctx: AccessContext, paperId: String) {
        discardCurrentDraft(ctx, paperId, expectedDraftSha = null)
    }

    suspend fun discard(ctx: AccessContext, paperId: String, expectedDraftSha: String): Boolean =
        discardCurrentDraft(ctx, paperId, expectedDraftSha)

    private suspend fun discardCurrentDraft(
        ctx: AccessContext,
        paperId: String,
        expectedDraftSha: String?,
    ): Boolean = mutation.withLock {
        requireIdentity(ctx)
        database.withTransaction {
            requireIdentity(ctx)
            val paper = database.quizPaperDao().findPaperForUser(ctx.userId, paperId)
                ?: return@withTransaction false
            if (paper.status != "FAILED") return@withTransaction false
            val coverage = Json.parseToJsonElement(paper.generationCoverageJson).jsonObject
            if (expectedDraftSha != null && store.referenceFromCoverage(coverage)?.draftSha256 != expectedDraftSha) {
                return@withTransaction false
            }
            val review = coverage["supportReview"] as? JsonObject ?: return@withTransaction false
            val updated = JsonObject(coverage + ("supportReview" to JsonObject(review + ("state" to JsonPrimitive("DISCARDED")))))
            requireIdentity(ctx)
            val changed = database.openHelper.writableDatabase.compileStatement(
                "UPDATE quiz_papers SET generation_coverage_json = ? WHERE id = ? AND user_id = ? " +
                    "AND status = 'FAILED' AND generation_coverage_json = ?",
            ).use { statement ->
                statement.bindString(1, updated.toString())
                statement.bindString(2, paperId)
                statement.bindLong(3, ctx.userId)
                statement.bindString(4, paper.generationCoverageJson)
                statement.executeUpdateDelete()
            }
            requireIdentity(ctx)
            changed == 1
        }
    }

    suspend fun confirmPersonal(ctx: AccessContext,paperId: String,expectedDraftSha: String,
        checkedAssertions: Map<Int,Set<String>>,requestId: String): Outcome = mutation.withLock {
        requireIdentity(ctx)
        if (requestId.isBlank() || requestId.length > 160) return@withLock Outcome.Rejected("INVALID_REQUEST")
        val key = "personal-review:$requestId"
        database.aiRunDao().findRunByRequestKey(ctx.userId,key)?.let { run ->
            val linkedPaperId = database.openHelper.readableDatabase.query(
                "SELECT id FROM quiz_papers WHERE user_id = ? AND generation_run_id = ? AND status = 'READY' LIMIT 1",arrayOf<Any?>(ctx.userId,run.id)).use { cursor ->
                if(cursor.moveToFirst())cursor.getString(0)else null
            }
            val sameRequest = runCatching { val scope=Json.parseToJsonElement(run.scopeSnapshotJson).jsonObject
                scope["sourcePaperId"]?.jsonPrimitive?.content==paperId && scope["draftHash"]?.jsonPrimitive?.content==expectedDraftSha }.getOrDefault(false)
            requireIdentity(ctx)
            return@withLock if (linkedPaperId != null && run.status == "COMPLETED" && sameRequest) Outcome.Published(linkedPaperId) else Outcome.Rejected("REQUEST_ALREADY_USED")
        }
        val session = load(ctx.userId,paperId) ?: return@withLock Outcome.Rejected("REVIEW_NOT_FOUND")
        if (session.reference.draftSha256 != expectedDraftSha) return@withLock Outcome.Rejected("DRAFT_CHANGED")
        val original = database.quizPaperDao().findPaperForUser(ctx.userId,paperId) ?: return@withLock Outcome.Rejected("REVIEW_NOT_FOUND")
        val originalRun = original.generationRunId?.let { database.aiRunDao().findRun(ctx.userId,it) } ?: return@withLock Outcome.Rejected("RUN_MISSING")
        val draft = session.draft
        val gold = session.sources.filter { source -> draft.items.any { source.evidenceId in it.evidenceIds } }
        val sourceMap = linkedMapOf<String,SourceEvidence>()
        try { for (ref in gold) {
            val opened = documentAccess.open(ctx,ref.documentId,draft.sourceRevision)
            if (opened !is OperationResult.Ok) return@withLock Outcome.Rejected("SOURCE_CHANGED")
            val snapshot = opened.value
            val scope = draft.scopeSnapshot
            fun matches(key: String,value: String) = scope[key]?.jsonPrimitive?.content == value
            if (!matches("accessEpoch",snapshot.accessEpoch.toString()) || !matches("metadataRevision",snapshot.metadataRevision.toString()) || !matches("extractorVersion",snapshot.extractorVersion) ||
                !matches("sourceOwnerUserId",snapshot.ownerUserId?.toString() ?: "PUBLIC")) return@withLock Outcome.Rejected("SOURCE_CHANGED")
            val read = documentAccess.readRange(ctx,snapshot.snapshotId,ref.locator,8192)
            if (read !is OperationResult.Ok || read.value.quote != ref.quote || read.value.quoteSha256 != ref.quoteSha256 || read.value.documentId != ref.documentId || read.value.contentRevision != ref.contentRevision ||
                read.value.sectionId != ref.sectionId || read.value.locator != ref.locator || read.value.extractorVersion != snapshot.extractorVersion) return@withLock Outcome.Rejected("SOURCE_CHANGED")
            sourceMap[ref.evidenceId] = read.value.copy(evidenceId=ref.evidenceId)
        } } catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){return@withLock Outcome.Rejected("SOURCE_UNAVAILABLE")}
        requireIdentity(ctx)
        val assessments = draft.items.mapIndexed { index,item ->
            val saved = session.supportAssessments[index]
            val prior = if (saved.factKey != null || saved.factReviewSha256 != null)
                reassessKnownFact(item,saved,gold.filter { it.evidenceId in item.evidenceIds },draft.sourceRevision)
            else saved
            if (prior.state == QuizSupportState.SUPPORTED && prior.authority == "AUDITED_TEMPLATE") prior
            else QuizPersonalReview.approve(item,prior,QuizPersonalApproval(ctx.userId,utc(),expectedDraftSha,prior.evidenceHashes,checkedAssertions[item.ordinal].orEmpty()),ctx.userId)
                ?: return@withLock Outcome.Rejected("ASSERTIONS_NOT_CONFIRMED")
        }
        val newPaperId = "quiz-paper-${UUID.randomUUID()}"
        val runId = "quiz-review-${UUID.randomUUID()}"
        val coverage = JsonObject(Json.parseToJsonElement(original.generationCoverageJson).jsonObject +
            ("personalReview" to buildJsonObject {put("sourcePaperId",paperId);put("draftHash",expectedDraftSha);put("trust","USER_VERIFIED");put("assessments",JsonArray(assessments.map(QuizSupportAssessor::encodeAssessment))) }))
        val paper = QuizPaperSnapshot.of(QuizTemplate.chapterQuizV1(),draft.sourceRevision,original.scopeJson,draft.items,coverageJson=coverage.toString(),paperId=newPaperId)
        val validation = QuizValidator(QuizTemplate.chapterQuizV1(),evidenceResolver=sourceMap::get,expectedSourceRevision=draft.sourceRevision).validate(paper)
        if (!validation.isValid) return@withLock Outcome.Rejected("QUIZ_INVALID")
        val bound = QuizEvidenceBinder(sourceMap::get,draft.sourceRevision).bindPaper(paper)
        val now=clock()
        val run=originalRun.copy(id=runId,requestKey=key,status="RUNNING",activeSlot=null,rounds=0,tokenUsage=0,lastEventSeq=0,createdAt=now,updatedAt=now,
            scopeSnapshotJson=buildJsonObject {put("sourcePaperId",paperId);put("draftHash",expectedDraftSha)}.toString())
        try {
            database.withTransaction {
                requireIdentity(ctx)
                val current = database.quizPaperDao().findPaperForUser(ctx.userId,paperId)
                check(current?.status == "FAILED" && current.generationCoverageJson == original.generationCoverageJson) { "DRAFT_CHANGED" }
                requireIdentity(ctx)
                database.aiRunDao().insertRun(run)
                database.quizPaperDao().insertPaper(original.copy(id=newPaperId,status="VALIDATING",generationRunId=runId,generationCoverageJson=coverage.toString(),createdAt=now))
                val outcome=QuizPublisher(database,clock,personalReviewStore=store,identityProvider=identityProvider).publish(QuizPublisher.Request(ctx,newPaperId,paper,bound,original.documentId,null,runId,draft.sourceRevision,
                    supportAssessments=assessments,personalSourcePaperId=paperId,personalDraftSha256=expectedDraftSha))
                check(outcome is QuizPublisher.Outcome.Published) { (outcome as? QuizPublisher.Outcome.Rejected)?.code ?: "PUBLISH_FAILED" }
                database.aiRunDao().updateRunState(ctx.userId,runId,"COMPLETED",null,0,0,1,now)
                database.aiRunDao().insertRunEvent(AiRunEventEntity(runId,1,"PERSONAL_PRACTICE_REVIEW",payloadJson=coverage.getValue("personalReview").toString(),createdAt=now))
                val oldCoverage=Json.parseToJsonElement(original.generationCoverageJson).jsonObject
                val review=oldCoverage.getValue("supportReview").jsonObject
                val resolved=JsonObject(oldCoverage+("supportReview" to JsonObject(review+("state" to JsonPrimitive("RESOLVED"))+("resolvedToPaperId" to JsonPrimitive(newPaperId)))))
                database.openHelper.writableDatabase.execSQL("UPDATE quiz_papers SET generation_coverage_json = ? WHERE id = ? AND user_id = ? AND status = 'FAILED'",arrayOf(resolved.toString(),paperId,ctx.userId))
                requireIdentity(ctx)
            }
            Outcome.Published(newPaperId)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (error: Exception) { Outcome.Rejected(error.message ?: "PUBLISH_FAILED") }
    }

    private fun refs(draft: QuizReviewDraftStore.Draft) = QuizCodecs.decodeEvidence(draft.sourceSnapshots.toString())
    private fun utc(): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",java.util.Locale.ROOT).apply {timeZone=java.util.TimeZone.getTimeZone("UTC")}.format(java.util.Date(clock()))
    private fun decodeAssessment(o: JsonObject): QuizSupportAssessment {
        return QuizPersonalReview.decodeAssessment(o)
    }
}
