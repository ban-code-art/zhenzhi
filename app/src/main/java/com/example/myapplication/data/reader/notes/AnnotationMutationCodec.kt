package com.example.myapplication.data.reader.notes

import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import com.example.myapplication.domain.reader.ReaderLocatorValidator
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** CJ2 is the canonical JSON itself. It is never persisted as Locator v1 JSON. */
internal object AnnotationMutationCodec {
    private val numberPattern = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    private val integerFields = setOf("pageIndex", "rotation", "canonicalStart", "canonicalEnd", "noteRevision")
    private val decimalFields = setOf("progression", "left", "top", "right", "bottom")

    fun canonicalize(payload: JsonObject): String = buildString { appendCanonical(payload) }

    fun locatorProjection(locator: ReaderLocator): JsonObject {
        val encoded = ReaderLocatorCodec.encode(locator)
        // Apply the actual strict v1 codec, including its optional-field omission rules.
        val normalized = ReaderLocatorCodec.decode(encoded)
        val value = Json.parseToJsonElement(ReaderLocatorCodec.encode(normalized)).jsonObject
        validateStrings(value)
        return project(value)
    }

    fun payload(
        action: String,
        kind: AnnotationKind,
        annotationId: String,
        baseRevision: Long,
        parentLocator: ReaderLocator,
        quote: String?,
        noteText: String?,
        style: AnnotationStyle,
        targets: List<AnnotationTargetInput>,
    ): JsonObject {
        require(action in setOf("CREATE", "UPDATE", "DELETE", "RESTORE")) { "unsupported annotation action" }
        require(kind == AnnotationKind.NOTE || kind == AnnotationKind.HIGHLIGHT) { "unsupported annotation kind" }
        require(baseRevision > 0) { "invalid base revision" }
        require(annotationId.isNotBlank() && ReaderLocatorValidator.isWellFormedUtf16(annotationId)) { "invalid annotation id" }
        require((if (action == "CREATE") validQuote(quote) else validRetainedQuote(quote)) && validNote(noteText)) { "invalid annotation text" }
        require(targets.isNotEmpty()) { "empty annotation targets" }
        return buildJsonObject {
            put("payloadVersion", 2)
            put("action", action)
            put("kind", kind.name)
            put("annotationId", annotationId)
            put("baseRevision", baseRevision.toString())
            put("parentLocator", locatorProjection(parentLocator))
            put("quote", quote?.let(::JsonPrimitive) ?: JsonNull)
            put("noteText", noteText?.let(::JsonPrimitive) ?: JsonNull)
            put("style", Json.parseToJsonElement(style.encode()))
            put("ink", JsonNull)
            put("targets", JsonArray(targets.map { target ->
                require(target.precision in setOf("EXACT", "PAGE_ONLY", "UNRESOLVED")) { "invalid target precision" }
                buildJsonObject {
                    put("anchorVersion", 1)
                    put("locator", locatorProjection(target.locator))
                    put("precision", target.precision)
                }
            }))
        }
    }

    fun validQuote(value: String?): Boolean = value != null && value.isNotEmpty() && value.length <= 8192 && ReaderLocatorValidator.isWellFormedUtf16(value)
    // UPDATE hashes the retained legacy field, preserving SQL NULL and an empty string separately.
    fun validRetainedQuote(value: String?): Boolean = value == null || (value.length <= 8192 && ReaderLocatorValidator.isWellFormedUtf16(value))
    fun validNote(value: String?): Boolean = value == null || (value.length <= 1_000_000 && ReaderLocatorValidator.isWellFormedUtf16(value))

    private fun project(value: JsonObject): JsonObject = JsonObject(value.mapValues { (name, element) ->
        when {
            element is JsonObject -> project(element)
            element is JsonArray -> JsonArray(element.map { if (it is JsonObject) project(it) else it })
            name in integerFields -> JsonPrimitive((element as JsonPrimitive).content.toInt().toString())
            name in decimalFields -> {
                val decimal = BigDecimal((element as JsonPrimitive).content)
                require(decimal >= BigDecimal.ZERO && decimal <= BigDecimal.ONE) { "geometry must be finite within 0..1" }
                val rounded = decimal.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros()
                JsonPrimitive(if (rounded.signum() == 0) "0" else rounded.toPlainString())
            }
            else -> element
        }
    })

    private fun validateStrings(element: JsonElement) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                require(ReaderLocatorValidator.isWellFormedUtf16(key)) { "unpaired surrogate in key" }
                validateStrings(value)
            }
            is JsonArray -> element.forEach(::validateStrings)
            is JsonPrimitive -> if (element.isString) require(ReaderLocatorValidator.isWellFormedUtf16(element.content)) { "unpaired surrogate in string" }
        }
    }

    private fun StringBuilder.appendCanonical(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                append('{')
                element.keys.sortedWith(Comparator { a, b -> compareCodePoints(a, b) }).forEachIndexed { index, key ->
                    if (index != 0) append(',')
                    appendString(key)
                    append(':')
                    appendCanonical(element.getValue(key))
                }
                append('}')
            }
            is JsonArray -> {
                append('[')
                element.forEachIndexed { index, value ->
                    if (index != 0) append(',')
                    appendCanonical(value)
                }
                append(']')
            }
            JsonNull -> append("null")
            is JsonPrimitive -> if (element.isString) appendString(element.content) else {
                require(element.content == "true" || element.content == "false" || numberPattern.matches(element.content)) { "invalid JSON primitive" }
                append(element.content)
            }
        }
    }

    private fun StringBuilder.appendString(value: String) {
        require(ReaderLocatorValidator.isWellFormedUtf16(value)) { "unpaired surrogate in string" }
        append(JsonPrimitive(value).toString())
    }

    private fun compareCodePoints(first: String, second: String): Int {
        var a = 0
        var b = 0
        while (a < first.length && b < second.length) {
            val ac = Character.codePointAt(first, a)
            val bc = Character.codePointAt(second, b)
            if (ac != bc) return ac.compareTo(bc)
            a += Character.charCount(ac)
            b += Character.charCount(bc)
        }
        return (first.length - a).compareTo(second.length - b)
    }
}
