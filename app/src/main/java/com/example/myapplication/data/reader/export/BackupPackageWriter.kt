package com.example.myapplication.data.reader.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.ReaderAnnotationEntity
import com.example.myapplication.data.local.entity.ReaderDocumentEntity
import com.example.myapplication.data.reader.ink.InkManifest
import com.example.myapplication.data.reader.ink.InkManifestCodec
import com.example.myapplication.data.reader.ink.InkStore
import com.example.myapplication.data.reader.notes.AnnotationColor
import com.example.myapplication.data.reader.notes.AnnotationStyle
import com.example.myapplication.domain.reader.ReaderAccessDecision
import com.example.myapplication.domain.reader.ReaderAccessPolicy
import com.example.myapplication.domain.reader.ReaderDocumentAccessRecord
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * 备份包写出器（W5-05）。
 *
 * 输出目录固定为 `cache/exports/<exportId>/`（进程内由 [exportsRoot] 注入，生产为 cache/exports）：
 * ```
 * manifest.json
 * annotations.json
 * notes/doc-1.md
 * ink/doc-1/page-0.json
 * ```
 *
 * 约束：
 * - 不导出原书正文/聊天/AI key（结构上不读这些表；manifest 校验也拒绝 includeSources/includeChatHistory）；
 * - 墨迹按**当前 userId** 读取（复用 [InkStore] 的 userId API），不会读到他人笔迹；
 * - 所有写入路径都用 canonicalPath 校验落在 exports 根内；
 * - 先写 `.staging-<exportId>`，全部成功后再 rename 成正式目录，失败清理残留；
 * - 分享路径由 [exportDirectory] 给出，FileProvider 只暴露 `cache/exports`（res/xml/file_paths.xml）。
 */
class BackupPackageWriter(
    private val database: AppDatabase,
    private val inkStore: InkStore,
    exportsRoot: File,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxPackageBytes: Long = BackupPackageVerifier.DEFAULT_MAX_PACKAGE_BYTES,
) {
    private val exportsRoot: File = exportsRoot.absoluteFile

    private val root: File get() = exportsRoot.canonicalFile

    private val catalog get() = database.readerCatalogDao()
    private val annotationDao get() = database.readerAnnotationDao()
    private val notebookDao get() = database.readerNotebookDao()
    private val stateDao get() = database.readerStateDao()

    /** 预览：不写任何文件，只统计内容与预计大小。 */
    suspend fun preview(request: ExportRequest): ExportPreview {
        val plan = collect(request)
        val previewId = newExportId(clock())
        return buildPreview(previewId, plan, request)
    }

    /** 执行导出；完整写完 manifest 才返回 [ExportOutcome.Completed]。 */
    suspend fun export(request: ExportRequest): ExportOutcome {
        val validation = request.validationIssues()
        if (validation.isNotEmpty()) {
            return ExportOutcome.Rejected(
                validation.first().code,
                validation.joinToString("; ") { it.toString() },
            )
        }
        val plan = collect(request)
        if (plan.documents.isEmpty()) {
            return ExportOutcome.Rejected(
                BackupIssueCodes.NO_EXPORTABLE_DOCUMENT,
                plan.rejections.joinToString("; ") { "${it.documentId}: ${it.code}/${it.detail}" }
                    .ifEmpty { "没有可导出的文档" },
            )
        }
        val now = clock()
        val exportId = newExportId(now)
        val directory = exportDirectory(exportId)
            ?: return ExportOutcome.Rejected(BackupIssueCodes.EXPORT_FAILED, "导出目录越界")
        if (directory.exists()) {
            return ExportOutcome.Rejected(BackupIssueCodes.EXPORT_EXISTS, "导出目录已存在：$exportId")
        }
        val staging = File(root, ".staging-$exportId")
        return try {
            deleteRecursively(staging, root)
            ensureDirectory(staging)

            val files = mutableListOf<BackupFile>()
            if (request.includeNotes) {
                plan.documents.forEach { document ->
                    val markdown = buildMarkdown(document, exportId, now)
                    files += writeText(
                        staging,
                        "notes/${document.exportDocumentId}.md",
                        markdown,
                        BackupFileKind.NOTE,
                    )
                }
            }
            val annotationEntries = if (request.includeAnnotations) {
                buildAnnotationEntries(plan.documents)
            } else {
                emptyList()
            }
            if (request.includeAnnotations) {
                val encoded = BackupAnnotationCodec.encode(annotationEntries)
                ensureNoSensitiveKeys(encoded, "annotations.json")
                files += writeText(staging, BackupSchema.ANNOTATIONS_FILE, encoded, BackupFileKind.ANNOTATIONS)
            }
            if (request.includeInk) {
                plan.documents.forEach { document ->
                    files += writeInkPages(staging, document)
                }
            }
            if (files.isEmpty()) {
                // 什么都没选（或所有文档都没可导出内容）时不生成空包，避免 manifest.files 为空。
                deleteRecursively(staging, root)
                return ExportOutcome.Rejected(
                    BackupIssueCodes.INVALID_REQUEST,
                    "没有可写出的内容：请至少选择笔记/标注/手写之一",
                )
            }

            val manifest = buildManifest(
                exportId = exportId,
                createdAt = now,
                plan = plan,
                annotationEntries = annotationEntries,
                files = files,
            )
            val manifestText = BackupManifestCodec.encode(manifest)
            ensureNoSensitiveKeys(manifestText, BackupSchema.MANIFEST_FILE)
            val manifestBytes = manifestText.toByteArray(StandardCharsets.UTF_8)
            if (manifestBytes.size.toLong() > BackupPackageVerifier.MAX_MANIFEST_BYTES) {
                throw IllegalStateException("manifest.json 超过大小上限")
            }
            // manifest 不写进自己的 files 列表（避免自引用 hash）。
            writeRaw(staging, BackupSchema.MANIFEST_FILE, manifestBytes)

            ensureDirectory(root)
            if (!staging.renameTo(directory)) {
                throw IllegalStateException("无法原子发布导出目录")
            }
            val preview = buildPreview(exportId, plan, request)
            ExportOutcome.Completed(
                preview = preview,
                directory = directory,
                manifestHash = BackupPackageVerifier.sha256(manifestText),
                rejectedDocuments = plan.rejections,
            )
        } catch (cancel: CancellationException) {
            deleteRecursively(staging, root)
            throw cancel
        } catch (error: Exception) {
            deleteRecursively(staging, root)
            ExportOutcome.Rejected(
                BackupIssueCodes.EXPORT_FAILED,
                "导出失败：${error.message}",
            )
        }
    }

    /** 分享/清理用的导出目录（必须落在 exports 根内）。 */
    fun exportDirectory(exportId: String): File? {
        if (!EXPORT_ID.matches(exportId)) return null
        val rootDirectory = root
        val candidate = File(rootDirectory, exportId)
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        val prefix = rootDirectory.path + File.separator
        return if (canonical.path.startsWith(prefix)) canonical else null
    }

    /** 清理指定导出（只删 exports 根内的目录）。 */
    fun cleanup(exportId: String): Boolean {
        val directory = exportDirectory(exportId) ?: return false
        return deleteRecursively(directory, root)
    }

    /** 清理遗留的 `.staging-*` 临时目录（被杀进程留下的残留）。 */
    fun cleanupStaleStaging(maxAgeMs: Long = DEFAULT_STAGING_MAX_AGE_MS): Int {
        val rootDirectory = runCatching { root }.getOrNull() ?: return 0
        val children = rootDirectory.listFiles() ?: return 0
        val cutoff = clock() - maxAgeMs
        var removed = 0
        children.forEach { child ->
            if (child.name.startsWith(STAGING_PREFIX) && child.lastModified() < cutoff) {
                if (deleteRecursively(child, rootDirectory)) removed += 1
            }
        }
        return removed
    }

    /** 单个备份压缩包的路径（`cache/exports/<exportId>.zip`），仍受 exports 根约束。 */
    fun zipFile(exportId: String): File? {
        if (!EXPORT_ID.matches(exportId)) return null
        val rootDirectory = root
        val candidate = File(rootDirectory, "$exportId.zip")
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        val prefix = rootDirectory.path + File.separator
        return if (canonical.path.startsWith(prefix)) canonical else null
    }

    /**
     * 把已完成的导出目录打成单文件 zip（分享/迁移用）。
     *
     * 失败只返回 Rejected，不动已完成的目录——导出结果本身仍然可用。
     */
    fun packageZip(exportId: String, archive: BackupArchive = BackupArchive()): BackupArchive.Outcome {
        val directory = exportDirectory(exportId)
            ?: return BackupArchive.Outcome.Rejected("INVALID_ARGUMENT", "导出目录不存在")
        val target = zipFile(exportId)
            ?: return BackupArchive.Outcome.Rejected("INVALID_ARGUMENT", "导出路径无效")
        return archive.zipDirectory(directory, target)
    }

    /**
     * 分享用 content:// URI 列表：只包含该导出目录内的文件。
     *
     * authority 由 AndroidManifest 的 `${applicationId}.fileprovider` 提供，
     * 而 `res/xml/file_paths.xml` 只暴露 `cache/exports`，因此即使有人传入别的路径也拿不到 URI。
     */
    fun shareUris(context: Context, exportId: String): List<Uri> {
        val directory = exportDirectory(exportId) ?: return emptyList()
        if (!directory.isDirectory) return emptyList()
        val authority = "${context.packageName}.fileprovider"
        return directory.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.name }
            .mapNotNull { file ->
                runCatching { FileProvider.getUriForFile(context, authority, file) }.getOrNull()
            }
            .toList()
    }

    /** 分享单个文件（压缩包）的 URI；路径必须落在 exports 根内。 */
    fun shareFile(context: Context, file: File): Uri? {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val prefix = root.path + File.separator
        if (!canonical.path.startsWith(prefix)) return null
        val authority = "${context.packageName}.fileprovider"
        return runCatching { FileProvider.getUriForFile(context, authority, canonical) }.getOrNull()
    }

    // ---------------------------------------------------------------- 采集

    private suspend fun collect(request: ExportRequest): ExportPlan {
        val userId = request.userId
        val rejections = mutableListOf<ExportRejection>()
        val warnings = mutableListOf<String>()
        val inkByDocument = if (request.includeInk) collectInk(userId) else emptyMap()
        val documents = mutableListOf<PlannedDocument>()

        request.normalizedDocumentIds().forEachIndexed { index, documentId ->
            val document = catalog.findDocument(documentId)
            if (document == null) {
                rejections += ExportRejection(documentId, "NOT_FOUND", "文档不存在或已在回收站")
                return@forEachIndexed
            }
            val decision = ReaderAccessPolicy.canRead(userId, document.toAccessRecord())
            if (decision is ReaderAccessDecision.Denied) {
                rejections += ExportRejection(documentId, decision.code, "当前用户无权导出该文档")
                return@forEachIndexed
            }
            if (document.kind == "IMPORTED" && document.importStatus != "READY") {
                rejections += ExportRejection(documentId, "NOT_READY", "文档尚未就绪，不能导出")
                return@forEachIndexed
            }

            val notebook = notebookDao.findNotebookForDocument(userId, documentId)
            val noteBody = if (request.includeNotes) notebook?.bodyMarkdown.orEmpty() else ""

            val sectionTitles = runCatching {
                catalog.findSections(documentId, document.contentRevision)
                    .associate { it.id to it.title }
            }.getOrDefault(emptyMap())

            val entities = if (request.includeAnnotations) {
                annotationDao.observeAnnotations(userId, documentId).first()
            } else {
                emptyList()
            }
            val locators = linkedMapOf<String, ReaderLocator>()
            val exportable = mutableListOf<ReaderAnnotationEntity>()
            entities.forEach { entity ->
                val locator = ReaderLocatorCodec.decodeOrNull(entity.locatorJson)
                if (locator == null) {
                    warnings += "标注 ${entity.id} 的 locator 无法解析，已跳过"
                    return@forEach
                }
                locators[entity.id] = locator
                exportable += entity
            }

            val lastLocator = stateDao.find(userId, documentId)?.locatorJson?.let(ReaderLocatorCodec::decodeOrNull)
            val inkPages = inkByDocument[documentId].orEmpty().sortedWith(
                compareBy({ it.pageIndex }, { it.sectionId }),
            )
            documents += PlannedDocument(
                document = document,
                exportDocumentId = "doc-${index + 1}",
                noteBody = noteBody,
                annotationEntities = exportable,
                locatorByEntity = locators,
                inkPages = inkPages,
                lastLocator = lastLocator,
                sectionTitles = sectionTitles,
            )
        }
        return ExportPlan(documents, rejections, warnings)
    }

    /** 按 userId 遍历该用户墨迹根（不会读到其他用户目录）。 */
    private fun collectInk(userId: Long): Map<String, List<PlannedInkPage>> {
        val userDirectory = runCatching { inkStore.userDirectory(userId) }.getOrNull() ?: return emptyMap()
        if (!userDirectory.isDirectory) return emptyMap()
        val canonicalRoot = runCatching { userDirectory.canonicalFile }.getOrNull() ?: return emptyMap()
        val prefix = canonicalRoot.path + File.separator
        val result = linkedMapOf<String, MutableList<PlannedInkPage>>()
        canonicalRoot.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            if (!file.name.startsWith("page-") || !file.name.endsWith(".json")) return@forEach
            val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return@forEach
            if (!canonical.path.startsWith(prefix)) return@forEach
            val raw = runCatching { canonical.readBytes() }.getOrNull() ?: return@forEach
            val manifest = runCatching {
                InkManifestCodec.decode(String(raw, StandardCharsets.UTF_8))
            }.getOrNull() ?: return@forEach
            result.getOrPut(manifest.documentId) { mutableListOf() }
                .add(PlannedInkPage(manifest.pageIndex, manifest.sectionId, manifest))
        }
        return result
    }

    // ---------------------------------------------------------------- 渲染

    private fun buildMarkdown(
        document: PlannedDocument,
        exportId: String,
        exportedAt: Long,
    ): String {
        val portableNotebookId = portableNotebookId(document.exportDocumentId)
        val documentLocator = document.lastLocator
            ?.let { PortableReferenceMapper.toPortable(it, document.exportDocumentId, portableNotebookId) }
            ?.let(ReaderLocatorCodec::encode)

        val groupedEntries = linkedMapOf<String, MutableList<MarkdownEntry>>()
        var index = 0
        document.annotationEntities.forEach { entity ->
            val locator = document.locatorByEntity[entity.id] ?: return@forEach
            val portable = PortableReferenceMapper.toPortable(
                locator,
                document.exportDocumentId,
                portableNotebookId,
            ) ?: return@forEach
            index += 1
            // 章节归属：优先标注自己绑定的 sectionId，其次 locator 里的 sectionId
            // （旧标注可能没有 section_id，只有 locator）。导出只带章节标题，
            // 恢复端按标题唯一匹配重映射，避免把导出端的内部 section id 带进来。
            val sectionKey = entity.sectionId?.takeIf { it.isNotBlank() } ?: locator.sectionId
            val sectionTitle = document.sectionTitles[sectionKey]
            val pageIndex = (locator as? ReaderLocator.Fixed)?.pageIndex
            val sourceLine = MarkdownExporter.readableSource(
                title = document.document.title,
                sectionTitle = sectionTitle,
                pageIndex = pageIndex,
                progression = locator.progression,
            )
            val style = AnnotationStyle.decode(entity.styleJson)
            val entry = MarkdownEntry(
                index = index,
                typeLabel = MarkdownExporter.typeLabel(entity.kind),
                colorLabel = style.color.label,
                sourceLine = sourceLine,
                locatorJson = ReaderLocatorCodec.encode(portable),
                quote = entity.quoteSnapshot,
                noteText = entity.noteText,
            )
            groupedEntries.getOrPut(sectionKey) { mutableListOf() }.add(entry)
        }

        val sections = groupedEntries.map { (sectionId, entries) ->
            MarkdownSectionBlock(
                title = document.sectionTitles[sectionId] ?: if (sectionId.isBlank()) "标注" else "其他位置",
                locatorJson = null,
                entries = entries,
            )
        }

        val headerSource = MarkdownExporter.readableSource(
            title = document.document.title,
            sectionTitle = null,
            pageIndex = (document.lastLocator as? ReaderLocator.Fixed)?.pageIndex,
            progression = document.lastLocator?.progression,
        )
        return MarkdownExporter.render(
            MarkdownDocumentInput(
                title = document.document.title,
                author = document.document.author,
                format = document.document.format,
                sourceLine = headerSource,
                documentLocatorJson = documentLocator,
                sections = sections,
                noteBody = document.noteBody,
                exportId = exportId,
                exportedAt = exportedAt,
            ),
        )
    }

    private fun buildAnnotationEntries(documents: List<PlannedDocument>): List<BackupAnnotationEntry> {
        val entries = mutableListOf<BackupAnnotationEntry>()
        documents.forEach { document ->
            val portableNotebookId = portableNotebookId(document.exportDocumentId)
            document.annotationEntities.forEach { entity ->
                val locator = document.locatorByEntity[entity.id] ?: return@forEach
                val portable = PortableReferenceMapper.toPortable(
                    locator,
                    document.exportDocumentId,
                    portableNotebookId,
                ) ?: return@forEach
                val style = AnnotationStyle.decode(entity.styleJson)
                val rects = (portable as? ReaderLocator.Fixed)?.rects.orEmpty()
                val inkPage = if (entity.kind == "INK") (portable as? ReaderLocator.Fixed)?.pageIndex else null
                val sectionKey = entity.sectionId?.takeIf { it.isNotBlank() } ?: locator.sectionId
                entries += BackupAnnotationEntry(
                    id = entity.id,
                    documentId = document.exportDocumentId,
                    type = entity.kind,
                    quote = entity.quoteSnapshot,
                    color = runCatching { style.color.name }.getOrNull() ?: AnnotationColor.YELLOW.name,
                    underline = style.underline,
                    sectionId = entity.sectionId,
                    sectionTitle = document.sectionTitles[sectionKey],
                    locator = portable,
                    contentRevision = locator.contentRevision,
                    createdAt = entity.createdAt,
                    updatedAt = entity.updatedAt,
                    noteText = entity.noteText,
                    rects = rects,
                    inkPage = inkPage,
                )
            }
        }
        return entries
    }

    private fun buildManifest(
        exportId: String,
        createdAt: Long,
        plan: ExportPlan,
        annotationEntries: List<BackupAnnotationEntry>,
        files: List<BackupFile>,
    ): BackupManifest {
        val filesByPath = files.associateBy { it.path }
        val documents = plan.documents.map { planned ->
            val exportIdForDocument = planned.exportDocumentId
            val notesPath = "notes/$exportIdForDocument.md"
            val annotationsForDocument = annotationEntries.count { it.documentId == exportIdForDocument }
            val inkCount = files.count { it.kind == BackupFileKind.INK && it.path.startsWith("ink/$exportIdForDocument/") }
            BackupDocument(
                exportDocumentId = exportIdForDocument,
                title = planned.document.title,
                author = planned.document.author,
                format = planned.document.format,
                sourceIncluded = false,
                sourceSha256 = planned.document.fileSha256?.takeIf { HASH.matches(it) },
                contentRevision = planned.document.contentRevision,
                notesFile = if (filesByPath.containsKey(notesPath)) notesPath else null,
                noteChars = planned.noteBody.length,
                annotationCount = annotationsForDocument,
                inkPageCount = inkCount,
                lastLocator = planned.lastLocator?.let {
                    PortableReferenceMapper.toPortable(
                        it,
                        exportIdForDocument,
                        portableNotebookId(exportIdForDocument),
                    )
                },
            )
        }
        val singleSource = plan.documents.singleOrNull()?.document?.fileSha256?.takeIf { HASH.matches(it) }
        return BackupManifest(
            schemaVersion = BackupSchema.VERSION,
            exportId = exportId,
            createdAt = createdAt,
            appVersion = appVersion,
            sourceContentHash = singleSource,
            documents = documents,
            files = files,
            options = BackupOptions(includeSources = false, includeChatHistory = false),
        )
    }

    private fun buildPreview(
        exportId: String,
        plan: ExportPlan,
        request: ExportRequest,
    ): ExportPreview {
        val exportedAt = clock()
        val annotationEntries = if (request.includeAnnotations) buildAnnotationEntries(plan.documents) else emptyList()
        var notes = 0
        var ink = 0
        var estimated = 0L
        val documents = plan.documents.map { planned ->
            val markdown = if (request.includeNotes) buildMarkdown(planned, exportId, exportedAt) else ""
            val noteBytes = markdown.toByteArray(StandardCharsets.UTF_8).size.toLong()
            val inkBytes = planned.inkPages.sumOf { page ->
                InkManifestCodec.encode(portableInk(page.manifest, planned.exportDocumentId))
                    .toByteArray(StandardCharsets.UTF_8).size.toLong()
            }
            val annotations = annotationEntries.count { it.documentId == planned.exportDocumentId }
            if (markdown.isNotEmpty()) notes += 1
            ink += planned.inkPages.size
            estimated += noteBytes + inkBytes
            ExportDocumentPreview(
                documentId = planned.document.id,
                exportDocumentId = planned.exportDocumentId,
                title = planned.document.title,
                format = planned.document.format,
                sourceSha256 = planned.document.fileSha256?.takeIf { HASH.matches(it) },
                noteChars = planned.noteBody.length,
                annotationCount = annotations,
                inkPageCount = planned.inkPages.size,
                estimatedBytes = noteBytes + inkBytes,
            )
        }
        val annotationsBytes = if (request.includeAnnotations) {
            BackupAnnotationCodec.encode(annotationEntries).toByteArray(StandardCharsets.UTF_8).size.toLong()
        } else {
            0L
        }
        val warnings = plan.warnings + plan.rejections.map { "跳过 ${it.documentId}：${it.code} ${it.detail}" }
        return ExportPreview(
            exportId = exportId,
            documents = documents,
            noteFileCount = notes,
            annotationCount = annotationEntries.size,
            inkPageCount = ink,
            estimatedBytes = estimated + annotationsBytes,
            options = BackupOptions(includeSources = false, includeChatHistory = false),
            warnings = warnings,
        )
    }

    // ---------------------------------------------------------------- 写文件

    private fun writeInkPages(staging: File, document: PlannedDocument): List<BackupFile> {
        val files = mutableListOf<BackupFile>()
        val usedNames = mutableSetOf<String>()
        document.inkPages.forEach { page ->
            val bytes = InkManifestCodec.encode(portableInk(page.manifest, document.exportDocumentId))
                .toByteArray(StandardCharsets.UTF_8)
            var name = "page-${page.pageIndex}"
            if (!usedNames.add(name)) {
                name = "$name-${BackupPackageVerifier.sha256(page.sectionId).take(8)}"
                usedNames += name
            }
            files += writeBytes(
                staging,
                "ink/${document.exportDocumentId}/$name.json",
                bytes,
                BackupFileKind.INK,
            )
        }
        return files
    }

    private fun portableInk(manifest: InkManifest, exportDocumentId: String): InkManifest =
        manifest.copy(documentId = exportDocumentId)

    private fun writeText(staging: File, relativePath: String, text: String, kind: BackupFileKind): BackupFile =
        writeBytes(staging, relativePath, text.toByteArray(StandardCharsets.UTF_8), kind)

    private fun writeBytes(
        staging: File,
        relativePath: String,
        bytes: ByteArray,
        kind: BackupFileKind,
    ): BackupFile {
        val target = BackupPackageVerifier.resolveInside(staging, relativePath)
            ?: throw IllegalStateException("导出路径越界：$relativePath")
        target.parentFile?.let(::ensureDirectory)
        FileOutputStream(target).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        return BackupFile(
            path = relativePath,
            sha256 = BackupPackageVerifier.sha256(bytes),
            bytes = bytes.size.toLong(),
            kind = kind,
        )
    }

    private fun writeRaw(staging: File, relativePath: String, bytes: ByteArray) {
        val target = BackupPackageVerifier.resolveInside(staging, relativePath)
            ?: throw IllegalStateException("导出路径越界：$relativePath")
        target.parentFile?.let(::ensureDirectory)
        FileOutputStream(target).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    private fun ensureNoSensitiveKeys(rawJson: String, fileName: String) {
        val hits = BackupPrivacyGuard.findSensitiveKeys(rawJson)
        if (hits.isNotEmpty()) {
            throw IllegalStateException("$fileName 含禁止的敏感字段：${hits.joinToString(",")}")
        }
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

    private fun newExportId(now: Long): String {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        return "backup-$now-$suffix"
    }

    private fun portableNotebookId(exportDocumentId: String): String = "nb-$exportDocumentId"

    private fun ReaderDocumentEntity.toAccessRecord(): ReaderDocumentAccessRecord =
        ReaderDocumentAccessRecord(
            documentId = id,
            kind = kind,
            ownerUserId = ownerUserId,
            contentRevision = contentRevision,
            accessEpoch = accessEpoch,
            importStatus = importStatus,
            deletedAt = deletedAt,
        )

    private data class PlannedInkPage(
        val pageIndex: Int,
        val sectionId: String,
        val manifest: InkManifest,
    )

    private data class PlannedDocument(
        val document: ReaderDocumentEntity,
        val exportDocumentId: String,
        val noteBody: String,
        val annotationEntities: List<ReaderAnnotationEntity>,
        val locatorByEntity: Map<String, ReaderLocator>,
        val inkPages: List<PlannedInkPage>,
        val lastLocator: ReaderLocator?,
        val sectionTitles: Map<String, String>,
    )

    private data class ExportPlan(
        val documents: List<PlannedDocument>,
        val rejections: List<ExportRejection>,
        val warnings: List<String>,
    )

    companion object {
        /** `cache/exports` 目录名；生产由 AppContainer/调用方拼到 cacheDir 下。 */
        const val EXPORTS_DIRECTORY: String = "exports"
        const val STAGING_PREFIX: String = ".staging-"
        const val DEFAULT_STAGING_MAX_AGE_MS: Long = 24L * 60 * 60 * 1000
        private val EXPORT_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        private val HASH = Regex("^[0-9a-f]{64}$")
    }
}
