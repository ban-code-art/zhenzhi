package com.example.myapplication.data.quiz

import com.example.myapplication.domain.quiz.QuizCodecs
import com.example.myapplication.domain.quiz.QuizItemSnapshot
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.*

/** App-private, owner-isolated review storage. No model-provided path is ever opened. */
class QuizReviewDraftStore(private val rootDirectory: File) {
    data class Draft(
        val paperId: String, val ownerUserId: Long, val sourceRevision: String,
        val scopeSnapshot: JsonObject, val items: List<QuizItemSnapshot>,
        val supportAssessments: JsonArray, val sourceSnapshots: JsonArray,
        val createdAtUtc: String, val updatedAtUtc: String,
    )
    data class Reference(val version: Int = 1, val state: String = "REVIEW_REQUIRED",
        val draftPath: String, val draftSha256: String)

    fun save(draft: Draft): Reference = saveInternal(draft, false)

    /** Immutable version first, database reference CAS second: a crash cannot destroy the old draft. */
    fun saveVersioned(draft: Draft): Reference = saveInternal(draft, true)

    private fun saveInternal(draft: Draft, versioned: Boolean): Reference = synchronized(storageLock) {
        validate(draft)
        val basePath = relativePath(draft.ownerUserId, draft.paperId)
        val payload = payload(draft)
        val document = JsonObject(payload + ("draftHash" to JsonPrimitive(hash(canonical(payload)))))
        val bytes = canonical(document).toByteArray(Charsets.UTF_8)
        val path = if (versioned) basePath.replace("draft-v1.json", "draft-v1-${hash(bytes)}.json") else basePath
        val target = safeFile(path)
        require(bytes.size <= MAX_BYTES) { "Review draft exceeds private storage limit" }
        require(target.parentFile.mkdirs() || target.parentFile.isDirectory) { "Review directory unavailable" }
        safeFile(path) // reject links introduced through an existing user/paper directory
        if (versioned && target.exists()) {
            if (!target.isFile || target.length() != bytes.size.toLong() ||
                target.inputStream().use { it.readBytesBounded(MAX_BYTES) }?.contentEquals(bytes) != true) throw IOException("Immutable review version is corrupt")
            return@synchronized Reference(draftPath = path, draftSha256 = hash(bytes))
        }
        val temporary = File(target.parentFile, "draft-${UUID.randomUUID()}.tmp")
        val backup = safeFile("$path.bak")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.flush(); it.fd.sync() }
            // Android/Linux rename replaces atomically. Windows needs the backup branch.
            if (!temporary.renameTo(target)) {
                if (backup.exists() && !backup.delete()) throw IOException("Stale review backup cannot be removed")
                val hadOriginal = target.exists()
                if (hadOriginal && !target.renameTo(backup)) throw IOException("Review backup failed")
                if (!temporary.renameTo(target)) {
                    if (hadOriginal && !backup.renameTo(target)) throw IOException("Review commit and restoration failed")
                    throw IOException("Review commit failed")
                }
            }
            if (backup.exists() && !backup.delete()) throw IOException("Review backup cleanup failed")
            syncDirectory(target.parentFile)
            Reference(draftPath = path, draftSha256 = hash(bytes))
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    /** Missing, corrupt, stale or foreign records fail closed; IO failures do not become valid drafts. */
    fun load(userId: Long, paperId: String, reference: Reference,
        expectedRevision: String? = null, expectedScope: JsonObject? = null): Draft? = synchronized(storageLock) {
        try {
            val base = relativePath(userId, paperId)
            val path = reference.draftPath
            val versioned = base.replace("draft-v1.json", "draft-v1-${reference.draftSha256.lowercase()}.json")
            if (reference.version != 1 || reference.state != "REVIEW_REQUIRED" || (path != base && path != versioned) ||
                !reference.draftSha256.matches(Regex("[a-fA-F0-9]{64}"))) return@synchronized null
            val target = safeFile(path)
            val backup = safeFile("$path.bak")
            // A crash in the Windows replacement window leaves the previous committed file here.
            if (!target.exists() && backup.isFile && !backup.renameTo(target)) return@synchronized null
            if (!target.isFile || target.length() > MAX_BYTES) return@synchronized null
            val bytes = target.inputStream().use { input ->
                val result = input.readBytesBounded(MAX_BYTES)
                result ?: return@synchronized null
            }
            if (!hash(bytes).equals(reference.draftSha256, ignoreCase = true)) return@synchronized null
            val obj = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            if (obj["schemaVersion"]?.jsonPrimitive?.intOrNull != 1) return@synchronized null
            val storedHash = obj["draftHash"]?.jsonPrimitive?.content ?: return@synchronized null
            if (storedHash != hash(canonical(JsonObject(obj - "draftHash")))) return@synchronized null
            val draft = Draft(
                obj.getValue("paperId").jsonPrimitive.content, obj.getValue("ownerUserId").jsonPrimitive.long,
                obj.getValue("sourceRevision").jsonPrimitive.content, obj.getValue("scopeSnapshot").jsonObject,
                obj.getValue("items").jsonArray.map { QuizCodecs.decodeItemSnapshot(it.toString()) },
                obj.getValue("supportAssessments").jsonArray, obj.getValue("sourceSnapshots").jsonArray,
                obj.getValue("createdAtUtc").jsonPrimitive.content, obj.getValue("updatedAtUtc").jsonPrimitive.content,
            )
            validate(draft)
            if (draft.ownerUserId != userId || draft.paperId != paperId ||
                (expectedRevision != null && draft.sourceRevision != expectedRevision) ||
                (expectedScope != null && draft.scopeSnapshot != expectedScope)) null else draft
        } catch (_: IOException) { null } catch (_: IllegalArgumentException) { null }
          catch (_: NoSuchElementException) { null } catch (_: IllegalStateException) { null }
    }

    fun mergeCoverage(existing: JsonObject, reference: Reference): JsonObject = JsonObject(existing +
        ("supportReview" to buildJsonObject {
            put("version", reference.version); put("state", reference.state)
            put("draftPath", reference.draftPath); put("draftSha256", reference.draftSha256)
        }))

    fun referenceFromCoverage(existing: JsonObject): Reference? = try {
        val r = existing["supportReview"]?.jsonObject
        if (r == null) null else Reference(r.getValue("version").jsonPrimitive.int,
            r.getValue("state").jsonPrimitive.content, r.getValue("draftPath").jsonPrimitive.content,
            r.getValue("draftSha256").jsonPrimitive.content).takeIf { it.version == 1 && it.state == "REVIEW_REQUIRED" }
    } catch (_: IllegalArgumentException) { null } catch (_: NoSuchElementException) { null }
      catch (_: IllegalStateException) { null }

    private fun payload(draft: Draft): JsonObject = buildJsonObject {
        put("schemaVersion", 1); put("paperId", draft.paperId); put("ownerUserId", draft.ownerUserId)
        put("sourceRevision", draft.sourceRevision); put("scopeSnapshot", draft.scopeSnapshot)
        put("items", JsonArray(draft.items.map { Json.parseToJsonElement(QuizCodecs.encodeItemSnapshot(it)) }))
        put("supportAssessments", draft.supportAssessments); put("sourceSnapshots", draft.sourceSnapshots)
        put("createdAtUtc", draft.createdAtUtc); put("updatedAtUtc", draft.updatedAtUtc)
    }
    private fun validate(draft: Draft) {
        relativePath(draft.ownerUserId, draft.paperId)
        require(draft.sourceRevision.isNotBlank() && draft.createdAtUtc.isNotBlank() && draft.updatedAtUtc.isNotBlank())
        require(draft.items.isNotEmpty() && draft.items.size <= 200)
    }
    private fun relativePath(userId: Long, paperId: String): String {
        require(userId > 0 && paperId.matches(Regex("[A-Za-z0-9_-]{1,160}"))) { "Invalid review owner or paper identity" }
        return "quiz-review/$userId/$paperId/draft-v1.json"
    }
    private fun safeFile(relative: String): File {
        val root = rootDirectory.canonicalFile // app filesDir itself may use the OS /data/user/0 alias
        val target = File(root, relative)
        var part = root
        relative.split('/').forEach { name ->
            part = File(part, name)
            require(!isSymbolicLink(part)) { "Review path contains a symbolic link" }
        }
        require(target.canonicalFile == target.absoluteFile && target.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
            "Review path escaped private root"
        }
        return target
    }
    /** Android API24 lacks java.nio.file; reflective probes avoid linking unavailable classes. */
    private fun isSymbolicLink(file: File): Boolean {
        try {
            val path = File::class.java.getMethod("toPath").invoke(file)
            val pathClass = Class.forName("java.nio.file.Path")
            return Class.forName("java.nio.file.Files").getMethod("isSymbolicLink", pathClass).invoke(null, path) as Boolean
        } catch (_: ReflectiveOperationException) {
            // API24's public Os.lstat observes the link itself, including dangling links.
            try {
                val stat = Class.forName("android.system.Os").getMethod("lstat",String::class.java).invoke(null,file.path)
                val mode = stat.javaClass.getField("st_mode").getInt(stat)
                return mode and 0xF000 == 0xA000
            } catch (error: java.lang.reflect.InvocationTargetException) {
                // ENOENT is safe only for a genuinely absent path; other IO failures fail closed.
                val cause = error.cause
                val errno = try { cause?.javaClass?.getField("errno")?.getInt(cause) } catch (_: ReflectiveOperationException) { null }
                require(errno == 2) { "Cannot inspect review path" }
                return false
            } catch (_: ReflectiveOperationException) {
                throw IllegalArgumentException("Symbolic link inspection unavailable")
            }
        }
    }
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray? {
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val count = read(buffer); if (count < 0) break
            if (output.size() + count > limit) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.entries.sortedBy { it.key }.joinToString(",", "{", "}") {
            JsonPrimitive(it.key).toString() + ":" + canonical(it.value)
        }
        is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
        else -> value.toString()
    }
    private fun hash(text: String) = hash(text.toByteArray(Charsets.UTF_8))
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    /** Persist the renamed entry before Room may commit its reference; host JVM tests do not use Android Os. */
    private fun syncDirectory(directory: File) {
        if (System.getProperty("java.vm.name") != "Dalvik") return
        val descriptor = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY, 0)
        try { android.system.Os.fsync(descriptor) } finally { android.system.Os.close(descriptor) }
    }
    companion object {
        private const val MAX_BYTES = 8 * 1024 * 1024
        private val storageLock = Any()
    }
}
