package com.example.myapplication.domain.reader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

class ReaderLocatorCodecException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * Strict JSON codec for the frozen Locator v1 contract.
 *
 * Unknown fields are rejected instead of silently dropped, and validation is
 * performed on both encode and decode. Persisted locators are security- and
 * correctness-sensitive data: a malformed rectangle, document id, revision or
 * UTF-16 boundary must never be treated as a usable position.
 */
object ReaderLocatorCodec {
    private const val SCHEMA_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = true
        allowSpecialFloatingPointValues = false
    }

    fun encode(locator: ReaderLocator): String {
        val validation = ReaderLocatorValidator.validate(locator)
        if (!validation.isValid) {
            throw ReaderLocatorCodecException(
                "invalid locator: ${validation.errors.joinToString("; ")}",
            )
        }
        return buildJsonObject {
            put("schemaVersion", locator.schemaVersion)
            put("kind", locator.kindName())
            put("documentId", locator.documentId)
            put("sectionId", locator.sectionId)
            put("contentRevision", locator.contentRevision)
            locator.progression?.let { put("progression", it) }

            when (locator) {
                is ReaderLocator.Reflow -> {
                    put("nativeLocator", locator.nativeLocator)
                    put("exactQuote", locator.exactQuote)
                    locator.prefix?.takeIf(String::isNotEmpty)?.let { put("prefix", it) }
                    locator.suffix?.takeIf(String::isNotEmpty)?.let { put("suffix", it) }
                    put("canonicalStart", locator.canonicalStart)
                    put("canonicalEnd", locator.canonicalEnd)
                    put("blockId", locator.blockId)
                }

                is ReaderLocator.Fixed -> {
                    put("pageIndex", locator.pageIndex)
                    put("pageBox", locator.pageBox.toJson())
                    put("rotation", locator.rotationDegrees)
                    put("rects", JsonArray(locator.rects.map { it.toJson() }))
                    locator.textQuote?.let { put("textQuote", it) }
                }

                is ReaderLocator.Note -> {
                    put("notebookId", locator.notebookId)
                    put("noteRevision", locator.noteRevision)
                    locator.blockId?.let { put("blockId", it) }
                    locator.canonicalStart?.let { put("canonicalStart", it) }
                    locator.canonicalEnd?.let { put("canonicalEnd", it) }
                }
            }
        }.toString()
    }

    fun decode(text: String): ReaderLocator {
        val root = try {
            json.parseToJsonElement(text)
        } catch (error: Exception) {
            throw ReaderLocatorCodecException("locator JSON is not valid", error)
        }
        val obj = root as? JsonObject
            ?: throw ReaderLocatorCodecException("locator root must be a JSON object")

        val schemaVersion = obj.requireInt("schemaVersion")
        if (schemaVersion != SCHEMA_VERSION) {
            throw ReaderLocatorCodecException("unsupported locator schema version: $schemaVersion")
        }

        val kind = obj.requireString("kind")
        val allowedKeys = when (kind) {
            "REFLOW" -> COMMON_KEYS + setOf(
                "nativeLocator", "exactQuote", "prefix", "suffix",
                "canonicalStart", "canonicalEnd", "blockId",
            )

            "FIXED" -> COMMON_KEYS + setOf(
                "pageIndex", "pageBox", "rotation", "rects", "textQuote",
            )

            "NOTE" -> COMMON_KEYS + setOf(
                "notebookId", "noteRevision", "blockId", "canonicalStart", "canonicalEnd",
            )

            else -> throw ReaderLocatorCodecException("unsupported locator kind: $kind")
        }
        obj.rejectUnknownKeys(allowedKeys)

        val documentId = obj.requireString("documentId")
        val sectionId = obj.requireString("sectionId")
        val contentRevision = obj.requireString("contentRevision")
        val progression = obj.optionalFloat("progression")

        val locator = when (kind) {
            "REFLOW" -> ReaderLocator.Reflow(
                documentId = documentId,
                sectionId = sectionId,
                contentRevision = contentRevision,
                progression = progression,
                nativeLocator = obj.requireString("nativeLocator"),
                exactQuote = obj.requireString("exactQuote"),
                prefix = obj.optionalString("prefix"),
                suffix = obj.optionalString("suffix"),
                canonicalStart = obj.requireInt("canonicalStart"),
                canonicalEnd = obj.requireInt("canonicalEnd"),
                blockId = obj.requireString("blockId"),
            )

            "FIXED" -> ReaderLocator.Fixed(
                documentId = documentId,
                sectionId = sectionId,
                contentRevision = contentRevision,
                progression = progression,
                pageIndex = obj.requireInt("pageIndex"),
                pageBox = obj.requireRect("pageBox"),
                rotationDegrees = obj.requireInt("rotation"),
                rects = obj.requireRectArray("rects"),
                textQuote = obj.optionalString("textQuote"),
            )

            "NOTE" -> ReaderLocator.Note(
                documentId = documentId,
                sectionId = sectionId,
                contentRevision = contentRevision,
                progression = progression,
                notebookId = obj.requireString("notebookId"),
                noteRevision = obj.requireInt("noteRevision"),
                blockId = obj.optionalString("blockId"),
                canonicalStart = obj.optionalInt("canonicalStart"),
                canonicalEnd = obj.optionalInt("canonicalEnd"),
            )

            else -> error("kind was checked above")
        }

        val validation = ReaderLocatorValidator.validate(locator)
        if (!validation.isValid) {
            throw ReaderLocatorCodecException(
                "locator failed validation: ${validation.errors.joinToString("; ")}",
            )
        }
        return locator
    }

    fun decodeOrNull(text: String): ReaderLocator? = runCatching { decode(text) }.getOrNull()

    private fun ReaderLocator.kindName(): String = when (this) {
        is ReaderLocator.Reflow -> "REFLOW"
        is ReaderLocator.Fixed -> "FIXED"
        is ReaderLocator.Note -> "NOTE"
    }

    private fun NormalizedRect.toJson(): JsonObject = buildJsonObject {
        put("left", JsonPrimitive(left))
        put("top", JsonPrimitive(top))
        put("right", JsonPrimitive(right))
        put("bottom", JsonPrimitive(bottom))
    }

    private fun JsonObject.requireString(name: String): String {
        val element = this[name] ?: throw ReaderLocatorCodecException("missing $name")
        val primitive = element as? JsonPrimitive
            ?: throw ReaderLocatorCodecException("$name must be a string")
        if (!primitive.isString) throw ReaderLocatorCodecException("$name must be a string")
        return primitive.content
    }

    private fun JsonObject.optionalString(name: String): String? {
        val element = this[name] ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive
            ?: throw ReaderLocatorCodecException("$name must be a string or null")
        if (!primitive.isString) throw ReaderLocatorCodecException("$name must be a string or null")
        return primitive.content
    }

    private fun JsonObject.requireInt(name: String): Int {
        val element = this[name] ?: throw ReaderLocatorCodecException("missing $name")
        val primitive = element as? JsonPrimitive
            ?: throw ReaderLocatorCodecException("$name must be an integer")
        if (primitive.isString) throw ReaderLocatorCodecException("$name must be an integer")
        return primitive.intOrNull ?: throw ReaderLocatorCodecException("$name must be an integer")
    }

    private fun JsonObject.optionalInt(name: String): Int? {
        val element = this[name] ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive
            ?: throw ReaderLocatorCodecException("$name must be an integer or null")
        if (primitive.isString) throw ReaderLocatorCodecException("$name must be an integer or null")
        return primitive.intOrNull
            ?: throw ReaderLocatorCodecException("$name must be an integer or null")
    }

    private fun JsonObject.optionalFloat(name: String): Float? {
        val element = this[name] ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive
            ?: throw ReaderLocatorCodecException("$name must be a finite number or null")
        if (primitive.isString) throw ReaderLocatorCodecException("$name must be a finite number or null")
        val value = primitive.floatOrNull
            ?: throw ReaderLocatorCodecException("$name must be a finite number or null")
        if (!value.isFinite()) throw ReaderLocatorCodecException("$name must be finite")
        return value
    }

    private fun JsonObject.requireRect(name: String): NormalizedRect {
        val element = this[name] ?: throw ReaderLocatorCodecException("missing $name")
        return element.toRect(name)
    }

    private fun JsonObject.requireRectArray(name: String): List<NormalizedRect> {
        val element = this[name] ?: throw ReaderLocatorCodecException("missing $name")
        val array = element as? JsonArray
            ?: throw ReaderLocatorCodecException("$name must be an array")
        return array.mapIndexed { index, item -> item.toRect("$name[$index]") }
    }

    private fun JsonElement.toRect(field: String): NormalizedRect {
        val obj = this as? JsonObject
            ?: throw ReaderLocatorCodecException("$field must be an object")
        obj.rejectUnknownKeys(RECT_KEYS)
        return NormalizedRect(
            left = obj.requireFloat("left", field),
            top = obj.requireFloat("top", field),
            right = obj.requireFloat("right", field),
            bottom = obj.requireFloat("bottom", field),
        )
    }

    private fun JsonObject.requireFloat(name: String, prefix: String): Float {
        val element = this[name] ?: throw ReaderLocatorCodecException("missing $prefix.$name")
        val primitive = element as? JsonPrimitive
            ?: throw ReaderLocatorCodecException("$prefix.$name must be a finite number")
        if (primitive.isString) throw ReaderLocatorCodecException("$prefix.$name must be a finite number")
        val value = primitive.floatOrNull
            ?: throw ReaderLocatorCodecException("$prefix.$name must be a finite number")
        if (!value.isFinite()) throw ReaderLocatorCodecException("$prefix.$name must be finite")
        return value
    }

    private fun JsonObject.rejectUnknownKeys(allowed: Set<String>) {
        val unknown = keys - allowed
        if (unknown.isNotEmpty()) {
            throw ReaderLocatorCodecException("unknown locator field(s): ${unknown.sorted().joinToString(",")}")
        }
    }

    private val COMMON_KEYS = setOf(
        "schemaVersion", "kind", "documentId", "sectionId", "contentRevision", "progression",
    )
    private val RECT_KEYS = setOf("left", "top", "right", "bottom")
}