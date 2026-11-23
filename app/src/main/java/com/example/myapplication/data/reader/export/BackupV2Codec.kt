package com.example.myapplication.data.reader.export

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import com.example.myapplication.data.reader.content.ContentPackageCodec
import kotlinx.serialization.json.*

sealed interface LearningStateBackupFormat {
    data class V1(val manifest: BackupManifest) : LearningStateBackupFormat
    data class V2(val backup: BackupV2Package) : LearningStateBackupFormat
}
enum class BackupV2TestOnlyPolicy { FORBID, ALLOW_ISOLATED_TEST }

object BackupV2Codec {
    private val manifestFields = setOf("format", "schemaVersion", "exportId", "createdAtUtc", "appVersion", "testOnly", "contentHash", "options", "documents", "files", "recordsPath", "courseChapterRefs")
    fun dispatch(manifestBytes: ByteArray, recordsBytes: ByteArray? = null): LearningStateBackupFormat {
        val bytes = manifestBytes.copyOf()
        val root = BackupV2Json.parse(bytes, BackupV2Limits.MANIFEST_BYTES) as? JsonObject
            ?: throw BackupV2Exception("INVALID_MANIFEST", "manifest object required")
        val version = BackupV2Json.integer(root["schemaVersion"])
        if (root["format"] == null && version == 1L) {
            v2require(recordsBytes == null, "UNSUPPORTED_MANIFEST_VERSION", "V1 cannot carry V2 records")
            return LearningStateBackupFormat.V1(BackupManifestCodec.decode(bytes.toString(Charsets.UTF_8)))
        }
        v2require(root["format"] == JsonPrimitive("learning-state-backup") && version == 2L, "UNSUPPORTED_MANIFEST_VERSION", "mixed or unsupported version")
        return LearningStateBackupFormat.V2(decode(bytes, recordsBytes ?: throw BackupV2Exception("RECORDS_MISSING", "records.json required")))
    }
    fun decode(manifestBytes: ByteArray, recordsBytes: ByteArray): BackupV2Package {
        v2require(manifestBytes.size <= BackupV2Limits.MANIFEST_BYTES && recordsBytes.size <= BackupV2Limits.JSON_BYTES, "LIMIT_EXCEEDED", "manifest/records byte limit")
        val manifestSnapshot = manifestBytes.copyOf(); val recordsSnapshot = recordsBytes.copyOf()
        val manifest = decodeManifest(manifestSnapshot)
        val descriptor = manifest.files.single { it.path == "records.json" }
        v2require(descriptor.bytes == recordsSnapshot.size.toLong(), "SIZE_MISMATCH", "records.json descriptor size")
        v2require(descriptor.sha256 == ContentPackageCodec.sha256(recordsSnapshot), "HASH_MISMATCH", "records.json bytes")
        val records = LearningStateBackupCodec.decode(recordsSnapshot)
        v2require(BackupV2JsonProjection.project(records).changes.isEmpty(), "PRIVATE_JSON_NOT_PORTABLE", "scope capabilities or supportReview draft references in wire package")
        checkClosure(manifest, records)
        return BackupV2Package(manifest, records)
    }
    /** Encodes a portable copy and reports exact changed JSON columns. Incoming immutable records are untouched. */
    fun encode(backup: BackupV2Package): BackupV2Encoded {
        val projection = BackupV2JsonProjection.project(backup.records)
        val records = LearningStateBackupCodec.encode(projection.records)
        val root = backup.manifest.toJson().toMutableMap()
        root["files"] = JsonArray(backup.manifest.files.map { file -> if (file.path == "records.json")
            file.copy(bytes = records.size.toLong(), sha256 = ContentPackageCodec.sha256(records)) else file
        }.sortedBy { it.path }.map { file -> buildJsonObject { put("path", file.path); put("sha256", file.sha256); put("bytes", file.bytes); put("kind", file.kind) } })
        root["contentHash"] = JsonPrimitive(contentHash(JsonObject(root)))
        checkEncodedSize(JsonObject(root), BackupV2Limits.MANIFEST_BYTES)
        val manifest = ContentPackageCodec.canonicalJson(JsonObject(root)).toByteArray(Charsets.UTF_8)
        decode(manifest, records)
        return BackupV2Encoded(manifest, records, projection.changes)
    }
    /** Callers must explicitly choose their test-only gate; this never writes Room or grants source-readability. */
    fun requireImportAllowed(backup: BackupV2Package, policy: BackupV2TestOnlyPolicy) {
        v2require(!backup.manifest.testOnly || policy == BackupV2TestOnlyPolicy.ALLOW_ISOLATED_TEST, "TEST_ONLY_PACKAGE", "testOnly package cannot be imported into production")
    }
    fun decodeManifest(bytes: ByteArray): BackupV2Manifest {
        v2require(bytes.size <= BackupV2Limits.MANIFEST_BYTES, "LIMIT_EXCEEDED", "manifest byte limit before snapshot allocation")
        val rootRaw = BackupV2Json.parse(bytes.copyOf(), BackupV2Limits.MANIFEST_BYTES)
        val versionRoot = rootRaw as? JsonObject ?: throw BackupV2Exception("INVALID_MANIFEST", "manifest object required")
        v2require(versionRoot["format"] == JsonPrimitive("learning-state-backup") && versionRoot["schemaVersion"] == JsonPrimitive(2), "UNSUPPORTED_MANIFEST_VERSION", "exact V2 discriminator required")
        val root = BackupV2Json.obj(rootRaw, manifestFields)
        val options = BackupV2Json.obj(root.getValue("options"), setOf("includeSources", "includeChatHistory", "includeAiProfiles"))
        v2require(!BackupV2Json.bool(options["includeChatHistory"]) && !BackupV2Json.bool(options["includeAiProfiles"]), "UNSUPPORTED_OPTION", "chat/AI profiles forbidden")
        val includeSources = BackupV2Json.bool(options["includeSources"])
        val docsRaw = BackupV2Json.array(root["documents"]); val filesRaw = BackupV2Json.array(root["files"])
        v2require(docsRaw.size <= BackupV2Limits.DOCUMENTS && filesRaw.size in 1..BackupV2Limits.FILES, "LIMIT_EXCEEDED", "document/file count")
        val documents = docsRaw.map { value ->
            val d = BackupV2Json.obj(value, setOf("exportDocumentId", "kind", "title", "format", "sourceSha256", "contentRevision", "chapterKey", "sourcePath", "snapshotPaths", "sectionIds"))
            fun str(key: String) = nonempty(BackupV2Json.string(d[key]), key)
            fun nullable(key: String) = d[key]?.takeUnless { it === JsonNull }?.let(BackupV2Json::string)
            BackupV2Document(str("exportDocumentId"), str("kind"), str("title"), str("format"), nullable("sourceSha256"), str("contentRevision"), nullable("chapterKey"), nullable("sourcePath"), BackupV2Json.strings(d["snapshotPaths"]), BackupV2Json.strings(d["sectionIds"]))
        }
        var total = 0L
        val files = filesRaw.map { value ->
            val f = BackupV2Json.obj(value, setOf("path", "sha256", "bytes", "kind"))
            val bytesCount = BackupV2Json.integer(f["bytes"])
            v2require(bytesCount in 0..BackupV2Limits.FILE_BYTES.toLong(), "LIMIT_EXCEEDED", "single file > 32 MiB")
            total += bytesCount
            v2require(total <= BackupV2Limits.TOTAL_BYTES, "LIMIT_EXCEEDED", "declared total > 256 MiB")
            BackupV2File(BackupV2Json.string(f["path"]), BackupV2Json.string(f["sha256"]), bytesCount, BackupV2Json.string(f["kind"]))
        }
        val refs = BackupV2Json.array(root["courseChapterRefs"]).map { value ->
            val r = BackupV2Json.obj(value, setOf("exportChapterId", "chapterKey", "bookKey", "contentRevision"))
            BackupV2CourseChapterRef(BackupV2Json.string(r["exportChapterId"]), nonempty(BackupV2Json.string(r["chapterKey"]), "chapterKey"), nonempty(BackupV2Json.string(r["bookKey"]), "bookKey"), nonempty(BackupV2Json.string(r["contentRevision"]), "contentRevision"))
        }
        val manifest = BackupV2Manifest(nonempty(BackupV2Json.string(root["exportId"]), "exportId"), BackupV2Json.string(root["createdAtUtc"]), nonempty(BackupV2Json.string(root["appVersion"]), "appVersion"), BackupV2Json.bool(root["testOnly"]), BackupV2Json.string(root["contentHash"]), BackupV2Options(includeSources), documents, files, refs)
        v2require(root["recordsPath"] == JsonPrimitive("records.json"), "INVALID_MANIFEST", "recordsPath must be records.json")
        checkManifest(manifest)
        v2require(manifest.contentHash == contentHash(root), "HASH_MISMATCH", "manifest contentHash")
        return manifest
    }
    fun contentHash(root: JsonObject): String = ContentPackageCodec.sha256(ContentPackageCodec.canonicalJson(JsonObject(root.filterKeys { it !in setOf("contentHash", "exportId", "createdAtUtc") })).toByteArray(Charsets.UTF_8))
    private fun checkManifest(manifest: BackupV2Manifest) {
        BackupV2JsonProjection.hash(manifest.contentHash); utc(manifest.createdAtUtc)
        val files = manifest.files
        v2require(files.map { it.path } == files.map { it.path }.sorted(), "INVALID_MANIFEST", "files must be sorted by path")
        val paths = files.map { it.path.lowercase(Locale.ROOT) }
        v2require(paths.toSet().size == paths.size, "DUPLICATE_PATH", "duplicate/case-colliding file")
        files.forEach { f ->
            v2require(safePath(f.path) && f.path != "manifest.json", "INVALID_PATH", f.path)
            BackupV2JsonProjection.hash(f.sha256)
            v2require(f.kind in setOf("RECORDS", "SOURCE", "SNAPSHOT", "NOTE", "INK"), "INVALID_MANIFEST", "unknown file kind")
            v2require((f.path == "records.json") == (f.kind == "RECORDS"), "INVALID_MANIFEST", "records descriptor mismatch")
            if (!manifest.options.includeSources) v2require(f.kind !in setOf("SOURCE", "SNAPSHOT"), "SOURCE_NOT_SELECTED", "source resource without opt-in")
        }
        v2require(files.count { it.path == "records.json" && it.kind == "RECORDS" } == 1, "RECORDS_MISSING", "one records descriptor required")
        val documents = manifest.documents
        v2require(documents.map { it.exportDocumentId }.toSet().size == documents.size, "DUPLICATE_ID", "documents")
        val sections = documents.flatMap { it.sectionIds }
        v2require(sections.all { it.isNotBlank() } && sections.toSet().size == sections.size, "DUPLICATE_ID", "sectionIds")
        val byPath = files.associateBy { it.path }
        documents.forEach { d ->
            v2require(d.kind in setOf("BUILTIN_REFERENCE", "IMPORTED", "NOTE"), "INVALID_MANIFEST", "document kind")
            d.sourceSha256?.let(BackupV2JsonProjection::hash)
            d.chapterKey?.let { v2require(it.matches(Regex("[a-z][a-z0-9-]*(?::[a-z0-9-]+)*")), "INVALID_MANIFEST", "chapterKey") }
            if (!manifest.options.includeSources) v2require(d.sourcePath == null && d.snapshotPaths.isEmpty(), "SOURCE_NOT_SELECTED", "sourcePath/snapshotPaths must be absent")
            else {
                val source = d.sourcePath?.let { path -> v2require(safePath(path), "INVALID_PATH", path); byPath[path] }
                v2require(source != null && source.kind == "SOURCE" && source.bytes > 0 && d.sourceSha256 == source.sha256 && d.snapshotPaths.isNotEmpty(), "SOURCE_STRUCTURE_INCOMPLETE", "fixed revision requires original source and snapshot resources")
                v2require(d.snapshotPaths.toSet().size == d.snapshotPaths.size && d.snapshotPaths.all { safePath(it) && byPath[it]?.kind == "SNAPSHOT" && byPath[it]!!.bytes > 0 }, "SOURCE_STRUCTURE_INCOMPLETE", "missing snapshot resource")
            }
        }
        val refs = manifest.courseChapterRefs
        refs.forEach { BackupV2RecordRules.longId(it.exportChapterId) }
        v2require(refs.map { it.exportChapterId }.toSet().size == refs.size && refs.map { Triple(it.bookKey, it.chapterKey, it.contentRevision) }.toSet().size == refs.size, "DUPLICATE_ID", "course dictionary")
    }
    internal fun safePath(path: String): Boolean = path.length in 1..1024 && path.matches(Regex("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")) && path.split('/').none { part ->
        part in setOf(".", "..") || part.endsWith('.') || part.substringBefore('.').uppercase(Locale.ROOT).matches(Regex("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))
    }
    private fun checkClosure(manifest: BackupV2Manifest, records: BackupV2Records) {
        val documents = manifest.documents.associateBy { it.exportDocumentId }
        val sections = manifest.documents.flatMap { d -> d.sectionIds.map { it to d.exportDocumentId } }.toMap()
        val courses = manifest.courseChapterRefs.map { it.exportChapterId }.toSet()
        val paths = manifest.files.associateBy { it.path }
        fun doc(id: String) = v2require(id in documents, "ORPHAN_FK", "document:$id")
        fun section(id: String, owner: String?) = v2require(id in sections && (owner == null || sections[id] == owner), "ORPHAN_FK", "section:$id")
        records.tables.values.forEach { rows -> rows.forEach { row ->
            val owner = listOf("document_id", "source_document_id").filter { it in row.fields }.mapNotNull { row.text(it) }.singleOrNull()
            owner?.let(::doc)
            if ("section_id" in row.fields) row.text("section_id")?.let { section(it, owner) }
            if ("chapter_id" in row.fields) row.text("chapter_id")?.let { v2require(it in courses, "ORPHAN_FK", "course chapter absent from dictionary") }
            BackupV2JsonProjection.locators(row).forEach { locator ->
                doc(locator.documentId); section(locator.sectionId, locator.documentId)
                if (owner != null) v2require(owner == locator.documentId, "ORPHAN_FK", "locator outside row document")
            }
            if (row.table == BackupV2Table.QUIZ_PAPERS) {
                val scope = BackupV2JsonProjection.scopeIdentity(row); scope.first?.let(::doc)
                scope.second.forEach { section(it, scope.first) }
                v2require(owner == null || scope.first == null || owner == scope.first, "ORPHAN_FK", "paper scope document mismatch")
                BackupV2JsonProjection.coverageSections(row).forEach { section(it, owner ?: scope.first) }
            }
            if (row.table == BackupV2Table.READER_ANNOTATIONS) row.text("ink_blob_path")?.let { path -> v2require(safePath(path) && paths[path]?.kind == "INK", "ORPHAN_FK", "ink resource") }
            if (row.table == BackupV2Table.READER_SOURCE_REFERENCES) v2require(row.text("quote_sha256") == ContentPackageCodec.sha256(row.text("quote_snapshot")!!.toByteArray(Charsets.UTF_8)), "HASH_MISMATCH", "source quote hash")
        } }
    }
    private fun nonempty(value: String, field: String): String { v2require(value.isNotBlank(), "INVALID_MANIFEST", "$field is empty"); return value }
    private fun utc(value: String) {
        v2require(value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{3})?Z")), "INVALID_TIME", "UTC timestamp required")
        val pattern = if (value.contains('.')) "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" else "yyyy-MM-dd'T'HH:mm:ss'Z'"
        val parser = SimpleDateFormat(pattern, Locale.ROOT).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
        val position = ParsePosition(0); val date = parser.parse(value, position)
        v2require(date != null && position.index == value.length && date.time in 0..BackupV2Limits.SAFE_INTEGER, "INVALID_TIME", "invalid UTC time")
    }
    /** Count encoded bytes before building a joined JSON string. */
    internal fun checkEncodedSize(root: JsonElement, limit: Int) {
        var bytes = 0L
        fun add(value: Long) { bytes += value; v2require(bytes <= limit.toLong(), "LIMIT_EXCEEDED", "encoded JSON size") }
        fun string(value: String) {
            add(2); var index = 0
            while (index < value.length) { val c = value[index++]
                add(when { c == '"' || c == '\\' || c in "\b\u000c\n\r\t" -> 2
                    c.code < 32 -> 6; c.code < 128 -> 1; c.code < 2048 -> 2
                    Character.isHighSurrogate(c) -> { index++; 4 }; else -> 3 }.toLong())
            }
        }
        fun visit(node: JsonElement) { when (node) {
            is JsonObject -> { add(2); node.entries.forEachIndexed { index, entry -> if (index > 0) add(1); string(entry.key); add(1); visit(entry.value) } }
            is JsonArray -> { add(2); node.forEachIndexed { index, value -> if (index > 0) add(1); visit(value) } }
            is JsonPrimitive -> if (node.isString) string(node.content) else add(node.toString().length.toLong())
        } }
        visit(root)
    }
}
