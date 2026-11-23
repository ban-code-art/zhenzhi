package com.example.myapplication.data.reader.notes

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.example.myapplication.data.encyclopedia.EncyclopediaSourceLocator
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.ReaderAnnotationEntity
import com.example.myapplication.data.local.entity.ReaderBookmarkEntity
import com.example.myapplication.data.local.entity.ReaderDocumentEntity
import com.example.myapplication.data.local.entity.ReaderMutationReceiptEntity
import com.example.myapplication.data.local.entity.ReaderSourceReferenceEntity
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.NormalizedRect
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.ReaderAccessDecision
import com.example.myapplication.domain.reader.ReaderAccessPolicy
import com.example.myapplication.domain.reader.ReaderDocumentAccessRecord
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import com.example.myapplication.domain.reader.ReaderLocatorCodecException
import com.example.myapplication.domain.reader.ReaderLocatorValidator
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class SourceReferenceView(
    val id: String,
    val documentId: String?,
    val sectionId: String?,
    val contentRevision: String,
    val locator: ReaderLocator?,
    val quote: String,
    val quoteSha256: String,
    val sourceTitle: String,
    val sourceAvailable: Boolean,
    val evidenceKind: String = "READER_SELECTION",
    val rawLocatorJson: String? = null,
)

sealed interface BookmarkToggleResult {
    data class Added(val bookmark: ReaderBookmarkView) : BookmarkToggleResult
    data class Removed(val anchorHash: String) : BookmarkToggleResult
}

class AnnotationRepository(
    private val database: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val textStore = AnnotationV2Store(database, clock)
    private val annotations get() = database.readerAnnotationDao()
    private val catalog get() = database.readerCatalogDao()
    private val receipts get() = database.readerNotebookDao()

    fun observeAnnotations(userId: Long, documentId: String): Flow<List<ReaderAnnotationView>> =
        annotations.observeAnnotations(userId, documentId).map { rows -> rows.mapNotNull { toView(it) } }

    fun observeAnnotationsByKind(
        userId: Long,
        documentId: String,
        kind: AnnotationKind,
    ): Flow<List<ReaderAnnotationView>> =
        annotations.observeAnnotationsByKind(userId, documentId, kind.name)
            .map { rows -> rows.mapNotNull { toView(it) } }

    fun observeBookmarks(userId: Long, documentId: String): Flow<List<ReaderBookmarkView>> =
        annotations.observeBookmarks(userId, documentId).map { rows -> rows.map(::toBookmarkView) }

    suspend fun create(
        context: AccessContext,
        documentId: String,
        kind: AnnotationKind,
        locator: ReaderLocator,
        quote: String?,
        style: AnnotationStyle,
        noteText: String? = null,
        inkBlobPath: String? = null,
        targets: List<AnnotationTargetInput>? = null,
        writeGuard: AnnotationWriteGuard? = null,
    ): OperationResult<ReaderAnnotationView> {
        if (kind == AnnotationKind.INK && (targets != null || writeGuard != null)) {
            return OperationResult.Rejected("UNSUPPORTED_INK_PAYLOAD")
        }
        if (kind != AnnotationKind.INK) {
            return textStore.create(context, documentId, kind, locator, quote, style, noteText, inkBlobPath, targets, writeGuard)
        }
        val validation = ReaderLocatorValidator.validate(locator)
        if (!validation.isValid || locator.documentId != documentId) {
            return OperationResult.Rejected("INVALID_ARGUMENT")
        }
        if (kind != AnnotationKind.INK && quote.isNullOrBlank()) {
            return OperationResult.Rejected("INVALID_ARGUMENT")
        }
        if (noteText != null && noteText.length > MAX_NOTE_CHARS) {
            return OperationResult.Rejected("INVALID_ARGUMENT")
        }
        val annotationId = deterministicAnnotationId(context, documentId, context.requestId)
        val payload = buildString {
            append(kind.name).append('|').append(ReaderLocatorCodec.encode(locator))
            append('|').append(quote.orEmpty()).append('|').append(noteText.orEmpty())
            append('|').append(style.encode()).append('|').append(inkBlobPath.orEmpty())
        }
        return database.withTransaction {
            val access = readableDocument(context, documentId, locator.contentRevision)
                ?: return@withTransaction OperationResult.Rejected("NOT_FOUND")
            val existingReceipt = receipts.findReceipt(context.userId, context.requestId)
            if (existingReceipt != null) {
                if (existingReceipt.requestHash != ReaderMutationHasher.requestHash("ANNOTATION", annotationId, 1, payload)) {
                    return@withTransaction OperationResult.Rejected("IDEMPOTENCY_CONFLICT")
                }
                val existing = annotations.findAnnotation(context.userId, annotationId)
                return@withTransaction if (existing != null) {
                    OperationResult.Ok(toView(existing) ?: return@withTransaction OperationResult.Rejected("STORAGE_FAILURE"), context.requestId)
                } else {
                    OperationResult.Rejected("RECEIPT_TARGET_MISSING")
                }
            }
            val now = clock()
            annotations.insertAnnotation(
                ReaderAnnotationEntity(
                    id = annotationId,
                    userId = context.userId,
                    documentId = documentId,
                    sectionId = locator.sectionId,
                    kind = kind.name,
                    locatorJson = ReaderLocatorCodec.encode(locator),
                    quoteSnapshot = quote?.take(MAX_QUOTE_CHARS),
                    noteText = noteText,
                    styleJson = style.encode(),
                    inkBlobPath = inkBlobPath,
                    revision = 1,
                    deletedAt = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            receipts.insertReceipt(
                ReaderMutationReceiptEntity(
                    userId = context.userId,
                    operationId = context.requestId,
                    targetKind = "ANNOTATION",
                    targetId = annotationId,
                    requestHash = ReaderMutationHasher.requestHash("ANNOTATION", annotationId, 1, payload),
                    beforeRevision = 0,
                    afterRevision = 1,
                    resultJson = jsonResult(annotationId),
                    createdAt = now,
                ),
            )
            OperationResult.Ok(
                toView(annotations.findAnnotation(context.userId, annotationId)!!)!!,
                context.requestId,
            )
        }
    }

    suspend fun saveInk(
        context: AccessContext,
        documentId: String,
        locator: ReaderLocator,
        manifestPath: String,
        annotationId: String?,
        baseRevision: Long,
    ): OperationResult<ReaderAnnotationView> {
        val validation = ReaderLocatorValidator.validate(locator)
        if (!validation.isValid || locator.documentId != documentId || manifestPath.isBlank()) {
            return OperationResult.Rejected("INVALID_ARGUMENT")
        }
        if (annotationId == null) {
            return create(
                context = context,
                documentId = documentId,
                kind = AnnotationKind.INK,
                locator = locator,
                quote = null,
                style = AnnotationStyle(),
                inkBlobPath = manifestPath,
            )
        }
        val payload = "ink|${ReaderLocatorCodec.encode(locator)}|${manifestPath.take(1024)}"
        return database.withTransaction {
            readableDocument(context, documentId, locator.contentRevision)
                ?: return@withTransaction OperationResult.Rejected("NOT_FOUND")
            val existing = annotations.findAnnotation(context.userId, annotationId)
                ?: return@withTransaction OperationResult.Rejected("NOT_FOUND")
            if (existing.kind != AnnotationKind.INK.name) {
                return@withTransaction OperationResult.Rejected("NOT_FOUND")
            }
            val requestHash = ReaderMutationHasher.requestHash("ANNOTATION", annotationId, baseRevision, payload)
            val receipt = receipts.findReceipt(context.userId, context.requestId)
            if (receipt != null) {
                return@withTransaction if (receipt.requestHash == requestHash) {
                    annotations.findAnnotation(context.userId, annotationId)
                        ?.let { OperationResult.Ok(toView(it)!!, context.requestId) }
                        ?: OperationResult.Rejected("RECEIPT_TARGET_MISSING")
                } else {
                    OperationResult.Rejected("IDEMPOTENCY_CONFLICT")
                }
            }
            if (existing.revision != baseRevision) {
                return@withTransaction OperationResult.Conflict(existing.revision.toInt())
            }
            val now = clock()
            val changed = annotations.updateInk(
                userId = context.userId,
                annotationId = annotationId,
                expectedRevision = baseRevision,
                locatorJson = ReaderLocatorCodec.encode(locator),
                inkBlobPath = manifestPath.take(1024),
                now = now,
            )
            if (changed != 1) return@withTransaction OperationResult.Conflict((existing.revision + 1).toInt())
            receipts.insertReceipt(
                ReaderMutationReceiptEntity(
                    userId = context.userId,
                    operationId = context.requestId,
                    targetKind = "ANNOTATION",
                    targetId = annotationId,
                    requestHash = requestHash,
                    beforeRevision = baseRevision,
                    afterRevision = baseRevision + 1,
                    resultJson = jsonResult(annotationId),
                    createdAt = now,
                ),
            )
            OperationResult.Ok(
                toView(annotations.findAnnotation(context.userId, annotationId)!!)!!,
                context.requestId,
            )
        }
    }

    suspend fun update(
        context: AccessContext,
        annotationId: String,
        baseRevision: Long,
        noteText: String?,
        style: AnnotationStyle? = null,
        writeGuard: AnnotationWriteGuard? = null,
    ): OperationResult<ReaderAnnotationView> {
        val currentKind = annotations.findAnnotation(context.userId, annotationId)?.kind
        if (currentKind != AnnotationKind.INK.name) {
            return textStore.update(context, annotationId, baseRevision, noteText, style, writeGuard)
        }
        if (writeGuard != null) return OperationResult.Rejected("UNSUPPORTED_INK_PAYLOAD")
        if (noteText != null && noteText.length > MAX_NOTE_CHARS) {
            return OperationResult.Rejected("INVALID_ARGUMENT")
        }
        return database.withTransaction {
            val current = annotations.findAnnotation(context.userId, annotationId)
                ?: return@withTransaction OperationResult.Rejected("NOT_FOUND")
            val access = readableDocument(context, current.documentId, null)
                ?: return@withTransaction OperationResult.Rejected("NOT_FOUND")
            val styleValue = style ?: AnnotationStyle.decode(current.styleJson)
            val payload = "note|${noteText.orEmpty()}|${styleValue.encode()}"
            val existingReceipt = receipts.findReceipt(context.userId, context.requestId)
            if (existingReceipt != null) {
                return@withTransaction if (existingReceipt.requestHash ==
                    ReaderMutationHasher.requestHash("ANNOTATION", annotationId, baseRevision, payload)
                ) {
                    annotations.findAnnotation(context.userId, annotationId)
                        ?.let { OperationResult.Ok(toView(it)!!, context.requestId) }
                        ?: OperationResult.Rejected("RECEIPT_TARGET_MISSING")
                } else {
                    OperationResult.Rejected("IDEMPOTENCY_CONFLICT")
                }
            }
            if (current.deletedAt != null) return@withTransaction OperationResult.Rejected("NOT_FOUND")
            if (current.revision != baseRevision) {
                return@withTransaction OperationResult.Conflict(current.revision.toInt())
            }
            val now = clock()
            val changed = annotations.updateAnnotation(
                userId = context.userId,
                annotationId = annotationId,
                expectedRevision = baseRevision,
                locatorJson = current.locatorJson,
                quoteSnapshot = current.quoteSnapshot,
                noteText = noteText,
                styleJson = styleValue.encode(),
                inkBlobPath = current.inkBlobPath,
                now = now,
            )
            if (changed != 1) {
                val latest = annotations.findAnnotation(context.userId, annotationId)
                return@withTransaction OperationResult.Conflict((latest?.revision ?: baseRevision).toInt())
            }
            receipts.insertReceipt(
                ReaderMutationReceiptEntity(
                    userId = context.userId,
                    operationId = context.requestId,
                    targetKind = "ANNOTATION",
                    targetId = annotationId,
                    requestHash = ReaderMutationHasher.requestHash("ANNOTATION", annotationId, baseRevision, payload),
                    beforeRevision = baseRevision,
                    afterRevision = baseRevision + 1,
                    resultJson = jsonResult(annotationId),
                    createdAt = now,
                ),
            )
            OperationResult.Ok(toView(annotations.findAnnotation(context.userId, annotationId)!!)!!, context.requestId)
        }
    }

    suspend fun removeText(context: AccessContext, annotationId: String, baseRevision: Long,
        writeGuard: AnnotationWriteGuard? = null): OperationResult<ReaderAnnotationView> =
        textStore.lifecycle(context, annotationId, baseRevision, "DELETE", writeGuard)

    suspend fun restoreText(context: AccessContext, annotationId: String, baseRevision: Long,
        writeGuard: AnnotationWriteGuard? = null): OperationResult<ReaderAnnotationView> =
        textStore.lifecycle(context, annotationId, baseRevision, "RESTORE", writeGuard)

    suspend fun remove(
        context: AccessContext,
        annotationId: String,
        baseRevision: Long,
    ): OperationResult<Unit> = database.withTransaction {
        val current = annotations.findAnnotation(context.userId, annotationId)
            ?: return@withTransaction OperationResult.Rejected("NOT_FOUND")
        readableDocument(context, current.documentId, null)
            ?: return@withTransaction OperationResult.Rejected("NOT_FOUND")
        if (current.deletedAt != null) return@withTransaction OperationResult.Ok(Unit, context.requestId)
        if (current.revision != baseRevision) {
            return@withTransaction OperationResult.Conflict(current.revision.toInt())
        }
        val now = clock()
        val changed = annotations.softDeleteAnnotation(context.userId, annotationId, baseRevision, now)
        if (changed != 1) return@withTransaction OperationResult.Conflict((current.revision + 1).toInt())
        OperationResult.Ok(Unit, context.requestId)
    }

    suspend fun restore(context: AccessContext, annotationId: String): OperationResult<Unit> = database.withTransaction {
        val current = annotations.findAnnotation(context.userId, annotationId)
            ?: return@withTransaction OperationResult.Rejected("NOT_FOUND")
        readableDocument(context, current.documentId, null)
            ?: return@withTransaction OperationResult.Rejected("NOT_FOUND")
        if (current.deletedAt == null) return@withTransaction OperationResult.Ok(Unit, context.requestId)
        val changed = annotations.restoreAnnotation(context.userId, annotationId, clock())
        if (changed == 1) OperationResult.Ok(Unit, context.requestId) else OperationResult.Conflict(current.revision.toInt())
    }

    suspend fun ensureBookmark(
        context: AccessContext,
        documentId: String,
        locator: ReaderLocator,
        preview: String,
    ): OperationResult<ReaderBookmarkView> {
        // Decode the encoded input before any suspension so Fixed.rects cannot change underneath a transaction.
        val locatorJson = try {
            ReaderLocatorCodec.encode(locator)
        } catch (_: ReaderLocatorCodecException) {
            return OperationResult.Rejected("INVALID_ARGUMENT")
        }
        val frozenLocator = try {
            ReaderLocatorCodec.decode(locatorJson)
        } catch (_: ReaderLocatorCodecException) {
            return OperationResult.Rejected("INVALID_ARGUMENT")
        }
        val normalizedPreview = normalizeEnsureBookmarkPreview(preview)
        if (frozenLocator.documentId != documentId || !ReaderLocatorValidator.isWellFormedUtf16(normalizedPreview)) {
            return OperationResult.Rejected("INVALID_ARGUMENT")
        }
        val anchorHash = ReaderMutationHasher.bookmarkHash(frozenLocator)
        val payload = buildJsonObject {
            put("documentId", documentId)
            put("locatorJson", locatorJson)
            put("preview", normalizedPreview)
        }.toString()
        val requestHash = ReaderMutationHasher.requestHash(BOOKMARK_ENSURE_KIND, anchorHash, 1, payload)

        return try {
            database.withTransaction {
                readableDocument(context, documentId, frozenLocator.contentRevision)
                    ?: return@withTransaction OperationResult.Rejected("NOT_FOUND")
                val existingReceipt = receipts.findReceipt(context.userId, context.requestId)
                if (existingReceipt != null) {
                    if (existingReceipt.userId != context.userId || existingReceipt.operationId != context.requestId ||
                        existingReceipt.targetKind != BOOKMARK_ENSURE_KIND || existingReceipt.targetId != anchorHash ||
                        existingReceipt.requestHash != requestHash
                    ) {
                        return@withTransaction OperationResult.Rejected("IDEMPOTENCY_CONFLICT")
                    }
                    val original = decodeEnsureBookmarkReceipt(existingReceipt, documentId, anchorHash)
                        ?: return@withTransaction OperationResult.Rejected("RECEIPT_CORRUPTED")
                    return@withTransaction OperationResult.Ok(original, existingReceipt.operationId)
                }

                val existing = annotations.findBookmarkByAnchor(context.userId, documentId, anchorHash)
                val now = clock()
                val bookmark = existing ?: ReaderBookmarkEntity(
                    id = "bookmark-${ReaderMutationHasher.sha256("${context.userId}|$documentId|$anchorHash").take(32)}",
                    userId = context.userId,
                    documentId = documentId,
                    locatorJson = locatorJson,
                    anchorHash = anchorHash,
                    preview = normalizedPreview,
                    createdAt = now,
                )
                val view = ensureBookmarkView(bookmark, context.userId, documentId, anchorHash)
                    ?: return@withTransaction OperationResult.Rejected("STORAGE_FAILURE")
                val receipt = ReaderMutationReceiptEntity(
                    userId = context.userId,
                    operationId = context.requestId,
                    targetKind = BOOKMARK_ENSURE_KIND,
                    targetId = anchorHash,
                    requestHash = requestHash,
                    beforeRevision = if (existing == null) 0 else 1,
                    afterRevision = 1,
                    resultJson = "",
                    createdAt = now,
                ).let { it.copy(resultJson = ensureBookmarkResultJson(it, bookmark)) }

                if (existing == null) annotations.insertBookmark(bookmark)
                receipts.insertReceipt(receipt)
                if (annotations.findBookmarkByAnchor(context.userId, documentId, anchorHash) != bookmark ||
                    receipts.findReceipt(context.userId, context.requestId) != receipt
                ) {
                    throw SQLiteException("BOOKMARK_ENSURE_STORAGE_FAILURE")
                }
                OperationResult.Ok(view, receipt.operationId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SQLiteException) {
            OperationResult.Failed("STORAGE_FAILURE", retryable = true)
        }
    }

    suspend fun toggleBookmark(
        context: AccessContext,
        documentId: String,
        locator: ReaderLocator,
        preview: String,
    ): OperationResult<BookmarkToggleResult> {
        if (locator.documentId != documentId) return OperationResult.Rejected("INVALID_ARGUMENT")
        val validation = ReaderLocatorValidator.validate(locator)
        if (!validation.isValid) return OperationResult.Rejected("INVALID_ARGUMENT")
        val anchorHash = ReaderMutationHasher.bookmarkHash(locator)
        val payload = "bookmark|${ReaderLocatorCodec.encode(locator)}|${preview.take(240)}"
        return database.withTransaction {
            readableDocument(context, documentId, locator.contentRevision)
                ?: return@withTransaction OperationResult.Rejected("NOT_FOUND")
            val existingReceipt = receipts.findReceipt(context.userId, context.requestId)
            if (existingReceipt != null) {
                if (existingReceipt.requestHash !=
                    ReaderMutationHasher.requestHash("BOOKMARK", anchorHash, 1, payload)
                ) {
                    return@withTransaction OperationResult.Rejected("IDEMPOTENCY_CONFLICT")
                }
                return@withTransaction decodeBookmarkReceipt(context.userId, documentId, anchorHash, existingReceipt.operationId)
            }
            val existing = annotations.findBookmarkByAnchor(context.userId, documentId, anchorHash)
            val now = clock()
            val result: BookmarkToggleResult
            if (existing == null) {
                val bookmark = ReaderBookmarkEntity(
                    id = "bookmark-${ReaderMutationHasher.sha256("${context.userId}|$documentId|$anchorHash").take(32)}",
                    userId = context.userId,
                    documentId = documentId,
                    locatorJson = ReaderLocatorCodec.encode(locator),
                    anchorHash = anchorHash,
                    preview = preview.take(240),
                    createdAt = now,
                )
                annotations.insertBookmark(bookmark)
                result = BookmarkToggleResult.Added(toBookmarkView(bookmark))
            } else {
                annotations.deleteBookmark(context.userId, existing.id)
                result = BookmarkToggleResult.Removed(anchorHash)
            }
            receipts.insertReceipt(
                ReaderMutationReceiptEntity(
                    userId = context.userId,
                    operationId = context.requestId,
                    targetKind = "BOOKMARK",
                    targetId = anchorHash,
                    requestHash = ReaderMutationHasher.requestHash("BOOKMARK", anchorHash, 1, payload),
                    beforeRevision = if (existing == null) 0 else 1,
                    afterRevision = if (existing == null) 1 else 0,
                    resultJson = bookmarkResultJson(result),
                    createdAt = now,
                ),
            )
            OperationResult.Ok(result, context.requestId)
        }
    }

    suspend fun registerSourceReference(
        context: AccessContext,
        evidence: com.example.myapplication.domain.reader.SourceEvidence,
        extractionQuality: String = "VERIFIED_TEXT",
    ): OperationResult<SourceReferenceView> {
        val validation = ReaderLocatorValidator.validate(evidence.locator)
        if (!validation.isValid || evidence.locator.documentId != evidence.documentId) {
            return OperationResult.Rejected("INVALID_ARGUMENT")
        }
        if (evidence.quote.isBlank() || evidence.quote.length > MAX_QUOTE_CHARS) {
            return OperationResult.Rejected("INVALID_ARGUMENT")
        }
        val quoteHash = ReaderMutationHasher.sha256(evidence.quote)
        val key = ReaderMutationHasher.sha256(
            listOf(
                context.userId.toString(),
                evidence.documentId,
                evidence.contentRevision,
                ReaderLocatorCodec.encode(evidence.locator),
                quoteHash,
            ).joinToString("|"),
        )
        return database.withTransaction {
            readableDocument(context, evidence.documentId, evidence.contentRevision)
                ?: return@withTransaction OperationResult.Rejected("NOT_FOUND")
            val existing = annotations.findSourceReferenceByKey(context.userId, key)
            if (existing != null) return@withTransaction OperationResult.Ok(toSourceView(existing), context.requestId)
            val id = "source-${ReaderMutationHasher.sha256("${context.userId}|$key").take(32)}"
            val entity = ReaderSourceReferenceEntity(
                id = id,
                userId = context.userId,
                referenceKey = key,
                sourceDocumentId = evidence.documentId,
                sectionId = evidence.sectionId,
                sourceRevision = evidence.contentRevision,
                locatorJson = ReaderLocatorCodec.encode(evidence.locator),
                quoteSnapshot = evidence.quote.take(MAX_QUOTE_CHARS),
                quoteSha256 = quoteHash,
                bibliographicJson = buildJsonObject {
                    put("title", evidence.sourceTitleSnapshot.take(240))
                    put("extractorVersion", evidence.extractorVersion.take(120))
                }.toString(),
                evidenceKind = "READER_SELECTION",
                extractionQuality = extractionQuality,
                createdAt = clock(),
            )
            annotations.insertSourceReference(entity)
            OperationResult.Ok(toSourceView(entity), context.requestId)
        }
    }

    suspend fun lookupSourceReference(
        context: AccessContext,
        referenceId: String,
    ): OperationResult<SourceReferenceView> {
        val found = annotations.findSourceReference(context.userId, referenceId)
            ?: return OperationResult.Rejected("NOT_FOUND")
        return OperationResult.Ok(toSourceView(found), context.requestId)
    }

    suspend fun registerEncyclopediaSourceReference(
        context: AccessContext,
        locator: EncyclopediaSourceLocator,
        title: String,
        quote: String,
    ): OperationResult<SourceReferenceView> {
        if (context.userId <= 0L || quote.isBlank() || quote.length > MAX_QUOTE_CHARS) {
            return OperationResult.Rejected("INVALID_ARGUMENT")
        }
        val locatorJson = EncyclopediaSourceLocator.encode(locator)
        val quoteHash = ReaderMutationHasher.sha256(quote)
        val key = ReaderMutationHasher.sha256(
            listOf(
                context.userId.toString(),
                EVIDENCE_KIND_ENCYCLOPEDIA,
                locator.kind.wire,
                locator.entryId,
                locator.sectionId,
                locator.contentRevision,
                locator.openClues.joinToString(","),
                quoteHash,
            ).joinToString("|"),
        )
        return database.withTransaction {
            val existing = annotations.findSourceReferenceByKey(context.userId, key)
            if (existing != null) return@withTransaction OperationResult.Ok(toSourceView(existing), context.requestId)
            val id = "source-enc-${ReaderMutationHasher.sha256("${context.userId}|$key").take(32)}"
            val entity = ReaderSourceReferenceEntity(
                id = id,
                userId = context.userId,
                referenceKey = key,
                sourceDocumentId = null,
                sectionId = null,
                sourceRevision = locator.contentRevision,
                locatorJson = locatorJson,
                quoteSnapshot = quote.take(MAX_QUOTE_CHARS),
                quoteSha256 = quoteHash,
                bibliographicJson = buildJsonObject {
                    put("title", title.take(240))
                    put("sectionTitle", locator.sectionTitle.take(120))
                    put("kind", locator.kind.wire)
                    put("entryId", locator.entryId)
                }.toString(),
                evidenceKind = EVIDENCE_KIND_ENCYCLOPEDIA,
                extractionQuality = "VERIFIED_TEXT",
                createdAt = clock(),
            )
            annotations.insertSourceReference(entity)
            OperationResult.Ok(toSourceView(entity), context.requestId)
        }
    }

    private suspend fun readableDocument(
        context: AccessContext,
        documentId: String,
        revision: String?,
    ): ReaderDocumentEntity? {
        val document = catalog.findDocument(documentId) ?: return null
        val decision = ReaderAccessPolicy.canRead(
            context.userId,
            ReaderDocumentAccessRecord(
                documentId = document.id,
                kind = document.kind,
                ownerUserId = document.ownerUserId,
                contentRevision = document.contentRevision,
                accessEpoch = document.accessEpoch,
                importStatus = document.importStatus,
                deletedAt = document.deletedAt,
            ),
        )
        if (decision !is ReaderAccessDecision.Allowed) return null
        if (revision != null && document.contentRevision != revision) return null
        return document
    }

    private fun probeAccess(context: AccessContext, document: ReaderDocumentEntity): ReaderAccessDecision =
        ReaderAccessPolicy.canRead(
            context.userId,
            ReaderDocumentAccessRecord(
                documentId = document.id,
                kind = document.kind,
                ownerUserId = document.ownerUserId,
                contentRevision = document.contentRevision,
                accessEpoch = document.accessEpoch,
                importStatus = document.importStatus,
                deletedAt = document.deletedAt,
            ),
        )

    private suspend fun toView(entity: ReaderAnnotationEntity): ReaderAnnotationView? {
        val kind = runCatching { AnnotationKind.valueOf(entity.kind) }.getOrNull() ?: return null
        if (kind != AnnotationKind.INK) return textStore.view(entity)
        return ReaderAnnotationView(
            id = entity.id,
            documentId = entity.documentId,
            sectionId = entity.sectionId,
            kind = kind,
            locator = ReaderLocatorJson.decode(entity.locatorJson),
            quote = entity.quoteSnapshot,
            noteText = entity.noteText,
            style = AnnotationStyle.decode(entity.styleJson),
            inkBlobPath = entity.inkBlobPath,
            revision = entity.revision,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
        )
    }

    private fun toBookmarkView(entity: ReaderBookmarkEntity): ReaderBookmarkView =
        ReaderBookmarkView(
            id = entity.id,
            documentId = entity.documentId,
            locator = ReaderLocatorJson.decode(entity.locatorJson),
            anchorHash = entity.anchorHash,
            preview = entity.preview,
            createdAt = entity.createdAt,
        )

    private fun toSourceView(entity: ReaderSourceReferenceEntity): SourceReferenceView =
        SourceReferenceView(
            id = entity.id,
            documentId = entity.sourceDocumentId,
            sectionId = entity.sectionId,
            contentRevision = entity.sourceRevision,
            locator = if (entity.evidenceKind == EVIDENCE_KIND_ENCYCLOPEDIA) {
                null
            } else {
                ReaderLocatorJson.decode(entity.locatorJson)
            },
            quote = entity.quoteSnapshot,
            quoteSha256 = entity.quoteSha256,
            sourceTitle = runCatching {
                Json.parseToJsonElement(entity.bibliographicJson).jsonObject["title"]
                    ?.let { (it as? JsonPrimitive)?.content }
            }.getOrNull().orEmpty(),
            sourceAvailable = entity.sourceDocumentId != null || entity.evidenceKind == EVIDENCE_KIND_ENCYCLOPEDIA,
            evidenceKind = entity.evidenceKind,
            rawLocatorJson = entity.locatorJson,
        )

    private suspend fun decodeBookmarkReceipt(
        userId: Long,
        documentId: String,
        anchorHash: String,
        operationId: String,
    ): OperationResult<BookmarkToggleResult> {
        val bookmark = annotations.findBookmarkByAnchor(userId, documentId, anchorHash)
        return if (bookmark != null) {
            OperationResult.Ok(BookmarkToggleResult.Added(toBookmarkView(bookmark)), operationId)
        } else {
            OperationResult.Ok(BookmarkToggleResult.Removed(anchorHash), operationId)
        }
    }

    private fun jsonResult(annotationId: String): String = buildJsonObject {
        put("annotationId", annotationId)
    }.toString()

    private fun bookmarkResultJson(result: BookmarkToggleResult): String = when (result) {
        is BookmarkToggleResult.Added -> buildJsonObject {
            put("added", true)
            put("bookmarkId", result.bookmark.id)
        }.toString()

        is BookmarkToggleResult.Removed -> buildJsonObject {
            put("added", false)
            put("anchorHash", result.anchorHash)
        }.toString()
    }

    private fun normalizeEnsureBookmarkPreview(preview: String): String {
        var end = minOf(preview.length, BOOKMARK_ENSURE_MAX_PREVIEW_CHARS)
        if (end < preview.length && end > 0 && Character.isHighSurrogate(preview[end - 1]) &&
            Character.isLowSurrogate(preview[end])
        ) {
            end -= 1
        }
        return preview.substring(0, end)
    }

    private fun ensureBookmarkView(
        bookmark: ReaderBookmarkEntity,
        userId: Long,
        documentId: String,
        anchorHash: String,
    ): ReaderBookmarkView? {
        if (bookmark.id.isBlank() || bookmark.userId != userId || bookmark.documentId != documentId ||
            bookmark.anchorHash != anchorHash
        ) return null
        val locator = try {
            ReaderLocatorCodec.decode(bookmark.locatorJson)
        } catch (_: ReaderLocatorCodecException) {
            return null
        }
        if (locator.documentId != documentId || ReaderMutationHasher.bookmarkHash(locator) != anchorHash) return null
        return ReaderBookmarkView(
            bookmark.id, bookmark.documentId, locator, bookmark.anchorHash, bookmark.preview, bookmark.createdAt,
        )
    }

    private fun ensureBookmarkResultJson(receipt: ReaderMutationReceiptEntity, bookmark: ReaderBookmarkEntity): String {
        val snapshot = buildJsonObject {
            put("id", bookmark.id)
            put("documentId", bookmark.documentId)
            put("locatorJson", bookmark.locatorJson)
            put("anchorHash", bookmark.anchorHash)
            put("preview", bookmark.preview)
            put("createdAt", bookmark.createdAt.toString())
        }
        return buildJsonObject {
            put("snapshotVersion", BOOKMARK_ENSURE_SNAPSHOT_VERSION)
            put("bookmark", snapshot)
            put("snapshotHash", ensureBookmarkSnapshotHash(receipt, snapshot))
        }.toString()
    }

    private fun decodeEnsureBookmarkReceipt(
        receipt: ReaderMutationReceiptEntity,
        documentId: String,
        anchorHash: String,
    ): ReaderBookmarkView? {
        if (receipt.beforeRevision !in 0L..1L || receipt.afterRevision != 1L) return null
        val root = try {
            Json.parseToJsonElement(receipt.resultJson) as? JsonObject
        } catch (_: IllegalArgumentException) {
            return null
        } ?: return null
        if (root.keys != BOOKMARK_ENSURE_RECEIPT_FIELDS) return null
        val version = root["snapshotVersion"] as? JsonPrimitive ?: return null
        if (version.isString || version.intOrNull != BOOKMARK_ENSURE_SNAPSHOT_VERSION) return null
        val snapshot = root["bookmark"] as? JsonObject ?: return null
        if (snapshot.keys != BOOKMARK_ENSURE_SNAPSHOT_FIELDS.toSet()) return null
        val id = snapshot.ensureSnapshotString("id") ?: return null
        val snapshotDocumentId = snapshot.ensureSnapshotString("documentId") ?: return null
        val locatorJson = snapshot.ensureSnapshotString("locatorJson") ?: return null
        val snapshotAnchorHash = snapshot.ensureSnapshotString("anchorHash") ?: return null
        val preview = snapshot.ensureSnapshotString("preview") ?: return null
        val createdAtText = snapshot.ensureSnapshotString("createdAt") ?: return null
        val createdAt = createdAtText.toLongOrNull() ?: return null
        if (id.isBlank() || snapshotDocumentId != documentId || snapshotAnchorHash != anchorHash ||
            createdAt.toString() != createdAtText
        ) return null
        val locator = try {
            ReaderLocatorCodec.decode(locatorJson)
        } catch (_: ReaderLocatorCodecException) {
            return null
        }
        if (locator.documentId != documentId || ReaderMutationHasher.bookmarkHash(locator) != anchorHash) return null
        val orderedSnapshot = buildJsonObject {
            BOOKMARK_ENSURE_SNAPSHOT_FIELDS.forEach { field -> put(field, snapshot.getValue(field)) }
        }
        if (root.ensureSnapshotString("snapshotHash") != ensureBookmarkSnapshotHash(receipt, orderedSnapshot)) return null
        // The saved locator may predate the input revision or differ in non-anchor metadata for an existing bookmark.
        return ReaderBookmarkView(id, snapshotDocumentId, locator, snapshotAnchorHash, preview, createdAt)
    }

    private fun ensureBookmarkSnapshotHash(receipt: ReaderMutationReceiptEntity, snapshot: JsonObject): String =
        ReaderMutationHasher.sha256(buildJsonObject {
            put("snapshotVersion", BOOKMARK_ENSURE_SNAPSHOT_VERSION)
            put("userId", receipt.userId.toString())
            put("operationId", receipt.operationId)
            put("targetKind", receipt.targetKind)
            put("targetId", receipt.targetId)
            put("requestHash", receipt.requestHash)
            put("beforeRevision", receipt.beforeRevision.toString())
            put("afterRevision", receipt.afterRevision.toString())
            put("createdAt", receipt.createdAt.toString())
            put("bookmark", snapshot)
        }.toString())

    private fun JsonObject.ensureSnapshotString(field: String): String? =
        (this[field] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun deterministicAnnotationId(context: AccessContext, documentId: String, requestId: String): String =
        "annotation-${ReaderMutationHasher.sha256("${context.userId}|$documentId|$requestId").take(32)}"

    companion object {
        const val EVIDENCE_KIND_ENCYCLOPEDIA = "MERIDIAN_ENCYCLOPEDIA"
        private const val MAX_QUOTE_CHARS = 8192
        private const val MAX_NOTE_CHARS = 1_000_000
        private const val BOOKMARK_ENSURE_KIND = "BOOKMARK_ENSURE"
        private const val BOOKMARK_ENSURE_MAX_PREVIEW_CHARS = 160
        private const val BOOKMARK_ENSURE_SNAPSHOT_VERSION = 1
        private val BOOKMARK_ENSURE_SNAPSHOT_FIELDS = listOf(
            "id", "documentId", "locatorJson", "anchorHash", "preview", "createdAt",
        )
        private val BOOKMARK_ENSURE_RECEIPT_FIELDS = setOf("snapshotVersion", "bookmark", "snapshotHash")
    }
}

