package com.example.myapplication.data.test

import com.example.myapplication.data.local.entity.QuestionEntity
import com.example.myapplication.data.reader.content.ContentPackageQuestionGrading
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import java.text.Normalizer

/** 原随机卷：20 选择 ×2 +10 判断 ×2 +5 填空 ×4 +5 应用 ×4 =100。 */
object OrdinaryTestRules {
    val blueprint: List<Pair<String, Int>> = listOf("CHOICE" to 20, "JUDGE" to 10, "FILL" to 5, "ESSAY" to 5)
    const val durationSeconds = 2400

    fun validPaper(questions: List<QuestionEntity>): Boolean =
        questions.size == 40 && questions.map { it.id }.distinct().size == 40 &&
            questions.map { it.type } == blueprint.flatMap { (type, count) -> List(count) { type } } &&
            questions.all(::validQuestion) &&
            questions.sumOf { it.score } == 100

    fun validQuestion(question: QuestionEntity): Boolean {
        if (question.id <= 0 || question.stem.isBlank() || question.analysis.isBlank() ||
            question.score != if (question.type in setOf("CHOICE", "JUDGE")) 2 else 4) return false
        return when (question.type) {
            "CHOICE" -> choiceOptions(question.options).let { options ->
                options.size == 4 && options.map { it.first } == listOf("A", "B", "C", "D") &&
                    options.all { it.second.isNotBlank() } && choiceAnswer(question.answer) in options.map { it.first }
            }
            "JUDGE" -> judge(question.answer) != null
            "FILL" -> {
                val points = arrayStrings(question.answer)
                points.isNotEmpty() && points.all(String::isNotBlank) &&
                    (question.synonyms == null || runCatching {
                        val synonyms = Json.parseToJsonElement(question.synonyms) as JsonArray
                        synonyms.size == points.size && synonyms.all { element ->
                            element is JsonArray && element.all { it is JsonPrimitive && it.content.isNotBlank() }
                        }
                    }.getOrDefault(false))
            }
            "ESSAY" -> arrayStrings(question.answer).let {
                it.isNotEmpty() && it.all(String::isNotBlank) && it.distinct().size == it.size &&
                    (!ContentPackageQuestionGrading.hasEnvelope(question) || runCatching { ContentPackageQuestionGrading.decode(question) }.isSuccess)
            }
            else -> false
        }
    }

    fun choiceOptions(raw: String?): List<Pair<String, String>> = runCatching {
        when (val value = Json.parseToJsonElement(raw.orEmpty())) {
            is JsonObject -> value.entries.map {
                val text = it.value as JsonPrimitive
                require(text.isString)
                it.key to text.content
            }.sortedBy { it.first }
            is JsonArray -> value.mapIndexed { index, element ->
                if (element is JsonObject) {
                    val key = (element["key"] ?: element["id"] ?: element["letter"]) as? JsonPrimitive
                    val text = (element["text"] ?: element["label"]) as? JsonPrimitive
                    require(key == null || key.isString)
                    require(text != null && text.isString)
                    (key?.content ?: ('A' + index).toString()) to text?.content.orEmpty()
                } else {
                    require(element is JsonPrimitive && element.isString)
                    ('A' + index).toString() to element.content
                }
            }
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    fun grade(question: QuestionEntity, answer: String?): Int {
        val text = answer?.trim().orEmpty()
        if (text.isEmpty()) return 0
        return when (question.type) {
            "CHOICE" -> if (choiceAnswer(text) != null && choiceAnswer(text) == choiceAnswer(question.answer)) question.score else 0
            "JUDGE" -> if (judge(text) != null && judge(text) == judge(question.answer)) question.score else 0
            "FILL" -> {
                val points = arrayStrings(question.answer)
                if (points.isEmpty()) return 0
                val supplied = if (text.startsWith("[")) arrayStrings(text) else text.split(Regex("[;；\\n|]")).map(String::trim)
                val synonyms = runCatching { Json.parseToJsonElement(question.synonyms.orEmpty()) as JsonArray }.getOrNull()
                val matched = points.indices.count { index ->
                    val expected = normalize(points[index])
                    val value = normalize(supplied.getOrNull(index).orEmpty())
                    val alternatives = (synonyms?.getOrNull(index) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
                    value.isNotEmpty() && (value == expected || alternatives.any { normalize(it) == value })
                }
                question.score * matched / points.size
            }
            "ESSAY" -> {
                if (ContentPackageQuestionGrading.hasEnvelope(question)) return ContentPackageQuestionGrading.weightedGrade(question, text)
                val points = arrayStrings(question.answer)
                if (points.isEmpty()) return 0
                val normalized = normalize(text)
                val matched = points.count { point ->
                    val target = normalize(point)
                    target.isNotEmpty() && matchesPositivePoint(normalized, target)
                }
                question.score * matched / points.size
            }
            else -> 0
        }.coerceIn(0, question.score.coerceAtLeast(0))
    }

    fun arrayStrings(raw: String): List<String> = runCatching {
        (Json.parseToJsonElement(raw) as JsonArray).map {
            require(it is JsonPrimitive && it.isString)
            it.content
        }
    }.getOrDefault(emptyList())

    fun choiceAnswer(raw: String): String? {
        val value = raw.trim()
        val decoded = if (value.startsWith('"')) runCatching {
            (Json.parseToJsonElement(value) as JsonPrimitive).takeIf { it.isString }?.content
        }.getOrNull() ?: return null else value
        return normalize(decoded).uppercase().trimEnd('.', '。').takeIf { it in listOf("A", "B", "C", "D") }
    }

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase().replace(Regex("\\s+"), "")

    private fun judge(value: String): Boolean? = when (normalize(value).trim('"')) {
        "true", "对", "正确", "是", "1" -> true
        "false", "错", "错误", "否", "0" -> false
        else -> null
    }

    internal fun matchesPositivePoint(answer: String, point: String): Boolean {
        var start = answer.indexOf(point)
        while (start >= 0) {
            val prefix = answer.substring((start - 6).coerceAtLeast(0), start)
            if (!listOf("不", "没", "无", "非", "禁止", "不要", "不应", "不应当", "不能", "不必", "不需要", "无需", "无须", "无法", "不会").any(prefix::endsWith)) return true
            start = answer.indexOf(point, start + point.length)
        }
        return false
    }
}
