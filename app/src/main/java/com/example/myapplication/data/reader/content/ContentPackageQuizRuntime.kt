package com.example.myapplication.data.reader.content

import androidx.room.withTransaction
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.ReaderContentRevisionEntity
import com.example.myapplication.data.local.entity.ReaderSectionEntity
import com.example.myapplication.data.reader.verifiedBuiltInProjection
import com.example.myapplication.data.reader.storage.DocumentStorage
import com.example.myapplication.data.quiz.QuizAuditedFact
import com.example.myapplication.data.quiz.QuizSupportAssessor
import com.example.myapplication.domain.quiz.*
import com.example.myapplication.domain.reader.*
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

private class RuntimeSourceChanged : IllegalStateException("SOURCE_CHANGED")

data class ContentQuizRuntimePin(val versionId:String,val generation:Long,val packageVersion:Int,val chapterKey:String,val documentId:String,val contentRevision:String,val bodySha256:String,val accessEpoch:Long)
enum class ContentQuizRuntimeState { APPROVED, REVIEW_REQUIRED, REJECTED, SOURCE_CHANGED }
data class ContentQuizRuntimeResult(val state:ContentQuizRuntimeState,val snapshot:ContentPackageQuizRuntime.Snapshot?=null,val issues:List<ContentPackageIssue> = emptyList())

/** Explicit authority boundary. The production factory reads the Room active package, never the FS active pointer. */
class ContentPackageQuizRuntime(
    internal val pin: suspend (String)->ContentQuizRuntimePin?,
    internal val readInput:suspend (String)->ContentPackageArchiveInput,
) {
    suspend fun load(chapterKey:String,professionalVerifier:ContentProfessionalReviewVerifier?=null,finalReviews:Map<String,ContentQuizFinalReview> = emptyMap(),finalReviewVerifier:ContentQuizFinalReviewVerifier?=null):ContentQuizRuntimeResult =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val result=Snapshot.loadVerified(this@ContentPackageQuizRuntime,chapterKey,professionalVerifier,finalReviews,finalReviewVerifier)
            currentCoroutineContext().ensureActive()
            result
        }

    /** No constructor or unchecked approved-fact factory is exposed. Every getter returns a private defensive value. */
    class Snapshot private constructor(
        val sourcePin:ContentQuizRuntimePin,
        private val facts:List<PreparedContentQuizFact>,
        private val records:Map<String,ByteArray>,
        private val readPin:suspend(String)->ContentQuizRuntimePin?,
    ) {
        val preparedFacts:List<PreparedContentQuizFact> get()=frozen(facts.map(::copyPrepared))
        val supportAssessor:QuizSupportAssessor get()=QuizSupportAssessor { key -> reviewBytes(key) }
        fun factForItem(item:QuizItemSnapshot):QuizAuditedFact? = facts.singleOrNull { it.fact.prompt==item.stem && it.fact.knowledgePoint==item.knowledgePoint }?.fact?.let(::copyFact)
        fun factByKey(key:String):QuizAuditedFact? = facts.singleOrNull { it.fact.key==key }?.fact?.let(::copyFact)
        fun reviewBytes(key:String):ByteArray? = records[key]?.copyOf()
        fun render(factKey:String,type:QuizItemType,ordinal:Int,seed:Long=0):QuizItemSnapshot {
            require(ordinal>0) { "runtime.render: ordinal must be positive" }
            val prepared=requireNotNull(facts.singleOrNull { it.fact.key==factKey }) { "runtime.render: unknown fact key" }
            val item=QuizSupportAssessor().renderFactV1(copyFact(prepared.fact),type,prepared.evidenceRefs.map(::copyRef),ordinal,seed)
            return item.copy(options=frozen(item.options),answer=frozen(item.answer),evidenceIds=frozen(item.evidenceIds),rubric=frozen(item.rubric.map { it.copy(accepted=frozen(it.accepted),negatedBy=frozen(it.negatedBy)) }))
        }
        /** Every real read is bracketed with a fresh package generation/source pin, including already-open snapshots. */
        fun guard(access:DocumentAccess):DocumentAccess = object:DocumentAccess {
            private val opened=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            private val sectionId="${sourcePin.documentId}:${sourcePin.contentRevision}:section-1"
            private suspend fun current():Boolean = try { readPin(sourcePin.chapterKey)==sourcePin } catch(c:CancellationException) { throw c } catch(_:Exception) { false }
            private suspend fun <T> checked(read:suspend()->OperationResult<T>):OperationResult<T> {
                if(!current()) return OperationResult.Rejected("SOURCE_CHANGED")
                val result=read()
                return if(current()) result else OperationResult.Rejected("SOURCE_CHANGED")
            }
            override suspend fun open(ctx:AccessContext,documentId:String,requestedRevision:String?):OperationResult<DocumentSnapshot> = checked {
                if(documentId!=sourcePin.documentId || requestedRevision!=null && requestedRevision!=sourcePin.contentRevision) OperationResult.Rejected("SOURCE_CHANGED")
                else {
                    val result=access.open(ctx,documentId,sourcePin.contentRevision)
                    if(result is OperationResult.Ok && (result.value.documentId!=sourcePin.documentId || result.value.contentRevision!=sourcePin.contentRevision || result.value.accessEpoch!=sourcePin.accessEpoch)) OperationResult.Rejected("SOURCE_CHANGED") else {
                        if(result is OperationResult.Ok) opened.add(result.value.snapshotId)
                        result
                    }
                }
            }
            override suspend fun readSectionDescriptors(ctx:AccessContext,snapshotId:String,cursor:String?,limit:Int):OperationResult<ReaderPage<SectionDescriptor>> = checked {
                if(snapshotId !in opened) OperationResult.Rejected("SOURCE_CHANGED") else {
                    val result=access.readSectionDescriptors(ctx,snapshotId,cursor,limit)
                    if(result is OperationResult.Ok && result.value.items.any { it.documentId!=sourcePin.documentId || it.contentRevision!=sourcePin.contentRevision || it.id!=sectionId }) OperationResult.Rejected("SOURCE_CHANGED") else result
                }
            }
            override suspend fun readBlocks(ctx:AccessContext,snapshotId:String,sectionId:String,cursor:String?,maxBytes:Int):OperationResult<ReaderPage<ContentBlock>> = checked {
                if(snapshotId !in opened || sectionId!=this.sectionId) OperationResult.Rejected("SOURCE_CHANGED") else {
                    val result=access.readBlocks(ctx,snapshotId,sectionId,cursor,maxBytes)
                    if(result is OperationResult.Ok && result.value.items.any { it.sectionId!=sectionId }) OperationResult.Rejected("SOURCE_CHANGED") else result
                }
            }
            override suspend fun readRange(ctx:AccessContext,snapshotId:String,locator:ReaderLocator,maxTokens:Int):OperationResult<SourceEvidence> = checked {
                if(snapshotId !in opened || locator.documentId!=sourcePin.documentId || locator.contentRevision!=sourcePin.contentRevision || locator.sectionId!=sectionId) OperationResult.Rejected("SOURCE_CHANGED") else {
                    val result=access.readRange(ctx,snapshotId,locator,maxTokens)
                    if(result is OperationResult.Ok && (result.value.documentId!=sourcePin.documentId || result.value.contentRevision!=sourcePin.contentRevision || result.value.sectionId!=sectionId)) OperationResult.Rejected("SOURCE_CHANGED") else result
                }
            }
        }
        companion object {
            internal suspend fun loadVerified(runtime:ContentPackageQuizRuntime,key:String,professional:ContentProfessionalReviewVerifier?,reviews:Map<String,ContentQuizFinalReview>,finalVerifier:ContentQuizFinalReviewVerifier?):ContentQuizRuntimeResult {
                val workerContext=currentCoroutineContext()
                var pinned:ContentQuizRuntimePin?=null
                suspend fun finish(state:ContentQuizRuntimeState,issues:List<ContentPackageIssue> = emptyList(),snapshot:Snapshot?=null):ContentQuizRuntimeResult {
                    workerContext.ensureActive()
                    val before=pinned
                    val after=if(before!=null) runtime.pin(key) else null
                    workerContext.ensureActive()
                    if(before!=null && after!=before) return ContentQuizRuntimeResult(ContentQuizRuntimeState.SOURCE_CHANGED)
                    return ContentQuizRuntimeResult(state,snapshot,frozen(issues))
                }
                try {
                    workerContext.ensureActive()
                    val before=runtime.pin(key) ?: return ContentQuizRuntimeResult(ContentQuizRuntimeState.REJECTED,issues=listOf(ContentPackageIssue("room","no schema2 active source")))
                    workerContext.ensureActive()
                    pinned=before
                    require(before.chapterKey==key && before.documentId=="builtin:chapter:$key" && before.generation>0 && before.packageVersion>0 && before.accessEpoch>0 &&
                        listOf(before.versionId,before.contentRevision,before.bodySha256).all { it.matches(Regex("[0-9a-f]{64}")) }) { "runtime: malformed source pin" }
                    workerContext.ensureActive()
                    val supplied=runtime.readInput(before.versionId)
                    workerContext.ensureActive()
                    require(supplied.manifestBytes.size<=1024*1024 && supplied.resources.size<=10000 && supplied.resources.values.all { it.size<=8*1024*1024 } && supplied.manifestBytes.size+supplied.resources.values.sumOf { it.size.toLong() }<=256L*1024*1024) { "runtime: package exceeds bounds" }
                    val input=ContentPackageArchiveInput(supplied.manifestBytes.copyOf(),supplied.resources.mapValues { it.value.copyOf() })
                    val manifest=ContentPackageCodec.decode(input.manifestBytes)
                    require(manifest["packageId"]!!.jsonPrimitive.content=="builtin-acupuncture" && manifest["packageVersion"]!!.jsonPrimitive.int==before.packageVersion) { "runtime: package identity mismatch" }
                    val chapter=manifest["chapters"]!!.jsonArray.map { it.jsonObject }.single { it["chapterKey"]!!.jsonPrimitive.content==key }
                    if(chapter["contentRevision"]!!.jsonPrimitive.content!=before.contentRevision || chapter["bodySha256"]!!.jsonPrimitive.content!=before.bodySha256) return finish(ContentQuizRuntimeState.SOURCE_CHANGED)
                    val adapter=ContentPackageQuizFactAdapter()
                    val draft=adapter.prepareDraft(input.manifestBytes,input.resources,key)
                    if(draft.state==ContentQuizFactState.REJECTED) return finish(ContentQuizRuntimeState.REJECTED,draft.issues)
                    workerContext.ensureActive()
                    if(professional==null || finalVerifier==null) {
                        val missing=mutableListOf<ContentPackageIssue>()
                        if(professional==null) missing+=ContentPackageIssue("review","professional review verification is not connected")
                        if(finalVerifier==null) missing+=ContentPackageIssue("externalReviews","final review verification is not connected")
                        return finish(ContentQuizRuntimeState.REVIEW_REQUIRED,missing)
                    }
                    // Discover the exact chapter keys first, then preflight all receipt sizes before any receipt copy/authority call.
                    val required=linkedMapOf<String,ContentQuizFinalReview>()
                    var total=0L
                    for(prepared in draft.preparedFacts) {
                        val record=reviews[prepared.fact.key] ?: continue
                        total+=record.recordBytes.size.toLong()
                        if(record.recordBytes.size !in 1..1024*1024 || total>16L*1024*1024) return finish(ContentQuizRuntimeState.REVIEW_REQUIRED,listOf(ContentPackageIssue("externalReviews","receipt budget exceeded")))
                        required[prepared.fact.key]=record
                    }
                    val owned=required.mapValues { (_,record) -> record.copy(recordBytes=record.recordBytes.copyOf()) }
                    val recordByKey=linkedMapOf<String,ByteArray>()
                    for(record in owned.values) {
                        val previous=recordByKey[record.recordKey]
                        if(previous!=null && !previous.contentEquals(record.recordBytes)) return finish(ContentQuizRuntimeState.REVIEW_REQUIRED,listOf(ContentPackageIssue("externalReviews","duplicate record key has conflicting bytes")))
                        recordByKey[record.recordKey]=record.recordBytes
                    }
                    val guardedProfessional=professional?.let { external -> ContentProfessionalReviewVerifier { request ->
                        fun jsonCopy(value:JsonObject)=Json.parseToJsonElement(ContentPackageCodec.canonicalJson(value)).jsonObject
                        workerContext.ensureActive()
                        val verified=external.isVerified(request.copy(subject=jsonCopy(request.subject),manifest=jsonCopy(request.manifest),review=jsonCopy(request.review),recordBytes=request.recordBytes.copyOf(),chapterBindings=frozen(request.chapterBindings.map { it.copy() })))
                        workerContext.ensureActive()
                        verified
                    } }
                    val guardedFinal=finalVerifier?.let { external -> ContentQuizFinalReviewVerifier { request ->
                        workerContext.ensureActive()
                        val verified=external.isVerified(request.copy(fact=copyFact(request.fact),record=request.record.copy(recordBytes=request.record.recordBytes.copyOf())))
                        workerContext.ensureActive()
                        verified
                    } }
                    workerContext.ensureActive()
                    val approved=adapter.preparePublication(input.manifestBytes,input.resources,key,guardedProfessional,owned,guardedFinal)
                    workerContext.ensureActive()
                    if(approved.state!=ContentQuizFactState.APPROVED) return finish(if(approved.state==ContentQuizFactState.REJECTED) ContentQuizRuntimeState.REJECTED else ContentQuizRuntimeState.REVIEW_REQUIRED,approved.issues)
                    require(approved.preparedFacts.isNotEmpty() && approved.preparedFacts.all { it.fact.approved && it.fact.sourceDocumentId==before.documentId && it.fact.sourceRevision==before.contentRevision })
                    currentCoroutineContext().ensureActive()
                    val snapshot=Snapshot(before,frozen(approved.preparedFacts.map(::copyPrepared)),recordByKey.mapValues { it.value.copyOf() },runtime.pin)
                    return finish(ContentQuizRuntimeState.APPROVED,snapshot=snapshot)
                } catch(c:CancellationException) { throw c } catch(error:Exception) {
                    val before=pinned
                    val changed=error is RuntimeSourceChanged || before!=null && try { runtime.pin(key)!=before } catch(c:CancellationException) { throw c } catch(_:Exception) { true }
                    return ContentQuizRuntimeResult(if(changed) ContentQuizRuntimeState.SOURCE_CHANGED else ContentQuizRuntimeState.REJECTED,issues=listOf(ContentPackageIssue("runtime",error.message?:"invalid runtime source")))
                }
            }
        }
    }
    companion object {
        private data class RoomBinding(val pin:ContentQuizRuntimePin,val revision:ReaderContentRevisionEntity,val sections:List<ReaderSectionEntity>)
        fun forRoom(database:AppDatabase,store:ContentPackageVersionStore,storage:DocumentStorage):ContentPackageQuizRuntime {
            suspend fun binding(key:String):RoomBinding? = database.withTransaction {
                val row=database.readerJobDao().findPackage("builtin-acupuncture") ?: return@withTransaction null
                if(row.status=="APPLIED") {
                    require(row.version==1 && row.manifestHash==ContentPackageCodec.LEGACY_MANIFEST_HASH)
                    return@withTransaction null
                }
                require(row.status.matches(Regex("SCHEMA2_APPLIED:[1-9][0-9]*")))
                val generation=requireNotNull(row.status.substringAfter(':').toLongOrNull())
                require(row.version>0 && row.manifestHash.matches(Regex("[0-9a-f]{64}")))
                val doc=database.readerCatalogDao().findDocumentIncludingDeleted("builtin:chapter:$key") ?: return@withTransaction null
                require(doc.kind=="BUILTIN" && doc.ownerUserId==null && doc.format=="HTML" && doc.importStatus=="READY" && doc.deletedAt==null && doc.accessEpoch>0 && doc.extractorVersion==ContentPackageProjection.EXTRACTOR_VERSION)
                val revision=requireNotNull(database.readerCatalogDao().findContentRevision(doc.id,doc.contentRevision))
                val sections=database.readerCatalogDao().findSections(doc.id,doc.contentRevision)
                val chapter=requireNotNull(database.chapterDao().findByContentKey(key))
                require(chapter.enabled && doc.legacyChapterId==chapter.id)
                val mappings=listOf("acupuncture-foundation","acupuncture-methods","tcm-inquiry","tcm-pulse").flatMap { database.courseBookDao().listChapters("course:$it") }.filter { it.chapterKey==key }
                require(mappings.size==1 && mappings.single().documentId==doc.id && mappings.single().legacyChapterId==chapter.id && mappings.single().sectionId==sections.single().id)
                RoomBinding(ContentQuizRuntimePin(row.manifestHash,generation,row.version,key,doc.id,doc.contentRevision,requireNotNull(revision.sourceSha256),doc.accessEpoch),revision,sections)
            }
            return ContentPackageQuizRuntime({ key ->
                val first=binding(key)
                if(first==null) null else {
                    withContext(Dispatchers.IO) { verifiedBuiltInProjection(storage,first.pin.documentId,first.revision,first.sections) }
                    if(binding(key)!=first) throw RuntimeSourceChanged()
                    first.pin
                }
            },{ version -> withContext(Dispatchers.IO) { store.readVersion(version) } })
        }
        private fun <T> frozen(values:Collection<T>):List<T> = Collections.unmodifiableList(ArrayList(values))
        private fun copyFact(fact:QuizAuditedFact)=fact.copy(answerParts=frozen(fact.answerParts),distractors=frozen(fact.distractors),evidenceHashes=frozen(fact.evidenceHashes),sourceSectionIds=Collections.unmodifiableSet(LinkedHashSet(fact.sourceSectionIds)))
        private fun copyLocator(locator:ReaderLocator):ReaderLocator = when(locator) {
            is ReaderLocator.Reflow -> locator.copy()
            is ReaderLocator.Note -> locator.copy()
            is ReaderLocator.Fixed -> locator.copy(rects=frozen(locator.rects.map { it.copy() }),pageBox=locator.pageBox.copy())
        }
        private fun copyRef(ref:QuizEvidenceRef)=ref.copy(locator=copyLocator(ref.locator))
        private fun copyPrepared(value:PreparedContentQuizFact)=value.copy(fact=copyFact(value.fact),sourceEvidence=frozen(value.sourceEvidence.map { it.copy(locator=copyLocator(it.locator)) }),evidenceRefs=frozen(value.evidenceRefs.map(::copyRef)))
    }
}
