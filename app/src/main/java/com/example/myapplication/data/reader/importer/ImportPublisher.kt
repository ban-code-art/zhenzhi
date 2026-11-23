package com.example.myapplication.data.reader.importer

import androidx.room.withTransaction
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.ReaderContentRevisionEntity
import com.example.myapplication.data.local.entity.ReaderDocumentEntity
import com.example.myapplication.data.local.entity.ReaderOutlineNodeEntity
import com.example.myapplication.data.local.entity.ReaderSectionEntity
import com.example.myapplication.data.reader.storage.ContentManifest
import com.example.myapplication.data.reader.storage.ContentManifestCodec
import com.example.myapplication.data.reader.storage.DocumentStorage
import com.example.myapplication.data.reader.storage.DocumentStorageException
import com.example.myapplication.data.reader.storage.ManifestFile
import com.example.myapplication.data.reader.storage.ManifestFileKind
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class ImportPublicationStoppedException(val status: String?) : Exception("Import job is no longer active: $status")

data class ImportPublication(
    val documentId: String,
    val revision: String,
    val manifest: ContentManifest? = null,
)

class ImportPublisher(
    private val storage: DocumentStorage,
    private val database: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 索引登记与内容发布同一事务（设计 §6 源文档发布）。 */
    private val indexOutbox = com.example.myapplication.data.knowledge.index.IndexOutbox(database, clock)

    suspend fun publish(
        job: ImportJobSnapshot,
        parsed: ParsedDocument,
        sourceFile: File,
        sourceSha256: String,
        buildDirectory: File,
        requireActiveJob: Boolean = false,
    ): ImportPublication {
        currentCoroutineContext().ensureActive()
        activeJob(job, requireActiveJob)
        val documentId = "imported:${job.jobId}"
        val catalog = database.readerCatalogDao()
        val existing = catalog.findDocument(documentId)
        if (existing != null && existing.importStatus == "READY") {
            return withContext(NonCancellable) { database.withTransaction {
                val active = activeJob(job, requireActiveJob)
                val ready = catalog.findDocument(documentId)
                    ?: throw ImportPublicationStoppedException("DOCUMENT_CHANGED")
                check(ready.importStatus == "READY" && ready.ownerUserId == job.userId && ready.deletedAt == null)
                if (active != null) check(database.readerJobDao().markPublished(job.jobId, documentId, ready.contentRevision, clock()) == 1)
                ImportPublication(documentId, ready.contentRevision)
            } }
        }

        val sourceRelativePath = "source/source.${job.format.extension()}"
        val sourceTarget = File(buildDirectory, sourceRelativePath.replace('/', File.separatorChar))
        if (!sourceTarget.exists()) {
            sourceTarget.parentFile?.mkdirs()
            sourceFile.copyTo(sourceTarget, overwrite = true)
        }
        val paths = linkedSetOf<String>()
        val parsedFiles = parsed.files.filter { paths.add(it.relativePath) }
        val allFiles = if (paths.contains(sourceRelativePath)) {
            parsedFiles
        } else {
            parsedFiles + ParsedFile(sourceRelativePath, ManifestFileKind.SOURCE)
        }
        val manifestFiles = allFiles.map { parsedFile ->
            val file = File(buildDirectory, parsedFile.relativePath.replace('/', File.separatorChar))
            if (!file.isFile) throw DocumentStorageException("parser output is missing ${parsedFile.relativePath}")
            ManifestFile(
                relativePath = parsedFile.relativePath,
                sha256 = storage.sha256Of(file),
                bytes = file.length(),
                kind = parsedFile.kind,
            )
        }
        val provisional = ContentManifest(
            schemaVersion = 1,
            jobId = job.jobId,
            documentId = documentId,
            sourceSha256 = sourceSha256,
            contentRevision = "0".repeat(64),
            // extractorVersion 决定索引重建：一期格式保持原字符串（避免无谓重建既有用户索引），
            // 第五期新增格式使用 phase5 前缀（解析规则、章节切分口径都是新的）。
            extractorVersion = extractorVersionFor(parsed.format),
            title = parsed.title,
            author = parsed.author,
            files = manifestFiles,
            sections = parsed.sections,
            outline = parsed.outline,
        )
        val revision = ContentManifestCodec.canonicalHash(provisional)
        val manifest = provisional.copy(contentRevision = revision)
        storage.writeManifest(buildDirectory, manifest)
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) { database.withTransaction {
            val active = activeJob(job, requireActiveJob)
            catalog.findDocument(documentId)?.let { current ->
                check(current.ownerUserId == job.userId && current.importStatus == "READY" &&
                    current.deletedAt == null && current.contentRevision == revision) { "import document changed during publication" }
            }
            // CAS cancellation and file+catalog publication share the same Room transaction.
            val published = storage.publishRevision(buildDirectory, manifest)
            val now = clock()
            val sourceBlobPath = File(published.directory, sourceRelativePath.replace('/', File.separatorChar)).absolutePath
            if (catalog.findDocument(documentId) == null) {
                catalog.insertDocument(
                    ReaderDocumentEntity(
                        id = documentId,
                        ownerUserId = job.userId,
                        kind = "IMPORTED",
                        legacyChapterId = null,
                        format = parsed.format.name,
                        title = parsed.title,
                        author = parsed.author,
                        blobPath = sourceBlobPath,
                        fileSha256 = sourceSha256,
                        contentRevision = revision,
                        extractorVersion = manifest.extractorVersion,
                        metadataRevision = 1,
                        accessEpoch = 1,
                        importStatus = "READY",
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                catalog.insertContentRevision(
                    ReaderContentRevisionEntity(
                        documentId = documentId,
                        revision = revision,
                        sourceSha256 = sourceSha256,
                        sourceBlobPath = sourceBlobPath,
                        manifestPath = File(published.directory, DocumentStorage.MANIFEST_FILE_NAME).absolutePath,
                        extractorVersion = manifest.extractorVersion,
                        status = "READY",
                        createdAt = now,
                    ),
                )
                val sectionIds = parsed.sections.associate { section ->
                    section.id to "$documentId:$revision:${section.id}"
                }
                catalog.insertSections(
                    parsed.sections.map { section ->
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
                    parsed.outline.map { node ->
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
                // 同事务登记 knowledge_document + LOCAL 索引任务（设计 §6）：
                // 不允许"有文档没有索引任务"或反之。
                //
                // 例外：能力表声明不可进入知识索引的格式（图片漫画、待 OCR 的扫描件）不登记，
                // 否则会白跑一遍抽取+embedding 却得到 0 个 chunk，还会让"索引已完成"的
                // 状态掩盖"这本书本来就没有文字层"。等 OCR 落地后再单独登记新代次。
                if (FormatRegistry.capabilitiesOf(parsed.format).knowledgeIndexable) {
                    indexOutbox.registerDocument(
                        userId = job.userId,
                        documentId = documentId,
                        targetRevision = revision,
                        generation = 1,
                    )
                }
            }
            if (active != null) check(database.readerJobDao().markPublished(job.jobId, documentId, revision, now) == 1)
            ImportPublication(documentId, revision, manifest)
        } }
    }

    private suspend fun activeJob(job: ImportJobSnapshot, required: Boolean): com.example.myapplication.data.local.entity.ReaderJobEntity? {
        val current = database.readerJobDao().find(job.jobId)
        if (current == null) {
            if (required) throw ImportPublicationStoppedException(null)
            return null // Existing standalone publication fixtures intentionally have no job row.
        }
        if (current.kind != "IMPORT" || current.userId != job.userId ||
            current.requestKey != job.requestKey || current.status !in setOf("PENDING", "RUNNING", "RETRY_WAIT") ||
            (current.documentId != null && current.documentId != "imported:${job.jobId}"))
            throw ImportPublicationStoppedException(current.status)
        return current
    }
}

// 扩展名口径统一放在 FormatRegistry，避免这里再维护一份（历史上这里还重复了一个 HTML 分支）。
private fun ReaderFormat?.extension(): String =
    if (this == null) "bin" else FormatRegistry.extensionOf(this)

/** 一期格式沿用 phase1 版本串；第五期新增格式用 phase5，改动解析口径时同步升版本。 */
internal fun extractorVersionFor(format: ReaderFormat): String = when (format) {
    // M04: H1 AST chapter boundaries replace the old regex/H2/H3 splitting. Old revisions stay immutable.
    ReaderFormat.MARKDOWN -> "phase1-markdown-v2"
    ReaderFormat.EPUB -> "phase1-epub-v2"

    ReaderFormat.FB2,
    ReaderFormat.MHTML,
    ReaderFormat.MOBI,
    ReaderFormat.AZW,
    ReaderFormat.AZW3,
    ReaderFormat.CBZ,
    -> "phase5-${format.name.lowercase()}-v1"

    else -> "phase1-${format.name.lowercase()}-v1"
}
