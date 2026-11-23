package com.example.myapplication.data.reader.export

import com.example.myapplication.data.session.SessionIdentity
import java.util.Collections

enum class DataManagementVersion { V1, V2, V3 }
enum class DataManagementContent { NOTES, ANNOTATIONS, INK, SOURCES, READING_STATE, LEARNING_STATE, REVIEW, STANDALONE_REVIEW }
enum class DataManagementConflictPolicy { KEEP_LOCAL, COPY, REPLACE_REVIEW_SCHEDULE }
enum class DataManagementStep { SELECTION, EXPORT_PREVIEW, RESTORE_VALIDATING, RESTORE_PREVIEW, RUNNING, REPORT }
enum class DataManagementOperation { EXPORT, RESTORE }
enum class DataManagementResult { COMPLETED, PARTIAL, FAILED, CANCELLED }

private fun <T> frozenList(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
private fun <T> frozenSet(values: Collection<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))

/** Capability flags come only from the real service's verified round-trip support. Empty fails closed. */
class DataManagementVersionCapability(
    val version: DataManagementVersion,
    val exportSupported: Boolean = false,
    val restoreSupported: Boolean = false,
    supportedContent: Set<DataManagementContent> = emptySet(),
    val unavailableReason: String? = null,
    requiredContent: Set<DataManagementContent> = emptySet(),
) {
    val supportedContent = frozenSet(supportedContent)
    /** A real closed bundle may require related categories; partial selection never expands silently. */
    val requiredContent = frozenSet(requiredContent)
    init { require(this.supportedContent.containsAll(this.requiredContent)) }
}

class DataManagementCapabilities(versions: List<DataManagementVersionCapability> = emptyList()) {
    val versions = frozenList(versions)
    init { require(versions.map { it.version }.distinct().size == versions.size) }
    fun forVersion(version: DataManagementVersion) = versions.firstOrNull { it.version == version }
}

/** Owns snapshots, including when a caller supplies mutable sets. */
class DataManagementSelection(
    val version: DataManagementVersion = DataManagementVersion.V1,
    documentIds: Set<String> = emptySet(),
    content: Set<DataManagementContent> = setOf(DataManagementContent.NOTES, DataManagementContent.ANNOTATIONS, DataManagementContent.INK),
) {
    val documentIds = frozenSet(documentIds)
    val content = frozenSet(content)
    internal val hasExportRoots: Boolean get() = documentIds.isNotEmpty() ||
        version == DataManagementVersion.V3 && DataManagementContent.STANDALONE_REVIEW in content
    fun copy(
        version: DataManagementVersion = this.version,
        documentIds: Set<String> = this.documentIds,
        content: Set<DataManagementContent> = this.content,
    ) = DataManagementSelection(version, documentIds, content)
    override fun equals(other: Any?) = other is DataManagementSelection && version == other.version && documentIds == other.documentIds && content == other.content
    override fun hashCode() = 31 * (31 * version.hashCode() + documentIds.hashCode()) + content.hashCode()
}

data class DataManagementDocument(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val available: Boolean = true,
    val unavailableReason: String? = null,
)

/** All counts are actual service facts; no inferred success totals in the UI. */
data class DataManagementCounts(
    val documents: Int = 0,
    val notes: Int = 0,
    val annotations: Int = 0,
    val inkPages: Int = 0,
    val sources: Int = 0,
    val added: Int = 0,
    val reused: Int = 0,
    val conflicts: Int = 0,
    val damaged: Int = 0,
    val skipped: Int = 0,
    val retryable: Int = 0,
    val reviewItems: Int = 0,
    val reviewEvents: Int = 0,
    val reviewSessions: Int = 0,
    val practiceArchives: Int = 0,
    val missingReviewSources: Int = 0,
) {
    init {
        require(listOf(documents, notes, annotations, inkPages, sources, added, reused, conflicts, damaged, skipped,
            retryable, reviewItems, reviewEvents, reviewSessions, practiceArchives, missingReviewSources).all { it >= 0 })
    }
}

class DataManagementExportPreview(
    val id: String,
    val identity: SessionIdentity,
    val selection: DataManagementSelection,
    documents: List<DataManagementDocument>,
    val counts: DataManagementCounts,
    val estimatedBytes: Long,
    warnings: List<String> = emptyList(),
) {
    val documents = frozenList(documents)
    val warnings = frozenList(warnings)
    init { require(id.isNotBlank() && estimatedBytes >= 0) }
}

data class DataManagementConflict(
    val id: String,
    val documentTitle: String,
    val content: DataManagementContent,
    val detail: String,
    val count: Int,
) { init { require(count >= 0) } }

class DataManagementRestorePlan(
    val id: String,
    val identity: SessionIdentity,
    val version: DataManagementVersion,
    documents: List<DataManagementDocument>,
    val counts: DataManagementCounts,
    conflicts: List<DataManagementConflict> = emptyList(),
    supportedPolicies: Set<DataManagementConflictPolicy> = emptySet(),
    val policy: DataManagementConflictPolicy? = null,
    val canConfirm: Boolean = false,
    /** V1 uses a fixed legacy policy; leave supportedPolicies empty and describe its real behavior. */
    val conflictExplanation: String? = null,
    warnings: List<String> = emptyList(),
) {
    val documents = frozenList(documents)
    val conflicts = frozenList(conflicts)
    val supportedPolicies = frozenSet(supportedPolicies)
    val warnings = frozenList(warnings)
    init { require(id.isNotBlank()); require(policy == null || policy in supportedPolicies) }
}

data class DataManagementJob(
    val id: String,
    val identity: SessionIdentity,
    val operation: DataManagementOperation,
    val stageTitle: String,
    val completedUnits: Long = 0,
    val totalUnits: Long? = null,
    val canCancel: Boolean = false,
    /** Receipt-authoritative: cancellation must never promise rollback after commit. */
    val committed: Boolean = false,
    val finished: Boolean = false,
) {
    init { require(id.isNotBlank() && completedUnits >= 0 && (totalUnits == null || totalUnits >= completedUnits)) }
}

data class DataManagementIssue(val message: String, val retryable: Boolean = false)

class DataManagementReport(
    val jobId: String,
    val identity: SessionIdentity,
    val operation: DataManagementOperation,
    val result: DataManagementResult,
    val counts: DataManagementCounts,
    val committed: Boolean,
    val bytes: Long? = null,
    issues: List<DataManagementIssue> = emptyList(),
) {
    val issues = frozenList(issues)
    init { require(jobId.isNotBlank() && (bytes == null || bytes >= 0)) }
}

/** message is user-safe copy; URI, SQL names, codec internals and raw exceptions stay in the service. */
data class DataManagementError(val message: String, val retryable: Boolean = false)

/** Published atomically with authoritative selection; ordinary errors never acknowledge a draft. */
data class DataManagementSelectionResolution(val requestId: String, val identity: SessionIdentity, val accepted: Boolean) {
    init { require(requestId.isNotBlank()) }
}

class DataManagementState(
    val identity: SessionIdentity = SessionIdentity(null, 0),
    val loading: Boolean = true,
    val capabilities: DataManagementCapabilities = DataManagementCapabilities(),
    val selection: DataManagementSelection = DataManagementSelection(),
    documents: List<DataManagementDocument> = emptyList(),
    val step: DataManagementStep = DataManagementStep.SELECTION,
    val exportPreview: DataManagementExportPreview? = null,
    val restorePlan: DataManagementRestorePlan? = null,
    val job: DataManagementJob? = null,
    val report: DataManagementReport? = null,
    val error: DataManagementError? = null,
    val selectionResolution: DataManagementSelectionResolution? = null,
    /** Controller projection only: the service's selection remains authoritative. */
    val selectionPending: Boolean = false,
    recoveryIssues: List<DataManagementIssue> = emptyList(),
) {
    val documents = frozenList(documents)
    val recoveryIssues = frozenList(recoveryIssues)
    val signedIn: Boolean get() = (identity.userId ?: 0L) > 0L
    val busy: Boolean get() = step == DataManagementStep.RESTORE_VALIDATING || step == DataManagementStep.RUNNING || job?.finished == false
    val canEditSelection: Boolean get() = signedIn && !loading && !busy && step == DataManagementStep.SELECTION
    val canPreviewExport: Boolean get() {
        val capability = capabilities.forVersion(selection.version) ?: return false
        return canEditSelection && !selectionPending && capability.exportSupported && selection.hasExportRoots &&
            selection.content.isNotEmpty() && capability.supportedContent.containsAll(selection.content) &&
            selection.content.containsAll(capability.requiredContent) &&
            selection.documentIds.all { id -> documents.any { it.id == id && it.available } }
    }
    val canPickRestore: Boolean get() = signedIn && !loading && !busy &&
        (step == DataManagementStep.SELECTION || step == DataManagementStep.RESTORE_PREVIEW) && capabilities.versions.any { it.restoreSupported }
    val canSaveExport: Boolean get() {
        val preview = exportPreview ?: return false
        return signedIn && !loading && !busy && !selectionPending && step == DataManagementStep.EXPORT_PREVIEW &&
            preview.identity == identity && preview.selection == selection &&
            capabilities.forVersion(preview.selection.version)?.exportSupported == true &&
            capabilities.forVersion(preview.selection.version)?.supportedContent?.containsAll(preview.selection.content) == true
            && preview.selection.content.containsAll(capabilities.forVersion(preview.selection.version)?.requiredContent.orEmpty())
    }
    val canConfirmRestore: Boolean get() {
        val plan = restorePlan ?: return false
        return signedIn && !loading && !busy && step == DataManagementStep.RESTORE_PREVIEW && plan.identity == identity &&
            plan.canConfirm && capabilities.forVersion(plan.version)?.restoreSupported == true &&
            (plan.conflicts.isEmpty() || plan.supportedPolicies.isEmpty() || plan.policy in plan.supportedPolicies)
    }
    val canCancelJob: Boolean get() {
        val currentJob = job ?: return false
        return signedIn && currentJob.identity == identity && currentJob.canCancel && !currentJob.committed && !currentJob.finished
    }

    fun copy(
        identity: SessionIdentity = this.identity,
        loading: Boolean = this.loading,
        capabilities: DataManagementCapabilities = this.capabilities,
        selection: DataManagementSelection = this.selection,
        documents: List<DataManagementDocument> = this.documents,
        step: DataManagementStep = this.step,
        exportPreview: DataManagementExportPreview? = this.exportPreview,
        restorePlan: DataManagementRestorePlan? = this.restorePlan,
        job: DataManagementJob? = this.job,
        report: DataManagementReport? = this.report,
        error: DataManagementError? = this.error,
        selectionResolution: DataManagementSelectionResolution? = this.selectionResolution,
        selectionPending: Boolean = this.selectionPending,
        recoveryIssues: List<DataManagementIssue> = this.recoveryIssues,
    ) = DataManagementState(identity, loading, capabilities, selection, documents, step, exportPreview, restorePlan, job, report, error, selectionResolution, selectionPending, recoveryIssues)
}
