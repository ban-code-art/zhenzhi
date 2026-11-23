package com.example.myapplication.data.ai.protocol

/**
 * SSE 帧解析器：处理多行 data、event/id、注释行、空行分隔、EOF。
 *
 * UTF-8 拆包由调用方（Ktor readUTF8Line / decodeToString）保证；本类只处理
 * 已解码的文本行，因此可完全离线单测。
 *
 * 事件按 SSE 规范聚合：
 *  - `data:` 行追加到当前事件的 data 缓冲（多行以 \n 连接）
 *  - `event:` 设置事件类型
 *  - `id:` 设置事件 id
 *  - `:` 开头是注释（心跳），忽略但保留“收到过字节”的信号
 *  - 空行触发 dispatch
 *  - EOF 时若缓冲非空也 dispatch（部分服务端不补最后空行）
 */
class SseFrameParser {
    data class Frame(
        val event: String?,
        val id: String?,
        val data: String,
        val sawCommentOnly: Boolean = false,
    )

    private val dataLines = mutableListOf<String>()
    private var eventName: String? = null
    private var eventId: String? = null
    private var sawComment = false

    fun accept(rawLine: String): Frame? {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) {
            sawComment = true
            return null
        }
        val colon = line.indexOf(':')
        val field: String
        val rawValue: String
        if (colon < 0) {
            field = line
            rawValue = ""
        } else {
            field = line.substring(0, colon)
            rawValue = line.substring(colon + 1).removePrefix(" ")
        }
        when (field) {
            "data" -> dataLines.add(rawValue)
            "event" -> eventName = rawValue
            "id" -> eventId = rawValue
            "retry" -> Unit // 客户端重连间隔由自身策略控制，不采纳服务端数值
            else -> Unit
        }
        return null
    }

    /** EOF：把尚未 dispatch 的帧交给调用方。 */
    fun finish(): Frame? = dispatch()

    private fun dispatch(): Frame? {
        if (dataLines.isEmpty() && eventName == null && eventId == null) {
            val commentOnly = sawComment
            sawComment = false
            return if (commentOnly) Frame(event = null, id = null, data = "", sawCommentOnly = true) else null
        }
        val frame = Frame(
            event = eventName,
            id = eventId,
            data = dataLines.joinToString("\n"),
            sawCommentOnly = false,
        )
        dataLines.clear()
        eventName = null
        eventId = null
        sawComment = false
        return frame
    }
}

/**
 * 工具调用累积器：按 index + callId 组装，支持多工具交错与参数分片。
 * 残缺（未完成）的工具不会出现在 buildComplete 结果里。
 */
class ToolCallAccumulator {
    private class Builder {
        var callId: String? = null
        var name: String? = null
        val arguments = StringBuilder()
        var completed = false
        var delivered = false
    }

    private val byIndex = linkedMapOf<Int, Builder>()
    private val identityIndices = mutableMapOf<String, Int>()
    private var nextIdentityIndex = 0
    private var inputTokens = 0
    private var outputTokens = 0
    private var finishDelivered = false

    /** Responses item ids and call ids are separate opaque namespaces, never hash indexes. */
    fun identityIndex(callId: String?, itemId: String?, outputIndex: Int?, name: String? = null, argumentsJson: String? = null): Int {
        val keys = listOfNotNull(
            callId?.takeIf { it.isNotBlank() }?.let { "call:$it" },
            itemId?.takeIf { it.isNotBlank() }?.let { "item:$it" },
            outputIndex?.let { "output:$it" },
        )
        require(keys.isNotEmpty()) { "Tool event has no identity" }
        val boundIndices = keys.mapNotNull { identityIndices[it] }.distinct()
        require(boundIndices.size <= 1) { "Tool identity aliases conflict" }
        val boundIndex = boundIndices.firstOrNull()
        val boundBuilder = boundIndex?.let { byIndex[it] }
        val boundCallId = boundBuilder?.callId
        require(callId == null || boundCallId == null || callId == boundCallId) { "Tool call identity conflicts" }
        require(name == null || boundBuilder?.name == null || name == boundBuilder?.name) { "Tool function name conflicts" }
        if (boundBuilder?.completed == true && argumentsJson != null) {
            require(sameArguments(boundBuilder.arguments.toString(), argumentsJson)) { "Tool arguments conflict" }
        }
        val index = boundIndex ?: run {
            while (byIndex.containsKey(nextIdentityIndex)) nextIdentityIndex++
            nextIdentityIndex++
            nextIdentityIndex - 1
        }
        keys.forEach { identityIndices[it] = index }
        return index
    }

    fun usage(input: Int?, output: Int?): Pair<Int, Int> {
        input?.let { inputTokens = maxOf(inputTokens, it.coerceAtLeast(0)) }
        output?.let { outputTokens = maxOf(outputTokens, it.coerceAtLeast(0)) }
        return inputTokens to outputTokens
    }

    fun finishOnce(): Boolean {
        if (finishDelivered) return false
        finishDelivered = true
        return true
    }

    fun hasToolCalls(): Boolean = byIndex.values.any { !it.callId.isNullOrBlank() && !it.name.isNullOrBlank() }

    fun accept(index: Int, callId: String?, name: String?, argumentsDelta: String?) {
        val builder = byIndex.getOrPut(index) { Builder() }
        require(callId == null || builder.callId == null || callId == builder.callId) { "Tool call identity conflicts" }
        require(name == null || builder.name == null || name == builder.name) { "Tool function name conflicts" }
        if (builder.delivered) {
            require(argumentsDelta.isNullOrEmpty() || sameArguments(builder.arguments.toString(), argumentsDelta)) { "Tool arguments conflict" }
            return
        }
        if (callId != null) builder.callId = callId
        if (name != null) builder.name = name
        if (argumentsDelta != null) builder.arguments.append(argumentsDelta)
    }

    fun markComplete(index: Int) {
        completeArguments(index, null)
    }

    fun markCompleteByCallId(callId: String) {
        byIndex.values.firstOrNull { it.callId == callId }?.completed = true
    }

    /** Authoritative done payload replaces fragments, rather than appending them a second time. */
    fun completeArguments(index: Int, argumentsJson: String?) {
        val builder = byIndex[index] ?: return
        if (builder.completed && argumentsJson != null) {
            require(sameArguments(builder.arguments.toString(), argumentsJson)) { "Tool arguments conflict" }
        }
        if (builder.delivered) return
        if (argumentsJson != null) {
            builder.arguments.clear()
            builder.arguments.append(argumentsJson)
        }
        if (builder.arguments.isEmpty()) builder.arguments.append("{}")
        builder.completed = true
    }

    private fun sameArguments(first: String, second: String): Boolean {
        if (first == second) return true
        val json = kotlinx.serialization.json.Json
        val firstValue = runCatching { json.parseToJsonElement(first) }.getOrNull() ?: return false
        val secondValue = runCatching { json.parseToJsonElement(second) }.getOrNull() ?: return false
        return firstValue == secondValue
    }

    fun isEmpty(): Boolean = byIndex.isEmpty()

    /** 已完整组装且 callId/name 齐全的工具调用。 */
    fun buildComplete(): List<AssembledToolCall> = byIndex.entries
        .filter { it.value.completed && !it.value.delivered && !it.value.callId.isNullOrBlank() && !it.value.name.isNullOrBlank() }
        .map { (index, builder) ->
            builder.delivered = true
            AssembledToolCall(
                index = index,
                callId = builder.callId!!,
                name = builder.name!!,
                argumentsJson = builder.arguments.toString(),
            )
        }

    /** 未完成（流中断）的 index，用于把 run 标为 INTERRUPTED。 */
    fun incompleteIndices(): List<Int> = byIndex.entries
        .filter { !it.value.completed || it.value.callId.isNullOrBlank() || it.value.name.isNullOrBlank() }
        .map { it.key }
}

data class AssembledToolCall(
    val index: Int,
    val callId: String,
    val name: String,
    val argumentsJson: String,
)

/**
 * 错误分类：把 HTTP 状态 / provider 错误码映射为统一三类 + retryable。
 * 401/403 → AUTH（需用户修复，不自动重试）；
 * 429 → RATE_LIMIT（读 Retry-After，有界重试）；
 * 408/409/5xx/网络异常 → TRANSIENT（指数退避 + jitter）。
 */
object AiErrorClassifier {
    fun fromHttp(status: Int, retryAfterMs: Long? = null, providerCode: String? = null): com.example.myapplication.domain.ai.AiError {
        return when {
            status == 401 || status == 403 -> com.example.myapplication.domain.ai.AiError(
                kind = com.example.myapplication.domain.ai.AiErrorKind.AUTH,
                code = providerCode ?: "HTTP_$status",
                retryable = false,
                message = "鉴权失败，请检查密钥或权限",
            )
            status == 429 -> com.example.myapplication.domain.ai.AiError(
                kind = com.example.myapplication.domain.ai.AiErrorKind.RATE_LIMIT,
                code = providerCode ?: "HTTP_429",
                retryable = true,
                retryAtMs = retryAfterMs,
                message = "请求过于频繁，请稍后重试",
            )
            status in 400..499 -> com.example.myapplication.domain.ai.AiError(
                kind = com.example.myapplication.domain.ai.AiErrorKind.INVALID_REQUEST,
                code = providerCode ?: "HTTP_$status",
                retryable = false,
                message = "请求被拒绝（HTTP $status）",
            )
            status >= 500 -> com.example.myapplication.domain.ai.AiError(
                kind = com.example.myapplication.domain.ai.AiErrorKind.TRANSIENT,
                code = providerCode ?: "HTTP_$status",
                retryable = true,
                retryAtMs = retryAfterMs,
                message = "服务暂时不可用（HTTP $status）",
            )
            else -> com.example.myapplication.domain.ai.AiError(
                kind = com.example.myapplication.domain.ai.AiErrorKind.UNKNOWN,
                code = providerCode ?: "HTTP_$status",
                retryable = false,
                message = "未知错误（HTTP $status）",
            )
        }
    }

    /** Retry-After 头：秒数或 HTTP-date。无法解析返回 null，由调用方用默认退避。 */
    fun parseRetryAfter(value: String?, nowMs: Long): Long? {
        if (value.isNullOrBlank()) return null
        val seconds = value.trim().toLongOrNull()
        if (seconds != null) return (seconds * 1000L).coerceAtLeast(0)
        return runCatching {
            val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            val date = format.parse(value) ?: return null
            (date.time - nowMs).coerceAtLeast(0)
        }.getOrNull()
    }

    /** 指数退避 + jitter：30s 起，上限 30min。 */
    fun backoffMillis(attempt: Int, random: java.util.Random = java.util.Random()): Long {
        val base = 30_000L shl attempt.coerceIn(0, 6)
        val capped = base.coerceAtMost(30 * 60_000L)
        val jitter = (capped * 0.2 * random.nextDouble()).toLong()
        return capped + jitter
    }
}
