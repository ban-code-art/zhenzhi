package com.example.myapplication.domain.quiz

import com.example.myapplication.domain.reader.ReaderLocatorCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** 随堂测 JSON 编解码异常（持久化/模型输出格式错误时抛出）。 */
class QuizCodecException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * v6 DDL 三个 JSON 列的形状实现：
 *  - `quiz_items.question_snapshot_json`：完整题干/options/答案/score/rubric/templateVersion
 *  - `quiz_items.rubric_json`：要点数组
 *  - `quiz_items.evidence_json`：绑定后的出处数组（Locator/quote/revision 均由应用补全）
 *
 * 解码对未知字段宽容（便于向后兼容），但必需字段类型不符会抛 [QuizCodecException]，
 * 不会被静默当成合法历史数据。
 */
object QuizCodecs {
    private const val SCHEMA_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = false
    }

    // ---- question_snapshot_json ----

    fun encodeItemSnapshot(item: QuizItemSnapshot): String = buildJsonObject {
        put("schemaVersion", SCHEMA_VERSION)
        put("ordinal", item.ordinal)
        put("type", item.type.name)
        put("stem", item.stem)
        if (item.options.isNotEmpty()) {
            put("options", buildJsonArray { item.options.forEach { add(it) } })
        }
        when {
            item.type == QuizItemType.CHOICE && item.answer.size == 1 -> put("answer", item.answer.first())
            else -> put("answer", buildJsonArray { item.answer.forEach { add(it) } })
        }
        put("score", item.score)
        put("analysis", item.analysis)
        put("knowledgePoint", item.knowledgePoint)
        put("difficulty", item.difficulty.name)
        put("rubric", encodeRubricElement(item.rubric))
        put("evidenceIds", buildJsonArray { item.evidenceIds.forEach { add(it) } })
        put("templateVersion", item.templateVersion)
        item.itemId?.let { put("itemId", it) }
    }.toString()

    fun decodeItemSnapshot(text: String): QuizItemSnapshot {
        val root = parseObject(text, "question_snapshot_json")
        val type = enumOf<QuizItemType>(root, "type", "question_snapshot_json")
        val answerElement = root["answer"]
        val answers = when (answerElement) {
            is JsonPrimitive -> listOf(answerElement.contentOrNull.orEmpty()).filter { it.isNotEmpty() }
            is JsonArray -> answerElement.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }.filter { it.isNotEmpty() }
            else -> throw QuizCodecException("question_snapshot_json answer must be string or array")
        }
        return QuizItemSnapshot(
            ordinal = root.intOrNull("ordinal") ?: 0,
            type = type,
            stem = root.string("stem") ?: throw QuizCodecException("question_snapshot_json missing stem"),
            options = root.stringList("options"),
            answer = answers,
            score = root.intOrNull("score") ?: throw QuizCodecException("question_snapshot_json missing score"),
            analysis = root.string("analysis").orEmpty(),
            knowledgePoint = root.string("knowledgePoint").orEmpty(),
            difficulty = runCatching { enumOf<QuizDifficulty>(root, "difficulty", "question_snapshot_json") }
                .getOrDefault(QuizDifficulty.BASIC),
            rubric = root["rubric"]?.let { decodeRubricElement(it, "question_snapshot_json.rubric") }.orEmpty(),
            evidenceIds = root.stringList("evidenceIds"),
            templateVersion = root.string("templateVersion").orEmpty(),
            itemId = root.string("itemId"),
        )
    }

    // ---- rubric_json ----

    fun encodeRubric(points: List<RubricPoint>): String = encodeRubricElement(points).toString()

    fun decodeRubric(text: String): List<RubricPoint> {
        val element = runCatching { json.parseToJsonElement(text) }
            .getOrElse { throw QuizCodecException("rubric_json is not valid JSON", it) }
        return decodeRubricElement(element, "rubric_json")
    }

    private fun encodeRubricElement(points: List<RubricPoint>): JsonArray = buildJsonArray {
        points.forEach { point ->
            addJsonObject {
                put("key", point.key)
                put("weight", point.weight)
                put("accepted", buildJsonArray { point.accepted.forEach { add(it) } })
                if (point.negatedBy.isNotEmpty()) {
                    put("negatedBy", buildJsonArray { point.negatedBy.forEach { add(it) } })
                }
            }
        }
    }

    private fun decodeRubricElement(element: JsonElement, context: String): List<RubricPoint> {
        val array = element as? JsonArray ?: throw QuizCodecException("$context must be an array")
        return array.mapIndexed { index, raw ->
            val obj = raw as? JsonObject ?: throw QuizCodecException("$context[$index] must be an object")
            RubricPoint(
                key = obj.string("key") ?: throw QuizCodecException("$context[$index] missing key"),
                weight = obj.intOrNull("weight") ?: throw QuizCodecException("$context[$index] missing weight"),
                accepted = obj.stringList("accepted"),
                negatedBy = obj.stringList("negatedBy"),
            )
        }
    }

    // ---- evidence_json ----

    fun encodeEvidence(refs: List<QuizEvidenceRef>): String = buildJsonArray {
        refs.forEach { ref ->
            addJsonObject {
                put("evidenceId", ref.evidenceId)
                put("documentId", ref.documentId)
                put("sectionId", ref.sectionId)
                put("contentRevision", ref.contentRevision)
                put("locator", json.parseToJsonElement(ReaderLocatorCodec.encode(ref.locator)))
                put("quote", ref.quote)
                put("quoteSha256", ref.quoteSha256)
                put("sourceTitleSnapshot", ref.sourceTitleSnapshot)
            }
        }
    }.toString()

    fun decodeEvidence(text: String): List<QuizEvidenceRef> {
        val element = runCatching { json.parseToJsonElement(text) }
            .getOrElse { throw QuizCodecException("evidence_json is not valid JSON", it) }
        val array = element as? JsonArray ?: throw QuizCodecException("evidence_json must be an array")
        return array.mapIndexed { index, raw ->
            val obj = raw as? JsonObject ?: throw QuizCodecException("evidence_json[$index] must be an object")
            val locatorElement = obj["locator"]
                ?: throw QuizCodecException("evidence_json[$index] missing locator")
            QuizEvidenceRef(
                evidenceId = obj.string("evidenceId") ?: throw QuizCodecException("evidence_json[$index] missing evidenceId"),
                documentId = obj.string("documentId").orEmpty(),
                sectionId = obj.string("sectionId").orEmpty(),
                contentRevision = obj.string("contentRevision").orEmpty(),
                locator = ReaderLocatorCodec.decode(locatorElement.toString()),
                quote = obj.string("quote") ?: throw QuizCodecException("evidence_json[$index] missing quote"),
                quoteSha256 = obj.string("quoteSha256").orEmpty(),
                sourceTitleSnapshot = obj.string("sourceTitleSnapshot").orEmpty(),
            )
        }
    }

    // ---- helpers ----

    private fun parseObject(text: String, context: String): JsonObject = runCatching {
        json.parseToJsonElement(text).jsonObject
    }.getOrElse { throw QuizCodecException("$context is not a JSON object", it) }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.intOrNull(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.stringList(name: String): List<String> =
        (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    private inline fun <reified T : Enum<T>> enumOf(root: JsonObject, name: String, context: String): T {
        val raw = root.string(name) ?: throw QuizCodecException("$context missing $name")
        return enumValues<T>().firstOrNull { it.name == raw }
            ?: throw QuizCodecException("$context has unknown $name=$raw")
    }
}
