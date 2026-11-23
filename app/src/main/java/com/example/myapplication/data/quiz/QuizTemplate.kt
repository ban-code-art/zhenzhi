package com.example.myapplication.data.quiz

import com.example.myapplication.domain.quiz.QuizDifficulty
import com.example.myapplication.domain.quiz.QuizGeneratedBy
import com.example.myapplication.domain.quiz.QuizItemType
import com.example.myapplication.domain.quiz.QuizTemplateSpec

/**
 * 随堂测模板（第四期详细设计 §7.1 / §19；07-随堂测完整结构样例）。
 *
 * 规则：
 *  - chapter-quiz-v1 默认 7 CHOICE + 3 ESSAY，各 10 分，总分 100；
 *  - 自定义选择题数 5–10：选择题总分固定 70，按商余分配成整数分值；三简答各 10，
 *    总分恒 100（商余分配顺序稳定，同输入同结果）；
 *  - 模板变化必须写 templateVersion，历史题不被重新解释；
 *  - 离线卷单独声明实际题数/满分（[QuizTemplateSpec.offline]），不得冒充 7+3。
 */
object QuizTemplate {

    const val CHAPTER_QUIZ_V1_ID = "chapter-quiz-v1"
    const val CHAPTER_QUIZ_V1_VERSION = "1.0"
    const val OFFLINE_PRACTICE_ID = "offline-chapter-practice-v1"
    const val OFFLINE_PRACTICE_VERSION = "1.0"

    const val DEFAULT_CHOICE_COUNT = 7
    const val MIN_CHOICE_COUNT = 5
    const val MAX_CHOICE_COUNT = 10
    const val CHOICE_PART_SCORE = 70
    const val ESSAY_COUNT = 3
    const val ESSAY_SCORE = 10
    const val OPTIONS_PER_CHOICE = 4
    const val RUBRIC_MIN_POINTS = 3
    const val RUBRIC_MAX_POINTS = 5
    const val TOTAL_SCORE = 100

    /** 本地 rubric 规则版本：评分结果随分数一起保存，规则变化不重解释历史分。 */
    const val RUBRIC_VERSION = "local-rubric-v1"

    /** 默认正式模板（7+3，各 10 分，总分 100）。 */
    val chapterQuizDefault: QuizTemplateSpec = chapterQuizV1(DEFAULT_CHOICE_COUNT)

    /**
     * chapter-quiz-v1（默认 7 道选择题，或自定义 5–10 道）。
     *
     * 选择题总分 70 按商余分配：[choiceScoreAllocation]；简答固定 3 道各 10 分。
     */
    fun chapterQuizV1(choiceCount: Int = DEFAULT_CHOICE_COUNT): QuizTemplateSpec {
        require(choiceCount in MIN_CHOICE_COUNT..MAX_CHOICE_COUNT) {
            "choiceCount must be within $MIN_CHOICE_COUNT..$MAX_CHOICE_COUNT, got $choiceCount"
        }
        val spec = QuizTemplateSpec(
            templateId = CHAPTER_QUIZ_V1_ID,
            templateVersion = chapterVersionFor(choiceCount),
            choiceCount = choiceCount,
            essayCount = ESSAY_COUNT,
            choiceScores = choiceScoreAllocation(choiceCount),
            essayScores = List(ESSAY_COUNT) { ESSAY_SCORE },
            optionsPerChoice = OPTIONS_PER_CHOICE,
            rubricMinPoints = RUBRIC_MIN_POINTS,
            rubricMaxPoints = RUBRIC_MAX_POINTS,
            difficultyTargets = difficultyTargetsFor(choiceCount + ESSAY_COUNT),
            generatedBy = QuizGeneratedBy.AI,
            offline = false,
        )
        requireValid(spec)
        return spec
    }

    /** 模板版本：默认卷沿用 1.0；题数变化写新版本，历史卷不被重新解释。 */
    fun chapterVersionFor(choiceCount: Int): String =
        if (choiceCount == DEFAULT_CHOICE_COUNT) CHAPTER_QUIZ_V1_VERSION else "$CHAPTER_QUIZ_V1_VERSION-c$choiceCount"

    /**
     * 选择题 70 分的商余分配：先每人 quotient，余数从第一题开始 +1。
     *
     * 例：5→[14×5]；6→[12,12,12,12,11,11]；7→[10×7]；8→[9,9,9,9,9,9,8,8]；
     * 9→[8×7, 7×2]；10→[7×10]。
     */
    fun choiceScoreAllocation(choiceCount: Int): List<Int> {
        require(choiceCount in MIN_CHOICE_COUNT..MAX_CHOICE_COUNT) {
            "choiceCount must be within $MIN_CHOICE_COUNT..$MAX_CHOICE_COUNT, got $choiceCount"
        }
        val quotient = CHOICE_PART_SCORE / choiceCount
        val remainder = CHOICE_PART_SCORE % choiceCount
        return List(choiceCount) { index -> quotient + if (index < remainder) 1 else 0 }
    }

    /**
     * 难度规划：基础 5 / 理解 3 / 应用 2 的比例，按最大余数法取整到 [itemCount] 个槽位。
     *
     * 10 题时正好 5/3/2；其它题数保持同一比例且总和等于题数。
     */
    fun difficultyTargetsFor(itemCount: Int): Map<QuizDifficulty, Int> {
        require(itemCount > 0) { "itemCount must be positive" }
        val weighted = listOf(
            QuizDifficulty.BASIC to 5,
            QuizDifficulty.UNDERSTAND to 3,
            QuizDifficulty.APPLY to 2,
        )
        val exact = weighted.map { (difficulty, weight) -> Triple(difficulty, weight, weight * itemCount / 10.0) }
        val floors = exact.associate { (difficulty, _, value) -> difficulty to value.toInt() }
        var remaining = itemCount - floors.values.sum()
        val remainderOrder = exact
            .sortedWith(
                compareByDescending<Triple<QuizDifficulty, Int, Double>> { it.third - it.third.toInt() }
                    .thenByDescending { it.second },
            )
            .map { it.first }
        val result = floors.toMutableMap()
        for (difficulty in remainderOrder) {
            if (remaining <= 0) break
            result[difficulty] = (result[difficulty] ?: 0) + 1
            remaining--
        }
        // 兜底：极端题数下余数仍应分配完（最大余数法最多差 1）。
        if (remaining > 0) result[QuizDifficulty.BASIC] = (result[QuizDifficulty.BASIC] ?: 0) + remaining
        return result
    }

    /**
     * 离线预置卷：只声明实际题数与满分，不套用 AI 7+3/100 规则。
     * 例如 5 题基础练习满分 50 就是 50，不能显示成 100。
     */
    fun offlinePractice(
        choiceCount: Int,
        essayCount: Int,
        choiceScore: Int,
        essayScore: Int,
        templateId: String = OFFLINE_PRACTICE_ID,
        templateVersion: String = OFFLINE_PRACTICE_VERSION,
    ): QuizTemplateSpec {
        require(templateId != CHAPTER_QUIZ_V1_ID) { "offline practice must not masquerade as $CHAPTER_QUIZ_V1_ID" }
        require(choiceCount >= 0 && essayCount >= 0) { "counts must be non-negative" }
        require(choiceCount + essayCount > 0) { "offline practice requires at least one item" }
        require(choiceScore > 0 && essayScore > 0) { "item scores must be positive" }
        val spec = QuizTemplateSpec(
            templateId = templateId,
            templateVersion = templateVersion,
            choiceCount = choiceCount,
            essayCount = essayCount,
            choiceScores = List(choiceCount) { choiceScore },
            essayScores = List(essayCount) { essayScore },
            optionsPerChoice = OPTIONS_PER_CHOICE,
            rubricMinPoints = RUBRIC_MIN_POINTS,
            rubricMaxPoints = RUBRIC_MAX_POINTS,
            difficultyTargets = difficultyTargetsFor(choiceCount + essayCount),
            generatedBy = QuizGeneratedBy.OFFLINE_TEMPLATE,
            offline = true,
        )
        requireValid(spec)
        return spec
    }

    /** 模板自检不通过即拒绝使用（避免“改坏的模板”生成卷）。 */
    fun requireValid(spec: QuizTemplateSpec) {
        val errors = spec.selfCheck()
        require(errors.isEmpty()) { "invalid quiz template ${spec.templateId}: ${errors.joinToString("; ")}" }
    }

    /** 期望的题型序列（DDL 顺序：先 CHOICE 后 ESSAY）。 */
    fun expectedTypes(spec: QuizTemplateSpec): List<QuizItemType> =
        spec.expectedItems().map { it.type }
}
