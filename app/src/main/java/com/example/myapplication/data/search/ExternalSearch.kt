package com.example.myapplication.data.search

import java.net.URI

/**
 * 联网检索（W5-10）的端口与查询纪律。
 *
 * 设计口径（详细设计 §7）：
 *  - 默认**关闭**，由用户显式授权，可随时撤回；
 *  - 服务端 query **只发送用户/模型给出的检索词**，绝不把书籍段落、笔记、密钥拼进查询；
 *  - 只消费搜索 API 结果，不新增"任意 URL 抓取"能力（本期不做网页正文抓取）；
 *  - 外部来源与教材证据分开呈现，不写入知识库。
 *
 * 这里把"只发送检索词"做成可断言的结构：查询字符串只能经由 [ExternalSearchQuery.sanitize]
 * 产生，该方法只做清洗（去控制字符/折叠空白/截断），**不追加任何上下文**。
 */

/** 一条外部检索结果：只保留展示所需字段。 */
data class ExternalSearchHit(
    val title: String,
    val url: String,
    val snippet: String,
    val publishedAt: Long? = null,
)

sealed interface ExternalSearchOutcome {
    data class Ok(val hits: List<ExternalSearchHit>) : ExternalSearchOutcome

    /** 没有配置搜索服务（本期默认状态）：明确告诉模型"不可用"，不伪造结果。 */
    data class Unavailable(val code: String, val detail: String) : ExternalSearchOutcome

    data class Failed(val code: String, val detail: String, val retryable: Boolean = true) : ExternalSearchOutcome
}

/** 外部检索端口：生产实现需用户配置搜索服务后才能注册；测试用 fake。 */
interface ExternalSearchPort {
    suspend fun search(query: String, limit: Int): ExternalSearchOutcome
}

/** 本期默认实现：未配置搜索服务。永远返回 Unavailable，不联网、不返回假结果。 */
class UnconfiguredExternalSearchPort : ExternalSearchPort {
    override suspend fun search(query: String, limit: Int): ExternalSearchOutcome =
        ExternalSearchOutcome.Unavailable(
            code = "SEARCH_NOT_CONFIGURED",
            detail = "未配置联网检索服务，已跳过外部检索",
        )
}

object ExternalSearchQuery {
    const val MAX_QUERY_CHARS = 200
    const val MAX_RESULTS = 10

    /**
     * 清洗检索词：只保留单行文本，去掉控制字符、折叠空白、按上限截断。
     *
     * 注意：**不做任何上下文拼接**。书籍正文/笔记/选区如果有必要外发，
     * 必须由用户自己写进检索词并在 UI 上确认，不能在这里自动补。
     */
    fun sanitize(raw: String?): String? {
        val cleaned = raw
            ?.replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
        if (cleaned.isEmpty()) return null
        return if (cleaned.length <= MAX_QUERY_CHARS) cleaned else cleaned.take(MAX_QUERY_CHARS).trimEnd()
    }

    /** 只接受 http/https 且有主机名的 URL；其它（file/ javascript:/ data:）一律丢弃。 */
    fun isAllowedUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_URL_CHARS) return false
        return runCatching {
            val uri = URI(trimmed)
            uri.scheme?.lowercase() in setOf("http", "https") &&
                !uri.host.isNullOrBlank() && uri.rawUserInfo == null
        }.getOrDefault(false)
    }

    private const val MAX_URL_CHARS = 2_048
}
