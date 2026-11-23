package com.example.myapplication.data.agent

import com.example.myapplication.core.AppLog
import com.example.myapplication.data.ai.adapters.AnthropicAdapter
import com.example.myapplication.data.ai.adapters.ChatAdapter
import com.example.myapplication.data.ai.adapters.GeminiAdapter
import com.example.myapplication.data.ai.adapters.OpenAiChatAdapter
import com.example.myapplication.data.ai.adapters.OpenAiResponsesAdapter
import com.example.myapplication.data.ai.config.ProfileRepository
import com.example.myapplication.data.ai.config.ProfileRuntime
import com.example.myapplication.data.ai.config.EndpointOperation
import com.example.myapplication.data.ai.config.EndpointResolver
import com.example.myapplication.data.ai.config.FinalEndpoint
import com.example.myapplication.data.ai.net.KtorTransport
import com.example.myapplication.data.ai.protocol.AiErrorClassifier
import com.example.myapplication.data.ai.protocol.ToolCallAccumulator
import com.example.myapplication.data.agent.evidence.AgentSourceRecord
import com.example.myapplication.data.agent.evidence.EvidenceRegistry
import com.example.myapplication.data.agent.evidence.PersistentSourceReferencePort
import com.example.myapplication.data.agent.evidence.SourceVerifier
import com.example.myapplication.data.agent.evidence.toAnswerSource
import com.example.myapplication.data.agent.notes.NoteProposalPort
import com.example.myapplication.data.agent.notes.NoteProposalTools
import com.example.myapplication.data.agent.notes.NoteReadReceiptRegistry
import com.example.myapplication.data.agent.tools.EncyclopediaTools
import com.example.myapplication.data.agent.tools.KnowledgeTools
import com.example.myapplication.data.agent.tools.NoteReadTools
import com.example.myapplication.data.agent.tools.WebSearchTool
import com.example.myapplication.data.encyclopedia.EncyclopediaAgentLaunchContext
import com.example.myapplication.data.encyclopedia.EncyclopediaEvidenceExtractor
import com.example.myapplication.data.encyclopedia.EncyclopediaEvidenceVerifier
import com.example.myapplication.data.encyclopedia.EncyclopediaLoadState
import com.example.myapplication.data.encyclopedia.EncyclopediaPackRepository
import com.example.myapplication.data.encyclopedia.EncyclopediaSnapshot
import com.example.myapplication.data.encyclopedia.EncyclopediaVerificationResult
import com.example.myapplication.data.encyclopedia.EncyclopediaVerifiedExtract
import com.example.myapplication.data.knowledge.retrieval.HybridRetriever
import com.example.myapplication.data.knowledge.retrieval.RetrievalScopeResolver
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.reader.notes.AnnotationRepository
import com.example.myapplication.data.reader.notes.NoteRepository
import com.example.myapplication.data.reader.notes.SourceReferenceStore
import com.example.myapplication.domain.agent.ToolExecutionContext
import com.example.myapplication.domain.agent.ToolRegistry
import com.example.myapplication.domain.agent.ToolResult
import com.example.myapplication.domain.agent.ToolSpec
import com.example.myapplication.domain.ai.ChatEvent
import com.example.myapplication.domain.ai.CapabilityProfile
import com.example.myapplication.domain.ai.ChatMessage
import com.example.myapplication.domain.ai.ChatRequest
import com.example.myapplication.domain.ai.EndpointHash
import com.example.myapplication.domain.ai.FinishReason
import com.example.myapplication.domain.ai.ProfilePurpose
import com.example.myapplication.domain.ai.RunProfileSnapshot
import com.example.myapplication.domain.ai.ToolPolicy
import com.example.myapplication.domain.ai.WireProtocol
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.DocumentAccess
import com.example.myapplication.domain.reader.ReadingScope
import com.example.myapplication.domain.reader.ScopeRequest
import com.example.myapplication.domain.reader.ScopeSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** 每轮请求前的密钥解析结果：撤权/密文缺失都不得继续外发。 */
sealed interface SecretResolution {
    data class Ok(val apiKey: String?) : SecretResolution
    data object Revoked : SecretResolution
    data object SecretMissing : SecretResolution
}

/** 流式请求端口：生产走 [KtorChatStreamExecutor]，测试注入确定性 fake。 */
fun interface ChatStreamExecutor {
    suspend fun stream(
        request: ChatRequest,
        adapter: ChatAdapter,
        apiKey: String?,
        endpoint: FinalEndpoint,
        onEvent: suspend (ChatEvent) -> Unit,
    )
}

/** 生产实现：三/四期 ChatAdapter + 统一 Ktor 传输层（不整轮自动重发）。 */
class KtorChatStreamExecutor(
    private val transport: KtorTransport,
) : ChatStreamExecutor {
    override suspend fun stream(
        request: ChatRequest,
        adapter: ChatAdapter,
        apiKey: String?,
        endpoint: FinalEndpoint,
        onEvent: suspend (ChatEvent) -> Unit,
    ) {
        val operation = if (request.stream) EndpointOperation.CHAT_STREAM else EndpointOperation.CHAT_SINGLE
        if (endpoint.operation != operation) {
            onEvent(ChatEvent.ProviderError("ENDPOINT_OPERATION_MISMATCH", retryable = false))
            return
        }
        val httpRequest = adapter.buildChatRequest(request, apiKey, endpoint)
        if (!request.stream) {
            val response = transport.execute(httpRequest)
            if (response.status !in 200..299) {
                val error = AiErrorClassifier.fromHttp(response.status)
                onEvent(ChatEvent.ProviderError(error.code, error.retryable))
                return
            }
            adapter.decodeNonStream(response.body).forEach { onEvent(it) }
            return
        }
        val accumulator = ToolCallAccumulator()
        val status = try {
            transport.stream(httpRequest) { frame ->
                adapter.decodeStreamFrame(frame, accumulator).forEach { onEvent(it) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onEvent(ChatEvent.ProviderError("NETWORK_ERROR", retryable = true))
            return
        }
        if (status !in 200..299) {
            val error = AiErrorClassifier.fromHttp(status)
            onEvent(ChatEvent.ProviderError(error.code, error.retryable))
        } else if (accumulator.incompleteIndices().isNotEmpty()) {
            // EOF cannot turn a partially assembled tool request into a successful answer.
            onEvent(ChatEvent.ProviderError("INCOMPLETE_TOOL_CALL", retryable = true))
        }
    }
}

/** 单次 run 的硬预算（设计 §2：8 轮 / 24 次工具 / 180s）。 */
data class RuntimeLimits(
    val maxRounds: Int = 8,
    val maxToolCalls: Int = 24,
    val deadlineMs: Long = 180_000L,
) {
    init {
        require(maxRounds in 1..64) { "maxRounds must be in 1..64" }
        require(maxToolCalls in 1..256) { "maxToolCalls must be in 1..256" }
        require(deadlineMs in 1_000..1_800_000) { "deadlineMs must be in 1s..30min" }
    }
}

/**
 * 运行循环需要的工具派发端口（W4-02 接线用）。
 *
 * 生产实现是 [ToolDispatcher]（幂等 + 落库 + 取消检查）；这里抽出端口只为让
 * 运行循环可以在**纯 JVM 单测**里注入确定性 fake（不需要 Room/Android），
 * 不改变任何生产语义。默认参数从本声明继承，[ToolDispatcher] 的旧调用点不受影响。
 */
interface AgentToolDispatchPort {
    suspend fun dispatch(
        context: ToolExecutionContext,
        call: com.example.myapplication.domain.ai.ToolCall,
        state: ToolDispatcher.PathState,
        isCancelled: () -> Boolean = { false },
    ): ToolDispatcher.DispatchOutcome?
}

data class AgentRunRequest(
    val runId: String,
    val userId: Long,
    val sessionEpoch: Long = 0,
    val requestId: String,
    val snapshot: RunProfileSnapshot,
    val adapter: ChatAdapter,
    val secretResolver: suspend () -> SecretResolution,
    val tools: List<ToolSpec> = emptyList(),
    val history: List<ChatMessage> = emptyList(),
    val userMessage: ChatMessage,
    val readingContext: ReadingContext? = null,
    val evidenceText: List<String> = emptyList(),
    val contextWindowTokens: Int? = null,
    val limits: RuntimeLimits = RuntimeLimits(),
    /**
     * 回答级证据注册表（详细设计 §13）：生产由容器工厂按 run 新建注入。
     * 为 null 时保持旧行为（不注册证据、不向模型注入 [S#] 编号），便于既有测试。
     */
    val evidenceRegistry: EvidenceRegistry? = null,
    val encyclopediaEvidence: List<EncyclopediaVerifiedExtract> = emptyList(),
)

/** 运行事件/消息落盘回调（由 RunCoordinator 实现，负责 seq 与状态机）。 */
interface AgentRuntimeSink {
    suspend fun onEvent(type: String, toolCallId: String?, payloadJson: String)
    suspend fun onAssistantMessage(message: ChatMessage, status: String)
    /** tool-result 组按独立消息落库，保证历史重放时 tool_call 与 tool_result 成对。 */
    suspend fun onToolMessage(message: ChatMessage)
    /** 一轮 assistant 段结束（含工具调用组），下一段文本应新建消息行而不是覆盖旧段。 */
    suspend fun onAssistantTurnFinished()
    suspend fun isCancelled(): Boolean
}

data class AgentRunResult(
    val status: String,
    val stopReason: String,
    val rounds: Int,
    val toolCalls: Int,
    val tokenUsage: Long,
    val finalMessage: ChatMessage?,
    val errorCode: String? = null,
    /**
     * 本轮回答来源快照（W4-02）：由回答级 registry 分配 S 编号并带持久 referenceId，
     * 供 RunCoordinator 写入 `ai_messages.sources_json`（重启后可回读/回链）。
     * 无工具证据时为 emptyList。
     */
    val sources: List<AgentSourceRecord> = emptyList(),
)

/**
 * Agent 运行循环（W4-04 / 详细设计 §2、§12、§20；W4-02 来源接线 §13、§21）。
 *
 * - 每轮请求前重新解析密钥：撤权/密文缺失立即终止，不外发；
 * - 事件 seq 由 sink 持久（重订阅按 seq 恢复），文本按间隔 checkpoint；
 * - Finish LENGTH 不当完整结果；Finish TOOL_CALLS 必须派发完整工具组后再进下一轮；
 * - 工具结果带回的 [ToolResult.evidence] 立即注册到回答级 [EvidenceRegistry]，
 *   并把 registry 分配的 `[S#]` 编号 + 持久 referenceId 回注进喂给模型的 tool result JSON，
 *   模型只能按已核验编号引用；本轮结束把来源快照交给 [AgentRunResult.sources]；
 * - 8 轮 / 24 工具 / 180s 到界：保存已获取内容并给出停止原因，不伪造总结。
 */
class AgentRuntime(
    private val executor: ChatStreamExecutor,
    private val dispatcher: AgentToolDispatchPort?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(request: AgentRunRequest, sink: AgentRuntimeSink): AgentRunResult {
        val executionJob = currentCoroutineContext()[Job]
        val assembler = ContextAssembler(clock)
        val finalAnswerPrefix = FINAL_ANSWER_PREFIX.takeIf { request.tools.isNotEmpty() }
        // 回答级证据registry：一次 run 就是一次回答，[S#] 编号在同一回答内单调且可复用。
        val evidence = AnswerEvidenceTracker(request.evidenceRegistry, request.runId)
        val seededEvidenceLines = if (request.encyclopediaEvidence.isNotEmpty()) {
            val access = AccessContext(request.userId, request.sessionEpoch, request.requestId)
            request.encyclopediaEvidence.mapNotNull { extract ->
                val reg = request.evidenceRegistry?.registerEncyclopedia(
                    context = access,
                    turnId = request.runId,
                    locator = extract.locator,
                    title = extract.title,
                    quote = extract.quote,
                )
                if (reg is EvidenceRegistry.RegisterOutcome.Accepted) {
                    "[${reg.source.citation}] ${extract.title}（${extract.sectionTitle}）：${extract.quote}"
                } else {
                    null
                }
            }
        } else {
            emptyList()
        }
        val assembled = assembler.assemble(
            userMessage = request.userMessage,
            history = request.history,
            readingContext = request.readingContext,
            evidenceText = request.evidenceText + seededEvidenceLines,
            contextWindowTokens = request.contextWindowTokens,
            deadlineMs = request.limits.deadlineMs,
            finalAnswerPrefix = finalAnswerPrefix,
        )
        val conversation = assembled.messages.toMutableList()
        val deadlineAt = clock() + request.limits.deadlineMs
        val pathState = ToolDispatcher.PathState()
        var rounds = 0
        var toolCalls = 0
        var tokenUsage = 0L
        val endpoint = when (val resolved = EndpointResolver.resolveSnapshot(
            request.snapshot, ProfilePurpose.CHAT, EndpointOperation.CHAT_STREAM,
        )) {
            is EndpointResolver.Resolution.Ok -> resolved.endpoint
            is EndpointResolver.Resolution.Invalid ->
                return errorResult(resolved.code, rounds, toolCalls, tokenUsage, evidence.sources())
            is EndpointResolver.Resolution.Unsupported ->
                return errorResult("ENDPOINT_OPERATION_UNSUPPORTED", rounds, toolCalls, tokenUsage, evidence.sources())
        }

        while (true) {
            if (sink.isCancelled()) return cancelledResult(rounds, toolCalls, tokenUsage, evidence.sources())
            if (rounds >= request.limits.maxRounds) {
                return finishBudget("BUDGET_ROUNDS", rounds, toolCalls, tokenUsage, sink, conversation, evidence)
            }
            if (clock() > deadlineAt) {
                return finishBudget("BUDGET_DEADLINE", rounds, toolCalls, tokenUsage, sink, conversation, evidence)
            }
            val apiKey = when (val secret = request.secretResolver()) {
                is SecretResolution.Ok -> secret.apiKey
                SecretResolution.Revoked ->
                    return errorResult("ACCESS_REVOKED", rounds, toolCalls, tokenUsage, evidence.sources())

                SecretResolution.SecretMissing ->
                    return errorResult("SECRET_MISSING", rounds, toolCalls, tokenUsage, evidence.sources())
            }
            rounds += 1
            sink.onEvent(EVENT_ROUND_STARTED, null, """{"round":$rounds}""")
            val chatRequest = ChatRequest(
                runId = request.runId,
                profileId = request.snapshot.profileId,
                configRevision = request.snapshot.configRevision,
                messages = conversation.toList(),
                tools = request.tools.map { spec ->
                    com.example.myapplication.domain.ai.ToolSpec(
                        name = spec.name,
                        description = spec.description,
                        parametersJson = spec.parameters.toString(),
                    )
                },
                toolPolicy = if (request.tools.isEmpty()) ToolPolicy.NONE else ToolPolicy.AUTO,
                stream = true,
                budget = assembled.budget,
            )
            val text = StringBuilder()
            var toolStarted = false
            var lastCheckpoint = 0
            var lastCheckpointAt = clock()
            val completedCalls = mutableListOf<com.example.myapplication.domain.ai.ToolCall>()
            val completedById = linkedMapOf<String, com.example.myapplication.domain.ai.ToolCall>()
            var requestInputTokens = 0L
            var requestOutputTokens = 0L
            var finish: FinishReason? = null
            var providerError: ChatEvent.ProviderError? = null
            try {
                executor.stream(chatRequest, request.adapter, apiKey, endpoint) { event ->
                    if (executionJob?.isActive == false || sink.isCancelled()) return@stream
                    when (event) {
                        is ChatEvent.TextDelta -> {
                            if (event.text.isEmpty()) return@stream
                            text.append(event.text)
                            // With tools available, ordinary text can precede a tool call.
                            // Only an explicit final-answer boundary permits early delivery.
                            if (toolStarted) return@stream
                            val contentStart = if (finalAnswerPrefix == null) 0 else {
                                text.answerStart(finalAnswerPrefix) ?: return@stream
                            }
                            val visibleLength = text.length - contentStart
                            if (visibleLength <= 0) return@stream
                            val now = clock()
                            if (lastCheckpoint == 0 || visibleLength - lastCheckpoint >= CHECKPOINT_CHARS ||
                                now < lastCheckpointAt || now - lastCheckpointAt >= STREAM_UPDATE_MILLIS) {
                                lastCheckpoint = visibleLength
                                lastCheckpointAt = now
                                sink.onAssistantMessage(ChatMessage.assistant(text.substring(contentStart)), STATUS_STREAMING)
                            }
                        }

                        is ChatEvent.ToolComplete -> {
                            toolStarted = true
                            val call = com.example.myapplication.domain.ai.ToolCall(event.callId, event.name, event.argumentsJson)
                            val previous = completedById[event.callId]
                            if (previous == null) {
                                completedById[event.callId] = call
                                completedCalls += call
                            } else if (previous != call) {
                                providerError = ChatEvent.ProviderError("INVALID_PROVIDER_RESPONSE", retryable = false)
                            }
                        }

                        is ChatEvent.Usage -> {
                            requestInputTokens = maxOf(requestInputTokens, event.inputTokens.toLong())
                            requestOutputTokens = maxOf(requestOutputTokens, event.outputTokens.toLong())
                        }
                        is ChatEvent.Finish -> finish = event.reason
                        is ChatEvent.ProviderError -> providerError = event
                        is ChatEvent.ToolDelta -> toolStarted = true
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                providerError = ChatEvent.ProviderError("TRANSPORT_ERROR", retryable = true)
            }
            tokenUsage += requestInputTokens + requestOutputTokens
            if (sink.isCancelled()) return cancelledResult(rounds, toolCalls, tokenUsage, evidence.sources())
            providerError?.let { error ->
                sink.onEvent(EVENT_RUN_ERROR, null, """{"code":"${error.code}","round":$rounds}""")
                return errorResult(error.code, rounds, toolCalls, tokenUsage, evidence.sources())
            }
            // Gemini finishes a complete functionCall with STOP. Its wire finish reason stays
            // unchanged; the runtime must still return the tool result before asking for an answer.
            val effectiveFinish = if (finish == FinishReason.STOP && completedCalls.isNotEmpty())
                FinishReason.TOOL_CALLS else finish
            when (effectiveFinish) {
                FinishReason.TOOL_CALLS -> {
                    val assistant = ChatMessage(
                        role = "assistant",
                        parts = listOfNotNull(text.toString().takeIf { it.isNotEmpty() }?.let { com.example.myapplication.domain.ai.MessagePart.Text(it) }),
                        toolCalls = completedCalls,
                        contentPreview = text.toString(),
                    )
                    conversation += assistant
                    sink.onAssistantMessage(assistant, ConversationRepository.STATUS_COMPLETE)
                    sink.onAssistantTurnFinished()
                    if (completedCalls.isEmpty()) {
                        return errorResult("EMPTY_TOOL_CALLS", rounds, toolCalls, tokenUsage, evidence.sources())
                    }
                    for (call in completedCalls) {
                        if (sink.isCancelled()) return cancelledResult(rounds, toolCalls, tokenUsage, evidence.sources())
                        if (toolCalls >= request.limits.maxToolCalls) {
                            return finishBudget("BUDGET_TOOL_CALLS", rounds, toolCalls, tokenUsage, sink, conversation, evidence)
                        }
                        toolCalls += 1
                        sink.onEvent(EVENT_TOOL_CALL, call.callId, """{"tool":"${call.name}"}""")
                        val toolContext = ToolExecutionContext(
                            runId = request.runId,
                            userId = request.userId,
                            sessionEpoch = request.sessionEpoch,
                            requestId = request.requestId,
                            eventSeq = 0,
                            deadlineAtMs = deadlineAt,
                        )
                        val outcome = dispatcher?.dispatch(
                            context = toolContext,
                            call = call,
                            state = pathState,
                            isCancelled = { executionJob?.isActive == false },
                        )
                        if (outcome == null) {
                            return errorResult("CAPABILITY_UNSUPPORTED", rounds, toolCalls, tokenUsage, evidence.sources())
                        }
                        if (outcome.cancelled || executionJob?.isActive == false || sink.isCancelled()) return cancelledResult(rounds, toolCalls, tokenUsage, evidence.sources())
                        // 注册证据并把合法 [S#] 编号回注给模型（事件与工具消息用同一份 JSON，
                        // 保证“模型看到的”与“历史重放读到的”一致）。
                        val toolResultJson = evidence.annotateToolResult(toolContext.accessContext(), outcome.result)
                        sink.onEvent(
                            if (outcome.result.ok) EVENT_TOOL_RESULT else EVENT_TOOL_ERROR,
                            call.callId,
                            toolResultJson.toString(),
                        )
                        conversation += ChatMessage.toolResult(call.callId, toolResultJson.toString())
                        sink.onToolMessage(conversation.last())
                        if (outcome.terminateToolPath) {
                            return errorResult("TOOL_ARGUMENT_INVALID", rounds, toolCalls, tokenUsage, evidence.sources())
                        }
                    }
                }

                FinishReason.STOP, FinishReason.LENGTH, null -> {
                    val contentStart = finalAnswerPrefix?.let(text::answerStart) ?: 0
                    val content = text.substring(contentStart)
                    val reason = when {
                        finish == FinishReason.LENGTH -> "LENGTH"
                        finish == null -> "EOF"
                        else -> "STOP"
                    }
                    val assistant = ChatMessage.assistant(content)
                    conversation += assistant
                    sink.onAssistantMessage(assistant, ConversationRepository.STATUS_COMPLETE)
                    // 回答里的 [S99] 不在 registry 即伪造引用：只记事件，不改写模型原文、不伪造来源。
                    evidence.reportUnknownCitations(sink, content)
                    return AgentRunResult(
                        status = STATUS_COMPLETED,
                        stopReason = reason,
                        rounds = rounds,
                        toolCalls = toolCalls,
                        tokenUsage = tokenUsage,
                        finalMessage = assistant,
                        sources = evidence.sources(),
                    )
                }

                FinishReason.REFUSAL, FinishReason.CONTENT_FILTER ->
                    return errorResult("PROVIDER_REFUSAL", rounds, toolCalls, tokenUsage, evidence.sources())

                else -> return errorResult("PROVIDER_ERROR", rounds, toolCalls, tokenUsage, evidence.sources())
            }
        }
    }

    private suspend fun finishBudget(
        reason: String,
        rounds: Int,
        toolCalls: Int,
        tokenUsage: Long,
        sink: AgentRuntimeSink,
        conversation: MutableList<ChatMessage>,
        evidence: AnswerEvidenceTracker,
    ): AgentRunResult {
        val partialText = conversation.asReversed().firstOrNull { it.role == "assistant" }
            ?.parts
            ?.filterIsInstance<com.example.myapplication.domain.ai.MessagePart.Text>()
            ?.joinToString("") { it.text }
            .orEmpty()
        val assistant = ChatMessage.assistant(partialText)
        if (partialText.isNotEmpty()) {
            sink.onAssistantMessage(assistant, ConversationRepository.STATUS_COMPLETE)
            evidence.reportUnknownCitations(sink, partialText)
        }
        sink.onEvent(EVENT_RUN_STOPPED, null, """{"reason":"$reason"}""")
        return AgentRunResult(
            status = STATUS_COMPLETED,
            stopReason = reason,
            rounds = rounds,
            toolCalls = toolCalls,
            tokenUsage = tokenUsage,
            finalMessage = assistant.takeIf { partialText.isNotEmpty() },
            sources = evidence.sources(),
        )
    }

    private fun cancelledResult(
        rounds: Int,
        toolCalls: Int,
        tokenUsage: Long,
        sources: List<AgentSourceRecord>,
    ): AgentRunResult = AgentRunResult(
        STATUS_CANCELLED,
        "CANCELLED",
        rounds,
        toolCalls,
        tokenUsage,
        finalMessage = null,
        sources = sources,
    )

    private fun errorResult(
        code: String,
        rounds: Int,
        toolCalls: Int,
        tokenUsage: Long,
        sources: List<AgentSourceRecord> = emptyList(),
    ): AgentRunResult = AgentRunResult(
        STATUS_ERROR,
        code,
        rounds,
        toolCalls,
        tokenUsage,
        finalMessage = null,
        errorCode = code,
        sources = sources,
    )

    companion object {
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_ERROR = "ERROR"
        const val STATUS_CANCELLED = "CANCELLED"

        const val EVENT_ROUND_STARTED = "ROUND_STARTED"
        const val EVENT_TOOL_CALL = "TOOL_CALL"
        const val EVENT_TOOL_RESULT = "TOOL_RESULT"
        const val EVENT_TOOL_ERROR = "TOOL_ERROR"
        const val EVENT_RUN_STOPPED = "RUN_STOPPED"
        const val EVENT_RUN_ERROR = "RUN_ERROR"

        /** 回答引用了 registry 里不存在的编号（如 [S99]）：只记录，不改写回答。 */
        const val EVENT_CITATION_INVALID = "CITATION_INVALID"

        const val STATUS_STREAMING = "STREAMING"
        const val CHECKPOINT_CHARS = 512
        internal const val FINAL_ANSWER_PREFIX = "【S1_FINAL_ANSWER】"
        const val STREAM_UPDATE_MILLIS = 100L
    }
}

private fun CharSequence.answerStart(prefix: String): Int? {
    val start = indexOfFirst { !it.isWhitespace() }
    return if (start >= 0 && startsWith(prefix, start)) start + prefix.length else null
}

/**
 * 回答级来源接线（W4-02；详细设计 §13、§21）。
 *
 * - [annotateToolResult]：把工具结果携带的 [com.example.myapplication.domain.reader.SourceEvidence]
 *   逐条注册进 [EvidenceRegistry]（原文核验 + 持久 referenceId），把 `[S#]`/referenceId
 *   以 `sources` 数组回注给模型；被拒绝的证据明确标记 REJECTED + 错误码，绝不给可引用编号；
 * - [sources]：本轮来源快照，进入 [AgentRunResult.sources]，由 RunCoordinator 持久化；
 * - [reportUnknownCitations]：模型引用未注册编号时记录 CITATION_INVALID 事件.
 *
 * registry 为 null 时（旧测试/受限运行）全部退化为透传，不改变任何既有行为。
 */
private class AnswerEvidenceTracker(
    private val registry: EvidenceRegistry?,
    private val turnId: String,
) {
    suspend fun annotateToolResult(
        context: com.example.myapplication.domain.reader.AccessContext,
        result: ToolResult,
    ): JsonObject {
        val base = result.toJson()
        if (registry == null || (result.evidence.isEmpty() && result.encyclopediaEvidence.isEmpty())) return base
        val citations = buildJsonArray {
            result.evidence.forEachIndexed { index, source ->
                when (val outcome = registry.register(context, turnId, source)) {
                    is EvidenceRegistry.RegisterOutcome.Accepted -> addJsonObject {
                        put("evidenceIndex", index)
                        put("citation", outcome.source.citation)
                        put("referenceId", outcome.source.referenceId)
                        put("documentId", outcome.source.documentId)
                        put("sectionId", outcome.source.sectionId)
                        put("contentRevision", outcome.source.contentRevision)
                        put("status", STATUS_CITED)
                    }

                    is EvidenceRegistry.RegisterOutcome.Rejected -> addJsonObject {
                        put("evidenceIndex", index)
                        put("documentId", source.documentId)
                        put("sectionId", source.sectionId)
                        put("status", STATUS_REJECTED)
                        put("code", outcome.code.name)
                        put("reason", outcome.reason)
                    }
                }
            }
            result.encyclopediaEvidence.forEachIndexed { offset, enc ->
                val idx = result.evidence.size + offset
                when (val outcome = registry.registerEncyclopedia(context, turnId, enc.locator, enc.title, enc.quote)) {
                    is EvidenceRegistry.RegisterOutcome.Accepted -> addJsonObject {
                        put("evidenceIndex", idx)
                        put("citation", outcome.source.citation)
                        put("referenceId", outcome.source.referenceId)
                        put("entryId", enc.locator.entryId)
                        put("sectionId", enc.locator.sectionId)
                        put("contentRevision", enc.locator.contentRevision)
                        put("status", STATUS_CITED)
                    }
                    is EvidenceRegistry.RegisterOutcome.Rejected -> addJsonObject {
                        put("evidenceIndex", idx)
                        put("entryId", enc.locator.entryId)
                        put("sectionId", enc.locator.sectionId)
                        put("status", STATUS_REJECTED)
                        put("code", outcome.code.name)
                        put("reason", outcome.reason)
                    }
                }
            }
        }
        return buildJsonObject {
            base.forEach { (key, value) -> put(key, value) }
            put("sources", citations)
            put("citationHint", CITATION_HINT)
        }
    }

    fun sources(): List<AgentSourceRecord> =
        registry?.sourcesForTurn(turnId)?.map { it.toAnswerSource() }.orEmpty()

    suspend fun reportUnknownCitations(sink: AgentRuntimeSink, answer: String) {
        val active = registry ?: return
        if (answer.isBlank()) return
        val check = active.validateCitations(answer)
        if (check.isValid) return
        sink.onEvent(
            AgentRuntime.EVENT_CITATION_INVALID,
            null,
            buildJsonObject {
                putJsonArray("unknown") { check.unknown.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("cited") { check.cited.forEach { add(JsonPrimitive(it)) } }
            }.toString(),
        )
    }

    private companion object {
        const val STATUS_CITED = "CITED"
        const val STATUS_REJECTED = "REJECTED"

        /** 只告诉模型“怎么引用”，不把来源内容当指令注入（证据正文一律视为数据）。 */
        const val CITATION_HINT =
            "回答中引用来源时只能使用 sources[] 里 status=CITED 的 citation（如 [S1]）；" +
                "没有 citation 的证据不可引用，禁止编造编号。"
    }
}

/**
 * 容器级 runtime 工厂（task-11 极小接线）：把三期 DocumentAccess/HybridRetriever、
 * task-8 工具、task-10 笔记读取工具、笔记提议工具（W4-03/05 接线）与 CHAT profile/resolve secret
 * 组装成一次运行。
 *
 * 工具注册表分组（详细设计 §3）：
 *  - 只读内容：list_sources/read_outline/read_source/search_knowledge；
 *  - 笔记读取 + 笔记提议：read_note 成功后由应用侧签发 run 级 read receipt，
 *    propose_note_patch 只经 [NoteProposalPort] 落 PENDING 提议（写正文只发生在用户确认时）；
 *  - 测验产物：create_quiz_draft/get_quiz_status。
 *
 * 模型 capability 明确 toolCalling=UNSUPPORTED 时返回空工具表的受限模式：
 * 不暴露 propose_note_patch，也**不会**用自然语言正则解释模型文本去执行写操作。
 */
class ContainerAgentRuntimeFactory(
    private val database: AppDatabase,
    private val conversations: ConversationRepository,
    private val profileRepository: ProfileRepository,
    private val profileRuntime: ProfileRuntime,
    private val scopeResolver: RetrievalScopeResolver,
    private val documentAccess: DocumentAccess,
    private val retriever: HybridRetriever,
    private val noteRepository: NoteRepository,
    /** 笔记提议应用侧端口（生产 = NoteProposalService；工具只创建待确认提议）。 */
    private val noteProposalPort: NoteProposalPort,
    private val transport: KtorTransport,
    private val quizGenerationCoordinator: com.example.myapplication.data.quiz.QuizGenerationCoordinator,
    /** 联网检索/AI 评分复核的逐用户开关（默认关闭，见 W5-10）。 */
    private val enhancementToggles: com.example.myapplication.data.search.EnhancementToggles =
        com.example.myapplication.data.search.InMemoryEnhancementToggles(),
    /** 外部检索端口；未配置时工具返回 CAPABILITY_UNSUPPORTED，不伪造结果。 */
    private val externalSearchPort: com.example.myapplication.data.search.ExternalSearchPort =
        com.example.myapplication.data.search.UnconfiguredExternalSearchPort(),
    private val encyclopediaPackRepository: EncyclopediaPackRepository? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : RunCoordinator.RuntimeFactory {
    private suspend fun readyEncyclopediaSnapshot(): EncyclopediaSnapshot? {
        val repo = encyclopediaPackRepository ?: return null
        return repo.currentSnapshot ?: (repo.loadIfNeeded() as? EncyclopediaLoadState.Ready)?.snapshot
    }

    override suspend fun create(
        userId: Long,
        request: RunCoordinator.RunScopeRequest,
    ): RunCoordinator.PreparedRuntime {
        val ctx = AccessContext(userId = userId, sessionEpoch = 0, requestId = "agent-prepare-${request.conversationId}")
        val storedConversation = conversations.findConversation(userId, request.conversationId)
        val storedEncCtx = storedConversation?.scopeJson?.let { raw ->
            val obj = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            EncyclopediaAgentLaunchContext.fromJson(obj?.get("encyclopediaContext") as? JsonObject)
        }
        val encyclopediaCtx = request.readingContext?.encyclopediaContext ?: storedEncCtx
        val effectiveReadingContext = if (encyclopediaCtx != null && request.readingContext?.encyclopediaContext == null) {
            (request.readingContext ?: ReadingContext()).copy(encyclopediaContext = encyclopediaCtx)
        } else {
            request.readingContext
        }
        val scope = if (encyclopediaCtx != null) {
            ScopeSnapshot(userId = userId, selection = ReadingScope.CURRENT_SECTION, documents = emptyList())
        } else {
            when (val resolved = scopeResolver.resolve(ctx, request.scopeRequest)) {
                is RetrievalScopeResolver.Result.Ok -> resolved.snapshot
                is RetrievalScopeResolver.Result.Rejected -> throw IllegalStateException("SCOPE_REJECTED:${resolved.code}")
            }
        }
        val encyclopediaSeed = if (encyclopediaCtx != null) {
            val encSnapshot = readyEncyclopediaSnapshot()
            val checked = EncyclopediaEvidenceVerifier.verify(
                snapshot = encSnapshot,
                locator = encyclopediaCtx.toLocator(),
                expectedRevision = encyclopediaCtx.contentRevision,
                expectedQuote = encyclopediaCtx.selectedQuote,
            )
            when (checked) {
                is EncyclopediaVerificationResult.Verified -> listOf(checked.extract)
                is EncyclopediaVerificationResult.Rejected -> emptyList()
            }
        } else {
            emptyList()
        }
        val settings = database.aiProfileDao().findSettings(userId)
        val profileId = settings?.activeChatProfileId ?: throw IllegalStateException("NO_CHAT_PROFILE")
        // 向量路需要「当前选中的 embedding profile」指纹：传 null 会让 HybridRetriever 记
        // VECTOR_PROFILE_MISSING 并永久只走词法（三期 P1-1）。这里按与统计页相同口径解析。
        val embeddingFingerprint = resolveEmbeddingFingerprint(userId, settings.activeEmbeddingProfileId)
        val profile = profileRepository.find(userId, profileId) ?: throw IllegalStateException("CHAT_PROFILE_MISSING")
        require(profile.enabled) { "CHAT_PROFILE_DISABLED" }
        val configuration = when (val result = EndpointResolver.normalizeConfiguration(
            profile.protocol, ProfilePurpose.CHAT, profile.endpoint, profile.paramsJson, profile.model,
        )) {
            is EndpointResolver.ConfigurationResult.Ok -> result
            is EndpointResolver.ConfigurationResult.Invalid -> throw IllegalStateException(result.code)
        }
        val initialSnapshot = RunProfileSnapshot(
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
        val snapshot = when (val frozen = EndpointResolver.freezeSnapshot(
            initialSnapshot, ProfilePurpose.CHAT, setOf(EndpointOperation.CHAT_STREAM),
        )) {
            is EndpointResolver.SnapshotResult.Ok -> frozen.snapshot
            is EndpointResolver.SnapshotResult.Invalid -> throw IllegalStateException(frozen.code)
        }
        val adapter = adapterFor(profile.protocol, profile.model)
        // 能力门禁：探测明确 UNSUPPORTED 的模型不注册任何工具（受限模式），
        // 因此也不可能通过自然语言/正则触发 propose_note_patch 写提议。
        val toolCapable = profile.capabilities.toolCalling != CapabilityProfile.CapabilityStatus.UNSUPPORTED
        val receiptRegistry = NoteReadReceiptRegistry()
        val tools = if (!toolCapable) {
            emptyList()
        } else {
            buildList {
                if (encyclopediaCtx != null && encyclopediaPackRepository != null) {
                    addAll(EncyclopediaTools(encyclopediaPackRepository, encyclopediaCtx).tools())
                } else {
                    addAll(
                        KnowledgeTools(
                            documentAccess = documentAccess,
                            catalog = database.readerCatalogDao(),
                            retriever = retriever,
                            frozenScope = scope,
                            profileFingerprint = embeddingFingerprint,
                        ).tools(),
                    )
                }
                // 联网检索（W5-10）：只有用户显式授权时才注册。
                // 未授权时模型根本看不到这个工具（而不是"看到了但永远失败"），
                // 工具内部每次调用还会再查一次授权，支持运行期撤回。
                WebSearchTool.registeredWhen(
                    enabled = enhancementToggles.isWebSearchEnabled(userId),
                    authorization = enhancementToggles,
                    port = externalSearchPort,
                )?.let { add(it) }
                if (encyclopediaCtx == null) {
                    val noteReadTools = NoteReadTools(database, noteRepository, database.readerCatalogDao()).tools()
                    val plainReadNote = noteReadTools.firstOrNull { it.spec.name == "read_note" }
                        ?: error("read_note tool is required for note proposal receipt")
                    // read_note 包装（补发应用侧回执）与 propose_note_patch（只创建 PENDING 提议）。
                    addAll(NoteProposalTools(noteProposalPort, receiptRegistry, clock).tools(plainReadNote))
                    addAll(noteReadTools.filterNot { it.spec.name == "read_note" })
                    // W4-07 生成的 create_quiz_draft/get_quiz_status：只登记草稿与查状态，不抢当前会话 slot。
                    addAll(quizGenerationCoordinator.tools())
                }
            }
        }
        val registry = ToolRegistry(tools)
        val dispatcher = ToolDispatcher(database, registry, clock)
        val runtime = AgentRuntime(KtorChatStreamExecutor(transport), dispatcher, clock)
        val historyMessages = conversations.recentMessages(request.conversationId).mapNotNull { entity ->
            conversations.decode(entity)?.let { message ->
                ConversationCompressor.HistoryMessage(entity.id, message)
            }
        }
        val history = compressHistory(
            userId = userId,
            profile = profile,
            snapshot = snapshot,
            adapter = adapter,
            history = historyMessages,
        )
        return RunCoordinator.PreparedRuntime(
            runtime = runtime,
            snapshot = snapshot,
            adapter = adapter,
            secretResolver = {
                when (val resolved = profileRuntime.resolveRunSecret(userId, ProfilePurpose.CHAT, snapshot)) {
                    is ProfileRuntime.SecretResolution.Ok -> SecretResolution.Ok(resolved.apiKey)
                    ProfileRuntime.SecretResolution.Revoked -> SecretResolution.Revoked
                    ProfileRuntime.SecretResolution.SecretMissing -> SecretResolution.SecretMissing
                }
            },
            tools = registry.specs,
            contextWindowTokens = profile.capabilities.contextWindowTokens,
            readingContext = effectiveReadingContext,
            history = history,
            scope = scope,
            evidenceRegistry = newEvidenceRegistry(),
            encyclopediaEvidence = encyclopediaSeed,
        )
    }

    /**
     * 长会话压缩（W5-09）：历史 > 20 条且输入估算 ≥ 预算 60% 时，摘要早期完整 turn，
     * 保留最近 10 条 + 活跃工具组。
     *
     * 任何失败（没配置/超时/空结果/连续失败降级）都回滑动窗口：历史一条不丢、
     * 由 [ContextAssembler] 从最旧 turn 整组丢；日志只记错误码，不记对话正文。
     */
    private suspend fun compressHistory(
        userId: Long,
        profile: com.example.myapplication.domain.ai.ModelProfile,
        snapshot: RunProfileSnapshot,
        adapter: ChatAdapter,
        history: List<ConversationCompressor.HistoryMessage>,
    ): List<ChatMessage> {
        val plain = history.map { it.message }
        if (history.size <= ConversationCompressor.Config().triggerHistoryMessages) return plain
        val window = profile.capabilities.contextWindowTokens?.takeIf { it > 0 }
            ?: ContextAssembler.CONSERVATIVE_CONTEXT_WINDOW
        val outputReserve = (window / 2).coerceIn(256, 2_048)
        val inputLimit = (window - outputReserve).coerceAtLeast(1_024)
        val tokens = ConversationCompressor.estimateTokens(plain)
        val summaryEndpoint = when (val resolved = EndpointResolver.resolveSnapshot(
            snapshot, ProfilePurpose.CHAT, EndpointOperation.CHAT_STREAM,
        )) {
            is EndpointResolver.Resolution.Ok -> resolved.endpoint
            else -> return plain
        }
        val compressor = ConversationCompressor(
            summarizer = ChatHistorySummaryPort(
                executor = KtorChatStreamExecutor(transport),
                adapter = adapter,
                endpoint = summaryEndpoint,
                profileId = profile.id,
                configRevision = profile.configRevision,
                resolveSecret = { secretFor(userId, snapshot) },
                inputLimitTokens = inputLimit,
            ),
            clock = clock,
        )
        return when (
            val outcome = compressor.compress(
                history = history,
                inputTokens = tokens,
                inputLimit = inputLimit,
                sourceRevision = null,
                configFingerprint = snapshot.configRevision.toString(),
                model = profile.model,
            )
        ) {
            is ConversationCompressor.CompressionOutcome.Summarized -> outcome.messages
            is ConversationCompressor.CompressionOutcome.Failed -> {
                AppLog.swallowed(
                    "agent/summary",
                    "会话摘要失败，回滑动窗口：${outcome.code}",
                    IllegalStateException(outcome.detail),
                )
                plain
            }
            is ConversationCompressor.CompressionOutcome.Skipped -> plain
        }
    }

    /** 摘要复用与每轮请求同一套密钥解析：撤权/缺失就不发。 */
    private suspend fun secretFor(userId: Long, snapshot: RunProfileSnapshot): SecretResolution =
        when (val resolved = profileRuntime.resolveRunSecret(userId, ProfilePurpose.CHAT, snapshot)) {
            is ProfileRuntime.SecretResolution.Ok -> SecretResolution.Ok(resolved.apiKey)
            ProfileRuntime.SecretResolution.Revoked -> SecretResolution.Revoked
            ProfileRuntime.SecretResolution.SecretMissing -> SecretResolution.SecretMissing
        }

    /**
     * 每次 run 新建一个回答级注册表（详细设计 §13、§21；FX-31）：
     * - verifier 用三期 DocumentAccess 做固定 revision 原文核验（不重读 chunk 表）；
     * - references 复用二期 reader_source_references（按 userId 授权），
     *   使 [S1] 只是当次别名，长期回链走 referenceId；
     * - registry 生命周期随 run，编号在同一回答内单调、同区间复用。
     */
    private fun newEvidenceRegistry(): EvidenceRegistry = EvidenceRegistry(
        verifier = SourceVerifier(documentAccess),
        references = PersistentSourceReferencePort(
            SourceReferenceStore(AnnotationRepository(database, clock)),
        ),
        encyclopediaSnapshot = ::readyEncyclopediaSnapshot,
    )

    /**
     * 解析「当前选中的 embedding profile」指纹（与统计页 `KnowledgeStatsScreen` 同口径：
     * userId + 规范化 endpoint + protocol + model）。无配置/已禁用/端点非法时返回 null，
     * 此时 HybridRetriever 会如实记 VECTOR_PROFILE_MISSING 并只走词法，而不是伪装成已启用向量。
     */
    private suspend fun resolveEmbeddingFingerprint(userId: Long, embeddingProfileId: String?): String? {
        val id = embeddingProfileId?.takeIf { it.isNotBlank() } ?: return null
        val profile = profileRepository.find(userId, id) ?: return null
        if (!profile.enabled) return null
        return EndpointResolver.embeddingFingerprint(
            userId = userId,
            endpoint = profile.endpoint,
            protocol = profile.protocol,
            model = profile.model,
            paramsJson = profile.paramsJson,
        )
    }

    private fun adapterFor(protocol: WireProtocol, model: String): ChatAdapter = when (protocol) {
        WireProtocol.OPENAI_CHAT -> OpenAiChatAdapter(model)
        WireProtocol.OPENAI_RESPONSES -> OpenAiResponsesAdapter(model)
        WireProtocol.ANTHROPIC_MESSAGES -> AnthropicAdapter(model)
        WireProtocol.GEMINI_NATIVE -> GeminiAdapter(model)
    }
}
