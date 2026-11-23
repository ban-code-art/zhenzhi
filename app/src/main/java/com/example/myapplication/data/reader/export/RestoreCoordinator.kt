package com.example.myapplication.data.reader.export

import androidx.room.withTransaction
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.ReaderAnnotationEntity
import com.example.myapplication.data.local.entity.ReaderDocumentEntity
import com.example.myapplication.data.local.entity.ReaderJobEntity
import com.example.myapplication.data.local.entity.ReaderNoteVersionEntity
import com.example.myapplication.data.local.entity.ReaderNotebookEntity
import com.example.myapplication.data.local.entity.ReaderStateEntity
import com.example.myapplication.data.reader.ink.InkManifest
import com.example.myapplication.data.reader.ink.InkManifestCodec
import com.example.myapplication.data.reader.ink.InkStore
import com.example.myapplication.data.reader.notes.AnnotationColor
import com.example.myapplication.data.reader.notes.AnnotationStyle
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import com.example.myapplication.data.session.SessionIdentity
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.put

/**
 * 恢复协调器（W5-06）。
 *
 * 流程：`preview`（只校验、不写库）→ `apply`（staging → 单事务发布 → 回执）。
 *
 * 关键边界：
 * - **只恢复给当前 userId**：包内 userId/ownerUserId 一律不读（annotations 编码器显式忽略未知字段）；
 * - 同 `sourceContentHash` 复用当前用户已有的 READY 文档并重写 locator；无同 hash 时创建
 *   “来源待关联”文档（现有字段组合：`kind=IMPORTED`、`import_status=PENDING`、`blob_path=NULL`、
 *   `file_sha256=NULL`，见报告说明），笔记可读但不可原文检索；
 * - 冲突笔记**保留副本**（复制到 “（恢复副本）” 文档下的新 notebook），绝不覆盖；
 * - 异常/取消：删除已写墨迹文件 + 清理 staging，不重复插入；事务内失败由 Room 回滚；
 * - v2.2（§18）：不恢复 writerEpoch / MutationReceipt / active_slot / 未确认提议 / secret 引用；
 *   阅读进度只 `insertIfAbsent` 再用新 writer 领取（claimWriter），绝不覆盖本机已有进度；
 *   不触发任何 tool。
 *
 * 回执持久化在既有 `reader_jobs.progress_json`（§13），不新增表/列（schema v6 保持不动）。
 */
class RestoreCoordinator(
    private val database: AppDatabase,
    inkStore: InkStore,
    packageRoot: File,
    stagingRoot: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxPackageBytes: Long = BackupPackageVerifier.DEFAULT_MAX_PACKAGE_BYTES,
    private val identityProvider: (suspend () -> SessionIdentity)? = null,
) {
    private val inkStore: InkStore = inkStore
    private val packageRoot: File = packageRoot.absoluteFile
    private val stagingRoot: File = stagingRoot.absoluteFile

    private val catalog get() = database.readerCatalogDao()
    private val annotationDao get() = database.readerAnnotationDao()
    private val notebookDao get() = database.readerNotebookDao()
    private val stateDao get() = database.readerStateDao()
    private val jobDao get() = database.readerJobDao()

    // ---------------------------------------------------------------- 预览

    /**
     * 预检：manifest 版本 / 每文件 sha256 / 路径白名单 / 总大小上限；
     * 失败返回 [RestoreOutcome.Rejected] 且**不写库**。
     */
    suspend fun preview(packageDirectory: File, userId: Long, expectedIdentity: SessionIdentity? = null): RestoreOutcome {
        if (!matchesIdentity(userId, expectedIdentity)) return identityRejected()
        if (userId <= 0L) {
            return RestoreOutcome.Rejected(BackupIssueCodes.INVALID_USER, "userId 必须为正数")
        }
        val directory = resolvePackageDirectory(packageDirectory)
            ?: return RestoreOutcome.Rejected(
                BackupIssueCodes.PATH_ESCAPE,
                "备份包目录不在允许的读取根（cache/exports）内",
            )
        val load = BackupPackageVerifier.loadManifest(directory, maxPackageBytes)
        if (!load.isValid) return reject(load.issues)
        val manifest = load.manifest ?: return reject(load.issues)
        val verification = BackupPackageVerifier.verify(directory, manifest, maxPackageBytes)
        if (!verification.isValid) return reject(verification.issues)

        val manifestHash = BackupPackageVerifier.sha256Of(File(directory, BackupSchema.MANIFEST_FILE))
        val annotationsFile = File(directory, BackupSchema.ANNOTATIONS_FILE)
        val decoded = if (annotationsFile.isFile) {
            if (annotationsFile.length() > MAX_ANNOTATIONS_FILE_BYTES) {
                return RestoreOutcome.Rejected(
                    BackupIssueCodes.PACKAGE_TOO_LARGE,
                    "annotations.json 超过 $MAX_ANNOTATIONS_FILE_BYTES 字节",
                )
            }
            BackupAnnotationCodec.decodeEntries(annotationsFile.readText(StandardCharsets.UTF_8))
        } else {
            DecodedAnnotations(emptyList(), emptyList(), fatal = false)
        }
        if (decoded.fatal) return reject(decoded.issues)

        val warnings = mutableListOf<String>()
        decoded.issues.forEach { warnings += it.toString() }

        val index = loadDocumentIndex(userId)
        val reusable = manifest.documents.mapNotNull { document ->
            val target = RestorePreviewer.resolveReusableDocument(document, index)
                ?: return@mapNotNull null
            document.exportDocumentId to target
        }.toMap()
        var predictedConflicts = 0
        manifest.documents.forEach { document ->
            val target = reusable[document.exportDocumentId] ?: return@forEach
            if (document.noteChars <= 0) return@forEach
            val body = readNoteBody(directory, document).body ?: return@forEach
            if (body.isBlank()) return@forEach
            val existing = notebookDao.findNotebookForDocument(userId, target) ?: return@forEach
            if (existing.bodyMarkdown != body && existing.bodyMarkdown.isNotBlank()) {
                predictedConflicts += 1
            }
        }

        val preview = RestorePreview(
            exportId = manifest.exportId,
            manifestHash = manifestHash,
            appVersion = manifest.appVersion,
            createdAt = manifest.createdAt,
            sourceContentHash = manifest.sourceContentHash,
            documentCount = manifest.documents.size,
            noteCount = manifest.documents.count { it.notesFile != null && it.noteChars > 0 },
            annotationCount = decoded.entries.size,
            inkFileCount = manifest.files.count { it.kind == BackupFileKind.INK },
            totalBytes = manifest.files.sumOf { it.bytes },
            sameBookDetected = reusable.isNotEmpty(),
            reusableDocumentIds = reusable,
            predictedConflicts = predictedConflicts,
            invalidAnnotations = decoded.issues.size,
            noSourceDocumentCount = manifest.documents.count { it.exportDocumentId !in reusable },
            warnings = warnings,
        )
        return if (matchesIdentity(userId, expectedIdentity)) RestoreOutcome.Previewed(preview) else identityRejected()
    }

    // ---------------------------------------------------------------- 应用

    /** 执行恢复；只有全部提交且无损坏/可重试项才返回 [RestoreOutcome.Applied]。 */
    suspend fun apply(packageDirectory: File, userId: Long, expectedIdentity: SessionIdentity? = null): RestoreOutcome {
        return applyGuarded(packageDirectory, userId, expectedIdentity) { false }
    }

    suspend fun applyGuarded(packageDirectory: File, userId: Long, expectedIdentity: SessionIdentity?, cancelled: () -> Boolean): RestoreOutcome {
        if (cancelled()) throw CancellationException("RESTORE_CANCELLED")
        val previewOutcome = preview(packageDirectory, userId, expectedIdentity)
        val preview = (previewOutcome as? RestoreOutcome.Previewed)?.preview ?: return previewOutcome
        val directory = resolvePackageDirectory(packageDirectory)
            ?: return RestoreOutcome.Rejected(BackupIssueCodes.PATH_ESCAPE, "备份包目录不在允许的读取根内")
        val manifest = BackupPackageVerifier.loadManifest(directory, maxPackageBytes).manifest
            ?: return RestoreOutcome.Rejected(BackupIssueCodes.INVALID_MANIFEST, "manifest 无法读取")

        val restoreId = "restore-" + BackupPackageVerifier.sha256("$userId|${preview.manifestHash}").take(24)
        val staging = File(stagingRoot, restoreId)
        if (!isInside(stagingRoot, staging)) {
            return RestoreOutcome.Rejected(BackupIssueCodes.PATH_ESCAPE, "staging 目录越界")
        }

        val builder = ReportBuilder()
        val writtenInkPaths = mutableListOf<String>()
        var committed = false
        try {
            deleteRecursively(staging, stagingRoot)
            ensureDirectory(stagingRoot)
            ensureDirectory(staging)

            val receipt = loadReceipt(userId, preview.manifestHash)
            builder.warnings += preview.warnings

            // 1) staging：笔记正文落到 staging，解析失败计为损坏而不是中断整包。
            val noteBodies = stageNotes(directory, staging, manifest, builder)

            // 2) staging：标注（编码器已忽略包内 userId）。
            val annotationsFile = File(directory, BackupSchema.ANNOTATIONS_FILE)
            val decoded = if (annotationsFile.isFile) {
                BackupAnnotationCodec.decodeEntries(annotationsFile.readText(StandardCharsets.UTF_8))
            } else {
                DecodedAnnotations(emptyList(), emptyList(), fatal = false)
            }
            if (decoded.fatal) {
                return RestoreOutcome.Rejected(
                    BackupIssueCodes.ANNOTATIONS_CORRUPT,
                    decoded.issues.joinToString("; ") { it.toString() },
                )
            }
            decoded.issues.forEach { issue ->
                builder.markDamaged(issue.code, issue.detail, issue.path)
            }

            // 3) 文档映射（同 hash 复用 / 无源占位）。
            val mappings = resolveMappings(userId, manifest)

            // 4) staging + 发布墨迹（先落文件；事务失败再按清单补偿删除）。
            val existingInk = loadExistingInk(userId)
            val stagedInk = stageInk(directory, staging, manifest, mappings, builder)
            val restoredInk = publishInk(userId, stagedInk, existingInk, receipt, builder, writtenInkPaths)

            checkIdentity(userId, expectedIdentity)
            if (cancelled()) throw CancellationException("RESTORE_CANCELLED")

            // 5) 单事务发布：文档/notebook/标注/阅读进度/回执。
            database.withTransaction {
                checkIdentity(userId, expectedIdentity)
                if (cancelled()) throw CancellationException("RESTORE_CANCELLED")
                commitInto(
                    userId = userId,
                    manifest = manifest,
                    mappings = mappings,
                    noteBodies = noteBodies,
                    annotations = decoded.entries,
                    restoredInk = restoredInk,
                    receipt = receipt,
                    restoreId = restoreId,
                    manifestHash = preview.manifestHash,
                    builder = builder,
                    now = clock(),
                    strictReceipt = expectedIdentity != null,
                )
                // This is inside Room's transaction, after rows and receipt: epoch changes roll back both.
                checkIdentity(userId, expectedIdentity)
                if (cancelled()) throw CancellationException("RESTORE_CANCELLED")
            }
            committed = true

            // 冲突（笔记/标注/墨迹）也降级为 PARTIAL：数据已按“保留副本、不覆盖”策略提交，
            // 但用户必须看到明细，不能报成“全部成功”（§12）。
            val cleanlyApplied = builder.damaged == 0 && builder.retryable == 0 && builder.conflicts == 0
            val report = builder.build(
                restoreId = restoreId,
                exportId = manifest.exportId,
                manifestHash = preview.manifestHash,
                userId = userId,
                stage = if (cleanlyApplied) RestoreStage.APPLIED else RestoreStage.PARTIAL,
            )
            return if (report.fullyApplied) {
                RestoreOutcome.Applied(report)
            } else {
                RestoreOutcome.PartialFailure(report)
            }
        } catch (cancel: CancellationException) {
            if (expectedIdentity != null) committed = withContext(NonCancellable) {
                findServiceReceipt(userId, preview.manifestHash) != null
            }
            if (!committed) compensateInk(userId, writtenInkPaths)
            throw cancel
        } catch (error: Exception) {
            if (!committed) compensateInk(userId, writtenInkPaths)
            return RestoreOutcome.Rejected(
                BackupIssueCodes.RESTORE_FAILED,
                "恢复失败（已回滚，未写入任何数据）：${error.message}",
            )
        } finally {
            deleteRecursively(staging, stagingRoot)
        }
    }

    // ---------------------------------------------------------------- staging

    /**
     * 把每个文档的笔记正文写到 staging 再读回内存。
     *
     * 读取失败/超限计为损坏（逐文档），不影响其它文档；返回 exportDocumentId -> 正文。
     */
    private fun stageNotes(
        directory: File,
        staging: File,
        manifest: BackupManifest,
        builder: ReportBuilder,
    ): Map<String, String> {
        val bodies = linkedMapOf<String, String>()
        var totalBytes = 0L
        manifest.documents.forEach { document ->
            val read = readNoteBody(directory, document)
            if (read.issue != null) {
                builder.markDamaged(read.issue.code, read.issue.detail, read.issue.path)
                return@forEach
            }
            val body = read.body ?: return@forEach
            totalBytes += body.toByteArray(StandardCharsets.UTF_8).size.toLong()
            if (totalBytes > MAX_NOTE_BODY_TOTAL_BYTES) {
                builder.markDamaged(
                    BackupIssueCodes.NOTE_TOO_LARGE,
                    "笔记正文总量超过 $MAX_NOTE_BODY_TOTAL_BYTES 字节上限",
                )
                return@forEach
            }
            val stagedRelative = "notes/${document.exportDocumentId}.body"
            val staged = BackupPackageVerifier.resolveInside(staging, stagedRelative)
                ?: run {
                    builder.markDamaged(BackupIssueCodes.PATH_ESCAPE, "staging 笔记路径越界", stagedRelative)
                    return@forEach
                }
            staged.parentFile?.let(::ensureDirectory)
            FileOutputStream(staged).use { output ->
                output.write(body.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            val reread = runCatching { staged.readText(StandardCharsets.UTF_8) }.getOrNull()
            if (reread != body) {
                builder.markDamaged(BackupIssueCodes.STORAGE_FAILURE, "staging 笔记读回不一致", stagedRelative)
                return@forEach
            }
            bodies[document.exportDocumentId] = body
        }
        return bodies
    }

    private data class NoteRead(val body: String?, val issue: BackupIssue? = null)

    private fun readNoteBody(directory: File, document: BackupDocument): NoteRead {
        val relative = document.notesFile ?: return NoteRead("")
        val file = BackupPackageVerifier.resolveInside(directory, relative)
            ?: return NoteRead(null, BackupIssue(BackupIssueCodes.PATH_ESCAPE, "笔记路径越界", relative))
        if (!file.isFile) {
            return NoteRead(null, BackupIssue(BackupIssueCodes.FILE_MISSING, "笔记文件缺失", relative))
        }
        if (file.length() > MAX_NOTE_FILE_BYTES) {
            return NoteRead(null, BackupIssue(BackupIssueCodes.NOTE_TOO_LARGE, "笔记文件超过上限", relative))
        }
        val text = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull()
            ?: return NoteRead(null, BackupIssue(BackupIssueCodes.STORAGE_FAILURE, "笔记文件读取失败", relative))
        val body = MarkdownExporter.parseNoteBody(text)
            ?: return NoteRead(
                null,
                BackupIssue(BackupIssueCodes.INVALID_MANIFEST, "笔记文件缺少正文标记", relative),
            )
        if (body.length > MAX_BODY_CHARS) {
            return NoteRead(null, BackupIssue(BackupIssueCodes.NOTE_TOO_LARGE, "笔记正文超长", relative))
        }
        return NoteRead(body)
    }

    // ---------------------------------------------------------------- 文档映射

    private data class DocumentMapping(
        val document: BackupDocument,
        val targetDocumentId: String,
        val targetRevision: String,
        /** true 表示复用当前用户已有的 READY 文档（locator 需要改写 contentRevision）。 */
        val reuseReadyDocument: Boolean,
        val needsInsert: Boolean,
        val placeholder: Boolean,
    )

    private suspend fun resolveMappings(
        userId: Long,
        manifest: BackupManifest,
    ): Map<String, DocumentMapping> {
        val index = loadDocumentIndex(userId)
        val result = linkedMapOf<String, DocumentMapping>()
        manifest.documents.forEach { document ->
            val reusableId = RestorePreviewer.resolveReusableDocument(document, index)
            if (reusableId != null) {
                val entity = catalog.findDocument(reusableId)
                if (entity != null && entity.importStatus == "READY") {
                    result[document.exportDocumentId] = DocumentMapping(
                        document = document,
                        targetDocumentId = entity.id,
                        targetRevision = entity.contentRevision,
                        reuseReadyDocument = true,
                        needsInsert = false,
                        placeholder = false,
                    )
                    return@forEach
                }
            }
            val placeholderId = placeholderDocumentId(userId, document)
            val existing = catalog.findDocument(placeholderId)
            result[document.exportDocumentId] = if (existing != null) {
                DocumentMapping(
                    document = document,
                    targetDocumentId = existing.id,
                    targetRevision = existing.contentRevision,
                    reuseReadyDocument = false,
                    needsInsert = false,
                    placeholder = true,
                )
            } else {
                DocumentMapping(
                    document = document,
                    targetDocumentId = placeholderId,
                    targetRevision = placeholderRevision(document),
                    reuseReadyDocument = false,
                    needsInsert = true,
                    placeholder = true,
                )
            }
        }
        return result
    }

    /** 只读索引：READY 的内置书按 contentRevision、本人导入书按 fileSha256。 */
    private suspend fun loadDocumentIndex(userId: Long): RestoreDocumentIndex {
        val visible = catalog.observeVisibleDocuments(userId).first()
        val imported = linkedMapOf<String, String>()
        val builtin = linkedMapOf<String, String>()
        visible.forEach { document ->
            if (document.deletedAt != null || document.importStatus != "READY") return@forEach
            when (document.kind) {
                "BUILTIN" -> builtin[document.contentRevision] = document.id
                "IMPORTED" -> if (document.ownerUserId == userId) {
                    document.fileSha256?.let { hash -> imported[hash] = document.id }
                }
            }
        }
        return RestoreDocumentIndex(imported, builtin)
    }

    // ---------------------------------------------------------------- 墨迹

    private data class StagedInkPage(
        val exportDocumentId: String,
        val targetDocumentId: String,
        val pageIndex: Int,
        val sectionId: String,
        val manifest: InkManifest,
        val fileKey: String,
    )

    private data class ExistingInk(val relativePath: String, val manifest: InkManifest)

    private data class RestoredInkPage(
        val exportDocumentId: String,
        val pageIndex: Int,
        val sectionId: String,
        val relativePath: String,
    )

    private fun stageInk(
        directory: File,
        staging: File,
        manifest: BackupManifest,
        mappings: Map<String, DocumentMapping>,
        builder: ReportBuilder,
    ): List<StagedInkPage> {
        val staged = mutableListOf<StagedInkPage>()
        val usedNames = mutableMapOf<String, MutableSet<String>>()
        manifest.files.filter { it.kind == BackupFileKind.INK }.forEach { file ->
            val segments = file.path.split('/')
            val exportDocumentId = segments.getOrNull(1) ?: run {
                builder.markDamaged(
                    BackupIssueCodes.INK_PATH_NOT_ALLOWED,
                    "墨迹文件缺少文档目录",
                    file.path,
                )
                return@forEach
            }
            val mapping = mappings[exportDocumentId] ?: run {
                builder.markDamaged(
                    BackupIssueCodes.INK_PATH_NOT_ALLOWED,
                    "墨迹文件不属于包内任何文档",
                    file.path,
                )
                return@forEach
            }
            val source = BackupPackageVerifier.resolveInside(directory, file.path)
                ?: run {
                    builder.markDamaged(BackupIssueCodes.PATH_ESCAPE, "墨迹路径越界", file.path)
                    return@forEach
                }
            if (!source.isFile) {
                builder.markDamaged(BackupIssueCodes.FILE_MISSING, "墨迹文件缺失", file.path)
                return@forEach
            }
            if (source.length() > MAX_INK_FILE_BYTES) {
                builder.markDamaged(BackupIssueCodes.INK_CORRUPT, "墨迹文件超过上限", file.path)
                return@forEach
            }
            val inkManifest = runCatching {
                InkManifestCodec.decode(source.readText(StandardCharsets.UTF_8))
            }.getOrNull()
            if (inkManifest == null) {
                builder.markDamaged(BackupIssueCodes.INK_CORRUPT, "墨迹文件无法解析", file.path)
                return@forEach
            }
            if (inkManifest.documentId != exportDocumentId) {
                builder.markDamaged(
                    BackupIssueCodes.INK_CORRUPT,
                    "墨迹的 documentId 与目录不一致",
                    file.path,
                )
                return@forEach
            }
            val targetRevision = if (mapping.reuseReadyDocument) {
                mapping.targetRevision
            } else {
                // 无源占位文档没有正文 revision，保留导出时的来源 revision 作为历史身份。
                inkManifest.contentRevision
            }
            val rewritten = runCatching {
                inkManifest.copy(
                    documentId = mapping.targetDocumentId,
                    contentRevision = targetRevision,
                )
            }.getOrNull()
            if (rewritten == null) {
                builder.markDamaged(BackupIssueCodes.INK_CORRUPT, "墨迹重写失败", file.path)
                return@forEach
            }
            val names = usedNames.getOrPut(exportDocumentId) { mutableSetOf() }
            var name = "page-${rewritten.pageIndex}"
            if (!names.add(name)) {
                name = "$name-${BackupPackageVerifier.sha256(rewritten.sectionId).take(8)}"
                names += name
            }
            val stagedRelative = "ink/$exportDocumentId/$name.json"
            val stagedFile = BackupPackageVerifier.resolveInside(staging, stagedRelative)
                ?: run {
                    builder.markDamaged(BackupIssueCodes.PATH_ESCAPE, "staging 墨迹路径越界", stagedRelative)
                    return@forEach
                }
            runCatching {
                stagedFile.parentFile?.let(::ensureDirectory)
                FileOutputStream(stagedFile).use { output ->
                    output.write(InkManifestCodec.encode(rewritten).toByteArray(StandardCharsets.UTF_8))
                    output.fd.sync()
                }
            }.onFailure {
                builder.markDamaged(BackupIssueCodes.STORAGE_FAILURE, "staging 墨迹写入失败：${it.message}", stagedRelative)
                return@forEach
            }
            staged += StagedInkPage(
                exportDocumentId = exportDocumentId,
                targetDocumentId = mapping.targetDocumentId,
                pageIndex = rewritten.pageIndex,
                sectionId = rewritten.sectionId,
                manifest = rewritten,
                fileKey = file.path,
            )
        }
        return staged
    }

    /** 按 userId 遍历现有墨迹（用于冲突检测与 receipt 复用），不读其他用户目录。 */
    private fun loadExistingInk(userId: Long): Map<String, ExistingInk> {
        val userDirectory = runCatching { inkStore.userDirectory(userId) }.getOrNull() ?: return emptyMap()
        if (!userDirectory.isDirectory) return emptyMap()
        val canonicalRoot = runCatching { userDirectory.canonicalFile }.getOrNull() ?: return emptyMap()
        val prefix = canonicalRoot.path + File.separator
        val result = linkedMapOf<String, ExistingInk>()
        canonicalRoot.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            if (!file.name.startsWith("page-") || !file.name.endsWith(".json")) return@forEach
            val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return@forEach
            if (!canonical.path.startsWith(prefix)) return@forEach
            val manifest = runCatching {
                InkManifestCodec.decode(canonical.readText(StandardCharsets.UTF_8))
            }.getOrNull() ?: return@forEach
            val relative = "u$userId/" +
                canonical.path.removePrefix(prefix).replace(File.separatorChar, '/')
            result[inkKey(manifest.documentId, manifest.sectionId, manifest.pageIndex)] =
                ExistingInk(relative, manifest)
        }
        return result
    }

    private fun publishInk(
        userId: Long,
        staged: List<StagedInkPage>,
        existingInk: Map<String, ExistingInk>,
        receipt: RestoreReceipt,
        builder: ReportBuilder,
        writtenInkPaths: MutableList<String>,
    ): Map<String, List<RestoredInkPage>> {
        val result = linkedMapOf<String, MutableList<RestoredInkPage>>()
        staged.forEach { page ->
            val key = inkKey(page.targetDocumentId, page.sectionId, page.pageIndex)
            val existing = existingInk[key]
            if (existing != null) {
                if (existing.manifest.strokes == page.manifest.strokes) {
                    builder.inkPagesReused += 1
                    result.getOrPut(page.exportDocumentId) { mutableListOf() }
                        .add(RestoredInkPage(page.exportDocumentId, page.pageIndex, page.sectionId, existing.relativePath))
                } else {
                    builder.conflicts += 1
                    builder.issues += RestoreIssue(
                        BackupIssueCodes.INK_CONFLICT,
                        "本机同页已有不同手写，保留本机内容，未覆盖",
                        retryable = false,
                        path = page.fileKey,
                    )
                }
                return@forEach
            }
            if (page.fileKey in receipt.appliedFileKeys) {
                // 上次已应用但文件与索引不一致（例如被手工删除）：按可重试处理，不静默宣称成功。
                builder.markRetryable(BackupIssueCodes.INK_CORRUPT, "回执显示已应用但本机墨迹缺失", page.fileKey)
                return@forEach
            }
            try {
                val relative = inkStore.save(
                    userId = userId,
                    documentId = page.targetDocumentId,
                    sectionId = page.sectionId,
                    contentRevision = page.manifest.contentRevision,
                    pageIndex = page.pageIndex,
                    manifest = page.manifest,
                )
                writtenInkPaths += relative
                builder.inkPagesAdded += 1
                result.getOrPut(page.exportDocumentId) { mutableListOf() }
                    .add(RestoredInkPage(page.exportDocumentId, page.pageIndex, page.sectionId, relative))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                builder.markRetryable(
                    BackupIssueCodes.STORAGE_FAILURE,
                    "墨迹写入失败：${error.message}",
                    page.fileKey,
                )
            }
        }
        return result
    }

    private fun compensateInk(userId: Long, writtenPaths: List<String>) {
        writtenPaths.forEach { path -> runCatching { inkStore.delete(userId, path) } }
    }

    private fun inkKey(documentId: String, sectionId: String, pageIndex: Int): String =
        "$documentId|$sectionId|$pageIndex"

    // ---------------------------------------------------------------- 事务发布

    private suspend fun commitInto(
        userId: Long,
        manifest: BackupManifest,
        mappings: Map<String, DocumentMapping>,
        noteBodies: Map<String, String>,
        annotations: List<BackupAnnotationEntry>,
        restoredInk: Map<String, List<RestoredInkPage>>,
        receipt: RestoreReceipt,
        restoreId: String,
        manifestHash: String,
        builder: ReportBuilder,
        now: Long,
        strictReceipt: Boolean = false,
    ) {
        val notebookIds = linkedMapOf<String, String>()

        // 1) 文档 + 阅读进度（先不写 notebook 映射）。
        mappings.forEach { (exportDocumentId, mapping) ->
            if (mapping.needsInsert) {
                catalog.insertDocument(
                    ReaderDocumentEntity(
                        id = mapping.targetDocumentId,
                        ownerUserId = userId,
                        kind = "IMPORTED",
                        legacyChapterId = null,
                        format = mapping.document.format.take(MAX_FORMAT_CHARS),
                        title = mapping.document.title.take(MAX_TITLE_CHARS),
                        author = mapping.document.author,
                        // “来源待关联”：不写 blob_path，也不占 (owner_user_id, file_sha256) 唯一索引，
                        // 避免阻断用户之后对同一本书的真实导入。
                        blobPath = null,
                        fileSha256 = null,
                        contentRevision = mapping.targetRevision,
                        extractorVersion = RESTORE_EXTRACTOR_VERSION,
                        metadataRevision = 1,
                        accessEpoch = 1,
                        importStatus = NO_SOURCE_IMPORT_STATUS,
                        deletedAt = null,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                builder.documentsAdded += 1
            } else {
                builder.documentsReused += 1
            }
            if (mapping.placeholder) {
                builder.documentsNoSource += 1
                builder.noSourceDocumentIds += mapping.targetDocumentId
            }
        }

        // 2) 笔记本（冲突保留副本）。
        mappings.forEach { (exportDocumentId, mapping) ->
            val body = noteBodies[exportDocumentId] ?: return@forEach
            if (body.isBlank()) return@forEach
            val existing = notebookDao.findNotebookForDocument(userId, mapping.targetDocumentId)
            when {
                existing == null -> {
                    val notebookId = restoredNotebookId(userId, mapping.targetDocumentId)
                    notebookDao.insertNotebook(
                        ReaderNotebookEntity(
                            id = notebookId,
                            userId = userId,
                            documentId = mapping.targetDocumentId,
                            bodyMarkdown = body,
                            revision = 1,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                    insertNoteVersion(
                        notebookId = notebookId,
                        revision = 1,
                        operationId = "restore:$restoreId:$exportDocumentId",
                        body = body,
                        now = now,
                    )
                    notebookIds[exportDocumentId] = notebookId
                    builder.notesAdded += 1
                }

                existing.bodyMarkdown == body -> {
                    notebookIds[exportDocumentId] = existing.id
                    builder.notesReused += 1
                }

                existing.bodyMarkdown.isBlank() -> {
                    val changed = notebookDao.compareAndSetBody(
                        userId = userId,
                        notebookId = existing.id,
                        baseRevision = existing.revision,
                        body = body,
                        now = now,
                    )
                    if (changed == 1) {
                        insertNoteVersion(
                            notebookId = existing.id,
                            revision = existing.revision + 1,
                            operationId = "restore:$restoreId:$exportDocumentId",
                            body = body,
                            now = now,
                        )
                        builder.notesAdded += 1
                    } else {
                        // 并发下已有正文写入：以现有内容为准，不覆盖。
                        builder.notesReused += 1
                        builder.warnings += "笔记 $exportDocumentId 在恢复期间被本机修改，已保留本机内容"
                    }
                    notebookIds[exportDocumentId] = existing.id
                }

                else -> {
                    // 冲突：导出正文进“（恢复副本）”文档，原笔记不动。
                    val copyDocumentId = copyDocumentId(userId, restoreId, exportDocumentId)
                    if (catalog.findDocument(copyDocumentId) == null) {
                        catalog.insertDocument(
                            ReaderDocumentEntity(
                                id = copyDocumentId,
                                ownerUserId = userId,
                                kind = "IMPORTED",
                                legacyChapterId = null,
                                format = mapping.document.format.take(MAX_FORMAT_CHARS),
                                title = copyTitle(mapping.document.title),
                                author = mapping.document.author,
                                blobPath = null,
                                fileSha256 = null,
                                contentRevision = mapping.targetRevision,
                                extractorVersion = RESTORE_EXTRACTOR_VERSION,
                                metadataRevision = 1,
                                accessEpoch = 1,
                                importStatus = NO_SOURCE_IMPORT_STATUS,
                                deletedAt = null,
                                createdAt = now,
                                updatedAt = now,
                            ),
                        )
                        builder.copyDocumentIds += copyDocumentId
                    }
                    val copyNotebookId = restoredNotebookId(userId, copyDocumentId)
                    if (notebookDao.findNotebook(userId, copyNotebookId) == null) {
                        notebookDao.insertNotebook(
                            ReaderNotebookEntity(
                                id = copyNotebookId,
                                userId = userId,
                                documentId = copyDocumentId,
                                bodyMarkdown = body,
                                revision = 1,
                                createdAt = now,
                                updatedAt = now,
                            ),
                        )
                        insertNoteVersion(
                            notebookId = copyNotebookId,
                            revision = 1,
                            operationId = "restore:$restoreId:copy:$exportDocumentId",
                            body = body,
                            now = now,
                        )
                        builder.noteCopies += 1
                    } else {
                        builder.notesReused += 1
                    }
                    notebookIds[exportDocumentId] = copyNotebookId
                    builder.conflicts += 1
                    builder.issues += RestoreIssue(
                        BackupIssueCodes.NOTE_CONFLICT,
                        "本机已有不同笔记，已保留恢复副本（$copyDocumentId），未覆盖原笔记",
                    )
                    builder.documentIdMap[exportDocumentId] = mapping.targetDocumentId
                }
            }
            builder.documentIdMap[exportDocumentId] = mapping.targetDocumentId
        }

        // 3) 标注（含 locator 重写、inkBlobPath 关联、冲突副本）。
        val idMap = RestoreIdMap(
            documentIds = mappings.mapValues { it.value.targetDocumentId },
            contentRevisions = mappings
                .filterValues { it.reuseReadyDocument }
                .mapValues { it.value.targetRevision },
            notebookIds = notebookIds,
        )
        val sectionsByTitle = mutableMapOf<String, Map<String, String>>()
        mappings.values.forEach { mapping ->
            if (!sectionsByTitle.containsKey(mapping.targetDocumentId)) {
                sectionsByTitle[mapping.targetDocumentId] = runCatching {
                    catalog.findSections(mapping.targetDocumentId, mapping.targetRevision)
                        .groupBy { it.title }
                        .mapNotNull { (title, rows) -> rows.singleOrNull()?.let { title to it.id } }
                        .toMap()
                }.getOrDefault(emptyMap())
            }
        }
        annotations.forEach { entry ->
            if ("${BackupSchema.ANNOTATIONS_FILE}#${entry.id}" in receipt.appliedFileKeys) {
                builder.skipped += 1
                builder.annotationsReused += 1
                return@forEach
            }
            val mapping = mappings[entry.documentId]
            if (mapping == null) {
                builder.markDamaged(
                    BackupIssueCodes.INVALID_ANNOTATION,
                    "标注引用了包内不存在的文档：${entry.documentId}",
                )
                return@forEach
            }
            val mappedSectionId = entry.sectionTitle
                ?.let { title -> sectionsByTitle[mapping.targetDocumentId]?.get(title) }
            val rewritten = RestoreLocatorRewriter.rewrite(
                locator = entry.locator,
                idMap = idMap,
                sectionId = mappedSectionId,
                notebookId = notebookIds[entry.documentId],
            )
            if (rewritten == null) {
                builder.markDamaged(
                    BackupIssueCodes.INVALID_LOCATOR,
                    "标注 locator 无法映射到当前用户文档",
                )
                return@forEach
            }
            val inkBlobPath = resolveInkBlobPath(entry, restoredInk)
            val style = AnnotationStyle(
                color = entry.color?.let { name ->
                    runCatching { AnnotationColor.valueOf(name) }.getOrDefault(AnnotationColor.YELLOW)
                } ?: AnnotationColor.YELLOW,
                underline = entry.underline,
            )
            val entity = ReaderAnnotationEntity(
                id = restoredAnnotationId(userId, mapping.targetDocumentId, entry.id),
                userId = userId,
                documentId = mapping.targetDocumentId,
                sectionId = mappedSectionId,
                kind = entry.type,
                locatorJson = ReaderLocatorCodec.encode(rewritten),
                quoteSnapshot = entry.quote?.take(MAX_QUOTE_CHARS),
                noteText = entry.noteText,
                styleJson = style.encode(),
                inkBlobPath = inkBlobPath,
                revision = 1,
                deletedAt = null,
                createdAt = entry.createdAt,
                updatedAt = entry.updatedAt,
            )
            val existing = annotationDao.findAnnotation(userId, entity.id)
            when {
                existing == null -> {
                    annotationDao.insertAnnotation(entity)
                    builder.annotationsAdded += 1
                }

                sameAnnotation(existing, entity) -> builder.annotationsReused += 1

                else -> {
                    val copyId = entity.id + "-c" +
                        BackupPackageVerifier.sha256(annotationSignature(entity)).take(8)
                    val copyExisting = annotationDao.findAnnotation(userId, copyId)
                    if (copyExisting == null) {
                        annotationDao.insertAnnotation(entity.copy(id = copyId))
                        builder.annotationsAdded += 1
                    } else if (sameAnnotation(copyExisting, entity)) {
                        builder.annotationsReused += 1
                    } else {
                        builder.markDamaged(
                            BackupIssueCodes.ANNOTATION_CONFLICT,
                            "标注副本 id 冲突且内容不同，已跳过",
                        )
                        return@forEach
                    }
                    builder.conflicts += 1
                    builder.issues += RestoreIssue(
                        BackupIssueCodes.ANNOTATION_CONFLICT,
                        "本机已有同 id 但内容不同的标注，已保留副本（$copyId）",
                    )
                }
            }
        }

        // 4) 阅读进度：只补缺失 + 领取新 writer（不恢复 writerEpoch/MutationReceipt/active_slot）。
        mappings.forEach { (exportDocumentId, mapping) ->
            val locator = mapping.document.lastLocator?.let { portable ->
                RestoreLocatorRewriter.rewrite(
                    locator = portable,
                    idMap = RestoreIdMap(
                        documentIds = mapOf(exportDocumentId to mapping.targetDocumentId),
                        contentRevisions = if (mapping.reuseReadyDocument) {
                            mapOf(exportDocumentId to mapping.targetRevision)
                        } else {
                            emptyMap()
                        },
                        notebookIds = notebookIds.filterKeys { it == exportDocumentId },
                    ),
                    notebookId = notebookIds[exportDocumentId],
                )
            }
            // 只补缺失；已有状态（含用户改名/进度）保持不动，随后由新 writer 领取。
            stateDao.insertIfAbsent(
                ReaderStateEntity(
                    userId = userId,
                    documentId = mapping.targetDocumentId,
                    locatorJson = locator?.let(ReaderLocatorCodec::encode),
                    settingsJson = null,
                    isCompleted = false,
                    isHidden = false,
                    displayTitleOverride = if (mapping.placeholder) "待关联来源：${mapping.document.title}" else null,
                    lastReadAt = 0L,
                    // 新 writer：不恢复包内 writerEpoch/lastSequence。
                    writerEpoch = 0L,
                    lastSequence = 0L,
                    revision = 0L,
                ),
            )
            stateDao.claimWriter(userId, mapping.targetDocumentId)
        }

        // 5) 回执：持久在既有 reader_jobs.progress_json（§13），不新增表。
        val appliedFileKeys = linkedSetOf<String>()
        appliedFileKeys += receipt.appliedFileKeys
        noteBodies.keys.forEach { exportDocumentId ->
            manifest.documents.firstOrNull { it.exportDocumentId == exportDocumentId }
                ?.notesFile
                ?.let(appliedFileKeys::add)
        }
        annotations.forEach { if (it.id.isNotBlank()) appliedFileKeys += "${BackupSchema.ANNOTATIONS_FILE}#${it.id}" }
        restoredInk.values.flatten().forEach { page ->
            val inkFile = manifest.files.firstOrNull { file ->
                file.kind == BackupFileKind.INK &&
                    file.path.startsWith("ink/${page.exportDocumentId}/")
            }
            inkFile?.let { appliedFileKeys += it.path }
        }
        builder.documentIdMap.putAll(mappings.mapValues { it.value.targetDocumentId })
        // 只有 damaged/retryable/conflicts 全为 0 才记 APPLIED/COMPLETED，否则 PARTIAL/FAILED。
        // 这两个状态都不在书架“进行中任务”过滤集合里（ShelfScreen 只显示 PENDING/RUNNING/RETRY_WAIT），
        // 因此恢复回执不会出现在导入进度条中。
        val receiptClean = builder.damaged == 0 && builder.retryable == 0 && builder.conflicts == 0
        persistReceipt(
            userId = userId,
            manifestHash = manifestHash,
            restoreId = restoreId,
            stage = if (receiptClean) "APPLIED" else "PARTIAL",
            status = if (receiptClean) "COMPLETED" else "FAILED",
            appliedFileKeys = appliedFileKeys,
            documentIdMap = builder.documentIdMap,
            issues = builder.issues,
            now = now,
            builder = builder,
            strictReceipt = strictReceipt,
        )
    }

    private fun sameAnnotation(
        existing: ReaderAnnotationEntity,
        candidate: ReaderAnnotationEntity,
    ): Boolean =
        existing.documentId == candidate.documentId &&
            existing.sectionId == candidate.sectionId &&
            existing.kind == candidate.kind &&
            existing.locatorJson == candidate.locatorJson &&
            existing.quoteSnapshot == candidate.quoteSnapshot &&
            existing.noteText == candidate.noteText &&
            existing.styleJson == candidate.styleJson &&
            existing.inkBlobPath == candidate.inkBlobPath

    private fun annotationSignature(entity: ReaderAnnotationEntity): String = listOf(
        entity.documentId,
        entity.sectionId.orEmpty(),
        entity.kind,
        entity.locatorJson,
        entity.quoteSnapshot.orEmpty(),
        entity.noteText.orEmpty(),
        entity.styleJson,
        entity.inkBlobPath.orEmpty(),
    ).joinToString("|")

    private fun resolveInkBlobPath(
        entry: BackupAnnotationEntry,
        restoredInk: Map<String, List<RestoredInkPage>>,
    ): String? {
        if (entry.type != "INK") return null
        val pageIndex = entry.inkPage ?: (entry.locator as? ReaderLocator.Fixed)?.pageIndex ?: return null
        val candidates = restoredInk[entry.documentId].orEmpty().filter { it.pageIndex == pageIndex }
        val match = candidates.firstOrNull { it.sectionId == entry.sectionId } ?: candidates.firstOrNull()
        return match?.relativePath
    }

    private suspend fun insertNoteVersion(
        notebookId: String,
        revision: Long,
        operationId: String,
        body: String,
        now: Long,
    ) {
        if (notebookDao.findVersionByOperation(notebookId, operationId) != null) return
        runCatching {
            notebookDao.insertVersion(
                ReaderNoteVersionEntity(
                    id = "version-restore-" +
                        BackupPackageVerifier.sha256("$notebookId|$operationId").take(32),
                    notebookId = notebookId,
                    revision = revision,
                    operationId = operationId,
                    bodyMarkdown = body,
                    actor = "RESTORE",
                    createdAt = now,
                ),
            )
        }
    }

    // ---------------------------------------------------------------- 回执

    private data class RestoreReceipt(
        val appliedFileKeys: Set<String>,
        val documentIdMap: Map<String, String>,
    )

    private suspend fun loadReceipt(userId: Long, manifestHash: String): RestoreReceipt {
        val job = jobDao.findByRequestKey(userId, receiptRequestKey(manifestHash))
            ?: return RestoreReceipt(emptySet(), emptyMap())
        val root = runCatching { Json.parseToJsonElement(job.progressJson).jsonObject }.getOrNull()
            ?: return RestoreReceipt(emptySet(), emptyMap())
        val applied = (root["appliedFileKeys"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?.toSet()
            .orEmpty()
        val idMap = (root["documentIdMap"] as? JsonObject)
            ?.entries
            ?.mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.content?.let { key to it }
            }
            ?.toMap()
            .orEmpty()
        return RestoreReceipt(applied, idMap)
    }

    private suspend fun persistReceipt(
        userId: Long,
        manifestHash: String,
        restoreId: String,
        stage: String,
        status: String,
        appliedFileKeys: Set<String>,
        documentIdMap: Map<String, String>,
        issues: List<RestoreIssue>,
        now: Long,
        builder: ReportBuilder,
        strictReceipt: Boolean = false,
    ) {
        val progressJson = buildJsonObject {
            put("schemaVersion", 1)
            put("restoreId", restoreId)
            put("manifestHash", manifestHash)
            put("stage", stage)
            put("status", status)
            put("serviceCounts", buildJsonObject {
                put("documents", builder.documentsAdded + builder.documentsReused)
                put("notes", builder.notesAdded + builder.notesReused)
                put("annotations", builder.annotationsAdded + builder.annotationsReused)
                put("inkPages", builder.inkPagesAdded + builder.inkPagesReused)
                put("added", builder.documentsAdded + builder.notesAdded + builder.annotationsAdded + builder.inkPagesAdded)
                put("reused", builder.documentsReused + builder.notesReused + builder.annotationsReused + builder.inkPagesReused)
                put("conflicts", builder.conflicts); put("damaged", builder.damaged)
                put("retryable", builder.retryable); put("skipped", builder.skipped)
            })
            put("documentIdMap", buildJsonObject { documentIdMap.forEach { (key, value) -> put(key, value) } })
            put("appliedFileKeys", buildJsonArray { appliedFileKeys.sorted().forEach { add(JsonPrimitive(it)) } })
            put("conflicts", JsonPrimitive(issues.count { it.code.endsWith("CONFLICT") }))
            val lastError = issues.firstOrNull()?.toString()
            if (lastError == null) {
                put("lastError", JsonNull)
            } else {
                put("lastError", lastError)
            }
            put("updatedAt", now)
        }.toString()
        val requestKey = receiptRequestKey(manifestHash)
        try {
            val existing = jobDao.findByRequestKey(userId, requestKey)
            if (existing == null) {
                jobDao.insert(
                    ReaderJobEntity(
                        id = "restore-job-" +
                            BackupPackageVerifier.sha256("$userId|$restoreId").take(24),
                        userId = userId,
                        kind = "RESTORE",
                        documentId = null,
                        requestKey = requestKey,
                        stage = stage,
                        status = status,
                        stagedPath = null,
                        targetRevision = null,
                        leaseToken = null,
                        leaseUntil = null,
                        attempts = if (status == "COMPLETED") 0 else 1,
                        nextRetryAt = null,
                        errorCode = if (status == "COMPLETED") null else issues.firstOrNull()?.code,
                        progressJson = progressJson,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            } else {
                jobDao.updateProgress(
                    jobId = existing.id,
                    stage = stage,
                    status = status,
                    stagedPath = null,
                    targetRevision = null,
                    errorCode = if (status == "COMPLETED") null else issues.firstOrNull()?.code,
                    progressJson = progressJson,
                    updatedAt = now,
                )
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            if (strictReceipt) throw error
            builder.warnings += "恢复回执写入失败（不影响已提交数据）：${error.message}"
        }
    }

    private fun receiptRequestKey(manifestHash: String): String = "restore:$manifestHash"

    /** Only strict service receipts are authoritative; older best-effort receipts are not invented counts. */
    suspend fun findServiceReceipt(userId: Long, manifestHash: String): Pair<DataManagementCounts, Boolean>? {
        val row = jobDao.findByRequestKey(userId, receiptRequestKey(manifestHash)) ?: return null
        val json = Json.parseToJsonElement(row.progressJson).jsonObject
        val counts = json["serviceCounts"] as? JsonObject ?: return null
        fun n(key: String) = (counts[key] as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it >= 0 }
            ?: throw IllegalStateException("INVALID_RESTORE_RECEIPT")
        return DataManagementCounts(documents = n("documents"), notes = n("notes"), annotations = n("annotations"),
            inkPages = n("inkPages"), added = n("added"), reused = n("reused"), conflicts = n("conflicts"),
            damaged = n("damaged"), retryable = n("retryable"), skipped = n("skipped")) to (row.stage == "APPLIED")
    }

    private suspend fun matchesIdentity(userId: Long, expected: SessionIdentity?): Boolean =
        expected == null || (expected.userId == userId && identityProvider?.invoke() == expected)

    private suspend fun checkIdentity(userId: Long, expected: SessionIdentity?) {
        if (!matchesIdentity(userId, expected)) throw IllegalStateException("SESSION_CHANGED")
    }

    private fun identityRejected() = RestoreOutcome.Rejected("SESSION_CHANGED", "登录会话已改变，请重新预览。")

    // ---------------------------------------------------------------- 只读查看

    /** 恢复副本/待关联文档的笔记只读视图（不经过 Repo；用于验证“笔记可读、不可原文检索”）。 */
    data class RestoredNoteView(
        val documentId: String,
        val notebookId: String?,
        val body: String,
        val annotations: List<RestoredAnnotationView>,
    )

    data class RestoredAnnotationView(
        val id: String,
        val kind: String,
        val quote: String?,
        val noteText: String?,
        val locatorJson: String,
    )

    /**
     * 读取本协调器创建的“来源待关联/恢复副本”文档的笔记。
     *
     * 这类文档 import_status=PENDING，ReaderAccessPolicy 不会把它们当可读正文，
     * 因此不会进入阅读/索引路径；这里给恢复报告与后续“关联原书”流程一个只读入口。
     */
    suspend fun loadRestoredNote(userId: Long, documentId: String): RestoredNoteView? {
        if (userId <= 0L || documentId.isBlank()) return null
        val document = catalog.findDocument(documentId) ?: return null
        if (document.ownerUserId != userId) return null
        if (!isRestoreDocumentId(documentId)) return null
        if (document.importStatus == "READY") return null
        val notebook = notebookDao.findNotebookForDocument(userId, documentId)
        val rows = annotationDao.observeAnnotations(userId, documentId).first()
        return RestoredNoteView(
            documentId = documentId,
            notebookId = notebook?.id,
            body = notebook?.bodyMarkdown.orEmpty(),
            annotations = rows.map { entity ->
                RestoredAnnotationView(
                    id = entity.id,
                    kind = entity.kind,
                    quote = entity.quoteSnapshot,
                    noteText = entity.noteText,
                    locatorJson = entity.locatorJson,
                )
            },
        )
    }

    // ---------------------------------------------------------------- 工具

    private class ReportBuilder {
        var documentsAdded = 0
        var documentsReused = 0
        var documentsNoSource = 0
        var notesAdded = 0
        var notesReused = 0
        var noteCopies = 0
        var annotationsAdded = 0
        var annotationsReused = 0
        var inkPagesAdded = 0
        var inkPagesReused = 0
        var conflicts = 0
        var damaged = 0
        var retryable = 0
        var skipped = 0
        val issues = mutableListOf<RestoreIssue>()
        val warnings = mutableListOf<String>()
        val documentIdMap = linkedMapOf<String, String>()
        val copyDocumentIds = mutableListOf<String>()
        val noSourceDocumentIds = mutableListOf<String>()

        fun markDamaged(code: String, detail: String, path: String? = null) {
            damaged += 1
            issues += RestoreIssue(code, detail, retryable = false, path = path)
        }

        fun markRetryable(code: String, detail: String, path: String? = null) {
            retryable += 1
            issues += RestoreIssue(code, detail, retryable = true, path = path)
        }

        fun build(
            restoreId: String,
            exportId: String,
            manifestHash: String,
            userId: Long,
            stage: RestoreStage,
        ): RestoreReport = RestoreReport(
            restoreId = restoreId,
            exportId = exportId,
            manifestHash = manifestHash,
            userId = userId,
            stage = stage,
            documentsAdded = documentsAdded,
            documentsReused = documentsReused,
            documentsNoSource = documentsNoSource,
            notesAdded = notesAdded,
            notesReused = notesReused,
            noteCopies = noteCopies,
            annotationsAdded = annotationsAdded,
            annotationsReused = annotationsReused,
            inkPagesAdded = inkPagesAdded,
            inkPagesReused = inkPagesReused,
            conflicts = conflicts,
            damaged = damaged,
            retryable = retryable,
            skipped = skipped,
            documentIdMap = documentIdMap.toMap(),
            copyDocumentIds = copyDocumentIds.toList(),
            noSourceDocumentIds = noSourceDocumentIds.toList(),
            issues = issues.toList(),
            warnings = warnings.distinct(),
        )
    }

    private fun reject(issues: List<BackupIssue>): RestoreOutcome.Rejected {
        val first = issues.firstOrNull()
            ?: return RestoreOutcome.Rejected(BackupIssueCodes.INVALID_MANIFEST, "备份包校验失败")
        return RestoreOutcome.Rejected(first.code, issues.joinToString("; ") { it.toString() })
    }

    private fun resolvePackageDirectory(packageDirectory: File): File? {
        val canonicalRoot = runCatching { packageRoot.canonicalFile }.getOrNull() ?: return null
        val candidate = runCatching { packageDirectory.canonicalFile }.getOrNull() ?: return null
        if (!candidate.isDirectory) return null
        return if (isInside(canonicalRoot, candidate)) candidate else null
    }

    private fun isInside(root: File, child: File): Boolean {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return false
        val canonicalChild = runCatching { child.canonicalFile }.getOrNull() ?: return false
        if (canonicalChild == canonicalRoot) return false
        return canonicalChild.path.startsWith(canonicalRoot.path + File.separator)
    }

    private fun ensureDirectory(directory: File): File {
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
            throw IllegalStateException("无法创建目录：${directory.name}")
        }
        if (!directory.isDirectory) throw IllegalStateException("路径不是目录：${directory.name}")
        return directory
    }

    private fun deleteRecursively(target: File, allowedRoot: File): Boolean {
        val canonicalRoot = runCatching { allowedRoot.canonicalFile }.getOrNull() ?: return false
        val canonicalTarget = runCatching { target.canonicalFile }.getOrNull() ?: return false
        if (canonicalTarget == canonicalRoot) return false
        if (!canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)) return false
        if (!canonicalTarget.exists()) return false
        canonicalTarget.listFiles()?.forEach { child -> deleteRecursively(child, allowedRoot) }
        return canonicalTarget.delete() || !canonicalTarget.exists()
    }

    private fun placeholderDocumentId(userId: Long, document: BackupDocument): String =
        NO_SOURCE_PREFIX + BackupPackageVerifier.sha256(placeholderIdentity(userId, document)).take(32)

    private fun placeholderIdentity(userId: Long, document: BackupDocument): String =
        listOf(
            userId.toString(),
            document.sourceSha256 ?: document.contentRevision ?: "${document.exportDocumentId}|${document.title}",
        ).joinToString("|")

    private fun placeholderRevision(document: BackupDocument): String =
        document.sourceSha256
            ?: document.contentRevision
            ?: ("restore-" + BackupPackageVerifier.sha256("${document.exportDocumentId}|${document.title}").take(32))

    private fun copyDocumentId(userId: Long, restoreId: String, exportDocumentId: String): String =
        COPY_PREFIX + BackupPackageVerifier.sha256("$userId|$restoreId|$exportDocumentId").take(32)

    private fun restoredNotebookId(userId: Long, documentId: String): String =
        "notebook-restore-" + BackupPackageVerifier.sha256("$userId|$documentId").take(32)

    private fun restoredAnnotationId(userId: Long, documentId: String, exportAnnotationId: String): String =
        "annotation-restore-" +
            BackupPackageVerifier.sha256("$userId|$documentId|$exportAnnotationId").take(32)

    private fun copyTitle(title: String): String {
        val suffix = "（恢复副本）"
        val room = (MAX_TITLE_CHARS - suffix.length).coerceAtLeast(1)
        return title.take(room) + suffix
    }

    private fun isRestoreDocumentId(documentId: String): Boolean =
        documentId.startsWith(NO_SOURCE_PREFIX) || documentId.startsWith(COPY_PREFIX)

    companion object {
        /** 无同 hash 原书时的占位文档前缀（“来源待关联”）。 */
        const val NO_SOURCE_PREFIX: String = "restore-nosource-"

        /** 冲突笔记副本所在文档前缀。 */
        const val COPY_PREFIX: String = "restore-copy-"

        /**
         * NO_SOURCE 在 schema/AccessPolicy 中不存在：这里用现有字段组合
         * `import_status=PENDING`（非 READY → 不可打开、不可索引）+ `blob_path=NULL` + `file_sha256=NULL`。
         */
        const val NO_SOURCE_IMPORT_STATUS: String = "PENDING"

        const val RESTORE_EXTRACTOR_VERSION: String = "backup-restore-v1"

        private const val MAX_TITLE_CHARS = 512
        private const val MAX_FORMAT_CHARS = 64
        private const val MAX_QUOTE_CHARS = 8192
        private const val MAX_BODY_CHARS = 8_000_000
        private const val MAX_NOTE_FILE_BYTES = 48L * 1024 * 1024
        private const val MAX_NOTE_BODY_TOTAL_BYTES = 128L * 1024 * 1024
        private const val MAX_INK_FILE_BYTES = 16L * 1024 * 1024
        private const val MAX_ANNOTATIONS_FILE_BYTES = 96L * 1024 * 1024
    }
}
