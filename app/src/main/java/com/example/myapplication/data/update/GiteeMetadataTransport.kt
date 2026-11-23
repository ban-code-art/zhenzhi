package com.example.myapplication.data.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay

internal enum class UpdateMetadataRole(val maxBytes: Int) {
    Api(1_048_576), Manifest(65_536), Checksum(1_024),
}

internal data class UpdateMetadata(val bytes: ByteArray, val hasNextPage: Boolean = false)

internal fun interface UpdateMetadataTransport {
    suspend fun get(url: String, role: UpdateMetadataRole, deadline: UpdateCheckDeadline): UpdateMetadata
}

internal class GiteeMetadataTransport(
    private val perAttemptTimeoutMillis: Long = 90_000L,
    private val maxAttempts: Int = 2,
) : UpdateMetadataTransport {
    private val client by lazy {
        HttpClient(Android) {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 20_000
                requestTimeoutMillis = perAttemptTimeoutMillis
            }
        }
    }

    override suspend fun get(url: String, role: UpdateMetadataRole, deadline: UpdateCheckDeadline): UpdateMetadata {
        for (attempt in 0 until maxAttempts) {
            val result = try {
                follow(url, role, deadline)
            } catch (failure: UpdateCheckException) {
                throw failure
            } catch (error: IOException) {
                if (attempt + 1 >= maxAttempts) throw error
                delay(300)
                continue
            }
            when (result) {
                is GetResult.Content -> return result.value
                is GetResult.Retry -> {
                    val failure = if (result.rateLimited) UpdateCheckFailure.RateLimited else UpdateCheckFailure.Network
                    if (attempt + 1 >= maxAttempts) updateFailure(failure)
                    val wait = result.retryAfterMillis
                    if (wait > 0 && wait >= deadline.remainingMillis()) {
                        if (result.rateLimited) updateFailure(UpdateCheckFailure.RateLimited)
                        updateFailure(UpdateCheckFailure.Timeout)
                    }
                    delay(wait)
                }
                is GetResult.Redirect -> error("Must resolve redirects before returning a response")
            }
        }
        updateFailure(UpdateCheckFailure.Network)
    }

    private suspend fun follow(url: String, role: UpdateMetadataRole, deadline: UpdateCheckDeadline): GetResult {
        var uri = URI(url)
        val visited = mutableSetOf<URI>()
        repeat(6) {
            validateUpdateMetadataUri(uri, role)
            if (!visited.add(uri.normalize())) updateFailure(UpdateCheckFailure.SourceUnavailable)
            when (val result = request(uri, role, deadline)) {
                is GetResult.Redirect -> uri = try {
                    uri.resolve(result.location)
                } catch (_: IllegalArgumentException) {
                    updateFailure(UpdateCheckFailure.SourceUnavailable)
                }
                else -> return result
            }
        }
        updateFailure(UpdateCheckFailure.SourceUnavailable)
    }

    private suspend fun request(uri: URI, role: UpdateMetadataRole, deadline: UpdateCheckDeadline): GetResult =
        client.prepareGet(uri.toASCIIString()) {
            val remaining = minOf(perAttemptTimeoutMillis, deadline.remainingMillis()).takeIf { it > 0 }
                ?: updateFailure(UpdateCheckFailure.Timeout)
            timeout {
                connectTimeoutMillis = minOf(perAttemptTimeoutMillis, 10_000L, remaining)
                socketTimeoutMillis = minOf(perAttemptTimeoutMillis, 20_000L, remaining)
                requestTimeoutMillis = remaining
            }
            header(HttpHeaders.Accept, if (role == UpdateMetadataRole.Api) "application/json" else "*/*")
            header(HttpHeaders.AcceptEncoding, "identity")
        }.execute { response ->
            when (response.status.value) {
                301, 302, 303, 307, 308 -> GetResult.Redirect(
                    response.headers[HttpHeaders.Location]?.takeIf { it.isNotBlank() }
                        ?: updateFailure(UpdateCheckFailure.SourceUnavailable),
                )
                401, 403, 404 -> updateFailure(UpdateCheckFailure.SourceUnavailable)
                429, in 500..599 -> GetResult.Retry(
                    rateLimited = response.status.value == 429,
                    retryAfterMillis = retryAfter(response.headers[HttpHeaders.RetryAfter]),
                )
                200 -> {
                    val mime = response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()
                    if (mime.equals("text/html", ignoreCase = true)) {
                        updateFailure(UpdateCheckFailure.SourceUnavailable)
                    }
                    val coding = response.headers[HttpHeaders.ContentEncoding]
                    if (coding != null && !coding.equals("identity", ignoreCase = true)) {
                        updateFailure(UpdateCheckFailure.CatalogInvalid)
                    }
                    if ((response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > role.maxBytes) {
                        updateFailure(UpdateCheckFailure.CatalogInvalid)
                    }
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    val channel = response.bodyAsChannel()
                    try {
                        while (true) {
                            val size = channel.readAvailable(buffer, 0, buffer.size)
                            if (size < 0) break
                            if (out.size() + size > role.maxBytes) updateFailure(UpdateCheckFailure.CatalogInvalid)
                            out.write(buffer, 0, size)
                        }
                    } finally {
                        channel.cancel(null)
                    }
                    val bytes = out.toByteArray()
                    if (bytes.take(512).firstOrNull { it.toInt() !in listOf(9, 10, 13, 32) } == '<'.code.toByte()) {
                        updateFailure(UpdateCheckFailure.SourceUnavailable)
                    }
                    GetResult.Content(UpdateMetadata(
                        bytes, response.headers.getAll("Link").orEmpty().any { nextLink.containsMatchIn(it) },
                    ))
                }
                else -> updateFailure(UpdateCheckFailure.SourceUnavailable)
            }
        }

    private sealed interface GetResult {
        data class Content(val value: UpdateMetadata) : GetResult
        data class Redirect(val location: String) : GetResult
        data class Retry(
            val rateLimited: Boolean,
            val retryAfterMillis: Long,
        ) : GetResult
    }

    companion object {
        private val nextLink = Regex(""";\s*rel\s*=\s*(?:"[^"]*\bnext\b[^"]*"|next\b)""", RegexOption.IGNORE_CASE)
    }
}

internal fun validateUpdateMetadataUri(uri: URI, role: UpdateMetadataRole) {
    val host = uri.host?.lowercase(Locale.ROOT)
    val expectedHost = when {
        host == "gitee.com" -> true
        role != UpdateMetadataRole.Api && host in AllowedDownloadMirrorHosts -> true
        else -> false
    }
    if (!uri.scheme.equals("https", ignoreCase = true) || !expectedHost ||
        uri.port !in listOf(-1, 443) || uri.rawFragment != null || uri.rawUserInfo != null
    ) {
        updateFailure(UpdateCheckFailure.SourceUnavailable)
    }
}

private val AllowedDownloadMirrorHosts = setOf(
    "foruda.gitee.com",
    "github.com",
    "raw.githubusercontent.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
    "github-releases.githubusercontent.com",
    "cdn.gh-proxy.org",
    "gh-proxy.org",
    "gh-proxy.com",
    "v6.gh-proxy.org",
    "ghfast.top",
    "edgeone.gh-proxy.org",
)

internal class MirrorSelectionMemory {
    @Volatile var preferredPrefix: String? = null
}

/** Tries domestic mirrors in order before falling back to the canonical GitHub URL, remembering the last working mirror. */
internal class MirrorMetadataTransport(
    private val source: UpdateSource?,
    private val memory: MirrorSelectionMemory = MirrorSelectionMemory(),
    private val transport: UpdateMetadataTransport = GiteeMetadataTransport(perAttemptTimeoutMillis = 4_500L, maxAttempts = 1),
) : UpdateMetadataTransport {
    override suspend fun get(url: String, role: UpdateMetadataRole, deadline: UpdateCheckDeadline): UpdateMetadata {
        val mirrored = source?.mirrors.orEmpty().map { prefix -> prefix to "${prefix.trimEnd('/')}/$url" }
        val preferred = memory.preferredPrefix
        val orderedMirrors = if (preferred != null) {
            mirrored.filter { it.first == preferred } + mirrored.filterNot { it.first == preferred }
        } else {
            mirrored
        }
        val candidates = (orderedMirrors + (null to url)).distinctBy { it.second }
        var lastFailure: Exception? = null
        for ((prefix, candidate) in candidates) {
            if (deadline.remainingMillis() <= 0L) break
            try {
                val response = transport.get(candidate, role, deadline)
                if (prefix != null) memory.preferredPrefix = prefix
                return response
            } catch (failure: UpdateCheckException) {
                lastFailure = failure
            } catch (failure: IOException) {
                lastFailure = failure
            }
        }
        throw lastFailure ?: UpdateCheckException(UpdateCheckFailure.Timeout)
    }
}

private fun retryAfter(value: String?): Long {
    val text = value?.trim() ?: return 300
    if (text.isNotEmpty() && text.all { it in '0'..'9' }) {
        return (text.toLongOrNull() ?: Long.MAX_VALUE).coerceAtMost(Long.MAX_VALUE / 1000) * 1000
    }
    for (pattern in listOf("EEE, dd MMM yyyy HH:mm:ss zzz", "EEEE, dd-MMM-yy HH:mm:ss zzz", "EEE MMM d HH:mm:ss yyyy")) {
        val dateFormat = SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
            isLenient = false
        }
        val position = ParsePosition(0)
        val time = dateFormat.parse(text, position)?.time
        if (time != null && position.index == text.length) return (time - System.currentTimeMillis()).coerceAtLeast(0)
    }
    return 300
}
