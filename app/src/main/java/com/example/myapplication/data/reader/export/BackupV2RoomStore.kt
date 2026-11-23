package com.example.myapplication.data.reader.export

import android.database.Cursor
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.local.entity.*
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import com.example.myapplication.data.reader.notes.AnnotationStyle
import kotlinx.serialization.json.*

/** Schema7 portable whitelist. All mutations use explicit ABORT in one Room transaction. */
class BackupV2RoomStore(private val database: AppDatabase, private val identityProvider: suspend () -> SessionIdentity,
    private val publicationVerifier: BackupV2PublicationVerifier = BackupV2PublicationVerifier {
        throw BackupV2Exception("PUBLICATION_VERIFIER_REQUIRED", "real storage verification is required for READY")
    }, private val clock: () -> Long = System::currentTimeMillis,
    private val proofVerifier: BackupV2ProofVerifier = BackupV2ProofVerifier {
        throw BackupV2Exception("PROOF_VERIFIER_REQUIRED", "strict file proof verification required")
    }, private val courseVerifier: BackupV2CourseVerifier = BackupV2CourseVerifier { _, _ ->
        throw BackupV2Exception("COURSE_PROOF_MISSING", "real local asset/storage course verification required")
    }) {

    private suspend fun identity(expected: SessionIdentity): Long {
        v2require(expected.userId != null && expected.userId > 0 && identityProvider() == expected, "SESSION_CHANGED", "current owner/epoch differs")
        return expected.userId!!
    }
    suspend fun verifyIdentity(expectedIdentity: SessionIdentity) { identity(expectedIdentity) }
    private fun query(sql: String, vararg args: Any?): List<BackupV2SqlRow> = database.openHelper.writableDatabase.query(SimpleSQLiteQuery(sql, args)).use { cursor ->
        val rows = arrayListOf<BackupV2SqlRow>()
        while (cursor.moveToNext()) {
            v2require(rows.size < BackupV2Limits.RECORDS, "LIMIT_EXCEEDED", "Room query row limit")
            rows += BackupV2SqlRow(cursor.columnNames.mapIndexed { index, column -> column to when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> BackupV2Value.Null
                Cursor.FIELD_TYPE_INTEGER -> BackupV2Value.Integer(cursor.getLong(index))
                Cursor.FIELD_TYPE_STRING -> BackupV2Value.Text(cursor.getString(index))
                else -> throw BackupV2Exception("INVALID_SQL_AFFINITY", "$column must be TEXT/INTEGER")
            } }.toMap())
        }; rows
    }

    suspend fun listDocuments(expectedIdentity: SessionIdentity): List<ReaderDocumentEntity> = database.withTransaction {
        val u = identity(expectedIdentity)
        val docs = query("SELECT id FROM reader_documents WHERE (owner_user_id=? OR (owner_user_id IS NULL AND kind='BUILTIN')) AND deleted_at IS NULL ORDER BY updated_at DESC,id", u)
            .map { database.readerCatalogDao().findDocumentIncludingDeleted(it.text("id")!!)!! }
        identity(expectedIdentity); v2List(docs)
    }

    suspend fun snapshot(roots: BackupV2ExportRoots, expectedIdentity: SessionIdentity): BackupV2RoomSnapshot = database.withTransaction {
        val u = identity(expectedIdentity)
        val selected = BackupV2Table.entries.associateWith { linkedMapOf<String,BackupV2Record>() }
        val queue = ArrayDeque<BackupV2Record>()
        var count = 0
        fun include(table: BackupV2Table, raw: BackupV2SqlRow) {
            val row = portable(table,raw)
            if (selected.getValue(table).putIfAbsent(row.portableKey,row) == null) {
                count++
                v2require(count <= BackupV2Limits.RECORDS,"LIMIT_EXCEEDED","selected closure exceeds record limit")
                queue.add(row)
            }
        }
        fun read(table: BackupV2Table, where: String, vararg args: Any?) =
            query("SELECT t.* FROM " + table.wireName + " t WHERE (" + ownedPredicate(table) + ") AND (" + where + ")",u,*args)
        fun load(table: BackupV2Table,id: String,privateRoot: Boolean = false) {
            val sqlKey = if (table == BackupV2Table.READER_ANNOTATION_TARGETS) "target_id" else "id"
            val value: Any = if (table in BackupV2RecordRules.longTables) BackupV2RecordRules.longId(id) else id
            val raw = read(table,"t." + sqlKey + "=?",value).singleOrNull()
                ?: throw BackupV2Exception("DEPENDENCY_NOT_OWNED",table.wireName + ":" + id)
            if (privateRoot) v2require(raw.number("owner_user_id") == u,"ROOT_NOT_OWNED","private question root")
            include(table,raw)
        }
        roots.testIds.forEach { load(BackupV2Table.TEST_RECORDS,it.toString()) }
        roots.paperIds.forEach { load(BackupV2Table.QUIZ_PAPERS,it) }
        roots.favoriteIds.forEach { load(BackupV2Table.FAVORITES,it.toString()) }
        roots.privateQuestionIds.forEach { load(BackupV2Table.QUESTIONS,it.toString(),true) }
        roots.chapterIds.forEach { chapter -> read(BackupV2Table.STUDY_PROGRESS,"t.chapter_id=?",chapter).forEach { include(BackupV2Table.STUDY_PROGRESS,it) } }
        val docs = linkedMapOf<String,ReaderDocumentEntity>()
        suspend fun captureDocument(id: String) {
            if (id in docs) return
            val d = database.readerCatalogDao().findDocumentIncludingDeleted(id) ?: throw BackupV2Exception("DOCUMENT_MISSING",id)
            v2require(d.ownerUserId == u || d.ownerUserId == null && d.kind == "BUILTIN","DOCUMENT_NOT_OWNED",id)
            docs[id] = d
        }
        roots.documentIds.forEach { id ->
            captureDocument(id)
            listOf(BackupV2Table.READER_STATES,BackupV2Table.READER_ANNOTATIONS,BackupV2Table.READER_BOOKMARKS,
                BackupV2Table.READER_NOTEBOOKS,BackupV2Table.QUIZ_PAPERS).forEach { t ->
                read(t,"t.document_id=?",id).forEach { include(t,it) }
            }
            listOf(BackupV2Table.READER_SOURCE_REFERENCES,BackupV2Table.QUESTIONS).forEach { t ->
                read(t,"t.source_document_id=?",id).forEach { include(t,it) }
            }
        }
        val pinned = linkedMapOf<String,MutableSet<String>>()
        while (queue.isNotEmpty()) {
            val row = queue.removeFirst()
            BackupV2RecordRules.links[row.table].orEmpty().forEach { link -> row.textOrNull(link.column)?.let { load(link.target,it) } }
            val children = when(row.table) {
                BackupV2Table.TEST_RECORDS -> listOf(BackupV2Table.TEST_ANSWERS to "test_id")
                BackupV2Table.QUIZ_PAPERS -> listOf(BackupV2Table.QUIZ_ITEMS to "paper_id",BackupV2Table.QUIZ_ATTEMPTS to "paper_id")
                BackupV2Table.QUIZ_ATTEMPTS -> listOf(BackupV2Table.QUIZ_ATTEMPT_ANSWERS to "attempt_id",BackupV2Table.QUIZ_GRADE_EVENTS to "attempt_id")
                BackupV2Table.READER_ANNOTATIONS -> listOf(BackupV2Table.READER_ANNOTATION_TARGETS to "annotation_id")
                BackupV2Table.READER_NOTEBOOKS -> listOf(BackupV2Table.READER_NOTE_VERSIONS to "notebook_id")
                else -> emptyList()
            }
            children.forEach { (table,column) ->
                val parent: Any = if (row.table in BackupV2RecordRules.longTables) BackupV2RecordRules.longId(row.text("id")!!) else row.text("id")!!
                read(table,"t." + column + "=?",parent).forEach { include(table,it) }
            }
            listOf("document_id","source_document_id").forEach { c -> row.textOrNull(c)?.let { captureDocument(it) } }
            BackupV2JsonProjection.locators(row).forEach { locator ->
                captureDocument(locator.documentId)
                pinned.getOrPut(locator.documentId) { linkedSetOf() }.add(locator.contentRevision)
                if (locator is ReaderLocator.Note) load(BackupV2Table.READER_NOTEBOOKS,locator.notebookId)
            }
            if (row.table == BackupV2Table.QUIZ_PAPERS) BackupV2JsonProjection.scopeIdentity(row).first?.let { doc ->
                captureDocument(doc); pinned.getOrPut(doc) { linkedSetOf() }.add(row.text("source_revision")!!)
            }
            val sourceDoc = row.textOrNull("source_document_id") ?: row.textOrNull("document_id")
            if (sourceDoc != null) row.textOrNull("source_revision")?.let { pinned.getOrPut(sourceDoc) { linkedSetOf() }.add(it) }
        }
        val projected = BackupV2JsonProjection.project(BackupV2Records(selected.mapValues { it.value.values.toList() }))
        val chapters = roots.chapterIds.map { it.toString() }.toSet() + projected.records.tables.values.flatten().mapNotNull { it.textOrNull("chapter_id") }
        val refs = chapters.sorted().map { chapter ->
            val matches = query("SELECT b.content_key AS book_key,c.chapter_key,c.document_id,c.legacy_chapter_id,c.enabled,d.content_revision FROM course_book_chapters c JOIN course_books b ON b.book_id=c.book_id JOIN reader_documents d ON d.id=c.document_id WHERE c.legacy_chapter_id=? AND c.enabled=1",BackupV2RecordRules.longId(chapter))
            val c = matches.singleOrNull() ?: throw BackupV2Exception("COURSE_DICTIONARY_MISSING","chapter:" + chapter)
            captureDocument(c.text("document_id")!!)
            BackupV2CourseChapterRef(chapter,c.text("chapter_key")!!,c.text("book_key")!!,c.text("content_revision")!!)
        }
        val captures = arrayListOf<BackupV2RevisionCapture>()
        docs.values.forEach { d ->
            pinned.getOrPut(d.id) { linkedSetOf() }.add(d.contentRevision)
            pinned.getValue(d.id).forEach { revision -> database.readerCatalogDao().findContentRevision(d.id,revision)?.let { r ->
                captures += BackupV2RevisionCapture(d,r,database.readerCatalogDao().findSections(d.id,revision),database.readerCatalogDao().findOutline(d.id,revision))
            } }
        }
        val hash = snapshotHash(expectedIdentity,projected.records,docs.values.toList(),captures,refs)
        identity(expectedIdentity)
        BackupV2RoomSnapshot(expectedIdentity,roots,projected.records,projected.changes,roots.documentIds,
            docs.keys - roots.documentIds,docs.values.toList(),captures,refs,hash)
    }

    suspend fun verifySnapshot(snapshot: BackupV2RoomSnapshot) {
        val current = snapshot(snapshot.roots,snapshot.expectedIdentity)
        v2require(current.snapshotHash == snapshot.snapshotHash,"SNAPSHOT_STALE","selected database snapshot changed during file IO")
    }

    private fun ownedPredicate(table: BackupV2Table): String = when(table) {
        BackupV2Table.QUESTIONS -> "t.owner_user_id=? OR t.owner_user_id IS NULL"
        BackupV2Table.QUIZ_ATTEMPTS -> "t.user_id=? AND EXISTS(SELECT 1 FROM quiz_papers p WHERE p.id=t.paper_id AND p.user_id=t.user_id)"
        in BackupV2IdentityPlanner.ownedTables -> "t.user_id=?"
        BackupV2Table.TEST_ANSWERS -> "EXISTS(SELECT 1 FROM test_records p WHERE p.id=t.test_id AND p.user_id=?)"
        BackupV2Table.READER_NOTE_VERSIONS -> "EXISTS(SELECT 1 FROM reader_notebooks p WHERE p.id=t.notebook_id AND p.user_id=?)"
        BackupV2Table.READER_ANNOTATION_TARGETS -> "EXISTS(SELECT 1 FROM reader_annotations p WHERE p.id=t.annotation_id AND p.user_id=?)"
        BackupV2Table.QUIZ_ITEMS -> "EXISTS(SELECT 1 FROM quiz_papers p WHERE p.id=t.paper_id AND p.user_id=?)"
        BackupV2Table.QUIZ_ATTEMPT_ANSWERS,BackupV2Table.QUIZ_GRADE_EVENTS -> "EXISTS(SELECT 1 FROM quiz_attempts p JOIN quiz_papers q ON q.id=p.paper_id WHERE p.id=t.attempt_id AND p.user_id=? AND q.user_id=p.user_id)"
        else -> throw BackupV2Exception("INVALID_TABLE",table.wireName)
    }
    private fun portable(table: BackupV2Table, row: BackupV2SqlRow): BackupV2Record {
        val excluded = setOf("user_id", "owner_user_id", "writer_epoch", "last_sequence", "operation_id", "generation_run_id", "submit_key", "assessment_key")
        val fields = row.fields.filterKeys { it !in excluded }.toMutableMap()
        // schema7 and real AnnotationRepository interpret the local default {} as YELLOW/false.
        // Only this strictly parsed empty local object is expanded; external V2 JSON stays strict.
        if (table == BackupV2Table.READER_ANNOTATIONS) (fields["style_json"] as? BackupV2Value.Text)?.let { value ->
            val style = BackupV2Json.parseText(value.value)
            if (style is JsonObject && style.isEmpty()) fields["style_json"] = BackupV2Value.Text(AnnotationStyle.decode(value.value).encode())
        }
        if (table == BackupV2Table.READER_ANNOTATION_TARGETS) fields["id"] = fields.remove("target_id")!!
        longColumns(table).forEach { c -> (fields[c] as? BackupV2Value.Integer)?.let { fields[c] = BackupV2Value.Text(it.value.toString()) } }
        return BackupV2Record(table, fields)
    }
    private fun readCourseDictionary() = query("SELECT b.content_key AS book_key,c.chapter_key,c.document_id,c.legacy_chapter_id,c.enabled,d.content_revision FROM course_book_chapters c JOIN course_books b ON b.book_id=c.book_id JOIN reader_documents d ON d.id=c.document_id")

    suspend fun readTargetState(backup: BackupV2Package, expectedIdentity: SessionIdentity, copyNonce: String? = null): BackupV2TargetState {
        val raw = database.withTransaction {
            val user = identity(expectedIdentity)
            val result = targetState(expectedIdentity, findReceipt(user, backup.manifest.contentHash,
                copyNonce?.let { "restore-v2:" + v2Digest("$user\n${backup.manifest.contentHash}\n$it") }))
            identity(expectedIdentity); result
        }
        val courses = linkedMapOf<BackupV2CourseChapterRef,BackupV2CourseProof>()
        backup.manifest.courseChapterRefs.forEach { ref ->
            val row = raw.rows["course_dictionary"].orEmpty().singleOrNull { it.text("book_key") == ref.bookKey && it.text("chapter_key") == ref.chapterKey &&
                it.text("content_revision") == ref.contentRevision && it.number("enabled") == 1L && it.number("legacy_chapter_id") != null }
            val capture = raw.revisions.singleOrNull { it.document.id == row?.text("document_id") && it.revision == ref.contentRevision }
            if (row != null && capture != null && capture.document.ownerUserId == null && capture.document.kind == "BUILTIN" &&
                capture.document.deletedAt == null && capture.document.importStatus == "READY" && capture.revisionEntity.status == "READY") {
                try {
                    val digest = courseVerifier.verify(capture, ref)
                    BackupV2JsonProjection.hash(digest)
                    courses[ref] = BackupV2CourseProof(ref,row.number("legacy_chapter_id")!!.toString(),capture.document.id,capture,digest)
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
                } catch (_: IllegalArgumentException) { /* explicit missing proof remains a blocked preview */ }
            }
        }
        database.withTransaction { identity(expectedIdentity); v2require(targetState(expectedIdentity).stateHash == raw.databaseHash,"PLAN_STALE","course IO changed target snapshot") }
        val hash = v2Digest(raw.databaseHash + courses.entries.sortedBy { it.key.exportChapterId }.joinToString { "${it.key}:${it.value.targetChapterId}:${it.value.digest}" })
        return BackupV2TargetState(raw.expectedIdentity,raw.highWater,raw.rows,raw.catalog,raw.revisions,hash,raw.receipt,courses,raw.databaseHash)
    }
    private suspend fun targetState(expected: SessionIdentity, receipt: BackupV2RestoreReceipt? = null): BackupV2TargetState {
        val high = BackupV2RecordRules.longTables.associateWith { table ->
            val maximum = query("SELECT MAX(id) AS maximum FROM ${table.wireName}").single().number("maximum") ?: 0L
            val seq = query("SELECT seq,typeof(seq) AS type FROM sqlite_sequence WHERE name=?", table.wireName)
            v2require(seq.size <= 1, "INVALID_HIGH_WATER", "duplicate sequence")
            val entry = seq.singleOrNull(); val type = entry?.text("type") ?: "absent"
            v2require(type in setOf("integer", "absent") && maximum >= 0 && (entry?.number("seq") ?: 0) >= 0,
                "INVALID_HIGH_WATER", "global sequence type/range")
            BackupV2LongHighWater(maximum, entry?.number("seq"), type)
        }
        val raw = linkedMapOf<String,List<BackupV2SqlRow>>()
        BackupV2Table.entries.forEach { raw[it.wireName] = query("SELECT * FROM ${it.wireName}") }
        listOf("reader_documents", "reader_content_revisions", "reader_sections", "reader_outline_nodes", "course_books", "course_book_chapters").forEach { raw[it] = query("SELECT * FROM $it") }
        raw["course_dictionary"] = readCourseDictionary()
        val catalog = raw.getValue("reader_documents").map { database.readerCatalogDao().findDocumentIncludingDeleted(it.text("id")!!)!! }
        val captures = raw.getValue("reader_content_revisions").map { row ->
            val d = catalog.single { it.id == row.text("document_id") }
            val revision = row.text("revision")!!
            BackupV2RevisionCapture(d, database.readerCatalogDao().findContentRevision(d.id, revision)!!,
                database.readerCatalogDao().findSections(d.id, revision), database.readerCatalogDao().findOutline(d.id, revision))
        }
        val hash = v2Digest(v2Canonical(buildJsonObject {
            put("rows", JsonObject(raw.mapValues { (_, rows) -> JsonArray(rows.map { v2Canonical(it.json()) }.sorted().map(::JsonPrimitive)) }))
            put("highWater", JsonObject(high.mapKeys { it.key.wireName }.mapValues { JsonPrimitive(it.value.toString()) }))
            put("identity", expected.toString())
        }))
        return BackupV2TargetState(expected, high, raw, catalog, captures, hash, receipt)
    }

    suspend fun commit(plan: BackupV2RestorePlan, publications: List<BackupV2Publication>, journalId: String,
        expectedIdentity: SessionIdentity): BackupV2RestoreReceipt {
        identity(expectedIdentity)
        v2require(expectedIdentity == plan.expectedIdentity && journalId.isNotBlank(), "INVALID_PLAN", "identity/journal binding")
        // A receipt is authoritative even if a caller's journal/files are behind the DB commit.
        findRestoreReceipt(expectedIdentity.userId!!, plan.contentHash, if (plan.copyNonce == null) null else plan.operationId)?.let {
            verifyV2ReceiptManifest(plan.backup)
            identity(expectedIdentity); return it.copy(alreadyCommitted = true)
        }
        v2require(plan.existingReceipt == null,"RECEIPT_CHANGED","receipt replay must never become a new insertion plan")
        v2require(plan.canCommit, "PLAN_BLOCKED", plan.reasons.joinToString())
        val regenerated = BackupV2IdentityPlanner().preview(plan.backup, plan.targetState, plan.proofs, expectedIdentity, plan.policy, plan.copyNonce)
        v2require(regenerated.planHash == plan.planHash && regenerated.idMapHash == plan.idMapHash, "INVALID_PLAN", "fixed plan binding changed")
        plan.proofs.forEach { proofVerifier.verify(it) }
        plan.targetState.verifiedCourses.forEach { (ref, proof) ->
            v2require(courseVerifier.verify(proof.capture,ref) == proof.digest,"COURSE_PROOF_CHANGED","local course asset/storage changed")
        }
        val required = plan.documentDecisions.filter { it.disposition == BackupV2DocumentDisposition.PUBLISH }
        val requiredKeys = plan.proofs.filter { proof -> required.any { it.sourceId == proof.exportDocumentId } }.map { it.exportDocumentId to it.revision }.toSet()
        v2require(publications.map { it.exportDocumentId to it.revision }.toSet() == requiredKeys && publications.size == requiredKeys.size,
            "PUBLICATION_MISMATCH", "exact new document publications required")
        publications.forEach { pub ->
            val decision = required.singleOrNull { it.sourceId == pub.exportDocumentId }
            val proof = plan.proofs.singleOrNull { it.exportDocumentId == pub.exportDocumentId && it.revision == pub.revision }
            val wire = plan.backup.manifest.documents.singleOrNull { it.exportDocumentId == pub.exportDocumentId }
            v2require(decision != null && proof != null && wire != null && pub.targetDocumentId == decision.targetId &&
                pub.planHash == plan.planHash && pub.contentHash == plan.contentHash && pub.proofDigest == proof.proofDigest &&
                pub.resourceDigest == proof.resourceDigest && pub.resourceDigest.matches(Regex("[0-9a-f]{64}")) &&
                pub.sourceSha256 == proof.capture.revisionEntity.sourceSha256 && pub.extractorVersion == proof.capture.revisionEntity.extractorVersion &&
                pub.createdAt == proof.capture.revisionEntity.createdAt,
                "PUBLICATION_MISMATCH", "source/plan/target binding")
            val expectedSections = proof!!.capture.sections.map { row -> row.copy(id = plan.idMap.sections.getValue(row.id), documentId = pub.targetDocumentId,
                parentId = row.parentId?.let { plan.idMap.sections.getValue(it) }) }
            val expectedOutline = proof.capture.outline.map { row -> row.copy(id = plan.outlineIds.getValue(row.id), documentId = pub.targetDocumentId,
                nodeKey = plan.outlineIds.getValue(row.id), parentId = row.parentId?.let { plan.outlineIds.getValue(it) },
                targetSectionId = row.targetSectionId?.let { plan.idMap.sections.getValue(it) },
                targetLocatorJson = row.targetLocatorJson?.let { BackupV2JsonProjection.remapColumn(BackupV2Table.READER_STATES,"locator_json",it,plan.idMap) }) }
            v2require(pub.sections.sortedBy { it.id } == expectedSections.sortedBy { it.id } && pub.outline.sortedBy { it.id } == expectedOutline.sortedBy { it.id },
                "PUBLICATION_MISMATCH", "all section/outline metadata must equal the fixed mapped proof")
            publicationVerifier.verify(pub)
        }
        return database.withTransaction {
            val u = identity(expectedIdentity)
            findReceipt(u, plan.contentHash, if (plan.copyNonce == null) null else plan.operationId)?.let {
                verifyV2ReceiptManifest(plan.backup)
                identity(expectedIdentity); return@withTransaction it.copy(alreadyCommitted = true)
            }
            val nowTarget = targetState(expectedIdentity)
            v2require(nowTarget.databaseHash == plan.targetState.databaseHash, "PLAN_STALE", "global PK/unique/catalog/high water changed")
            val now = clock()
            plan.documentDecisions.filter { it.disposition in setOf(BackupV2DocumentDisposition.PENDING, BackupV2DocumentDisposition.PUBLISH) }.forEach { decision ->
                val d = plan.backup.manifest.documents.single { it.exportDocumentId == decision.sourceId }
                val pub = publications.singleOrNull { it.exportDocumentId == decision.sourceId && it.revision == d.contentRevision }
                database.readerCatalogDao().insertDocument(ReaderDocumentEntity(decision.targetId, u, "IMPORTED", format = d.format,
                    title = d.title, blobPath = pub?.sourceBlobPath, fileSha256 = null, contentRevision = d.contentRevision,
                    extractorVersion = pub?.extractorVersion ?: "restore-v2:source-missing", importStatus = if (pub == null) "PENDING" else "READY",
                    createdAt = now, updatedAt = now))
                if (pub != null) {
                    publications.filter { it.exportDocumentId == decision.sourceId }.forEach { revisionPub ->
                        database.readerCatalogDao().insertContentRevision(ReaderContentRevisionEntity(revisionPub.targetDocumentId, revisionPub.revision, revisionPub.sourceSha256,
                            revisionPub.sourceBlobPath, revisionPub.manifestPath, revisionPub.extractorVersion, "READY", revisionPub.createdAt))
                        insertSections(revisionPub.sections)
                        insertOutline(revisionPub.outline)
                    }
                }
            }
            insertionOrder.forEach { table -> plan.sqlRows.getValue(table).forEach { insert(table, it) } }
            val receipt = BackupV2RestoreReceipt(u, plan.contentHash, plan.operationId, journalId, plan.planHash, plan.idMapHash, plan.counts, now)
            val result = receiptJson(receipt)
            database.openHelper.writableDatabase.execSQL("INSERT OR ABORT INTO reader_mutation_receipts(user_id,operation_id,target_kind,target_id,request_hash,before_revision,after_revision,result_json,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                arrayOf(u, receipt.operationId, "RESTORE_V2", plan.contentHash, plan.planHash, 0L, 1L, result, now))
            identity(expectedIdentity)
            receipt
        }
    }
    private suspend fun insertSections(rows: List<ReaderSectionEntity>) {
        val left = rows.toMutableList(); val done = linkedSetOf<String>()
        while(left.isNotEmpty()) {
            val ready = left.filter { it.parentId == null || it.parentId in done }
            v2require(ready.isNotEmpty(), "SOURCE_PARENT_CYCLE", "section parents")
            database.readerCatalogDao().insertSections(ready); done.addAll(ready.map { it.id }); left.removeAll(ready.toSet())
        }
    }
    private suspend fun insertOutline(rows: List<ReaderOutlineNodeEntity>) {
        val left = rows.toMutableList(); val done = linkedSetOf<String>()
        while(left.isNotEmpty()) {
            val ready = left.filter { it.parentId == null || it.parentId in done }
            v2require(ready.isNotEmpty(), "SOURCE_PARENT_CYCLE", "outline parents")
            database.readerCatalogDao().insertOutline(ready); done.addAll(ready.map { it.id }); left.removeAll(ready.toSet())
        }
    }
    private fun insert(table: BackupV2Table, row: BackupV2SqlRow) {
        val columns = row.fields.keys.toList()
        val statement = database.openHelper.writableDatabase.compileStatement("INSERT OR ABORT INTO ${table.wireName} (${columns.joinToString { "`$it`" }}) VALUES(${columns.joinToString { "?" }})")
        statement.use { s -> columns.forEachIndexed { i, c -> when(val value = row.fields.getValue(c)) {
            BackupV2Value.Null -> s.bindNull(i+1)
            is BackupV2Value.Integer -> s.bindLong(i+1,value.value)
            is BackupV2Value.Text -> if(c in longColumns(table)) s.bindLong(i+1,BackupV2RecordRules.longId(value.value)) else s.bindString(i+1,value.value)
        } }; s.executeInsert() }
    }
    suspend fun findRestoreReceipt(userId: Long, contentHash: String, operationId: String? = null): BackupV2RestoreReceipt? = database.withTransaction {
        findReceipt(userId, contentHash, operationId)
    }
    private fun findReceipt(userId: Long, contentHash: String, operationId: String?): BackupV2RestoreReceipt? {
        // schema7 is a rowid table. Inserts never supply rowid; ascending rowid is stable persisted insertion order, including millisecond ties and nonce-first restores.
        val row = if (operationId == null) query("SELECT * FROM reader_mutation_receipts WHERE user_id=? AND target_kind='RESTORE_V2' AND target_id=? ORDER BY rowid ASC LIMIT 1", userId, contentHash)
        else query("SELECT * FROM reader_mutation_receipts WHERE user_id=? AND target_kind='RESTORE_V2' AND target_id=? AND operation_id=?", userId, contentHash, operationId)
        return row.singleOrNull()?.let { r ->
            val json = BackupV2Json.obj(BackupV2Json.parseText(r.text("result_json")!!), setOf("schemaVersion","journalId","planHash","idMapHash","counts"))
            v2require(BackupV2Json.integer(json["schemaVersion"]) == 1L, "INVALID_RECEIPT", "receipt version")
            val c = BackupV2Json.obj(json.getValue("counts"), setOf("inserted","reused","skipped","blocked","documents","pendingDocuments","sourceDocuments"))
            fun count(key:String):Int { val n = BackupV2Json.integer(c[key]); v2require(n in 0..Int.MAX_VALUE, "INVALID_RECEIPT", "count range"); return n.toInt() }
            BackupV2RestoreReceipt(userId, contentHash, r.text("operation_id")!!, BackupV2Json.string(json["journalId"]), BackupV2Json.string(json["planHash"]),
                BackupV2Json.string(json["idMapHash"]), BackupV2RestoreCounts(count("inserted"), count("reused"), count("skipped"), count("blocked"), count("documents"), count("pendingDocuments"), count("sourceDocuments")), r.number("created_at")!!)
        }
    }
    private fun receiptJson(r: BackupV2RestoreReceipt) = v2Canonical(buildJsonObject {
        put("schemaVersion",1); put("journalId",r.journalId); put("planHash",r.planHash); put("idMapHash",r.idMapHash)
        put("counts",buildJsonObject { put("inserted",r.counts.inserted); put("reused",r.counts.reused); put("skipped",r.counts.skipped); put("blocked",r.counts.blocked)
            put("documents",r.counts.documents); put("pendingDocuments",r.counts.pendingDocuments); put("sourceDocuments",r.counts.sourceDocuments) })
    })
    private fun snapshotHash(identity: SessionIdentity, records: BackupV2Records, docs: List<ReaderDocumentEntity>, captures: List<BackupV2RevisionCapture>, refs: List<BackupV2CourseChapterRef>) =
        v2Digest(v2Canonical(buildJsonObject {
            put("identity",identity.toString()); put("records",BackupV2Json.parse(LearningStateBackupCodec.encode(records)))
            put("catalog",JsonArray(docs.map { JsonPrimitive(it.toString()) }.sortedBy { it.content }))
            put("revisions",JsonArray(captures.map { JsonPrimitive("${it.revisionEntity}|${it.sections}|${it.outline}") }.sortedBy { it.content }))
            put("courses",JsonArray(refs.map { JsonPrimitive(it.toString()) }.sortedBy { it.content }))
        }))
    companion object {
        internal fun longColumns(table: BackupV2Table): Set<String> =
            (if (table in BackupV2RecordRules.longTables) setOf("id") else emptySet()) + when(table) {
                BackupV2Table.QUESTIONS, BackupV2Table.STUDY_PROGRESS -> setOf("chapter_id")
                BackupV2Table.TEST_ANSWERS -> setOf("test_id","question_id")
                BackupV2Table.FAVORITES, BackupV2Table.QUIZ_ITEMS -> setOf("question_id")
                else -> emptySet()
            }
        private val insertionOrder = listOf(BackupV2Table.QUESTIONS, BackupV2Table.TEST_RECORDS, BackupV2Table.TEST_ANSWERS, BackupV2Table.FAVORITES,
            BackupV2Table.STUDY_PROGRESS, BackupV2Table.READER_STATES, BackupV2Table.READER_ANNOTATIONS, BackupV2Table.READER_ANNOTATION_TARGETS,
            BackupV2Table.READER_BOOKMARKS, BackupV2Table.READER_SOURCE_REFERENCES, BackupV2Table.READER_NOTEBOOKS, BackupV2Table.READER_NOTE_VERSIONS,
            BackupV2Table.QUIZ_PAPERS, BackupV2Table.QUIZ_ITEMS, BackupV2Table.QUIZ_ATTEMPTS, BackupV2Table.QUIZ_ATTEMPT_ANSWERS, BackupV2Table.QUIZ_GRADE_EVENTS)
    }
}
