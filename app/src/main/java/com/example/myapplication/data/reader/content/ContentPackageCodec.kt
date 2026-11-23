package com.example.myapplication.data.reader.content

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.serialization.json.*

data class LegacyContentPackage(
    val manifest: JsonObject,
    val manifestHash: String,
    val chapterRevisions: Map<String, String>,
)

object ContentPackageCodec {
    private const val MAX_MANIFEST_BYTES = 1024 * 1024
    internal const val LEGACY_MANIFEST_SHA256 = "c353119bf26d966bc459fe8479fed694118e831cf802e76629a37d570bbc8dbb"
    internal const val LEGACY_MANIFEST_HASH = "a6bc2ecb9edac40a3fa56768fe5e30d3002c0d8ed28227520fd675ee2f9d0fe2"
    internal val legacyBodies = linkedMapOf(
        "meridian-overview" to "3b231c2480d8ca9949c5011383b8ab2dce367998a719cc49f5f6d713b932404d",
        "twelve-meridians" to "ffcab0b7a26cec0a196b0e165fd993a298d05cdcf3fe1e5b253dc4e8260405a1",
        "acupoint-basics" to "9765717663b70c8b584ac3d8cea713f815caf74c3c3e7bcab0514774ec22dac1",
        "common-acupoints" to "2dfc3f4b786da6b794fddde1d7140c8c4f6926de3861e1f2966713e0e83932d0",
        "needling-basics" to "ed813bbf61e8963a5d66ae691efcef483cda1992b60a779a179b799a468b5b39",
        "moxibustion-basics" to "b68bd465479835fdace4bcd60ee126ed693b1791c887ff5ced416a78c111f4ba",
        "treatment-principles" to "16c15dc054324ec1487fdc3a04f43b728fc59d99ce17a61e0eaf0f8a8c918d6b",
    )

    /** Strict schema2 shape, UTF-8, duplicate-property, depth and size validation. */
    fun decode(bytes: ByteArray): JsonObject = parse(bytes).also { ContentPackageWire.v2.check(it, "root") }

    /** Shape-only schema1 guard for the existing seeder; does not grant LEGACY_V1 identity. */
    fun decodeLegacyManifest(bytes: ByteArray): JsonObject = parse(bytes).also { root ->
        ContentPackageWire.v1.check(root, "root")
        root.getValue("chapters").jsonArray.forEachIndexed { index, chapter ->
            require(contentPackagePath(chapter.jsonObject.getValue("body").jsonPrimitive.content)) { "root.chapters[$index].body: unsafe path" }
        }
    }

    fun encode(manifest: JsonObject): ByteArray {
        ContentPackageWire.v2.check(manifest, "root")
        return canonicalJson(manifest).encodeToByteArray().also {
            require(it.size <= MAX_MANIFEST_BYTES) { "root: manifest exceeds 1 MiB" }
        }
    }

    /** CJ2 preserves arrays, explicit null and numeric JSON tokens. */
    fun canonicalJson(value: JsonElement): String = when (value) {
        is JsonObject -> value.keys.sorted().joinToString(",", "{", "}") { key ->
            require(contentUtf16Valid(key)) { "CJ2: invalid object key UTF-16" }
            JsonPrimitive(key).toString() + ":" + canonicalJson(value.getValue(key))
        }
        is JsonArray -> value.joinToString(",", "[", "]") { canonicalJson(it) }
        is JsonPrimitive -> {
            if (value.isString) require(contentUtf16Valid(value.content)) { "CJ2: invalid string UTF-16" }
            value.toString()
        }
    }

    fun contentRevision(chapter: JsonObject): String = sha256((
        "CONTENT_V2\n" + chapter.getValue("chapterKey").jsonPrimitive.content + "\n" +
            chapter.getValue("bodySha256").jsonPrimitive.content + "\n" +
            canonicalJson(chapter.getValue("knowledgePoints")) + "\n" + canonicalJson(chapter.getValue("review"))
        ).encodeToByteArray())

    /** Only original raw manifest bytes and the seven original body fingerprints qualify. */
    fun validateLegacy(manifestBytes: ByteArray, resources: Map<String, ByteArray>): LegacyContentPackage {
        val original = decodeLegacyManifest(manifestBytes)
        require(sha256(manifestBytes) == LEGACY_MANIFEST_SHA256) { "root: unknown legacy manifest fingerprint" }
        val chapters = original.getValue("chapters").jsonArray.map { it.jsonObject }
        require(resources.keys == legacyBodies.keys.map { "chapters/$it.html" }.toSet()) { "root.resources: original legacy body set required" }
        val hashes = chapters.associate { chapter ->
            val key = chapter.getValue("key").jsonPrimitive.content
            val path = chapter.getValue("body").jsonPrimitive.content
            val hash = sha256(requireNotNull(resources[path]) { "root.resources.$path: missing legacy body" })
            require(hash == legacyBodies[key]) { "root.resources.$path: unknown legacy body sha256" }
            key to hash
        }
        val manifestHash = sha256(manifestBytes + chapters.joinToString("") {
            it.getValue("key").jsonPrimitive.content.let { key -> key + hashes.getValue(key) }
        }.encodeToByteArray())
        require(manifestHash == LEGACY_MANIFEST_HASH) { "root: legacy identity mismatch" }
        val revisions = hashes.mapValues { (key, hash) -> sha256("$manifestHash:$key:$hash".encodeToByteArray()) }
        val mappedChapters = chapters.map { old ->
            val key = old.getValue("key").jsonPrimitive.content
            buildJsonObject {
                put("bookKey", "acupuncture-foundation"); put("chapterKey", key); put("orderIndex", old.getValue("order"))
                put("title", old.getValue("title")); put("summary", old.getValue("summary")); put("bodyPath", old.getValue("body"))
                put("bodySha256", hashes.getValue(key)); put("contentRevision", revisions.getValue(key)); put("revisionAlgorithm", "LEGACY_V1")
                put("knowledgePoints", JsonArray(emptyList())); put("review", draftReview)
                put("learning", learning("保留旧章作为兼容输入，后续补审核正文", "阅读后核对来源")); put("updatedAtUtc", LEGACY_UPDATED_AT)
            }
        }
        val book = buildJsonObject {
            put("bookKey", "acupuncture-foundation"); put("title", "针灸基础与经络腧穴"); put("summary", "旧七章格式兼容映射，不代表已审核")
            put("orderIndex", 0); put("coverPath", JsonNull); put("chapterKeys", JsonArray(hashes.keys.map { JsonPrimitive(it) }))
            put("review", draftReview); put("learning", learning("按固定来源阅读并核对本书知识点", "先读正文，再核对练习解析")); put("updatedAtUtc", LEGACY_UPDATED_AT)
        }
        val mapped = buildJsonObject {
            put("format", "learning-content-package"); put("schemaVersion", 2); put("packageId", original.getValue("packageId"))
            put("packageVersion", original.getValue("version")); put("testOnly", true); put("books", JsonArray(listOf(book))); put("chapters", JsonArray(mappedChapters))
            put("sources", JsonArray(emptyList())); put("questions", JsonArray(emptyList()))
            put("resources", JsonArray(chapters.map { old ->
                val key = old.getValue("key").jsonPrimitive.content
                val path = old.getValue("body").jsonPrimitive.content
                buildJsonObject {
                    put("path", path); put("kind", "BODY"); put("sha256", hashes.getValue(key)); put("bytes", resources.getValue(path).size)
                    put("license", "保留仓库旧样例许可状态，不扩展发布许可"); put("altText", JsonNull)
                }
            }))
        }
        ContentPackageWire.v2.check(mapped, "root")
        return LegacyContentPackage(mapped, manifestHash, revisions)
    }

    internal val draftReview = buildJsonObject {
        put("status", "DRAFT"); put("reviewer", JsonNull); put("reviewedAtUtc", JsonNull); put("recordPath", JsonNull); put("recordSha256", JsonNull)
    }
    private const val LEGACY_UPDATED_AT = "2026-10-26T00:00:00Z"
    private fun learning(objective: String, hint: String) = buildJsonObject {
        put("difficulty", "ENTRY"); put("objectives", JsonArray(listOf(JsonPrimitive(objective)))); put("prerequisiteChapterKeys", JsonArray(emptyList())); put("studyHint", hint)
    }

    internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    internal fun utf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (error: java.nio.charset.CharacterCodingException) {
        throw IllegalArgumentException("root: invalid UTF-8", error)
    }
    private fun parse(bytes: ByteArray): JsonObject {
        require(bytes.isNotEmpty() && bytes.size <= MAX_MANIFEST_BYTES) { "root: invalid manifest size" }
        val parsed = StrictJson(utf8(bytes)).parse()
        require(parsed is JsonObject) { "root: expected object" }
        return parsed
    }

    /** Build objects ourselves because Json.parseToJsonElement silently accepts duplicate keys. */
    private class StrictJson(private val source: String) {
        private var offset = 0
        fun parse(): JsonElement {
            val value = value("root", 0)
            whitespace()
            require(offset == source.length) { "root: trailing JSON data" }
            return value
        }
        private fun value(path: String, depth: Int): JsonElement {
            require(depth <= 64) { "$path: JSON nesting limit exceeded" }
            whitespace()
            require(offset < source.length) { "$path: incomplete JSON" }
            return when (source[offset]) {
                '{' -> {
                    offset++; whitespace(); val fields = linkedMapOf<String, JsonElement>()
                    if (!take('}')) {
                        do {
                            whitespace(); val key = string(path).content
                            require(contentUtf16Valid(key)) { "$path: invalid key UTF-16" }
                            require(key !in fields) { "$path.$key: duplicate property" }
                            whitespace(); require(take(':')) { "$path.$key: expected colon" }
                            fields[key] = value("$path.$key", depth + 1)
                            whitespace()
                        } while (take(','))
                        require(take('}')) { "$path: expected object end" }
                    }
                    JsonObject(fields)
                }
                '[' -> {
                    offset++; whitespace(); val items = mutableListOf<JsonElement>()
                    if (!take(']')) {
                        do { items += value("$path[${items.size}]", depth + 1); whitespace() } while (take(','))
                        require(take(']')) { "$path: expected array end" }
                    }
                    JsonArray(items)
                }
                '"' -> string(path)
                else -> {
                    val start = offset
                    while (offset < source.length && source[offset] !in " \t\r\n,]}") offset++
                    val token = source.substring(start, offset)
                    require(token.matches(Regex("(?:true|false|null|-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)"))) { "$path: invalid JSON token" }
                    Json.parseToJsonElement(token)
                }
            }
        }
        private fun string(path: String): JsonPrimitive {
            require(take('"')) { "$path: expected quoted string" }
            val start = offset - 1
            while (offset < source.length) {
                when (source[offset++]) {
                    '\\' -> { require(offset < source.length) { "$path: incomplete string escape" }; offset++ }
                    '"' -> return try {
                        Json.parseToJsonElement(source.substring(start, offset)).jsonPrimitive.also {
                            require(contentUtf16Valid(it.content)) { "$path: invalid UTF-16 string" }
                        }
                    } catch (error: IllegalArgumentException) { throw IllegalArgumentException("$path: invalid JSON string", error) }
                }
            }
            throw IllegalArgumentException("$path: unterminated string")
        }
        private fun whitespace() { while (offset < source.length && source[offset] in " \t\r\n") offset++ }
        private fun take(character: Char): Boolean = (offset < source.length && source[offset] == character).also { if (it) offset++ }
    }
}

internal fun contentPackagePath(path: String): Boolean =
    path.matches(Regex("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")) && path.split('/').none { it == "." || it == ".." }
