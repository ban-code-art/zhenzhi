package com.example.myapplication.data.reader.export

import java.util.Collections
import com.example.myapplication.data.reader.content.ContentPackageCodec
import com.example.myapplication.domain.quiz.QuizCodecs
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import kotlinx.serialization.json.*

class BackupV2ProjectionChange(val table: BackupV2Table, val rowKey: String, val column: String, excludedProperties: List<String>) {
    val excludedProperties: List<String> = Collections.unmodifiableList(ArrayList(excludedProperties))
}
class BackupV2Projection(val records: BackupV2Records, changes: List<BackupV2ProjectionChange>) {
    val changes: List<BackupV2ProjectionChange> = Collections.unmodifiableList(ArrayList(changes))
}

/** Explicit typed projections. No regex replacement, recursive identity rewriting or unknown-field passthrough. */
object BackupV2JsonProjection {
    private val scopePrivate = setOf("snapshotId", "sourceOwnerUserId", "accessEpoch", "metadataRevision")
    private val scopeFields = scopePrivate + setOf("schemaVersion", "documentId", "sectionId", "sectionIds", "readingScope", "sourceRevision", "extractorVersion")
    private val coverageFields = setOf("schemaVersion", "templateVersion", "sourceRevision", "coveredSections", "uncoveredSections", "coveredKnowledgePoints", "uncoveredKnowledgePoints", "excludedSections", "coveredRatio", "knowledgePointCoverage", "truncated", "sufficient", "notes", "supportReview", "personalReview")
    private data class Scope(val root: JsonObject, val documentId: String?, val sections: List<String>, val privateFields: Set<String>)
    private data class Coverage(val root: JsonObject, val sections: List<String>, val privateFields: Set<String>)
    private fun version(root: JsonObject, key: String = "schemaVersion") {
        root[key]?.let { v2require(BackupV2Json.integer(it) == 1L, "UNSUPPORTED_JSON_VERSION", "unsupported $key") }
    }
    private fun scope(text: String): Scope {
        val root = BackupV2Json.obj(BackupV2Json.parseText(text), scopeFields, emptySet()); version(root)
        if (root.isEmpty()) return Scope(root, null, emptyList(), emptySet()) // frozen historical empty scope
        root.forEach { (key, value) -> when (key) {
            "sectionIds" -> BackupV2Json.strings(value)
            "schemaVersion", "accessEpoch", "metadataRevision" -> v2require(BackupV2Json.integer(value) >= 0, detail = "negative scope $key")
            "sourceOwnerUserId" -> { val owner = BackupV2Json.string(value); if (owner != "PUBLIC") BackupV2RecordRules.longId(owner) }
            else -> v2require(BackupV2Json.string(value).isNotBlank(), detail = "blank scope $key")
        } }
        root["readingScope"]?.let { v2require(BackupV2Json.string(it) in setOf("CURRENT_SECTION", "CURRENT_DOCUMENT", "LIBRARY"), detail = "unknown readingScope") }
        val document = root["documentId"]?.let(BackupV2Json::string)
        val sections = root["sectionIds"]?.let(BackupV2Json::strings).orEmpty() + listOfNotNull(root["sectionId"]?.let(BackupV2Json::string))
        v2require(document != null || sections.isEmpty(), "INVALID_JSON_SHAPE", "scope sections require documentId")
        return Scope(root, document, sections, root.keys.intersect(scopePrivate))
    }
    private fun coverage(text: String): Coverage {
        val root = BackupV2Json.obj(BackupV2Json.parseText(text), coverageFields, emptySet()); version(root)
        val sections = arrayListOf<String>()
        root.forEach { (key, value) -> when (key) {
            "schemaVersion" -> Unit
            "coveredSections", "uncoveredSections" -> sections.addAll(BackupV2Json.strings(value))
            "coveredKnowledgePoints", "uncoveredKnowledgePoints", "notes" -> BackupV2Json.strings(value)
            "excludedSections" -> BackupV2Json.array(value).forEach { item ->
                val excluded = BackupV2Json.obj(item, setOf("sectionId", "reason")); sections += BackupV2Json.string(excluded["sectionId"]); BackupV2Json.string(excluded["reason"])
            }
            "coveredRatio", "knowledgePointCoverage" -> number(value, 0.0, 1.0)
            "truncated", "sufficient" -> BackupV2Json.bool(value)
            "personalReview" -> personalReview(value, root["sourceRevision"]?.let(BackupV2Json::string))
            "supportReview" -> {
                val review = BackupV2Json.obj(value, setOf("version", "state", "draftPath", "draftSha256", "resolvedToPaperId"), setOf("version", "state", "draftPath", "draftSha256"))
                v2require(BackupV2Json.integer(review["version"]) == 1L, "UNSUPPORTED_JSON_VERSION", "supportReview version")
                v2require(BackupV2Json.string(review["state"]) in setOf("REVIEW_REQUIRED", "PUBLISHED", "DISCARDED", "RESOLVED"), detail = "unknown supportReview state")
                v2require(BackupV2Json.string(review["draftPath"]).isNotBlank(), detail = "empty draftPath")
                hash(BackupV2Json.string(review["draftSha256"])); review["resolvedToPaperId"]?.let(BackupV2Json::string)
            }
            else -> BackupV2Json.string(value)
        } }
        return Coverage(root, sections, root.keys.intersect(setOf("supportReview", "personalReview")))
    }
    /** Local review receipts are recognized for export projection only, never portable authority. */
    private fun personalReview(value: JsonElement, sourceRevision: String?) {
        val root = BackupV2Json.obj(value, setOf("sourcePaperId", "draftHash", "trust", "assessments"))
        fun text(root: JsonObject, key: String): String = BackupV2Json.string(root[key]).also {
            v2require(it.isNotBlank(), detail = "empty personalReview $key")
        }
        fun hashes(value: JsonElement?, unique: Boolean = false): List<String> = BackupV2Json.strings(value).map { it.lowercase(java.util.Locale.ROOT) }.also { values ->
            v2require(values.isNotEmpty() && (!unique || values.distinct().size == values.size), detail = "empty or duplicate review evidence")
            values.forEach(::hash)
        }
        text(root, "sourcePaperId")
        val draftHash = text(root, "draftHash"); hash(draftHash)
        v2require(text(root, "trust") == "USER_VERIFIED", detail = "unknown personal review trust")
        val assessments = BackupV2Json.array(root["assessments"])
        v2require(assessments.isNotEmpty(), detail = "empty personal review assessments")
        assessments.forEach { value ->
            val required = setOf("schemaVersion", "itemDraftHash", "sourceRevision", "state", "reasonCodes",
                "authority", "evidenceHashes", "assertionChecks")
            val a = BackupV2Json.obj(value, required + setOf("approval", "factKey", "factReviewSha256"), required)
            v2require(BackupV2Json.integer(a["schemaVersion"]) == 1L, "UNSUPPORTED_JSON_VERSION", "personal assessment version")
            val itemHash = text(a, "itemDraftHash"); hash(itemHash)
            val revision = text(a, "sourceRevision")
            v2require(sourceRevision == null || revision == sourceRevision, detail = "personal review source revision differs")
            val state = text(a, "state")
            val authority = text(a, "authority")
            v2require(state in setOf("SUPPORTED", "UNSUPPORTED", "UNCERTAIN"), detail = "unknown assessment state")
            BackupV2Json.strings(a["reasonCodes"])
            val evidence = hashes(a["evidenceHashes"], unique = authority == "USER_REVIEW")
            a["factKey"]?.let(BackupV2Json::string)
            a["factReviewSha256"]?.let { hash(BackupV2Json.string(it).lowercase(java.util.Locale.ROOT)) }
            val checks = BackupV2Json.array(a["assertionChecks"]).map { check ->
                val c = BackupV2Json.obj(check, setOf("assertionKey", "role", "claimText", "evidenceHashes", "judgement", "reason"))
                val key = BackupV2Json.string(c["assertionKey"])
                val claim = BackupV2Json.string(c["claimText"])
                BackupV2Json.string(c["reason"]); BackupV2Json.string(c["role"])
                val claimEvidence = BackupV2Json.strings(c["evidenceHashes"]).map { it.lowercase(java.util.Locale.ROOT) }
                if (authority == "USER_REVIEW") v2require(key.isNotBlank() && claim.isNotBlank(), detail = "blank personal assertion")
                if (authority == "USER_REVIEW") v2require(claimEvidence.toSet() == evidence.toSet(), detail = "review claim evidence differs")
                v2require(text(c, "judgement") == if (authority == "USER_REVIEW") "USER_CONFIRMED" else "SUPPORTED",
                    detail = "review claim judgement differs")
                key
            }
            v2require(checks.isNotEmpty() && (authority != "USER_REVIEW" || checks.distinct().size == checks.size), detail = "empty or duplicate review assertions")
            if (authority == "USER_REVIEW") {
                v2require(state == "UNCERTAIN", detail = "personal confirmation must remain uncertain")
                val approval = BackupV2Json.obj(a["approval"] ?: throw BackupV2Exception("INVALID_JSON_SHAPE", "personal approval required"), setOf("reviewerUserId", "reviewedAtUtc", "draftHash",
                    "evidenceHashes", "checkedAssertions", "decision"))
                // This private receipt uses the producer's numeric Long, not a portable SQL Long string.
                val owner = approval["reviewerUserId"] as? JsonPrimitive
                v2require(owner != null && !owner.isString && owner.content.matches(Regex("[1-9][0-9]*")) &&
                    owner.longOrNull?.let { it > 0 } == true, detail = "invalid private reviewer Long")
                val time = text(approval, "reviewedAtUtc")
                v2require(com.example.myapplication.data.reader.content.Api24UtcTimestamp.isValid(time),
                    detail = "invalid personal review UTC timestamp")
                v2require(text(approval, "draftHash") == draftHash, detail = "approval draft differs")
                v2require(hashes(approval["evidenceHashes"], unique = true).toSet() == evidence.toSet(), detail = "approval evidence differs")
                val checked = BackupV2Json.strings(approval["checkedAssertions"])
                v2require(checked.distinct().size == checked.size && checked.toSet() == checks.toSet(),
                    detail = "approval assertions differ")
                v2require(text(approval, "decision") == "ACCEPT_FOR_PERSONAL_PRACTICE", detail = "unknown personal decision")
            } else {
                v2require(authority == "AUDITED_TEMPLATE" && state == "SUPPORTED" && !a.containsKey("approval") &&
                    a.containsKey("factReviewSha256"),
                    detail = "personal review contains unsupported authority")
            }
        }
    }

    fun project(records: BackupV2Records): BackupV2Projection {
        val changes = arrayListOf<BackupV2ProjectionChange>()
        val tables = records.tables.mapValues { (_, rows) -> rows.map { row ->
            if (row.table != BackupV2Table.QUIZ_PAPERS) row else {
                val fields = row.fields.toMutableMap()
                val scope = scope(row.text("scope_json")!!)
                if (scope.privateFields.isNotEmpty()) {
                    fields["scope_json"] = BackupV2Value.Text(ContentPackageCodec.canonicalJson(JsonObject(scope.root.filterKeys { it !in scopePrivate })))
                    changes += BackupV2ProjectionChange(row.table, row.portableKey, "scope_json", Collections.unmodifiableList(scope.privateFields.sorted()))
                }
                val coverage = coverage(row.text("generation_coverage_json")!!)
                if (coverage.privateFields.isNotEmpty()) {
                    fields["generation_coverage_json"] = BackupV2Value.Text(ContentPackageCodec.canonicalJson(JsonObject(coverage.root.filterKeys { it !in coverage.privateFields })))
                    changes += BackupV2ProjectionChange(row.table, row.portableKey, "generation_coverage_json", Collections.unmodifiableList(coverage.privateFields.sorted()))
                }
                BackupV2Record(row.table, fields)
            }
        } }
        return BackupV2Projection(BackupV2Records(tables), changes)
    }
    internal fun validateColumn(table: BackupV2Table, column: String, text: String) {
        try {
            when {
                column in setOf("locator_json", "source_locator_json") -> locator(text)
                column == "scope_json" -> scope(text)
                column == "generation_coverage_json" -> coverage(text)
                column == "settings_json" -> settings(text)
                column == "style_json" -> style(text)
                column == "bibliographic_json" -> {
                    val root = BackupV2Json.obj(BackupV2Json.parseText(text), setOf("title", "extractorVersion"), setOf("title")); root.values.forEach(BackupV2Json::string)
                }
                column == "question_snapshot_json" -> snapshot(text)
                column == "rubric_json" -> rubric(BackupV2Json.parseText(text)).also { QuizCodecs.decodeRubric(text) }
                column == "evidence_json" -> evidence(text)
                column == "matched_rubric_json" -> matched(text)
                column in setOf("old_assessment_json", "new_assessment_json") -> assessment(text)
                column == "raw_answer_json" -> rawAnswer(text)
                table == BackupV2Table.QUESTIONS && column == "options" -> options(text)
                table == BackupV2Table.QUESTIONS && column == "synonyms" -> synonyms(text)
            }
        } catch (e: BackupV2Exception) { throw e }
        catch (e: IllegalArgumentException) { throw BackupV2Exception("INVALID_JSON_COLUMN", "${table.wireName}.$column", e) }
    }
    private fun locator(text: String): ReaderLocator {
        val root = BackupV2Json.parseText(text)
        return ReaderLocatorCodec.decode(root.toString())
    }
    private fun settings(text: String) {
        val allowed = setOf("schemaVersion", "fontScale", "lineHeight", "theme", "fontColor", "marginDp", "flowMode", "edgeTap", "immersivePreference", "pageIndicator")
        val root = BackupV2Json.obj(BackupV2Json.parseText(text), allowed, emptySet()); version(root)
        root.forEach { (key, value) -> when (key) {
            "schemaVersion" -> Unit
            "fontScale" -> number(value, 0.8, 1.8)
            "lineHeight" -> number(value, 1.2, 2.2)
            "theme" -> v2require(BackupV2Json.string(value) in setOf("LIGHT", "SEPIA", "NIGHT"), detail = "unknown reader theme")
            "fontColor" -> v2require(BackupV2Json.string(value).matches(Regex("AUTO|#[0-9a-fA-F]{6}")), detail = "invalid fontColor")
            "marginDp" -> v2require(BackupV2Json.integer(value) in 0..64, detail = "invalid marginDp")
            "flowMode" -> v2require(BackupV2Json.string(value) in setOf("PAGINATED", "SCROLL"), detail = "unknown flowMode")
            else -> BackupV2Json.bool(value)
        } }
    }
    private fun style(text: String) {
        val root = BackupV2Json.obj(BackupV2Json.parseText(text), setOf("color", "underline"))
        v2require(BackupV2Json.string(root["color"]) in setOf("YELLOW", "GREEN", "BLUE", "PINK", "ORANGE"), detail = "unknown annotation color")
        BackupV2Json.bool(root["underline"])
    }
    private fun number(value: JsonElement, min: Double, max: Double) {
        val primitive = value as? JsonPrimitive
        v2require(primitive != null && !primitive.isString, "INVALID_JSON_SHAPE", "number required")
        val number = primitive!!.content.toDoubleOrNull()
        v2require(number != null && number.isFinite() && number in min..max, detail = "number outside supported range")
    }
    private fun rubric(element: JsonElement) {
        BackupV2Json.array(element).forEach { point ->
            val root = BackupV2Json.obj(point, setOf("key", "weight", "accepted", "negatedBy"), setOf("key", "weight", "accepted"))
            v2require(BackupV2Json.string(root["key"]).isNotBlank(), detail = "empty rubric key")
            v2require(BackupV2Json.integer(root["weight"]) in 1..Int.MAX_VALUE.toLong(), detail = "invalid rubric weight")
            BackupV2Json.strings(root["accepted"]); root["negatedBy"]?.let(BackupV2Json::strings)
        }
    }
    private fun snapshot(text: String) {
        val keys = setOf("schemaVersion", "ordinal", "type", "stem", "options", "answer", "score", "analysis", "knowledgePoint", "difficulty", "rubric", "evidenceIds", "templateVersion", "itemId")
        val root = BackupV2Json.obj(BackupV2Json.parseText(text), keys, keys - setOf("schemaVersion", "options", "itemId")); version(root)
        v2require(BackupV2Json.integer(root["ordinal"]) in 1..Int.MAX_VALUE.toLong(), detail = "invalid item ordinal")
        v2require(BackupV2Json.integer(root["score"]) in 1..Int.MAX_VALUE.toLong(), detail = "invalid item score")
        v2require(BackupV2Json.string(root["type"]) in setOf("CHOICE", "ESSAY"), detail = "unknown quiz item type")
        v2require(BackupV2Json.string(root["difficulty"]) in setOf("BASIC", "UNDERSTAND", "APPLY"), detail = "unknown difficulty")
        listOf("stem", "analysis", "knowledgePoint", "templateVersion").forEach { BackupV2Json.string(root[it]) }
        root["itemId"]?.let(BackupV2Json::string); root["options"]?.let(BackupV2Json::strings)
        val answer = root.getValue("answer"); if (answer is JsonArray) BackupV2Json.strings(answer) else BackupV2Json.string(answer)
        rubric(root.getValue("rubric")); BackupV2Json.strings(root["evidenceIds"])
        QuizCodecs.decodeItemSnapshot(text)
    }
    private fun evidence(text: String) {
        BackupV2Json.array(BackupV2Json.parseText(text)).forEach { value ->
            val root = BackupV2Json.obj(value, setOf("evidenceId", "documentId", "sectionId", "contentRevision", "locator", "quote", "quoteSha256", "sourceTitleSnapshot"))
            root.filterKeys { it != "locator" }.values.forEach(BackupV2Json::string)
            val loc = locator(root.getValue("locator").toString())
            v2require(loc.documentId == BackupV2Json.string(root["documentId"]) && loc.sectionId == BackupV2Json.string(root["sectionId"]) && loc.contentRevision == BackupV2Json.string(root["contentRevision"]), "ORPHAN_FK", "evidence locator identity mismatch")
            val quote = BackupV2Json.string(root["quote"])
            v2require(BackupV2Json.string(root["quoteSha256"]) == ContentPackageCodec.sha256(quote.toByteArray()), "HASH_MISMATCH", "evidence quote hash")
        }
        QuizCodecs.decodeEvidence(text)
    }
    private fun matched(text: String) {
        val raw = BackupV2Json.parseText(text)
        if (raw is JsonArray) { v2require(raw.isEmpty(), detail = "only legacy empty matched_rubric array supported"); return }
        val root = BackupV2Json.obj(raw, setOf("matched", "missed", "conflicted", "status", "rubricVersion"))
        listOf("matched", "missed", "conflicted").forEach { BackupV2Json.strings(root[it]) }
        v2require(BackupV2Json.string(root["status"]) in setOf("GRADED", "PENDING_SELF_REVIEW", "UNANSWERED"), detail = "unknown rubric grading status")
        BackupV2Json.string(root["rubricVersion"])
    }
    private fun assessment(text: String) {
        val root = BackupV2Json.obj(BackupV2Json.parseText(text), setOf("score", "isCorrect", "gradingMethod", "note", "reason"))
        root["score"]?.takeUnless { it === JsonNull }?.let { v2require(BackupV2Json.integer(it) >= 0, detail = "negative assessment score") }
        root["isCorrect"]?.takeUnless { it === JsonNull }?.let(BackupV2Json::bool)
        listOf("gradingMethod", "note", "reason").forEach { BackupV2Json.string(root[it]) }
    }
    private fun rawAnswer(text: String) {
        val raw = BackupV2Json.parseText(text)
        if (raw is JsonArray) { BackupV2Json.strings(raw); return } // frozen submitted legacy answer, preserve bytes
        val root = BackupV2Json.obj(raw, setOf("selectedOptionId", "text"), emptySet())
        v2require(root.size == 1, detail = "raw answer requires one known field")
        root.values.forEach(BackupV2Json::string)
    }
    private fun synonyms(text: String) {
        val raw = BackupV2Json.parseText(text)
        if (raw is JsonArray) {
            raw.forEach { value -> if (value is JsonArray) BackupV2Json.strings(value) else BackupV2Json.string(value) }
        } else {
            val root = BackupV2Json.obj(raw, setOf("format", "schemaVersion", "score", "answerSha256", "rubric"))
            v2require(root["format"] == JsonPrimitive("content-package-rubric"), "UNSUPPORTED_JSON_VERSION", "unregistered synonyms envelope")
            version(root); v2require(BackupV2Json.integer(root["score"]) in 1..1000, detail = "course rubric score")
            hash(BackupV2Json.string(root["answerSha256"])); rubric(root.getValue("rubric"))
            // Whole-row typed check below binds this envelope to the immutable answer and score.
        }
    }
    private fun options(text: String) {
        val raw = BackupV2Json.parseText(text)
        if (raw is JsonObject) {
            v2require(raw.keys.all { it in setOf("A", "B", "C", "D") }, "INVALID_JSON_SHAPE", "unknown ordinary option key")
            raw.values.forEach(BackupV2Json::string)
        } else BackupV2Json.array(raw).forEach { value ->
            if (value is JsonObject) {
                val root = BackupV2Json.obj(value, setOf("key", "id", "letter", "text", "label"), emptySet())
                v2require(root.keys.count { it in setOf("key", "id", "letter") } <= 1 && root.keys.count { it in setOf("text", "label") } == 1, "INVALID_JSON_SHAPE", "ambiguous option identity/text")
                root.values.forEach(BackupV2Json::string)
            } else BackupV2Json.string(value)
        }
    }
    internal fun hash(value: String) { v2require(value.matches(Regex("[0-9a-f]{64}")), "INVALID_HASH", "lowercase SHA256 required") }
    internal fun locators(row: BackupV2Record): List<ReaderLocator> {
        val direct = row.fields.filterKeys { it in setOf("locator_json", "source_locator_json") }.values
            .filterIsInstance<BackupV2Value.Text>().map { locator(it.value) }
        return direct + if (row.table == BackupV2Table.QUIZ_ITEMS) QuizCodecs.decodeEvidence(row.text("evidence_json")!!).map { it.locator } else emptyList()
    }
    internal fun scopeIdentity(row: BackupV2Record): Pair<String?, List<String>> {
        val s = scope(row.text("scope_json")!!); return s.documentId to s.sections
    }
    internal fun coverageSections(row: BackupV2Record): List<String> = coverage(row.text("generation_coverage_json")!!).sections
    internal fun remapColumn(table: BackupV2Table, column: String, text: String, map: BackupV2IdMap): String = when (column) {
        "locator_json", "source_locator_json" -> ReaderLocatorCodec.encode(remapLocator(locator(text), map))
        "question_snapshot_json" -> {
            snapshot(text); val item = QuizCodecs.decodeItemSnapshot(text)
            val root = BackupV2Json.parseText(text).jsonObject
            if (item.itemId == null) text else ContentPackageCodec.canonicalJson(JsonObject(root + ("itemId" to JsonPrimitive(map.table(BackupV2Table.QUIZ_ITEMS, item.itemId)))))
        }
        "evidence_json" -> {
            evidence(text)
            val refs = QuizCodecs.decodeEvidence(text).map { it.copy(documentId = map.document(it.documentId), sectionId = map.section(it.sectionId), locator = remapLocator(it.locator, map)) }
            QuizCodecs.encodeEvidence(refs)
        }
        "scope_json" -> {
            val s = scope(text); v2require(s.privateFields.isEmpty(), "PRIVATE_JSON_NOT_PORTABLE", "project scope before remapping")
            val fields = s.root.toMutableMap()
            s.documentId?.let { fields["documentId"] = JsonPrimitive(map.document(it)) }
            s.root["sectionId"]?.let { fields["sectionId"] = JsonPrimitive(map.section(BackupV2Json.string(it))) }
            s.root["sectionIds"]?.let { fields["sectionIds"] = JsonArray(BackupV2Json.strings(it).map { id -> JsonPrimitive(map.section(id)) }) }
            ContentPackageCodec.canonicalJson(JsonObject(fields))
        }
        "generation_coverage_json" -> {
            val c = coverage(text); v2require(c.privateFields.isEmpty(), "PRIVATE_JSON_NOT_PORTABLE", "project coverage before remapping")
            val fields = c.root.toMutableMap()
            listOf("coveredSections", "uncoveredSections").forEach { key -> c.root[key]?.let { fields[key] = JsonArray(BackupV2Json.strings(it).map { id -> JsonPrimitive(map.section(id)) }) } }
            c.root["excludedSections"]?.let { fields["excludedSections"] = JsonArray(BackupV2Json.array(it).map { value ->
                val excluded = value.jsonObject; JsonObject(excluded + ("sectionId" to JsonPrimitive(map.section(BackupV2Json.string(excluded["sectionId"])))))
            }) }
            ContentPackageCodec.canonicalJson(JsonObject(fields))
        }
        else -> text
    }
    private fun remapLocator(locator: ReaderLocator, map: BackupV2IdMap): ReaderLocator {
        val document = map.document(locator.documentId); val section = map.section(locator.sectionId)
        return when (locator) {
            is ReaderLocator.Reflow -> locator.copy(documentId = document, sectionId = section)
            is ReaderLocator.Fixed -> locator.copy(documentId = document, sectionId = section, rects = locator.rects.toList())
            is ReaderLocator.Note -> locator.copy(documentId = document, sectionId = section, notebookId = map.table(BackupV2Table.READER_NOTEBOOKS, locator.notebookId))
        }
    }
}
