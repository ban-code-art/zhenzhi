package com.example.myapplication.data.quiz

import com.example.myapplication.domain.quiz.QuizGradingMethod
import com.example.myapplication.domain.quiz.QuizGradingStatus
import com.example.myapplication.domain.quiz.QuizItemGrade
import com.example.myapplication.domain.quiz.QuizItemSnapshot
import com.example.myapplication.domain.quiz.QuizItemType
import com.example.myapplication.domain.quiz.QuizPaperSnapshot
import com.example.myapplication.domain.quiz.QuizRawAnswers
import com.example.myapplication.domain.quiz.QuizText
import com.example.myapplication.domain.quiz.RubricPoint

/**
 * 本地评分（第四期详细设计 §8；07-随堂测完整结构样例 §3；FX-16/Q-04）。
 *
 * 规则：
 *  - 单选按字母归一精确匹配（"a"/"A."/"选项A" → "A"）；未作答按 0 分；
 *  - 简答按 rubric 要点加权：accepted 同义表达/实体要点命中得分；
 *  - **先判否定与冲突**：出现 negatedBy 或要点前的否定表述（“不满足X”）不给该要点分；
 *  - 部分命中给参考分并列出命中/缺失要点；
 *  - 全不命中或存在否定/冲突 → PENDING_SELF_REVIEW，score = NULL（不能算 0）；
 *  - 未作答与待评分开：前者 UNANSWERED+0 分，后者 PENDING+NULL。
 */
class LocalRubricGrader {

    /** 否定判断窗口：要点前最多看 6 个归一化字符，避免“不需要/不满足/没有”等漏判。 */
    private val negationWindow = 6

    private val negationCues = listOf(
        "不满足", "不使用", "不需要", "不能", "无法", "不要", "不必", "不会",
        "不应", "并非", "不是", "没有", "未", "不", "没", "无", "非", "勿", "禁止",
    )

    private companion object {
        /** 否定词与要点之间允许的最大间隔（“并不完全满足X”=2）。 */
        const val NEGATION_MAX_GAP = 2
    }

    fun grade(item: QuizItemSnapshot, rawAnswerJson: String?): QuizItemGrade {
        val parsed = QuizRawAnswers.parse(rawAnswerJson)
        return when (item.type) {
            QuizItemType.CHOICE -> gradeChoice(item, parsed.selectedOptionId ?: parsed.text)
            QuizItemType.ESSAY -> gradeEssay(item, parsed.text ?: parsed.selectedOptionId)
        }
    }

    fun gradePaper(paper: QuizPaperSnapshot, rawAnswers: Map<Int, String?>): List<QuizItemGrade> =
        paper.items.map { item -> grade(item, rawAnswers[item.ordinal]) }

    // ---- 单选 ----

    /** 单选：字母归一化后精确匹配；无法识别的输入按 0 分（不是待评）。 */
    fun gradeChoice(item: QuizItemSnapshot, rawAnswer: String?): QuizItemGrade {
        val normalized = normalizeChoiceAnswer(rawAnswer)
        val correctLetter = item.choiceAnswerLetter
        if (normalized == null) {
            val rawText = rawAnswer?.trim().orEmpty()
            return if (rawText.isEmpty()) {
                QuizItemGrade(
                    itemOrdinal = item.ordinal,
                    itemType = QuizItemType.CHOICE,
                    normalizedAnswer = "",
                    score = 0,
                    isCorrect = false,
                    status = QuizGradingStatus.UNANSWERED,
                    gradingMethod = QuizGradingMethod.CHOICE_EXACT,
                    feedback = "未作答，按 0 分计",
                )
            } else {
                QuizItemGrade(
                    itemOrdinal = item.ordinal,
                    itemType = QuizItemType.CHOICE,
                    normalizedAnswer = rawText,
                    score = 0,
                    isCorrect = false,
                    status = QuizGradingStatus.GRADED,
                    gradingMethod = QuizGradingMethod.CHOICE_EXACT,
                    feedback = "无法识别的选项答案：$rawText（正确答案 $correctLetter）",
                )
            }
        }
        val isCorrect = normalized == correctLetter
        return QuizItemGrade(
            itemOrdinal = item.ordinal,
            itemType = QuizItemType.CHOICE,
            normalizedAnswer = normalized,
            score = if (isCorrect) item.score else 0,
            isCorrect = isCorrect,
            status = QuizGradingStatus.GRADED,
            gradingMethod = QuizGradingMethod.CHOICE_EXACT,
            matchedPoints = if (isCorrect) listOf(normalized) else emptyList(),
            missedPoints = if (isCorrect) emptyList() else listOf(correctLetter.orEmpty()),
            feedback = if (isCorrect) {
                "选择正确：$normalized"
            } else {
                "选择错误：作答 $normalized，正确答案 $correctLetter"
            },
        )
    }

    /** 归一化：只接受唯一一个字母（A–Z）；"a"、"A."、"选项A"、"（A）" 都归一为 "A"。 */
    fun normalizeChoiceAnswer(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val letters = trimmed.uppercase().filter { it in 'A'..'Z' }
        return if (letters.length == 1) letters else null
    }

    // ---- 简答 ----

    fun gradeEssay(item: QuizItemSnapshot, text: String?): QuizItemGrade {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) {
            return QuizItemGrade(
                itemOrdinal = item.ordinal,
                itemType = QuizItemType.ESSAY,
                normalizedAnswer = "",
                score = 0,
                isCorrect = false,
                status = QuizGradingStatus.UNANSWERED,
                gradingMethod = QuizGradingMethod.LOCAL_RUBRIC_V1,
                missedPoints = item.rubric.map { it.key },
                feedback = "未作答，按 0 分计",
                rubricVersion = QuizTemplate.RUBRIC_VERSION,
            )
        }
        val normalizedText = QuizText.normalize(raw)
        val matched = mutableListOf<String>()
        val missed = mutableListOf<String>()
        val conflicted = mutableListOf<String>()
        var score = 0
        item.rubric.forEach { point ->
            when (evaluatePoint(point, normalizedText)) {
                PointVerdict.HIT -> {
                    matched += point.key
                    score += point.weight
                }

                PointVerdict.NEGATED -> conflicted += point.key
                PointVerdict.MISS -> missed += point.key
            }
        }
        val rubricWeightSum = item.rubric.sumOf { it.weight }
        val base = QuizItemGrade(
            itemOrdinal = item.ordinal,
            itemType = QuizItemType.ESSAY,
            normalizedAnswer = raw,
            score = null,
            isCorrect = null,
            status = QuizGradingStatus.PENDING_SELF_REVIEW,
            gradingMethod = QuizGradingMethod.LOCAL_RUBRIC_V1,
            matchedPoints = matched,
            missedPoints = missed,
            conflictedPoints = conflicted,
            rubricVersion = QuizTemplate.RUBRIC_VERSION,
        )
        if (rubricWeightSum != item.score) {
            return base.copy(feedback = "rubric 权重和 $rubricWeightSum 与题目分值 ${item.score} 不一致，转待自评")
        }
        if (conflicted.isNotEmpty()) {
            return base.copy(
                feedback = "存在否定/冲突表述（${conflicted.joinToString("、")}），要点不给分；语义不唯一，转待自评（不计 0 分）",
            )
        }
        if (matched.isEmpty()) {
            return base.copy(
                feedback = "本地规则未命中任何要点（可能是同义改写），转待自评（不计 0 分），请对照参考答案确认",
            )
        }
        if (missed.isEmpty()) {
            return base.copy(
                score = item.score,
                isCorrect = true,
                status = QuizGradingStatus.GRADED,
                feedback = "全部要点命中（${matched.size}/${item.rubric.size}）：${matched.joinToString("、")}",
            )
        }
        return base.copy(
            score = score,
            isCorrect = null,
            status = QuizGradingStatus.GRADED,
            feedback = "部分命中：${matched.joinToString("、")}（+$score 分）；缺 ${missed.joinToString("、")}",
        )
    }

    // ---- rubric 要点判定 ----

    private enum class PointVerdict { HIT, NEGATED, MISS }

    private fun evaluatePoint(point: RubricPoint, normalizedText: String): PointVerdict {
        // 1) 显式否定表达优先：模型/设计给出的 negatedBy 命中即视为否定，不因包含而得分。
        if (point.negatedBy.any { normalizePhrase(it).let { phrase -> phrase.isNotEmpty() && normalizedText.contains(phrase) } }) {
            return PointVerdict.NEGATED
        }
        var sawNegated = false
        point.accepted.forEach { accepted ->
            when (matchAccepted(accepted, normalizedText)) {
                PointVerdict.HIT -> return PointVerdict.HIT
                PointVerdict.NEGATED -> sawNegated = true
                PointVerdict.MISS -> Unit
            }
        }
        return if (sawNegated) PointVerdict.NEGATED else PointVerdict.MISS
    }

    private fun matchAccepted(accepted: String, normalizedText: String): PointVerdict {
        val phrase = normalizePhrase(accepted)
        if (phrase.isEmpty()) return PointVerdict.MISS
        var sawNegated = false
        var index = normalizedText.indexOf(phrase)
        while (index >= 0) {
            if (isNegatedAt(normalizedText, index)) {
                sawNegated = true
            } else {
                return PointVerdict.HIT
            }
            index = normalizedText.indexOf(phrase, index + 1)
        }
        if (sawNegated) return PointVerdict.NEGATED
        // 实体要点回退：把 accepted 按标点拆成多个词块，全部出现才算命中（仍然先判否定）。
        val tokens = tokenize(accepted)
        if (tokens.size >= 2 && tokens.all { normalizedText.contains(it) }) {
            val firstTokenIndex = normalizedText.indexOf(tokens.first())
            return if (firstTokenIndex >= 0 && isNegatedAt(normalizedText, firstTokenIndex)) {
                PointVerdict.NEGATED
            } else {
                PointVerdict.HIT
            }
        }
        return PointVerdict.MISS
    }

    /**
     * 检查命中位置前是否出现否定线索（“不满足示例条件甲”不得因包含而得分）。
     *
     * 否定词必须紧贴要点（否定词与要点之间最多隔 2 个字符），否则
     * “……不直接覆盖；重复确认同一次修改不能增加新版本”里的“不”会被误算成
     * 后一个要点的否定。误判只会让结果变成待自评（不给分），但也不能滥杀。
     */
    private fun isNegatedAt(text: String, phraseStart: Int): Boolean {
        if (phraseStart <= 0) return false
        val from = (phraseStart - negationWindow).coerceAtLeast(0)
        val window = text.substring(from, phraseStart)
        return negationCues.any { cue ->
            var index = window.lastIndexOf(cue)
            while (index >= 0) {
                val gap = window.length - (index + cue.length)
                if (gap <= NEGATION_MAX_GAP) return true
                index = window.lastIndexOf(cue, index - 1)
            }
            false
        }
    }

    private fun normalizePhrase(phrase: String): String = QuizText.normalize(phrase.trim())

    private fun tokenize(phrase: String): List<String> = phrase
        .split(Regex("[\\s\\p{Punct}，。、；：？！“”‘’（）【】《》…—·]"))
        .map { QuizText.normalize(it) }
        .filter { it.length >= 2 }
}
