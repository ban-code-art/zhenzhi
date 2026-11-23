package com.example.myapplication.domain.quiz

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * quiz_attempt_answers.raw_answer_json 的形状（设计 §15）。
 *
 * - 单选用 {"selectedOptionId":"A"}；
 * - 简答用 {"text":"用户原始回答"}；
 * - 空白未答与空字符串统一为提交时未作答（NULL/空都按未作答处理）。
 */
object QuizRawAnswers {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Parsed(
        val selectedOptionId: String?,
        val text: String?,
        val isJson: Boolean,
    )

    fun choice(selectedOptionId: String): String = buildJsonObject {
        put("selectedOptionId", selectedOptionId)
    }.toString()

    fun essay(text: String): String = buildJsonObject { put("text", text) }.toString()

    fun parse(raw: String?): Parsed {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return Parsed(null, null, isJson = false)
        val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
            ?: return Parsed(null, trimmed, isJson = false)
        return Parsed(
            selectedOptionId = (obj["selectedOptionId"] as? JsonPrimitive)?.contentOrNull,
            text = (obj["text"] as? JsonPrimitive)?.contentOrNull,
            isJson = true,
        )
    }
}
