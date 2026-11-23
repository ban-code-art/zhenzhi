package com.example.myapplication.domain.quiz

/**
 * 单题评分状态（对应 quiz_attempt_answers.score/is_correct/grading_method 三态）。
 *
 * - GRADED：已评，score 非 NULL；is_correct=1 满分、0 零分。
 * - PENDING_SELF_REVIEW：本地规则无法可靠判定，score 必须为 NULL，不能算 0（§8/FX-16）。
 * - UNANSWERED：已交卷的未作答，按 0 分计，但不与“待评”混为一谈。
 */
enum class QuizGradingStatus { GRADED, PENDING_SELF_REVIEW, UNANSWERED }

enum class QuizGradingMethod { NONE, CHOICE_EXACT, LOCAL_RUBRIC_V1 }

/**
 * 一道题的评分结果。
 *
 * [score] 只在 PENDING_SELF_REVIEW 时为 NULL；[isCorrect] 为 NULL 表示部分正确或待评，
 * 不能把 NULL 当 0 对外宣称最终分（设计 §15）。
 */
data class QuizItemGrade(
    val itemOrdinal: Int,
    val itemType: QuizItemType,
    val normalizedAnswer: String,
    val score: Int?,
    val isCorrect: Boolean?,
    val status: QuizGradingStatus,
    val gradingMethod: QuizGradingMethod,
    val matchedPoints: List<String> = emptyList(),
    val missedPoints: List<String> = emptyList(),
    val conflictedPoints: List<String> = emptyList(),
    val feedback: String = "",
    val rubricVersion: String = "",
) {
    val isPending: Boolean get() = status == QuizGradingStatus.PENDING_SELF_REVIEW
}

/**
 * 整卷计分汇总（设计 §15：只有全部题已评才是最终总分）。
 *
 * 存在待评时 [totalScore] 为 NULL，UI 只能展示已评得分/已评满分与待评数。
 */
data class QuizScoreSummary(
    val totalScore: Int?,
    val gradedScore: Int,
    val gradedMaxScore: Int,
    val pendingCount: Int,
    val unansweredCount: Int,
) {
    val allGraded: Boolean get() = pendingCount == 0
    val maxScore: Int get() = gradedMaxScore

    companion object {
        fun of(grades: List<QuizItemGrade>, itemMaxScores: Map<Int, Int>): QuizScoreSummary = of(
            grades = grades,
            maxScoreOf = { ordinal -> itemMaxScores[ordinal] ?: 0 },
        )

        fun of(grades: List<QuizItemGrade>, maxScoreOf: (Int) -> Int): QuizScoreSummary {
            val pending = grades.count { it.isPending }
            val unanswered = grades.count { it.status == QuizGradingStatus.UNANSWERED }
            val gradedScore = grades.sumOf { it.score ?: 0 }
            val gradedMax = grades.sumOf { maxScoreOf(it.itemOrdinal) }
            val total = if (pending == 0) gradedScore else null
            return QuizScoreSummary(
                totalScore = total,
                gradedScore = gradedScore,
                gradedMaxScore = gradedMax,
                pendingCount = pending,
                unansweredCount = unanswered,
            )
        }

        fun of(paper: QuizPaperSnapshot, grades: List<QuizItemGrade>): QuizScoreSummary =
            of(grades) { ordinal -> paper.items.firstOrNull { it.ordinal == ordinal }?.score ?: 0 }
    }
}
