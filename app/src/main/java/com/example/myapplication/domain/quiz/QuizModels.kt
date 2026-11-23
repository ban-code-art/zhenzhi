package com.example.myapplication.domain.quiz

/**
 * 随堂测领域模型（第四期详细设计 §7/§8/§14/§15；05 数据库契约 §4 v6 DDL 的 quiz_* 形状）。
 *
 * 这些模型与 Room 实体解耦：实体/DAO 由 W4-01 负责，本文件只固定
 * question_snapshot_json / rubric_json / evidence_json 对应的领域结构，
 * 以及模板、校验、评分共享的只读视图。
 */

enum class QuizItemType { CHOICE, ESSAY }

enum class QuizDifficulty { BASIC, UNDERSTAND, APPLY }

/** quiz_papers.status：只有 VALIDATING 通过后同事务发布为 READY。 */
enum class QuizPaperStatus { GENERATING, VALIDATING, READY, FAILED, CANCELLED, INTERRUPTED }

/** AI 生成卷与离线预置卷必须可分：离线卷不得冒充 7+3/100。 */
enum class QuizGeneratedBy { AI, OFFLINE_TEMPLATE }

/**
 * 评分要点（rubric_json 的元素）。
 *
 * [accepted] 是可判定的同义表达/实体要点；[negatedBy] 是明确的否定表达。
 * 否定优先于包含匹配：用户写“不满足X”不得因为包含 X 而得分（FX-16）。
 */
data class RubricPoint(
    val key: String,
    val weight: Int,
    val accepted: List<String> = emptyList(),
    val negatedBy: List<String> = emptyList(),
) {
    init {
        require(key.isNotBlank()) { "rubric key must not be blank" }
    }
}

/**
 * 单题快照（quiz_items.question_snapshot_json + rubric_json 的领域视图）。
 *
 * 字段与 07-随堂测完整结构样例 对齐：type/stem/options/answer/score/analysis/
 * knowledgePoint/difficulty/rubric/evidenceIds。evidenceIds 只能来自本次读源
 * registry（S 编号）；Locator/quote/revision 由应用补全，模型不能自填。
 */
data class QuizItemSnapshot(
    val ordinal: Int,
    val type: QuizItemType,
    val stem: String,
    val options: List<String> = emptyList(),
    val answer: List<String> = emptyList(),
    val score: Int,
    val analysis: String = "",
    val knowledgePoint: String = "",
    val difficulty: QuizDifficulty = QuizDifficulty.BASIC,
    val rubric: List<RubricPoint> = emptyList(),
    val evidenceIds: List<String> = emptyList(),
    val templateVersion: String = "",
    /** 发布后由应用分配 quiz_items.id；生成草稿阶段为 null。 */
    val itemId: String? = null,
) {
    /** CHOICE 的答案字母（归一化后）；ESSAY 返回 null。 */
    val choiceAnswerLetter: String?
        get() = if (type == QuizItemType.CHOICE) answer.firstOrNull()?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } else null

    /** 参考答案文本列表（ESSAY 用）。 */
    val referencePoints: List<String>
        get() = answer.map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * 整卷快照（quiz_papers 行 + 固定下来的 items）。
 *
 * [questionCount]/[maxScore] 必须与实际 items 一致，防止“裁题后仍显示 10 题 100 分”。
 */
data class QuizPaperSnapshot(
    val templateId: String,
    val templateVersion: String,
    val sourceRevision: String,
    val scopeJson: String,
    val questionCount: Int,
    val maxScore: Int,
    val items: List<QuizItemSnapshot>,
    val generatedBy: QuizGeneratedBy = QuizGeneratedBy.AI,
    val coverageJson: String = "{}",
    val paperId: String? = null,
    val status: QuizPaperStatus = QuizPaperStatus.VALIDATING,
) {
    val choiceCount: Int get() = items.count { it.type == QuizItemType.CHOICE }
    val essayCount: Int get() = items.count { it.type == QuizItemType.ESSAY }
    val scoreSum: Int get() = items.sumOf { it.score }

    companion object {
        fun of(
            template: QuizTemplateSpec,
            sourceRevision: String,
            scopeJson: String = "{}",
            items: List<QuizItemSnapshot>,
            generatedBy: QuizGeneratedBy = QuizGeneratedBy.AI,
            coverageJson: String = "{}",
            paperId: String? = null,
        ): QuizPaperSnapshot = QuizPaperSnapshot(
            templateId = template.templateId,
            templateVersion = template.templateVersion,
            sourceRevision = sourceRevision,
            scopeJson = scopeJson,
            questionCount = items.size,
            maxScore = items.sumOf { it.score },
            items = items,
            generatedBy = generatedBy,
            coverageJson = coverageJson,
            paperId = paperId,
        )
    }
}

/**
 * 模板规格（chapter-quiz-v1 及其变体）。
 *
 * 默认 AI 模板：7 CHOICE + 3 ESSAY，各 10 分，总分 100；自定义 5–10 选择题时
 * 选择题总分 70 按商余分配为整数分值，三简答各 10 恒 100（§7.1）。
 * 离线模板单独声明实际题数/满分，[offline] 为 true，不参与 70/30 规则。
 */
data class QuizTemplateSpec(
    val templateId: String,
    val templateVersion: String,
    val choiceCount: Int,
    val essayCount: Int,
    val choiceScores: List<Int>,
    val essayScores: List<Int>,
    val optionsPerChoice: Int = 4,
    val rubricMinPoints: Int = 3,
    val rubricMaxPoints: Int = 5,
    val difficultyTargets: Map<QuizDifficulty, Int> = emptyMap(),
    val generatedBy: QuizGeneratedBy = QuizGeneratedBy.AI,
    val offline: Boolean = false,
) {
    init {
        require(templateId.isNotBlank()) { "templateId must not be blank" }
        require(templateVersion.isNotBlank()) { "templateVersion must not be blank" }
        require(choiceCount >= 0 && essayCount >= 0) { "counts must be non-negative" }
        require(choiceScores.size == choiceCount) { "choiceScores size must equal choiceCount" }
        require(essayScores.size == essayCount) { "essayScores size must equal essayCount" }
        require(choiceScores.all { it > 0 } && essayScores.all { it > 0 }) { "item scores must be positive" }
        require(optionsPerChoice >= 2) { "optionsPerChoice must be >= 2" }
        require(rubricMinPoints in 1..rubricMaxPoints) { "rubric bounds are invalid" }
    }

    val itemCount: Int get() = choiceCount + essayCount

    val maxScore: Int get() = choiceScores.sum() + essayScores.sum()

    /** 逐题（按 DDL 顺序：先 CHOICE 后 ESSAY，ordinal 从 1 开始）的目标分值与难度。 */
    fun expectedItems(): List<PlannedItemScore> {
        val result = mutableListOf<PlannedItemScore>()
        choiceScores.forEachIndexed { index, score ->
            result += PlannedItemScore(result.size + 1, QuizItemType.CHOICE, score)
        }
        essayScores.forEachIndexed { index, score ->
            result += PlannedItemScore(result.size + 1, QuizItemType.ESSAY, score)
        }
        return result
    }

    /** 模板自检：AI 模板的错误会直接拒绝，避免“改坏的模板”被当合法契约。 */
    fun selfCheck(): List<String> {
        val errors = mutableListOf<String>()
        if (!offline) {
            if (quizTypeOfTemplate() == QuizTemplateKind.CHAPTER_QUIZ_V1) {
                if (choiceCount !in 5..10) errors += "choiceCount must be within 5..10 for $templateId"
                if (essayCount != 3) errors += "essayCount must be 3 for $templateId"
                if (choiceScores.sum() != 70) errors += "choice scores must sum to 70"
                if (essayScores.sum() != 30 || essayScores.any { it != 10 }) errors += "essay scores must be 10 each"
                if (maxScore != 100) errors += "max score must be 100"
                if (optionsPerChoice != 4) errors += "optionsPerChoice must be 4"
            }
        }
        if (rubricMinPoints < 3 || rubricMaxPoints > 5) {
            errors += "essay rubric must declare 3..5 points"
        }
        if (!offline && essayCount > 0 && rubricMinPoints < 3) {
            errors += "essay items require at least 3 rubric points"
        }
        return errors
    }

    private fun quizTypeOfTemplate(): QuizTemplateKind = when {
        templateId.startsWith("chapter-quiz") -> QuizTemplateKind.CHAPTER_QUIZ_V1
        else -> QuizTemplateKind.CUSTOM
    }
}

enum class QuizTemplateKind { CHAPTER_QUIZ_V1, CUSTOM }

/** 模板对某一 ordinal 的期望题型/分值（校验与规划共用）。 */
data class PlannedItemScore(
    val ordinal: Int,
    val type: QuizItemType,
    val score: Int,
)
