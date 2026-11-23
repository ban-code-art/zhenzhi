package com.example.myapplication.data.reader.export

import android.content.ContentResolver
import android.net.Uri
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.quiz.SourcePracticeScope
import com.example.myapplication.data.reader.BuiltInAssetVerifier
import com.example.myapplication.data.reader.verifiedBuiltInProjection
import com.example.myapplication.data.reader.storage.DocumentStorage
import com.example.myapplication.data.reader.content.ContentPackageCodec
import com.example.myapplication.data.session.SessionIdentity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import com.example.myapplication.data.reader.content.Api24UtcTimestamp
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** IO/Room boundary. The service, rather than a screen or ViewModel, owns its lifetime. */
interface BackupV2ServiceBackend {
    val capabilities: DataManagementCapabilities
    val canCancelRestore: Boolean
    suspend fun documents(identity: SessionIdentity): List<DataManagementDocument>
    suspend fun previewExport(selection: DataManagementSelection, identity: SessionIdentity): DataManagementExportPreview
    suspend fun saveExport(preview: DataManagementExportPreview, uri: String, identity: SessionIdentity,
        jobId: String, cancelled: () -> Boolean): DataManagementReport
    suspend fun prepareRestore(uri: String, identity: SessionIdentity): DataManagementRestorePlan
    suspend fun chooseConflict(plan: DataManagementRestorePlan, policy: DataManagementConflictPolicy,
        identity: SessionIdentity): DataManagementRestorePlan
    suspend fun confirmRestore(plan: DataManagementRestorePlan, identity: SessionIdentity,
        jobId: String, cancelled: () -> Boolean): DataManagementReport
    /** Receipt lookup only. */
    suspend fun reopen(jobId: String, identity: SessionIdentity): DataManagementReport? = null
    suspend fun recoverOnStartup()
    /** Only a durable V3 confirmation authorizes replay of a previous import. */
    suspend fun resumeConfirmedV3(identity: SessionIdentity): List<DataManagementReport> = emptyList()
    /** Persistent recovery problems; failed records never masquerade as completed operations. */
    val recoveryIssues: List<DataManagementIssue> get() = emptyList()
    suspend fun forget(identity: SessionIdentity)
    /** Release only this returned object. A late completion must never clear a newer preview. */
    suspend fun releaseExport(preview: DataManagementExportPreview) = Unit
    suspend fun releaseRestore(plan: DataManagementRestorePlan) = Unit
}

private class ServiceFailure(val userMessage: String) : IllegalStateException(userMessage)

/** Verifies the local course consumers' actual asset/storage inputs, outside the Room transaction. */
class AndroidBackupV2CourseVerifier(private val storage: DocumentStorage, private val readAsset: (String) -> ByteArray) : BackupV2CourseVerifier {
    override suspend fun verify(capture: BackupV2RevisionCapture, reference: BackupV2CourseChapterRef): String = withContext(Dispatchers.IO) {
        val document = capture.document; val revision = capture.revisionEntity
        require(document.ownerUserId == null && document.kind == "BUILTIN" && document.importStatus == "READY" &&
            document.deletedAt == null && document.format == "HTML" && document.legacyChapterId != null)
        require(document.contentRevision == reference.contentRevision && revision.revision == reference.contentRevision &&
            revision.status == "READY" && revision.documentId == document.id && document.extractorVersion == revision.extractorVersion)
        require(document.id == "builtin:chapter:${reference.chapterKey}")
        val contentDigest = if (revision.manifestPath.startsWith("asset:")) {
            require(reference.bookKey == "acupuncture-foundation")
            val asset = BuiltInAssetVerifier.verify(document.id, readAsset)
            require(asset.revision == revision.revision && asset.extractor == revision.extractorVersion &&
                revision.manifestPath == "asset:content/manifest.json" && revision.sourceBlobPath == asset.href &&
                revision.sourceSha256 == null && document.blobPath == asset.href && document.fileSha256 == null)
            val manifest = Json.parseToJsonElement(readAsset("content/manifest.json").toString(Charsets.UTF_8)).jsonObject
            val chapter = manifest.getValue("chapters").jsonArray.map { it.jsonObject }.single {
                it.getValue("key").jsonPrimitive.content == reference.chapterKey
            }
            val section = capture.sections.single()
            val title = chapter.getValue("title").jsonPrimitive.content
            val text = asset.html.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
            require(document.title == title && section.id == "${document.id}:${revision.revision}:section-1" &&
                section.documentId == document.id && section.contentRevision == revision.revision && section.parentId == null &&
                section.orderIndex == chapter.getValue("order").jsonPrimitive.int && section.depth == 0 && section.title == title &&
                section.resourceHref == asset.href && section.canonicalPath == asset.href && section.charCount == text.length &&
                section.pageStart == null && section.pageEnd == null)
            val outline = capture.outline.single()
            require(outline.id == "${document.id}:${revision.revision}:toc-1" && outline.nodeKey == "toc-1" &&
                outline.parentId == null && outline.orderIndex == 0 && outline.title == title &&
                outline.targetSectionId == section.id && outline.targetLocatorJson == null)
            v2Digest(asset.revision + "\n" + asset.html)
        } else {
            val published = verifiedBuiltInProjection(storage, document.id, revision, capture.sections)
            require(document.fileSha256 == revision.sourceSha256 && document.blobPath != null && revision.sourceBlobPath != null &&
                File(document.blobPath).canonicalFile == File(revision.sourceBlobPath).canonicalFile)
            val expected = published.manifest.outline.map {
                com.example.myapplication.data.local.entity.ReaderOutlineNodeEntity("${document.id}:${revision.revision}:${it.nodeKey}",
                    document.id, revision.revision, it.nodeKey, orderIndex = it.orderIndex, title = it.title, targetSectionId = it.targetSectionId)
            }
            require(capture.outline == expected)
            published.manifestSha256
        }
        // Actual immutable content and the exact local metadata both contribute to the CAS proof.
        v2Digest(contentDigest + "\n" + reference.bookKey + "\n" + reference.chapterKey + "\n" +
            document.toString() + "\n" + revision.toString() + "\n" + capture.sections.toString() + "\n" + capture.outline.toString())
    }
}

/** Explicit oracle gate. A source implementation alone never advertises a verified round trip. */
class BackupV2VerifiedSupport(content: Set<DataManagementContent> = emptySet(),
    val unavailableReason: String = "V2 的真实 Room、固定来源和中断恢复往返尚未完成验证。") {
    val content: Set<DataManagementContent> = content.toSet()
    init {
        require(DataManagementContent.INK !in content) { "V2 ink file adapter is not implemented" }
        require(content.isEmpty() || content.containsAll(BUNDLE)) { "V2 verified support must include the actual closed bundle" }
    }
    companion object {
        val BUNDLE = setOf(DataManagementContent.NOTES, DataManagementContent.ANNOTATIONS,
            DataManagementContent.READING_STATE, DataManagementContent.LEARNING_STATE)
    }
}

/** Production SAF, V1 and V2 adapter. All package content is private, bounded and identity pinned. */
class RoomBackupV2ServiceBackend(
    private val database: AppDatabase,
    private val resolver: ContentResolver,
    private val identityProvider: suspend () -> SessionIdentity,
    private val writer: BackupPackageWriter,
    private val archive: BackupArchive,
    private val legacyRestore: RestoreCoordinator,
    private val exportsRoot: File,
    private val privateRoot: File,
    private val room: BackupV2RoomStore,
    private val revisions: BackupV2RevisionSnapshot,
    private val packageWriter: BackupV2PackageWriter,
    private val restore: BackupV2RestoreCoordinator,
    private val appVersion: String,
    verifiedSupport: BackupV2VerifiedSupport = BackupV2VerifiedSupport(),
    private val v3: BackupV3RoomSupport? = null,
) : BackupV2ServiceBackend {
    private val v3Content = if (v3 != null && verifiedSupport.content.isNotEmpty())
        verifiedSupport.content + setOf(DataManagementContent.REVIEW, DataManagementContent.STANDALONE_REVIEW)
        else emptySet()
    override val capabilities = DataManagementCapabilities(listOf(
        DataManagementVersionCapability(DataManagementVersion.V1, true, true,
            setOf(DataManagementContent.NOTES, DataManagementContent.ANNOTATIONS, DataManagementContent.INK),
            "V1 不包含知识回顾。"),
        DataManagementVersionCapability(DataManagementVersion.V2, verifiedSupport.content.isNotEmpty(), verifiedSupport.content.isNotEmpty(),
            verifiedSupport.content,
            if (verifiedSupport.content.isEmpty()) verifiedSupport.unavailableReason
            else "V2 需同时选择笔记、标注、阅读状态和学习状态。" +
                if (DataManagementContent.SOURCES !in verifiedSupport.content) "固定来源恢复尚未完成验证。V2 不包含知识回顾。"
                else "来源可单独选择。V2 不包含知识回顾。墨迹请使用 V1。",
            requiredContent = if (verifiedSupport.content.isEmpty()) emptySet() else BackupV2VerifiedSupport.BUNDLE),
        DataManagementVersionCapability(DataManagementVersion.V3, v3Content.isNotEmpty(), v3Content.isNotEmpty(), v3Content,
            if (v3Content.isEmpty()) "V3 的知识回顾恢复尚未完成验证。" else "V3 包含所选文档的知识回顾；可单独勾选独立回顾卡。",
            requiredContent = if (v3Content.isEmpty()) emptySet() else BackupV2VerifiedSupport.BUNDLE + DataManagementContent.REVIEW)))
    override val canCancelRestore = true
    private data class ExportHandle(val identity: SessionIdentity, val preview: DataManagementExportPreview,
        val bytes: ByteArray, val snapshot: BackupV2RoomSnapshot? = null, val review: BackupV3RoomSupport.Capture? = null)
    private data class RestoreV3(val snapshot: BackupV3Archive.Snapshot, val practice: Map<String, SourcePracticeScope>,
        val targetVersion: String, val counts: BackupV3RoomSupport.ReviewCounts, val idMap: BackupV2IdMap)
    private data class RestoreHandle(val identity: SessionIdentity, val ui: DataManagementRestorePlan, val bytes: ByteArray,
        val v1Directory: File? = null, val v1Hash: String? = null, val v2: BackupV2RestorePlan? = null,
        val v3: RestoreV3? = null, val generation: Long)
    private val exports = ConcurrentHashMap<String, ExportHandle>()
    private val restores = ConcurrentHashMap<String, RestoreHandle>()
    private val reports = ConcurrentHashMap<String, DataManagementReport>()
    private val v3Journal = RestoreJournalV3(File(privateRoot, "v3-pending"))
    private val handleLock = Any()
    private val restoreGenerations = HashMap<SessionIdentity, Long>()
    @Volatile override var recoveryIssues: List<DataManagementIssue> = emptyList()
        private set
    private val planner = BackupV2IdentityPlanner()
    private suspend fun check(identity: SessionIdentity) {
        check((identity.userId ?: 0L) > 0L && identityProvider() == identity) { "SESSION_CHANGED" }
    }
    override suspend fun documents(identity: SessionIdentity): List<DataManagementDocument> = withContext(Dispatchers.IO) {
        check(identity)
        val result = database.readerCatalogDao().observeVisibleDocuments(checkNotNull(identity.userId)).first().map {
            DataManagementDocument(it.id, it.title, if (it.kind == "BUILTIN") "内置课程" else "我的书籍",
                available = it.importStatus == "READY", unavailableReason = "文档尚未就绪".takeIf { _ -> it.importStatus != "READY" })
        }
        check(identity)
        result
    }

    override suspend fun previewExport(selection: DataManagementSelection, identity: SessionIdentity): DataManagementExportPreview {
        var pendingHandle: ExportHandle? = null
        try {
            return withContext(Dispatchers.IO) {
                check(identity)
                require(selection.hasExportRoots)
                val cap = capabilities.forVersion(selection.version)
                require(cap?.exportSupported == true && cap.supportedContent.containsAll(selection.content))
                require(selection.content.containsAll(cap.requiredContent)) { "INCOMPLETE_CONTENT_BUNDLE" }
                val handle = if (selection.version == DataManagementVersion.V1) {
                    val request = ExportRequest(checkNotNull(identity.userId), selection.documentIds.toList(),
                        includeNotes = DataManagementContent.NOTES in selection.content,
                        includeAnnotations = DataManagementContent.ANNOTATIONS in selection.content,
                        includeInk = DataManagementContent.INK in selection.content)
                    val output = writer.export(request) as? ExportOutcome.Completed ?: error("EXPORT_REJECTED")
                    try {
                        require(output.rejectedDocuments.isEmpty()) { "SELECTED_DOCUMENT_UNAVAILABLE" }
                        val packet = writer.packageZip(output.preview.exportId, archive) as? BackupArchive.Outcome.Ok ?: error("ZIP_FAILED")
                        val bytes = packet.file.inputStream().use { boundedRead(it) }
                        val p = output.preview
                        val preview = DataManagementExportPreview(UUID.randomUUID().toString(), identity, selection,
                            p.documents.map { DataManagementDocument(it.documentId, it.title) },
                            DataManagementCounts(p.documentCount, p.noteFileCount, p.annotationCount, p.inkPageCount), bytes.size.toLong(), p.warnings)
                        ExportHandle(identity, preview, bytes)
                    } finally { writer.cleanup(output.preview.exportId) }
                } else {
                    val support = v3.takeIf { selection.version == DataManagementVersion.V3 }
                    val review = support?.capture(identity, selection.documentIds, DataManagementContent.STANDALONE_REVIEW in selection.content)
                    val roots = BackupV2ExportRoots(documentIds = selection.documentIds,
                        chapterIds = if (DataManagementContent.LEARNING_STATE in selection.content) selection.documentIds.mapNotNull {
                            database.readerCatalogDao().findDocument(it)?.takeIf { d -> d.kind == "BUILTIN" }?.legacyChapterId
                        }.toSet() else emptySet(), privateQuestionIds = review?.privateQuestions.orEmpty())
                    val snapshot = room.snapshot(roots, identity)
                    // The selection explicitly names the whole closed bundle. Never add an unselected content class.
                    val records = snapshot.records
                    if (records[BackupV2Table.READER_ANNOTATIONS].any { it.text("ink_blob_path") != null })
                        throw ServiceFailure("所选文档包含墨迹，V2 暂不支持；请使用 V1 导出墨迹。")
                    val base = BackupV2Package(BackupV2Manifest(UUID.randomUUID().toString(), BackupV2Service.exportCreatedAtUtc(System.currentTimeMillis()), appVersion, false,
                        "0".repeat(64), BackupV2Options(DataManagementContent.SOURCES in selection.content), snapshot.catalog.map { d ->
                            BackupV2Document(d.id, if (d.kind == "BUILTIN") "BUILTIN_REFERENCE" else "IMPORTED", d.title, d.format,
                                snapshot.revisions.firstOrNull { it.document.id == d.id && it.revision == d.contentRevision }?.revisionEntity?.sourceSha256 ?: d.fileSha256,
                                d.contentRevision, snapshot.courseChapterRefs.firstOrNull { it.exportChapterId == d.legacyChapterId?.toString() }?.chapterKey,
                                null, emptyList(), snapshot.sectionIdsByDocument[d.id].orEmpty().toList())
                        }, listOf(BackupV2File("records.json", "0".repeat(64), 0, "RECORDS")), snapshot.courseChapterRefs), records)
                    val proofs = if (DataManagementContent.SOURCES in selection.content)
                        revisions.captureAll(snapshot.revisions) else emptyList()
                    val packet = packageWriter.encode(base, proofs)
                    room.verifySnapshot(snapshot)
                    val bytes = if (review == null) packet.bytes else BackupV3Archive.write(
                        packet.bytes, review.state, review.practice, PortableEncyclopediaState(review.encyclopediaFavorites),
                    )
                    val counts = if (review == null) counts(packet.backup) else counts(packet.backup).withReview(
                        BackupV3RoomSupport.ReviewCounts(review.state.items.size, review.state.events.size, review.state.sessions.size,
                            review.practice.size, review.state.items.count { it.source.kind == "missing" }))
                    val preview = DataManagementExportPreview(UUID.randomUUID().toString(), identity, selection,
                        packet.backup.manifest.documents.map { DataManagementDocument(it.exportDocumentId, it.title) },
                        counts, bytes.size.toLong(),
                        if (packet.projectionChanges.isEmpty()) emptyList() else listOf("备份已移除运行权限和其他不可恢复的私有字段。"))
                    ExportHandle(identity, preview, bytes, snapshot, review)
                }
                pendingHandle = handle
                check(identity)
                exports[handle.preview.id] = handle
                handle.preview
            }
        } catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { pendingHandle?.let { exports.remove(it.preview.id, it) } }
            throw error
        }
    }

    override suspend fun saveExport(preview: DataManagementExportPreview, uri: String, identity: SessionIdentity,
        jobId: String, cancelled: () -> Boolean): DataManagementReport = withContext(Dispatchers.IO) {
        check(identity)
        val handle = exports[preview.id] ?: error("PREVIEW_CONSUMED")
        require(handle.identity == identity && handle.preview === preview)
        require(exports.remove(preview.id, handle)) { "PREVIEW_CONSUMED" }
        handle.snapshot?.let { room.verifySnapshot(it) }
        handle.review?.let { capture ->
            checkNotNull(v3).exportStillCurrent(capture, identity, preview.selection.documentIds,
                DataManagementContent.STANDALONE_REVIEW in preview.selection.content)
        }
        if (cancelled()) throw CancellationException()
        val destination = contentUri(uri)
        var written = 0L
        try {
            resolver.openOutputStream(destination, "wt").use { output ->
                checkNotNull(output) { "SAF_STREAM_UNAVAILABLE" }
                var cursor = 0
                while (cursor < handle.bytes.size) {
                    check(identity)
                    if (cancelled()) throw CancellationException()
                    val count = minOf(64 * 1024, handle.bytes.size - cursor)
                    output.write(handle.bytes, cursor, count); cursor += count; written += count
                }
                output.flush()
            }
        } catch (error: Exception) {
            return@withContext DataManagementReport(jobId, identity, DataManagementOperation.EXPORT,
                if (error is CancellationException) DataManagementResult.CANCELLED else DataManagementResult.FAILED,
                DataManagementCounts(), false, written, issues = listOf(DataManagementIssue(
                    if (written > 0) "备份未完整保存，已生成的文件可能不完整；请重新选择目标并保存。" else BackupV2Service.safeMessage(error), true)))
        }
        // Complete close is the export boundary. Later cancellation cannot undo a saved document.
        DataManagementReport(jobId, identity, DataManagementOperation.EXPORT, DataManagementResult.COMPLETED,
            preview.counts, true, handle.bytes.size.toLong()).also { reports[jobId] = it }
    }

    override suspend fun prepareRestore(uri: String, identity: SessionIdentity): DataManagementRestorePlan {
        var pendingDirectory: File? = null
        var pendingHandle: RestoreHandle? = null
        try {
            return withContext(Dispatchers.IO) {
                check(identity)
                val (generation, obsolete) = synchronized(handleLock) {
                    val next = (restoreGenerations[identity] ?: 0L) + 1L
                    restoreGenerations[identity] = next
                    next to removeRestoreHandles(identity)
                }
                obsolete.forEach { it.v1Directory?.deleteRecursively() }
                val bytes = resolver.openInputStream(contentUri(uri)).use { boundedRead(checkNotNull(it)) }
                check(identity)
                val version = detectVersion(bytes)
                val handle = if (version >= 2) {
                    val capability = capabilities.forVersion(if (version == 3) DataManagementVersion.V3 else DataManagementVersion.V2)
                    if (capability?.restoreSupported != true)
                        throw ServiceFailure(capability?.unavailableReason ?: "暂时不能恢复此备份。")
                    val composite = if (version == 3) BackupV3Archive.read(bytes) else null
                    val snapshot = composite?.base ?: BackupV2Archive.read(bytes)
                    BackupV2Codec.requireImportAllowed(snapshot.backup, BackupV2TestOnlyPolicy.FORBID)
                    if (snapshot.backup.manifest.options.includeSources && DataManagementContent.SOURCES !in capability.supportedContent)
                        throw ServiceFailure("固定来源恢复尚未完成验证，暂时不能恢复此备份。")
                    val practice = composite?.let { checkNotNull(v3).validate(it) }
                    val proofs = revisions.fromArchive(snapshot)
                    val target = room.readTargetState(snapshot.backup, identity)
                    val plan = planner.preview(snapshot.backup, target, proofs, identity, DataManagementConflictPolicy.KEEP_LOCAL)
                    if (composite == null) RestoreHandle(identity, uiPlan(plan), bytes, v2 = plan, generation = generation)
                    else {
                        val support = checkNotNull(v3)
                        val archives = checkNotNull(practice)
                        val ids = plan.existingReceipt?.let { restore.verifiedIdMap(it, identity) } ?: plan.idMap
                        val review = RestoreV3(composite, archives, support.targetVersion(identity),
                            support.estimate(composite, plan, ids, archives), ids)
                        RestoreHandle(identity, uiV3Plan(plan, review, DataManagementConflictPolicy.KEEP_LOCAL),
                            bytes, v2 = plan, v3 = review, generation = generation)
                    }
                } else {
                    require(capabilities.forVersion(DataManagementVersion.V1)?.restoreSupported == true)
                    val dir = child(exportsRoot, "service-restore-${UUID.randomUUID()}")
                    pendingDirectory = dir
                    val zip = child(exportsRoot, "service-input-${UUID.randomUUID()}.zip")
                    try {
                        FileOutputStream(zip).use { it.write(bytes); it.fd.sync() }
                        require(archive.extract(zip, dir) is BackupArchive.Outcome.Ok) { "INVALID_V1_ARCHIVE" }
                        val outcome = legacyRestore.preview(dir, checkNotNull(identity.userId), identity) as? RestoreOutcome.Previewed ?: error("V1_RESTORE_REJECTED")
                        val p = outcome.preview
                        val manifest = BackupPackageVerifier.loadManifest(dir).manifest ?: error("V1_MANIFEST_MISSING")
                        val ui = DataManagementRestorePlan(UUID.randomUUID().toString(), identity, DataManagementVersion.V1,
                            manifest.documents.map { DataManagementDocument(it.exportDocumentId, it.title) },
                            DataManagementCounts(p.documentCount, p.noteCount, p.annotationCount, p.inkFileCount,
                                conflicts = p.predictedConflicts, damaged = p.invalidAnnotations), canConfirm = true,
                            conflictExplanation = "V1 复用同来源文档；冲突笔记保留恢复副本，保留本机阅读进度。",
                            warnings = p.warnings + "V1 备份不包含知识回顾。")
                        RestoreHandle(identity, ui, bytes, dir, p.manifestHash, generation = generation)
                    } catch (error: Exception) { dir.deleteRecursively(); throw error }
                    finally { zip.delete() }
                }
                pendingHandle = handle
                check(identity)
                synchronized(handleLock) {
                    require(restoreGenerations[identity] == generation) { "PREVIEW_SUPERSEDED" }
                    restores[handle.ui.id] = handle
                }
                handle.ui
            }
        } catch (error: Exception) {
            // Includes cancellation while returning from withContext after registration and the final identity check.
            withContext(NonCancellable + Dispatchers.IO) {
                pendingHandle?.let { handle -> synchronized(handleLock) { restores.remove(handle.ui.id, handle) } }
                pendingDirectory?.deleteRecursively()
            }
            throw error
        }
    }

    override suspend fun chooseConflict(plan: DataManagementRestorePlan, policy: DataManagementConflictPolicy,
        identity: SessionIdentity): DataManagementRestorePlan {
        var pendingHandle: RestoreHandle? = null
        try {
            return withContext(Dispatchers.IO) {
                check(identity)
                require(plan.version != DataManagementVersion.V1 && policy in plan.supportedPolicies)
                val old = takeRestore(plan, identity)
                val fixed = checkNotNull(old.v2)
                // Every explicit COPY starts a distinct operation, including a retry after files were published but Room did not commit.
                val handle = if (old.v3 == null) {
                    val nonce = if (policy == DataManagementConflictPolicy.COPY) UUID.randomUUID().toString() else fixed.copyNonce
                    val replacement = planner.preview(fixed.backup, room.readTargetState(fixed.backup, identity, nonce),
                        if (fixed.existingReceipt == null) fixed.proofs else revisions.fromArchive(BackupV2Archive.read(old.bytes)), identity, policy, nonce)
                    old.copy(ui = uiPlan(replacement), v2 = replacement)
                } else old.copy(ui = uiV3Plan(fixed, old.v3, policy))
                val ui = handle.ui
                pendingHandle = handle
                check(identity)
                synchronized(handleLock) {
                    require(restoreGenerations[identity] == old.generation) { "PREVIEW_SUPERSEDED" }
                    restores[ui.id] = handle
                }
                ui
            }
        } catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.IO) {
                pendingHandle?.let { handle -> synchronized(handleLock) { restores.remove(handle.ui.id, handle) } }
            }
            throw error
        }
    }

    override suspend fun confirmRestore(plan: DataManagementRestorePlan, identity: SessionIdentity,
        jobId: String, cancelled: () -> Boolean): DataManagementReport = withContext(Dispatchers.IO) {
        check(identity)
        val handle = takeRestore(plan, identity)
        val v2 = handle.v2
        val composite = handle.v3
        try {
            require(handle.identity == identity && handle.ui === plan)
            if (composite != null) {
                val fixed = checkNotNull(v2)
                val support = checkNotNull(v3)
                restore.withV3JobLock(identity) {
                    check(identity)
                    if (cancelled()) throw CancellationException()
                    v2require(fixed.canCommit, "RESTORE_PLAN_BLOCKED", "V3 confirmation requires a viable base plan")
                    v2require(support.targetVersion(identity) == composite.targetVersion, "RESTORE_PLAN_CHANGED", "review changed after preview")
                    val confirmed = ConfirmedV3Restore(jobId, identity, composite.snapshot.packageSha256, handle.bytes.size.toLong(),
                        fixed.contentHash, fixed.operationId, fixed.planHash, fixed.idMapHash, composite.targetVersion,
                        checkNotNull(plan.policy), System.currentTimeMillis())
                    // Reference only supports old UI receipt lookups. The journal is committed before any import side effect.
                    writeReference(jobId, identity, handle)
                    v3Journal.stage(confirmed, handle.bytes)
                    runConfirmedV3(confirmed, cancelled, composite, fixed)
                }
            } else if (v2 != null) {
                writeReference(jobId, identity, handle)
                val receipt = restore.restore(handle.bytes, v2, jobId, identity, cancelled)
                receiptReport(jobId, identity, receipt)
            } else {
                writeReference(jobId, identity, handle)
                if (cancelled()) throw CancellationException()
                val hash = BackupPackageVerifier.sha256Of(File(checkNotNull(handle.v1Directory), BackupSchema.MANIFEST_FILE))
                require(hash == handle.v1Hash) { "PACKAGE_CHANGED" }
                val outcome = legacyRestore.applyGuarded(handle.v1Directory, checkNotNull(identity.userId), identity, cancelled)
                val receipt = legacyRestore.findServiceReceipt(checkNotNull(identity.userId), hash)
                require(receipt != null) { "V1_NOT_COMMITTED:$outcome" }
                DataManagementReport(jobId, identity, DataManagementOperation.RESTORE,
                    if (receipt.second) DataManagementResult.COMPLETED else DataManagementResult.PARTIAL, receipt.first, true)
            }
        } catch (error: Exception) {
            // Query committed authority even when cancellation/IO races the return from Room.
            withContext(NonCancellable) {
                val receiptReport = if (v2 != null) room.findRestoreReceipt(checkNotNull(identity.userId), v2.contentHash, v2.operationId)
                    ?.let { if (composite == null) receiptReport(jobId, identity, it) else {
                        readConfirmedV3Result(jobId, identity) ?: partialV3(jobId, identity, it,
                            when {
                                cancelled() -> "已取消后续恢复。已提交的基础数据已保留。"
                                (error as? BackupV2Exception)?.code in setOf("RESTORE_PLAN_CHANGED", "PLAN_STALE") ->
                                    "基础数据已恢复。本机数据已变化，请重新选择备份并确认新的预览。"
                                else -> "基础数据已恢复。下次打开应用时将续接未完成的知识回顾与随堂档案。"
                            })
                    } }
                else legacyRestore.findServiceReceipt(checkNotNull(identity.userId), checkNotNull(handle.v1Hash))?.let {
                    DataManagementReport(jobId, identity, DataManagementOperation.RESTORE,
                        if (it.second) DataManagementResult.COMPLETED else DataManagementResult.PARTIAL, it.first, true)
                }
                receiptReport ?: DataManagementReport(jobId, identity, DataManagementOperation.RESTORE,
                    if (error is CancellationException || cancelled()) DataManagementResult.CANCELLED else DataManagementResult.FAILED,
                    DataManagementCounts(), false, issues = listOf(DataManagementIssue(BackupV2Service.safeMessage(error), error !is CancellationException)))
            }
        } finally { handle.v1Directory?.deleteRecursively() }
    }.also { reports[jobId] = it }

    private suspend fun runConfirmedV3(job: ConfirmedV3Restore, cancelled: () -> Boolean = { false },
        original: RestoreV3? = null, basePlan: BackupV2RestorePlan? = null): DataManagementReport {
        val support = checkNotNull(v3)
        val identity = job.identity
        val owner = checkNotNull(identity.userId)
        try {
            check(identity)
            val existing = room.findRestoreReceipt(owner, job.baseContentHash, job.baseOperationId)?.also(job::verifyBase)
            support.restoreResult(identity, job.jobId)?.let {
                v2require(it.job == job, "JOURNAL_PLAN_CONFLICT", "V3 result confirmation changed")
                val report = v3Report(it, checkNotNull(existing))
                v3Journal.finish(job)
                return report
            }
            if (cancelled()) throw CancellationException()
            v2require(support.targetVersion(identity) == job.reviewTargetVersion, "RESTORE_PLAN_CHANGED", "review changed since V3 confirmation")
            val snapshot = original?.snapshot ?: BackupV3Archive.read(v3Journal.readArchive(job))
            v2require(snapshot.packageSha256 == job.packageSha256 && snapshot.base.backup.manifest.contentHash == job.baseContentHash,
                "JOURNAL_PLAN_CONFLICT", "V3 staged archive differs from confirmation")
            val practice = original?.practice ?: support.validate(snapshot)
            val receipt = existing ?: support.withCurrentIdentity(identity) {
                val fixed = basePlan ?: planner.preview(snapshot.base.backup, room.readTargetState(snapshot.base.backup, identity),
                    revisions.fromArchive(snapshot.base), identity, DataManagementConflictPolicy.KEEP_LOCAL)
                v2require(fixed.canCommit && fixed.expectedIdentity == identity && fixed.operationId == job.baseOperationId &&
                    fixed.planHash == job.basePlanHash && fixed.idMapHash == job.baseIdMapHash,
                    "RESTORE_PLAN_CHANGED", "V3 base no longer matches the confirmed preview")
                // If no receipt exists, V2 resumes its original source-publication journal using the same jobId.
                restore.restoreConfirmedV3(snapshot.baseBytes(), fixed, job.jobId, identity, cancelled).also(job::verifyBase)
            }
            if (cancelled()) throw CancellationException()
            val ids = restore.verifiedIdMap(receipt, identity)
            if (original != null) v2require(idMapJson(ids) == idMapJson(original.idMap), "RESTORE_PLAN_CHANGED", "V3 map changed")
            val practicePlan = support.practicePlan(identity, practice, ids)
            v3Journal.bindPractice(job, practicePlan.bindingHash)
            val archives = support.importPractice(identity, snapshot, ids, practicePlan)
            if (cancelled()) throw CancellationException()
            val review = support.importReview(job, snapshot.review, ids, practice, archives, practicePlan, snapshot.encyclopedia)
            val report = v3Report(review, receipt)
            v3Journal.finish(job)
            return report
        } catch (error: Exception) {
            // Cancel discards V3 continuation, even if its base was already committed. A stale frozen plan
            // requires a new confirmation. Ordinary IO or process interruption retains the exact package.
            if (cancelled() || (error as? BackupV2Exception)?.code in setOf("RESTORE_PLAN_CHANGED", "PLAN_STALE", "CANCELLED")) {
                withContext(NonCancellable) { v3Journal.finish(job) }
            }
            throw error
        }
    }

    override suspend fun resumeConfirmedV3(identity: SessionIdentity): List<DataManagementReport> = withContext(Dispatchers.IO) {
        if (v3 == null || (identity.userId ?: 0L) <= 0L) return@withContext emptyList()
        restore.withV3JobLock(identity) {
            check(identity)
            val scan = v3Journal.scan(identity)
            if (scan.failures.isNotEmpty()) recoveryIssues += DataManagementIssue("有 ${scan.failures.size} 项 V3 恢复记录无法核验，请重新选择备份。")
            scan.jobs.map { job ->
                val report = try {
                    val recovered = runConfirmedV3(job)
                    DataManagementReport(job.jobId, identity, recovered.operation, recovered.result, recovered.counts,
                        recovered.committed, recovered.bytes, listOf(DataManagementIssue("已继续上次确认的 V3 恢复。")) + recovered.issues)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    check(identity)
                    val base = room.findRestoreReceipt(checkNotNull(identity.userId), job.baseContentHash, job.baseOperationId)
                    val stale = (error as? BackupV2Exception)?.code in setOf("RESTORE_PLAN_CHANGED", "PLAN_STALE")
                    val issue = if (stale) "本机数据已变化。请重新选择备份并确认新的恢复预览。"
                        else "V3 恢复尚未完成。原确认与备份已保留，下次打开应用时将再次续接。"
                    DataManagementReport(job.jobId, identity, DataManagementOperation.RESTORE,
                        if (base == null) DataManagementResult.FAILED else DataManagementResult.PARTIAL,
                        base?.let(::receiptCounts) ?: DataManagementCounts(), base != null,
                        issues = listOf(DataManagementIssue(issue, true)))
                }
                reports[job.jobId] = report
                report
            }
        }
    }

    override suspend fun reopen(jobId: String, identity: SessionIdentity): DataManagementReport? = withContext(Dispatchers.IO) {
        check(identity)
        reports[jobId]?.takeIf { it.identity == identity } ?: readConfirmedV3Result(jobId, identity) ?: readReference(jobId, identity)
    }
    override suspend fun recoverOnStartup() = withContext(Dispatchers.IO) {
        val result = restore.recoverOnStartupSafely() // Legacy V2 remains receipt-only.
        recoveryIssues = result.failures.map { DataManagementIssue("上次恢复记录未能核验，需检查该操作；已保留记录，未重新恢复数据。") }
        Unit
    }
    override suspend fun forget(identity: SessionIdentity) = withContext(Dispatchers.IO) {
        exports.entries.removeAll { it.value.identity == identity }
        val obsolete = synchronized(handleLock) {
            restoreGenerations[identity] = (restoreGenerations[identity] ?: 0L) + 1L
            removeRestoreHandles(identity)
        }
        obsolete.forEach { it.v1Directory?.deleteRecursively() }
    }
    private fun removeRestoreHandles(identity: SessionIdentity): List<RestoreHandle> = restores.entries
        .filter { it.value.identity == identity }.mapNotNull { (id, handle) -> handle.takeIf { restores.remove(id, handle) } }
    private fun takeRestore(plan: DataManagementRestorePlan, identity: SessionIdentity): RestoreHandle = synchronized(handleLock) {
        val handle = restores[plan.id] ?: error("PLAN_CONSUMED")
        require(handle.identity == identity && handle.ui === plan) { "PLAN_CONSUMED" }
        require(restores.remove(plan.id, handle)) { "PLAN_CONSUMED" }
        handle
    }
    override suspend fun releaseExport(preview: DataManagementExportPreview) = withContext(NonCancellable + Dispatchers.IO) {
        exports[preview.id]?.takeIf { it.preview === preview && it.identity == preview.identity }?.let { exports.remove(preview.id, it) }
        Unit
    }
    override suspend fun releaseRestore(plan: DataManagementRestorePlan) = withContext(NonCancellable + Dispatchers.IO) {
        val removed = synchronized(handleLock) {
            restores[plan.id]?.takeIf { it.ui === plan && it.identity == plan.identity }?.takeIf { restores.remove(plan.id, it) }
        }
        removed?.v1Directory?.deleteRecursively()
        Unit
    }
    private fun uiPlan(plan: BackupV2RestorePlan) = DataManagementRestorePlan(plan.planHash, plan.expectedIdentity, DataManagementVersion.V2,
        plan.backup.manifest.documents.map { DataManagementDocument(it.exportDocumentId, it.title) },
        if (plan.existingReceipt != null) receiptCounts(plan.existingReceipt) else counts(plan.backup).copy(added = plan.counts.inserted, reused = plan.counts.reused,
            skipped = plan.counts.skipped, conflicts = plan.documentDecisions.count { it.detail.isNotBlank() }),
        supportedPolicies = setOf(DataManagementConflictPolicy.KEEP_LOCAL, DataManagementConflictPolicy.COPY), policy = plan.policy,
        canConfirm = plan.canCommit, conflictExplanation = if (plan.existingReceipt != null)
            "此备份已恢复。再次确认将显示首次提交结果；选择创建副本可发起新的恢复。"
            else "保留本机数据，或按本次预览创建副本；同包提交复用首次回执。",
        warnings = plan.reasons + plan.documentDecisions.mapNotNull { it.detail.takeIf(String::isNotBlank) } + "V2 备份不包含知识回顾。")
    private fun uiV3Plan(base: BackupV2RestorePlan, review: RestoreV3, policy: DataManagementConflictPolicy): DataManagementRestorePlan {
        val ui = uiPlan(base)
        val planId = v2Digest("3\n${review.snapshot.packageSha256}\n${base.planHash}\n${base.expectedIdentity}\n${review.targetVersion}\n${policy.name}")
        return DataManagementRestorePlan(planId, ui.identity, DataManagementVersion.V3, ui.documents, ui.counts.withReview(review.counts),
            supportedPolicies = setOf(DataManagementConflictPolicy.KEEP_LOCAL, DataManagementConflictPolicy.REPLACE_REVIEW_SCHEDULE),
            policy = policy, canConfirm = ui.canConfirm,
            conflictExplanation = "基础数据保留本机已有内容。同源回顾卡默认保留本机调度，也可只替换回顾调度。",
            warnings = base.reasons + base.documentDecisions.mapNotNull { it.detail.takeIf(String::isNotBlank) })
    }
    private fun DataManagementCounts.withReview(review: BackupV3RoomSupport.ReviewCounts) =
        copy(reviewItems = review.items, reviewEvents = review.events, reviewSessions = review.sessions,
            practiceArchives = review.archives, missingReviewSources = review.missingSources)
    private fun counts(backup: BackupV2Package) = DataManagementCounts(documents = backup.manifest.documents.size,
        notes = backup.records[BackupV2Table.READER_NOTEBOOKS].size, annotations = backup.records[BackupV2Table.READER_ANNOTATIONS].size,
        sources = backup.manifest.documents.count { it.sourcePath != null })
    private fun receiptReport(jobId: String, identity: SessionIdentity, receipt: BackupV2RestoreReceipt) =
        DataManagementReport(jobId, identity, DataManagementOperation.RESTORE, DataManagementResult.COMPLETED,
            receiptCounts(receipt), true)
    private fun receiptCounts(receipt: BackupV2RestoreReceipt) = DataManagementCounts(documents = receipt.counts.documents,
        sources = receipt.counts.sourceDocuments, added = receipt.counts.inserted, reused = receipt.counts.reused, skipped = receipt.counts.skipped)
    private fun contentUri(raw: String): Uri = Uri.parse(raw).also { require(it.scheme == "content" && !it.authority.isNullOrBlank() && raw.length <= 8192) }
    private fun boundedRead(input: java.io.InputStream, limit: Long = BackupV2Limits.TOTAL_BYTES): ByteArray {
        val output = ByteArrayOutputStream(); val buffer = ByteArray(64 * 1024); var total = 0L
        while (true) { val read = input.read(buffer); if (read < 0) break; if (read == 0) continue
            total += read; require(total <= limit) { "PACKAGE_TOO_LARGE" }; output.write(buffer, 0, read) }
        return output.toByteArray()
    }
    private fun detectVersion(bytes: ByteArray): Int = java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
        var entries = 0; var expanded = 0L; val buffer = ByteArray(64 * 1024)
        while (true) {
            val entry = zip.nextEntry ?: error("MANIFEST_MISSING")
            require(++entries <= BackupV2Limits.FILES)
            val manifest = if (entry.name == "manifest.json") ByteArrayOutputStream() else null
            var entryBytes = 0L
            while (true) {
                val read = zip.read(buffer); if (read < 0) break; if (read == 0) continue
                expanded += read; entryBytes += read
                require(expanded <= BackupV2Limits.TOTAL_BYTES && entryBytes <= if (manifest == null) BackupV2Limits.FILE_BYTES.toLong() else BackupV2Limits.MANIFEST_BYTES.toLong())
                manifest?.write(buffer, 0, read)
            }
            zip.closeEntry()
            if (manifest != null) {
                val json = BackupV2Json.parse(manifest.toByteArray(), BackupV2Limits.MANIFEST_BYTES).jsonObject
                val version = (json["schemaVersion"] as? JsonPrimitive)?.content?.toIntOrNull()
                require(version == 1 || version == 2 || version == 3) { "UNSUPPORTED_VERSION" }
                return@use version!!
            }
        }
        @Suppress("UNREACHABLE_CODE") error("MANIFEST_MISSING")
    }
    private fun child(root: File, name: String): File {
        val canonical = root.canonicalFile
        require(canonical.exists() && canonical.isDirectory || canonical.mkdirs())
        val target = File(canonical, name)
        require(target.canonicalFile.parentFile == canonical && target.absoluteFile == target.canonicalFile)
        return target
    }
    private fun writeReference(jobId: String, identity: SessionIdentity, handle: RestoreHandle) {
        require(jobId.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        val target = child(privateRoot, "$jobId.json")
        val temporary = child(privateRoot, ".$jobId-${UUID.randomUUID()}.tmp")
        val data = buildJsonObject { put("version", 1); put("jobId", jobId); put("userId", identity.userId.toString()); put("epoch", identity.epoch.toString())
            put("kind", if (handle.v3 != null) "V3" else if (handle.v2 != null) "V2" else "V1")
            put("contentHash", handle.v2?.contentHash ?: handle.v1Hash)
            put("operationId", handle.v2?.operationId) }.toString().toByteArray()
        try { FileOutputStream(temporary).use { it.write(data); it.fd.sync() }; require(!target.exists() && temporary.renameTo(target)) }
        finally { temporary.delete() }
    }
    private suspend fun readReference(jobId: String, identity: SessionIdentity): DataManagementReport? {
        if (!jobId.matches(Regex("[a-zA-Z0-9-]{1,80}"))) return null
        val file = child(privateRoot, "$jobId.json")
        if (!file.isFile || file.length() !in 1..8192) return null
        val reference = ServiceJobReferenceCodec.read(file.inputStream().use { boundedRead(it, 8192) }, jobId, identity) ?: return null
        return if (reference.kind in setOf("V2", "V3"))
            room.findRestoreReceipt(checkNotNull(identity.userId), reference.contentHash, reference.operationId)?.let { receipt ->
                if (reference.kind == "V2") receiptReport(jobId, identity, receipt)
                else readV3Result(jobId, identity, receipt) ?: partialV3(jobId, identity, receipt)
            }
        else legacyRestore.findServiceReceipt(checkNotNull(identity.userId), reference.contentHash)?.let {
            DataManagementReport(jobId, identity, DataManagementOperation.RESTORE,
                if (it.second) DataManagementResult.COMPLETED else DataManagementResult.PARTIAL, it.first, true)
        }
    }

    private fun partialV3(jobId: String, identity: SessionIdentity, receipt: BackupV2RestoreReceipt) =
        partialV3(jobId, identity, receipt, "基础数据已恢复。知识回顾与随堂档案可能尚未全部恢复，可重新打开同一备份继续。")

    private fun partialV3(jobId: String, identity: SessionIdentity, receipt: BackupV2RestoreReceipt, message: String) =
        DataManagementReport(jobId, identity, DataManagementOperation.RESTORE, DataManagementResult.PARTIAL,
            receiptCounts(receipt), true, issues = listOf(DataManagementIssue(message, true)))

    private suspend fun readConfirmedV3Result(jobId: String, identity: SessionIdentity): DataManagementReport? {
        if (!ConfirmedV3Restore.jobName.matches(jobId)) return null
        val result = v3?.restoreResult(identity, jobId) ?: return null
        val job = result.job
        val receipt = room.findRestoreReceipt(checkNotNull(identity.userId), job.baseContentHash, job.baseOperationId)
            ?: throw BackupV2Exception("RECEIPT_MISSING", "V3 Room receipt has no base receipt")
        return v3Report(result, receipt)
    }

    private fun v3Report(result: BackupV3RoomSupport.RestoreResult, receipt: BackupV2RestoreReceipt): DataManagementReport {
        val job = result.job
        job.verifyBase(receipt)
        val issues = buildList {
            if (result.unavailableArchives > 0) add(DataManagementIssue("有 ${result.unavailableArchives} 份随堂记录缺少可用原文。"))
            if (result.counts.missingSources > 0) add(DataManagementIssue("有 ${result.counts.missingSources} 张卡片只能查看已保存的内容。"))
        }
        return DataManagementReport(job.jobId, job.identity, DataManagementOperation.RESTORE,
            if (result.unavailableArchives == 0) DataManagementResult.COMPLETED else DataManagementResult.PARTIAL,
            receiptCounts(receipt).withReview(result.counts), true, issues = issues)
    }

    // Read service result files produced by the first V3 release. New V3 results are authoritative in Room.
    private fun readV3Result(jobId: String, identity: SessionIdentity, receipt: BackupV2RestoreReceipt): DataManagementReport? = runCatching {
        val file = child(privateRoot, "$jobId.review.json")
        require(file.isFile && file.length() in 1..8192)
        val payload = BackupV2Json.obj(BackupV2Json.parse(file.inputStream().use { boundedRead(it, 8192) }, 8192),
            setOf("version", "job", "owner", "epoch", "result", "items", "sessions", "events", "archives", "missing", "issues"))
        require(BackupV2Json.integer(payload["version"]) == 1L && BackupV2Json.string(payload["job"]) == jobId &&
            BackupV2Json.string(payload["owner"]) == identity.userId.toString() &&
            BackupV2Json.string(payload["epoch"]) == identity.epoch.toString())
        val result = DataManagementResult.valueOf(BackupV2Json.string(payload["result"]))
        require(result in setOf(DataManagementResult.COMPLETED, DataManagementResult.PARTIAL))
        fun count(key: String) = BackupV2Json.integer(payload[key]).also { require(it in 0L..BackupV2Limits.RECORDS.toLong()) }.toInt()
        val review = BackupV3RoomSupport.ReviewCounts(count("items"), count("events"), count("sessions"), count("archives"), count("missing"))
        DataManagementReport(jobId, identity, DataManagementOperation.RESTORE, result, receiptCounts(receipt).withReview(review),
            true, issues = BackupV2Json.strings(payload["issues"]).map { DataManagementIssue(it) })
    }.getOrNull()
}

internal data class ServiceJobReference(val kind: String, val contentHash: String, val operationId: String?)

/** Private receipt references are strict bindings, never an instruction to replay an archive. */
internal object ServiceJobReferenceCodec {
    fun read(bytes: ByteArray, jobId: String, identity: SessionIdentity): ServiceJobReference? = try {
        val json = BackupV2Json.obj(BackupV2Json.parse(bytes, 8192),
            setOf("version", "jobId", "userId", "epoch", "kind", "contentHash", "operationId"))
        require(BackupV2Json.integer(json["version"]) == 1L && BackupV2Json.string(json["jobId"]) == jobId)
        require((identity.userId ?: 0L) > 0L && BackupV2Json.string(json["userId"]) == identity.userId.toString() &&
            BackupV2Json.string(json["epoch"]) == identity.epoch.toString())
        val kind = BackupV2Json.string(json["kind"]); require(kind in setOf("V1", "V2", "V3"))
        val hash = BackupV2Json.string(json["contentHash"]); require(hash.matches(Regex("[0-9a-f]{64}")))
        val operation = if (json["operationId"] == JsonNull) null else BackupV2Json.string(json["operationId"])
        require(if (kind == "V1") operation == null else operation?.matches(Regex("restore-v2:[0-9a-f]{64}")) == true)
        ServiceJobReference(kind, hash, operation)
    } catch (_: Exception) { null }
}

class BackupV2Service(
    identities: Flow<SessionIdentity>,
    private val identityProvider: suspend () -> SessionIdentity,
    private val backend: BackupV2ServiceBackend,
    private val scope: CoroutineScope,
) : DataManagementPort {
    private val mutableState = MutableStateFlow(DataManagementState(capabilities = backend.capabilities))
    override val state: StateFlow<DataManagementState> = mutableState.asStateFlow()
    private val commands = Channel<suspend () -> Unit>(256)
    private class Active(val id: String, val identity: SessionIdentity) { val cancelled = AtomicBoolean(false) }
    @Volatile private var active: Active? = null
    private val previewGeneration = AtomicLong(0)
    private val publicationLock = Any()
    private val restoreUris = linkedSetOf<String>()

    init {
        scope.launch { for (command in commands) command() }
        scope.launch {
            var observedIdentity: SessionIdentity? = null
            var initialization: Job? = null
            identities.collect { identity ->
                if (observedIdentity != identity) {
                    observedIdentity = identity
                    // Revoke immediately, including while an earlier IO command is suspended.
                    val previous = synchronized(publicationLock) {
                        val previous = mutableState.value.identity
                        active?.cancelled?.set(true); active = null
                        previewGeneration.incrementAndGet()
                        mutableState.value = DataManagementState(identity = identity, capabilities = backend.capabilities)
                        previous
                    }
                    // Revocation owns cleanup even if the next identity initialization is cancelled or skipped.
                    // This task is independent of the latest-only UI initialization and matches the complete identity.
                    if (previous != identity) scope.launch {
                        try { withContext(NonCancellable) { backend.forget(previous) } }
                        catch (_: Exception) {
                            publish(identity) { it.copy(error = DataManagementError("旧会话资源未能完整释放，请重新打开应用后重试。", true)) }
                        }
                    }
                    initialization?.cancel()
                    initialization = scope.launch { complete(identity) {
                        restoreUris.clear() // Actor-owned; never races the collector or URI commands.
                        var recoveryIssues = emptyList<DataManagementIssue>()
                        var recovered: DataManagementReport? = null
                        try {
                            backend.recoverOnStartup()
                            if (current(identity) && (identity.userId ?: 0L) > 0L) {
                                recovered = backend.resumeConfirmedV3(identity).lastOrNull { it.identity == identity }
                            }
                            recoveryIssues = backend.recoveryIssues
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            recoveryIssues = listOf(DataManagementIssue("上次恢复记录未能核验，请检查恢复结果。"))
                        }
                        try {
                            val documents = if ((identity.userId ?: 0L) > 0L) backend.documents(identity) else emptyList()
                            if (current(identity)) publish(identity) { state ->
                                val report = recovered
                                val initial = state.copy(loading = false, documents = documents, recoveryIssues = recoveryIssues)
                                if (report == null) initial else initial.copy(step = DataManagementStep.REPORT, report = report,
                                    job = DataManagementJob(report.jobId, identity, report.operation, "自动续接已结束",
                                        committed = report.committed, finished = true))
                            }
                        } catch (error: Exception) {
                            publish(identity) { it.copy(recoveryIssues = recoveryIssues) }
                            failure(identity, error, loading = false)
                        }
                    } }
                }
            }
        }
    }

    private fun enqueue(identity: SessionIdentity, requestId: String? = null, block: suspend () -> Unit) {
        if (commands.trySend {
                try { if (current(identity)) block() }
                catch (error: Exception) { failure(identity, error) }
            }.isFailure) {
            publish(identity) { it.copy(error = DataManagementError("操作过多，请稍后重试。", true),
                selectionResolution = requestId?.let { DataManagementSelectionResolution(it, identity, false) }) }
        }
    }
    /** Workers backpressure on the bounded actor queue; a completed IO job can never be dropped as a user overflow. */
    private suspend fun complete(identity: SessionIdentity, discarded: (suspend () -> Unit)? = null, block: suspend () -> Unit) {
        try {
            commands.send {
                try { if (current(identity)) block() else discarded?.invoke() }
                catch (error: Exception) { failure(identity, error) }
            }
        } catch (error: Exception) {
            withContext(NonCancellable) { discarded?.invoke() }
            throw error
        }
    }
    private suspend fun current(identity: SessionIdentity): Boolean = identityProvider() == identity && mutableState.value.identity == identity
    /** Publication and identity revocation share one boundary; suspended checks cannot restore an old state. */
    private fun publish(identity: SessionIdentity, transform: (DataManagementState) -> DataManagementState): Boolean = synchronized(publicationLock) {
        val before = mutableState.value
        if (before.identity != identity) false else { mutableState.value = transform(before); true }
    }
    private fun usable() = mutableState.value.signedIn && !mutableState.value.loading && !mutableState.value.busy

    override fun refreshDocuments(expectedIdentity: SessionIdentity) = enqueue(expectedIdentity) {
        if (!usable()) return@enqueue
        val documents = backend.documents(expectedIdentity)
        if (current(expectedIdentity)) publish(expectedIdentity) { it.copy(documents = documents) }
    }

    override fun changeSelection(selection: DataManagementSelection, expectedIdentity: SessionIdentity, requestId: String) {
        if (requestId.isBlank()) return
        enqueue(expectedIdentity, requestId) {
            val before = mutableState.value
            val capability = backend.capabilities.forVersion(selection.version)
            val valid = usable() && selection.documentIds.size <= ExportRequest.MAX_DOCUMENTS && capability != null &&
                selection.documentIds.all { id -> before.documents.any { it.id == id && it.available } } &&
                (capability.exportSupported && capability.supportedContent.containsAll(selection.content) ||
                    !capability.exportSupported && selection.documentIds.isEmpty())
            if (valid) {
                previewGeneration.incrementAndGet()
                backend.forget(expectedIdentity)
                restoreUris.clear() // Explicit accepted selection starts a new task, even if unchanged.
            }
            if (current(expectedIdentity)) publish(expectedIdentity) { before.copy(
                selection = if (valid) selection else before.selection,
                step = if (valid) DataManagementStep.SELECTION else before.step,
                exportPreview = if (valid) null else before.exportPreview,
                restorePlan = if (valid) null else before.restorePlan,
                report = if (valid) null else before.report, job = if (valid) null else before.job,
                error = if (valid) null else DataManagementError("所选内容暂时不可用，请重新选择。"),
                selectionResolution = DataManagementSelectionResolution(requestId, expectedIdentity, valid)) }
        }
    }

    override fun previewExport(expectedIdentity: SessionIdentity) = enqueue(expectedIdentity) {
        val before = mutableState.value
        if (!before.canPreviewExport) return@enqueue
        val generation = previewGeneration.incrementAndGet()
        if (!publish(expectedIdentity) { before.copy(loading = true, error = null, selectionResolution = null) }) return@enqueue
        scope.launch {
            try {
                val preview = backend.previewExport(before.selection, expectedIdentity)
                if (!current(expectedIdentity)) { backend.releaseExport(preview); return@launch }
                complete(expectedIdentity, discarded = { backend.releaseExport(preview) }) {
                    if (previewGeneration.get() == generation && preview.identity == expectedIdentity && preview.selection == before.selection)
                        publish(expectedIdentity) { it.copy(loading = false, step = DataManagementStep.EXPORT_PREVIEW,
                            exportPreview = preview, restorePlan = null, error = null) }
                    else backend.releaseExport(preview)
                }
            } catch (error: Exception) { complete(expectedIdentity) { if (previewGeneration.get() == generation) failure(expectedIdentity, error, false) } }
        }
    }

    override fun saveExport(previewId: String, destinationUri: String, expectedIdentity: SessionIdentity) = enqueue(expectedIdentity) {
        val before = mutableState.value
        val preview = before.exportPreview
        if (!before.canSaveExport || preview?.id != previewId || !validUri(destinationUri)) return@enqueue
        // Consume the preview before entering IO. A second URI cannot reuse it.
        start(expectedIdentity, DataManagementOperation.EXPORT, true) { running ->
            backend.saveExport(checkNotNull(preview), destinationUri, expectedIdentity, running.id, running.cancelled::get)
        }
    }

    override fun prepareRestore(sourceUri: String, expectedIdentity: SessionIdentity) = enqueue(expectedIdentity) {
        if (!mutableState.value.canPickRestore || !validUri(sourceUri)) return@enqueue
        if (sourceUri in restoreUris) {
            publish(expectedIdentity) { it.copy(error = DataManagementError("此文件选择已使用，请重新选择备份文件。")) }
            return@enqueue
        }
        restoreUris += sourceUri
        val generation = previewGeneration.incrementAndGet()
        mutableState.value.restorePlan?.let { backend.releaseRestore(it) }
        if (!current(expectedIdentity)) return@enqueue
        if (!publish(expectedIdentity) { it.copy(step = DataManagementStep.RESTORE_VALIDATING,
            exportPreview = null, restorePlan = null, error = null, selectionResolution = null) }) return@enqueue
        scope.launch {
            try {
                val plan = backend.prepareRestore(sourceUri, expectedIdentity)
                if (!current(expectedIdentity)) { backend.releaseRestore(plan); return@launch }
                complete(expectedIdentity, discarded = { backend.releaseRestore(plan) }) {
                    if (previewGeneration.get() == generation && plan.identity == expectedIdentity)
                        publish(expectedIdentity) { it.copy(step = DataManagementStep.RESTORE_PREVIEW, restorePlan = plan) }
                    else backend.releaseRestore(plan)
                }
            } catch (error: Exception) { complete(expectedIdentity) {
                if (previewGeneration.get() == generation) {
                    restoreUris.clear() // Validation ended; an explicit fresh picker may choose the same URI.
                    publish(expectedIdentity) { it.copy(step = DataManagementStep.SELECTION, restorePlan = null) }
                    failure(expectedIdentity, error)
                }
            } }
        }
    }

    override fun chooseConflict(policy: DataManagementConflictPolicy, expectedIdentity: SessionIdentity) = enqueue(expectedIdentity) {
        val before = mutableState.value
        val plan = before.restorePlan ?: return@enqueue
        if (!usable() || before.step != DataManagementStep.RESTORE_PREVIEW || plan.identity != expectedIdentity || policy !in plan.supportedPolicies) return@enqueue
        val generation = previewGeneration.incrementAndGet()
        if (!publish(expectedIdentity) { before.copy(loading = true, error = null) }) return@enqueue
        scope.launch {
            try {
                val replacement = backend.chooseConflict(plan, policy, expectedIdentity)
                if (!current(expectedIdentity)) { backend.releaseRestore(replacement); return@launch }
                complete(expectedIdentity, discarded = { backend.releaseRestore(replacement) }) {
                    if (previewGeneration.get() == generation && replacement.identity == expectedIdentity)
                        publish(expectedIdentity) { it.copy(loading = false, restorePlan = replacement) }
                    else backend.releaseRestore(replacement)
                }
            } catch (error: Exception) { complete(expectedIdentity) {
                if (previewGeneration.get() == generation) {
                    backend.releaseRestore(plan)
                    restoreUris.clear()
                    publish(expectedIdentity) { it.copy(step = DataManagementStep.SELECTION, restorePlan = null) }
                    failure(expectedIdentity, error, false)
                }
            } }
        }
    }

    override fun confirmRestore(planId: String, expectedIdentity: SessionIdentity) = enqueue(expectedIdentity) {
        val before = mutableState.value
        val plan = before.restorePlan
        if (!before.canConfirmRestore || plan?.id != planId) return@enqueue
        start(expectedIdentity, DataManagementOperation.RESTORE, backend.canCancelRestore) { running ->
            backend.confirmRestore(checkNotNull(plan), expectedIdentity, running.id, running.cancelled::get)
        }
    }

    private fun start(identity: SessionIdentity, operation: DataManagementOperation, canCancel: Boolean,
        work: suspend (Active) -> DataManagementReport) {
        val running = Active(UUID.randomUUID().toString(), identity)
        if (!publish(identity) {
            active = running
            it.copy(step = DataManagementStep.RUNNING, exportPreview = null, restorePlan = null,
            error = null, report = null, job = DataManagementJob(running.id, identity, operation,
                if (operation == DataManagementOperation.EXPORT) "正在保存备份" else "正在恢复数据", canCancel = canCancel))
        }) return
        scope.launch {
            val report = try { work(running) } catch (error: Exception) {
                // Backends resolve receipt/commit races before throwing or returning a cancellation report.
                DataManagementReport(running.id, identity, operation,
                    if (error is CancellationException) DataManagementResult.CANCELLED else DataManagementResult.FAILED,
                    DataManagementCounts(), committed = false, issues = listOf(DataManagementIssue(safeMessage(error), error !is CancellationException)))
            }
            complete(identity) {
                if (active === running && report.identity == identity && report.jobId == running.id && report.operation == operation) {
                    publish(identity) {
                        active = null
                        it.copy(step = DataManagementStep.REPORT, report = report,
                            job = it.job?.copy(committed = report.committed, finished = true, canCancel = false,
                                stageTitle = if (report.committed) "操作已提交" else "操作已结束"))
                    }
                }
            }
        }
    }

    override fun cancel(jobId: String, expectedIdentity: SessionIdentity) = enqueue(expectedIdentity) {
        active?.takeIf { it.id == jobId && it.identity == expectedIdentity && mutableState.value.canCancelJob }?.let {
            it.cancelled.set(true)
            publish(expectedIdentity) { state -> state.copy(job = state.job?.copy(canCancel = false, stageTitle = "正在确认取消结果")) }
        }
    }
    override fun reopen(jobId: String, expectedIdentity: SessionIdentity) = enqueue(expectedIdentity) {
        if (jobId.isBlank()) return@enqueue
        val before = mutableState.value
        if (before.job?.id == jobId || before.report?.jobId == jobId) return@enqueue
        if (!usable()) return@enqueue
        val report = backend.reopen(jobId, expectedIdentity)
        if (current(expectedIdentity)) {
            if (report?.identity == expectedIdentity && report.jobId == jobId) publish(expectedIdentity) { it.copy(
                step = DataManagementStep.REPORT, report = report, exportPreview = null, restorePlan = null,
                job = DataManagementJob(jobId, expectedIdentity, report.operation, "操作已结束", committed = report.committed, finished = true)) }
            else publish(expectedIdentity) { it.copy(error = DataManagementError("上次操作已中断，请重新选择文件并预览。")) }
        }
    }
    override fun dismissReport(expectedIdentity: SessionIdentity) = enqueue(expectedIdentity) {
        if (!usable()) return@enqueue
        backend.forget(expectedIdentity)
        restoreUris.clear()
        if (current(expectedIdentity)) publish(expectedIdentity) { it.copy(step = DataManagementStep.SELECTION,
            report = null, job = null, exportPreview = null, restorePlan = null, error = null) }
    }
    private suspend fun failure(identity: SessionIdentity, error: Exception, loading: Boolean = mutableState.value.loading) {
        if (current(identity)) publish(identity) { it.copy(loading = loading, error = DataManagementError(safeMessage(error), true)) }
    }
    private fun validUri(uri: String) = uri.length in 1..8192 && uri.startsWith("content://") && '\u0000' !in uri
    companion object {
        internal fun exportCreatedAtUtc(epochMillis: Long): String = Api24UtcTimestamp.fromEpochMillis(epochMillis)

        /** Keeps existing host Instant callers source-compatible without linking that API26 type on ART. */
        internal fun exportCreatedAtUtc(instant: Any): String {
            require(instant.javaClass.name == "java.time.Instant") { "host Instant required" }
            return Api24UtcTimestamp.truncateMillis(instant.toString())
        }

        internal fun safeMessage(error: Exception) = when (error) {
            is ServiceFailure -> error.userMessage
            is CancellationException -> "取消请求已处理，请查看本次提交结果。"
            is SecurityException -> "文件访问授权已失效，请重新选择文件。"
            else -> "操作未完成，请重新预览后重试。"
        }
    }
}
