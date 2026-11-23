package com.example.myapplication.data.reader.export

import com.example.myapplication.data.local.entity.*
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.data.reader.content.ContentPackageCodec
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.Collections

internal fun <T> v2List(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
internal fun <T> v2Set(values: Collection<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
internal fun <K,V> v2Map(values: Map<K,V>): Map<K,V> = Collections.unmodifiableMap(LinkedHashMap(values))
internal fun v2Digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
internal fun v2Canonical(value: JsonElement): String = ContentPackageCodec.canonicalJson(value)
/** A direct object with a changed manifest cannot impersonate an existing content receipt. Original records bytes remain the archive codec's boundary. */
internal fun verifyV2ReceiptManifest(backup: BackupV2Package) {
    v2require(backup.manifest.contentHash == BackupV2Codec.contentHash(backup.manifest.toJson()),
        "HASH_MISMATCH", "receipt replay manifest contentHash is not self-consistent")
}

/** Explicit roots; dependent documents do not implicitly select their unrelated history. */
class BackupV2ExportRoots(documentIds: Set<String> = emptySet(), testIds: Set<Long> = emptySet(),
    paperIds: Set<String> = emptySet(), favoriteIds: Set<Long> = emptySet(), chapterIds: Set<Long> = emptySet(),
    privateQuestionIds: Set<Long> = emptySet()) {
    val documentIds = v2Set(documentIds); val testIds = v2Set(testIds); val paperIds = v2Set(paperIds)
    val favoriteIds = v2Set(favoriteIds); val chapterIds = v2Set(chapterIds); val privateQuestionIds = v2Set(privateQuestionIds)
    init { v2require((testIds + favoriteIds + chapterIds + privateQuestionIds).all { it > 0 }, "INVALID_ROOT", "positive Long roots required") }
}

class BackupV2RevisionCapture(val document: ReaderDocumentEntity, val revisionEntity: ReaderContentRevisionEntity,
    sections: List<ReaderSectionEntity>, outline: List<ReaderOutlineNodeEntity>) {
    val revision: String get() = revisionEntity.revision
    val sections = v2List(sections); val outline = v2List(outline)
    init { v2require(revisionEntity.documentId == document.id && sections.all { it.documentId == document.id && it.contentRevision == revision } &&
        outline.all { it.documentId == document.id && it.contentRevision == revision }, "SOURCE_METADATA_MISMATCH", "capture scope") }
}
class BackupV2RoomSnapshot(val expectedIdentity: SessionIdentity, val roots: BackupV2ExportRoots,
    val records: BackupV2Records, projectionChanges: List<BackupV2ProjectionChange>,
    selectedDocuments: Set<String>, dependencyDocuments: Set<String>, catalog: List<ReaderDocumentEntity>,
    revisions: List<BackupV2RevisionCapture>, courseChapterRefs: List<BackupV2CourseChapterRef>, val snapshotHash: String) {
    val identity get() = expectedIdentity
    val projectionChanges = v2List(projectionChanges); val selectedDocuments = v2Set(selectedDocuments)
    val dependencyDocuments = v2Set(dependencyDocuments); val catalog = v2List(catalog)
    val revisions = v2List(revisions); val courseChapterRefs = v2List(courseChapterRefs)
    val sectionIdsByDocument: Map<String, Set<String>> = v2Map(catalog.associate { d ->
        d.id to v2Set(revisions.filter { it.document.id == d.id }.flatMap { it.sections.map { s -> s.id } } +
            records.tables.values.flatten().flatMap { row ->
                val locators = BackupV2JsonProjection.locators(row).filter { it.documentId == d.id }.map { it.sectionId }
                val scope = if (row.table == BackupV2Table.QUIZ_PAPERS) BackupV2JsonProjection.scopeIdentity(row) else null
                locators + if (scope?.first == d.id) scope.second + BackupV2JsonProjection.coverageSections(row) else emptyList()
            })
    })
}

/** Implemented only by the strict file factory. Digest alone never authorizes READY. */
interface BackupV2RevisionProof {
    val exportDocumentId: String
    val revision: String
    val proofDigest: String
    val resourceDigest: String
    val capture: BackupV2RevisionCapture
}
class BackupV2Publication(val planHash: String, val contentHash: String, val exportDocumentId: String,
    val targetDocumentId: String, val revision: String, val proofDigest: String, val manifestPath: String,
    val sourceBlobPath: String, val sourceSha256: String, val manifestSha256: String,
    val extractorVersion: String, val createdAt: Long, sections: List<ReaderSectionEntity>, outline: List<ReaderOutlineNodeEntity>, val resourceDigest: String = "") {
    val sections = v2List(sections); val outline = v2List(outline)
}
fun interface BackupV2PublicationVerifier { suspend fun verify(publication: BackupV2Publication) }
fun interface BackupV2ProofVerifier { suspend fun verify(proof: BackupV2RevisionProof) }
/** Verifies local asset/storage bytes against this exact captured BUILTIN revision. */
fun interface BackupV2CourseVerifier { suspend fun verify(capture: BackupV2RevisionCapture, reference: BackupV2CourseChapterRef): String }
class BackupV2CourseProof internal constructor(val reference: BackupV2CourseChapterRef, val targetChapterId: String,
    val targetDocumentId: String, val capture: BackupV2RevisionCapture, val digest: String)

data class BackupV2LongHighWater(val maximum: Long, val sequence: Long?, val sequenceType: String = if (sequence == null) "absent" else "integer")
class BackupV2SqlRow(fields: Map<String, BackupV2Value>) {
    val fields = v2Map(fields)
    fun text(key: String) = (fields[key] as? BackupV2Value.Text)?.value
    fun number(key: String) = (fields[key] as? BackupV2Value.Integer)?.value
    internal fun json() = JsonObject(fields.mapValues { (_, value) -> when(value) {
        BackupV2Value.Null -> JsonNull
        is BackupV2Value.Text -> buildJsonObject { put("type", "text"); put("value", value.value) }
        is BackupV2Value.Integer -> buildJsonObject { put("type", "integer"); put("value", value.value.toString()) }
    } })
}
class BackupV2TargetState(val expectedIdentity: SessionIdentity, highWater: Map<BackupV2Table, BackupV2LongHighWater>,
    rows: Map<String, List<BackupV2SqlRow>>, catalog: List<ReaderDocumentEntity>, revisions: List<BackupV2RevisionCapture>,
    val stateHash: String, val receipt: BackupV2RestoreReceipt? = null,
    verifiedCourses: Map<BackupV2CourseChapterRef,BackupV2CourseProof> = emptyMap(), val databaseHash: String = stateHash) {
    val highWater = v2Map(highWater); val rows = v2Map(rows.mapValues { v2List(it.value) })
    val catalog = v2List(catalog); val revisions = v2List(revisions)
    val verifiedCourses = v2Map(verifiedCourses)
}
enum class BackupV2DocumentDisposition { PENDING, PUBLISH, REUSE, BLOCKED }
enum class BackupV2RowDisposition { INSERT, SKIP, BLOCKED }
data class BackupV2DocumentDecision(val sourceId: String, val targetId: String, val disposition: BackupV2DocumentDisposition, val detail: String = "")
data class BackupV2RowDecision(val table: BackupV2Table, val sourceKey: String, val disposition: BackupV2RowDisposition, val detail: String = "")
data class BackupV2RestoreCounts(val inserted: Int, val reused: Int, val skipped: Int, val blocked: Int,
    val documents: Int, val pendingDocuments: Int, val sourceDocuments: Int)
class BackupV2RestorePlan internal constructor(val backup: BackupV2Package, val expectedIdentity: SessionIdentity,
    val copyNonce: String?, val policy: DataManagementConflictPolicy, val idMap: BackupV2IdMap,
    documentDecisions: List<BackupV2DocumentDecision>, rowDecisions: List<BackupV2RowDecision>,
    val records: BackupV2Records, sqlRows: Map<BackupV2Table, List<BackupV2SqlRow>>,
    val counts: BackupV2RestoreCounts, val targetState: BackupV2TargetState, proofs: List<BackupV2RevisionProof>,
    outlineIds: Map<String,String>, val operationId: String, val idMapHash: String, val planHash: String, reasons: List<String>,
    val existingReceipt: BackupV2RestoreReceipt? = null) {
    val modelVersion = 1; val contentHash get() = backup.manifest.contentHash
    val documentDecisions = v2List(documentDecisions); val rowDecisions = v2List(rowDecisions)
    val sqlRows = v2Map(sqlRows.mapValues { v2List(it.value) }); val proofs = v2List(proofs); val outlineIds = v2Map(outlineIds)
    val reasons = v2List(reasons); val canCommit get() = existingReceipt != null || counts.blocked == 0 && reasons.isEmpty()
}
data class BackupV2RestoreReceipt(val userId: Long, val contentHash: String, val operationId: String,
    val journalId: String, val planHash: String, val idMapHash: String, val counts: BackupV2RestoreCounts,
    val createdAt: Long, val alreadyCommitted: Boolean = false)
