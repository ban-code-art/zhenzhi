package com.example.myapplication.data.ai.config

import com.example.myapplication.data.ai.adapters.AnthropicAdapter
import com.example.myapplication.data.ai.adapters.ChatAdapter
import com.example.myapplication.data.ai.adapters.EmbeddingAdapter
import com.example.myapplication.data.ai.adapters.GeminiAdapter
import com.example.myapplication.data.ai.adapters.OpenAiChatAdapter
import com.example.myapplication.data.ai.adapters.OpenAiResponsesAdapter
import com.example.myapplication.data.ai.net.HttpExecutor
import com.example.myapplication.data.ai.net.KtorTransport
import com.example.myapplication.data.ai.protocol.AiErrorClassifier
import com.example.myapplication.data.ai.protocol.SseFrameParser
import com.example.myapplication.data.ai.protocol.ToolCallAccumulator
import com.example.myapplication.domain.ai.CapabilityProfile
import com.example.myapplication.domain.ai.CapabilityProfile.CapabilityStatus
import com.example.myapplication.domain.ai.ChatBudget
import com.example.myapplication.domain.ai.ChatEvent
import com.example.myapplication.domain.ai.ChatMessage
import com.example.myapplication.domain.ai.ChatRequest
import com.example.myapplication.domain.ai.ProfilePurpose
import com.example.myapplication.domain.ai.ToolCall
import com.example.myapplication.domain.ai.ToolPolicy
import com.example.myapplication.domain.ai.ToolSpec
import com.example.myapplication.domain.ai.WireProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * probe 专用传输端口。
 *
 * 生产实现只包装 [KtorTransport]（AppContainer 注入真实 transport）；单测注入受控 fake
 * 验证解码与失败分类。fake 结果只是协议 fixture 证据，UI/诊断不得据此宣称
 * “真实端点已联通”——没有 key 的端点仍按未测处理（设计 §3.2 / 实施规划 W3-11）。
 */
interface ProbeTransport : HttpExecutor {
    suspend fun stream(request: KtorTransport.Request, onFrame: suspend (SseFrameParser.Frame) -> Unit): Int
}

private class KtorProbeTransport(private val transport: KtorTransport) : ProbeTransport {
    override suspend fun execute(request: KtorTransport.Request): KtorTransport.Response =
        transport.execute(request)

    override suspend fun stream(
        request: KtorTransport.Request,
        onFrame: suspend (SseFrameParser.Frame) -> Unit,
    ): Int = transport.stream(request, onFrame)
}

/** 失败分类：直接对应可执行的修复动作，不用一句“测试失败”糊住（设计 §3.2/§15/§17）。 */
enum class ProbeFailure(val label: String) {
    NONE(""),
    AUTH("鉴权失败：检查 key 与端点权限"),
    RATE_LIMIT("限流：按 Retry-After 稍后重试"),
    INVALID_REQUEST("请求被拒绝：检查 model 与参数"),
    SERVER("服务端错误：稍后重试"),
    NETWORK("网络错误：检查网络与端点地址"),
    TIMEOUT("超时：服务未在时限内响应"),
    RESPONSE_SHAPE("响应结构非法：端点映射可能不匹配"),
}

/** 探测阶段：按钮显示“目的与实际阶段”，不是一个笼统的转圈。 */
enum class ProbeStage(val label: String) {
    GENERATION("鉴权与生成"),
    STREAMING("流式首文本"),
    TOOL_CALLING("工具闭环"),
    STRUCTURED_OUTPUT("结构化输出"),
    EMBEDDING("嵌入向量"),
    RERANK("重排"),
    SEARCH("搜索"),
}

/** 单项探测结果：状态 + 失败分类 + 可读细节（细节已脱敏：不含 key、不含带 key 的 URL）。 */
data class ProbeStep(
    val stage: ProbeStage,
    val status: CapabilityStatus,
    val failure: ProbeFailure = ProbeFailure.NONE,
    val detail: String? = null,
    val embeddingDim: Int? = null,
    val firstTokenMs: Long? = null,
)

/** 一次草稿探测的完整产出。 */
data class ProbeOutcome(
    val capabilities: CapabilityProfile,
    val notes: List<String> = emptyList(),
    val steps: List<ProbeStep> = emptyList(),
    val firstTokenMs: Long? = null,
    val totalMs: Long? = null,
)

/** 模型列表结果：成功与否同样要能分类，不能“列表 200”就当成整体连通。 */
data class ModelCatalog(
    val models: List<String> = emptyList(),
    val failure: ProbeFailure = ProbeFailure.NONE,
    val detail: String? = null,
    val unsupported: Boolean = false,
)

/**
 * 能力探测：用实际业务请求验证，不用名称猜。
 *
 * 每项独立记录 PASS/FAIL/UNSUPPORTED/UNTESTED：
 *  - 端点可达、鉴权、指定模型生成、流式首文本、工具闭环、schema 输出、embedding
 *  - 仅拉到列表/HTTP200/空流/注释心跳不算通过
 *  - probe 只影响草稿的 probe 状态，不改已保存 profile
 *  - CHAT 不暗测 embedding（Claude 对话端点不暗转 OpenAI embedding，§17）
 *  - RERANK/SEARCH 没有真实探测实现时如实保持 UNTESTED，不伪造 PASS
 *  - 取消（协程取消）直接向上传播，不被 runCatching 吞成 FAIL
 */
class CapabilityProbe(
    private val transport: ProbeTransport,
) {
    /** 生产接线：AppContainer 只传真实 [KtorTransport]。 */
    constructor(transport: KtorTransport) : this(KtorProbeTransport(transport))

    private fun adapterFor(protocol: WireProtocol, model: String): ChatAdapter = when (protocol) {
        WireProtocol.OPENAI_CHAT -> OpenAiChatAdapter(model)
        WireProtocol.OPENAI_RESPONSES -> OpenAiResponsesAdapter(model)
        WireProtocol.ANTHROPIC_MESSAGES -> AnthropicAdapter(model)
        WireProtocol.GEMINI_NATIVE -> GeminiAdapter(model)
    }

    // ---- 草稿编排：按 purpose 只测该用途真实存在的能力 ----

    /**
     * 按 purpose 探测当前**草稿快照**（调用方每次只传一次快照，探测期间不会读到已保存 profile）。
     *
     * CHAT：生成 → 流式 → 工具闭环 → 结构化输出；生成未通过时其余阶段如实 UNTESTED，不重试式刷请求。
     * EMBEDDING：只测 embedding（数量/index/维度/有限值）。
     * RERANK/SEARCH：没有真实探测实现 → UNTESTED，并说明“不代表不支持”。
     */
    suspend fun probeDraft(
        purpose: ProfilePurpose,
        protocol: WireProtocol,
        endpoint: String,
        model: String,
        apiKey: String?,
        timeoutMs: Long = 30_000,
        onStage: (ProbeStage) -> Unit = {},
        paramsJson: String = "{}",
    ): ProbeOutcome {
        val startedAt = System.nanoTime()
        val steps = mutableListOf<ProbeStep>()
        var firstTokenMs: Long? = null

        when (purpose) {
            ProfilePurpose.CHAT -> {
                onStage(ProbeStage.GENERATION)
                val generation = probeGeneration(protocol, endpoint, model, apiKey, timeoutMs, paramsJson)
                steps += generation
                if (generation.status == CapabilityStatus.PASS) {
                    onStage(ProbeStage.STREAMING)
                    val streaming = probeStreaming(protocol, endpoint, model, apiKey, timeoutMs, paramsJson)
                    steps += streaming
                    firstTokenMs = streaming.firstTokenMs

                    onStage(ProbeStage.TOOL_CALLING)
                    steps += probeToolCalling(protocol, endpoint, model, apiKey, timeoutMs, paramsJson)

                    onStage(ProbeStage.STRUCTURED_OUTPUT)
                    steps += probeStructuredOutput(protocol, endpoint, model, apiKey, timeoutMs, paramsJson)
                } else {
                    // 鉴权/限流/参数错误下继续打三个请求只会刷失败：保持未测，并说明原因。
                    val reason = generation.detail?.takeIf { it.isNotBlank() }
                        ?: generation.failure.label.ifBlank { generation.status.name }
                    val skipped = "生成未通过（$reason），未继续"
                    for ((stage, operation) in listOf(ProbeStage.STREAMING to EndpointOperation.CHAT_STREAM,
                        ProbeStage.TOOL_CALLING to EndpointOperation.CHAT_SINGLE,
                        ProbeStage.STRUCTURED_OUTPUT to EndpointOperation.CHAT_SINGLE)) {
                        val resolved = EndpointResolver.resolve(protocol, purpose, endpoint, paramsJson, operation, model,
                            stream = operation == EndpointOperation.CHAT_STREAM)
                        steps += if (resolved is EndpointResolver.Resolution.Unsupported)
                            ProbeStep(stage, CapabilityStatus.UNSUPPORTED, detail = "该请求操作没有可用地址")
                        else ProbeStep(stage, CapabilityStatus.UNTESTED, detail = skipped)
                    }
                }
            }

            ProfilePurpose.EMBEDDING -> {
                onStage(ProbeStage.EMBEDDING)
                steps += probeEmbedding(protocol, endpoint, model, apiKey, timeoutMs, paramsJson)
            }

            ProfilePurpose.RERANK, ProfilePurpose.SEARCH -> {
                // 没有真实探测实现：不得伪造 PASS，也不武断标记“不支持”。
                val stage = if (purpose == ProfilePurpose.RERANK) ProbeStage.RERANK else ProbeStage.SEARCH
                onStage(stage)
                steps += ProbeStep(stage, CapabilityStatus.UNTESTED, detail = "尚无真实探测实现：保持未测（不代表不支持）")
            }
        }

        val notes = steps.mapNotNull(::noteFor)
        return ProbeOutcome(
            capabilities = capabilitiesOf(purpose, steps, notes),
            notes = notes,
            steps = steps,
            firstTokenMs = firstTokenMs,
            totalMs = elapsedMs(startedAt),
        )
    }

    private fun capabilitiesOf(
        purpose: ProfilePurpose,
        steps: List<ProbeStep>,
        notes: List<String>,
    ): CapabilityProfile {
        fun statusOf(stage: ProbeStage): CapabilityStatus =
            steps.firstOrNull { it.stage == stage }?.status ?: CapabilityStatus.UNTESTED

        return when (purpose) {
            ProfilePurpose.CHAT -> CapabilityProfile(
                generation = statusOf(ProbeStage.GENERATION),
                streaming = statusOf(ProbeStage.STREAMING),
                toolCalling = statusOf(ProbeStage.TOOL_CALLING),
                structuredOutput = statusOf(ProbeStage.STRUCTURED_OUTPUT),
                // CHAT 草稿不暗测 embedding：能力画像里保持未测，等独立的 embedding profile 去测。
                embedding = CapabilityStatus.UNTESTED,
                notes = notes,
            )

            ProfilePurpose.EMBEDDING -> CapabilityProfile(
                embedding = statusOf(ProbeStage.EMBEDDING),
                embeddingDim = steps.firstOrNull { it.stage == ProbeStage.EMBEDDING }?.embeddingDim,
                notes = notes,
            )

            ProfilePurpose.RERANK -> CapabilityProfile(
                rerank = statusOf(ProbeStage.RERANK),
                notes = notes,
            )

            ProfilePurpose.SEARCH -> CapabilityProfile(
                search = statusOf(ProbeStage.SEARCH),
                notes = notes,
            )
        }
    }

    /** 未通过项的可读 note：脱敏，只含阶段名、状态与分类，不回显请求 URL/key。 */
    private fun noteFor(step: ProbeStep): String? {
        if (step.status == CapabilityStatus.PASS) return null
        val head = when (step.status) {
            CapabilityStatus.UNSUPPORTED -> "「${step.stage.label}」不支持"
            CapabilityStatus.UNTESTED -> "「${step.stage.label}」未测"
            else -> "「${step.stage.label}」失败"
        }
        val reasons = listOfNotNull(
            step.detail?.takeIf { it.isNotBlank() },
            step.failure.takeIf { it != ProbeFailure.NONE }?.label?.takeIf { it.isNotBlank() },
        ).distinct()
        return if (reasons.isEmpty()) head else "$head：${reasons.joinToString("；")}"
    }

    // ---- 单项探测 ----

    /** 生成测试：最小回复，区分鉴权/限流/模型/参数错误。 */
    suspend fun probeGeneration(
        protocol: WireProtocol,
        endpoint: String,
        model: String,
        apiKey: String?,
        timeoutMs: Long = 30_000,
        paramsJson: String = "{}",
    ): ProbeStep {
        val stage = ProbeStage.GENERATION
        val resolved = EndpointResolver.resolve(protocol, ProfilePurpose.CHAT, endpoint, paramsJson,
            EndpointOperation.CHAT_SINGLE, model)
        endpointProblem(stage, resolved)?.let { return it }
        val finalEndpoint = (resolved as EndpointResolver.Resolution.Ok).endpoint
        val adapter = adapterFor(protocol, model)
        val request = ChatRequest(
            runId = "probe-gen",
            profileId = "probe",
            configRevision = 0,
            messages = listOf(ChatMessage.user("hi")),
            toolPolicy = ToolPolicy.NONE,
            stream = false,
            budget = ChatBudget(inputLimit = 2048, outputReserve = 16, deadlineMs = timeoutMs),
        )
        val attempt = executeAttempt(adapter.buildChatRequest(request, apiKey, finalEndpoint), timeoutMs)
        rejectionStep(stage, attempt, timeoutMs)?.let { return it }
        val response = (attempt as HttpAttempt.Response).response
        val events = adapter.decodeNonStream(response.body)
        events.filterIsInstance<ChatEvent.ProviderError>().firstOrNull()?.let { error ->
            return ProbeStep(stage, CapabilityStatus.FAIL, ProbeFailure.INVALID_REQUEST, "服务返回错误：${error.code}")
        }
        val hasText = events.any { it is ChatEvent.TextDelta && it.text.isNotBlank() }
        return if (hasText) {
            ProbeStep(stage, CapabilityStatus.PASS)
        } else {
            ProbeStep(stage, CapabilityStatus.FAIL, ProbeFailure.RESPONSE_SHAPE, "HTTP 2xx 但没有文本内容")
        }
    }

    /** 流式测试：实际首文本 + 终态；心跳/空内容不算通过。 */
    suspend fun probeStreaming(
        protocol: WireProtocol,
        endpoint: String,
        model: String,
        apiKey: String?,
        timeoutMs: Long = 30_000,
        paramsJson: String = "{}",
    ): ProbeStep {
        val stage = ProbeStage.STREAMING
        val resolved = EndpointResolver.resolve(protocol, ProfilePurpose.CHAT, endpoint, paramsJson,
            EndpointOperation.CHAT_STREAM, model, stream = true)
        endpointProblem(stage, resolved)?.let { return it }
        val finalEndpoint = (resolved as EndpointResolver.Resolution.Ok).endpoint
        val adapter = adapterFor(protocol, model)
        val request = ChatRequest(
            runId = "probe-stream",
            profileId = "probe",
            configRevision = 0,
            messages = listOf(ChatMessage.user("hi")),
            toolPolicy = ToolPolicy.NONE,
            stream = true,
            budget = ChatBudget(inputLimit = 2048, outputReserve = 16, deadlineMs = timeoutMs),
        )
        val accumulator = ToolCallAccumulator()
        val startedAt = System.nanoTime()
        var firstTextMs: Long? = null
        var sawFinish = false
        var providerErrorCode: String? = null

        val status: Int? = try {
            withTimeoutOrNull(timeoutMs) {
                transport.stream(adapter.buildChatRequest(request, apiKey, finalEndpoint)) { frame ->
                    adapter.decodeStreamFrame(frame, accumulator).forEach { event ->
                        when (event) {
                            is ChatEvent.TextDelta -> if (event.text.isNotBlank() && firstTextMs == null) {
                                firstTextMs = elapsedMs(startedAt)
                            }
                            is ChatEvent.Finish -> sawFinish = true
                            is ChatEvent.ProviderError -> providerErrorCode = event.code
                            else -> Unit
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return ProbeStep(
                stage = stage,
                status = CapabilityStatus.FAIL,
                failure = ProbeFailure.NETWORK,
                detail = "连接中断（${error.javaClass.simpleName}）",
            )
        }

        if (status == null) {
            return ProbeStep(stage, CapabilityStatus.FAIL, ProbeFailure.TIMEOUT, "未在 ${timeoutMs} ms 内完成")
        }
        if (status !in 200..299) {
            val synthetic = KtorTransport.Response(status, "", emptyMap())
            return ProbeStep(stage, CapabilityStatus.FAIL, failureOf(synthetic), httpDetail(synthetic))
        }
        providerErrorCode?.let { code ->
            return ProbeStep(stage, CapabilityStatus.FAIL, ProbeFailure.INVALID_REQUEST, "服务返回错误：$code")
        }
        if (firstTextMs == null) {
            return ProbeStep(
                stage = stage,
                status = CapabilityStatus.FAIL,
                failure = ProbeFailure.RESPONSE_SHAPE,
                detail = "未收到文本（心跳与空内容不算通过）",
            )
        }
        if (!sawFinish) {
            return ProbeStep(stage, CapabilityStatus.FAIL, ProbeFailure.RESPONSE_SHAPE, "缺少终态（流被中断）")
        }
        return ProbeStep(stage, CapabilityStatus.PASS, firstTokenMs = firstTextMs)
    }

    /** 工具闭环：发送无副作用 echo 工具，接 call，回 tool 结果再生成。 */
    suspend fun probeToolCalling(
        protocol: WireProtocol,
        endpoint: String,
        model: String,
        apiKey: String?,
        timeoutMs: Long = 60_000,
        paramsJson: String = "{}",
    ): ProbeStep {
        val stage = ProbeStage.TOOL_CALLING
        val resolved = EndpointResolver.resolve(protocol, ProfilePurpose.CHAT, endpoint, paramsJson,
            EndpointOperation.CHAT_SINGLE, model)
        endpointProblem(stage, resolved)?.let { return it }
        val finalEndpoint = (resolved as EndpointResolver.Resolution.Ok).endpoint
        val adapter = adapterFor(protocol, model)
        val tool = ToolSpec(
            name = "echo",
            description = "Echo back the provided text. No side effects.",
            parametersJson = """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""",
        )
        val prompt = "Call the echo tool with text=ping, then tell me the result."
        val firstRequest = ChatRequest(
            runId = "probe-tool-1",
            profileId = "probe",
            configRevision = 0,
            messages = listOf(ChatMessage.user(prompt)),
            tools = listOf(tool),
            toolPolicy = ToolPolicy.AUTO,
            stream = false,
            budget = ChatBudget(inputLimit = 4096, outputReserve = 128, deadlineMs = timeoutMs),
        )
        val firstAttempt = executeAttempt(adapter.buildChatRequest(firstRequest, apiKey, finalEndpoint), timeoutMs)
        rejectionStep(stage, firstAttempt, timeoutMs)?.let { return it }
        val firstEvents = adapter.decodeNonStream((firstAttempt as HttpAttempt.Response).response.body)
        firstEvents.filterIsInstance<ChatEvent.ProviderError>().firstOrNull()?.let { error ->
            return ProbeStep(stage, CapabilityStatus.FAIL, ProbeFailure.INVALID_REQUEST, "服务返回错误：${error.code}")
        }
        val call = firstEvents.filterIsInstance<ChatEvent.ToolComplete>().firstOrNull()
            ?: return ProbeStep(
                stage = stage,
                status = CapabilityStatus.UNSUPPORTED,
                detail = "未返回工具调用（端点可能不支持 tools）",
            )
        if (call.name != "echo") {
            return ProbeStep(stage, CapabilityStatus.FAIL, ProbeFailure.RESPONSE_SHAPE, "工具名不匹配：${call.name}")
        }
        // 残缺/半截的工具参数不执行、也不能当成通过（设计 §15）。
        if (!isCompleteJsonObject(call.argumentsJson)) {
            return ProbeStep(
                stage = stage,
                status = CapabilityStatus.FAIL,
                failure = ProbeFailure.RESPONSE_SHAPE,
                detail = "工具参数不是完整 JSON（残缺参数不执行）",
            )
        }

        // 回 tool 结果再生成：证明 tool role/callId 往返可用。
        val secondRequest = ChatRequest(
            runId = "probe-tool-2",
            profileId = "probe",
            configRevision = 0,
            messages = listOf(
                ChatMessage.user(prompt),
                ChatMessage(role = "assistant", toolCalls = listOf(ToolCall(call.callId, call.name, call.argumentsJson))),
                ChatMessage.toolResult(call.callId, "pong"),
            ),
            tools = listOf(tool),
            toolPolicy = ToolPolicy.AUTO,
            stream = false,
            budget = ChatBudget(inputLimit = 4096, outputReserve = 128, deadlineMs = timeoutMs),
        )
        val secondAttempt = executeAttempt(adapter.buildChatRequest(secondRequest, apiKey, finalEndpoint), timeoutMs)
        rejectionStep(stage, secondAttempt, timeoutMs)?.let { return it }
        val secondEvents = adapter.decodeNonStream((secondAttempt as HttpAttempt.Response).response.body)
        secondEvents.filterIsInstance<ChatEvent.ProviderError>().firstOrNull()?.let { error ->
            return ProbeStep(stage, CapabilityStatus.FAIL, ProbeFailure.INVALID_REQUEST, "服务返回错误：${error.code}")
        }
        val continued = secondEvents.any { it is ChatEvent.TextDelta && it.text.isNotBlank() } ||
            secondEvents.any { it is ChatEvent.Finish }
        return if (continued) {
            ProbeStep(stage, CapabilityStatus.PASS)
        } else {
            ProbeStep(stage, CapabilityStatus.FAIL, ProbeFailure.RESPONSE_SHAPE, "回填 tool 结果后未再生成")
        }
    }

    /** 结构化输出：简短 JSON schema 任务，解析并校验；不支持时如实 UNSUPPORTED。 */
    suspend fun probeStructuredOutput(
        protocol: WireProtocol,
        endpoint: String,
        model: String,
        apiKey: String?,
        timeoutMs: Long = 30_000,
        paramsJson: String = "{}",
    ): ProbeStep {
        val stage = ProbeStage.STRUCTURED_OUTPUT
        val resolved = EndpointResolver.resolve(protocol, ProfilePurpose.CHAT, endpoint, paramsJson,
            EndpointOperation.CHAT_SINGLE, model)
        endpointProblem(stage, resolved)?.let { return it }
        val finalEndpoint = (resolved as EndpointResolver.Resolution.Ok).endpoint
        val adapter = adapterFor(protocol, model)
        val request = ChatRequest(
            runId = "probe-schema",
            profileId = "probe",
            configRevision = 0,
            messages = listOf(ChatMessage.user("Return a JSON object with key ok set to true.")),
            toolPolicy = ToolPolicy.NONE,
            responseSchema = """{"type":"object","properties":{"ok":{"type":"boolean"}},"required":["ok"]}""",
            stream = false,
            budget = ChatBudget(inputLimit = 2048, outputReserve = 64, deadlineMs = timeoutMs),
        )
        val attempt = executeAttempt(adapter.buildChatRequest(request, apiKey, finalEndpoint), timeoutMs)
        val rejection = rejectionStep(stage, attempt, timeoutMs)
        if (rejection != null) {
            // 400/422 常见于端点不支持 response_format：记为“不支持”，不是“失败”，更不是通过。
            return if (rejection.failure == ProbeFailure.INVALID_REQUEST) {
                ProbeStep(
                    stage = stage,
                    status = CapabilityStatus.UNSUPPORTED,
                    detail = "端点拒绝 schema 参数（${rejection.detail}），可能不支持结构化输出",
                )
            } else {
                rejection
            }
        }
        val response = (attempt as HttpAttempt.Response).response
        val text = adapter.decodeNonStream(response.body)
            .filterIsInstance<ChatEvent.TextDelta>()
            .joinToString("") { it.text }
        val parsed = runCatching { probeJson.parseToJsonElement(stripJsonFence(text)).jsonObject }.getOrNull()
            ?: return ProbeStep(
                stage = stage,
                status = CapabilityStatus.FAIL,
                failure = ProbeFailure.RESPONSE_SHAPE,
                detail = "返回不是合法 JSON",
            )
        val ok = (parsed["ok"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
        return if (ok == true) {
            ProbeStep(stage, CapabilityStatus.PASS)
        } else {
            ProbeStep(stage, CapabilityStatus.FAIL, ProbeFailure.RESPONSE_SHAPE, "JSON 缺少 ok=true")
        }
    }

    /** embedding 实际向量测试：两段文本，检查数量、index、维度、有限值。 */
    suspend fun probeEmbedding(
        protocol: WireProtocol,
        endpoint: String,
        model: String,
        apiKey: String?,
        timeoutMs: Long = 30_000,
        paramsJson: String = "{}",
    ): ProbeStep {
        val stage = ProbeStage.EMBEDDING
        val resolved = EndpointResolver.resolve(protocol, ProfilePurpose.EMBEDDING, endpoint, paramsJson,
            EndpointOperation.EMBEDDING, model)
        endpointProblem(stage, resolved)?.let { return it }
        val finalEndpoint = (resolved as EndpointResolver.Resolution.Ok).endpoint
        val adapter = EmbeddingAdapter(protocol, model)
        val attempt = executeAttempt(adapter.buildRequest(listOf("甲", "乙"), apiKey, finalEndpoint), timeoutMs)
        rejectionStep(stage, attempt, timeoutMs)?.let { return it }
        val response = (attempt as HttpAttempt.Response).response
        return when (val outcome = adapter.decode(response.body)) {
            is EmbeddingAdapter.Outcome.Invalid ->
                ProbeStep(stage, CapabilityStatus.FAIL, ProbeFailure.RESPONSE_SHAPE, outcome.reason)

            is EmbeddingAdapter.Outcome.Ok -> when {
                outcome.vectors.size != 2 ->
                    ProbeStep(
                        stage = stage,
                        status = CapabilityStatus.FAIL,
                        failure = ProbeFailure.RESPONSE_SHAPE,
                        detail = "向量数量不符：期望 2，实际 ${outcome.vectors.size}",
                    )

                outcome.dim <= 0 ->
                    ProbeStep(stage, CapabilityStatus.FAIL, ProbeFailure.RESPONSE_SHAPE, "维度非法：${outcome.dim}")

                else -> ProbeStep(stage, CapabilityStatus.PASS, embeddingDim = outcome.dim)
            }
        }
    }

    /** 模型列表：分页、去重、上限 5000。候选不标为已验证。 */
    suspend fun fetchModelCatalog(
        protocol: WireProtocol,
        endpoint: String,
        model: String,
        apiKey: String?,
        timeoutMs: Long = 20_000,
        paramsJson: String = "{}",
        purpose: ProfilePurpose = ProfilePurpose.CHAT,
    ): ModelCatalog {
        val finalEndpoint = when (val resolved = EndpointResolver.resolve(protocol, purpose, endpoint, paramsJson,
            EndpointOperation.MODELS, model)) {
            is EndpointResolver.Resolution.Ok -> resolved.endpoint
            is EndpointResolver.Resolution.Invalid ->
                return ModelCatalog(failure = ProbeFailure.INVALID_REQUEST, detail = resolved.reason)
            is EndpointResolver.Resolution.Unsupported ->
                return ModelCatalog(unsupported = true, detail = "未配置模型列表地址，可手动输入模型名称")
        }
        val adapter = adapterFor(protocol, model)
        return when (val attempt = executeAttempt(adapter.buildModelsRequest(finalEndpoint, apiKey), timeoutMs)) {
            is HttpAttempt.Timeout ->
                ModelCatalog(failure = ProbeFailure.TIMEOUT, detail = "未在 $timeoutMs ms 内响应")

            is HttpAttempt.Network ->
                ModelCatalog(failure = ProbeFailure.NETWORK, detail = "连接失败（${attempt.error.javaClass.simpleName}）")

            is HttpAttempt.Response -> {
                val response = attempt.response
                if (response.status !in 200..299) {
                    ModelCatalog(failure = failureOf(response), detail = httpDetail(response))
                } else {
                    ModelCatalog(models = adapter.decodeModels(response.body).distinct().take(MAX_MODELS))
                }
            }
        }
    }

    /** 便捷入口：只要模型 id（失败返回空列表，分类信息用 [fetchModelCatalog]）。 */
    suspend fun fetchModels(
        protocol: WireProtocol,
        endpoint: String,
        model: String,
        apiKey: String?,
        timeoutMs: Long = 20_000,
        paramsJson: String = "{}",
        purpose: ProfilePurpose = ProfilePurpose.CHAT,
    ): List<String> = fetchModelCatalog(protocol, endpoint, model, apiKey, timeoutMs, paramsJson, purpose).models

    // ---- 传输与分类 ----

    private fun endpointProblem(stage: ProbeStage, resolved: EndpointResolver.Resolution): ProbeStep? = when (resolved) {
        is EndpointResolver.Resolution.Ok -> null
        is EndpointResolver.Resolution.Invalid ->
            ProbeStep(stage, CapabilityStatus.FAIL, ProbeFailure.INVALID_REQUEST, resolved.reason)
        is EndpointResolver.Resolution.Unsupported ->
            ProbeStep(stage, CapabilityStatus.UNSUPPORTED, detail = "该请求操作没有可用地址")
    }

    private sealed interface HttpAttempt {
        data class Response(val response: KtorTransport.Response) : HttpAttempt
        data object Timeout : HttpAttempt
        data class Network(val error: Throwable) : HttpAttempt
    }

    private suspend fun executeAttempt(request: KtorTransport.Request, timeoutMs: Long): HttpAttempt = try {
        val response = withTimeoutOrNull(timeoutMs) { transport.execute(request) }
        if (response == null) HttpAttempt.Timeout else HttpAttempt.Response(response)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        HttpAttempt.Network(error)
    }

    /** 非 2xx/超时/网络错误的统一 step；返回 null 表示 2xx 可继续解码。 */
    private fun rejectionStep(stage: ProbeStage, attempt: HttpAttempt, timeoutMs: Long): ProbeStep? = when (attempt) {
        is HttpAttempt.Timeout -> ProbeStep(stage, CapabilityStatus.FAIL, ProbeFailure.TIMEOUT, "未在 $timeoutMs ms 内响应")
        is HttpAttempt.Network -> ProbeStep(
            stage = stage,
            status = CapabilityStatus.FAIL,
            failure = ProbeFailure.NETWORK,
            detail = "连接失败（${attempt.error.javaClass.simpleName}）",
        )

        is HttpAttempt.Response -> {
            val response = attempt.response
            if (response.status in 200..299) {
                null
            } else {
                ProbeStep(stage, CapabilityStatus.FAIL, failureOf(response), httpDetail(response))
            }
        }
    }

    private fun failureOf(response: KtorTransport.Response): ProbeFailure = when {
        response.status == 401 || response.status == 403 -> ProbeFailure.AUTH
        response.status == 429 -> ProbeFailure.RATE_LIMIT
        response.status in 400..499 -> ProbeFailure.INVALID_REQUEST
        response.status >= 500 -> ProbeFailure.SERVER
        else -> ProbeFailure.RESPONSE_SHAPE
    }

    private fun httpDetail(response: KtorTransport.Response): String {
        val retry = if (response.status == 429) retryAfterText(response) else null
        return if (retry != null) "HTTP ${response.status}，$retry" else "HTTP ${response.status}"
    }

    /** 429 尊重 Retry-After：把“什么时候能重试”变成可执行信息（设计 §15）。 */
    private fun retryAfterText(response: KtorTransport.Response): String? {
        val raw = response.headers.entries
            .firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value
            ?: return null
        val ms = AiErrorClassifier.parseRetryAfter(raw, System.currentTimeMillis()) ?: return null
        val seconds = ((ms + 999) / 1000).coerceAtLeast(1)
        return "约 $seconds 秒后可重试"
    }

    private fun isCompleteJsonObject(raw: String): Boolean =
        runCatching { probeJson.parseToJsonElement(raw).jsonObject }.isSuccess

    /** 容忍 ```json 围栏与前后说明文字，只取首个 { 到末个 }。 */
    private fun stripJsonFence(raw: String): String {
        val trimmed = raw.trim()
        val unfenced = if (trimmed.startsWith("```")) {
            trimmed.removePrefix("```").removePrefix("json").removePrefix("JSON").trimEnd('`').trim()
        } else {
            trimmed
        }
        val start = unfenced.indexOf('{')
        val end = unfenced.lastIndexOf('}')
        return if (start >= 0 && end > start) unfenced.substring(start, end + 1) else unfenced
    }

    private fun elapsedMs(startedAtNanos: Long): Long = (System.nanoTime() - startedAtNanos) / 1_000_000

    private companion object {
        /** fetchModels 去重后最多保留 5000 项（设计 §3.2）。 */
        const val MAX_MODELS = 5000

        val probeJson = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
