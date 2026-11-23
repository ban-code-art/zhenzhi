package com.example.myapplication.data.reader.storage

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class ManifestFileKind {
    SECTION,
    IMAGE,
    STYLE,
    FONT,
    COVER,
    OTHER,
    SOURCE,
}

data class ManifestFile(
    val relativePath: String,
    val sha256: String,
    val bytes: Long,
    val kind: ManifestFileKind,
)

data class ManifestSection(
    val id: String,
    val orderIndex: Int,
    val title: String,
    val resourceHref: String,
    val charCount: Int = 0,
    val pageStart: Int? = null,
    val pageEnd: Int? = null,
)

data class ManifestOutlineNode(
    val nodeKey: String,
    val parentKey: String? = null,
    val orderIndex: Int,
    val title: String,
    val targetSectionId: String? = null,
    val targetLocatorJson: String? = null,
)

data class ContentManifest(
    val schemaVersion: Int,
    val jobId: String,
    val documentId: String,
    val sourceSha256: String,
    val contentRevision: String,
    val extractorVersion: String,
    val title: String? = null,
    val author: String? = null,
    val files: List<ManifestFile>,
    val sections: List<ManifestSection>,
    val outline: List<ManifestOutlineNode> = emptyList(),
)

data class ManifestValidation(val errors: List<String>) {
    val isValid: Boolean get() = errors.isEmpty()
}

object ContentManifestValidator {
    private const val SCHEMA_VERSION = 1
    private const val MAX_ID_LENGTH = 512
    private const val MAX_PATH_LENGTH = 512
    private val SHA256 = Regex("^[0-9a-f]{64}$")

    fun validate(manifest: ContentManifest): ManifestValidation {
        val errors = mutableListOf<String>()
        if (manifest.schemaVersion != SCHEMA_VERSION) {
            errors += "unsupported manifest schema version: ${manifest.schemaVersion}"
        }
        validateText(manifest.jobId, "jobId", errors)
        validateText(manifest.documentId, "documentId", errors)
        validateText(manifest.extractorVersion, "extractorVersion", errors)
        validateHash(manifest.sourceSha256, "sourceSha256", errors)
        validateHash(manifest.contentRevision, "contentRevision", errors)
        manifest.title?.let { validateText(it, "title", errors) }
        manifest.author?.let { validateText(it, "author", errors) }

        if (manifest.files.isEmpty()) errors += "manifest must contain at least one file"
        val paths = mutableSetOf<String>()
        manifest.files.forEachIndexed { index, file ->
            val prefix = "files[$index]"
            validateRelativePath(file.relativePath, "$prefix.relativePath", errors)
            val folded = file.relativePath.lowercase()
            if (!paths.add(folded)) errors += "$prefix.relativePath is duplicated"
            validateHash(file.sha256, "$prefix.sha256", errors)
            if (file.bytes < 0) errors += "$prefix.bytes must be >= 0"
            if (file.kind.name.isBlank()) errors += "$prefix.kind must not be blank"
        }

        if (manifest.sections.isEmpty()) errors += "manifest must contain at least one section"
        val sectionIds = mutableSetOf<String>()
        manifest.sections.forEachIndexed { index, section ->
            val prefix = "sections[$index]"
            validateText(section.id, "$prefix.id", errors)
            if (!sectionIds.add(section.id)) errors += "$prefix.id is duplicated"
            if (section.orderIndex < 0) errors += "$prefix.orderIndex must be >= 0"
            validateText(section.title, "$prefix.title", errors)
            validateText(section.resourceHref, "$prefix.resourceHref", errors)
            if (section.charCount < 0) errors += "$prefix.charCount must be >= 0"
            val start = section.pageStart
            val end = section.pageEnd
            if ((start == null) != (end == null)) {
                errors += "$prefix.pageStart and pageEnd must be provided together"
            }
            if (start != null && start < 0) errors += "$prefix.pageStart must be >= 0"
            if (start != null && end != null && end < start) {
                errors += "$prefix.pageEnd must be >= pageStart"
            }
        }

        val outlineKeys = mutableSetOf<String>()
        manifest.outline.forEachIndexed { index, node ->
            val prefix = "outline[$index]"
            validateText(node.nodeKey, "$prefix.nodeKey", errors)
            if (!outlineKeys.add(node.nodeKey)) errors += "$prefix.nodeKey is duplicated"
            if (node.orderIndex < 0) errors += "$prefix.orderIndex must be >= 0"
            validateText(node.title, "$prefix.title", errors)
            node.parentKey?.let { validateText(it, "$prefix.parentKey", errors) }
            node.targetSectionId?.let { target ->
                if (target !in sectionIds) errors += "$prefix.targetSectionId does not exist in sections"
            }
            node.targetLocatorJson?.let {
                if (it.isBlank()) errors += "$prefix.targetLocatorJson must not be blank"
            }
        }
        manifest.outline.forEachIndexed { index, node ->
            val parent = node.parentKey
            if (parent != null && parent !in outlineKeys) {
                errors += "outline[$index].parentKey does not exist in outline"
            }
        }

        return ManifestValidation(errors)
    }

    fun validateText(value: String, field: String, errors: MutableList<String>) {
        if (value.isBlank()) errors += "$field must not be blank"
        if (value.length > MAX_ID_LENGTH) errors += "$field is too long"
        if (value.any(Char::isISOControl)) errors += "$field contains control characters"
    }

    fun validateRelativePath(path: String, field: String, errors: MutableList<String>) {
        if (path.isBlank()) {
            errors += "$field must not be blank"
            return
        }
        if (path.length > MAX_PATH_LENGTH) errors += "$field is too long"
        if (path.any(Char::isISOControl)) errors += "$field contains control characters"
        if (path.startsWith('/') || path.startsWith('\\')) errors += "$field must be relative"
        if (Regex("^[A-Za-z]:").containsMatchIn(path)) errors += "$field must not contain a drive prefix"
        if (path.contains('\\')) errors += "$field must use forward slashes"
        if (path == "manifest.json" || path.endsWith("/manifest.json")) {
            errors += "$field uses a reserved manifest name"
        }
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            errors += "$field contains an invalid path segment"
        }
    }

    private fun validateHash(value: String, field: String, errors: MutableList<String>) {
        if (!SHA256.matches(value)) errors += "$field must be a lowercase SHA-256 hex digest"
    }
}

class ManifestCodecException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

object ContentManifestCodec {
    private const val SCHEMA_VERSION = 1
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = true
        allowSpecialFloatingPointValues = false
    }

    fun encode(manifest: ContentManifest): String {
        val validation = ContentManifestValidator.validate(manifest)
        if (!validation.isValid) {
            throw ManifestCodecException("invalid manifest: ${validation.errors.joinToString("; ")}")
        }
        val objectValue = buildJsonObject {
            put("schemaVersion", manifest.schemaVersion)
            put("jobId", manifest.jobId)
            put("documentId", manifest.documentId)
            put("sourceSha256", manifest.sourceSha256)
            put("contentRevision", manifest.contentRevision)
            put("extractorVersion", manifest.extractorVersion)
            manifest.title?.let { put("title", it) }
            manifest.author?.let { put("author", it) }
            put("files", JsonArray(manifest.files.map { file ->
                buildJsonObject {
                    put("relativePath", file.relativePath)
                    put("sha256", file.sha256)
                    put("bytes", file.bytes)
                    put("kind", file.kind.name)
                }
            }))
            put("sections", JsonArray(manifest.sections.map { section ->
                buildJsonObject {
                    put("id", section.id)
                    put("orderIndex", section.orderIndex)
                    put("title", section.title)
                    put("resourceHref", section.resourceHref)
                    put("charCount", section.charCount)
                    section.pageStart?.let { put("pageStart", it) }
                    section.pageEnd?.let { put("pageEnd", it) }
                }
            }))
            put("outline", JsonArray(manifest.outline.map { node ->
                buildJsonObject {
                    put("nodeKey", node.nodeKey)
                    node.parentKey?.let { put("parentKey", it) }
                    put("orderIndex", node.orderIndex)
                    put("title", node.title)
                    node.targetSectionId?.let { put("targetSectionId", it) }
                    node.targetLocatorJson?.let { put("targetLocatorJson", it) }
                }
            }))
        }
        return objectValue.toString()
    }

    fun decode(text: String): ContentManifest {
        val root = try {
            json.parseToJsonElement(text)
        } catch (error: Exception) {
            throw ManifestCodecException("manifest JSON is not valid", error)
        }
        val obj = root as? JsonObject
            ?: throw ManifestCodecException("manifest root must be a JSON object")
        obj.rejectUnknownKeys(
            setOf(
                "schemaVersion", "jobId", "documentId", "sourceSha256", "contentRevision",
                "extractorVersion", "title", "author", "files", "sections", "outline",
            ),
        )
        val schemaVersion = obj.requireInt("schemaVersion")
        if (schemaVersion != SCHEMA_VERSION) {
            throw ManifestCodecException("unsupported manifest schema version: $schemaVersion")
        }
        val files = obj.requireArray("files").mapIndexed { index, element ->
            element.toFile("files[$index]")
        }
        val sections = obj.requireArray("sections").mapIndexed { index, element ->
            element.toSection("sections[$index]")
        }
        val outline = obj.optionalArray("outline").mapIndexed { index, element ->
            element.toOutlineNode("outline[$index]")
        }
        val manifest = ContentManifest(
            schemaVersion = schemaVersion,
            jobId = obj.requireString("jobId"),
            documentId = obj.requireString("documentId"),
            sourceSha256 = obj.requireString("sourceSha256"),
            contentRevision = obj.requireString("contentRevision"),
            extractorVersion = obj.requireString("extractorVersion"),
            title = obj.optionalString("title"),
            author = obj.optionalString("author"),
            files = files,
            sections = sections,
            outline = outline,
        )
        val validation = ContentManifestValidator.validate(manifest)
        if (!validation.isValid) {
            throw ManifestCodecException("manifest failed validation: ${validation.errors.joinToString("; ")}")
        }
        return manifest
    }

    fun canonicalHash(manifest: ContentManifest): String {
        val bytes = encode(manifest).toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun JsonElement.toFile(field: String): ManifestFile {
        val obj = this as? JsonObject ?: throw ManifestCodecException("$field must be an object")
        obj.rejectUnknownKeys(setOf("relativePath", "sha256", "bytes", "kind"))
        val kindText = obj.requireString("kind")
        val kind = runCatching { ManifestFileKind.valueOf(kindText) }
            .getOrElse { throw ManifestCodecException("$field.kind is unsupported") }
        return ManifestFile(
            relativePath = obj.requireString("relativePath"),
            sha256 = obj.requireString("sha256"),
            bytes = obj.requireLong("bytes"),
            kind = kind,
        )
    }

    private fun JsonElement.toSection(field: String): ManifestSection {
        val obj = this as? JsonObject ?: throw ManifestCodecException("$field must be an object")
        obj.rejectUnknownKeys(
            setOf("id", "orderIndex", "title", "resourceHref", "charCount", "pageStart", "pageEnd"),
        )
        return ManifestSection(
            id = obj.requireString("id"),
            orderIndex = obj.requireInt("orderIndex"),
            title = obj.requireString("title"),
            resourceHref = obj.requireString("resourceHref"),
            charCount = obj.optionalInt("charCount") ?: 0,
            pageStart = obj.optionalInt("pageStart"),
            pageEnd = obj.optionalInt("pageEnd"),
        )
    }

    private fun JsonElement.toOutlineNode(field: String): ManifestOutlineNode {
        val obj = this as? JsonObject ?: throw ManifestCodecException("$field must be an object")
        obj.rejectUnknownKeys(
            setOf("nodeKey", "parentKey", "orderIndex", "title", "targetSectionId", "targetLocatorJson"),
        )
        return ManifestOutlineNode(
            nodeKey = obj.requireString("nodeKey"),
            parentKey = obj.optionalString("parentKey"),
            orderIndex = obj.requireInt("orderIndex"),
            title = obj.requireString("title"),
            targetSectionId = obj.optionalString("targetSectionId"),
            targetLocatorJson = obj.optionalString("targetLocatorJson"),
        )
    }

    private fun JsonObject.requireString(name: String): String {
        val element = this[name] ?: throw ManifestCodecException("missing $name")
        val primitive = element as? JsonPrimitive
            ?: throw ManifestCodecException("$name must be a string")
        if (!primitive.isString) throw ManifestCodecException("$name must be a string")
        return primitive.content
    }

    private fun JsonObject.optionalString(name: String): String? {
        val element = this[name] ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive
            ?: throw ManifestCodecException("$name must be a string or null")
        if (!primitive.isString) throw ManifestCodecException("$name must be a string or null")
        return primitive.content
    }

    private fun JsonObject.requireInt(name: String): Int {
        val element = this[name] ?: throw ManifestCodecException("missing $name")
        val primitive = element as? JsonPrimitive
            ?: throw ManifestCodecException("$name must be an integer")
        if (primitive.isString) throw ManifestCodecException("$name must be an integer")
        return primitive.intOrNull ?: throw ManifestCodecException("$name must be an integer")
    }

    private fun JsonObject.optionalInt(name: String): Int? {
        val element = this[name] ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive
            ?: throw ManifestCodecException("$name must be an integer or null")
        if (primitive.isString) throw ManifestCodecException("$name must be an integer or null")
        return primitive.intOrNull ?: throw ManifestCodecException("$name must be an integer or null")
    }

    private fun JsonObject.requireLong(name: String): Long {
        val element = this[name] ?: throw ManifestCodecException("missing $name")
        val primitive = element as? JsonPrimitive
            ?: throw ManifestCodecException("$name must be an integer")
        if (primitive.isString) throw ManifestCodecException("$name must be an integer")
        return primitive.longOrNull ?: throw ManifestCodecException("$name must be an integer")
    }

    private fun JsonObject.requireArray(name: String): JsonArray {
        val element = this[name] ?: throw ManifestCodecException("missing $name")
        return element as? JsonArray ?: throw ManifestCodecException("$name must be an array")
    }

    private fun JsonObject.optionalArray(name: String): JsonArray {
        val element = this[name] ?: return JsonArray(emptyList())
        if (element is JsonNull) return JsonArray(emptyList())
        return element as? JsonArray ?: throw ManifestCodecException("$name must be an array")
    }

    private fun JsonObject.rejectUnknownKeys(allowed: Set<String>) {
        val unknown = keys - allowed
        if (unknown.isNotEmpty()) {
            throw ManifestCodecException("unknown manifest field(s): ${unknown.sorted().joinToString(",")}")
        }
    }
}