package com.example.myapplication.data.reader.importer

import androidx.room.withTransaction
import com.example.myapplication.data.knowledge.index.IndexOutbox
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.ReaderContentRevisionEntity
import com.example.myapplication.data.local.entity.ReaderOutlineNodeEntity
import com.example.myapplication.data.local.entity.ReaderSectionEntity
import com.example.myapplication.data.reader.storage.*
import com.example.myapplication.data.session.SessionManager
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.OperationResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class MarkdownReparsePublication(
    val documentId: String,
    val previousRevision: String,
    val revision: String,
    val changed: Boolean,
)

/** Explicit owner operation; does not change import retry identity or rewrite historical anchors. */
class MarkdownReparser(
    private val database: AppDatabase,
    private val storage: DocumentStorage,
    private val sessions: SessionManager,
    private val parser: DocumentParser = MarkdownDocumentParser(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun reparse(context: AccessContext, documentId: String, expectedRevision: String): OperationResult<MarkdownReparsePublication> =
        withContext(Dispatchers.IO) {
            if (documentId.isBlank() || expectedRevision.isBlank()) return@withContext OperationResult.Rejected("INVALID_ARGUMENT")
            if (!authorized(context)) return@withContext OperationResult.Rejected("ACCESS_REVOKED")
            val catalog = database.readerCatalogDao()
            val original = catalog.findDocument(documentId)
                ?: return@withContext OperationResult.Rejected("NOT_FOUND")
            if (original.ownerUserId != context.userId || original.kind != "IMPORTED")
                return@withContext OperationResult.Rejected("NOT_FOUND")
            if (original.format != ReaderFormat.MARKDOWN.name || original.importStatus != "READY")
                return@withContext OperationResult.Rejected("UNSUPPORTED_FORMAT")
            if (original.contentRevision != expectedRevision)
                return@withContext OperationResult.Conflict(original.metadataRevision.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            val stagingId = "markdown-reparse-${UUID.randomUUID()}"
            try {
                val old = storage.openRevision(documentId, expectedRevision, verifyFiles = true)
                    ?: return@withContext OperationResult.Rejected("REVISION_MISSING")
                val source = old.manifest.files.singleOrNull { it.kind == ManifestFileKind.SOURCE }
                    ?: return@withContext OperationResult.Rejected("SOURCE_MISSING")
                val sourceFile = storage.resolveFile(documentId, expectedRevision, source.relativePath)
                if (storage.sha256Of(sourceFile) != old.manifest.sourceSha256 ||
                    old.manifest.sourceSha256 != original.fileSha256)
                    return@withContext OperationResult.Rejected("SOURCE_CHANGED")
                val staged = sourceFile.inputStream().use { storage.stageSource(stagingId, it) }
                if (staged.sha256 != old.manifest.sourceSha256) return@withContext OperationResult.Rejected("SOURCE_CHANGED")
                currentCoroutineContext().ensureActive()
                val build = storage.stagingRevisionDirectory(stagingId, "0".repeat(64))
                // M04：附件只来自旧 revision 已声明的文件（附件索引或旧版手工资源），不访问外部位置。
                val parsed = (parser as? MarkdownDocumentParser)?.parse(
                    staged.file, build, original.title,
                    RevisionMarkdownAttachments(old.manifest) { storage.resolveFile(documentId, expectedRevision, it) },
                ) ?: parser.parse(staged.file, build, original.title)
                currentCoroutineContext().ensureActive()
                require(parsed.format == ReaderFormat.MARKDOWN) { "reparse parser must return Markdown" }
                // Preserve only safe local resources declared by the old immutable manifest.
                val paths = parsed.files.mapTo(mutableSetOf()) { it.relativePath }
                val files = parsed.files.toMutableList()
                for (resource in old.manifest.files.filter { it.kind !in setOf(ManifestFileKind.SECTION, ManifestFileKind.SOURCE) }) {
                    if (!paths.add(resource.relativePath)) continue
                    currentCoroutineContext().ensureActive()
                    if (resource.bytes > ParserIO.MAX_TEXT_BYTES) throw DocumentParseException("LIMIT_EXCEEDED", "本地资源超过64 MiB限制")
                    ParserIO.writeBytes(build, resource.relativePath,
                        storage.resolveFile(documentId, expectedRevision, resource.relativePath).readBytes())
                    files += ParsedFile(resource.relativePath, resource.kind)
                }
                ParserIO.writeBytes(build, source.relativePath, staged.file.readBytes())
                files += ParsedFile(source.relativePath, ManifestFileKind.SOURCE)
                val manifestFiles = files.map { item ->
                    val file = File(build, item.relativePath)
                    ManifestFile(item.relativePath, storage.sha256Of(file), file.length(), item.kind)
                }
                val provisional = ContentManifest(1, old.manifest.jobId, documentId, staged.sha256, "0".repeat(64),
                    extractorVersionFor(ReaderFormat.MARKDOWN), parsed.title, parsed.author,
                    manifestFiles, parsed.sections, parsed.outline)
                val revision = ContentManifestCodec.canonicalHash(provisional)
                val manifest = provisional.copy(contentRevision = revision)
                storage.writeManifest(build, manifest)
                currentCoroutineContext().ensureActive()
                val published = storage.publishRevision(build, manifest)
                currentCoroutineContext().ensureActive()
                database.withTransaction {
                    if (!authorized(context)) return@withTransaction OperationResult.Rejected("ACCESS_REVOKED")
                    val current = catalog.findDocument(documentId)
                        ?: return@withTransaction OperationResult.Rejected("NOT_FOUND")
                    if (current.ownerUserId != context.userId || current.kind != "IMPORTED")
                        return@withTransaction OperationResult.Rejected("NOT_FOUND")
                    if (current.contentRevision != expectedRevision || current.accessEpoch != original.accessEpoch ||
                        current.metadataRevision != original.metadataRevision || current.importStatus != "READY")
                        return@withTransaction OperationResult.Conflict(current.metadataRevision.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    if (revision == expectedRevision) return@withTransaction OperationResult.Ok(
                        MarkdownReparsePublication(documentId, expectedRevision, revision, false), context.requestId)
                    val knowledgeDao = database.knowledgeDocumentDao()
                    val knowledge = knowledgeDao.findByDocument(context.userId, documentId)
                    val generation = Math.addExact(knowledge?.requestedGeneration ?: 0, 1)
                    val epoch = Math.addExact(current.accessEpoch, 1)
                    val now = clock()
                    if (catalog.findContentRevision(documentId, revision) == null) {
                        catalog.insertContentRevision(ReaderContentRevisionEntity(documentId, revision, staged.sha256,
                            File(published.directory, source.relativePath).absolutePath,
                            File(published.directory, DocumentStorage.MANIFEST_FILE_NAME).absolutePath,
                            manifest.extractorVersion, "READY", now))
                        val sectionIds = parsed.sections.associate { it.id to "$documentId:$revision:${it.id}" }
                        catalog.insertSections(parsed.sections.map { s -> ReaderSectionEntity(
                            id = sectionIds.getValue(s.id), documentId = documentId, contentRevision = revision,
                            orderIndex = s.orderIndex, title = s.title, depth = 0, resourceHref = s.resourceHref,
                            canonicalPath = s.resourceHref, charCount = s.charCount, pageStart = s.pageStart, pageEnd = s.pageEnd) })
                        catalog.insertOutline(parsed.outline.map { n -> ReaderOutlineNodeEntity(
                            id = "$documentId:$revision:${n.nodeKey}", documentId = documentId, contentRevision = revision,
                            nodeKey = n.nodeKey, parentId = n.parentKey?.let { "$documentId:$revision:$it" },
                            orderIndex = n.orderIndex, title = n.title,
                            targetSectionId = n.targetSectionId?.let(sectionIds::get), targetLocatorJson = n.targetLocatorJson) })
                    }
                    check(catalog.activateMarkdownRevision(documentId, context.userId, expectedRevision,
                        original.accessEpoch, original.metadataRevision, revision, manifest.extractorVersion, now) == 1)
                    database.readerStateDao().invalidateForContentChange(documentId)
                    if (knowledge != null) {
                        knowledgeDao.setAccessEpoch(knowledge.id, epoch)
                        knowledgeDao.updateLexicalState(knowledge.id, "PENDING")
                        knowledgeDao.updateVectorState(knowledge.id, "PENDING")
                    }
                    val registered = IndexOutbox(database, clock).registerDocument(context.userId, documentId, revision, generation)
                    // IndexOutbox may encode a cancellation as Failed; never commit in a cancelled caller.
                    currentCoroutineContext().ensureActive()
                    if (registered is IndexOutbox.RegisterResult.Failed) throw DocumentStorageException("index registration failed")
                    if (knowledge == null) {
                        val created = knowledgeDao.findByDocument(context.userId, documentId)
                            ?: error("index registration did not create a document")
                        knowledgeDao.setAccessEpoch(created.id, epoch)
                    }
                    OperationResult.Ok(MarkdownReparsePublication(documentId, expectedRevision, revision, true), context.requestId)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: DocumentParseException) { OperationResult.Failed(error.code, false) }
            catch (_: ArithmeticException) { OperationResult.Failed("LIMIT_EXCEEDED", false) }
            catch (_: Exception) { OperationResult.Failed("STORAGE_FAILURE", true) }
            finally {
                runCatching { storage.deleteStagingJob(stagingId) }.onFailure {
                    android.util.Log.w("MarkdownReparser", "Staging cleanup failed: ${it.javaClass.simpleName}")
                }
            }
        }

    private suspend fun authorized(context: AccessContext): Boolean = sessions.identity.first().let {
        it.userId == context.userId && it.epoch == context.sessionEpoch
    }
}
