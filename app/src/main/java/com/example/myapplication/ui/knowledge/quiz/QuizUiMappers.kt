package com.example.myapplication.ui.knowledge.quiz

import com.example.myapplication.data.quiz.QuizAttemptAnswerView
import com.example.myapplication.data.quiz.QuizAttemptSnapshot
import com.example.myapplication.data.quiz.QuizAttemptView
import com.example.myapplication.domain.quiz.QuizEvidenceRef
import com.example.myapplication.domain.quiz.QuizItemSnapshot
import com.example.myapplication.domain.quiz.QuizItemType
import com.example.myapplication.domain.quiz.QuizPaperSnapshot
import com.example.myapplication.domain.quiz.QuizRawAnswers
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import com.example.myapplication.ui.ai.AgentSourceUi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 领域快照 → UI 状态映射（W4-11）。
 *
 * 只做纯函数：不访问 DB/网络。接线时由 `QuizUiPort` 适配器调用：
 *  - [toAttemptUi]：答题页（fixed items + 当前答案 + 有效用时）；
 *  - [toResultUi]：结果页（已评/待评分开，score=NULL 不当 0）；
 *  - [toHistoryItem]：历史列表（paper + attempt 分开，重练/重新生成由页面决定）；
 *  - [displayAnswer]/[rawAnswerJson]：`quiz_attempt_answers.raw_answer_json` 形状
 *    以 task-9 的 [QuizRawAnswers] 为准（choice: {"selectedOptionId":"A"}；essay: {"text":"..."}）。
 */
object QuizUiMappers {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun statusOf(attempt: QuizAttemptView): QuizAttemptStatusUi = when {
        attempt.isInProgress -> QuizAttemptStatusUi.IN_PROGRESS
        attempt.isPendingReview -> QuizAttemptStatusUi.SUBMITTED_PENDING_REVIEW
        else -> QuizAttemptStatusUi.SUBMITTED_FINAL
    }

    fun toAttemptUi(
        snapshot: QuizAttemptSnapshot,
        title: String,
        courseLabel: String,
        sourcesByItem: Map<String, List<AgentSourceUi>> = emptyMap(),
    ): QuizAttemptUi {
        val submitted = !snapshot.attempt.isInProgress
        val questions = snapshot.paper.items.mapIndexed { index, item ->
            val itemId = item.itemId ?: "ordinal-${item.ordinal}"
            // Domain ordinals stay one-based; UI selection and resume state use list positions.
            toQuestionUi(item, itemId, snapshot.answers[itemId], submitted, sourcesByItem[itemId].orEmpty())
                .copy(ordinal = index)
        }
        return QuizAttemptUi(
            attemptId = snapshot.attempt.attemptId,
            submitKey = snapshot.attempt.submitKey,
            paperId = snapshot.attempt.paperId,
            title = title,
            courseLabel = courseLabel,
            status = statusOf(snapshot.attempt),
            questions = questions,
            currentOrdinal = snapshot.attempt.currentOrdinal.coerceIn(0, (questions.size - 1).coerceAtLeast(0)),
            activeUsedMs = snapshot.attempt.activeUsedMs,
            attemptRevision = snapshot.attempt.revision,
            gradedScore = questions.sumOf { it.score ?: 0 },
            maxScore = snapshot.paper.maxScore,
            pendingReviewCount = snapshot.pendingCount,
        )
    }

    fun toResultUi(
        snapshot: QuizAttemptSnapshot,
        title: String,
        courseLabel: String,
        sourcesByItem: Map<String, List<AgentSourceUi>> = emptyMap(),
        gradeNote: String? = null,
        wrongQuestionIds: Set<Long> = emptySet(),
    ): QuizResultUi {
        val attempt = toAttemptUi(snapshot, title, courseLabel, sourcesByItem)
        // 错题本状态来自 favorites（source=WRONG）的只读集合；question 映射缺失时一律不算错题，
        // 既不伪造状态也不会给不存在的行提供移除入口。
        val questions = attempt.questions.map { question ->
            val questionId = snapshot.itemQuestionIds[question.itemId]
            question.copy(
                questionId = questionId,
                inWrongBook = questionId != null && questionId in wrongQuestionIds,
            )
        }
        return QuizResultUi(
            attemptId = attempt.attemptId,
            paperId = snapshot.attempt.paperId,
            title = attempt.title,
            courseLabel = attempt.courseLabel,
            status = attempt.status,
            gradedScore = attempt.questions.sumOf { it.score ?: 0 },
            maxScore = attempt.maxScore,
            pendingCount = attempt.pendingReviewCount,
            elapsedMs = attempt.activeUsedMs,
            questions = questions,
            gradeNote = gradeNote,
        )
    }

    fun toQuestionUi(
        item: QuizItemSnapshot,
        itemId: String,
        answer: QuizAttemptAnswerView?,
        submitted: Boolean,
        sources: List<AgentSourceUi> = emptyList(),
    ): QuizQuestionUi {
        val parsed = QuizRawAnswers.parse(answer?.rawAnswerJson)
        val display = parsed.selectedOptionId ?: parsed.text.orEmpty()
        val answered = display.isNotBlank()
        val gradeStatus = when {
            // 未作答优先：交卷事务会为未答行落 score=0，但 raw_answer 为空，不能显示为“已评 0 分”
            submitted && !answered -> QuizItemGradeStatusUi.UNANSWERED
            submitted && answer?.score != null -> QuizItemGradeStatusUi.GRADED
            submitted -> QuizItemGradeStatusUi.PENDING_SELF_REVIEW
            answered -> QuizItemGradeStatusUi.NOT_GRADED
            else -> QuizItemGradeStatusUi.UNANSWERED
        }
        return QuizQuestionUi(
            itemId = itemId,
            ordinal = item.ordinal,
            type = if (item.type == QuizItemType.CHOICE) QuizItemTypeUi.CHOICE else QuizItemTypeUi.ESSAY,
            stem = item.stem,
            options = item.options.mapIndexed { index, raw ->
                QuizOptionUi(letter = optionLetter(index), text = stripOptionPrefix(raw))
            },
            savedAnswer = display,
            maxScore = item.score,
            score = answer?.score,
            isCorrect = answer?.isCorrect,
            gradeStatus = gradeStatus,
            rubricLines = rubricLines(item, answer),
            analysis = item.analysis,
            knowledgePoint = item.knowledgePoint,
            sources = sources,
        )
    }

    /** 保存/交卷时要写入的 raw_answer_json（choice 用字母，essay 用原文）。 */
    fun rawAnswerJson(question: QuizQuestionUi, answerText: String): String =
        if (question.type == QuizItemTypeUi.CHOICE) {
            QuizRawAnswers.choice(answerText.trim().uppercase())
        } else {
            QuizRawAnswers.essay(answerText)
        }

    fun displayAnswer(question: QuizQuestionUi): String = question.savedAnswer

    /** 结果页 rubric 行：简答给参考要点 + 已命中/缺失；选择题给正确答案字母。 */
    fun rubricLines(item: QuizItemSnapshot, answer: QuizAttemptAnswerView?): List<String> {
        val lines = mutableListOf<String>()
        if (item.type == QuizItemType.CHOICE) {
            item.choiceAnswerLetter?.let { lines += "正确答案：$it" }
        } else {
            item.referencePoints.forEach { point -> lines += "参考要点：$point" }
        }
        val matched = parseMatchedRubric(answer?.matchedRubricJson)
        matched.matched.forEach { lines += "已命中：$it" }
        matched.missed.forEach { lines += "未命中：$it" }
        matched.conflicted.forEach { lines += "冲突：$it" }
        return lines
    }

    /** 出处映射：evidenceId（S1…）→ UI 卡片；locator 由应用侧快照提供，可跳转。 */
    fun evidenceSources(refs: List<QuizEvidenceRef>): List<AgentSourceUi> = refs.map { ref ->
        AgentSourceUi(
            ordinal = ref.evidenceId.removePrefix("S").toIntOrNull() ?: 0,
            documentId = ref.documentId,
            documentTitle = ref.sourceTitleSnapshot.ifBlank { ref.documentId },
            sectionId = ref.sectionId,
            sectionTitle = null,
            quote = ref.quote,
            locatorJson = runCatching { ReaderLocatorCodec.encode(ref.locator) }.getOrNull(),
        )
    }

    fun toHistoryItem(
        paper: QuizPaperSnapshot,
        attempt: QuizAttemptView,
        submittedAtLabel: String,
        title: String,
        courseLabel: String,
    ): QuizHistoryItemUi {
        val status = statusOf(attempt)
        val pending = status == QuizAttemptStatusUi.SUBMITTED_PENDING_REVIEW
        val scoreLabel = when {
            status == QuizAttemptStatusUi.IN_PROGRESS -> "作答中"
            pending -> "已评 ${attempt.totalScore ?: 0}/${paper.maxScore}（未终分）"
            else -> "总分 ${attempt.totalScore ?: 0}/${paper.maxScore}"
        }
        return QuizHistoryItemUi(
            paperId = attempt.paperId,
            attemptId = attempt.attemptId,
            title = title,
            courseLabel = courseLabel,
            statusLabel = when (status) {
                QuizAttemptStatusUi.IN_PROGRESS -> "作答中"
                QuizAttemptStatusUi.SUBMITTED_PENDING_REVIEW -> "待自评"
                QuizAttemptStatusUi.SUBMITTED_FINAL -> "已评"
            },
            scoreLabel = scoreLabel,
            submittedAtLabel = submittedAtLabel,
            generatedByLabel = if (paper.generatedBy.name == "OFFLINE_TEMPLATE") "离线模板" else "AI 生成",
            hasInProgressAttempt = status == QuizAttemptStatusUi.IN_PROGRESS,
        )
    }

    private data class MatchedRubric(
        val matched: List<String>,
        val missed: List<String>,
        val conflicted: List<String>,
    )

    private fun parseMatchedRubric(raw: String?): MatchedRubric {
        if (raw.isNullOrBlank()) return MatchedRubric(emptyList(), emptyList(), emptyList())
        val obj: JsonObject = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return MatchedRubric(emptyList(), emptyList(), emptyList())
        return MatchedRubric(
            matched = readStringArray(obj, "matched"),
            missed = readStringArray(obj, "missed"),
            conflicted = readStringArray(obj, "conflicted"),
        )
    }

    private fun readStringArray(obj: JsonObject, key: String): List<String> {
        val array = obj[key] as? JsonArray ?: return emptyList()
        return array.mapNotNull { it.toString().trim('"').takeIf { text -> text.isNotBlank() } }
    }

    private fun optionLetter(index: Int): String = ('A' + index).toString()

    private fun stripOptionPrefix(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length >= 3 && trimmed[1] == '.' && trimmed[0].isUpperCase()) {
            return trimmed.substring(2).trim()
        }
        return trimmed
    }
}
