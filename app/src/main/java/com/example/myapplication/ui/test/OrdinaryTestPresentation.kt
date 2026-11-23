package com.example.myapplication.ui.test

import com.example.myapplication.data.local.entity.QuestionEntity
import com.example.myapplication.data.reader.content.ContentPackageQuestionGrading
import com.example.myapplication.data.test.OrdinaryQuestionProvenance
import com.example.myapplication.data.test.OrdinaryTestRules
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

internal fun ordinaryTypeLabel(type: String): String = when (type) {
    "CHOICE" -> "选择题"
    "JUDGE" -> "判断题"
    "FILL" -> "填空题"
    "ESSAY" -> "应用题"
    else -> type
}

internal fun ordinaryOriginSummary(questionId: Long, provenance: OrdinaryQuestionProvenance?): String = when {
    provenance == null -> "内置题 #$questionId"
    provenance.sourceOrigin == "member3" -> {
        val src = provenance.sourceId?.let { "原题 #$it" } ?: "来件"
        "#${provenance.designId} · 来件收录（$src）"
    }
    else -> "#${provenance.designId} · 原创扩展"
}

internal fun ordinaryReviewBadge(provenance: OrdinaryQuestionProvenance?): String? = when (provenance?.reviewStatus) {
    "clinical-review" -> "待临床终审"
    else -> null
}

internal fun ordinaryFillBlankCount(question: QuestionEntity): Int =
    OrdinaryTestRules.arrayStrings(question.answer).size.coerceAtLeast(1)

internal fun decodeOrdinaryFillBlanks(raw: String?, blankCount: Int): List<String> {
    val count = blankCount.coerceAtLeast(1)
    val text = raw?.trim().orEmpty()
    val parsed = when {
        text.isEmpty() -> emptyList()
        text.startsWith("[") -> OrdinaryTestRules.arrayStrings(text)
        count == 1 -> listOf(text)
        else -> text.split(Regex("[;；\\n|]")).map(String::trim)
    }
    return List(count) { index -> parsed.getOrElse(index) { "" } }
}

internal fun encodeOrdinaryFillBlanks(blanks: List<String>): String {
    if (blanks.all { it.isBlank() }) return ""
    if (blanks.size == 1) {
        val single = blanks[0].trim()
        return if (single.startsWith("[")) JsonArray(listOf(JsonPrimitive(single))).toString() else single
    }
    return JsonArray(blanks.map { JsonPrimitive(it.trim()) }).toString()
}

internal fun hasEffectiveOrdinaryAnswer(raw: String?): Boolean {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return false
    return if (text.startsWith("[")) {
        OrdinaryTestRules.arrayStrings(text).any { it.isNotBlank() }
    } else {
        true
    }
}

internal fun formatOrdinaryUserAnswer(question: QuestionEntity, raw: String?): String {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return "未作答"
    return when (question.type) {
        "CHOICE" -> {
            val letter = OrdinaryTestRules.choiceAnswer(text) ?: text
            val label = OrdinaryTestRules.choiceOptions(question.options).firstOrNull { it.first == letter }?.second
            if (label != null) "$letter. $label" else letter
        }
        "JUDGE" -> when (OrdinaryTestRules.normalize(text).trim('"')) {
            "true", "对", "正确", "是", "1" -> "对"
            "false", "错", "错误", "否", "0" -> "错"
            else -> text
        }
        "FILL" -> {
            val parts = decodeOrdinaryFillBlanks(text, ordinaryFillBlankCount(question))
            if (parts.all { it.isBlank() }) "未作答"
            else if (parts.size == 1) parts[0]
            else parts.mapIndexed { idx, value -> "第${idx + 1}空：${value.ifBlank { "未填" }}" }.joinToString("；")
        }
        else -> text
    }
}

internal fun formatOrdinaryOfficialAnswer(question: QuestionEntity): String = when (question.type) {
    "CHOICE" -> {
        val letter = OrdinaryTestRules.choiceAnswer(question.answer) ?: question.answer.trim()
        val label = OrdinaryTestRules.choiceOptions(question.options).firstOrNull { it.first == letter }?.second
        if (label != null) "$letter. $label" else letter
    }
    "JUDGE" -> when (OrdinaryTestRules.normalize(question.answer).trim('"')) {
        "true", "对", "正确", "是", "1" -> "对"
        "false", "错", "错误", "否", "0" -> "错"
        else -> question.answer
    }
    "FILL" -> OrdinaryTestRules.arrayStrings(question.answer).ifEmpty { listOf(question.answer) }.joinToString(" / ")
    "ESSAY" -> if (ContentPackageQuestionGrading.hasEnvelope(question)) {
        ContentPackageQuestionGrading.displayLines(question).joinToString("；")
    } else {
        OrdinaryTestRules.arrayStrings(question.answer).ifEmpty { listOf(question.answer) }.joinToString("；")
    }
    else -> question.answer
}

internal fun formatOrdinaryFillSynonyms(question: QuestionEntity): List<String> {
    if (question.type != "FILL") return emptyList()
    val blanks = OrdinaryTestRules.arrayStrings(question.answer)
    val synMatrix = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(question.synonyms.orEmpty()) as JsonArray
    }.getOrNull() ?: return emptyList()
    return blanks.indices.mapNotNull { idx ->
        val expected = blanks[idx]
        val alternatives = (synMatrix.getOrNull(idx) as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?.filter { it.isNotBlank() && it != expected }
            .orEmpty()
        if (alternatives.isEmpty()) null else "第${idx + 1}空同义词：${alternatives.joinToString(" / ")}"
    }
}

internal fun formatOrdinaryCountdown(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    return "%02d:%02d".format(safe / 60, safe % 60)
}
