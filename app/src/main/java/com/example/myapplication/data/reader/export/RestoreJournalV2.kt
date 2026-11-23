package com.example.myapplication.data.reader.export

import com.example.myapplication.data.local.entity.*
import com.example.myapplication.data.session.SessionIdentity
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

enum class RestoreJournalV2State { RECEIVED, VALIDATED, FILES_PREPARED, FILES_PUBLISHED, DB_COMMITTED, COMPLETED }
data class RestoreJournalV2Staging(val jobId: String, val absolutePath: String, val documentId: String, val revision: String, val manifestSha256: String)
data class RestoreJournalV2Failure(val journalId: String?, val code: String)
class RestoreJournalV2Scan(records: List<RestoreJournalV2Record>, failures: List<RestoreJournalV2Failure>) {
    val records = v2List(records); val failures = v2List(failures)
}
class BackupV2RecoveryResult(receipts: List<BackupV2RestoreReceipt>, failures: List<RestoreJournalV2Failure>) {
    val receipts = v2List(receipts); val failures = v2List(failures)
}

class RestoreJournalV2Record(val journalId: String, val expectedIdentity: SessionIdentity, val contentHash: String,
    val packageSha256: String, val archivePath: String, val planHash: String, val idMapHash: String, val idMap: BackupV2IdMap,
    val counts: BackupV2RestoreCounts, val receiptOperationId: String, val state: RestoreJournalV2State,
    val sequence: Long, val createdAt: Long, val updatedAt: Long, staging: List<RestoreJournalV2Staging> = emptyList(),
    publications: List<BackupV2Publication> = emptyList(), val cancelled: Boolean = false,
    outlineIds: Map<String, String> = emptyMap(), val authoritativeReceipt: BackupV2RestoreReceipt? = null,
    receiptPublications: List<BackupV2Publication> = emptyList(), val receiptLookupOperationId: String? = receiptOperationId) {
    val staging = v2List(staging); val publications = v2List(publications)
    val outlineIds = v2Map(outlineIds); val receiptPublications = v2List(receiptPublications)
    fun advance(state: RestoreJournalV2State = this.state, staging: List<RestoreJournalV2Staging> = this.staging,
        publications: List<BackupV2Publication> = this.publications, cancelled: Boolean = this.cancelled, now: Long = System.currentTimeMillis(),
        authoritativeReceipt: BackupV2RestoreReceipt? = this.authoritativeReceipt,
        receiptPublications: List<BackupV2Publication> = this.receiptPublications) =
        RestoreJournalV2Record(journalId, expectedIdentity, contentHash, packageSha256, archivePath, planHash, idMapHash, idMap, counts,
            receiptOperationId, state, Math.addExact(sequence, 1), createdAt, now, staging, publications, cancelled, outlineIds, authoritativeReceipt, receiptPublications, receiptLookupOperationId)
}

/** Private, fsynced JSON records. Atomic replacement never degrades to a copy. */
class RestoreJournalV2(privateRoot: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val root = privateRoot.absoluteFile
    init {
        ordinaryFile(root, false)
        val creationBoundary = existingDirectoryAncestor(root)
        v2require(root.mkdirs() || root.isDirectory, "IO_FAILURE", "journal root creation")
        ordinaryFile(root, false)
        syncPrivateDirectories(creationBoundary, listOf(root))
    }
    fun archivePath(packageSha256: String): File { checkHash(packageSha256); return File(File(root, "packages"), "$packageSha256.zip") }
    fun writeArchive(packageSha256: String, bytes: ByteArray): File {
        v2require(fileDigest(bytes) == packageSha256, "HASH_MISMATCH", "received archive SHA")
        val target = archivePath(packageSha256); ownedPath(root, target)
        if (target.exists()) verifyBytes(readFile(target, BackupV2Limits.TOTAL_BYTES), packageSha256, bytes.size.toLong(), "private archive")
        else atomicFileWrite(target, bytes, false)
        syncPrivateDirectories(root, listOf(target.parentFile!!))
        return target
    }
    fun read(journalId: String): RestoreJournalV2Record? {
        validateId(journalId)
        val file = recordFile(journalId); ownedPath(root, file)
        if (!file.exists()) return null
        return decode(readFile(file, BackupV2Limits.JSON_BYTES.toLong())).also {
            v2require(it.journalId == journalId, "INVALID_JOURNAL", "journal namespace differs")
            validate(it)
        }
    }
    private fun recordFiles(): List<File> {
        ordinaryFile(root, false)
        v2require(root.isDirectory, "IO_FAILURE", "journal root is unavailable")
        return (root.listFiles() ?: throw BackupV2Exception("IO_FAILURE", "journal root listing failed"))
            .filter { it.name.matches(Regex("[0-9a-f]{64}\\.json")) }.sortedBy { it.name }
    }
    fun entries(): List<RestoreJournalV2Record> = recordFiles()
        .map { file -> decode(readFile(file, BackupV2Limits.JSON_BYTES.toLong())).also { r ->
            validate(r); v2require(recordFile(r.journalId).absoluteFile == file.absoluteFile, "INVALID_JOURNAL", "record filename differs")
        } }.sortedBy { it.journalId }.let(::v2List)

    /** Corrupt attempts remain visible without preventing recovery of independent valid attempts. */
    fun scan(): RestoreJournalV2Scan {
        val records = arrayListOf<RestoreJournalV2Record>(); val failures = arrayListOf<RestoreJournalV2Failure>()
        recordFiles().forEach { file ->
            try {
                val record = decode(readFile(file, BackupV2Limits.JSON_BYTES.toLong())); validate(record)
                v2require(recordFile(record.journalId).absoluteFile == file.absoluteFile, "INVALID_JOURNAL", "record filename differs")
                records += record
            } catch (error: Exception) {
                failures += RestoreJournalV2Failure("file:${file.name.removeSuffix(".json")}", (error as? BackupV2Exception)?.code ?: "IO_FAILURE")
            }
        }
        return RestoreJournalV2Scan(records.sortedBy { it.journalId }, failures)
    }

    /** Size-only reservation; no receipt or state transition is invented or persisted. */
    fun verifyCommitCapacity(record: RestoreJournalV2Record) {
        validate(record)
        val receiptShape = buildJsonObject {
            put("userId", record.expectedIdentity.userId.toString()); put("contentHash", record.contentHash)
            put("operationId", record.receiptOperationId); put("journalId", record.journalId); put("planHash", record.planHash)
            put("idMapHash", record.idMapHash); put("counts", countJson(record.counts)); put("createdAt", Long.MAX_VALUE.toString())
            put("alreadyCommitted", false)
        }
        val finalShape = JsonObject(json(record) + mapOf("authoritativeReceipt" to receiptShape,
            "receiptPublications" to JsonArray(record.publications.map(::publicationJson)), "state" to JsonPrimitive("COMPLETED"),
            "sequence" to JsonPrimitive(Long.MAX_VALUE.toString()), "updatedAt" to JsonPrimitive(Long.MAX_VALUE.toString())))
        BackupV2Codec.checkEncodedSize(finalShape, BackupV2Limits.JSON_BYTES)
    }

    /** sequence CAS protects independent readers; transitions are persisted one state at a time. */
    fun write(record: RestoreJournalV2Record) {
        validate(record)
        val processLock = ioLocks.getOrPut(root.canonicalPath) { ReentrantLock() }
        processLock.lock()
        try {
            val lockFile = File(root, ".journal-write.lock"); ordinaryFile(lockFile, false)
            RandomAccessFile(lockFile, "rw").use { handle -> handle.channel.lock().use {
                val previous = read(record.journalId)
                if (previous == null) v2require(record.sequence == 0L && record.state == RestoreJournalV2State.RECEIVED && !record.cancelled,
                    "JOURNAL_STATE_CONFLICT", "first record must be RECEIVED sequence zero")
                else {
                    v2require(record.sequence == Math.addExact(previous.sequence, 1), "JOURNAL_STATE_CONFLICT", "stale journal sequence")
                    v2require(bindingJson(previous) == bindingJson(record), "JOURNAL_PLAN_CONFLICT", "journal frozen plan changed")
                    v2require(record.state.ordinal in previous.state.ordinal..minOf(previous.state.ordinal + 1, RestoreJournalV2State.COMPLETED.ordinal),
                        "JOURNAL_STATE_CONFLICT", "nonsequential journal state")
                    v2require(!previous.cancelled || record.cancelled || record.state.ordinal >= RestoreJournalV2State.DB_COMMITTED.ordinal,
                        "JOURNAL_STATE_CONFLICT", "cancel cannot disappear before receipt")
                    v2require(record.staging.containsAll(previous.staging) && record.publications.map(::publicationJson).containsAll(previous.publications.map(::publicationJson)),
                        "JOURNAL_STATE_CONFLICT", "owned resources cannot disappear")
                    if (previous.authoritativeReceipt != null) v2require(record.authoritativeReceipt == previous.authoritativeReceipt &&
                        record.receiptPublications.map(::publicationJson) == previous.receiptPublications.map(::publicationJson),
                        "JOURNAL_STATE_CONFLICT", "receipt authority cannot change")
                }
                val data = v2Canonical(json(record)).toByteArray(Charsets.UTF_8)
                v2require(data.size <= BackupV2Limits.JSON_BYTES, "LIMIT_EXCEEDED", "journal byte budget")
                atomicFileWrite(recordFile(record.journalId), data, true)
                syncPrivateDirectory(root, root)
            } }
        } finally { processLock.unlock() }
    }

    /** Same owner/operation serialization covers restore, resume and cancellation across instances/processes. */
    suspend fun <T> withOperationLock(identity: SessionIdentity, operationId: String, block: suspend () -> T): T {
        val user = identity.userId ?: throw BackupV2Exception("IDENTITY_CHANGED", "signed-in identity required")
        v2require(user > 0 && identity.epoch >= 0 && operationId.isNotBlank(), "INVALID_JOURNAL", "operation scope")
        val key = "${root.canonicalPath}|$user|$operationId"
        return operationLocks.getOrPut(key) { Mutex() }.withLock {
            val lockFile = File(root, ".operation-${v2Digest("$user|$operationId")}.lock"); ordinaryFile(lockFile, false)
            RandomAccessFile(lockFile, "rw").use { handle ->
                var lock: java.nio.channels.FileLock? = null
                try {
                    val deadline = System.nanoTime() + 30_000_000_000L
                    while (lock == null) {
                        lock = try { handle.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                        if (lock == null) { v2require(System.nanoTime() < deadline, "LOCK_TIMEOUT", "restore operation busy"); delay(20) }
                    }
                    block()
                } finally { lock?.release() }
            }
        }
    }

    private fun validate(record: RestoreJournalV2Record) {
        validateId(record.journalId)
        v2require(record.expectedIdentity.userId != null && record.expectedIdentity.userId > 0 && record.expectedIdentity.epoch >= 0 &&
            record.sequence >= 0 && record.createdAt >= 0 && record.updatedAt >= record.createdAt && record.receiptOperationId.isNotBlank(), "INVALID_JOURNAL", "identity/times/sequence")
        listOf(record.contentHash, record.packageSha256, record.planHash, record.idMapHash).forEach(::checkHash)
        v2require(record.receiptLookupOperationId == null || record.receiptLookupOperationId == record.receiptOperationId,
            "INVALID_JOURNAL", "receipt lookup scope")
        v2require(File(record.archivePath).absoluteFile == archivePath(record.packageSha256).absoluteFile, "INVALID_JOURNAL", "archive ownership")
        ownedPath(root, File(record.archivePath))
        countJson(record.counts)
        v2require(record.staging.size <= BackupV2Limits.FILES && record.publications.size <= BackupV2Limits.RECORDS &&
            record.staging.map { it.jobId }.toSet().size == record.staging.size && record.publications.map { it.targetDocumentId to it.revision }.toSet().size == record.publications.size,
            "INVALID_JOURNAL", "resource descriptor identity/count")
        record.staging.forEach { s -> checkHash(s.revision); checkHash(s.manifestSha256)
            v2require(s.jobId.isNotBlank() && s.documentId.isNotBlank() && File(s.absolutePath).isAbsolute, "INVALID_JOURNAL", "staging descriptor")
            ordinaryFile(File(s.absolutePath), false)
        }
        fun publications(values: List<BackupV2Publication>, planHash: String) {
            v2require(values.size <= BackupV2Limits.RECORDS && values.map { it.targetDocumentId to it.revision }.toSet().size == values.size,
                "INVALID_JOURNAL", "receipt resource identity/count")
            values.forEach { p ->
            v2require(p.planHash == planHash && p.contentHash == record.contentHash, "INVALID_JOURNAL", "publication plan binding")
            listOf(p.revision, p.proofDigest, p.sourceSha256, p.manifestSha256, p.resourceDigest).forEach(::checkHash)
            v2require(File(p.manifestPath).isAbsolute && File(p.sourceBlobPath).isAbsolute, "INVALID_JOURNAL", "publication absolute paths")
            }
        }
        publications(record.publications, record.planHash)
        record.authoritativeReceipt?.let { receipt ->
            receiptJson(receipt)
            v2require(receipt.userId == record.expectedIdentity.userId && receipt.contentHash == record.contentHash &&
                (record.receiptLookupOperationId == null || receipt.operationId == record.receiptLookupOperationId), "INVALID_RECEIPT", "receipt attempt scope")
            publications(record.receiptPublications, receipt.planHash)
            v2require(receipt.counts.sourceDocuments == record.receiptPublications.map { it.targetDocumentId }.toSet().size,
                "INVALID_JOURNAL", "receipt publications are incomplete")
        } ?: v2require(record.receiptPublications.isEmpty(), "INVALID_JOURNAL", "resources lack receipt authority")
    }
    private fun validateId(id: String) = v2require(id.isNotBlank() && id.length <= 512, "INVALID_JOURNAL", "journal ID")
    private fun recordFile(id: String) = File(root, "${v2Digest(id)}.json")
    private fun bindingJson(r: RestoreJournalV2Record) = buildJsonObject {
        put("journalId", r.journalId); put("owner", r.expectedIdentity.userId.toString()); put("epoch", r.expectedIdentity.epoch.toString())
        put("contentHash", r.contentHash); put("packageSha256", r.packageSha256); put("archivePath", r.archivePath); put("planHash", r.planHash)
        put("idMapHash", r.idMapHash); put("idMap", idMapJson(r.idMap)); put("counts", countJson(r.counts)); put("receiptOperationId", r.receiptOperationId)
        put("createdAt", r.createdAt.toString()); put("outlineIds", stringMapJson(r.outlineIds))
        put("receiptLookupOperationId", r.receiptLookupOperationId?.let(::JsonPrimitive) ?: JsonNull)
    }
    private fun json(r: RestoreJournalV2Record) = JsonObject(bindingJson(r) + mapOf(
        "schemaVersion" to JsonPrimitive(1), "state" to JsonPrimitive(r.state.name), "sequence" to JsonPrimitive(r.sequence.toString()),
        "updatedAt" to JsonPrimitive(r.updatedAt.toString()), "cancelled" to JsonPrimitive(r.cancelled),
        "staging" to JsonArray(r.staging.map { s -> buildJsonObject { put("jobId", s.jobId); put("absolutePath", s.absolutePath); put("documentId", s.documentId)
            put("revision", s.revision); put("manifestSha256", s.manifestSha256) } }), "publications" to JsonArray(r.publications.map(::publicationJson)),
        "authoritativeReceipt" to (r.authoritativeReceipt?.let(::receiptJson) ?: JsonNull),
        "receiptPublications" to JsonArray(r.receiptPublications.map(::publicationJson))))

    private fun decode(bytes: ByteArray): RestoreJournalV2Record {
        val required = setOf("schemaVersion", "journalId", "owner", "epoch", "contentHash", "packageSha256", "archivePath", "planHash", "idMapHash", "idMap", "counts", "receiptOperationId", "state", "sequence", "createdAt", "updatedAt", "staging", "publications", "cancelled")
        val r = BackupV2Json.obj(fileJson(bytes, BackupV2Limits.JSON_BYTES), required + setOf("outlineIds", "authoritativeReceipt", "receiptPublications", "receiptLookupOperationId"), required)
        v2require(BackupV2Json.integer(r["schemaVersion"]) == 1L, "UNSUPPORTED_JOURNAL_VERSION", "journal version")
        val state = runCatching { RestoreJournalV2State.valueOf(BackupV2Json.string(r["state"])) }.getOrElse { throw BackupV2Exception("INVALID_JOURNAL", "journal state") }
        return RestoreJournalV2Record(BackupV2Json.string(r["journalId"]), SessionIdentity(decimalLong(r["owner"]), decimalLong(r["epoch"])),
            BackupV2Json.string(r["contentHash"]), BackupV2Json.string(r["packageSha256"]), BackupV2Json.string(r["archivePath"]),
            BackupV2Json.string(r["planHash"]), BackupV2Json.string(r["idMapHash"]), decodeIdMap(r.getValue("idMap")), decodeCounts(r.getValue("counts")),
            BackupV2Json.string(r["receiptOperationId"]), state, decimalLong(r["sequence"]), decimalLong(r["createdAt"]), decimalLong(r["updatedAt"]),
            BackupV2Json.array(r["staging"]).map { value -> val s = BackupV2Json.obj(value, setOf("jobId", "absolutePath", "documentId", "revision", "manifestSha256"))
                RestoreJournalV2Staging(BackupV2Json.string(s["jobId"]), BackupV2Json.string(s["absolutePath"]), BackupV2Json.string(s["documentId"]), BackupV2Json.string(s["revision"]), BackupV2Json.string(s["manifestSha256"])) },
            BackupV2Json.array(r["publications"]).map(::decodePublication), BackupV2Json.bool(r["cancelled"]), r["outlineIds"]?.let(::decodeStringMap) ?: emptyMap(),
            r["authoritativeReceipt"].let { if (it == null || it === JsonNull) null else decodeReceipt(it) },
            r["receiptPublications"]?.let(BackupV2Json::array)?.map(::decodePublication) ?: emptyList(),
            if (r.containsKey("receiptLookupOperationId")) nullableText(r["receiptLookupOperationId"]) else BackupV2Json.string(r["receiptOperationId"]))
    }
    companion object {
        private val operationLocks = ConcurrentHashMap<String, Mutex>()
        private val ioLocks = ConcurrentHashMap<String, ReentrantLock>()
    }
}

internal fun countJson(c: BackupV2RestoreCounts): JsonObject {
    v2require(listOf(c.inserted, c.reused, c.skipped, c.blocked, c.documents, c.pendingDocuments, c.sourceDocuments).all { it >= 0 }, "INVALID_JOURNAL", "negative counts")
    return buildJsonObject { put("inserted", c.inserted); put("reused", c.reused); put("skipped", c.skipped); put("blocked", c.blocked)
        put("documents", c.documents); put("pendingDocuments", c.pendingDocuments); put("sourceDocuments", c.sourceDocuments) }
}
internal fun stringMapJson(values: Map<String, String>) = JsonObject(values.mapValues { JsonPrimitive(it.value) })
internal fun decodeStringMap(value: JsonElement): Map<String, String> =
    (value as? JsonObject ?: throw BackupV2Exception("INVALID_JOURNAL", "string map shape")).mapValues { BackupV2Json.string(it.value) }
internal fun receiptJson(r: BackupV2RestoreReceipt): JsonObject {
    v2require(r.userId > 0 && r.createdAt >= 0 && r.operationId.isNotBlank() && r.journalId.isNotBlank() && r.journalId.length <= 512,
        "INVALID_RECEIPT", "receipt identity/timestamp")
    listOf(r.contentHash, r.planHash, r.idMapHash).forEach(::checkHash)
    return buildJsonObject {
        put("userId", r.userId.toString()); put("contentHash", r.contentHash); put("operationId", r.operationId); put("journalId", r.journalId)
        put("planHash", r.planHash); put("idMapHash", r.idMapHash); put("counts", countJson(r.counts)); put("createdAt", r.createdAt.toString())
        put("alreadyCommitted", r.alreadyCommitted)
    }
}
internal fun decodeReceipt(value: JsonElement): BackupV2RestoreReceipt {
    val r = BackupV2Json.obj(value, setOf("userId", "contentHash", "operationId", "journalId", "planHash", "idMapHash", "counts", "createdAt", "alreadyCommitted"))
    return BackupV2RestoreReceipt(decimalLong(r["userId"]), BackupV2Json.string(r["contentHash"]), BackupV2Json.string(r["operationId"]),
        BackupV2Json.string(r["journalId"]), BackupV2Json.string(r["planHash"]), BackupV2Json.string(r["idMapHash"]),
        decodeCounts(r.getValue("counts")), decimalLong(r["createdAt"]), BackupV2Json.bool(r["alreadyCommitted"])).also(::receiptJson)
}
internal fun decodeCounts(value: JsonElement): BackupV2RestoreCounts {
    val c = BackupV2Json.obj(value, setOf("inserted", "reused", "skipped", "blocked", "documents", "pendingDocuments", "sourceDocuments"))
    val result = BackupV2RestoreCounts(int(c["inserted"]), int(c["reused"]), int(c["skipped"]), int(c["blocked"]), int(c["documents"]), int(c["pendingDocuments"]), int(c["sourceDocuments"]))
    countJson(result); return result
}
internal fun idMapJson(m: BackupV2IdMap) = buildJsonObject {
    put("tables", JsonObject(m.tables.mapKeys { it.key.wireName }.mapValues { (_, ids) -> JsonObject(ids.mapValues { JsonPrimitive(it.value) }) }))
    put("documents", JsonObject(m.documents.mapValues { JsonPrimitive(it.value) })); put("sections", JsonObject(m.sections.mapValues { JsonPrimitive(it.value) }))
    put("courses", JsonArray(m.courseChapters.entries.sortedBy { it.key.exportChapterId }.map { (r, target) -> buildJsonObject {
        put("exportChapterId", r.exportChapterId); put("chapterKey", r.chapterKey); put("bookKey", r.bookKey); put("contentRevision", r.contentRevision); put("target", target)
    } }))
}
internal fun decodeIdMap(value: JsonElement): BackupV2IdMap {
    val m = BackupV2Json.obj(value, setOf("tables", "documents", "sections", "courses"))
    fun ids(value: JsonElement): Map<String, String> = (value as? JsonObject ?: throw BackupV2Exception("INVALID_JOURNAL", "identity map shape")).mapValues { BackupV2Json.string(it.value) }
    val tables = m.getValue("tables") as? JsonObject ?: throw BackupV2Exception("INVALID_JOURNAL", "table map shape")
    val courses = BackupV2Json.array(m["courses"]).map { value ->
        val r = BackupV2Json.obj(value, setOf("exportChapterId", "chapterKey", "bookKey", "contentRevision", "target"))
        BackupV2CourseChapterRef(BackupV2Json.string(r["exportChapterId"]), BackupV2Json.string(r["chapterKey"]), BackupV2Json.string(r["bookKey"]), BackupV2Json.string(r["contentRevision"])) to BackupV2Json.string(r["target"])
    }
    v2require(courses.map { it.first }.toSet().size == courses.size, "INVALID_JOURNAL", "duplicate course mapping")
    return BackupV2IdMap(tables.map { (name, values) -> (BackupV2Table.entries.singleOrNull { it.wireName == name }
        ?: throw BackupV2Exception("INVALID_JOURNAL", "unknown table mapping")) to ids(values) }.toMap(), ids(m.getValue("documents")), ids(m.getValue("sections")), courses.toMap())
}
internal fun publicationJson(p: BackupV2Publication) = buildJsonObject {
    put("planHash", p.planHash); put("contentHash", p.contentHash); put("exportDocumentId", p.exportDocumentId); put("targetDocumentId", p.targetDocumentId)
    put("revision", p.revision); put("proofDigest", p.proofDigest); put("manifestPath", p.manifestPath); put("sourceBlobPath", p.sourceBlobPath)
    put("sourceSha256", p.sourceSha256); put("manifestSha256", p.manifestSha256); put("resourceDigest", p.resourceDigest); put("extractorVersion", p.extractorVersion); put("createdAt", p.createdAt.toString())
    put("sections", JsonArray(p.sections.map { s -> buildJsonObject { put("id", s.id); put("parentId", s.parentId?.let(::JsonPrimitive) ?: JsonNull)
        put("orderIndex", s.orderIndex); put("title", s.title); put("depth", s.depth); put("resourceHref", s.resourceHref); put("canonicalPath", s.canonicalPath)
        put("charCount", s.charCount); put("pageStart", s.pageStart?.let(::JsonPrimitive) ?: JsonNull); put("pageEnd", s.pageEnd?.let(::JsonPrimitive) ?: JsonNull) } }))
    put("outline", JsonArray(p.outline.map { n -> buildJsonObject { put("id", n.id); put("nodeKey", n.nodeKey); put("parentId", n.parentId?.let(::JsonPrimitive) ?: JsonNull)
        put("orderIndex", n.orderIndex); put("title", n.title); put("targetSectionId", n.targetSectionId?.let(::JsonPrimitive) ?: JsonNull); put("targetLocatorJson", n.targetLocatorJson?.let(::JsonPrimitive) ?: JsonNull) } }))
}
internal fun decodePublication(value: JsonElement): BackupV2Publication {
    val p = BackupV2Json.obj(value, setOf("planHash", "contentHash", "exportDocumentId", "targetDocumentId", "revision", "proofDigest", "manifestPath", "sourceBlobPath", "sourceSha256", "manifestSha256", "resourceDigest", "extractorVersion", "createdAt", "sections", "outline"))
    val document = BackupV2Json.string(p["targetDocumentId"]); val revision = BackupV2Json.string(p["revision"])
    val sections = BackupV2Json.array(p["sections"]).map { value -> val s = BackupV2Json.obj(value, setOf("id", "parentId", "orderIndex", "title", "depth", "resourceHref", "canonicalPath", "charCount", "pageStart", "pageEnd"))
        ReaderSectionEntity(BackupV2Json.string(s["id"]), document, revision, nullableText(s["parentId"]), int(s["orderIndex"]), BackupV2Json.string(s["title"]),
            int(s["depth"]), BackupV2Json.string(s["resourceHref"]), BackupV2Json.string(s["canonicalPath"]), int(s["charCount"]), nullableInt(s["pageStart"]), nullableInt(s["pageEnd"])) }
    val outline = BackupV2Json.array(p["outline"]).map { value -> val n = BackupV2Json.obj(value, setOf("id", "nodeKey", "parentId", "orderIndex", "title", "targetSectionId", "targetLocatorJson"))
        ReaderOutlineNodeEntity(BackupV2Json.string(n["id"]), document, revision, BackupV2Json.string(n["nodeKey"]), nullableText(n["parentId"]), int(n["orderIndex"]),
            BackupV2Json.string(n["title"]), nullableText(n["targetSectionId"]), nullableText(n["targetLocatorJson"])) }
    return BackupV2Publication(BackupV2Json.string(p["planHash"]), BackupV2Json.string(p["contentHash"]), BackupV2Json.string(p["exportDocumentId"]), document, revision,
        BackupV2Json.string(p["proofDigest"]), BackupV2Json.string(p["manifestPath"]), BackupV2Json.string(p["sourceBlobPath"]), BackupV2Json.string(p["sourceSha256"]),
        BackupV2Json.string(p["manifestSha256"]), BackupV2Json.string(p["extractorVersion"]), decimalLong(p["createdAt"]), sections, outline, BackupV2Json.string(p["resourceDigest"]))
}
