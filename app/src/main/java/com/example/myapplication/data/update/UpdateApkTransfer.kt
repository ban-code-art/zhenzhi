package com.example.myapplication.data.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

internal data class UpdateApk(
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val mirrorUrls: List<String> = emptyList(),
) {
    init {
        require(sha256.matches(updateDigest) && sizeBytes in 1..100_000_000)
    }
}

internal fun interface UpdateApkTransport {
    suspend fun transfer(apk: UpdateApk, consume: (ByteArray, Int) -> Unit)
}

internal class UpdatePackageInvalid : IOException()

internal suspend fun receiveUpdateApk(
    transport: UpdateApkTransport,
    apk: UpdateApk,
    destination: File,
    onProgress: (Long) -> Unit,
) {
    val candidates = (apk.mirrorUrls + apk.url).distinct()
    var lastFailure: Exception? = null
    for (candidateUrl in candidates) {
        try {
            receiveSingleUpdateApk(transport, apk.copy(url = candidateUrl, mirrorUrls = emptyList()), destination, onProgress)
            return
        } catch (cancelled: CancellationException) {
            destination.delete()
            throw cancelled
        } catch (failure: Exception) {
            destination.delete()
            lastFailure = failure
        }
    }
    throw lastFailure ?: IOException("No APK download URL available")
}

private suspend fun receiveSingleUpdateApk(
    transport: UpdateApkTransport,
    apk: UpdateApk,
    destination: File,
    onProgress: (Long) -> Unit,
) {
    val digest = MessageDigest.getInstance("SHA-256")
    var total = 0L
    try {
        FileOutputStream(destination).use { output ->
            transport.transfer(apk) { buffer, count ->
                if (count !in 1..buffer.size || total + count > apk.sizeBytes) throw UpdatePackageInvalid()
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                total += count
                onProgress(total)
            }
            if (total != apk.sizeBytes || digest.digest().toSha256Hex() != apk.sha256) throw UpdatePackageInvalid()
            output.fd.sync()
        }
    } catch (failure: Exception) {
        destination.delete()
        throw failure
    }
}

internal fun ByteArray.toSha256Hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }

internal class GiteeApkTransport : UpdateApkTransport {
    private val client by lazy {
        HttpClient(Android) {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 30_000
                requestTimeoutMillis = 900_000
            }
        }
    }

    override suspend fun transfer(apk: UpdateApk, consume: (ByteArray, Int) -> Unit) {
        var uri = URI(apk.url)
        val visited = mutableSetOf<URI>()
        repeat(6) {
            validateUpdateMetadataUri(uri, UpdateMetadataRole.Manifest)
            if (!visited.add(uri.normalize())) throw IOException("APK redirect loop")
            val next = client.prepareGet(uri.toASCIIString()) {
                header(HttpHeaders.AcceptEncoding, "identity")
            }.execute { response ->
                when (response.status.value) {
                    301, 302, 303, 307, 308 -> response.headers[HttpHeaders.Location]
                        ?.takeIf { it.isNotBlank() } ?: throw IOException("Missing APK redirect")
                    200 -> {
                        val type = response.headers[HttpHeaders.ContentType].orEmpty().substringBefore(';').trim()
                        val encoding = response.headers[HttpHeaders.ContentEncoding]
                        val bytes = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                        if (type.equals("text/html", ignoreCase = true) ||
                            (encoding != null && !encoding.equals("identity", ignoreCase = true))
                        ) {
                            throw IOException("Unexpected APK response")
                        }
                        if (bytes != null && bytes != apk.sizeBytes) throw UpdatePackageInvalid()
                        val stream = response.bodyAsChannel()
                        try {
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = stream.readAvailable(buffer, 0, buffer.size)
                                if (n < 0) break
                                if (n > 0) consume(buffer, n)
                            }
                        } finally {
                            stream.cancel(null)
                        }
                        null
                    }
                    else -> throw IOException("APK unavailable")
                }
            }
            if (next == null) return
            uri = uri.resolve(next)
        }
        throw IOException("Too many APK redirects")
    }
}
