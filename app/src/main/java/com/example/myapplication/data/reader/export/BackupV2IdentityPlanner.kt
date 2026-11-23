package com.example.myapplication.data.reader.export

import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.data.reader.notes.ReaderMutationHasher
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.quiz.QuizCodecs
import kotlinx.serialization.json.*

/** Pure planning. No DAO, sequence write, insert probe or source identity reuse. */
class BackupV2IdentityPlanner {
    fun allocateLongIds(sourceIds: List<String>, highWater: BackupV2LongHighWater): Map<String,String> {
        v2require(highWater.maximum >= 0 && (highWater.sequence == null || highWater.sequence >= 0) &&
            highWater.sequenceType in setOf("integer", "absent"), "INVALID_HIGH_WATER", "nonnegative SQLite INTEGER high water required")
        v2require(sourceIds.distinct().size == sourceIds.size, "DUPLICATE_ID", "Long source identities")
        var next = maxOf(highWater.maximum, highWater.sequence ?: 0L)
        return v2Map(sourceIds.sortedBy(BackupV2RecordRules::longId).associateWith {
            try { next = Math.addExact(next, 1L) } catch (error: ArithmeticException) {
                throw BackupV2Exception("ID_OVERFLOW", "SQLite positive Long identities exhausted", error)
            }; next.toString()
        })
    }

    fun preview(backup: BackupV2Package, targetState: BackupV2TargetState, proofs: List<BackupV2RevisionProof> = emptyList(),
        expectedIdentity: SessionIdentity = targetState.expectedIdentity, policy: DataManagementConflictPolicy = DataManagementConflictPolicy.COPY,
        copyNonce: String? = null): BackupV2RestorePlan {
        val owner = expectedIdentity.userId
        v2require(owner != null && owner > 0 && expectedIdentity == targetState.expectedIdentity, "SESSION_CHANGED", "preview owner/epoch")
        v2require(copyNonce == null || copyNonce.isNotBlank() && copyNonce.length <= 128, "INVALID_NONCE", "copy nonce")
        val reasons = arrayListOf<String>()
        val operationId = "restore-v2:" + v2Digest("$owner\n${backup.manifest.contentHash}\n${copyNonce ?: "DEFAULT"}")
        targetState.receipt?.let { receipt ->
            verifyV2ReceiptManifest(backup)
            v2require(receipt.userId == owner && receipt.contentHash == backup.manifest.contentHash &&
                (copyNonce == null || receipt.operationId == operationId), "INVALID_RECEIPT", "receipt owner/content/operation binding")
            // This is an explicit receipt replay, not a new allocation or a fabricated reconstruction of its journal idMap.
            return BackupV2RestorePlan(backup,expectedIdentity,copyNonce,policy,BackupV2IdMap(),emptyList(),emptyList(),
                BackupV2Records(BackupV2Table.entries.associateWith { emptyList() }),BackupV2Table.entries.associateWith { emptyList() },
                receipt.counts,targetState,emptyList(),emptyMap(),receipt.operationId,receipt.idMapHash,receipt.planHash,emptyList(),receipt)
        }
        fun identity(kind: String, key: String) = "restore-v2:" + v2Digest("$operationId\n$kind\n$key")
        val proofsByKey = proofs.associateBy { it.exportDocumentId to it.revision }
        v2require(proofsByKey.size == proofs.size, "DUPLICATE_PROOF", "document/revision proof")
        proofs.forEach { p ->
            v2require(p.proofDigest.matches(Regex("[0-9a-f]{64}")) && p.capture.document.id == p.exportDocumentId &&
                p.capture.revision == p.revision, "INVALID_SOURCE_PROOF", "proof scope")
        }
        val documents = linkedMapOf<String,String>()
        val sections = linkedMapOf<String,String>()
        val outline = linkedMapOf<String,String>()
        val docDecisions = backup.manifest.documents.map { d ->
            val builtinRef = if (d.kind == "BUILTIN_REFERENCE") backup.manifest.courseChapterRefs.singleOrNull {
                it.chapterKey == d.chapterKey && it.contentRevision == d.contentRevision
            } else null
            val builtin = builtinRef?.let { targetState.verifiedCourses[it] }
            val same = builtin?.capture?.document ?: if (policy == DataManagementConflictPolicy.KEEP_LOCAL) targetState.catalog.singleOrNull {
                it.ownerUserId == owner && d.sourceSha256 != null && it.fileSha256 == d.sourceSha256
            } else null
            val proof = proofsByKey[d.exportDocumentId to d.contentRevision]
            val target = same?.id ?: identity("document", d.exportDocumentId)
            documents[d.exportDocumentId] = target
            var disposition = when {
                same != null -> BackupV2DocumentDisposition.REUSE
                proof != null -> BackupV2DocumentDisposition.PUBLISH
                else -> BackupV2DocumentDisposition.PENDING
            }
            if (d.sourcePath != null && proof == null) { reasons += "SOURCE_PROOF_MISSING:${d.exportDocumentId}"; disposition = BackupV2DocumentDisposition.BLOCKED }
            if (same != null && (same.deletedAt != null || same.contentRevision != d.contentRevision)) {
                reasons += "KEEP_LOCAL_REVISION_CONFLICT:${d.exportDocumentId}"; disposition = BackupV2DocumentDisposition.BLOCKED
            }
            if (d.kind == "BUILTIN_REFERENCE" && builtin == null) {
                reasons += "BUILTIN_REFERENCE_PROOF_MISSING:${d.exportDocumentId}"; disposition = BackupV2DocumentDisposition.BLOCKED
            }
            val targetCapture = targetState.revisions.singleOrNull { it.document.id == target && it.revision == d.contentRevision }
            val sectionIds = d.sectionIds + proof?.capture?.sections.orEmpty().map { it.id }
            sectionIds.distinct().forEach { section ->
                sections[section] = if (same != null && targetCapture != null) {
                    val old = proof?.capture?.sections?.singleOrNull { it.id == section }
                    val found = if (builtin != null && d.sectionIds.size == 1 && targetCapture.sections.size == 1) targetCapture.sections.single()
                    else targetCapture.sections.singleOrNull { local -> old != null && local.orderIndex == old.orderIndex &&
                        local.resourceHref == old.resourceHref && local.charCount == old.charCount && local.pageStart == old.pageStart && local.pageEnd == old.pageEnd }
                    if (found == null) {
                        // A fully KEEP_LOCAL imported component is skipped. This virtual identity is audit-only and is never inserted or used for physical reuse.
                        if (builtin == null && policy == DataManagementConflictPolicy.KEEP_LOCAL) identity("skipped-section",section)
                        else { reasons += "REUSE_SECTION_PROOF_MISSING:$section"; identity("section", section) }
                    } else found.id
                } else identity("section", section)
            }
            BackupV2DocumentDecision(d.exportDocumentId, target, disposition,
                if (disposition == BackupV2DocumentDisposition.PUBLISH) "COPY preserves revision source SHA; copy is excluded from import deduplication" else "")
        }
        proofs.forEach { proof ->
            v2require(documents.containsKey(proof.exportDocumentId), "INVALID_SOURCE_PROOF", "proof outside package")
            proof.capture.sections.forEach { sections.putIfAbsent(it.id, identity("section", it.id)) }
            proof.capture.outline.forEach { outline[it.id] = identity("outline", it.id) }
        }
        backup.records.tables.values.flatten().forEach { row ->
            val referenced = BackupV2JsonProjection.locators(row).map { it.documentId to it.contentRevision }.toMutableList()
            if (row.table == BackupV2Table.QUIZ_PAPERS) BackupV2JsonProjection.scopeIdentity(row).first?.let { referenced += it to row.text("source_revision")!! }
            (row.textOrNull("source_document_id") ?: row.textOrNull("document_id"))?.let { doc -> row.textOrNull("source_revision")?.let { referenced += doc to it } }
            referenced.forEach { (doc, revision) ->
                if (docDecisions.any { it.sourceId == doc && it.disposition == BackupV2DocumentDisposition.PUBLISH } && !proofsByKey.containsKey(doc to revision))
                    reasons += "HISTORICAL_SOURCE_PROOF_MISSING:$doc/$revision"
            }
        }
        // All locator/typed scope sections are required by the validated manifest.
        val courseMap = linkedMapOf<BackupV2CourseChapterRef,String>()
        backup.manifest.courseChapterRefs.forEach { ref ->
            val candidates = targetState.rows["course_dictionary"].orEmpty().filter {
                it.text("book_key") == ref.bookKey && it.text("chapter_key") == ref.chapterKey &&
                    it.text("content_revision") == ref.contentRevision && it.number("enabled") == 1L && it.number("legacy_chapter_id") != null
            }
            val candidate = candidates.singleOrNull()
            val localProof = targetState.verifiedCourses[ref]
            val verified = candidate != null && localProof != null && localProof.targetDocumentId == candidate.text("document_id") &&
                localProof.targetChapterId == candidate.number("legacy_chapter_id")?.toString() && localProof.capture.revision == ref.contentRevision
            if (!verified) reasons += "COURSE_PROOF_MISSING:${ref.bookKey}/${ref.chapterKey}/${ref.contentRevision}"
            else courseMap[ref] = localProof!!.targetChapterId
        }
        // Build complete mappings even for a blocked plan; no partial remap may reach a commit.
        val tableMap = BackupV2Table.entries.filter { it.primaryColumns == listOf("id") }.associateWith { table ->
            val keys = backup.records[table].map { it.text("id")!! }
            if (table in BackupV2RecordRules.longTables) allocateLongIds(keys, targetState.highWater.getValue(table))
            else keys.associateWith { identity(table.wireName, it) }
        }
        val idMap = BackupV2IdMap(tableMap, documents, sections, courseMap)
        val idMapHash = hashIdMap(idMap, outline)
        val mapped = if (courseMap.size != backup.manifest.courseChapterRefs.size) backup.records else LearningStateBackupCodec.remap(backup.records, idMap)
        val reuseDocs = docDecisions.filter { d ->
            d.disposition == BackupV2DocumentDisposition.REUSE && policy == DataManagementConflictPolicy.KEEP_LOCAL &&
                (backup.manifest.documents.single { it.exportDocumentId == d.sourceId }.kind != "BUILTIN_REFERENCE" ||
                    BackupV2IdentityPlanner.ownedTables.any { table -> targetState.rows[table.wireName].orEmpty().any { row ->
                        row.number("user_id") == owner && (row.text("document_id") == d.targetId || row.text("source_document_id") == d.targetId)
                    } })
        }.map { it.sourceId }.toSet()
        // Undirected relation graph keeps every connected shared learning component together.
        val adjacency = linkedMapOf<String,MutableSet<String>>()
        fun edge(a: String, b: String) { adjacency.getOrPut(a) { linkedSetOf() }.add(b); adjacency.getOrPut(b) { linkedSetOf() }.add(a) }
        backup.records.tables.forEach { (table, rows) -> rows.forEach { row ->
            val node = table.wireName + row.portableKey; adjacency.getOrPut(node) { linkedSetOf() }
            listOf("document_id", "source_document_id").forEach { c -> row.textOrNull(c)?.let { edge(node, "document:$it") } }
            BackupV2JsonProjection.locators(row).forEach { edge(node, "document:${it.documentId}") }
            BackupV2RecordRules.links[table].orEmpty().forEach { link -> row.textOrNull(link.column)?.let {
                edge(node, link.target.wireName + JsonArray(listOf(JsonPrimitive(it))).toString())
            } }
        } }
        val skippedNodes = linkedSetOf<String>(); val pending = ArrayDeque<String>(); reuseDocs.forEach { pending.add("document:$it") }
        while (pending.isNotEmpty()) { val n = pending.removeFirst(); if (skippedNodes.add(n)) adjacency[n].orEmpty().forEach(pending::add) }
        fun fixedCapture(doc: String, revision: String): BackupV2RevisionCapture? {
            val decision = docDecisions.singleOrNull { it.sourceId == doc } ?: return null
            return when(decision.disposition) {
                BackupV2DocumentDisposition.PUBLISH -> proofsByKey[doc to revision]?.capture
                BackupV2DocumentDisposition.REUSE -> targetState.revisions.singleOrNull { it.document.id == decision.targetId && it.revision == revision }
                else -> null
            }
        }
        fun sourceSection(doc: String, revision: String, section: String): com.example.myapplication.data.local.entity.ReaderSectionEntity? {
            val decision = docDecisions.singleOrNull { it.sourceId == doc } ?: return null
            if (decision.disposition !in setOf(BackupV2DocumentDisposition.PUBLISH,BackupV2DocumentDisposition.REUSE)) return null
            val capture = fixedCapture(doc,revision)
            val key = if (decision.disposition == BackupV2DocumentDisposition.REUSE) sections[section] else section
            val match = capture?.sections?.singleOrNull { it.id == key && it.contentRevision == revision }
            if (match == null) reasons += "SOURCE_SECTION_REVISION_MISMATCH:$doc/$revision/$section"
            return match
        }
        val papersById = backup.records[BackupV2Table.QUIZ_PAPERS].associateBy { it.text("id")!! }
        backup.records.tables.forEach { (table, rows) -> rows.filter { table.wireName + it.portableKey !in skippedNodes }.forEach { row ->
            val locators = BackupV2JsonProjection.locators(row)
            locators.forEach { locator ->
                val section = sourceSection(locator.documentId,locator.contentRevision,locator.sectionId)
                if (section != null) when(locator) {
                    is ReaderLocator.Fixed -> if (section.pageStart != locator.pageIndex || section.pageEnd == null || section.pageEnd < locator.pageIndex)
                        reasons += "SOURCE_FIXED_PAGE_MISMATCH:${row.portableKey}"
                    is ReaderLocator.Reflow -> if (section.pageStart != null || locator.canonicalEnd > section.charCount)
                        reasons += "SOURCE_REFLOW_RANGE_MISMATCH:${row.portableKey}"
                    is ReaderLocator.Note -> Unit // Note coordinates refer to the confirmed notebook revision, not section HTML offsets.
                }
            }
            row.textOrNull("source_revision")?.let { revision -> if (table in setOf(BackupV2Table.QUESTIONS,BackupV2Table.READER_SOURCE_REFERENCES) && locators.any {
                fixedCapture(it.documentId,it.contentRevision) != null && it.contentRevision != revision })
                reasons += "SOURCE_ROW_REVISION_MISMATCH:${row.portableKey}" }
            row.textOrNull("section_id")?.let { section -> locators.firstOrNull()?.let { first ->
                if (sourceSection(first.documentId,first.contentRevision,section) != null && section != first.sectionId) reasons += "SOURCE_SQL_SECTION_MISMATCH:${row.portableKey}"
            } }
            if (table == BackupV2Table.QUIZ_PAPERS) {
                val scope = BackupV2JsonProjection.scopeIdentity(row)
                scope.first?.let { doc -> (scope.second + BackupV2JsonProjection.coverageSections(row)).forEach { sourceSection(doc,row.text("source_revision")!!,it) } }
                val scopeRoot = BackupV2Json.parseText(row.text("scope_json")!!).jsonObject
                // Frozen empty/LIBRARY scopes are historical portable data, not a single-document generation request.
                if (scopeRoot.isNotEmpty() && scopeRoot["readingScope"]?.let(BackupV2Json::string) != "LIBRARY") {
                    val doc = row.textOrNull("document_id") ?: scope.first
                    val revision = row.text("source_revision")!!
                    if (scope.first != null && row.textOrNull("document_id") != scope.first)
                        reasons += "SOURCE_PAPER_DOCUMENT_MISMATCH:${row.portableKey}"
                    if (scopeRoot["sourceRevision"]?.let(BackupV2Json::string)?.let { it != revision } == true)
                        reasons += "SOURCE_SCOPE_REVISION_MISMATCH:${row.portableKey}"
                    val coverage = BackupV2Json.parseText(row.text("generation_coverage_json")!!).jsonObject
                    if (coverage["sourceRevision"]?.let(BackupV2Json::string)?.let { it != revision } == true)
                        reasons += "SOURCE_COVERAGE_REVISION_MISMATCH:${row.portableKey}"
                    val capture = doc?.let { fixedCapture(it,revision) }
                    if (capture != null && scopeRoot["extractorVersion"]?.let(BackupV2Json::string)?.let { it != capture.revisionEntity.extractorVersion } == true)
                        reasons += "SOURCE_SCOPE_EXTRACTOR_MISMATCH:${row.portableKey}"
                }
            }
            if (table == BackupV2Table.QUIZ_ITEMS) {
                val paper = papersById.getValue(row.text("paper_id")!!)
                val scopeRoot = BackupV2Json.parseText(paper.text("scope_json")!!).jsonObject
                if (scopeRoot.isNotEmpty() && scopeRoot["readingScope"]?.let(BackupV2Json::string) != "LIBRARY") {
                    val scope = BackupV2JsonProjection.scopeIdentity(paper)
                    val doc = paper.textOrNull("document_id") ?: scope.first
                    val revision = paper.text("source_revision")!!
                    QuizCodecs.decodeEvidence(row.text("evidence_json")!!).forEach { evidence ->
                        if (evidence.documentId != doc) reasons += "SOURCE_ITEM_DOCUMENT_MISMATCH:${row.portableKey}"
                        if (!evidence.matchesPinnedRevision(revision)) reasons += "SOURCE_ITEM_REVISION_MISMATCH:${row.portableKey}"
                        if (scope.second.isNotEmpty() && evidence.sectionId !in scope.second) reasons += "SOURCE_ITEM_SCOPE_MISMATCH:${row.portableKey}"
                    }
                }
            }
        } }
        backup.records[BackupV2Table.READER_ANNOTATION_TARGETS].groupBy { it.text("annotation_id") }.values.forEach { targets ->
            var previous: Int? = null
            targets.sortedBy { it.integer("ordinal") }.filter { BackupV2Table.READER_ANNOTATION_TARGETS.wireName + it.portableKey !in skippedNodes }.forEach { row ->
                val locator = ReaderLocatorCodec.decode(row.text("locator_json")!!)
                sourceSection(locator.documentId,locator.contentRevision,locator.sectionId)?.let { section ->
                    if (previous != null && section.orderIndex < previous!!) reasons += "SOURCE_TARGET_ORDER_MISMATCH:${row.portableKey}"
                    previous = section.orderIndex
                }
            }
        }
        val rowDecisions = arrayListOf<BackupV2RowDecision>()
        val sqlRows = BackupV2Table.entries.associateWith { arrayListOf<BackupV2SqlRow>() }
        val processed = BackupV2Table.entries.associateWith { arrayListOf<BackupV2Record>() }
        val pendingTargets = docDecisions.filter { it.disposition == BackupV2DocumentDisposition.PENDING }.map { it.targetId }.toSet()
        backup.records.tables.forEach { (table, rows) -> rows.forEachIndexed { index, source ->
            var disposition = if (table.wireName + source.portableKey in skippedNodes) BackupV2RowDisposition.SKIP else BackupV2RowDisposition.INSERT
            var detail = if (disposition == BackupV2RowDisposition.SKIP) "KEEP_LOCAL closed component" else ""
            if (table == BackupV2Table.READER_ANNOTATIONS && (source.text("kind") == "INK" || source.text("ink_blob_path") != null)) {
                disposition = BackupV2RowDisposition.BLOCKED; reasons += "UNSUPPORTED_INK_RESOURCE:${source.portableKey}"
            }
            val fields = mapped[table][index].fields.toMutableMap()
            if (table == BackupV2Table.STUDY_PROGRESS && courseMap.size == backup.manifest.courseChapterRefs.size &&
                targetState.rows[table.wireName].orEmpty().any { it.number("user_id") == owner && it.number("chapter_id")?.toString() == (fields["chapter_id"] as? BackupV2Value.Text)?.value }) {
                disposition = BackupV2RowDisposition.SKIP; detail = "Existing unique user/chapter progress preserved"
            }
            if (table == BackupV2Table.READER_STATES && targetState.rows[table.wireName].orEmpty().any {
                it.number("user_id") == owner && it.text("document_id") == (fields["document_id"] as? BackupV2Value.Text)?.value }) {
                disposition = BackupV2RowDisposition.SKIP; detail = "Existing reading state preserved"
            }
            if (table == BackupV2Table.READER_BOOKMARKS) fields["anchor_hash"] = BackupV2Value.Text(ReaderMutationHasher.bookmarkHash(ReaderLocatorCodec.decode((fields.getValue("locator_json") as BackupV2Value.Text).value)))
            if (table == BackupV2Table.READER_SOURCE_REFERENCES) fields["reference_key"] = BackupV2Value.Text(identity("reference-key", source.text("id")!!))
            val row = BackupV2Record(table, fields)
            processed.getValue(table).add(row)
            val sql = fields.toMutableMap()
            // Only physical nullable section FK is cleared; typed historical locators remain intact.
            val doc = row.textOrNull("document_id") ?: row.textOrNull("source_document_id") ?: BackupV2JsonProjection.locators(row).firstOrNull()?.documentId
            if (doc in pendingTargets && "section_id" in sql) sql["section_id"] = BackupV2Value.Null
            if (table == BackupV2Table.READER_ANNOTATION_TARGETS && doc in pendingTargets) sql["precision"] = BackupV2Value.Text("UNRESOLVED")
            // Section/page ownership is proved above. Exact historical quote/geometry is not proved by catalog metadata alone.
            if (table == BackupV2Table.READER_ANNOTATION_TARGETS && row.text("precision") == "EXACT") {
                sql["precision"] = BackupV2Value.Text("UNRESOLVED")
                if (disposition == BackupV2RowDisposition.INSERT) detail = "Historical exact quote/geometry has no independent anchor proof; restored UNRESOLVED"
            }
            if (table in ownedTables) sql["user_id"] = BackupV2Value.Integer(owner!!)
            if (table == BackupV2Table.QUESTIONS) sql["owner_user_id"] = BackupV2Value.Integer(owner!!)
            if (table == BackupV2Table.READER_STATES) { sql["writer_epoch"] = BackupV2Value.Integer(0); sql["last_sequence"] = BackupV2Value.Integer(0) }
            if (table == BackupV2Table.READER_NOTE_VERSIONS) sql["operation_id"] = BackupV2Value.Text(identity("note-operation", source.text("id")!!))
            if (table == BackupV2Table.QUIZ_PAPERS) sql["generation_run_id"] = BackupV2Value.Null
            if (table == BackupV2Table.QUIZ_ATTEMPTS) sql["submit_key"] = BackupV2Value.Text(identity("submit", source.text("id")!!))
            if (table == BackupV2Table.QUIZ_GRADE_EVENTS) sql["assessment_key"] = BackupV2Value.Text(identity("assessment", source.text("id")!!))
            if (table == BackupV2Table.READER_ANNOTATION_TARGETS) sql["target_id"] = sql.remove("id")!!
            if (disposition == BackupV2RowDisposition.INSERT) sqlRows.getValue(table).add(BackupV2SqlRow(sql))
            rowDecisions += BackupV2RowDecision(table, source.portableKey, disposition, detail)
        } }
        // Planned PKs and every schema7 unique key are checked against existing rows and the plan itself.
        sqlRows.forEach { (table, rows) ->
            val existing = targetState.rows[table.wireName].orEmpty()
            val seen = existing.toMutableList()
            rows.forEach { row ->
                val keys = uniqueKeys.getValue(table)
                if (keys.any { cols -> cols.all { row.fields[it] != null && row.fields[it] !== BackupV2Value.Null } &&
                        seen.any { old -> cols.all { old.fields[it] == row.fields[it] } } }) reasons += "TARGET_UNIQUE_CONFLICT:${table.wireName}"
                seen += row
            }
        }
        docDecisions.filter { it.disposition != BackupV2DocumentDisposition.REUSE }.forEach { d ->
            if (targetState.catalog.any { it.id == d.targetId }) reasons += "TARGET_DOCUMENT_CONFLICT:${d.targetId}"
        }
        val plannedMetadata = linkedMapOf("reader_content_revisions" to arrayListOf<BackupV2SqlRow>(),"reader_sections" to arrayListOf<BackupV2SqlRow>(),"reader_outline_nodes" to arrayListOf<BackupV2SqlRow>())
        proofs.filter { p -> docDecisions.any { it.sourceId == p.exportDocumentId && it.disposition == BackupV2DocumentDisposition.PUBLISH } }.forEach { p ->
            val target = documents.getValue(p.exportDocumentId)
            plannedMetadata.getValue("reader_content_revisions") += BackupV2SqlRow(mapOf("document_id" to BackupV2Value.Text(target),"revision" to BackupV2Value.Text(p.revision)))
            p.capture.sections.forEach { s -> plannedMetadata.getValue("reader_sections") += BackupV2SqlRow(mapOf("id" to BackupV2Value.Text(sections.getValue(s.id)),
                "document_id" to BackupV2Value.Text(target),"content_revision" to BackupV2Value.Text(p.revision),"resource_href" to BackupV2Value.Text(s.resourceHref))) }
            p.capture.outline.forEach { n -> plannedMetadata.getValue("reader_outline_nodes") += BackupV2SqlRow(mapOf("id" to BackupV2Value.Text(outline.getValue(n.id)),
                "document_id" to BackupV2Value.Text(target),"content_revision" to BackupV2Value.Text(p.revision),"node_key" to BackupV2Value.Text(outline.getValue(n.id)))) }
        }
        plannedMetadata.forEach { (table, rows) ->
            val keys = when(table) { "reader_content_revisions" -> listOf(listOf("document_id","revision"))
                "reader_sections" -> listOf(listOf("id"),listOf("document_id","content_revision","resource_href"))
                else -> listOf(listOf("id"),listOf("document_id","content_revision","node_key")) }
            val seen = targetState.rows[table].orEmpty().toMutableList()
            rows.forEach { row -> if (keys.any { key -> seen.any { old -> key.all { old.fields[it] == row.fields[it] } } }) reasons += "TARGET_METADATA_CONFLICT:$table"; seen += row }
        }
        val counts = BackupV2RestoreCounts(sqlRows.values.sumOf { it.size }, docDecisions.count { it.disposition == BackupV2DocumentDisposition.REUSE },
            rowDecisions.count { it.disposition == BackupV2RowDisposition.SKIP }, reasons.distinct().size + rowDecisions.count { it.disposition == BackupV2RowDisposition.BLOCKED },
            docDecisions.size, docDecisions.count { it.disposition == BackupV2DocumentDisposition.PENDING }, docDecisions.count { it.disposition == BackupV2DocumentDisposition.PUBLISH })
        val planRows = JsonObject(sqlRows.mapKeys { it.key.wireName }.mapValues { JsonArray(it.value.map(BackupV2SqlRow::json)) })
        val planHash = v2Digest(v2Canonical(buildJsonObject {
            put("modelVersion", 1); put("contentHash", backup.manifest.contentHash); put("owner", owner.toString()); put("epoch", expectedIdentity.epoch.toString())
            put("targetHash", targetState.stateHash); put("idMapHash", idMapHash); put("operationId", operationId); put("policy", policy.name)
            put("inputHash", v2Digest(v2Canonical(backup.manifest.toJson()) + LearningStateBackupCodec.encode(backup.records).toString(Charsets.UTF_8)))
            put("rows", planRows); put("documents", JsonArray(docDecisions.map { JsonPrimitive(it.toString()) }))
            put("decisions", JsonArray(rowDecisions.map { JsonPrimitive(it.toString()) })); put("counts", counts.toString())
            put("proofs", JsonArray(proofs.map { JsonPrimitive("${it.exportDocumentId}/${it.revision}/${it.proofDigest}") }.sortedBy { it.content })); put("reasons", JsonArray(reasons.distinct().sorted().map(::JsonPrimitive)))
        }))
        return BackupV2RestorePlan(backup, expectedIdentity, copyNonce, policy, idMap, docDecisions, rowDecisions,
            BackupV2Records(processed), sqlRows, counts, targetState, proofs, outline, operationId, idMapHash, planHash, reasons.distinct())
    }

    companion object {
        internal val ownedTables = setOf(BackupV2Table.TEST_RECORDS, BackupV2Table.FAVORITES, BackupV2Table.STUDY_PROGRESS,
            BackupV2Table.READER_STATES, BackupV2Table.READER_ANNOTATIONS, BackupV2Table.READER_BOOKMARKS, BackupV2Table.READER_SOURCE_REFERENCES,
            BackupV2Table.READER_NOTEBOOKS, BackupV2Table.QUIZ_PAPERS, BackupV2Table.QUIZ_ATTEMPTS)
        internal val uniqueKeys = BackupV2Table.entries.associateWith { t ->
            val pk = when(t) { BackupV2Table.READER_STATES -> listOf("user_id", "document_id"); BackupV2Table.READER_ANNOTATION_TARGETS -> listOf("target_id"); else -> t.primaryColumns }
            listOf(pk) + when(t) {
                BackupV2Table.STUDY_PROGRESS -> listOf(listOf("user_id", "chapter_id"))
                BackupV2Table.TEST_ANSWERS -> listOf(listOf("test_id", "question_id"))
                BackupV2Table.FAVORITES -> listOf(listOf("user_id", "question_id", "source"))
                BackupV2Table.READER_BOOKMARKS -> listOf(listOf("user_id", "document_id", "anchor_hash"))
                BackupV2Table.READER_SOURCE_REFERENCES -> listOf(listOf("user_id", "reference_key"))
                BackupV2Table.READER_NOTEBOOKS -> listOf(listOf("user_id", "document_id"))
                BackupV2Table.READER_NOTE_VERSIONS -> listOf(listOf("notebook_id", "revision"), listOf("notebook_id", "operation_id"))
                BackupV2Table.QUIZ_PAPERS -> listOf(listOf("generation_run_id"))
                BackupV2Table.QUIZ_ITEMS -> listOf(listOf("paper_id", "ordinal"))
                BackupV2Table.QUIZ_ATTEMPTS -> listOf(listOf("submit_key"))
                BackupV2Table.QUIZ_GRADE_EVENTS -> listOf(listOf("attempt_id", "assessment_key"))
                BackupV2Table.READER_ANNOTATION_TARGETS -> listOf(listOf("annotation_id", "ordinal"))
                else -> emptyList()
            }
        }
        internal fun hashIdMap(map: BackupV2IdMap, outline: Map<String,String>) = v2Digest(v2Canonical(buildJsonObject {
            put("documents", JsonObject(map.documents.mapValues { JsonPrimitive(it.value) })); put("sections", JsonObject(map.sections.mapValues { JsonPrimitive(it.value) }))
            put("outline", JsonObject(outline.mapValues { JsonPrimitive(it.value) }))
            put("tables", JsonObject(map.tables.mapKeys { it.key.wireName }.mapValues { JsonObject(it.value.mapValues { p -> JsonPrimitive(p.value) }) }))
            put("courses", JsonArray(map.courseChapters.entries.map { JsonPrimitive("${it.key}=${it.value}") }.sortedBy { it.content }))
        }))
    }
}

internal fun BackupV2Record.textOrNull(column: String): String? = (fields[column] as? BackupV2Value.Text)?.value
