package com.example.myapplication.data.reader.export

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import com.example.myapplication.domain.reader.ReaderLocatorValidator
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import com.example.myapplication.data.local.entity.QuestionEntity
import com.example.myapplication.data.reader.content.ContentPackageQuestionGrading
import com.example.myapplication.domain.quiz.QuizCodecs
import kotlinx.serialization.json.*

internal object BackupV2RecordRules {
    private data class Field(val kind: Char, val nullable: Boolean)
    val longTables = setOf(BackupV2Table.QUESTIONS, BackupV2Table.TEST_RECORDS, BackupV2Table.TEST_ANSWERS, BackupV2Table.FAVORITES, BackupV2Table.STUDY_PROGRESS)
    // Frozen backup-field-map-v2 + schema7 affinity/nullability (targets wire.id = SQL target_id).
    private val specs = listOf(
        "questions|id:L type:T chapter_id:L? stem:T options:T? answer:T synonyms:T? knowledge_point:T? analysis:T score:N enabled:N created_at:N origin:T source_document_id:T? source_locator_json:T? source_excerpt:T? source_revision:T?",
        "test_records|id:L paper_type:T status:T started_at:N submitted_at:N? duration_limit_seconds:N remaining_seconds:N? used_seconds:N? total_score:N? choice_score:N? judge_score:N? fill_score:N? essay_score:N? created_at:N",
        "test_answers|id:L test_id:L question_id:L order_index:N user_answer:T? score:N is_correct:N?",
        "favorites|id:L question_id:L source:T created_at:N",
        "study_progress|id:L chapter_id:L completed:N quiz_score:N? quiz_total:N? updated_at:N",
        "reader_states|document_id:T locator_json:T? settings_json:T? is_completed:N is_hidden:N display_title_override:T? last_read_at:N revision:N",
        "reader_annotations|id:T document_id:T section_id:T? kind:T locator_json:T quote_snapshot:T? note_text:T? style_json:T ink_blob_path:T? revision:N deleted_at:N? created_at:N updated_at:N",
        "reader_bookmarks|id:T document_id:T locator_json:T anchor_hash:T preview:T created_at:N",
        "reader_source_references|id:T reference_key:T source_document_id:T? section_id:T? source_revision:T locator_json:T quote_snapshot:T quote_sha256:T bibliographic_json:T evidence_kind:T extraction_quality:T created_at:N",
        "reader_notebooks|id:T document_id:T body_markdown:T revision:N created_at:N updated_at:N",
        "reader_note_versions|id:T notebook_id:T revision:N body_markdown:T actor:T created_at:N",
        "quiz_papers|id:T document_id:T? scope_json:T source_revision:T template_version:T question_count:N max_score:N status:T generation_coverage_json:T generated_by:T created_at:N",
        "quiz_items|id:T paper_id:T question_id:L ordinal:N question_snapshot_json:T rubric_json:T evidence_json:T",
        "quiz_attempts|id:T paper_id:T status:T current_ordinal:N started_at:N active_used_ms:N submitted_at:N? total_score:N? grading_status:T revision:N grade_revision:N",
        "quiz_attempt_answers|attempt_id:T item_id:T raw_answer_json:T? edit_sequence:N score:N? is_correct:N? matched_rubric_json:T grading_method:T feedback:T updated_at:N",
        "quiz_grade_events|id:T attempt_id:T item_id:T grade_revision:N old_assessment_json:T? new_assessment_json:T reason:T created_at:N",
        "reader_annotation_targets|id:T annotation_id:T ordinal:N locator_json:T anchor_version:N precision:T",
    ).associate { line ->
        val parts = line.split('|')
        BackupV2Table.entries.single { it.wireName == parts[0] } to parts[1].split(' ').associate { entry ->
            val p = entry.split(':'); p[0] to Field(p[1][0], p[1].endsWith('?'))
        }
    }
    fun longId(value: String): Long {
        v2require(value.matches(Regex("[1-9][0-9]{0,18}")), "INVALID_LONG_ID", "positive decimal Long required")
        return value.toLongOrNull()?.takeIf { it > 0 } ?: throw BackupV2Exception("INVALID_LONG_ID", "outside real Long range")
    }
    fun checkRow(row: BackupV2Record) {
        val spec = specs.getValue(row.table)
        v2require(row.fields.keys == spec.keys, detail = "${row.table.wireName}: missing or unknown column")
        spec.forEach { (column, field) ->
            val value = row.fields.getValue(column)
            if (value === BackupV2Value.Null) {
                v2require(field.nullable, detail = "${row.table.wireName}.$column is not nullable")
            } else when (field.kind) {
                'T', 'L' -> {
                    v2require(value is BackupV2Value.Text, detail = "$column requires string")
                    val text = (value as BackupV2Value.Text).value
                    v2require(text.length <= BackupV2Limits.JSON_BYTES, "LIMIT_EXCEEDED", "$column text limit")
                    v2require(ReaderLocatorValidator.isWellFormedUtf16(text), "INVALID_UTF16", "$column has unpaired surrogate")
                    if (field.kind == 'L') longId(text)
                    if (column in row.table.primaryColumns || column.endsWith("_id")) v2require(text.isNotBlank(), detail = "$column identity is empty")
                    BackupV2JsonProjection.validateColumn(row.table, column, text)
                }
                'N' -> {
                    v2require(value is BackupV2Value.Integer, detail = "$column requires integer")
                    val number = (value as BackupV2Value.Integer).value
                    v2require(number in 0..BackupV2Limits.SAFE_INTEGER, "UNSAFE_INTEGER", "$column must be nonnegative safe integer")
                    if (column in setOf("enabled", "completed", "is_completed", "is_hidden", "is_correct"))
                        v2require(number in 0..1, detail = "$column must be 0 or 1")
                }
            }
        }
        if (row.table == BackupV2Table.QUESTIONS && row.text("synonyms")?.trimStart()?.startsWith('{') == true) {
            try {
                // Stored UTF16 can be valid while a nested JSON escape decodes to an unpaired surrogate.
                BackupV2Json.strings(BackupV2Json.parseText(row.text("answer")!!))
                val score = row.integer("score")!!
                v2require(score <= Int.MAX_VALUE, "UNSAFE_INTEGER", "question score exceeds typed Int")
                ContentPackageQuestionGrading.decode(QuestionEntity(
                    id = longId(row.text("id")!!), type = row.text("type")!!, stem = row.text("stem")!!,
                    answer = row.text("answer")!!, synonyms = row.text("synonyms"), analysis = row.text("analysis")!!,
                    score = score.toInt(), createdAt = row.integer("created_at")!!, origin = row.text("origin")!!,
                ))
            } catch (e: BackupV2Exception) { throw e }
            catch (e: IllegalArgumentException) { throw BackupV2Exception("INVALID_JSON_COLUMN", "questions.synonyms typed grading envelope", e) }
        }
        if (row.table == BackupV2Table.QUIZ_ITEMS) {
            val item = QuizCodecs.decodeItemSnapshot(row.text("question_snapshot_json")!!)
            val evidence = QuizCodecs.decodeEvidence(row.text("evidence_json")!!)
            v2require((item.itemId == null || item.itemId == row.text("id")) && item.ordinal.toLong() == row.integer("ordinal"), "ORPHAN_FK", "snapshot item identity/ordinal mismatch")
            v2require(evidence.map { it.evidenceId }.distinct().size == evidence.size && item.evidenceIds.distinct().size == item.evidenceIds.size &&
                item.evidenceIds.toSet() == evidence.map { it.evidenceId }.toSet(), "ORPHAN_FK", "snapshot evidence registry mismatch")
            v2require(item.rubric == QuizCodecs.decodeRubric(row.text("rubric_json")!!), "ORPHAN_FK", "snapshot rubric mismatch")
        }
        if (row.table == BackupV2Table.READER_ANNOTATIONS) v2require(row.text("kind") in setOf("HIGHLIGHT", "NOTE", "INK"), detail = "unknown annotation kind")
        if (row.table == BackupV2Table.READER_ANNOTATION_TARGETS) {
            v2require(row.integer("anchor_version") == 1L && row.text("precision") in setOf("EXACT", "PAGE_ONLY", "UNRESOLVED"), detail = "unsupported anchor/precision")
        }
    }
    internal data class Link(val column: String, val target: BackupV2Table)
    val links: Map<BackupV2Table, List<Link>> = mapOf(
        BackupV2Table.TEST_ANSWERS to listOf(Link("test_id", BackupV2Table.TEST_RECORDS), Link("question_id", BackupV2Table.QUESTIONS)),
        BackupV2Table.FAVORITES to listOf(Link("question_id", BackupV2Table.QUESTIONS)),
        BackupV2Table.READER_NOTE_VERSIONS to listOf(Link("notebook_id", BackupV2Table.READER_NOTEBOOKS)),
        BackupV2Table.QUIZ_ITEMS to listOf(Link("paper_id", BackupV2Table.QUIZ_PAPERS), Link("question_id", BackupV2Table.QUESTIONS)),
        BackupV2Table.QUIZ_ATTEMPTS to listOf(Link("paper_id", BackupV2Table.QUIZ_PAPERS)),
        BackupV2Table.QUIZ_ATTEMPT_ANSWERS to listOf(Link("attempt_id", BackupV2Table.QUIZ_ATTEMPTS), Link("item_id", BackupV2Table.QUIZ_ITEMS)),
        BackupV2Table.QUIZ_GRADE_EVENTS to listOf(Link("attempt_id", BackupV2Table.QUIZ_ATTEMPTS), Link("item_id", BackupV2Table.QUIZ_ITEMS)),
        BackupV2Table.READER_ANNOTATION_TARGETS to listOf(Link("annotation_id", BackupV2Table.READER_ANNOTATIONS)),
    )
    fun checkRelations(records: BackupV2Records) {
        val indexes = BackupV2Table.entries.associateWith { table ->
            val rows = records[table]
            val keys = rows.map { row -> table.primaryColumns.map { row.text(it) } }
            v2require(keys.toSet().size == keys.size, "DUPLICATE_ID", table.wireName)
            if (table.primaryColumns.size == 1) rows.associateBy { it.text(table.primaryColumns[0])!! } else emptyMap()
        }
        links.forEach { (table, relations) -> records[table].forEach { row -> relations.forEach { relation ->
            val value = row.text(relation.column)
            v2require(value == null || indexes.getValue(relation.target).containsKey(value), "ORPHAN_FK", "${table.wireName}.${relation.column}")
        } } }
        val targetsByAnnotation = records[BackupV2Table.READER_ANNOTATION_TARGETS].groupBy { it.text("annotation_id") }
        indexes.getValue(BackupV2Table.READER_ANNOTATIONS).values.forEach { parent ->
            val parentLocator = ReaderLocatorCodec.decode(parent.text("locator_json")!!)
            val documentId = parent.text("document_id")!!
            v2require(parentLocator.documentId == documentId &&
                (parent.text("section_id") == null || parent.text("section_id") == parentLocator.sectionId), "ORPHAN_FK", "annotation locator outside parent columns")
            val targets = targetsByAnnotation[parent.text("id")].orEmpty().sortedBy { it.integer("ordinal") }
            // Existing legacy annotations can have no physical targets; never manufacture rows here.
            if (targets.isEmpty()) return@forEach
            val locators = targets.mapIndexed { ordinal, target ->
                v2require(target.integer("ordinal") == ordinal.toLong(), "INVALID_TARGET_ORDER", "physical annotation ordinals must be 0..n-1")
                val locator = ReaderLocatorCodec.decode(target.text("locator_json")!!)
                v2require(locator.documentId == documentId && locator.contentRevision == parentLocator.contentRevision,
                    "ORPHAN_FK", "target locator outside parent annotation document/revision")
                locator
            }
            v2require(ReaderLocatorCodec.encode(locators.first()) == ReaderLocatorCodec.encode(parentLocator),
                "ORPHAN_FK", "first target must equal normalized parent locator")
            for (index in 1 until locators.size) {
                val before = locators[index - 1]; val after = locators[index]
                v2require(before.javaClass == after.javaClass, "INVALID_TARGET_ORDER", "adjacent annotation target kinds differ")
                if (before.sectionId == after.sectionId) {
                    v2require(before is ReaderLocator.Reflow && after is ReaderLocator.Reflow,
                        "INVALID_TARGET_ORDER", "multiple targets in one section require reflow fragments")
                    before as ReaderLocator.Reflow; after as ReaderLocator.Reflow
                    v2require(before.canonicalEnd <= after.canonicalStart && before.canonicalStart < after.canonicalStart,
                        "INVALID_TARGET_ORDER", "reflow fragments overlap or do not progress")
                    if (before.blockId != after.blockId) {
                        val precision = targets[index - 1].text("precision")
                        v2require(precision in setOf("EXACT", "UNRESOLVED") && targets[index].text("precision") == precision &&
                            before.canonicalEnd.toLong() - before.canonicalStart == before.exactQuote.length.toLong() &&
                            after.canonicalEnd.toLong() - after.canonicalStart == after.exactQuote.length.toLong(),
                            "INVALID_TARGET_ORDER", "cross-block coordinate/precision kind differs")
                    }
                }
                // Cross-section orderIndex/source proof is absent from this wire; do not infer it from sectionIds.
            }
        }
        listOf(BackupV2Table.QUIZ_ATTEMPT_ANSWERS, BackupV2Table.QUIZ_GRADE_EVENTS).forEach { table -> records[table].forEach { row ->
            val attempt = indexes.getValue(BackupV2Table.QUIZ_ATTEMPTS).getValue(row.text("attempt_id")!!)
            val item = indexes.getValue(BackupV2Table.QUIZ_ITEMS).getValue(row.text("item_id")!!)
            v2require(attempt.text("paper_id") == item.text("paper_id"), "ORPHAN_FK", "item is outside attempt paper")
        } }
        val itemsByPaper = records[BackupV2Table.QUIZ_ITEMS].groupBy { it.text("paper_id") }
        records[BackupV2Table.QUIZ_PAPERS].forEach { paper ->
            val items = itemsByPaper[paper.text("id")].orEmpty()
            v2require(items.size.toLong() == paper.integer("question_count"), detail = "paper question_count mismatch")
            v2require(items.map { it.integer("ordinal") }.toSet().size == items.size, "DUPLICATE_ID", "paper item ordinal")
        }
        val notebooks = indexes.getValue(BackupV2Table.READER_NOTEBOOKS)
        records.tables.values.flatten().forEach { row -> BackupV2JsonProjection.locators(row).forEach { locator ->
            if (locator is com.example.myapplication.domain.reader.ReaderLocator.Note) {
                val notebook = notebooks[locator.notebookId]
                v2require(notebook != null && notebook.text("document_id") == locator.documentId, "ORPHAN_FK", "Note.notebookId")
            }
        } }
    }
}

/** Strict bounded parser before kotlinx/typed codecs: duplicate names, UTF-8/UTF-16 and depth are never normalized away. */
internal object BackupV2Json {
    fun parse(bytes: ByteArray, limit: Int = BackupV2Limits.JSON_BYTES): JsonElement {
        v2require(bytes.size <= limit, "LIMIT_EXCEEDED", "JSON byte limit")
        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
        catch (e: Exception) { throw BackupV2Exception("INVALID_UTF8", "JSON is not UTF-8", e) }
        return parseText(text, limit)
    }
    fun parseText(text: String, limit: Int = BackupV2Limits.JSON_BYTES): JsonElement {
        v2require(text.length <= limit, "LIMIT_EXCEEDED", "JSON text length")
        v2require(ReaderLocatorValidator.isWellFormedUtf16(text), "INVALID_UTF16", "JSON contains unpaired surrogate")
        v2require(text.toByteArray(Charsets.UTF_8).size <= limit, "LIMIT_EXCEEDED", "JSON byte limit")
        return Parser(text).parse()
    }
    fun obj(element: JsonElement, allowed: Set<String>, required: Set<String> = allowed): JsonObject {
        val root = element as? JsonObject ?: throw BackupV2Exception("INVALID_JSON_SHAPE", "object required")
        v2require(root.keys.all { it in allowed } && root.keys.containsAll(required), "INVALID_JSON_SHAPE", "missing or unknown property")
        return root
    }
    fun string(element: JsonElement?): String {
        val p = element as? JsonPrimitive
        v2require(p != null && p !is JsonNull && p.isString, "INVALID_JSON_SHAPE", "string required")
        return p!!.content
    }
    fun integer(element: JsonElement?): Long {
        val p = element as? JsonPrimitive
        v2require(p != null && !p.isString && p.content.matches(Regex("-?(0|[1-9][0-9]*)")), "INVALID_JSON_SHAPE", "integer token required")
        val value = p!!.content.toLongOrNull() ?: throw BackupV2Exception("UNSAFE_INTEGER", "integer overflow")
        v2require(value in -BackupV2Limits.SAFE_INTEGER..BackupV2Limits.SAFE_INTEGER, "UNSAFE_INTEGER", "unsafe numeric integer")
        return value
    }
    fun bool(element: JsonElement?): Boolean {
        val p = element as? JsonPrimitive
        v2require(p != null && !p.isString && p.content in setOf("true", "false"), "INVALID_JSON_SHAPE", "boolean required")
        return p!!.content == "true"
    }
    fun array(element: JsonElement?): JsonArray = element as? JsonArray ?: throw BackupV2Exception("INVALID_JSON_SHAPE", "array required")
    fun strings(element: JsonElement?): List<String> = array(element).map(::string)
    private class Parser(val text: String) {
        var cursor = 0
        var nodes = 0
        fun parse(): JsonElement {
            val result = value(0); ws(); v2require(cursor == text.length, "INVALID_JSON", "trailing JSON input"); return result
        }
        fun ws() { while (cursor < text.length && text[cursor] in " \t\r\n") cursor++ }
        fun take(c: Char): Boolean { ws(); return if (cursor < text.length && text[cursor] == c) { cursor++; true } else false }
        fun need(c: Char) { v2require(take(c), "INVALID_JSON", "expected $c") }
        fun value(depth: Int): JsonElement {
            ws(); v2require(depth <= BackupV2Limits.DEPTH, "JSON_DEPTH_EXCEEDED", "depth > 64")
            nodes++; v2require(nodes <= 2_000_000, "LIMIT_EXCEEDED", "JSON node limit")
            v2require(cursor < text.length, "INVALID_JSON", "unexpected EOF")
            return when (text[cursor]) {
                '{' -> { cursor++; val map = linkedMapOf<String, JsonElement>()
                    if (!take('}')) { do { ws(); val key = quoted(); v2require(!map.containsKey(key), "DUPLICATE_JSON_PROPERTY", key)
                        need(':'); map[key] = value(depth + 1) } while (take(',')); need('}') }; JsonObject(map) }
                '[' -> { cursor++; val list = arrayListOf<JsonElement>()
                    if (!take(']')) { do { v2require(list.size < BackupV2Limits.RECORDS, "LIMIT_EXCEEDED", "array size"); list.add(value(depth + 1)) } while (take(',')); need(']') }; JsonArray(list) }
                '"' -> JsonPrimitive(quoted())
                't' -> { literal("true"); JsonPrimitive(true) }
                'f' -> { literal("false"); JsonPrimitive(false) }
                'n' -> { literal("null"); JsonNull }
                else -> { val start = cursor
                    while (cursor < text.length && text[cursor] in "-+0123456789.eE") cursor++
                    val token = text.substring(start, cursor)
                    v2require(token.length in 1..128 && token.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")), "INVALID_JSON", "invalid number")
                    Json.parseToJsonElement(token)
                }
            }
        }
        fun literal(word: String) { v2require(text.startsWith(word, cursor), "INVALID_JSON", "invalid literal"); cursor += word.length }
        fun quoted(): String {
            need('"'); val out = StringBuilder()
            while (cursor < text.length) {
                val c = text[cursor++]
                if (c == '"') { val s = out.toString(); v2require(ReaderLocatorValidator.isWellFormedUtf16(s), "INVALID_UTF16", "escaped surrogate"); return s }
                if (c == '\\') {
                    v2require(cursor < text.length, "INVALID_JSON", "truncated escape")
                    when (val escape = text[cursor++]) {
                        '"', '\\', '/' -> out.append(escape)
                        'b' -> out.append('\b'); 'f' -> out.append('\u000c'); 'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t')
                        'u' -> { v2require(cursor + 4 <= text.length, "INVALID_JSON", "truncated unicode escape")
                            val hex = text.substring(cursor, cursor + 4); v2require(hex.matches(Regex("[0-9a-fA-F]{4}")), "INVALID_JSON", "bad unicode escape")
                            out.append(hex.toInt(16).toChar()); cursor += 4 }
                        else -> throw BackupV2Exception("INVALID_JSON", "unknown escape")
                    }
                } else { v2require(c.code >= 32, "INVALID_JSON", "control in string"); out.append(c) }
            }
            throw BackupV2Exception("INVALID_JSON", "unterminated string")
        }
    }
}
