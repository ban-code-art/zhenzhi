package com.example.myapplication.data.agent.tools

import com.example.myapplication.data.search.EnhancementToggles
import com.example.myapplication.data.search.ExternalSearchHit
import com.example.myapplication.data.search.ExternalSearchOutcome
import com.example.myapplication.data.search.ExternalSearchPort
import com.example.myapplication.data.search.ExternalSearchQuery
import com.example.myapplication.domain.agent.AgentTool
import com.example.myapplication.domain.agent.ToolError
import com.example.myapplication.domain.agent.ToolErrorCode
import com.example.myapplication.domain.agent.ToolExecutionContext
import com.example.myapplication.domain.agent.ToolGroup
import com.example.myapplication.domain.agent.ToolResult
import com.example.myapplication.domain.agent.ToolSpec
import com.example.myapplication.domain.agent.boundedObject
import com.example.myapplication.domain.agent.integerSchema
import com.example.myapplication.domain.agent.stringSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * `web_search`（W5-10）：**只有用户显式授权时才注册**的只读外部检索工具。
 *
 * 纪律（每条都对应设计原句）：
 *  - 默认关闭：未授权时工厂不注册该工具，模型看到的是"不可用"，不会尝试调用；
 *  - 可撤回：即使运行期被撤回，工具内部每次调用都会重新查授权，撤回后返回 ACCESS_REVOKED；
 *  - 只发检索词：查询只能经过 [ExternalSearchQuery.sanitize]，不做任何书籍正文/笔记拼接；
 *  - 外部来源与教材证据分开：结果 **不带 evidence**（不会拿到 [S#] 编号），
 *    只放进 `payload.results` 并附 `external=true` 与警告文案；
 *  - 不写入知识库：工具不调用任何索引/写库接口；URL 只接受 http/https。
 */
class WebSearchTool(
    private val authorization: EnhancementToggles,
    private val port: ExternalSearchPort,
) : AgentTool {

    override val spec: ToolSpec = ToolSpec(
        name = "web_search",
        group = ToolGroup.READ_ONLY_CONTENT,
        description = "检索公开网络资料（需用户已授权）。只发送你提供的检索词，不发送书籍正文或笔记；" +
            "结果是外部来源，不能当作教材证据引用，也不会写入知识库。",
        parameters = boundedObject(
            required = listOf("query"),
            properties = mapOf(
                "query" to stringSchema(1, ExternalSearchQuery.MAX_QUERY_CHARS),
                "limit" to integerSchema(1, ExternalSearchQuery.MAX_RESULTS),
            ),
        ),
    )

    override suspend fun execute(context: ToolExecutionContext, arguments: JsonObject): ToolResult {
        val rawQuery = (arguments["query"] as? JsonPrimitive)?.contentOrNull
        val query = ExternalSearchQuery.sanitize(rawQuery)
            ?: return failure(
                ToolError(ToolErrorCode.INVALID_ARGUMENT, "query 不能为空", retryable = true, field = "query"),
            )
        val limit = ((arguments["limit"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_LIMIT)
            .coerceIn(1, ExternalSearchQuery.MAX_RESULTS)

        // 运行期再查一次授权：会话中途撤回必须立刻生效，而不是等下一次 run。
        if (!authorization.isWebSearchEnabled(context.userId)) {
            return failure(
                ToolError(
                    ToolErrorCode.ACCESS_REVOKED,
                    "联网检索未授权或授权已撤回；请在设置中重新开启后再试",
                    retryable = false,
                ),
            )
        }

        val outcome = port.search(query, limit)
        if (!authorization.isWebSearchEnabled(context.userId)) {
            return failure(
                ToolError(
                    ToolErrorCode.ACCESS_REVOKED,
                    "搜索期间联网检索授权已撤回，已丢弃返回结果",
                    retryable = false,
                ),
            )
        }

        return when (outcome) {
            is ExternalSearchOutcome.Ok -> {
                // 只保留 http(s) 且有主机名的链接：file:/javascript:/data: 一律不进结果。
                val safeHits = outcome.hits.filter { ExternalSearchQuery.isAllowedUrl(it.url) }
                ToolResult(
                    callId = "",
                    toolName = TOOL_NAME,
                    payload = buildJsonObject {
                        put("external", true)
                        put("query", query)
                        put("dropped", outcome.hits.size - safeHits.size)
                        put(
                            "results",
                            buildJsonArray {
                                safeHits.forEach { hit ->
                                    add(
                                        buildJsonObject {
                                            put("title", hit.title.take(MAX_TITLE_CHARS))
                                            put("url", hit.url)
                                            put("snippet", hit.snippet.take(MAX_SNIPPET_CHARS))
                                            hit.publishedAt?.let { publishedAt -> put("publishedAt", publishedAt) }
                                        },
                                    )
                                }
                            },
                        )
                    },
                    // 关键：外部结果不进入证据注册表——它们不是可逐字核验的教材原文。
                    evidence = emptyList(),
                    warnings = listOf(EXTERNAL_WARNING),
                )
            }
            is ExternalSearchOutcome.Unavailable -> failure(
                ToolError(ToolErrorCode.CAPABILITY_UNSUPPORTED, outcome.detail, retryable = false),
            )
            is ExternalSearchOutcome.Failed -> failure(
                ToolError(ToolErrorCode.INDEX_PARTIAL, outcome.detail, retryable = outcome.retryable),
            )
        }
    }

    private fun failure(error: ToolError): ToolResult =
        ToolResult(callId = "", toolName = TOOL_NAME, error = error)

    companion object {
        const val TOOL_NAME = "web_search"
        const val DEFAULT_LIMIT = 5
        const val MAX_TITLE_CHARS = 200
        const val MAX_SNIPPET_CHARS = 400
        const val EXTERNAL_WARNING = "外部来源：未写入知识库，不能作为教材证据引用"

        /**
         * 注册策略（W5-10）：未授权时返回 null，让工厂**根本不注册**这个工具。
         *
         * 做成独立函数是为了能被单测直接锁住——"关掉开关后模型看不到 web_search"
         * 是产品承诺，不应该只存在于工厂里的一个 if。
         */
        fun registeredWhen(
            enabled: Boolean,
            authorization: EnhancementToggles,
            port: ExternalSearchPort,
        ): AgentTool? = if (enabled) WebSearchTool(authorization, port) else null
    }
}
