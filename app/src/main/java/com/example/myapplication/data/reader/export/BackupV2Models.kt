package com.example.myapplication.data.reader.export

import java.util.Collections
import kotlinx.serialization.json.*

object BackupV2Limits {
    const val MANIFEST_BYTES = 1024 * 1024
    const val JSON_BYTES = 16 * 1024 * 1024
    const val FILE_BYTES = 32 * 1024 * 1024
    const val TOTAL_BYTES = 256L * 1024 * 1024
    const val DOCUMENTS = 200
    const val FILES = 10_000
    const val RECORDS = 100_000
    const val DEPTH = 64
    const val SAFE_INTEGER = 9_007_199_254_740_991L
}

class BackupV2Exception(val code: String, detail: String, cause: Throwable? = null) :
    IllegalArgumentException("$code: $detail", cause)

internal fun v2require(condition: Boolean, code: String = "INVALID_RECORDS", detail: String) {
    if (!condition) throw BackupV2Exception(code, detail)
}

/** Fixed portable whitelist. This is not a database-table dump API. */
enum class BackupV2Table(val wireName: String, primaryColumns: List<String>) {
    QUESTIONS("questions", listOf("id")), TEST_RECORDS("test_records", listOf("id")),
    TEST_ANSWERS("test_answers", listOf("id")), FAVORITES("favorites", listOf("id")),
    STUDY_PROGRESS("study_progress", listOf("id")), READER_STATES("reader_states", listOf("document_id")),
    READER_ANNOTATIONS("reader_annotations", listOf("id")), READER_BOOKMARKS("reader_bookmarks", listOf("id")),
    READER_SOURCE_REFERENCES("reader_source_references", listOf("id")), READER_NOTEBOOKS("reader_notebooks", listOf("id")),
    READER_NOTE_VERSIONS("reader_note_versions", listOf("id")), QUIZ_PAPERS("quiz_papers", listOf("id")),
    QUIZ_ITEMS("quiz_items", listOf("id")), QUIZ_ATTEMPTS("quiz_attempts", listOf("id")),
    QUIZ_ATTEMPT_ANSWERS("quiz_attempt_answers", listOf("attempt_id", "item_id")),
    QUIZ_GRADE_EVENTS("quiz_grade_events", listOf("id")), READER_ANNOTATION_TARGETS("reader_annotation_targets", listOf("id"));
    val primaryColumns: List<String> = Collections.unmodifiableList(ArrayList(primaryColumns))
}

sealed interface BackupV2Value {
    data class Text(val value: String) : BackupV2Value
    data class Integer(val value: Long) : BackupV2Value
    data object Null : BackupV2Value
}

/** Immutable, affinity-checked row; SQL Long identities remain Text throughout the wire boundary. */
class BackupV2Record(val table: BackupV2Table, fields: Map<String, BackupV2Value>) {
    val fields: Map<String, BackupV2Value> = Collections.unmodifiableMap(LinkedHashMap(fields))
    init { BackupV2RecordRules.checkRow(this) }
    fun text(column: String): String? = when (val value = fields.getValue(column)) {
        is BackupV2Value.Text -> value.value
        BackupV2Value.Null -> null
        else -> throw BackupV2Exception("TYPE_MISMATCH", "$column is not TEXT")
    }
    fun integer(column: String): Long? = when (val value = fields.getValue(column)) {
        is BackupV2Value.Integer -> value.value
        BackupV2Value.Null -> null
        else -> throw BackupV2Exception("TYPE_MISMATCH", "$column is not INTEGER")
    }
    val portableKey: String get() = JsonArray(table.primaryColumns.map { JsonPrimitive(text(it)!!) }).toString()
    internal fun json(): JsonObject = JsonObject(fields.mapValues { (_, value) -> when (value) {
        is BackupV2Value.Text -> JsonPrimitive(value.value)
        is BackupV2Value.Integer -> JsonPrimitive(value.value)
        BackupV2Value.Null -> JsonNull
    } })
}

class BackupV2Records(tables: Map<BackupV2Table, List<BackupV2Record>>) {
    val tables: Map<BackupV2Table, List<BackupV2Record>>
    val recordCount: Int
    init {
        v2require(tables.keys == BackupV2Table.entries.toSet(), detail = "all 17 arrays are required")
        var count = 0L
        tables.forEach { (table, rows) ->
            count += rows.size.toLong()
            v2require(count <= BackupV2Limits.RECORDS, "LIMIT_EXCEEDED", "record count exceeds 100000")
            v2require(rows.all { it.table == table }, detail = "row belongs to wrong table")
        }
        this.tables = Collections.unmodifiableMap(tables.mapValues { (_, rows) ->
            Collections.unmodifiableList(ArrayList(rows))
        })
        recordCount = count.toInt()
        BackupV2RecordRules.checkRelations(this)
    }
    operator fun get(table: BackupV2Table): List<BackupV2Record> = tables.getValue(table)
    internal fun json() = buildJsonObject {
        put("schemaVersion", 2)
        put("tables", JsonObject(BackupV2Table.entries.associate { it.wireName to JsonArray(get(it).map(BackupV2Record::json)) }))
    }
}

data class BackupV2Options(val includeSources: Boolean)
data class BackupV2File(val path: String, val sha256: String, val bytes: Long, val kind: String)
data class BackupV2CourseChapterRef(val exportChapterId: String, val chapterKey: String, val bookKey: String, val contentRevision: String)

class BackupV2Document(
    val exportDocumentId: String, val kind: String, val title: String, val format: String,
    val sourceSha256: String?, val contentRevision: String, val chapterKey: String?, val sourcePath: String?,
    snapshotPaths: List<String>, sectionIds: List<String>,
) {
    init { v2require(snapshotPaths.size <= BackupV2Limits.FILES && sectionIds.size <= BackupV2Limits.RECORDS, "LIMIT_EXCEEDED", "document identity/resource count") }
    val snapshotPaths: List<String> = Collections.unmodifiableList(ArrayList(snapshotPaths))
    val sectionIds: List<String> = Collections.unmodifiableList(ArrayList(sectionIds))
}

class BackupV2Manifest(
    val exportId: String, val createdAtUtc: String, val appVersion: String, val testOnly: Boolean,
    val contentHash: String, val options: BackupV2Options, documents: List<BackupV2Document>,
    files: List<BackupV2File>, courseChapterRefs: List<BackupV2CourseChapterRef>,
) {
    init { v2require(documents.size <= BackupV2Limits.DOCUMENTS && files.size <= BackupV2Limits.FILES && courseChapterRefs.size <= BackupV2Limits.RECORDS, "LIMIT_EXCEEDED", "manifest collection count") }
    val documents: List<BackupV2Document> = Collections.unmodifiableList(ArrayList(documents))
    val files: List<BackupV2File> = Collections.unmodifiableList(ArrayList(files))
    val courseChapterRefs: List<BackupV2CourseChapterRef> = Collections.unmodifiableList(ArrayList(courseChapterRefs))
    val schemaVersion: Int get() = 2
    val format: String get() = "learning-state-backup"
    fun toJson(): JsonObject = buildJsonObject {
        put("format", format); put("schemaVersion", 2); put("exportId", exportId); put("createdAtUtc", createdAtUtc)
        put("appVersion", appVersion); put("testOnly", testOnly); put("contentHash", contentHash)
        put("options", buildJsonObject { put("includeSources", options.includeSources); put("includeChatHistory", false); put("includeAiProfiles", false) })
        put("documents", JsonArray(documents.map { d -> buildJsonObject {
            put("exportDocumentId", d.exportDocumentId); put("kind", d.kind); put("title", d.title); put("format", d.format)
            put("sourceSha256", d.sourceSha256?.let(::JsonPrimitive) ?: JsonNull); put("contentRevision", d.contentRevision)
            put("chapterKey", d.chapterKey?.let(::JsonPrimitive) ?: JsonNull); put("sourcePath", d.sourcePath?.let(::JsonPrimitive) ?: JsonNull)
            put("snapshotPaths", JsonArray(d.snapshotPaths.map(::JsonPrimitive))); put("sectionIds", JsonArray(d.sectionIds.map(::JsonPrimitive)))
        } }))
        put("files", JsonArray(files.map { f -> buildJsonObject { put("path", f.path); put("sha256", f.sha256); put("bytes", f.bytes); put("kind", f.kind) } }))
        put("recordsPath", "records.json")
        put("courseChapterRefs", JsonArray(courseChapterRefs.map { r -> buildJsonObject {
            put("exportChapterId", r.exportChapterId); put("chapterKey", r.chapterKey); put("bookKey", r.bookKey); put("contentRevision", r.contentRevision)
        } }))
    }
}

class BackupV2Package(val manifest: BackupV2Manifest, val records: BackupV2Records)

class BackupV2Encoded(manifestBytes: ByteArray, recordsBytes: ByteArray, changes: List<BackupV2ProjectionChange>) {
    private val manifestSnapshot = manifestBytes.copyOf()
    private val recordsSnapshot = recordsBytes.copyOf()
    val manifestBytes: ByteArray get() = manifestSnapshot.copyOf()
    val recordsBytes: ByteArray get() = recordsSnapshot.copyOf()
    val projectionChanges: List<BackupV2ProjectionChange> = Collections.unmodifiableList(ArrayList(changes))
}

/** Course mappings carry the exported dictionary identity; a coincident Long is never a course match. */
class BackupV2IdMap(
    tables: Map<BackupV2Table, Map<String, String>> = emptyMap(), documents: Map<String, String> = emptyMap(),
    sections: Map<String, String> = emptyMap(), courseChapters: Map<BackupV2CourseChapterRef, String> = emptyMap(),
) {
    val tables: Map<BackupV2Table, Map<String, String>> = Collections.unmodifiableMap(tables.mapValues { (table, mapping) ->
        mapping.forEach { (from, to) ->
            v2require(from.isNotBlank() && to.isNotBlank(), "INVALID_ID_MAP", "empty $table identity")
            if (table in BackupV2RecordRules.longTables) { BackupV2RecordRules.longId(from); BackupV2RecordRules.longId(to) }
        }
        v2require(mapping.values.toSet().size == mapping.size, "INVALID_ID_MAP", "non-injective $table mapping")
        Collections.unmodifiableMap(LinkedHashMap(mapping))
    })
    val documents: Map<String, String> = frozenIdentityMap(documents)
    val sections: Map<String, String> = frozenIdentityMap(sections)
    val courseChapters: Map<BackupV2CourseChapterRef, String> = Collections.unmodifiableMap(LinkedHashMap(courseChapters))
    init {
        v2require(courseChapters.keys.map { it.exportChapterId }.toSet().size == courseChapters.size, "INVALID_ID_MAP", "duplicate exported chapter identity")
        courseChapters.forEach { (ref, target) ->
            BackupV2RecordRules.longId(ref.exportChapterId); BackupV2RecordRules.longId(target)
            v2require(ref.bookKey.isNotBlank() && ref.chapterKey.isNotBlank() && ref.contentRevision.isNotBlank(), "INVALID_ID_MAP", "course identity triple required")
        }
    }
    internal fun table(table: BackupV2Table, value: String): String = tables[table]?.get(value)
        ?: throw BackupV2Exception("MISSING_ID_MAPPING", "${table.wireName}:$value")
    internal fun document(value: String): String = documents[value] ?: throw BackupV2Exception("MISSING_ID_MAPPING", "document:$value")
    internal fun section(value: String): String = sections[value] ?: throw BackupV2Exception("MISSING_ID_MAPPING", "section:$value")
    internal fun chapter(value: String): String = courseChapters.entries.singleOrNull { it.key.exportChapterId == value }?.value
        ?: throw BackupV2Exception("MISSING_COURSE_MAPPING", "chapter dictionary identity:$value")
    private fun frozenIdentityMap(values: Map<String, String>): Map<String, String> {
        v2require(values.all { it.key.isNotBlank() && it.value.isNotBlank() } && values.values.toSet().size == values.size, "INVALID_ID_MAP", "invalid identity mapping")
        return Collections.unmodifiableMap(LinkedHashMap(values))
    }
}
