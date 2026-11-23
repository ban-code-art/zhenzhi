package com.example.myapplication.data.ocr

import androidx.room.withTransaction
import com.example.myapplication.data.knowledge.index.IndexOutbox
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.ReaderContentRevisionEntity
import com.example.myapplication.data.local.entity.ReaderOutlineNodeEntity
import com.example.myapplication.data.local.entity.ReaderSectionEntity
import com.example.myapplication.data.reader.importer.ParserIO
import com.example.myapplication.data.reader.storage.ContentManifest
import com.example.myapplication.data.reader.storage.ContentManifestCodec
import com.example.myapplication.data.reader.storage.DocumentStorage
import com.example.myapplication.data.reader.storage.ManifestFile
import com.example.myapplication.data.reader.storage.ManifestFileKind
import com.example.myapplication.data.reader.notes.ReaderMutationHasher
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * OCR 结果发布（W5-03）：派生**新内容代次**，不覆盖源 PDF、不原地改旧代次。
 *
 * 流程：当前 revision 文件复制到 staging → 仅替换 OCR 页 HTML → manifest 重算 hash →
 * 发布目录 → 同一事务写 revision/sections/outline、切换文档当前代次、迁移阅读位置、
 * 登记新 generation 的索引任务。
 *
 * 门禁：只发布 `VERIFIED_TEXT` 且非空的页；`OCR_REVIEW_REQUIRED`/`NO_TEXT` 原样保留
 * 扫描提示，绝不把待复核文本当正文。
 *
 * 注释与书签只迁移到内容未变化的 section；被 OCR 替换页上的 Reflow 锚点保持旧代次，
 * 不做模糊重定位。来源引用作为不可变证据继续指向旧代次。
 */
class OcrRevisionPublisher(
    private val storage: DocumentStorage,
    private val database: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface Outcome {
        data class Published(
            val revision: String,
            val previousRevision: String,
            val pages: List<Int>,
            val indexGeneration: Long?,
        ) : Outcome

        data class AlreadyPublished(val revision: String) : Outcome
        data class NothingToDo(val reason: String) : Outcome
        data class Failed(val code: String, val message: String) : Outcome
    }

    suspend fun publish(documentId: String, checkpoint: OcrCheckpoint): Outcome {
        val catalog = database.readerCatalogDao()
        val document = catalog.findDocument(documentId)
            ?: return Outcome.Failed("DOCUMENT_NOT_FOUND", "文档不存在或已删除")
        val current = try {
            storage.openRevision(documentId, document.contentRevision, verifyFiles = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return Outcome.Failed("REVISION_UNREADABLE", error.javaClass.simpleName)
        } ?: return Outcome.Failed("REVISION_MISSING", "当前内容代次不可读")
        val manifest = current.manifest
        if (checkpoint.documentId != documentId ||
            checkpoint.sourceFileHash != manifest.sourceSha256 ||
            checkpoint.extractorVersion != EXTRACTOR_VERSION
        ) {
            return Outcome.Failed("OCR_CHECKPOINT_STALE", "OCR 检查点与当前源文件不一致，拒绝发布")
        }
        val pageSections = manifest.sections.filter { it.pageStart != null }.associateBy { it.pageStart!! }
        val eligible = checkpoint.pages
            .filter { it.quality == com.example.myapplication.domain.reader.ExtractionQuality.VERIFIED_TEXT }
            .filter { it.humanVerifiedByUserId == document.ownerUserId && it.humanVerifiedAt != null }
            .filter { it.text.isNotBlank() }
            .filter { pageSections.containsKey(it.pageIndex) }
            .filter { page -> isScannedPage(current.directory, pageSections.getValue(page.pageIndex).resourceHref) }
            .sortedBy { it.pageIndex }
        if (eligible.isEmpty()) return Outcome.NothingToDo("没有可发布的已验证 OCR 扫描页")

        val jobId = "ocr-$documentId"
        storage.deleteStagingJob(jobId)
        val provisionalRevision = "0".repeat(64)
        val staging = storage.stagingRevisionDirectory(jobId, provisionalRevision)
        if (!staging.mkdirs() && !staging.isDirectory) {
            return Outcome.Failed("OCR_STAGING_FAILED", "无法创建 OCR 发布暂存目录")
        }
        try {
            manifest.files.forEach { declared ->
                val source = File(current.directory, declared.relativePath.replace('/', File.separatorChar))
                val target = File(staging, declared.relativePath.replace('/', File.separatorChar))
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
            }
            eligible.forEach { page ->
                val section = pageSections.getValue(page.pageIndex)
                val target = File(staging, section.resourceHref.replace('/', File.separatorChar))
                val pageHtml = ParserIO.htmlDocument(
                    "第 ${page.pageIndex + 1} 页",
                    OcrPageHtml.body(
                        pageIndex = page.pageIndex,
                        result = page.toResult(pageTaskKey(checkpoint, page.pageIndex)),
                        modelVersion = checkpoint.modelVersion,
                        humanReviewed = page.humanVerifiedAt != null,
                    ),
                )
                target.parentFile?.mkdirs()
                target.writeText(pageHtml, Charsets.UTF_8)
            }
            val files = manifest.files.map { declared ->
                val file = File(staging, declared.relativePath.replace('/', File.separatorChar))
                ManifestFile(
                    relativePath = declared.relativePath,
                    sha256 = storage.sha256Of(file),
                    bytes = file.length(),
                    kind = declared.kind,
                )
            }
            val eligibleBySection = eligible.associateBy { pageSections.getValue(it.pageIndex).id }
            val sections = manifest.sections.map { section ->
                val page = eligibleBySection[section.id]
                if (page == null) section else section.copy(charCount = page.text.length)
            }
            val provisional = manifest.copy(
                contentRevision = provisionalRevision,
                extractorVersion = EXTRACTOR_VERSION,
                files = files,
                sections = sections,
            )
            val revision = ContentManifestCodec.canonicalHash(provisional)
            val publishedManifest = provisional.copy(contentRevision = revision)
            storage.writeManifest(staging, publishedManifest)
            val published = storage.publishRevision(staging, publishedManifest)

            if (catalog.findContentRevision(documentId, revision) != null) {
                return Outcome.AlreadyPublished(revision)
            }
            val now = clock()
            val sourceRelative = files.firstOrNull { it.kind == ManifestFileKind.SOURCE }?.relativePath
            val sourceBlobPath = sourceRelative
                ?.let { File(published.directory, it.replace('/', File.separatorChar)).absolutePath }
            val sectionIds = sections.associate { it.id to "$documentId:$revision:${it.id}" }
            val oldToNewSectionId = sections.associate { section ->
                "$documentId:${document.contentRevision}:${section.id}" to sectionIds.getValue(section.id)
            }
            val modifiedOldSectionIds = eligible.map { page ->
                val sectionId = pageSections.getValue(page.pageIndex).id
                "$documentId:${document.contentRevision}:$sectionId"
            }.toSet()
            var indexGeneration: Long? = null
            database.withTransaction {
                catalog.insertContentRevision(
                    ReaderContentRevisionEntity(
                        documentId = documentId,
                        revision = revision,
                        sourceSha256 = manifest.sourceSha256,
                        sourceBlobPath = sourceBlobPath,
                        manifestPath = File(published.directory, DocumentStorage.MANIFEST_FILE_NAME).absolutePath,
                        extractorVersion = EXTRACTOR_VERSION,
                        status = "READY",
                        createdAt = now,
                    ),
                )
                catalog.insertSections(
                    sections.map { section ->
                        ReaderSectionEntity(
                            id = sectionIds.getValue(section.id),
                            documentId = documentId,
                            contentRevision = revision,
                            parentId = null,
                            orderIndex = section.orderIndex,
                            title = section.title,
                            depth = 0,
                            resourceHref = section.resourceHref,
                            canonicalPath = section.resourceHref,
                            charCount = section.charCount,
                            pageStart = section.pageStart,
                            pageEnd = section.pageEnd,
                        )
                    },
                )
                catalog.insertOutline(
                    manifest.outline.map { node ->
                        ReaderOutlineNodeEntity(
                            id = "$documentId:$revision:${node.nodeKey}",
                            documentId = documentId,
                            contentRevision = revision,
                            nodeKey = node.nodeKey,
                            parentId = node.parentKey?.let { "$documentId:$revision:$it" },
                            orderIndex = node.orderIndex,
                            title = node.title,
                            targetSectionId = node.targetSectionId?.let(sectionIds::get),
                            targetLocatorJson = node.targetLocatorJson,
                        )
                    },
                )
                remapUserAnchors(
                    userId = document.ownerUserId,
                    documentId = documentId,
                    oldRevision = document.contentRevision,
                    newRevision = revision,
                    sectionIdMap = oldToNewSectionId,
                    modifiedSectionIds = modifiedOldSectionIds,
                    now = now,
                )
                catalog.updateContentRevision(documentId, revision, EXTRACTOR_VERSION, now)
                database.readerStateDao().remapSectionPrefix(
                    documentId = documentId,
                    oldPrefix = "$documentId:${document.contentRevision}:",
                    newPrefix = "$documentId:$revision:",
                )
                val owner = document.ownerUserId
                if (owner != null && document.kind != "BUILTIN") {
                    val existingKnowledge = database.knowledgeDocumentDao().findByDocument(owner, documentId)
                    val generation = (existingKnowledge?.requestedGeneration ?: 0L) + 1
                    val registered = IndexOutbox(database, clock).registerDocument(
                        userId = owner,
                        documentId = documentId,
                        targetRevision = revision,
                        generation = generation,
                    )
                    if (registered !is IndexOutbox.RegisterResult.Failed) {
                        indexGeneration = generation
                    }
                }
            }
            return Outcome.Published(
                revision = revision,
                previousRevision = document.contentRevision,
                pages = eligible.map { it.pageIndex },
                indexGeneration = indexGeneration,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            storage.deleteStagingJob(jobId)
            return Outcome.Failed("OCR_PUBLISH_FAILED", error.message ?: error.javaClass.simpleName)
        }
    }

    private fun pageTaskKey(checkpoint: OcrCheckpoint, pageIndex: Int) =
        com.example.myapplication.domain.ocr.OcrTaskKey(
            documentId = checkpoint.documentId,
            sourceFileHash = checkpoint.sourceFileHash,
            pageIndex = pageIndex,
            ocrModelVersion = checkpoint.modelVersion,
            extractorVersion = checkpoint.extractorVersion,
        )

    /** Migrate only stable user anchors; immutable source evidence remains on its original revision. */
    private suspend fun remapUserAnchors(
        userId: Long?,
        documentId: String,
        oldRevision: String,
        newRevision: String,
        sectionIdMap: Map<String, String>,
        modifiedSectionIds: Set<String>,
        now: Long,
    ) {
        if (userId == null) return
        val annotations = database.readerAnnotationDao()
        annotations.listActiveForDocument(userId, documentId).forEach { annotation ->
            val locator = ReaderLocatorCodec.decodeOrNull(annotation.locatorJson) ?: return@forEach
            if (locator.contentRevision != oldRevision) return@forEach
            val newSectionId = sectionIdMap[locator.sectionId] ?: return@forEach
            if (locator is ReaderLocator.Reflow && locator.sectionId in modifiedSectionIds) return@forEach
            val migrated = when (locator) {
                is ReaderLocator.Reflow -> locator.copy(sectionId = newSectionId, contentRevision = newRevision)
                is ReaderLocator.Fixed -> locator.copy(sectionId = newSectionId, contentRevision = newRevision)
                is ReaderLocator.Note -> locator.copy(sectionId = newSectionId, contentRevision = newRevision)
            }
            annotations.remapSourceAnchor(
                userId = userId,
                documentId = documentId,
                annotationId = annotation.id,
                expectedRevision = annotation.revision,
                sectionId = newSectionId,
                locatorJson = ReaderLocatorCodec.encode(migrated),
                now = now,
            )
        }
        annotations.listForDocument(userId, documentId).forEach { bookmark ->
            val locator = ReaderLocatorCodec.decodeOrNull(bookmark.locatorJson) ?: return@forEach
            if (locator.contentRevision != oldRevision) return@forEach
            val newSectionId = sectionIdMap[locator.sectionId] ?: return@forEach
            if (locator is ReaderLocator.Reflow && locator.sectionId in modifiedSectionIds) return@forEach
            val migrated = when (locator) {
                is ReaderLocator.Reflow -> locator.copy(sectionId = newSectionId, contentRevision = newRevision)
                is ReaderLocator.Fixed -> locator.copy(sectionId = newSectionId, contentRevision = newRevision)
                is ReaderLocator.Note -> locator.copy(sectionId = newSectionId, contentRevision = newRevision)
            }
            val migratedHash = ReaderMutationHasher.bookmarkHash(migrated)
            val duplicate = annotations.findBookmarkByAnchor(userId, documentId, migratedHash)
            if (duplicate != null && duplicate.id != bookmark.id) {
                // Keep one bookmark for a canonical anchor when an older revision already had it.
                annotations.deleteBookmark(userId, bookmark.id)
                return@forEach
            }
            annotations.remapSourceAnchor(
                userId = userId,
                documentId = documentId,
                bookmarkId = bookmark.id,
                locatorJson = ReaderLocatorCodec.encode(migrated),
                anchorHash = migratedHash,
            )
        }
    }

    /** 只替换当前代次里确实无文字层的页；原生文字页不得被 OCR 结果覆盖。 */
    private fun isScannedPage(revisionDirectory: File, resourceHref: String): Boolean {
        val html = File(revisionDirectory, resourceHref.replace('/', File.separatorChar))
            .takeIf { it.isFile }
            ?.readText(Charsets.UTF_8)
            ?: return false
        return html.contains("data-extraction=\"no-text\"")
    }

    companion object {
        /** 新代次的 extractor 版本：索引按它判断是否需要重建。 */
        const val EXTRACTOR_VERSION = "phase5-pdf-ocr-v2-tiles"
    }
}
