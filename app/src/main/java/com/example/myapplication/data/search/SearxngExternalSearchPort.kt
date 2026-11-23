package com.example.myapplication.data.search

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Bounded SearXNG JSON API client. Only the sanitized query is sent; redirects are rejected. */
class SearxngExternalSearchPort(
    private val settings: ExternalSearchSettings,
) : ExternalSearchPort {
    override suspend fun search(query: String, limit: Int): ExternalSearchOutcome {
        val cleanQuery = ExternalSearchQuery.sanitize(query)
            ?: return ExternalSearchOutcome.Failed("EMPTY_QUERY", "检索词为空", retryable = false)
        val endpoint = settings.endpoint()
            ?: return ExternalSearchOutcome.Unavailable("SEARCH_NOT_CONFIGURED", "请先配置 SearXNG 搜索地址")
        val resultLimit = limit.coerceIn(1, ExternalSearchQuery.MAX_RESULTS)
        return try {
            withContext(Dispatchers.IO) { request(endpoint, cleanQuery, resultLimit) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ExternalSearchOutcome.Failed("SEARCH_TRANSPORT_ERROR", "外部检索失败：${error.javaClass.simpleName}")
        }
    }

    private fun request(endpoint: String, query: String, limit: Int): ExternalSearchOutcome {
        val url = "$endpoint?q=${URLEncoder.encode(query, "UTF-8")}&format=json"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "AcupunctureLearning/1.0")
        }
        return try {
            val status = connection.responseCode
            if (status !in 200..299) {
                return ExternalSearchOutcome.Failed(
                    code = if (status in 300..399) "SEARCH_REDIRECT_REJECTED" else "SEARCH_HTTP_$status",
                    detail = "搜索服务返回 HTTP $status",
                    retryable = status == 429 || status >= 500,
                )
            }
            val declaredLength = connection.contentLengthLong
            if (declaredLength > MAX_RESPONSE_BYTES) {
                return ExternalSearchOutcome.Failed("SEARCH_RESPONSE_TOO_LARGE", "搜索响应超过大小上限", retryable = false)
            }
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream(minOf(declaredLength.takeIf { it >= 0 }?.toInt() ?: 4096, MAX_RESPONSE_BYTES))
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_RESPONSE_BYTES) {
                        return ExternalSearchOutcome.Failed("SEARCH_RESPONSE_TOO_LARGE", "搜索响应超过大小上限", retryable = false)
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            val root = runCatching { JSON.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
                ?: return ExternalSearchOutcome.Failed("SEARCH_RESPONSE_INVALID", "搜索服务返回了无效 JSON", retryable = false)
            val results = root["results"] as? JsonArray
                ?: return ExternalSearchOutcome.Failed("SEARCH_RESPONSE_INVALID", "JSON 缺少 results 数组", retryable = false)
            val hits = results.mapNotNull { element ->
                val row = element as? JsonObject ?: return@mapNotNull null
                val title = row.string("title")?.trim()?.take(MAX_TITLE_CHARS)?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val resultUrl = row.string("url")?.trim()?.takeIf { ExternalSearchQuery.isAllowedUrl(it) }
                    ?: return@mapNotNull null
                val snippet = row.string("content")?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_SNIPPET_CHARS).orEmpty()
                val publishedAt = row.string("publishedDate")?.let { value ->
                    value.toLongOrNull()
                }
                ExternalSearchHit(title, resultUrl, snippet, publishedAt)
            }.distinctBy { it.url }.take(limit)
            ExternalSearchOutcome.Ok(hits)
        } finally {
            connection.disconnect()
        }
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 8_000
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_TITLE_CHARS = 300
        private const val MAX_SNIPPET_CHARS = 1_200
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
