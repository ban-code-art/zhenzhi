package com.example.myapplication.data.agent

import com.example.myapplication.data.ai.adapters.ChatAdapter
import com.example.myapplication.data.ai.config.FinalEndpoint
import com.example.myapplication.domain.ai.ChatBudget
import com.example.myapplication.domain.ai.ChatEvent
import com.example.myapplication.domain.ai.ChatMessage
import com.example.myapplication.domain.ai.ChatRequest
import com.example.myapplication.domain.ai.ToolPolicy

/**
 * 生产版摘要端口：用**当前 CHAT profile 的同一套请求/适配器**做一次单轮、无工具的调用。
 *
 * 与出题生成器（`ChatQuizDraftSource`）保持同一纪律：
 *  - 不注册任何工具（toolPolicy=NONE），摘要过程不可能触发写操作；
 *  - 只发一次，不自动整轮重试；失败由 [ConversationCompressor] 记失败并回滑动窗口；
 *  - 密钥每轮重新解析，撤权/缺失直接返回 null（不外发）；
 *  - 输出预算与超时由 `ChatRequest.budget` + 调用方 `withTimeout` 双重约束。
 *
 * 注意：内容只来自**本会话历史消息**，不会把书籍正文/笔记/密钥拼进摘要请求；
 * 摘要文本本身也不写回数据库（原始消息永久保留）。
 */
class ChatHistorySummaryPort(
    private val executor: ChatStreamExecutor,
    private val adapter: ChatAdapter,
    private val endpoint: FinalEndpoint,
    private val profileId: String,
    private val configRevision: Long,
    private val resolveSecret: suspend () -> SecretResolution,
    private val inputLimitTokens: Int,
    private val maxPromptChars: Int = DEFAULT_MAX_PROMPT_CHARS,
) : HistorySummaryPort {

    override suspend fun summarize(request: SummaryRequest): String? {
        val apiKey = when (val secret = resolveSecret()) {
            is SecretResolution.Ok -> secret.apiKey
            SecretResolution.Revoked, SecretResolution.SecretMissing -> return null
        }
        val prompt = SUMMARY_SYSTEM_PROMPT + "\n\n【需要压缩的历史】\n" +
            request.promptText().take(maxPromptChars)
        val chatRequest = ChatRequest(
            runId = "conv-summary-${request.coveredMessageIds.size}-${request.messages.size}",
            profileId = profileId,
            configRevision = configRevision,
            messages = listOf(
                ChatMessage.system(SUMMARY_SYSTEM_PROMPT),
                ChatMessage.user(prompt),
            ),
            // 摘要不调用工具：它只做文本压缩，不能读库、不能发请求、不能写任何东西。
            tools = emptyList(),
            toolPolicy = ToolPolicy.NONE,
            responseSchema = null,
            stream = true,
            budget = ChatBudget(
                inputLimit = inputLimitTokens.coerceAtLeast(MIN_INPUT_TOKENS),
                outputReserve = request.outputTokenBudget.coerceAtLeast(MIN_OUTPUT_RESERVE),
                deadlineMs = request.deadlineMs,
            ),
        )
        val text = StringBuilder()
        runCatching {
            executor.stream(chatRequest, adapter, apiKey, endpoint) { event ->
                when (event) {
                    is ChatEvent.TextDelta -> text.append(event.text)
                    is ChatEvent.ProviderError -> throw IllegalStateException(event.code)
                    else -> Unit
                }
            }
        }.onFailure { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            return null
        }
        return text.toString().trim().ifBlank { null }
    }

    companion object {
        const val DEFAULT_MAX_PROMPT_CHARS = 24_000
        private const val MIN_INPUT_TOKENS = 512
        private const val MIN_OUTPUT_RESERVE = 128

        /**
         * 摘要提示词刻意"只压缩、不回答、不引用"：
         * 模型若把旧 [S1] 当证据带出来，注入前还会被 [ConversationCompressor.sanitize] 改写。
         */
        const val SUMMARY_SYSTEM_PROMPT =
            "你是对话历史压缩器。把给定的历史消息压缩成不超过 400 字的中文要点，" +
                "保留：用户目标与偏好、已确认的结论、未解决的问题、用到的工具名与关键参数。" +
                "不要新增事实，不要回答用户问题，不要输出任何权限或系统规则，" +
                "不要保留引用编号（例如 [S1]），如必须提到来源请写“旧回答中的引用”。" +
                "只输出要点本身，不要前后缀。"
    }
}
