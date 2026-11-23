package com.example.myapplication.data.reader.notes

import android.database.Cursor
import android.database.SQLException
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.AnnotationTargetEntity
import com.example.myapplication.data.local.entity.ReaderAnnotationEntity
import com.example.myapplication.data.local.entity.ReaderDocumentEntity
import com.example.myapplication.data.local.entity.ReaderMutationReceiptEntity
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.ReaderAccessDecision
import com.example.myapplication.domain.reader.ReaderAccessPolicy
import com.example.myapplication.domain.reader.ReaderDocumentAccessRecord
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import com.example.myapplication.domain.reader.ReaderLocatorValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Text mutations only. Receipt replays never use a later annotation as an earlier result. */
internal class AnnotationV2Store(private val database: AppDatabase, private val clock: () -> Long) {
    private val annotations get() = database.readerAnnotationDao()
    private val catalog get() = database.readerCatalogDao()
    private val receipts get() = database.readerNotebookDao()

    suspend fun create(
        context: AccessContext,
        documentId: String,
        kind: AnnotationKind,
        locator: ReaderLocator,
        quote: String?,
        style: AnnotationStyle,
        noteText: String?,
        inkBlobPath: String?,
        targets: List<AnnotationTargetInput>?,
        writeGuard: AnnotationWriteGuard?,
    ): OperationResult<ReaderAnnotationView> {
        if (inkBlobPath != null || kind == AnnotationKind.INK) return OperationResult.Rejected("UNSUPPORTED_INK_PAYLOAD")
        if (!AnnotationMutationCodec.validQuote(quote) || !AnnotationMutationCodec.validNote(noteText) || locator.documentId != documentId ||
            !ReaderLocatorValidator.isWellFormedUtf16(context.requestId)) {
            return OperationResult.Rejected("INVALID_ARGUMENT")
        }
        // No suspension may precede this copy: Room can queue behind an unrelated transaction.
        val parent = AnnotationTargetValidation.freezeLocator(locator)
        val inputs = AnnotationTargetValidation.freezeTargets(targets ?: listOf(AnnotationTargetInput(parent, "UNRESOLVED")))
        if (inputs.isEmpty()) return OperationResult.Rejected("INVALID_ARGUMENT")
        val id = "annotation-${ReaderMutationHasher.sha256("${context.userId}|$documentId|${context.requestId}").take(32)}"
        val canonical = try {
            request("CREATE", kind, id, 1, parent, quote, noteText, style, inputs)
        } catch (_: IllegalArgumentException) {
            return OperationResult.Rejected("INVALID_ARGUMENT")
        }
        return transaction {
            val document = readable(context, documentId)
            val receipt = receipts.findReceipt(context.userId, context.requestId)
            if (receipt != null) {
                checkReceiptIdentity(receipt, context, id)
                return@transaction when (receipt.targetKind) {
                    "ANNOTATION_V2" -> {
                        val snapshot = snapshot(receipt, context, "CREATE")
                        if (canonical != snapshot.requestPayload || receipt.requestHash != requestHash(id, 1, canonical)) reject("IDEMPOTENCY_CONFLICT")
                        OperationResult.Ok(snapshot.view, snapshot.operationId)
                    }
                    "ANNOTATION" -> legacyCreate(receipt, context, id, kind, parent, quote, noteText, style, inputs)
                    else -> reject("IDEMPOTENCY_CONFLICT")
                }
            }
            val validated = validated(parent, inputs)
            if (AnnotationTargetValidation.requiresSourceGuard(validated) && writeGuard == null) reject("SOURCE_PROOF_REQUIRED")
            checkpoint(context, document, parent.contentRevision, writeGuard, validated, AnnotationWriteCheckpoint.BEFORE_WRITE)
            val now = clock().coerceAtLeast(0L)
            val newEntity = ReaderAnnotationEntity(
                id = id, userId = context.userId, documentId = documentId, sectionId = parent.sectionId,
                kind = kind.name, locatorJson = ReaderLocatorCodec.encode(parent), quoteSnapshot = quote,
                noteText = noteText, styleJson = style.encode(), inkBlobPath = null, revision = 1,
                createdAt = now, updatedAt = now,
            )
            annotations.insertAnnotation(newEntity)
            val intendedTargets = validated.mapIndexed { ordinal, target ->
                AnnotationTargetEntity(
                    targetId = "annotation-target-${ReaderMutationHasher.sha256("$id|$ordinal").take(32)}",
                    annotationId = id, ordinal = ordinal, locatorJson = ReaderLocatorCodec.encode(target.locator),
                    anchorVersion = 1, precision = target.precision,
                )
            }
            database.annotationTargetDao().insertTargets(intendedTargets)
            assertAnnotationStored(newEntity)
            val success = annotations.findAnnotation(context.userId, id) ?: storageAbort()
            val physical = physicalTargets(id, ::storageAbort)
            if (success != newEntity || physical != intendedTargets) storageAbort()
            val view = toView(success, physical)
            val savedReceipt = ReaderMutationReceiptEntity(
                userId = context.userId, operationId = context.requestId, targetKind = "ANNOTATION_V2", targetId = id,
                requestHash = requestHash(id, 1, canonical), beforeRevision = 0, afterRevision = 1,
                resultJson = resultJson("CREATE", context.requestId, canonical, success, physical), createdAt = now,
            )
            receipts.insertReceipt(savedReceipt)
            // The actual receipt is present before this callback; a non-Ok must abort Room.
            checkpoint(context, document, parent.contentRevision, writeGuard, validated, AnnotationWriteCheckpoint.BEFORE_COMMIT)
            assertStored(newEntity, intendedTargets, savedReceipt)
            OperationResult.Ok(view, context.requestId)
        }
    }

    suspend fun update(
        context: AccessContext,
        annotationId: String,
        baseRevision: Long,
        noteText: String?,
        style: AnnotationStyle?,
        writeGuard: AnnotationWriteGuard?,
    ): OperationResult<ReaderAnnotationView> {
        if (!AnnotationMutationCodec.validNote(noteText) || baseRevision <= 0 || annotationId.isBlank() ||
            !ReaderLocatorValidator.isWellFormedUtf16(annotationId) || !ReaderLocatorValidator.isWellFormedUtf16(context.requestId)) {
            return OperationResult.Rejected("INVALID_ARGUMENT")
        }
        return transaction {
            val receipt = receipts.findReceipt(context.userId, context.requestId)
            if (receipt != null) {
                checkReceiptIdentity(receipt, context, annotationId)
                when (receipt.targetKind) {
                    "ANNOTATION_V2" -> {
                        val original = snapshot(receipt, context, "UPDATE")
                        readable(context, original.annotation.documentId)
                        val canonical = request("UPDATE", original.view.kind, annotationId, baseRevision,
                            requireNotNull(original.view.locator), original.view.quote, noteText, style ?: original.view.style, original.view.targets)
                        if (canonical != original.requestPayload || receipt.requestHash != requestHash(annotationId, baseRevision, canonical)) reject("IDEMPOTENCY_CONFLICT")
                        return@transaction OperationResult.Ok(original.view, original.operationId)
                    }
                    "ANNOTATION" -> return@transaction legacyUpdate(receipt, context, annotationId, baseRevision, noteText, style)
                    else -> reject("IDEMPOTENCY_CONFLICT")
                }
            }
            val current = annotations.findAnnotation(context.userId, annotationId) ?: reject("NOT_FOUND")
            val document = readable(context, current.documentId)
            if (current.deletedAt != null) reject("NOT_FOUND")
            if (current.inkBlobPath != null || current.kind == AnnotationKind.INK.name) reject("UNSUPPORTED_INK_PAYLOAD")
            val kind = textKind(current.kind)
            if (current.revision != baseRevision) abort(conflict(current.revision))
            if (current.revision == Long.MAX_VALUE || current.revision <= 0) reject("REVISION_OUT_OF_RANGE")
            if (current.createdAt < 0 || current.updatedAt < current.createdAt) reject("INVALID_ARGUMENT")
            assertAnnotationStored(current) { reject("INVALID_ARGUMENT") }
            val parent = strictLocator(current.locatorJson)
            if (parent.documentId != current.documentId || (current.sectionId != null && parent.sectionId != current.sectionId) ||
                !AnnotationMutationCodec.validRetainedQuote(current.quoteSnapshot)) reject("INVALID_ARGUMENT")
            val physical = physicalTargets(annotationId)
            if (physical.isNotEmpty() && (current.sectionId != parent.sectionId || !AnnotationMutationCodec.validQuote(current.quoteSnapshot))) reject("INVALID_ARGUMENT")
            val inputs = targetInputs(parent, physical)
            val validated = validated(parent, inputs)
            if (AnnotationTargetValidation.requiresSourceGuard(validated) && writeGuard == null) reject("SOURCE_PROOF_REQUIRED")
            val styleValue = style ?: AnnotationStyle.decode(current.styleJson)
            val canonical = request("UPDATE", kind, annotationId, baseRevision, parent, current.quoteSnapshot, noteText, styleValue, validated)
            checkpoint(context, document, parent.contentRevision, writeGuard, validated, AnnotationWriteCheckpoint.BEFORE_WRITE)
            val now = maxOf(clock().coerceAtLeast(0L), current.createdAt, current.updatedAt)
            val changed = annotations.updateAnnotation(
                userId = context.userId, annotationId = annotationId, expectedRevision = baseRevision,
                locatorJson = current.locatorJson, quoteSnapshot = current.quoteSnapshot, noteText = noteText,
                styleJson = styleValue.encode(), inkBlobPath = current.inkBlobPath, now = now,
            )
            if (changed != 1) {
                val latest = annotations.findAnnotation(context.userId, annotationId)
                abort(conflict(latest?.revision ?: baseRevision))
            }
            val intended = current.copy(noteText = noteText, styleJson = styleValue.encode(), revision = baseRevision + 1, updatedAt = now)
            assertAnnotationStored(intended)
            val success = annotations.findAnnotation(context.userId, annotationId) ?: storageAbort()
            val actualTargets = physicalTargets(annotationId, ::storageAbort)
            if (success != intended || actualTargets != physical) storageAbort()
            val view = toView(success, actualTargets)
            val savedReceipt = ReaderMutationReceiptEntity(
                userId = context.userId, operationId = context.requestId, targetKind = "ANNOTATION_V2", targetId = annotationId,
                requestHash = requestHash(annotationId, baseRevision, canonical), beforeRevision = baseRevision, afterRevision = baseRevision + 1,
                resultJson = resultJson("UPDATE", context.requestId, canonical, success, actualTargets), createdAt = now,
            )
            receipts.insertReceipt(savedReceipt)
            checkpoint(context, document, parent.contentRevision, writeGuard, validated, AnnotationWriteCheckpoint.BEFORE_COMMIT)
            assertStored(intended, physical, savedReceipt)
            OperationResult.Ok(view, context.requestId)
        }
    }

    suspend fun lifecycle(context: AccessContext, annotationId: String, baseRevision: Long,
        action: String, writeGuard: AnnotationWriteGuard?): OperationResult<ReaderAnnotationView> {
        if (action !in setOf("DELETE", "RESTORE") || annotationId.isBlank() || baseRevision <= 0 ||
            !ReaderLocatorValidator.isWellFormedUtf16(annotationId) ||
            !ReaderLocatorValidator.isWellFormedUtf16(context.requestId)) return OperationResult.Rejected("INVALID_ARGUMENT")
        return transaction {
            val receipt = receipts.findReceipt(context.userId, context.requestId)
            if (receipt != null) {
                checkReceiptIdentity(receipt, context, annotationId)
                if (receipt.targetKind != "ANNOTATION_V2") reject("IDEMPOTENCY_CONFLICT")
                val saved = snapshot(receipt, context, action)
                readable(context, saved.view.documentId)
                if (receipt.beforeRevision != baseRevision) reject("IDEMPOTENCY_CONFLICT")
                return@transaction OperationResult.Ok(saved.view, saved.operationId)
            }
            val current = annotations.findAnnotation(context.userId, annotationId) ?: reject("NOT_FOUND")
            val document = readable(context, current.documentId)
            if (current.kind == AnnotationKind.INK.name || current.inkBlobPath != null) reject("UNSUPPORTED_INK_PAYLOAD")
            if (current.revision != baseRevision) abort(conflict(current.revision))
            if (baseRevision == Long.MAX_VALUE) reject("REVISION_OUT_OF_RANGE")
            if ((action == "RESTORE") != (current.deletedAt != null)) reject("INVALID_LIFECYCLE_STATE")
            assertAnnotationStored(current) { reject("INVALID_ARGUMENT") }
            val parent = strictLocator(current.locatorJson)
            if (parent.documentId != current.documentId ||
                !AnnotationMutationCodec.validRetainedQuote(current.quoteSnapshot) ||
                !AnnotationMutationCodec.validNote(current.noteText)) reject("INVALID_ARGUMENT")
            val physical = physicalTargets(annotationId)
            val inputs = validated(parent, targetInputs(parent, physical))
            if (action == "RESTORE" && AnnotationTargetValidation.requiresSourceGuard(inputs) && writeGuard == null)
                reject("SOURCE_PROOF_REQUIRED")
            val canonical = request(action, textKind(current.kind), annotationId, baseRevision,
                parent, current.quoteSnapshot, current.noteText, strictStyle(current.styleJson), inputs)
            checkpoint(context, document, parent.contentRevision, writeGuard, inputs, AnnotationWriteCheckpoint.BEFORE_WRITE)
            val now = maxOf(clock().coerceAtLeast(0L), current.createdAt, current.updatedAt)
            val intended = current.copy(deletedAt = if (action == "DELETE") now else null,
                revision = baseRevision + 1, updatedAt = now)
            val statement = database.openHelper.writableDatabase.compileStatement(
                "UPDATE reader_annotations SET deleted_at=?, revision=revision+1, updated_at=? " +
                    "WHERE id=? AND user_id=? AND revision=? AND deleted_at IS ${if (action == "DELETE") "NULL" else "NOT NULL"}")
            val changed = try {
                if (intended.deletedAt == null) statement.bindNull(1) else statement.bindLong(1, intended.deletedAt)
                statement.bindLong(2, now); statement.bindString(3, annotationId)
                statement.bindLong(4, context.userId); statement.bindLong(5, baseRevision)
                statement.executeUpdateDelete()
            } finally { statement.close() }
            if (changed != 1) abort(conflict(annotations.findAnnotation(context.userId, annotationId)?.revision ?: baseRevision))
            assertAnnotationStored(intended)
            val actualTargets = physicalTargets(annotationId, ::storageAbort)
            if (actualTargets != physical) storageAbort()
            val savedReceipt = ReaderMutationReceiptEntity(
                userId = context.userId, operationId = context.requestId, targetKind = "ANNOTATION_V2", targetId = annotationId,
                requestHash = requestHash(annotationId, baseRevision, canonical), beforeRevision = baseRevision,
                afterRevision = baseRevision + 1, resultJson = resultJson(action, context.requestId, canonical, intended, physical), createdAt = now)
            receipts.insertReceipt(savedReceipt)
            checkpoint(context, document, parent.contentRevision, writeGuard, inputs, AnnotationWriteCheckpoint.BEFORE_COMMIT)
            assertStored(intended, physical, savedReceipt)
            OperationResult.Ok(toView(intended, physical), context.requestId)
        }
    }

    suspend fun view(entity: ReaderAnnotationEntity): ReaderAnnotationView? = try {
        database.withTransaction {
            readable(AccessContext(entity.userId, 0, "annotation-flow-read"), entity.documentId)
            toView(entity, physicalTargets(entity.id))
        }
    } catch (_: Abort) {
        null
    }

    private suspend fun validated(parent: ReaderLocator, inputs: List<AnnotationTargetInput>): List<AnnotationTargetInput> {
        val sections = catalog.findSections(parent.documentId, parent.contentRevision)
        return when (val result = AnnotationTargetValidation.validateAndFreeze(parent, inputs, sections)) {
            is OperationResult.Ok -> result.value
            else -> abort(result)
        }
    }

    private suspend fun assertStored(entity: ReaderAnnotationEntity, targets: List<AnnotationTargetEntity>, receipt: ReaderMutationReceiptEntity) {
        assertAnnotationStored(entity)
        if (physicalTargets(entity.id, ::storageAbort) != targets) storageAbort()
        assertReceiptStored(receipt)
        currentCoroutineContext().ensureActive()
    }

    /** Match the actual SQLite row before a typed DAO can coerce REAL into Long. */
    private fun assertAnnotationStored(entity: ReaderAnnotationEntity, onInvalid: () -> Nothing = { storageAbort() }) {
        database.openHelper.writableDatabase.query(SimpleSQLiteQuery(
            "SELECT id,user_id,document_id,section_id,kind,locator_json,quote_snapshot,note_text,style_json,ink_blob_path," +
                "revision,deleted_at,created_at,updated_at FROM reader_annotations WHERE user_id=? AND id=?",
            arrayOf(entity.userId, entity.id),
        )).use { cursor ->
            if (!cursor.moveToFirst() ||
                !cursor.matchesText(0, entity.id) || !cursor.matchesInteger(1, entity.userId) ||
                !cursor.matchesText(2, entity.documentId) || !cursor.matchesText(3, entity.sectionId) ||
                !cursor.matchesText(4, entity.kind) || !cursor.matchesText(5, entity.locatorJson) ||
                !cursor.matchesText(6, entity.quoteSnapshot) || !cursor.matchesText(7, entity.noteText) ||
                !cursor.matchesText(8, entity.styleJson) || !cursor.matchesText(9, entity.inkBlobPath) ||
                !cursor.matchesInteger(10, entity.revision) || !cursor.matchesInteger(11, entity.deletedAt) ||
                !cursor.matchesInteger(12, entity.createdAt) || !cursor.matchesInteger(13, entity.updatedAt) ||
                cursor.moveToNext()
            ) onInvalid()
        }
    }

    private fun assertReceiptStored(receipt: ReaderMutationReceiptEntity, onInvalid: () -> Nothing = { storageAbort() }) {
        database.openHelper.writableDatabase.query(SimpleSQLiteQuery(
            "SELECT user_id,operation_id,target_kind,target_id,request_hash,before_revision,after_revision,result_json,created_at " +
                "FROM reader_mutation_receipts WHERE user_id=? AND operation_id=?",
            arrayOf(receipt.userId, receipt.operationId),
        )).use { cursor ->
            if (!cursor.moveToFirst() ||
                !cursor.matchesInteger(0, receipt.userId) || !cursor.matchesText(1, receipt.operationId) ||
                !cursor.matchesText(2, receipt.targetKind) || !cursor.matchesText(3, receipt.targetId) ||
                !cursor.matchesText(4, receipt.requestHash) || !cursor.matchesInteger(5, receipt.beforeRevision) ||
                !cursor.matchesInteger(6, receipt.afterRevision) || !cursor.matchesText(7, receipt.resultJson) ||
                !cursor.matchesInteger(8, receipt.createdAt) || cursor.moveToNext()
            ) onInvalid()
        }
    }

    private fun Cursor.matchesText(index: Int, expected: String?): Boolean = if (expected == null) {
        isNull(index) && getType(index) == Cursor.FIELD_TYPE_NULL
    } else {
        !isNull(index) && getType(index) == Cursor.FIELD_TYPE_STRING && getString(index) == expected
    }

    private fun Cursor.matchesInteger(index: Int, expected: Long?): Boolean = if (expected == null) {
        isNull(index) && getType(index) == Cursor.FIELD_TYPE_NULL
    } else {
        !isNull(index) && getType(index) == Cursor.FIELD_TYPE_INTEGER && getLong(index) == expected
    }

    private suspend fun readable(context: AccessContext, id: String): ReaderDocumentEntity {
        val document = catalog.findDocument(id) ?: reject("NOT_FOUND")
        val allowed = ReaderAccessPolicy.canRead(context.userId, ReaderDocumentAccessRecord(
            documentId = document.id, kind = document.kind, ownerUserId = document.ownerUserId,
            contentRevision = document.contentRevision, accessEpoch = document.accessEpoch,
            importStatus = document.importStatus, deletedAt = document.deletedAt,
        ))
        if (allowed !is ReaderAccessDecision.Allowed) reject("NOT_FOUND")
        return document
    }

    private suspend fun checkpoint(
        context: AccessContext,
        opened: ReaderDocumentEntity,
        contentRevision: String,
        guard: AnnotationWriteGuard?,
        targets: List<AnnotationTargetInput>,
        point: AnnotationWriteCheckpoint,
    ) {
        currentCoroutineContext().ensureActive()
        suspend fun verify() {
            val actual = readable(context, opened.id)
            if (guard != null) {
                if (guard.expectedDocumentId != actual.id || guard.expectedContentRevision != actual.contentRevision || guard.expectedAccessEpoch != actual.accessEpoch) reject("SOURCE_PROOF_STALE")
            }
            if (actual.contentRevision != contentRevision || actual.accessEpoch != opened.accessEpoch) reject("SOURCE_PROOF_STALE")
        }
        verify()
        if (guard != null) {
            when (val result = guard.check(context, point, targets)) {
                is OperationResult.Ok -> Unit
                else -> abort(result)
            }
        }
        currentCoroutineContext().ensureActive()
        verify()
    }

    /** Existing corruption rejects the request; any corruption after a write aborts the transaction. */
    private fun physicalTargets(annotationId: String, onInvalid: () -> Nothing = { reject("INVALID_ARGUMENT") }): List<AnnotationTargetEntity> =
        database.openHelper.writableDatabase.query(SimpleSQLiteQuery(
            "SELECT target_id,annotation_id,ordinal,locator_json,anchor_version,precision FROM reader_annotation_targets WHERE annotation_id=? ORDER BY ordinal",
            arrayOf(annotationId),
        )).use { cursor ->
            val result = ArrayList<AnnotationTargetEntity>()
            val identities = HashSet<String>()
            while (cursor.moveToNext()) {
                val id = cursor.requiredText(0, onInvalid)
                val parentId = cursor.requiredText(1, onInvalid)
                val ordinal = cursor.requiredInteger(2, onInvalid)
                val locator = cursor.requiredText(3, onInvalid)
                val version = cursor.requiredInteger(4, onInvalid)
                val precision = cursor.requiredText(5, onInvalid)
                if (id.isBlank() || !ReaderLocatorValidator.isWellFormedUtf16(id) || !identities.add(id) || parentId != annotationId ||
                    ordinal < 0 || ordinal > Int.MAX_VALUE.toLong() || ordinal != result.size.toLong() ||
                    version != 1L || precision !in setOf("EXACT", "PAGE_ONLY", "UNRESOLVED")) onInvalid()
                strictLocator(locator, onInvalid)
                result += AnnotationTargetEntity(id, parentId, ordinal.toInt(), locator, version.toInt(), precision)
            }
            result
        }

    private fun Cursor.requiredText(index: Int, onInvalid: () -> Nothing): String {
        if (isNull(index) || getType(index) != Cursor.FIELD_TYPE_STRING) onInvalid()
        return getString(index) ?: onInvalid()
    }

    private fun Cursor.requiredInteger(index: Int, onInvalid: () -> Nothing): Long {
        if (isNull(index) || getType(index) != Cursor.FIELD_TYPE_INTEGER) onInvalid()
        return getLong(index)
    }

    private fun strictLocator(json: String, onInvalid: () -> Nothing = { reject("INVALID_ARGUMENT") }): ReaderLocator = try {
        ReaderLocatorCodec.decode(json).also { AnnotationMutationCodec.locatorProjection(it) }
    } catch (_: IllegalArgumentException) {
        onInvalid()
    }

    private fun hasPhysicalTargets(id: String): Boolean = database.openHelper.writableDatabase.query(
        SimpleSQLiteQuery("SELECT 1 FROM reader_annotation_targets WHERE annotation_id=? LIMIT 1", arrayOf(id)),
    ).use { it.moveToFirst() }

    private fun targetInputs(parent: ReaderLocator, physical: List<AnnotationTargetEntity>): List<AnnotationTargetInput> =
        AnnotationTargetValidation.freezeTargets(if (physical.isEmpty()) listOf(AnnotationTargetInput(parent, "UNRESOLVED"))
        else physical.map { AnnotationTargetInput(strictLocator(it.locatorJson), it.precision) })

    private fun toView(entity: ReaderAnnotationEntity, targets: List<AnnotationTargetEntity>): ReaderAnnotationView {
        // Migrated whole-page records can legitimately have no quote or section_id.
        // Reading those records preserves their fields; it does not admit a new null-quote mutation.
        if (entity.quoteSnapshot?.let { !ReaderLocatorValidator.isWellFormedUtf16(it) } == true ||
            entity.noteText?.let { !ReaderLocatorValidator.isWellFormedUtf16(it) } == true ||
            !ReaderLocatorValidator.isWellFormedUtf16(entity.styleJson)) reject("INVALID_ARGUMENT")
        val parent = strictLocator(entity.locatorJson)
        if (parent.documentId != entity.documentId || (entity.sectionId != null && parent.sectionId != entity.sectionId)) reject("INVALID_ARGUMENT")
        val inputs = targetInputs(parent, targets)
        if (!sameLocator(parent, inputs.first().locator) || inputs.any { it.locator.documentId != entity.documentId || it.locator.contentRevision != parent.contentRevision }) reject("INVALID_ARGUMENT")
        return AnnotationTargetValidation.freezeView(ReaderAnnotationView(
            id = entity.id, documentId = entity.documentId, sectionId = entity.sectionId, kind = textKind(entity.kind),
            locator = parent, quote = entity.quoteSnapshot, noteText = entity.noteText, style = AnnotationStyle.decode(entity.styleJson),
            inkBlobPath = entity.inkBlobPath, revision = entity.revision, createdAt = entity.createdAt, updatedAt = entity.updatedAt,
            targets = inputs,
        ))
    }

    private fun textKind(value: String): AnnotationKind = when (value) {
        "NOTE" -> AnnotationKind.NOTE
        "HIGHLIGHT" -> AnnotationKind.HIGHLIGHT
        else -> reject("INVALID_ARGUMENT")
    }

    private fun request(action: String, kind: AnnotationKind, id: String, base: Long, parent: ReaderLocator,
        quote: String?, note: String?, style: AnnotationStyle, targets: List<AnnotationTargetInput>): String =
        AnnotationMutationCodec.canonicalize(AnnotationMutationCodec.payload(action, kind, id, base, parent, quote, note, style, targets))

    private fun requestHash(id: String, base: Long, canonical: String): String = ReaderMutationHasher.requestHash("ANNOTATION_V2", id, base, canonical)
    private fun sameLocator(first: ReaderLocator, second: ReaderLocator): Boolean =
        ReaderLocatorCodec.encode(ReaderLocatorCodec.decode(ReaderLocatorCodec.encode(first))) == ReaderLocatorCodec.encode(ReaderLocatorCodec.decode(ReaderLocatorCodec.encode(second)))

    private fun checkReceiptIdentity(receipt: ReaderMutationReceiptEntity, context: AccessContext, id: String) {
        if (receipt.userId != context.userId || receipt.operationId != context.requestId || receipt.targetId != id ||
            receipt.targetKind !in setOf("ANNOTATION", "ANNOTATION_V2")) reject("IDEMPOTENCY_CONFLICT")
    }

    private suspend fun legacyCreate(
        receipt: ReaderMutationReceiptEntity, context: AccessContext, id: String, kind: AnnotationKind,
        parent: ReaderLocator, quote: String?, note: String?, style: AnnotationStyle, inputs: List<AnnotationTargetInput>,
    ): OperationResult<ReaderAnnotationView> {
        assertReceiptStored(receipt) { reject("LEGACY_RECEIPT_SNAPSHOT_UNAVAILABLE") }
        val payload = "${kind.name}|${ReaderLocatorCodec.encode(parent)}|${quote.orEmpty()}|${note.orEmpty()}|${style.encode()}|"
        if (receipt.requestHash != ReaderMutationHasher.requestHash("ANNOTATION", id, 1, payload) ||
            receipt.beforeRevision != 0L || receipt.afterRevision != 1L || !legacyId(receipt, id)) reject("IDEMPOTENCY_CONFLICT")
        val entity = annotations.findAnnotation(context.userId, id) ?: reject("RECEIPT_TARGET_MISSING")
        readable(context, entity.documentId)
        assertAnnotationStored(entity) { reject("LEGACY_RECEIPT_SNAPSHOT_UNAVAILABLE") }
        if (entity.revision != receipt.afterRevision || entity.updatedAt != receipt.createdAt || entity.createdAt != receipt.createdAt || entity.deletedAt != null) reject("LEGACY_RECEIPT_SNAPSHOT_UNAVAILABLE")
        if (entity.documentId != parent.documentId || entity.sectionId != parent.sectionId || entity.kind != kind.name ||
            entity.quoteSnapshot != quote || entity.noteText != note || entity.styleJson != style.encode() || entity.inkBlobPath != null ||
            !sameLocator(strictLocator(entity.locatorJson), parent) || hasPhysicalTargets(id) || inputs.size != 1 ||
            inputs[0].precision != "UNRESOLVED" || !sameLocator(inputs[0].locator, parent)) reject("IDEMPOTENCY_CONFLICT")
        return OperationResult.Ok(toView(entity, emptyList()), receipt.operationId)
    }

    private suspend fun legacyUpdate(
        receipt: ReaderMutationReceiptEntity, context: AccessContext, id: String, base: Long, note: String?, style: AnnotationStyle?,
    ): OperationResult<ReaderAnnotationView> {
        assertReceiptStored(receipt) { reject("LEGACY_RECEIPT_SNAPSHOT_UNAVAILABLE") }
        if (!legacyId(receipt, id) || receipt.beforeRevision != base || base == Long.MAX_VALUE || receipt.afterRevision != base + 1) reject("IDEMPOTENCY_CONFLICT")
        val entity = annotations.findAnnotation(context.userId, id) ?: reject("RECEIPT_TARGET_MISSING")
        readable(context, entity.documentId)
        assertAnnotationStored(entity) { reject("LEGACY_RECEIPT_SNAPSHOT_UNAVAILABLE") }
        if (entity.revision != receipt.afterRevision || entity.updatedAt != receipt.createdAt || entity.deletedAt != null) reject("LEGACY_RECEIPT_SNAPSHOT_UNAVAILABLE")
        val resolved = style ?: AnnotationStyle.decode(entity.styleJson)
        val payload = "note|${note.orEmpty()}|${resolved.encode()}"
        if (receipt.requestHash != ReaderMutationHasher.requestHash("ANNOTATION", id, base, payload) || entity.noteText != note ||
            entity.styleJson != resolved.encode() || entity.inkBlobPath != null || entity.kind !in setOf("NOTE", "HIGHLIGHT") ||
            !AnnotationMutationCodec.validRetainedQuote(entity.quoteSnapshot) || hasPhysicalTargets(id)) reject("IDEMPOTENCY_CONFLICT")
        return OperationResult.Ok(toView(entity, emptyList()), receipt.operationId)
    }

    private fun legacyId(receipt: ReaderMutationReceiptEntity, id: String): Boolean = try {
        val root = Json.parseToJsonElement(receipt.resultJson).jsonObject
        root.keys == setOf("annotationId") && string(root, "annotationId") == id
    } catch (_: IllegalArgumentException) {
        false
    }

    private data class Snapshot(val action: String, val operationId: String, val requestPayload: String,
        val annotation: ReaderAnnotationEntity, val physical: List<AnnotationTargetEntity>, val view: ReaderAnnotationView)

    private suspend fun snapshot(receipt: ReaderMutationReceiptEntity, context: AccessContext, action: String): Snapshot {
        // Existing receipts must pass the same raw checks before trusting DAO-coerced values.
        assertReceiptStored(receipt) { reject("RECEIPT_RESULT_INVALID") }
        val root = try {
            Json.parseToJsonElement(receipt.resultJson).jsonObject
        } catch (_: IllegalArgumentException) {
            reject("RECEIPT_RESULT_INVALID")
        }
        // An action mismatch is a request-id conflict, including unknown actions.
        val savedAction = (root["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (savedAction != null && savedAction != action) reject("IDEMPOTENCY_CONFLICT")
        try {
            require(root.keys == setOf("resultVersion", "action", "operationId", "requestPayload", "annotation", "targets"))
            require(integer(root, "resultVersion") == 2)
            require(string(root, "action") == action)
            require(string(root, "operationId") == receipt.operationId && receipt.operationId == context.requestId)
            val canonical = string(root, "requestPayload")
            val source = requireNotNull(root["annotation"] as? JsonObject)
            require(source.keys == ANNOTATION_FIELDS)
            val entity = ReaderAnnotationEntity(
                id = string(source, "id"), userId = long(source, "userId"), documentId = string(source, "documentId"),
                sectionId = nullableString(source, "sectionId"), kind = string(source, "kind"), locatorJson = string(source, "locatorJson"),
                quoteSnapshot = nullableString(source, "quoteSnapshot"), noteText = nullableString(source, "noteText"), styleJson = string(source, "styleJson"),
                inkBlobPath = nullableString(source, "inkBlobPath"), revision = long(source, "revision"), deletedAt = nullableLong(source, "deletedAt"),
                createdAt = long(source, "createdAt"), updatedAt = long(source, "updatedAt"),
            )
            require(entity.id == receipt.targetId && entity.userId == context.userId && entity.userId > 0 && entity.inkBlobPath == null)
            require(if (action == "DELETE") entity.deletedAt == entity.updatedAt else entity.deletedAt == null)
            require(entity.revision == receipt.afterRevision && entity.revision > 0 && entity.updatedAt == receipt.createdAt && entity.createdAt >= 0 && entity.createdAt <= entity.updatedAt)
            if (action == "CREATE") require(receipt.beforeRevision == 0L && receipt.afterRevision == 1L && entity.createdAt == entity.updatedAt)
            else require(receipt.beforeRevision > 0 && receipt.beforeRevision < Long.MAX_VALUE && receipt.afterRevision == receipt.beforeRevision + 1)
            require(AnnotationMutationCodec.validRetainedQuote(entity.quoteSnapshot) && AnnotationMutationCodec.validNote(entity.noteText))
            val parent = ReaderLocatorCodec.decode(entity.locatorJson)
            AnnotationMutationCodec.locatorProjection(parent)
            require(parent.documentId == entity.documentId)
            val style = strictStyle(entity.styleJson)
            val targetArray = requireNotNull(root["targets"] as? JsonArray)
            val seen = HashSet<String>()
            val physical = targetArray.mapIndexed { ordinal, item ->
                val target = requireNotNull(item as? JsonObject)
                require(target.keys == TARGET_FIELDS)
                val targetId = string(target, "targetId")
                require(targetId.isNotBlank() && seen.add(targetId))
                if (action == "CREATE") require(targetId == "annotation-target-${ReaderMutationHasher.sha256("${entity.id}|$ordinal").take(32)}")
                require(string(target, "annotationId") == entity.id && integer(target, "ordinal") == ordinal && integer(target, "anchorVersion") == 1)
                val locatorJson = string(target, "locatorJson")
                val locator = ReaderLocatorCodec.decode(locatorJson)
                AnnotationMutationCodec.locatorProjection(locator)
                require(locator.documentId == entity.documentId && locator.contentRevision == parent.contentRevision)
                val precision = string(target, "precision")
                require(precision in setOf("EXACT", "PAGE_ONLY", "UNRESOLVED"))
                AnnotationTargetEntity(targetId, entity.id, ordinal, locatorJson, 1, precision)
            }
            require(action != "CREATE" || physical.isNotEmpty())
            if (action == "CREATE" || physical.isNotEmpty()) require(AnnotationMutationCodec.validQuote(entity.quoteSnapshot))
            require(parent.sectionId == entity.sectionId || (action != "CREATE" && physical.isEmpty() && entity.sectionId == null))
            val retainedInputs = if (physical.isEmpty()) listOf(AnnotationTargetInput(parent, "UNRESOLVED"))
                else physical.map { AnnotationTargetInput(ReaderLocatorCodec.decode(it.locatorJson), it.precision) }
            val inputs = when (val result = AnnotationTargetValidation.validateAndFreeze(parent, retainedInputs,
                catalog.findSections(parent.documentId, parent.contentRevision))) {
                is OperationResult.Ok -> result.value
                else -> reject("RECEIPT_RESULT_INVALID")
            }
            val base = if (action == "CREATE") 1L else receipt.beforeRevision
            val projected = request(action, textKindForSnapshot(entity.kind), entity.id, base, parent, entity.quoteSnapshot, entity.noteText, style, inputs)
            require(canonical == projected && canonical == AnnotationMutationCodec.canonicalize(Json.parseToJsonElement(canonical).jsonObject))
            require(receipt.requestHash == requestHash(entity.id, base, canonical))
            val view = AnnotationTargetValidation.freezeView(ReaderAnnotationView(entity.id, entity.documentId, entity.sectionId,
                textKindForSnapshot(entity.kind), parent, entity.quoteSnapshot, entity.noteText, style, null,
                entity.revision, entity.createdAt, entity.updatedAt, inputs))
            return Snapshot(action, receipt.operationId, canonical, entity, physical, view)
        } catch (_: IllegalArgumentException) {
            reject("RECEIPT_RESULT_INVALID")
        }
    }

    private fun textKindForSnapshot(value: String): AnnotationKind {
        require(value == "NOTE" || value == "HIGHLIGHT")
        return AnnotationKind.valueOf(value)
    }

    private fun strictStyle(value: String): AnnotationStyle {
        val style = Json.parseToJsonElement(value).jsonObject
        require(style.keys == setOf("color", "underline"))
        val color = AnnotationColor.valueOf(string(style, "color"))
        val underline = requireNotNull(style["underline"] as? JsonPrimitive)
        require(!underline.isString && underline.content in setOf("true", "false"))
        return AnnotationStyle(color, underline.content == "true")
    }

    private fun string(root: JsonObject, name: String): String {
        val value = requireNotNull(root[name] as? JsonPrimitive)
        require(value !is JsonNull && value.isString && ReaderLocatorValidator.isWellFormedUtf16(value.content))
        return value.content
    }

    private fun nullableString(root: JsonObject, name: String): String? {
        require(root.containsKey(name))
        return if (root[name] == JsonNull) null else string(root, name)
    }

    private fun long(root: JsonObject, name: String): Long = string(root, name).let {
        val value = it.toLong()
        require(value.toString() == it)
        value
    }

    private fun nullableLong(root: JsonObject, name: String): Long? {
        require(root.containsKey(name))
        return if (root[name] == JsonNull) null else long(root, name)
    }

    private fun integer(root: JsonObject, name: String): Int {
        val value = requireNotNull(root[name] as? JsonPrimitive)
        require(!value.isString && value !is JsonNull)
        return value.content.toInt().also { require(it.toString() == value.content) }
    }

    private fun resultJson(action: String, operationId: String, canonical: String, entity: ReaderAnnotationEntity,
        targets: List<AnnotationTargetEntity>): String = AnnotationMutationCodec.canonicalize(buildJsonObject {
        put("resultVersion", 2)
        put("action", action)
        put("operationId", operationId)
        put("requestPayload", canonical)
        put("annotation", buildJsonObject {
            put("id", entity.id)
            put("userId", entity.userId.toString())
            put("documentId", entity.documentId)
            put("sectionId", nullable(entity.sectionId))
            put("kind", entity.kind)
            put("locatorJson", entity.locatorJson)
            put("quoteSnapshot", nullable(entity.quoteSnapshot))
            put("noteText", nullable(entity.noteText))
            put("styleJson", entity.styleJson)
            put("inkBlobPath", nullable(entity.inkBlobPath))
            put("revision", entity.revision.toString())
            put("deletedAt", nullable(entity.deletedAt?.toString()))
            put("createdAt", entity.createdAt.toString())
            put("updatedAt", entity.updatedAt.toString())
        })
        put("targets", JsonArray(targets.map { target -> buildJsonObject {
            put("targetId", target.targetId)
            put("annotationId", target.annotationId)
            put("ordinal", target.ordinal)
            put("locatorJson", target.locatorJson)
            put("anchorVersion", target.anchorVersion)
            put("precision", target.precision)
        } }))
    })

    private fun nullable(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    private fun conflict(revision: Long): OperationResult<Nothing> = if (revision < 0 || revision > Int.MAX_VALUE.toLong())
        OperationResult.Rejected("REVISION_OUT_OF_RANGE") else OperationResult.Conflict(revision.toInt())

    private class Abort(val result: OperationResult<Nothing>) : RuntimeException(null, null, false, false)

    private fun reject(code: String): Nothing = throw Abort(OperationResult.Rejected(code))
    private fun storageAbort(): Nothing = throw Abort(OperationResult.Failed("STORAGE_FAILURE", true))
    private fun abort(result: OperationResult<*>): Nothing {
        check(result !is OperationResult.Ok<*>)
        @Suppress("UNCHECKED_CAST")
        throw Abort(result as OperationResult<Nothing>)
    }

    private suspend fun transaction(block: suspend () -> OperationResult<ReaderAnnotationView>): OperationResult<ReaderAnnotationView> = try {
        database.withTransaction {
            currentCoroutineContext().ensureActive()
            block()
        }
    } catch (abort: Abort) {
        abort.result
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SQLException) {
        OperationResult.Failed("STORAGE_FAILURE", true)
    }

    private companion object {
        val ANNOTATION_FIELDS = setOf("id", "userId", "documentId", "sectionId", "kind", "locatorJson", "quoteSnapshot", "noteText", "styleJson", "inkBlobPath", "revision", "deletedAt", "createdAt", "updatedAt")
        val TARGET_FIELDS = setOf("targetId", "annotationId", "ordinal", "locatorJson", "anchorVersion", "precision")
    }
}
