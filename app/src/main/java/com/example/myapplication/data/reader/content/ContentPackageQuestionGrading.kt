package com.example.myapplication.data.reader.content

import com.example.myapplication.data.local.entity.QuestionEntity
import com.example.myapplication.data.test.OrdinaryTestRules
import kotlinx.serialization.json.*

/** Persisted lexical scoring contract, distinct from professional review or model grading. */
object ContentPackageQuestionGrading {
    data class Point(val key: String, val weight: Int, val accepted: List<String>, val negatedBy: List<String>)
    private const val FORMAT = "content-package-rubric"
    private const val MAX_BYTES = 65536
    private val fields = setOf("format","schemaVersion","score","answerSha256","rubric")

    fun encode(question: JsonObject): String {
        require(question["type"] == JsonPrimitive("ESSAY"))
        val score = integer(question["score"])
        val answer = requireNotNull(question["answer"]).toString()
        validateAnswer(answer)
        val rubric = requireNotNull(question["rubric"])
        points(rubric,score)
        val result = ContentPackageCodec.canonicalJson(buildJsonObject {
            put("format",FORMAT);put("schemaVersion",1);put("score",score)
            put("answerSha256",ContentPackageCodec.sha256(answer.toByteArray(Charsets.UTF_8)))
            put("rubric",rubric)
        })
        require(result.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        return result
    }

    /** Throws on malformed/rebound course metadata; callers must never fall back to equal weights. */
    fun decode(question: QuestionEntity): List<Point> {
        require(question.type == "ESSAY")
        val raw = requireNotNull(question.synonyms)
        checkSizeAndDepth(raw)
        val root = Json.parseToJsonElement(raw) as? JsonObject ?: error("rubric: expected object")
        // Storage has one canonical encoding: this also rejects duplicate keys and alternate numeric forms.
        require(ContentPackageCodec.canonicalJson(root) == raw)
        require(root.keys == fields && root["format"] == JsonPrimitive(FORMAT) && root["schemaVersion"] == JsonPrimitive(1))
        require(integer(root["score"]) == question.score)
        validateAnswer(question.answer)
        require(root["answerSha256"] == JsonPrimitive(ContentPackageCodec.sha256(question.answer.toByteArray(Charsets.UTF_8))))
        return points(root["rubric"],question.score)
    }

    fun hasEnvelope(question: QuestionEntity): Boolean = question.type == "ESSAY" &&
        (question.origin == "CONTENT_PACKAGE" || question.synonyms?.trimStart()?.let { !it.startsWith("[") } == true)

    fun displayLines(question: QuestionEntity): List<String> = if (!hasEnvelope(question))
        OrdinaryTestRules.arrayStrings(question.answer)
    else runCatching { decode(question).map {
        "${it.accepted.first()}（${it.weight}分）" +
            if (it.accepted.size > 1) "；也接受：${it.accepted.drop(1).joinToString(" / ")}" else ""
    } }
        .getOrElse { listOf("评分标准数据无效，无法评分") }

    fun weightedGrade(question: QuestionEntity, answer: String): Int = runCatching {
        val normalized = OrdinaryTestRules.normalize(answer)
        decode(question).sumOf { point ->
            val accepted = point.accepted.any { OrdinaryTestRules.matchesPositivePoint(normalized,OrdinaryTestRules.normalize(it)) }
            val negated = point.negatedBy.any { normalized.contains(OrdinaryTestRules.normalize(it)) }
            if(accepted && !negated) point.weight else 0
        }
    }.getOrDefault(0)

    private fun integer(element: JsonElement?): Int {
        require(element is JsonPrimitive && !element.isString)
        val value = requireNotNull(element.intOrNull)
        require(element.content == value.toString() && value in 1..1000)
        return value
    }
    private fun strings(element: JsonElement?, allowEmpty: Boolean): List<String> {
        require(element is JsonArray && element.size <= 100 && (allowEmpty || element.isNotEmpty()))
        return element.map {
            require(it is JsonPrimitive && it.isString && it.content.isNotBlank() && it.content.length <= 4096)
            require(OrdinaryTestRules.normalize(it.content).isNotEmpty())
            it.content
        }.also { require(it.distinct().size == it.size) }
    }
    private fun points(element: JsonElement?, score: Int): List<Point> {
        require(score in 1..1000 && element is JsonArray && element.size in 1..100)
        var textBytes = 0L
        val result = element.map { item ->
            require(item is JsonObject && item.keys == setOf("key","weight","accepted","negatedBy"))
            val key = item["key"]
            require(key is JsonPrimitive && key.isString && key.content.isNotBlank() && key.content.length <= 512)
            val point = Point(key.content,integer(item["weight"]),strings(item["accepted"],false),strings(item["negatedBy"],true))
            textBytes += key.content.toByteArray(Charsets.UTF_8).size
            (point.accepted + point.negatedBy).forEach { textBytes += it.toByteArray(Charsets.UTF_8).size }
            require(textBytes <= MAX_BYTES)
            point
        }
        require(result.map { it.key }.distinct().size == result.size && result.sumOf { it.weight.toLong() } == score.toLong())
        return result
    }
    private fun validateAnswer(raw: String) {
        checkSizeAndDepth(raw)
        strings(Json.parseToJsonElement(raw),false)
    }
    private fun checkSizeAndDepth(raw: String) {
        require(raw.length <= MAX_BYTES && raw.toByteArray(Charsets.UTF_8).size in 1..MAX_BYTES)
        var depth=0;var quoted=false;var escaped=false
        raw.forEach { ch ->
            if(quoted) { if(escaped) escaped=false else if(ch=='\\') escaped=true else if(ch=='"') quoted=false }
            else when(ch) { '"'->quoted=true; '{','['->{depth++;require(depth<=16)}; '}',']'->{depth--;require(depth>=0)} }
        }
        require(!quoted && !escaped && depth==0)
    }
}
