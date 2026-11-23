package com.example.myapplication.data.reader.export

import com.example.myapplication.data.session.SessionIdentity
import java.io.File
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Durable authorization for this exact V3 package and preview. It is private to the active account session. */
internal data class ConfirmedV3Restore(
    val jobId: String,
    val identity: SessionIdentity,
    val packageSha256: String,
    val packageBytes: Long,
    val baseContentHash: String,
    val baseOperationId: String,
    val basePlanHash: String,
    val baseIdMapHash: String,
    val reviewTargetVersion: String,
    val policy: DataManagementConflictPolicy,
    val confirmedAt: Long,
) {
    init {
        v2require(jobName.matches(jobId) && (identity.userId ?: 0) > 0 && identity.epoch >= 0 &&
            packageBytes in 1..BackupV3Archive.TOTAL_BYTES && confirmedAt >= 0 &&
            policy in setOf(DataManagementConflictPolicy.KEEP_LOCAL, DataManagementConflictPolicy.REPLACE_REVIEW_SCHEDULE) &&
            baseOperationId.matches(Regex("restore-v2:[a-f0-9]{64}")), "INVALID_JOURNAL", "V3 confirmation scope")
        listOf(packageSha256, baseContentHash, basePlanHash, baseIdMapHash, reviewTargetVersion).forEach(::checkHash)
    }

    val bindingHash get() = v2Digest(v2Canonical(binding()))
    val operationId get() = "restore-v3:$jobId"

    private fun binding() = buildJsonObject {
        put("version", 1)
        put("job", jobId)
        put("owner", identity.userId.toString())
        put("epoch", identity.epoch.toString())
        put("packageSha256", packageSha256)
        put("packageBytes", packageBytes.toString())
        put("baseContentHash", baseContentHash)
        put("baseOperationId", baseOperationId)
        put("basePlanHash", basePlanHash)
        put("baseIdMapHash", baseIdMapHash)
        put("reviewTargetVersion", reviewTargetVersion)
        put("policy", policy.name)
        put("confirmedAt", confirmedAt.toString())
    }

    fun encode() = buildJsonObject {
        put("plan", binding())
        put("bindingHash", bindingHash)
    }

    fun verifyBase(receipt: BackupV2RestoreReceipt) {
        v2require(receipt.userId == identity.userId && receipt.contentHash == baseContentHash &&
            receipt.operationId == baseOperationId && receipt.planHash == basePlanHash &&
            receipt.idMapHash == baseIdMapHash, "RESTORE_PLAN_CHANGED", "V3 base receipt differs from confirmed plan")
    }

    companion object {
        val jobName = Regex("[a-zA-Z0-9-]{1,80}")
        fun decode(value: JsonElement): ConfirmedV3Restore {
            val envelope = BackupV2Json.obj(value, setOf("plan", "bindingHash"))
            val plan = BackupV2Json.obj(envelope.getValue("plan"), setOf("version", "job", "owner", "epoch",
                "packageSha256", "packageBytes", "baseContentHash", "baseOperationId", "basePlanHash", "baseIdMapHash",
                "reviewTargetVersion", "policy", "confirmedAt"))
            v2require(BackupV2Json.integer(plan["version"]) == 1L, "UNSUPPORTED_JOURNAL_VERSION", "V3 confirmation version")
            fun field(name: String) = BackupV2Json.string(plan[name])
            val job = ConfirmedV3Restore(field("job"), SessionIdentity(decimalLong(plan["owner"]), decimalLong(plan["epoch"])),
                field("packageSha256"), decimalLong(plan["packageBytes"]), field("baseContentHash"), field("baseOperationId"),
                field("basePlanHash"), field("baseIdMapHash"), field("reviewTargetVersion"),
                DataManagementConflictPolicy.valueOf(field("policy")), decimalLong(plan["confirmedAt"]))
            v2require(job.bindingHash == BackupV2Json.string(envelope["bindingHash"]), "JOURNAL_PLAN_CONFLICT", "V3 confirmation checksum")
            return job
        }
    }
}

/** Two private files. The small confirmation is published last, after the exact package is durable. */
internal class RestoreJournalV3(private val root: File) {
    internal data class Scan(val jobs: List<ConfirmedV3Restore>, val failures: List<String>)
    private val recordName = Regex("([a-zA-Z0-9-]{1,80})\\.json")
    private val stagedName = Regex("([a-zA-Z0-9-]{1,80})\\.(?:zip|sources)")

    fun stage(job: ConfirmedV3Restore, bytes: ByteArray) {
        verifyBytes(bytes, job.packageSha256, job.packageBytes, "V3 confirmed archive")
        val directory = directory(job.identity)
        val record = record(job)
        v2require(!record.exists(), "JOURNAL_STATE_CONFLICT", "confirmed V3 job already exists")
        val scan = scan(job.identity)
        v2require(scan.jobs.size + scan.failures.size < MAX_PENDING, "LIMIT_EXCEEDED", "too many confirmed V3 restores")
        val payload = archive(job)
        atomicFileWrite(payload, bytes, false)
        try {
            atomicFileWrite(record, v2Canonical(job.encode()).toByteArray(Charsets.UTF_8), false)
        } catch (error: Exception) {
            if (!record.exists()) payload.delete()
            throw error
        }
        syncPrivateDirectory(root, directory)
    }

    fun scan(identity: SessionIdentity): Scan {
        val directory = directory(identity)
        if (!directory.exists()) return Scan(emptyList(), emptyList())
        val files = directory.listFiles() ?: throw BackupV2Exception("IO_FAILURE", "V3 journal directory not readable")
        val jobs = ArrayList<ConfirmedV3Restore>()
        val failures = ArrayList<String>()
        files.forEach { file ->
            val jobId = recordName.matchEntire(file.name)?.groupValues?.get(1) ?: return@forEach
            try {
                val job = ConfirmedV3Restore.decode(BackupV2Json.parse(readFile(file, RECORD_LIMIT.toLong()), RECORD_LIMIT))
                v2require(job.identity == identity && job.jobId == jobId, "INVALID_JOURNAL", "V3 job ownership")
                jobs.add(job)
            } catch (_: Exception) {
                failures.add(jobId)
            }
        }
        // Caller holds the per-account coordinator lock. Sidecars without a confirmation have no work to resume.
        files.forEach { file ->
            val jobId = stagedName.matchEntire(file.name)?.groupValues?.get(1) ?: return@forEach
            if (!File(directory, "$jobId.json").exists()) {
                ordinaryFile(file, true)
                if (!file.delete()) failures.add(jobId)
            }
        }
        return Scan(jobs.sortedWith(compareBy(ConfirmedV3Restore::confirmedAt, ConfirmedV3Restore::jobId)), failures)
    }

    fun readArchive(job: ConfirmedV3Restore): ByteArray =
        readFile(archive(job), BackupV3Archive.TOTAL_BYTES).also {
            verifyBytes(it, job.packageSha256, job.packageBytes, "staged V3 archive")
        }

    /** All retries use the same local document and revision bindings once publication starts. */
    fun bindPractice(job: ConfirmedV3Restore, bindingHash: String) {
        checkHash(bindingHash)
        val file = sources(job)
        val data = (job.bindingHash + "\n" + bindingHash).toByteArray(Charsets.UTF_8)
        if (file.exists()) v2require(readFile(file, data.size.toLong()).contentEquals(data),
            "RESTORE_PLAN_CHANGED", "V3 practice source bindings changed")
        else atomicFileWrite(file, data, false)
    }

    /** The Room receipt is read before calling this on a committed import. */
    fun finish(job: ConfirmedV3Restore) {
        val directory = directory(job.identity)
        val record = record(job)
        val archive = archive(job)
        val sources = sources(job)
        v2require(!record.exists() || record.delete(), "IO_FAILURE", "cannot close V3 confirmation")
        v2require(!archive.exists() || archive.delete(), "IO_FAILURE", "cannot remove staged V3 archive")
        v2require(!sources.exists() || sources.delete(), "IO_FAILURE", "cannot remove V3 source bindings")
        syncPrivateDirectory(root, directory)
    }

    private fun record(job: ConfirmedV3Restore) = File(directory(job.identity), "${job.jobId}.json").also { ownedPath(root, it) }
    private fun archive(job: ConfirmedV3Restore) = File(directory(job.identity), "${job.jobId}.zip").also { ownedPath(root, it) }
    private fun sources(job: ConfirmedV3Restore) = File(directory(job.identity), "${job.jobId}.sources").also { ownedPath(root, it) }
    private fun directory(identity: SessionIdentity): File {
        v2require((identity.userId ?: 0) > 0 && identity.epoch >= 0, "IDENTITY_CHANGED", "V3 job owner")
        return File(root, "${identity.userId}-${identity.epoch}").also { ownedPath(root, it) }
    }

    private companion object {
        const val RECORD_LIMIT = 8192
        const val MAX_PENDING = 8
    }
}
