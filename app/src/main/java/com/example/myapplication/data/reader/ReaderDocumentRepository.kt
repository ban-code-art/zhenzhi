package com.example.myapplication.data.reader

import android.content.res.AssetManager
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.ReaderStateEntity
import com.example.myapplication.data.reader.importer.ReaderFormat
import com.example.myapplication.data.reader.storage.DocumentStorage
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.ReaderAccessDecision
import com.example.myapplication.domain.reader.ReaderAccessPolicy
import com.example.myapplication.domain.reader.ReaderDocumentAccessRecord
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CancellationException
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ReaderDocumentRepository(
    private val database: AppDatabase,
    private val storage: DocumentStorage,
    private val assets: AssetManager,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val catalog get() = database.readerCatalogDao()
    private val states get() = database.readerStateDao()

    fun observeShelf(userId: Long): Flow<List<ReaderShelfItem>> =
        catalog.observeShelf(userId).map { rows ->
            rows.map { row ->
                ReaderShelfItem(
                    documentId = row.document.id,
                    title = row.displayTitleOverride?.takeIf { it.isNotBlank() } ?: row.document.title,
                    author = row.document.author,
                    format = row.document.format.toReaderFormat(),
                    kind = row.document.kind,
                    legacyChapterId = row.document.legacyChapterId,
                    isCompleted = row.isCompleted ?: false,
                    lastReadAt = row.lastReadAt ?: 0L,
                    locatorJson = row.locatorJson,
                )
            }
        }

    fun observeTrash(userId: Long): Flow<List<ReaderShelfItem>> =
        catalog.observeTrash(userId).map { documents ->
            documents.map { document ->
                ReaderShelfItem(
                    documentId = document.id,
                    title = document.title,
                    author = document.author,
                    format = document.format.toReaderFormat(),
                    kind = document.kind,
                    legacyChapterId = document.legacyChapterId,
                    isCompleted = false,
                    lastReadAt = 0L,
                    locatorJson = null,
                )
            }
        }
    suspend fun openSession(userId: Long, documentId: String, requestedRevision: String? = null): OperationResult<ReaderSession> {
        val document = catalog.findDocument(documentId)
            ?: return OperationResult.Rejected("NOT_FOUND")
        when (val decision = ReaderAccessPolicy.canRead(userId, document.toAccessRecord())) {
            is ReaderAccessDecision.Denied -> return OperationResult.Rejected(decision.code)
            else -> Unit
        }
        val targetRevision = requestedRevision ?: document.contentRevision
        val revision = catalog.findContentRevision(documentId, targetRevision)
            ?: return OperationResult.Rejected("REVISION_MISSING")
        val sections = catalog.findSections(documentId, revision.revision)
        if (sections.isEmpty()) return OperationResult.Failed("REVISION_MISSING", retryable = false)
        val outline = catalog.findOutline(documentId, revision.revision)
        // Historical sessions are read-only: no current reading position is projected onto old content.
        val state = if (targetRevision == document.contentRevision) ensureState(userId, documentId) else null
        val storageBacked = document.kind == "IMPORTED" || (document.kind == "BUILTIN" && !revision.manifestPath.startsWith("asset:"))
        val published = if (storageBacked) {
            try {
                if (document.kind == "BUILTIN") verifiedBuiltInProjection(storage, documentId, revision, sections)
                else storage.openRevision(documentId, revision.revision, verifyFiles = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return OperationResult.Failed("STORAGE_FAILURE", retryable = true)
            }
        } else {
            null
        }
        if (storageBacked && published == null) {
            return OperationResult.Failed("STORAGE_FAILURE", retryable = true)
        }
        return OperationResult.Ok(
            ReaderSession(
                document = document.copy(contentRevision = targetRevision, extractorVersion = revision.extractorVersion),
                revision = revision,
                sections = sections,
                outline = outline,
                state = state,
                manifest = published?.manifest,
                contentRoot = published?.directory,
                format = document.format.toReaderFormat(),
            ),
            operationId = operationId(),
        )
    }

    suspend fun acquireWriter(
        userId: Long,
        documentId: String,
        expectedRevision: String? = null,
    ): OperationResult<WriterLease> = database.withTransaction {
        val document = catalog.findDocument(documentId)
            ?: return@withTransaction OperationResult.Rejected("NOT_FOUND")
        when (val decision = ReaderAccessPolicy.canRead(userId, document.toAccessRecord())) {
            is ReaderAccessDecision.Denied -> return@withTransaction OperationResult.Rejected(decision.code)
            else -> Unit
        }
        if (expectedRevision != null && document.contentRevision != expectedRevision) {
            return@withTransaction OperationResult.Rejected("SOURCE_CHANGED")
        }
        ensureState(userId, documentId)
        val changed = states.claimWriter(userId, documentId)
        if (changed != 1) return@withTransaction OperationResult.Failed("STORAGE_FAILURE", retryable = true)
        val updated = states.find(userId, documentId)
            ?: return@withTransaction OperationResult.Failed("STORAGE_FAILURE", retryable = true)
        OperationResult.Ok(WriterLease(updated.writerEpoch), operationId())
    }

    suspend fun saveLocator(
        userId: Long,
        documentId: String,
        lease: WriterLease,
        sequence: Long,
        locatorJson: String,
    ): OperationResult<Unit> = database.withTransaction {
        val locator = ReaderLocatorCodec.decodeOrNull(locatorJson)
            ?: return@withTransaction OperationResult.Rejected("INVALID_ARGUMENT")
        val document = catalog.findDocument(documentId)
            ?: return@withTransaction OperationResult.Rejected("NOT_FOUND")
        if (ReaderAccessPolicy.canRead(userId, document.toAccessRecord()) is ReaderAccessDecision.Denied)
            return@withTransaction OperationResult.Rejected("NOT_FOUND")
        if (locator.documentId != documentId || locator.contentRevision != document.contentRevision)
            return@withTransaction OperationResult.Rejected("SOURCE_CHANGED")
        val changed = states.saveProgress(
            userId = userId,
            documentId = documentId,
            writerEpoch = lease.epoch,
            sequence = sequence,
            locatorJson = locatorJson,
            now = clock(),
        )
        if (changed == 1) return@withTransaction OperationResult.Ok(Unit, operationId())
        val current = states.find(userId, documentId)
        OperationResult.Conflict((current?.revision ?: 0L).toInt())
    }

    suspend fun saveSettings(userId: Long, documentId: String, settingsJson: String): OperationResult<Unit> =
        saveSettings(userId, documentId, settingsJson, null) { true }

    /** Validate the current catalog and update the settings under the same Room transaction. */
    suspend fun saveSettings(
        userId: Long,
        documentId: String,
        settingsJson: String,
        expectedRevision: String?,
        identityValid: suspend () -> Boolean,
    ): OperationResult<Unit> = database.withTransaction {
        if (!identityValid()) return@withTransaction OperationResult.Rejected("SESSION_CHANGED")
        val document = catalog.findDocument(documentId)
            ?: return@withTransaction OperationResult.Rejected("NOT_FOUND")
        when (val decision = ReaderAccessPolicy.canRead(userId, document.toAccessRecord())) {
            is ReaderAccessDecision.Denied -> return@withTransaction OperationResult.Rejected(decision.code)
            else -> Unit
        }
        if (expectedRevision != null && document.contentRevision != expectedRevision)
            return@withTransaction OperationResult.Rejected("SOURCE_CHANGED")
        ensureState(userId, documentId)
        if (!identityValid()) return@withTransaction OperationResult.Rejected("SESSION_CHANGED")
        val changed = states.saveSettings(userId, documentId, settingsJson)
        if (changed == 1) OperationResult.Ok(Unit, operationId())
        else OperationResult.Conflict(0)
    }

    suspend fun setCompleted(userId: Long, documentId: String, completed: Boolean): OperationResult<Unit> {
        ensureState(userId, documentId)
        val changed = states.setCompleted(userId, documentId, completed)
        return if (changed == 1) OperationResult.Ok(Unit, operationId())
        else OperationResult.Conflict(0)
    }

    suspend fun rename(userId: Long, documentId: String, title: String): OperationResult<Unit> {
        val clean = title.trim()
        if (clean.isBlank()) return OperationResult.Rejected("INVALID_ARGUMENT")
        val document = catalog.findDocumentIncludingDeleted(documentId)
            ?: return OperationResult.Rejected("NOT_FOUND")
        val now = clock()
        return when (document.kind) {
            "IMPORTED" -> {
                if (document.ownerUserId != userId) return OperationResult.Rejected("NOT_FOUND")
                val changed = catalog.updateTitle(documentId, clean, now)
                if (changed == 1) OperationResult.Ok(Unit, operationId()) else OperationResult.Conflict(0)
            }

            "BUILTIN" -> {
                ensureState(userId, documentId)
                val changed = states.setDisplayTitleOverride(userId, documentId, clean)
                if (changed == 1) OperationResult.Ok(Unit, operationId()) else OperationResult.Conflict(0)
            }

            else -> OperationResult.Rejected("NOT_FOUND")
        }
    }

    suspend fun trash(userId: Long, documentId: String): OperationResult<Unit> {
        val document = catalog.findDocumentIncludingDeleted(documentId)
            ?: return OperationResult.Rejected("NOT_FOUND")
        return when (document.kind) {
            "IMPORTED" -> {
                if (document.ownerUserId != userId) return OperationResult.Rejected("NOT_FOUND")
                val now = clock()
                val changed = catalog.softDelete(documentId, userId, now, now)
                if (changed == 1) OperationResult.Ok(Unit, operationId()) else OperationResult.Conflict(0)
            }

            "BUILTIN" -> {
                ensureState(userId, documentId)
                val changed = states.setHidden(userId, documentId, true)
                if (changed == 1) OperationResult.Ok(Unit, operationId()) else OperationResult.Conflict(0)
            }

            else -> OperationResult.Rejected("NOT_FOUND")
        }
    }

    suspend fun restore(userId: Long, documentId: String): OperationResult<Unit> {
        val document = catalog.findDocumentIncludingDeleted(documentId)
            ?: return OperationResult.Rejected("NOT_FOUND")
        return when (document.kind) {
            "IMPORTED" -> {
                if (document.ownerUserId != userId) return OperationResult.Rejected("NOT_FOUND")
                val changed = catalog.restore(documentId, userId, clock())
                if (changed == 1) OperationResult.Ok(Unit, operationId()) else OperationResult.Conflict(0)
            }

            "BUILTIN" -> {
                ensureState(userId, documentId)
                val changed = states.setHidden(userId, documentId, false)
                if (changed == 1) OperationResult.Ok(Unit, operationId()) else OperationResult.Conflict(0)
            }

            else -> OperationResult.Rejected("NOT_FOUND")
        }
    }

    suspend fun loadSection(session: ReaderSession, sectionId: String): OperationResult<ReaderSectionContent> {
        val section = session.sections.firstOrNull { it.id == sectionId }
            ?: return OperationResult.Rejected("NOT_FOUND")
        return try {
            if (session.format == ReaderFormat.PDF) {
                val pdfPath = session.document.blobPath
                    ?: return OperationResult.Failed("REVISION_MISSING", retryable = false)
                val html = sectionResourceText(session, section.resourceHref)
                OperationResult.Ok(
                    ReaderSectionContent(
                        sectionId = section.id,
                        title = section.title,
                        html = html,
                        baseUrl = localFileUrl(requireNotNull(File(pdfPath).parentFile)),
                        fileUrl = localFileUrl(File(File(pdfPath).parentFile, "content/page-${(section.pageStart ?: 0) + 1}.html")),
                        pdfFile = File(pdfPath),
                        pageIndex = section.pageStart ?: 0,
                    ),
                    operationId(),
                )
            } else {
                val html = sectionResourceText(session, section.resourceHref)
                OperationResult.Ok(
                    ReaderSectionContent(
                        sectionId = section.id,
                        title = section.title,
                        html = html,
                        baseUrl = sectionBaseUrl(session, section.resourceHref),
                        fileUrl = sectionFileUrl(session, section.resourceHref),
                    ),
                    operationId(),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            OperationResult.Failed("STORAGE_FAILURE", retryable = true)
        }
    }

    private suspend fun ensureState(userId: Long, documentId: String): ReaderStateEntity {
        val existing = states.find(userId, documentId)
        if (existing != null) return existing
        states.insertIfAbsent(
            ReaderStateEntity(
                userId = userId,
                documentId = documentId,
                locatorJson = null,
                settingsJson = null,
                isCompleted = false,
                isHidden = false,
                displayTitleOverride = null,
                lastReadAt = 0,
                writerEpoch = 0,
                lastSequence = 0,
                revision = 0,
            ),
        )
        return states.find(userId, documentId) ?: error("reader state could not be created")
    }

    private fun sectionResourceText(session: ReaderSession, resourceHref: String): String {
        if (session.document.kind == "BUILTIN" && session.revision.manifestPath.startsWith("asset:") && resourceHref.startsWith("asset:")) {
            val assetPath = resourceHref.removePrefix("asset:")
            return assets.open(assetPath).use { input ->
                input.readBytes().toString(StandardCharsets.UTF_8)
            }
        }
        if (session.document.kind == "BUILTIN") {
            verifiedBuiltInProjection(storage, session.document.id, session.revision, session.sections)
        }
        val file = storage.resolveFile(session.document.id, session.revision.revision, resourceHref)
        return file.readText(StandardCharsets.UTF_8)
    }

    private fun sectionFileUrl(session: ReaderSession, resourceHref: String): String {
        if (session.document.kind == "BUILTIN" && session.revision.manifestPath.startsWith("asset:") && resourceHref.startsWith("asset:")) {
            return assetUrl(resourceHref.removePrefix("asset:"))
        }
        if (session.document.kind == "BUILTIN") {
            verifiedBuiltInProjection(storage, session.document.id, session.revision, session.sections)
        }
        val file = storage.resolveFile(session.document.id, session.revision.revision, resourceHref)
        return localFileUrl(file)
    }

    private fun sectionBaseUrl(session: ReaderSession, resourceHref: String): String {
        if (session.document.kind == "BUILTIN" && session.revision.manifestPath.startsWith("asset:") && resourceHref.startsWith("asset:")) {
            val assetPath = resourceHref.removePrefix("asset:")
            val directory = assetPath.substringBeforeLast('/', "")
            return assetUrl(if (directory.isEmpty()) "" else "$directory/")
        }
        if (session.document.kind == "BUILTIN") {
            verifiedBuiltInProjection(storage, session.document.id, session.revision, session.sections)
        }
        val file = storage.resolveFile(session.document.id, session.revision.revision, resourceHref)
        return localFileUrl(requireNotNull(file.parentFile))
    }

    // Construct URI components rather than concatenating raw filenames containing #, +, % or spaces.
    private fun localFileUrl(file: File): String =
        java.net.URI("file", "", file.toURI().path, null).toASCIIString()

    private fun assetUrl(path: String): String =
        java.net.URI("file", "", "/android_asset/$path", null).toASCIIString()

    private fun com.example.myapplication.data.local.entity.ReaderDocumentEntity.toAccessRecord() =
        ReaderDocumentAccessRecord(
            documentId = id,
            kind = kind,
            ownerUserId = ownerUserId,
            contentRevision = contentRevision,
            accessEpoch = accessEpoch,
            importStatus = importStatus,
            deletedAt = deletedAt,
        )

    private fun String.toReaderFormat(): ReaderFormat =
        ReaderFormat.entries.firstOrNull { it.name == this } ?: ReaderFormat.UNKNOWN

    private fun operationId(): String = UUID.randomUUID().toString()
}
