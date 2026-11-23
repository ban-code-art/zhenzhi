package com.example.myapplication.data.ai.net

import com.example.myapplication.data.ai.protocol.AiErrorClassifier
import com.example.myapplication.data.ai.protocol.SseFrameParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow

/**
 * 统一 HTTP 传输层。
 *
 * - 连接 15s、首事件 30s、空闲 60s、总请求 180s（可调）
 * - 计时用 monotonic（System.nanoTime）
 * - 取消直接断 HTTP 调用；不自动整轮重发
 * - 不记录 key/正文到日志
 */
/**
 * 非流式 HTTP 执行端口。生产实现是 [KtorTransport]；测试注入受控 fake，
 * 但生产 probe/索引路径只接受真实 transport（不把 Mock 当联通证据）。
 */
interface HttpExecutor {
    suspend fun execute(request: KtorTransport.Request): KtorTransport.Response
}

class KtorTransport(
    private val client: HttpClient = defaultClient(),
) : HttpExecutor {
    data class Request(
        val url: String,
        val method: String = "POST",
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
        val stream: Boolean = false,
    )

    data class Response(
        val status: Int,
        val body: String,
        val headers: Map<String, String>,
    )

    /** 非流式请求：方法按 [Request.method] 执行（模型发现等 GET 请求不能被当成 POST）。 */
    override suspend fun execute(request: Request): Response {
        val response = client.request(request.url) {
            method = request.httpMethod()
            request.headers.forEach { (name, value) -> header(name, value) }
            if (request.body != null) {
                contentType(ContentType.Application.Json)
                setBody(request.body)
            }
        }
        return Response(
            status = response.status.value,
            body = response.bodyAsText(),
            headers = response.headers.entries().associate { it.key to it.value.firstOrNull().orEmpty() },
        )
    }

    /**
     * SSE 流式请求：把每个已 dispatch 的 SSE 帧交给 [onFrame]。
     * 返回 HTTP 状态；调用方据此分类错误。
     */
    suspend fun stream(
        request: Request,
        onFrame: suspend (SseFrameParser.Frame) -> Unit,
    ): Int {
        return client.prepareRequest(request.url) {
            method = request.httpMethod()
            request.headers.forEach { (name, value) -> header(name, value) }
            header(HttpHeaders.Accept, "text/event-stream")
            if (request.body != null) {
                contentType(ContentType.Application.Json)
                setBody(request.body)
            }
        }.execute { response ->
            val channel = response.bodyAsChannel()
            try {
                if (response.status.isSuccess()) {
                    val parser = SseFrameParser()
                    while (!channel.isClosedForRead) {
                        val line = channel.readUTF8Line() ?: break
                        parser.accept(line)?.let { onFrame(it) }
                    }
                    parser.finish()?.let { onFrame(it) }
                }
                response.status.value
            } finally {
                channel.cancel(null)
            }
        }
    }

    companion object {
        fun defaultClient(): HttpClient = HttpClient(Android) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 180_000
                socketTimeoutMillis = 60_000
            }
        }
    }
}

/**
 * 把字符串方法解析成 Ktor 的 [HttpMethod]。
 *
 * 这里用显式白名单而不用 `HttpMethod.parse`：Ktor 的 parse 对未知字符串会**新建**一个
 * 自定义方法（不报错也不回退），拼错的方法名会被原样发给服务端；白名单保证只有明确支持
 * 的动词才会离开本进程，其余回退 POST（保持旧行为，不把配置错误变成崩溃）。
 *
 * 方法解析由 `KtorTransportMethodTest` 覆盖；`YuMarkProtocolPortTest` 通过受控 ByteChannel
 * 引擎执行真实 Ktor 客户端/stream 路径，另核验 UTF-8 分片和响应释放。
 */
internal fun KtorTransport.Request.httpMethod(): HttpMethod = when (method.trim().uppercase()) {
    "GET" -> HttpMethod.Get
    "PUT" -> HttpMethod.Put
    "DELETE" -> HttpMethod.Delete
    "PATCH" -> HttpMethod.Patch
    "HEAD" -> HttpMethod.Head
    "OPTIONS" -> HttpMethod.Options
    // 默认与空串都按 POST：AI 端点的生成/探测请求绝大多数是 POST。
    "", "POST" -> HttpMethod.Post
    else -> HttpMethod.Post
}

/**
 * 有界重试包装：只在“尚未产生任何输出”时重试。
 * 已输出文本/已执行工具/非幂等操作后不自动整轮重发（由调用方保证 emittedAny 正确）。
 */
suspend fun <T> withBoundedRetry(
    maxAttempts: Int = 3,
    emittedAny: () -> Boolean,
    backoff: (attempt: Int) -> Long = { AiErrorClassifier.backoffMillis(it) },
    block: suspend (attempt: Int) -> RetryOutcome<T>,
): T? {
    var attempt = 0
    while (attempt < maxAttempts) {
        when (val outcome = block(attempt)) {
            is RetryOutcome.Success -> return outcome.value
            is RetryOutcome.Fatal -> return null
            is RetryOutcome.Retry -> {
                if (emittedAny() || attempt >= maxAttempts - 1) return null
                kotlinx.coroutines.delay(outcome.delayMs ?: backoff(attempt))
                attempt++
            }
        }
    }
    return null
}

sealed interface RetryOutcome<out T> {
    data class Success<T>(val value: T) : RetryOutcome<T>
    data class Retry(val delayMs: Long? = null) : RetryOutcome<Nothing>
    data class Fatal(val error: com.example.myapplication.domain.ai.AiError) : RetryOutcome<Nothing>
}
