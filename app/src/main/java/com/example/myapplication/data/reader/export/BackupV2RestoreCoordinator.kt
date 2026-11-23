package com.example.myapplication.data.reader.export

import com.example.myapplication.data.session.SessionIdentity
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Test seam uses the same receipt/CAS/commit boundary as the real Room adapter. */
interface BackupV2RestoreDatabase {
    suspend fun verifyIdentity(expectedIdentity: SessionIdentity)
    suspend fun findReceipt(userId: Long, contentHash: String, operationId: String?): BackupV2RestoreReceipt?
    suspend fun verifyPlan(plan: BackupV2RestorePlan, expectedIdentity: SessionIdentity)
    suspend fun commit(plan: BackupV2RestorePlan, publications: List<BackupV2Publication>, journalId: String,
        expectedIdentity: SessionIdentity): BackupV2RestoreReceipt
}

/** IO happens outside Room. Only a real receipt can authorize DB_COMMITTED/COMPLETED. */
class BackupV2RestoreCoordinator(private val journal: RestoreJournalV2, private val snapshotter: BackupV2RevisionSnapshot,
    private val database: BackupV2RestoreDatabase, private val clock: () -> Long = System::currentTimeMillis,
    private val checkpoint: (RestoreJournalV2State) -> Unit = {}) {
    constructor(journal: RestoreJournalV2, snapshotter: BackupV2RevisionSnapshot, store: BackupV2RoomStore) : this(journal, snapshotter,
        object : BackupV2RestoreDatabase {
            override suspend fun verifyIdentity(expectedIdentity: SessionIdentity) = store.verifyIdentity(expectedIdentity)
            override suspend fun findReceipt(userId: Long, contentHash: String, operationId: String?) = store.findRestoreReceipt(userId, contentHash, operationId)
            override suspend fun verifyPlan(plan: BackupV2RestorePlan, expectedIdentity: SessionIdentity) {
                val current = store.readTargetState(plan.backup, expectedIdentity, plan.copyNonce)
                v2require(current.stateHash == plan.targetState.stateHash, "RESTORE_PLAN_CHANGED", "target state changed since preview")
            }
            override suspend fun commit(plan: BackupV2RestorePlan, publications: List<BackupV2Publication>, journalId: String, expectedIdentity: SessionIdentity) =
                store.commit(plan, publications, journalId, expectedIdentity)
        })

    suspend fun restore(archiveBytes: ByteArray, plan: BackupV2RestorePlan, journalId: String, expectedIdentity: SessionIdentity,
        cancelled: () -> Boolean = { false }): BackupV2RestoreReceipt =
        restoreArchive(archiveBytes, plan, journalId, expectedIdentity, cancelled, preserveOnInterruption = false)

    internal suspend fun restoreConfirmedV3(archiveBytes: ByteArray, plan: BackupV2RestorePlan, journalId: String,
        expectedIdentity: SessionIdentity, cancelled: () -> Boolean): BackupV2RestoreReceipt =
        restoreArchive(archiveBytes, plan, journalId, expectedIdentity, cancelled, preserveOnInterruption = true)

    /** A separate lock from the base operation. Hold across the composite confirmation and all of its stages. */
    internal suspend fun <T> withV3JobLock(identity: SessionIdentity, block: suspend () -> T): T =
        journal.withOperationLock(identity, "confirmed-v3-restore", block)

    private suspend fun restoreArchive(archiveBytes: ByteArray, plan: BackupV2RestorePlan, journalId: String,
        expectedIdentity: SessionIdentity, cancelled: () -> Boolean, preserveOnInterruption: Boolean): BackupV2RestoreReceipt =
        journal.withOperationLock(expectedIdentity, plan.operationId) {
            requirePlanIdentity(plan, expectedIdentity)
            database.verifyIdentity(expectedIdentity)
            val prior = receipt(plan)
            if (prior != null) return@withOperationLock finishForIdentity(journal.read(journalId), prior, expectedIdentity)
            v2require(plan.existingReceipt == null, "RECEIPT_CHANGED", "preview receipt vanished")
            v2require(plan.canCommit, "RESTORE_PLAN_BLOCKED", "fixed preview has blocking reasons")
            val bytes = archiveBytes.copyOf()
            v2require(bytes.size.toLong() <= BackupV2Limits.TOTAL_BYTES, "LIMIT_EXCEEDED", "received archive byte budget")
            var record = journal.read(journalId)
            if (record == null) {
                val now = clock(); val sha = fileDigest(bytes)
                record = RestoreJournalV2Record(journalId, expectedIdentity, plan.contentHash, sha, journal.archivePath(sha).absolutePath,
                    plan.planHash, plan.idMapHash, plan.idMap, plan.counts, plan.operationId, RestoreJournalV2State.RECEIVED, 0, now, now,
                    outlineIds = plan.outlineIds, receiptLookupOperationId = if (plan.copyNonce == null) null else plan.operationId)
                journal.write(record); checkpoint(record.state)
            }
            verifyJournalPlan(record, plan, expectedIdentity, fileDigest(bytes))
            journal.writeArchive(record.packageSha256, bytes)
            run(record, bytes, plan, expectedIdentity, cancelled, preserveOnInterruption)
        }

    suspend fun resume(archiveBytes: ByteArray, plan: BackupV2RestorePlan, journalId: String, expectedIdentity: SessionIdentity,
        cancelled: () -> Boolean = { false }): BackupV2RestoreReceipt =
        restore(archiveBytes, plan, journalId, expectedIdentity, cancelled)

    /** Receipt replays have no freshly allocated map. The committing journal alone owns it. */
    internal suspend fun verifiedIdMap(receipt: BackupV2RestoreReceipt, identity: SessionIdentity): BackupV2IdMap =
        journal.withOperationLock(identity, receipt.operationId) {
            database.verifyIdentity(identity)
            val owner = identity.userId ?: throw BackupV2Exception("IDENTITY_CHANGED", "V2 receipt owner")
            val actual = database.findReceipt(owner, receipt.contentHash, receipt.operationId)
            v2require(actual?.copy(alreadyCommitted = false) == receipt.copy(alreadyCommitted = false),
                "INVALID_RECEIPT", "V2 mapping requires an actual Room receipt")
            finishExisting(null, receipt)
            val authority = journal.read(receipt.journalId)
                ?: throw BackupV2Exception("MISSING_ID_MAPPING", "V2 receipt has no committing journal")
            v2require(BackupV2IdentityPlanner.hashIdMap(authority.idMap, authority.outlineIds) == receipt.idMapHash,
                "INVALID_ID_MAP", "V2 committing journal map hash")
            database.verifyIdentity(identity)
            authority.idMap
        }

    suspend fun resume(plan: BackupV2RestorePlan, journalId: String, expectedIdentity: SessionIdentity,
        cancelled: () -> Boolean = { false }): BackupV2RestoreReceipt {
        journal.read(journalId) ?: throw BackupV2Exception("JOURNAL_MISSING", "no received operation")
        return journal.withOperationLock(expectedIdentity, plan.operationId) {
            requirePlanIdentity(plan, expectedIdentity); database.verifyIdentity(expectedIdentity)
            receipt(plan)?.let { return@withOperationLock finishForIdentity(journal.read(journalId), it, expectedIdentity) }
            v2require(plan.existingReceipt == null, "RECEIPT_CHANGED", "preview receipt vanished")
            v2require(plan.canCommit, "RESTORE_PLAN_BLOCKED", "fixed preview has blocking reasons")
            val current = journal.read(journalId) ?: throw BackupV2Exception("JOURNAL_MISSING", "received operation vanished")
            verifyJournalPlan(current, plan, expectedIdentity, current.packageSha256)
            val bytes = readFile(File(current.archivePath), BackupV2Limits.TOTAL_BYTES)
            verifyBytes(bytes, current.packageSha256, bytes.size.toLong(), "resume archive")
            run(current, bytes, plan, expectedIdentity, cancelled)
        }
    }

    suspend fun cancel(journalId: String, plan: BackupV2RestorePlan, expectedIdentity: SessionIdentity): BackupV2RestoreReceipt? =
        journal.withOperationLock(expectedIdentity, plan.operationId) {
            requirePlanIdentity(plan, expectedIdentity); database.verifyIdentity(expectedIdentity)
            receipt(plan)?.let { return@withOperationLock finishForIdentity(journal.read(journalId), it, expectedIdentity) }
            v2require(plan.existingReceipt == null, "RECEIPT_CHANGED", "preview receipt vanished")
            val record = journal.read(journalId) ?: return@withOperationLock null
            verifyJournalPlan(record, plan, expectedIdentity, record.packageSha256)
            database.verifyPlan(plan, expectedIdentity)
            v2require(record.state.ordinal < RestoreJournalV2State.DB_COMMITTED.ordinal, "RECEIPT_MISSING", "committed journal has no real receipt")
            val prepared = preparations(plan, journalId)
            val staging = prepared.associateBy { it.jobId }
            if (!record.cancelled) journal.write(record.advance(cancelled = true, now = clock()))
            record.staging.forEach { descriptor ->
                val p = staging[descriptor.jobId] ?: throw BackupV2Exception("INVALID_JOURNAL", "staging is outside fixed plan")
                verifyStaging(descriptor, p)
                if (!snapshotter.alreadyPublished(p)) snapshotter.removeOwnedStaging(descriptor.jobId, descriptor.absolutePath)
            }
            // Check the authoritative receipt again after cleanup; published resources were retained throughout.
            receipt(plan)?.let { return@withOperationLock finishForIdentity(journal.read(journalId), it, expectedIdentity) }
            null
        }

    /** Startup converges only from actual Room receipts. It never rebuilds a preview or inserts rows. */
    suspend fun recoverOnStartup(): List<BackupV2RestoreReceipt> {
        val receipts = arrayListOf<BackupV2RestoreReceipt>()
        journal.entries().forEach { entry -> journal.withOperationLock(entry.expectedIdentity, entry.receiptOperationId) {
            val current = journal.read(entry.journalId) ?: return@withOperationLock
            val receipt = database.findReceipt(current.expectedIdentity.userId!!, current.contentHash, current.receiptLookupOperationId)
            if (receipt != null) receipts += finishExisting(current, receipt)
            else v2require(current.state.ordinal < RestoreJournalV2State.DB_COMMITTED.ordinal, "RECEIPT_MISSING", "journal committed without receipt")
        } }
        return v2List(receipts)
    }

    suspend fun recoverOnStartupSafely(): BackupV2RecoveryResult {
        val scan = journal.scan(); val receipts = arrayListOf<BackupV2RestoreReceipt>(); val failures = ArrayList(scan.failures)
        scan.records.forEach { entry ->
            try {
                journal.withOperationLock(entry.expectedIdentity, entry.receiptOperationId) {
                    val current = journal.read(entry.journalId) ?: return@withOperationLock
                    val receipt = database.findReceipt(current.expectedIdentity.userId!!, current.contentHash, current.receiptLookupOperationId)
                    if (receipt != null) receipts += finishExisting(current, receipt)
                    else v2require(current.state.ordinal < RestoreJournalV2State.DB_COMMITTED.ordinal, "RECEIPT_MISSING", "journal committed without receipt")
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failures += RestoreJournalV2Failure(entry.journalId, (error as? BackupV2Exception)?.code ?: "IO_FAILURE")
            }
        }
        return BackupV2RecoveryResult(receipts, failures)
    }

    private suspend fun run(initial: RestoreJournalV2Record, bytes: ByteArray, plan: BackupV2RestorePlan,
        identity: SessionIdentity, cancelled: () -> Boolean, preserveOnInterruption: Boolean = false): BackupV2RestoreReceipt {
        var record = initial
        try {
            v2require(!record.cancelled, "CANCELLED", "operation was cancelled; create a new preview")
            v2require(record.state.ordinal < RestoreJournalV2State.DB_COMMITTED.ordinal, "RECEIPT_MISSING", "journal committed without actual receipt")
            checkCancellation(cancelled)
            val archive = BackupV2Archive.read(bytes)
            v2require(archive.backup.manifest.contentHash == plan.contentHash, "JOURNAL_PLAN_CONFLICT", "archive differs from fixed plan")
            val proofs = snapshotter.fromArchive(archive)
            v2require(proofs.map { Triple(it.exportDocumentId, it.revision, it.proofDigest) }.toSet() ==
                plan.proofs.map { Triple(it.exportDocumentId, it.revision, it.proofDigest) }.toSet(), "JOURNAL_PLAN_CONFLICT", "source proof set differs from fixed plan")
            plan.proofs.forEach(snapshotter::verifyProof)
            database.verifyPlan(plan, identity)
            if (record.state == RestoreJournalV2State.RECEIVED) {
                record = record.advance(RestoreJournalV2State.VALIDATED, now = clock()); journal.write(record); checkpoint(record.state)
            }
            val prepared = preparations(plan, record.journalId)
            val descriptors = prepared.map { p -> RestoreJournalV2Staging(p.jobId, snapshotter.stagingPath(p), p.documentId, p.revision, p.manifestSha256) }
            if (record.staging.isEmpty() && descriptors.isNotEmpty()) { record = record.advance(staging = descriptors, now = clock()); journal.write(record) }
            v2require(record.staging == descriptors, "JOURNAL_PLAN_CONFLICT", "owned staging set differs")
            if (record.state.ordinal <= RestoreJournalV2State.FILES_PREPARED.ordinal) {
                prepared.forEach { p -> checkCancellation(cancelled); database.verifyIdentity(identity); snapshotter.prepare(p) }
                if (record.state == RestoreJournalV2State.VALIDATED) {
                    record = record.advance(RestoreJournalV2State.FILES_PREPARED, now = clock()); journal.write(record); checkpoint(record.state)
                }
            }
            if (record.state == RestoreJournalV2State.FILES_PREPARED) {
                prepared.forEach { p ->
                    checkCancellation(cancelled); database.verifyIdentity(identity)
                    val publication = record.publications.singleOrNull { it.targetDocumentId == p.documentId && it.revision == p.revision }
                    if (publication == null) {
                        val published = snapshotter.publish(p)
                        record = record.advance(publications = record.publications + published, now = clock()); journal.write(record)
                    } else snapshotter.verifyPublication(publication)
                }
                record = record.advance(RestoreJournalV2State.FILES_PUBLISHED, now = clock()); journal.write(record); checkpoint(record.state)
            }
            record.publications.forEach(snapshotter::verifyPublication)
            v2require(record.publications.map { it.targetDocumentId to it.revision }.toSet() == prepared.map { it.documentId to it.revision }.toSet(),
                "JOURNAL_PLAN_CONFLICT", "published revision set differs")
            checkCancellation(cancelled); database.verifyIdentity(identity)
            receipt(plan)?.let { return finishForIdentity(record, it, identity) }
            journal.verifyCommitCapacity(record)
            val committed = database.commit(plan, record.publications, record.journalId, identity)
            // Finish is non-cancellable after Room commits, including the receipt lookup and journal durability boundary.
            return withContext(NonCancellable) {
                val authoritative = receipt(plan) ?: throw BackupV2Exception("RECEIPT_MISSING", "commit returned without persisted receipt")
                v2require(authoritative.operationId == committed.operationId && authoritative.planHash == committed.planHash,
                    "INVALID_RECEIPT", "committed result differs from Room receipt")
                finishForIdentity(record, authoritative, identity).copy(alreadyCommitted = committed.alreadyCommitted)
            }
        } catch (error: Throwable) {
            // A confirmed composite job retains its staging when the owning coroutine disappears.
            // An explicit cancel still takes the existing receipt-aware cancellation path.
            if (error is CancellationException && preserveOnInterruption && !cancelled()) throw error
            if (error is CancellationException || error is BackupV2Exception && error.code == "CANCELLED") {
                return withContext(NonCancellable) {
                    receipt(plan)?.let { return@withContext finishForIdentity(journal.read(record.journalId), it, identity) }
                    val current = journal.read(record.journalId)!!
                    if (!current.cancelled) journal.write(current.advance(cancelled = true, now = clock()))
                    // If account/target CAS has moved, retain staging until the same operation is reviewed again.
                    database.verifyPlan(plan, identity)
                    // Actual coroutine cancellation cannot reacquire its own operation lock. Clean only registered unpublished staging here.
                    val prepared = preparations(plan, record.journalId).associateBy { it.jobId }
                    current.staging.forEach { s -> val p = prepared[s.jobId] ?: throw BackupV2Exception("INVALID_JOURNAL", "unplanned staging")
                        verifyStaging(s, p); if (!snapshotter.alreadyPublished(p)) snapshotter.removeOwnedStaging(s.jobId, s.absolutePath) }
                    throw error
                }
            }
            throw error
        }
    }

    private fun preparations(plan: BackupV2RestorePlan, journalId: String): List<BackupV2PreparedRevision> =
        plan.documentDecisions.filter { it.disposition == BackupV2DocumentDisposition.PUBLISH }.flatMap { d ->
            plan.proofs.filter { it.exportDocumentId == d.sourceId }.map { proof ->
                val jobId = "m21-v2:" + v2Digest("$journalId|${plan.operationId}|${d.targetId}|${proof.revision}")
                snapshotter.bind(proof, d.targetId, plan.idMap, plan.outlineIds, jobId, plan.planHash, plan.contentHash)
            }
        }.sortedBy { "${it.documentId}|${it.revision}" }

    private fun requirePlanIdentity(plan: BackupV2RestorePlan, identity: SessionIdentity) {
        v2require(plan.expectedIdentity == identity && identity.userId != null && identity.userId > 0,
            "IDENTITY_CHANGED", "fixed preview belongs to another session")
    }
    private fun verifyJournalPlan(r: RestoreJournalV2Record, p: BackupV2RestorePlan, identity: SessionIdentity, packageSha: String) {
        v2require(r.expectedIdentity == identity && r.contentHash == p.contentHash && r.packageSha256 == packageSha && r.planHash == p.planHash &&
            r.idMapHash == p.idMapHash && idMapJson(r.idMap) == idMapJson(p.idMap) && r.outlineIds == p.outlineIds && r.counts == p.counts && r.receiptOperationId == p.operationId,
            "JOURNAL_PLAN_CONFLICT", "resume/cancel requires the exact preview/package/id map")
    }
    private fun verifyStaging(s: RestoreJournalV2Staging, p: BackupV2PreparedRevision) {
        v2require(s.documentId == p.documentId && s.revision == p.revision && s.manifestSha256 == p.manifestSha256 && s.absolutePath == snapshotter.stagingPath(p),
            "INVALID_JOURNAL", "staging descriptor differs from fixed plan")
    }
    private suspend fun receipt(plan: BackupV2RestorePlan): BackupV2RestoreReceipt? {
        val actual = database.findReceipt(plan.expectedIdentity.userId!!, plan.contentHash, if (plan.copyNonce == null) null else plan.operationId)
        if (actual != null) {
            v2require(actual.userId == plan.expectedIdentity.userId && actual.contentHash == plan.contentHash &&
                (plan.copyNonce == null || actual.operationId == plan.operationId), "INVALID_RECEIPT", "receipt lookup scope differs")
            plan.existingReceipt?.let { expected -> v2require(actual.copy(alreadyCommitted = false) == expected.copy(alreadyCommitted = false),
                "RECEIPT_CHANGED", "preview receipt changed") }
        }
        return actual
    }

    private suspend fun finishForIdentity(initial: RestoreJournalV2Record?, receipt: BackupV2RestoreReceipt, identity: SessionIdentity): BackupV2RestoreReceipt {
        database.verifyIdentity(identity)
        v2require(receipt.userId == identity.userId, "INVALID_RECEIPT", "receipt owner differs")
        val result = finishExisting(initial, receipt)
        database.verifyIdentity(identity)
        return result
    }

    private fun finishExisting(initial: RestoreJournalV2Record?, receipt: BackupV2RestoreReceipt): BackupV2RestoreReceipt {
        receiptJson(receipt)
        if (initial != null) v2require(receipt.userId == initial.expectedIdentity.userId && receipt.contentHash == initial.contentHash &&
            (initial.receiptLookupOperationId == null || receipt.operationId == initial.receiptLookupOperationId),
            "INVALID_RECEIPT", "authoritative receipt does not bind the attempt scope")
        // A failed attempt may have no resources or a different frozen preview. Only the committing journal owns receipt metadata.
        val authority = journal.read(receipt.journalId)
        val publications = if (authority == null) {
            v2require(receipt.counts.sourceDocuments == 0, "RECEIPT_RESOURCES_UNVERIFIABLE", "committing journal is unavailable")
            emptyList()
        } else {
            v2require(authority.expectedIdentity.userId == receipt.userId && authority.contentHash == receipt.contentHash &&
                authority.receiptOperationId == receipt.operationId && authority.planHash == receipt.planHash && authority.idMapHash == receipt.idMapHash &&
                authority.counts == receipt.counts, "INVALID_RECEIPT", "receipt does not bind the committing journal")
            authority.publications.forEach(snapshotter::verifyPublication)
            v2require(authority.publications.map { it.targetDocumentId }.toSet().size == receipt.counts.sourceDocuments,
                "RECEIPT_RESOURCES_UNVERIFIABLE", "committing journal has incomplete resources")
            v2require(authority.publications.map { Triple(it.targetDocumentId, it.revision, it.manifestSha256) }.toSet() ==
                authority.staging.map { Triple(it.documentId, it.revision, it.manifestSha256) }.toSet() &&
                authority.publications.all { p -> authority.idMap.documents[p.exportDocumentId] == p.targetDocumentId &&
                    p.sections.all { it.id in authority.idMap.sections.values } && p.outline.all { it.id in authority.outlineIds.values } },
                "RECEIPT_RESOURCES_UNVERIFIABLE", "committing journal lacks the complete fixed revision mapping")
            authority.publications
        }
        if (initial != null) {
            var current: RestoreJournalV2Record = initial
            val normalized = receipt.copy(alreadyCommitted = false)
            if (current.authoritativeReceipt == null) {
                current = current.advance(now = clock(), authoritativeReceipt = normalized, receiptPublications = publications)
                journal.write(current)
            } else v2require(current.authoritativeReceipt == normalized && current.receiptPublications.map(::publicationJson) == publications.map(::publicationJson),
                "INVALID_RECEIPT", "persisted receipt authority differs")
            while (current.state != RestoreJournalV2State.COMPLETED) {
                current = current.advance(RestoreJournalV2State.entries[current.state.ordinal + 1], now = clock())
                journal.write(current); checkpoint(current.state)
            }
        }
        return receipt.copy(alreadyCommitted = true)
    }
    private fun checkCancellation(cancelled: () -> Boolean) { v2require(!cancelled(), "CANCELLED", "cancel requested before commit") }
}
