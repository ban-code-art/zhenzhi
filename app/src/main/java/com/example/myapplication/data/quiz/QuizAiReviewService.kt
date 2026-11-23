package com.example.myapplication.data.quiz

import com.example.myapplication.data.agent.KtorChatStreamExecutor
import com.example.myapplication.data.agent.SecretResolution
import com.example.myapplication.data.ai.adapters.AnthropicAdapter
import com.example.myapplication.data.ai.adapters.ChatAdapter
import com.example.myapplication.data.ai.adapters.GeminiAdapter
import com.example.myapplication.data.ai.adapters.OpenAiChatAdapter
import com.example.myapplication.data.ai.adapters.OpenAiResponsesAdapter
import com.example.myapplication.data.ai.config.ProfileRepository
import com.example.myapplication.data.ai.config.ProfileRuntime
import com.example.myapplication.data.ai.config.EndpointOperation
import com.example.myapplication.data.ai.config.EndpointResolver
import com.example.myapplication.data.ai.net.KtorTransport
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.search.EnhancementToggles
import com.example.myapplication.domain.ai.EndpointHash
import com.example.myapplication.domain.ai.ProfilePurpose
import com.example.myapplication.domain.ai.RunProfileSnapshot
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.quiz.QuizCodecs
import com.example.myapplication.domain.quiz.QuizItemType
import com.example.myapplication.domain.quiz.QuizRawAnswers

/** Short answer review stays suggestion-only; all outbound content is assembled from one saved item. */
class QuizAiReviewService(
    private val database: AppDatabase,
    private val profileRepository: ProfileRepository,
    private val profileRuntime: ProfileRuntime,
    private val transport: KtorTransport,
    private val toggles: EnhancementToggles,
    private val adoption: AiReviewAdoption,
) {
    suspend fun isEnabled(userId: Long): Boolean = toggles.isAiReviewEnabled(userId)

    suspend fun review(
        ctx: AccessContext,
        snapshot: QuizAttemptSnapshot,
        itemId: String,
    ): AiRubricReviewer.ReviewOutcome {
        if (!toggles.isAiReviewEnabled(ctx.userId)) {
            return AiRubricReviewer.ReviewOutcome.Failed("AI_REVIEW_DISABLED", "请先在设置中开启 AI 简答复核", retryable = false)
        }
        val item = snapshot.paper.items.firstOrNull { it.itemId == itemId }
            ?: return AiRubricReviewer.ReviewOutcome.Rejected("ITEM_NOT_FOUND", "题目不在当前试卷中")
        if (item.type != QuizItemType.ESSAY) {
            return AiRubricReviewer.ReviewOutcome.Rejected("NOT_SHORT_ANSWER", "只有简答题可以请求复核")
        }
        val answer = QuizRawAnswers.parse(snapshot.answerFor(itemId)?.rawAnswerJson).text.orEmpty().trim()
        if (answer.isBlank()) {
            return AiRubricReviewer.ReviewOutcome.Rejected("ANSWER_EMPTY", "先提交这道简答题的答案")
        }
        val settings = database.aiProfileDao().findSettings(ctx.userId)
            ?: return AiRubricReviewer.ReviewOutcome.Failed("CHAT_PROFILE_MISSING", "没有可用的 CHAT 配置", retryable = false)
        val profileId = settings.activeChatProfileId
            ?: return AiRubricReviewer.ReviewOutcome.Failed("CHAT_PROFILE_MISSING", "没有可用的 CHAT 配置", retryable = false)
        val profile = profileRepository.find(ctx.userId, profileId)
            ?.takeIf { it.enabled && it.purpose == ProfilePurpose.CHAT }
            ?: return AiRubricReviewer.ReviewOutcome.Failed("CHAT_PROFILE_MISSING", "当前 CHAT 配置不可用", retryable = false)
        val configuration = when (val result = EndpointResolver.normalizeConfiguration(
            profile.protocol, ProfilePurpose.CHAT, profile.endpoint, profile.paramsJson, profile.model,
        )) {
            is EndpointResolver.ConfigurationResult.Ok -> result
            is EndpointResolver.ConfigurationResult.Invalid -> return AiRubricReviewer.ReviewOutcome.Failed(
                result.code,
                result.reason,
                retryable = false,
            )
        }
        val initialProfile = RunProfileSnapshot(
            profileId = profile.id,
            configRevision = profile.configRevision,
            protocol = profile.protocol,
            endpointHash = EndpointHash.of(configuration.endpoint),
            endpoint = configuration.endpoint,
            model = profile.model,
            paramsJson = configuration.paramsJson,
            capabilitiesHash = profile.capabilityProfileHash,
            secretVersionRef = profile.secretRef,
        )
        val runProfile = when (val frozen = EndpointResolver.freezeSnapshot(
            initialProfile, ProfilePurpose.CHAT, setOf(EndpointOperation.CHAT_STREAM),
        )) {
            is EndpointResolver.SnapshotResult.Ok -> frozen.snapshot
            is EndpointResolver.SnapshotResult.Invalid ->
                return AiRubricReviewer.ReviewOutcome.Failed(frozen.code, frozen.reason, retryable = false)
        }
        val endpoint = when (val resolved = EndpointResolver.resolveSnapshot(
            runProfile, ProfilePurpose.CHAT, EndpointOperation.CHAT_STREAM,
        )) {
            is EndpointResolver.Resolution.Ok -> resolved.endpoint
            is EndpointResolver.Resolution.Invalid ->
                return AiRubricReviewer.ReviewOutcome.Failed(resolved.code, resolved.reason, retryable = false)
            is EndpointResolver.Resolution.Unsupported ->
                return AiRubricReviewer.ReviewOutcome.Failed("ENDPOINT_OPERATION_UNSUPPORTED", "CHAT 流式地址未配置", retryable = false)
        }
        val adapter = adapterFor(profile.protocol, profile.model)
        val reviewer = AiRubricReviewer(
            executor = KtorChatStreamExecutor(transport),
            adapter = adapter,
            endpoint = endpoint,
            profileId = profile.id,
            configRevision = profile.configRevision,
            model = profile.model,
            rubricVersion = item.templateVersion.ifBlank { "rubric-v1" },
            resolveSecret = {
                when (val secret = profileRuntime.resolveRunSecret(ctx.userId, ProfilePurpose.CHAT, runProfile)) {
                    is ProfileRuntime.SecretResolution.Ok -> SecretResolution.Ok(secret.apiKey)
                    ProfileRuntime.SecretResolution.Revoked -> SecretResolution.Revoked
                    ProfileRuntime.SecretResolution.SecretMissing -> SecretResolution.SecretMissing
                }
            },
        )
        val evidence = snapshot.evidenceJsonByItem[itemId]?.let { raw ->
            runCatching { QuizCodecs.decodeEvidence(raw).map { it.quote } }.getOrDefault(emptyList())
        }.orEmpty()
        val outcome = reviewer.review(
            AiRubricReviewer.ReviewRequest(
                attemptId = snapshot.attempt.attemptId,
                itemId = itemId,
                question = item.stem,
                referenceAnswer = item.referencePoints.joinToString("\n"),
                studentAnswer = answer,
                rubricPoints = item.rubric.map { it.key },
                evidence = evidence,
                localScore = snapshot.answerFor(itemId)?.score,
                maxScore = item.score,
            ),
        )
        if (!toggles.isAiReviewEnabled(ctx.userId)) {
            return AiRubricReviewer.ReviewOutcome.Failed(
                "AI_REVIEW_REVOKED",
                "复核期间 AI 复核授权已撤回，已丢弃返回建议",
                retryable = false,
            )
        }
        return outcome
    }

    suspend fun adopt(
        ctx: AccessContext,
        suggestion: AiRubricReviewer.ReviewSuggestion,
        baseGradeRevision: Long,
    ): ReviseGradeResult = adoption.adopt(ctx, suggestion, baseGradeRevision)

    private fun adapterFor(protocol: com.example.myapplication.domain.ai.WireProtocol, model: String): ChatAdapter =
        when (protocol) {
            com.example.myapplication.domain.ai.WireProtocol.OPENAI_CHAT -> OpenAiChatAdapter(model)
            com.example.myapplication.domain.ai.WireProtocol.OPENAI_RESPONSES -> OpenAiResponsesAdapter(model)
            com.example.myapplication.domain.ai.WireProtocol.ANTHROPIC_MESSAGES -> AnthropicAdapter(model)
            com.example.myapplication.domain.ai.WireProtocol.GEMINI_NATIVE -> GeminiAdapter(model)
        }
}
