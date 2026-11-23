package com.example.myapplication.data.quiz

import com.example.myapplication.data.agent.ChatStreamExecutor
import com.example.myapplication.data.agent.SecretResolution
import com.example.myapplication.data.ai.adapters.ChatAdapter
import com.example.myapplication.data.ai.config.FinalEndpoint
import com.example.myapplication.domain.ai.ChatBudget
import com.example.myapplication.domain.ai.ChatEvent
import com.example.myapplication.domain.ai.ChatMessage
import com.example.myapplication.domain.ai.ChatRequest
import com.example.myapplication.domain.ai.ToolPolicy
import com.example.myapplication.domain.reader.AccessContext
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 简答题 AI 复核（W5-10，详细设计 §8）。
 *
 * 边界（逐条对应设计）：
 *  - 开关默认关闭，用户可对**任意**简答题发起复核（包括本地 0 分或满分）；
 *  - 只发送题干、参考答案、rubric 要点与已核验证据片段，不发送全库；
 *  - 复核只产生**建议**：`ReviewSuggestion` 不改任何分数；
 *  - 采纳才调用四期 `GradeAssessmentService.reviseGrade`，用确定性 `assessmentKey` 幂等：
 *    同一建议重复采纳不会重复改分/重复统计；
 *  - schema 错、分值越界、网络失败都保留原本地分（返回 Rejected/Failed，绝不写成 0）；
 *  - AI 意见不替代自评与人工审核：建议里保留 `localScore` 与不确定项，由用户决定。
 */
class AiRubricReviewer(
    private val executor: ChatStreamExecutor,
    private val adapter: ChatAdapter,
    private val endpoint: FinalEndpoint,
    private val profileId: String,
    private val configRevision: Long,
    private val model: String,
    private val rubricVersion: String,
    private val resolveSecret: suspend () -> SecretResolution,
    private val maxPromptChars: Int = MAX_PROMPT_CHARS,
    private val maxOutputTokens: Int = MAX_OUTPUT_TOKENS,
    private val deadlineMs: Long = DEFAULT_DEADLINE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class ReviewRequest(
        val attemptId: String,
        val itemId: String,
        val question: String,
        val referenceAnswer: String,
        val studentAnswer: String,
        val rubricPoints: List<String>,
        val evidence: List<String>,
        val localScore: Int?,
        val maxScore: Int,
    )

    sealed interface ReviewOutcome {
        /** 复核成功：只是一条建议，尚未写库。 */
        data class Suggested(val suggestion: ReviewSuggestion) : ReviewOutcome

        /** 输入/输出不合规（越界、未知要点、非 JSON）：本地分保持不变。 */
        data class Rejected(val code: String, val detail: String) : ReviewOutcome

        /** 网络/鉴权/超时失败：本地分保持不变，可重试。 */
        data class Failed(val code: String, val detail: String, val retryable: Boolean) : ReviewOutcome
    }

    data class RubricJudgment(val point: String, val hit: Boolean, val note: String)

    data class ReviewSuggestion(
        val attemptId: String,
        val itemId: String,
        val score: Int,
        val maxScore: Int,
        /** 原本地分：UI 必须同时显示，不允许"AI 覆盖了分数"的观感。 */
        val localScore: Int?,
        val rubricJudgments: List<RubricJudgment>,
        val rationale: String,
        val uncertainties: List<String>,
        val model: String,
        val rubricVersion: String,
        val generatedAt: Long,
    ) {
        /** 同一建议的稳定指纹：采纳时作为 assessmentKey 的一部分，保证幂等。 */
        val fingerprint: String by lazy { fingerprintOf(attemptId, itemId, score, model, rubricVersion, rationale) }
    }

    suspend fun review(request: ReviewRequest): ReviewOutcome {
        if (request.maxScore <= 0) {
            return ReviewOutcome.Rejected("INVALID_MAX_SCORE", "题目满分必须为正")
        }
        val apiKey = when (val secret = resolveSecret()) {
            is SecretResolution.Ok -> secret.apiKey
            SecretResolution.Revoked ->
                return ReviewOutcome.Failed("ACCESS_REVOKED", "CHAT 授权已撤回，未发起复核", retryable = false)
            SecretResolution.SecretMissing ->
                return ReviewOutcome.Failed("SECRET_MISSING", "缺少 CHAT 密钥，未发起复核", retryable = false)
        }
        val prompt = buildPrompt(request)
        val chatRequest = ChatRequest(
            runId = "ai-review-${request.attemptId}-${request.itemId}",
            profileId = profileId,
            configRevision = configRevision,
            messages = listOf(
                ChatMessage.system(SYSTEM_PROMPT),
                ChatMessage.user(prompt),
            ),
            // 复核不允许调用工具：它只读给定输入，不能查库、不能写分数。
            tools = emptyList(),
            toolPolicy = ToolPolicy.NONE,
            responseSchema = null,
            stream = true,
            budget = ChatBudget(
                inputLimit = (maxPromptChars / 3).coerceAtLeast(MIN_INPUT_TOKENS),
                outputReserve = maxOutputTokens,
                deadlineMs = deadlineMs,
            ),
        )
        val text = StringBuilder()
        try {
            executor.stream(chatRequest, adapter, apiKey, endpoint) { event ->
                when (event) {
                    is ChatEvent.TextDelta -> text.append(event.text)
                    is ChatEvent.ProviderError ->
                        // 端点明确报错：本地分不变
                        throw IllegalStateException("PROVIDER_${event.code}")
                    else -> Unit
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return ReviewOutcome.Failed("TRANSPORT_ERROR", "复核请求失败：${error.javaClass.simpleName}", retryable = true)
        }
        return parse(request, text.toString())
    }

    /** 解析并校验模型输出；任何不合规都不改分（选择 Rejected 而不是"尽力而为"）。 */
    fun parse(request: ReviewRequest, raw: String): ReviewOutcome {
        val jsonText = extractJsonObject(raw)
            ?: return ReviewOutcome.Rejected("SCHEMA_INVALID", "模型输出里没有 JSON 对象")
        val root = runCatching { JSON.parseToJsonElement(jsonText) as? JsonObject }.getOrNull()
            ?: return ReviewOutcome.Rejected("SCHEMA_INVALID", "模型输出不是合法 JSON 对象")

        val scoreElement = root["score"] as? JsonPrimitive
        if (scoreElement == null || scoreElement.isString) {
            // 只接受 JSON 数字：字符串 "7"、null、"满分" 一律拒绝，避免宽松解析把脏数据写进分数
            return ReviewOutcome.Rejected("SCHEMA_INVALID", "score 必须是 JSON 数字")
        }
        val score = scoreElement.intOrNull
            ?: return ReviewOutcome.Rejected("SCHEMA_INVALID", "score 必须是整数")
        if (score !in 0..request.maxScore) {
            return ReviewOutcome.Rejected(
                "SCORE_OUT_OF_RANGE",
                "复核分值 $score 超出 0..${request.maxScore}，已忽略，本地分保持不变",
            )
        }

        val allowedPoints = request.rubricPoints.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val judgments = mutableListOf<RubricJudgment>()
        (root["rubric"] as? JsonArray)?.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val point = (obj["point"] as? JsonPrimitive)?.jsonPrimitive?.content?.trim().orEmpty()
            if (point.isEmpty()) return@forEach
            if (allowedPoints.isNotEmpty() && point !in allowedPoints) {
                return ReviewOutcome.Rejected("UNKNOWN_RUBRIC_POINT", "模型给出了 rubric 之外的要点：$point")
            }
            val hit = (obj["hit"] as? JsonPrimitive)?.booleanOrNull
                ?: return ReviewOutcome.Rejected("SCHEMA_INVALID", "rubric[].hit 必须是布尔值")
            val note = (obj["note"] as? JsonPrimitive)?.jsonPrimitive?.content.orEmpty()
            judgments += RubricJudgment(point = point, hit = hit, note = note.take(MAX_NOTE_CHARS))
        }

        val rationale = (root["rationale"] as? JsonPrimitive)?.jsonPrimitive?.content.orEmpty().take(MAX_RATIONALE_CHARS)
        val uncertainties = (root["uncertainties"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { text -> text.isNotBlank() } }
            .take(MAX_UNCERTAINTIES)
        return ReviewOutcome.Suggested(
            ReviewSuggestion(
                attemptId = request.attemptId,
                itemId = request.itemId,
                score = score,
                maxScore = request.maxScore,
                localScore = request.localScore,
                rubricJudgments = judgments,
                rationale = rationale,
                uncertainties = uncertainties,
                model = model,
                rubricVersion = rubricVersion,
                generatedAt = clock(),
            ),
        )
    }

    /** 组装复核输入：题干 + 参考答案 + 评分要点 + 证据 + 学生答案，不带任何整库内容。 */
    fun buildPrompt(request: ReviewRequest): String = buildString {
        append("【题干】\n").append(request.question.take(MAX_QUESTION_CHARS)).append('\n')
        append("【参考答案】\n").append(request.referenceAnswer.take(MAX_ANSWER_CHARS)).append('\n')
        append("【评分要点】\n")
        request.rubricPoints.forEachIndexed { index, point ->
            append(index + 1).append(". ").append(point.take(MAX_POINT_CHARS)).append('\n')
        }
        if (request.evidence.isNotEmpty()) {
            append("【已核验证据】\n")
            request.evidence.take(MAX_EVIDENCE_ITEMS).forEach { item ->
                append("- ").append(item.take(MAX_EVIDENCE_CHARS)).append('\n')
            }
        }
        append("【学生答案】\n").append(request.studentAnswer.take(MAX_STUDENT_ANSWER_CHARS)).append('\n')
        append("【本地分】")
            .append(request.localScore?.let { "$it/${request.maxScore}" } ?: "待评 / ${request.maxScore}")
            .append('\n')
        append("请按要求只输出 JSON。")
    }.take(maxPromptChars)

    companion object {
        const val DEFAULT_DEADLINE_MS = 45_000L
        const val MAX_PROMPT_CHARS = 12_000
        const val MAX_OUTPUT_TOKENS = 512
        private const val MIN_INPUT_TOKENS = 512
        private const val MAX_QUESTION_CHARS = 2_000
        private const val MAX_ANSWER_CHARS = 2_000
        private const val MAX_STUDENT_ANSWER_CHARS = 2_000
        private const val MAX_POINT_CHARS = 200
        private const val MAX_EVIDENCE_ITEMS = 5
        private const val MAX_EVIDENCE_CHARS = 600
        private const val MAX_NOTE_CHARS = 200
        private const val MAX_RATIONALE_CHARS = 600
        private const val MAX_UNCERTAINTIES = 5

        val SYSTEM_PROMPT: String =
            "你是简答题评分复核器。只依据给出的题干、参考答案、评分要点和证据判断，不补充外部知识。" +
                "只输出一个 JSON 对象，字段：score（0 到满分的整数）、" +
                "rubric（数组，元素为 {point, hit, note}，point 必须来自评分要点原文）、" +
                "rationale（不超过 200 字）、uncertainties（数组，字符串）。" +
                "不要输出 JSON 之外的任何文字；不确定时把不确定性写进 uncertainties，不要编造。" +
                "你的输出只是建议，不会直接改分。"

        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /** 从可能带说明文字的输出里取出第一个完整 JSON 对象。 */
        fun extractJsonObject(raw: String): String? {
            val start = raw.indexOf('{')
            if (start < 0) return null
            var depth = 0
            var inString = false
            var escaped = false
            for (index in start until raw.length) {
                val ch = raw[index]
                when {
                    escaped -> escaped = false
                    ch == '\\' && inString -> escaped = true
                    ch == '"' -> inString = !inString
                    inString -> Unit
                    ch == '{' -> depth += 1
                    ch == '}' -> {
                        depth -= 1
                        if (depth == 0) return raw.substring(start, index + 1)
                    }
                }
            }
            return null
        }

        /** 建议指纹：同一模型/版本/分值/理由 → 同一 key（重复采纳幂等）。 */
        fun fingerprintOf(
            attemptId: String,
            itemId: String,
            score: Int,
            model: String,
            rubricVersion: String,
            rationale: String,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val payload = "$attemptId|$itemId|$score|$model|$rubricVersion|$rationale"
            return digest.digest(payload.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
                .take(16)
        }
    }
}

/**
 * 采纳 AI 复核建议（W5-10）。
 *
 * 只有用户点"采纳"才走到这里；写入完全复用四期 `GradeAssessmentService.reviseGrade`：
 *  - `gradingMethod = AI_REVIEW`、note 带理由、原分在事件里可回看；
 *  - `assessmentKey` 由建议指纹决定 → 同一建议重复采纳返回 Duplicate/AlreadyApplied，
 *    `grade_revision` 不会增长，也不会重复统计；
 *  - 越界建议在这里再拦一次（双保险），不写库。
 */
class AiReviewAdoption(
    private val gradings: GradeAssessmentService,
) {
    fun assessmentKey(suggestion: AiRubricReviewer.ReviewSuggestion): String =
        keyFor(suggestion.attemptId, suggestion.itemId, suggestion.fingerprint)

    suspend fun adopt(
        ctx: AccessContext,
        suggestion: AiRubricReviewer.ReviewSuggestion,
        baseGradeRevision: Long,
    ): ReviseGradeResult {
        if (suggestion.score !in 0..suggestion.maxScore) {
            return ReviseGradeResult.Rejected("SCORE_OUT_OF_RANGE")
        }
        return gradings.reviseGrade(
            ctx = ctx,
            attemptId = suggestion.attemptId,
            itemId = suggestion.itemId,
            baseGradeRevision = baseGradeRevision,
            newAssessment = GradeAssessment(
                score = suggestion.score,
                isCorrect = null,
                gradingMethod = GradeAssessment.GRADING_METHOD_AI_REVIEW,
                note = suggestion.rationale.take(300),
            ),
            reason = REASON_AI_REVIEW,
            assessmentKey = assessmentKey(suggestion),
        )
    }

    companion object {
        const val REASON_AI_REVIEW = "AI_REVIEW_ADOPTED"

        /** 幂等键：同一建议（同一指纹）重复采纳只会命中一次。 */
        fun keyFor(attemptId: String, itemId: String, fingerprint: String): String =
            "ai-review:$attemptId:$itemId:$fingerprint"
    }
}
