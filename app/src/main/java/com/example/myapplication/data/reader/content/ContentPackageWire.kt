package com.example.myapplication.data.reader.content

import kotlinx.serialization.json.*

/** Frozen projection of content-package-v2.schema.json; every listed field is required. */
internal data class ContentWireRule(
    val type: String? = null,
    val nullable: Boolean = false,
    val fields: Map<String, ContentWireRule> = emptyMap(),
    val item: ContentWireRule? = null,
    val minLength: Int = 0,
    val minimum: Int = Int.MIN_VALUE,
    val maximum: Int = Int.MAX_VALUE,
    val minItems: Int = 0,
    val pattern: Regex? = null,
    val constant: JsonElement? = null,
    val choices: Set<JsonElement> = emptySet(),
    val uniqueItems: Boolean = false,
) {
    fun check(value: JsonElement, path: String) {
        if (nullable && value === JsonNull) return
        constant?.let { require(value == it) { "$path: expected $it" } }
        if (choices.isNotEmpty()) require(value in choices) { "$path: invalid enum/type" }
        when (type) {
            "object" -> {
                require(value is JsonObject) { "$path: expected object" }
                val unknown = value.keys - fields.keys
                require(unknown.isEmpty()) { "$path.${unknown.firstOrNull()}: unknown field" }
                for ((name, rule) in fields) {
                    require(name in value) { "$path.$name: required field (use explicit null)" }
                    rule.check(value.getValue(name), "$path.$name")
                }
            }
            "array" -> {
                require(value is JsonArray) { "$path: expected array" }
                require(value.size >= minItems) { "$path: insufficient array items" }
                require(!uniqueItems || value.distinct().size == value.size) { "$path: duplicate array item" }
                value.forEachIndexed { index, child -> item!!.check(child, "$path[$index]") }
            }
            "string" -> {
                require(value is JsonPrimitive && value.isString) { "$path: expected string" }
                val text = value.content
                require(contentUtf16Valid(text)) { "$path: invalid UTF-16" }
                require(text.length >= minLength && (minLength == 0 || text.isNotBlank())) { "$path: empty string" }
                require(pattern == null || pattern.matches(text)) { "$path: invalid string pattern" }
            }
            "integer" -> {
                require(value is JsonPrimitive && !value.isString && value.content.matches(Regex("-?(?:0|[1-9][0-9]*)"))) { "$path: expected JSON integer" }
                val number = value.content.toIntOrNull()
                require(number != null && number in minimum..maximum) { "$path: integer outside supported range" }
            }
            "boolean" -> require(value is JsonPrimitive && !value.isString && value.content in listOf("true", "false")) { "$path: expected boolean" }
        }
    }
}

internal fun contentUtf16Valid(text: String): Boolean {
    var i = 0
    while (i < text.length) {
        val c = text[i++]
        if (c.isHighSurrogate()) {
            if (i == text.length || !text[i++].isLowSurrogate()) return false
        } else if (c.isLowSurrogate()) return false
    }
    return true
}

internal fun contentUtf16Boundary(text: String, offset: Int): Boolean =
    offset in 0..text.length && (offset == 0 || offset == text.length || !text[offset].isLowSurrogate() || !text[offset - 1].isHighSurrogate())

internal object ContentPackageWire {
    private val rule0 = ContentWireRule(constant = JsonPrimitive("learning-content-package"))
    private val rule1 = ContentWireRule(constant = JsonPrimitive(2))
    private val rule2 = ContentWireRule(type = "string", pattern = Regex("^[a-z][a-z0-9-]*(?::[a-z0-9-]+)*$"))
    private val rule3 = ContentWireRule(type = "integer", minimum = 1)
    private val rule4 = ContentWireRule(type = "boolean")
    private val rule5 = ContentWireRule(type = "string", minLength = 1)
    private val rule6 = ContentWireRule(type = "integer", minimum = 0)
    private val rule7 = ContentWireRule(type = "string", nullable = true, pattern = Regex("^(?!/)(?!.*(?:^|/)\\.\\.?(?:/|$))(?!.*[\\\\:%?#])[A-Za-z0-9_./-]+$"))
    private val rule8 = ContentWireRule(type = "array", item = rule2, minItems = 1, uniqueItems = true)
    private val rule9 = ContentWireRule(choices = setOf(JsonPrimitive("DRAFT"), JsonPrimitive("REVIEWED"), JsonPrimitive("APPROVED")))
    private val rule10 = ContentWireRule(type = "string", nullable = true, minLength = 1)
    private val rule11 = ContentWireRule(type = "string", nullable = true, pattern = Regex("^[0-9a-f]{64}$"))
    private val rule12 = ContentWireRule(type = "object", fields = linkedMapOf("status" to rule9, "reviewer" to rule10, "reviewedAtUtc" to rule10, "recordPath" to rule7, "recordSha256" to rule11))
    private val rule13 = ContentWireRule(choices = setOf(JsonPrimitive("ENTRY"), JsonPrimitive("BASIC"), JsonPrimitive("ADVANCED")))
    private val rule14 = ContentWireRule(type = "array", item = rule5, minItems = 1)
    private val rule15 = ContentWireRule(type = "array", item = rule5, uniqueItems = true)
    private val rule16 = ContentWireRule(type = "object", fields = linkedMapOf("difficulty" to rule13, "objectives" to rule14, "prerequisiteChapterKeys" to rule15, "studyHint" to rule5))
    private val rule17 = ContentWireRule(type = "object", fields = linkedMapOf("bookKey" to rule2, "title" to rule5, "summary" to rule5, "orderIndex" to rule6, "coverPath" to rule7, "chapterKeys" to rule8, "review" to rule12, "learning" to rule16, "updatedAtUtc" to rule5))
    private val rule18 = ContentWireRule(type = "array", item = rule17, minItems = 1)
    private val rule19 = ContentWireRule(type = "string", pattern = Regex("^(?!/)(?!.*(?:^|/)\\.\\.?(?:/|$))(?!.*[\\\\:%?#])[A-Za-z0-9_./-]+$"))
    private val rule20 = ContentWireRule(type = "string", pattern = Regex("^[0-9a-f]{64}$"))
    private val rule21 = ContentWireRule(choices = setOf(JsonPrimitive("LEGACY_V1"), JsonPrimitive("CONTENT_V2")))
    private val rule22 = ContentWireRule(type = "array", item = rule2, minItems = 1)
    private val rule23 = ContentWireRule(choices = setOf(JsonPrimitive("SEQUENCE"), JsonPrimitive("DEFINITION"), JsonPrimitive("ENUMERATION")))
    private val rule24 = ContentWireRule(type = "array", item = rule5)
    private val rule25 = ContentWireRule(type = "object", fields = linkedMapOf("sourceKey" to rule2, "blockId" to rule5, "quote" to rule5, "canonicalStart" to rule6, "canonicalEnd" to rule6))
    private val rule26 = ContentWireRule(type = "array", item = rule25)
    private val rule27 = ContentWireRule(type = "object", fields = linkedMapOf("factKey" to rule2, "kind" to rule23, "prompt" to rule5, "answerParts" to rule14, "distractors" to rule24, "analysis" to rule5, "evidence" to rule26, "review" to rule12))
    private val rule28 = ContentWireRule(type = "array", item = rule27)
    private val rule29 = ContentWireRule(type = "object", fields = linkedMapOf("knowledgePointKey" to rule2, "objective" to rule5, "blockIds" to rule14, "sourceKeys" to rule22, "facts" to rule28))
    private val rule30 = ContentWireRule(type = "array", item = rule29)
    private val rule31 = ContentWireRule(type = "object", fields = linkedMapOf("bookKey" to rule2, "chapterKey" to rule2, "orderIndex" to rule6, "title" to rule5, "summary" to rule5, "bodyPath" to rule19, "bodySha256" to rule20, "contentRevision" to rule20, "revisionAlgorithm" to rule21, "knowledgePoints" to rule30, "review" to rule12, "learning" to rule16, "updatedAtUtc" to rule5))
    private val rule32 = ContentWireRule(type = "array", item = rule31, minItems = 1)
    private val rule33 = ContentWireRule(type = "integer", minimum = 1, maximum = 9999)
    private val rule34 = ContentWireRule(type = "object", fields = linkedMapOf("sourceKey" to rule2, "title" to rule5, "authors" to rule14, "edition" to rule5, "publisher" to rule5, "year" to rule33, "identifier" to rule10, "extent" to rule5, "license" to rule5))
    private val rule35 = ContentWireRule(type = "array", item = rule34)
    private val rule36 = ContentWireRule(choices = setOf(JsonPrimitive("CHOICE"), JsonPrimitive("ESSAY")))
    private val rule37 = ContentWireRule(type = "object", fields = linkedMapOf("key" to rule5, "weight" to rule3, "accepted" to rule24, "negatedBy" to rule24))
    private val rule38 = ContentWireRule(type = "array", item = rule37)
    private val rule39 = ContentWireRule(choices = setOf(JsonPrimitive("BASIC"), JsonPrimitive("UNDERSTAND"), JsonPrimitive("APPLY")))
    private val rule40 = ContentWireRule(type = "array", item = rule25, minItems = 1)
    private val rule41 = ContentWireRule(type = "object", fields = linkedMapOf("questionKey" to rule2, "chapterKey" to rule2, "knowledgePointKeys" to rule22, "type" to rule36, "stem" to rule5, "options" to rule24, "answer" to rule14, "rubric" to rule38, "analysis" to rule5, "score" to rule3, "difficulty" to rule39, "evidenceRefs" to rule40, "review" to rule12))
    private val rule42 = ContentWireRule(type = "array", item = rule41)
    private val rule43 = ContentWireRule(choices = setOf(JsonPrimitive("BODY"), JsonPrimitive("COVER"), JsonPrimitive("IMAGE"), JsonPrimitive("STYLE"), JsonPrimitive("REVIEW")))
    private val rule44 = ContentWireRule(type = "integer", minimum = 0, maximum = 8388608)
    private val rule45 = ContentWireRule(type = "object", fields = linkedMapOf("path" to rule19, "kind" to rule43, "sha256" to rule20, "bytes" to rule44, "license" to rule5, "altText" to rule10))
    private val rule46 = ContentWireRule(type = "array", item = rule45)
    private val rule47 = ContentWireRule(type = "object", fields = linkedMapOf("format" to rule0, "schemaVersion" to rule1, "packageId" to rule2, "packageVersion" to rule3, "testOnly" to rule4, "books" to rule18, "chapters" to rule32, "sources" to rule35, "questions" to rule42, "resources" to rule46))
    val v2 = rule47
    private val legacyString = ContentWireRule(type = "string", minLength = 1)
    private val legacyKey = ContentWireRule(type = "string", pattern = Regex("^[a-z][a-z0-9-]*(?::[a-z0-9-]+)*$"))
    private val legacyChapter = ContentWireRule(type = "object", fields = linkedMapOf(
        "key" to legacyKey, "order" to ContentWireRule(type = "integer", minimum = 0),
        "title" to legacyString, "summary" to legacyString, "body" to legacyString,
    ))
    val v1 = ContentWireRule(type = "object", fields = linkedMapOf(
        "schemaVersion" to ContentWireRule(constant = JsonPrimitive(1)), "packageId" to legacyKey,
        "version" to ContentWireRule(type = "integer", minimum = 1),
        "reviewStatus" to ContentWireRule(constant = JsonPrimitive("DEVELOPMENT_SAMPLE")),
        "chapters" to ContentWireRule(type = "array", item = legacyChapter, minItems = 1),
    ))
}
