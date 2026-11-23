package com.example.myapplication.data.update

import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class UpdateSource(
    val owner: String,
    val repository: String,
    val channel: String,
    val certificateSha256: String,
    val mirrors: List<String> = DefaultDomesticGitHubMirrors,
) {
    init {
        require(owner.matches(segment) && repository.matches(segment))
        require(channel == "release" || channel == "debug-compatible")
        require(certificateSha256.matches(updateDigest))
    }

    val tagPrefix = "s1-$channel-"
    val catalogUrl = "https://gitee.com/api/v5/repos/$owner/$repository/releases"
    val githubCatalogUrl = "https://raw.githubusercontent.com/$owner/$repository/main/catalog.json"

    fun releasePage(tag: String) = "https://gitee.com/$owner/$repository/releases/tag/$tag"
    fun githubReleasePage(tag: String) = "https://github.com/$owner/$repository/releases/tag/$tag"
    fun githubReleaseAssetUrl(tag: String, assetName: String) =
        "https://github.com/$owner/$repository/releases/download/$tag/$assetName"

    fun mirrorUrlsFor(canonicalUrl: String): List<String> =
        mirrors.map { prefix -> "${prefix.trimEnd('/')}/$canonicalUrl" }

    companion object {
        private val segment = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")
        val DefaultDomesticGitHubMirrors = listOf(
            "https://cdn.gh-proxy.org/",
            "https://gh-proxy.org/",
            "https://gh-proxy.com/",
            "https://ghfast.top/",
            "https://edgeone.gh-proxy.org/",
        )

        fun configured(repository: String, channel: String, certificate: String): UpdateSource? {
            if (repository.isEmpty()) return null
            return UpdateSource(repository.substringBefore('/'), repository.substringAfter('/'), channel, certificate)
        }
    }
}

internal data class InstalledUpdateIdentity(
    val packageName: String,
    val versionCode: Long,
    val certificateSha256: String,
    val sdk: Int,
)

internal data class UpdateRelease(
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val packageName: String,
    val certificateSha256: String,
    val sizeBytes: Long,
    val notes: String,
    val releasePage: String,
    val apk: UpdateApk? = null,
)

internal sealed interface UpdateCheckResult {
    data class Available(val release: UpdateRelease, val blockedHigher: UpdateRelease?) : UpdateCheckResult
    data object NoRelease : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data object DeviceUnsupported : UpdateCheckResult
    data object ChannelUnsupported : UpdateCheckResult
    data class Failed(val reason: UpdateCheckFailure) : UpdateCheckResult
}

internal enum class UpdateCheckFailure {
    SourceNotConfigured,
    SourceUnavailable,
    InstalledIdentityUnavailable,
    CatalogInvalid,
    CatalogIncomplete,
    Conflict,
    Network,
    RateLimited,
    Timeout,
    Interrupted,
    Unexpected,
}

internal class UpdateCheckException(val reason: UpdateCheckFailure) : IOException(reason.name)

internal sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data class Running(val requestId: Long) : UpdateCheckState
    data class Finished(val requestId: Long, val result: UpdateCheckResult) : UpdateCheckState
}

internal class UpdateCheckCoordinator(
    private val scope: CoroutineScope,
    private val check: suspend () -> UpdateCheckResult,
) {
    private val current = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    private var nextId = 0L
    val state = current.asStateFlow()

    @Synchronized
    fun request(): Long {
        (current.value as? UpdateCheckState.Running)?.let { return it.requestId }
        val request = UpdateCheckState.Running(++nextId)
        current.value = request
        scope.launch {
            val result = try {
                check()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                UpdateCheckResult.Failed(UpdateCheckFailure.Unexpected)
            }
            current.compareAndSet(request, UpdateCheckState.Finished(request.requestId, result))
        }.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                val result = UpdateCheckResult.Failed(UpdateCheckFailure.Interrupted)
                current.compareAndSet(request, UpdateCheckState.Finished(request.requestId, result))
            }
        }
        return request.requestId
    }
}

internal val updateDigest = Regex("[0-9a-f]{64}")

internal fun updateSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun updateFailure(reason: UpdateCheckFailure): Nothing = throw UpdateCheckException(reason)

internal class UpdateCheckDeadline {
    private val endNanos = System.nanoTime() + 90_000_000_000L
    fun remainingMillis(): Long = ((endNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(0)
}

internal class GiteeUpdateCheck(
    private val source: UpdateSource?,
    private val transport: UpdateMetadataTransport,
) {
    suspend fun check(installed: () -> InstalledUpdateIdentity?): UpdateCheckResult {
        val config = source ?: return UpdateCheckResult.Failed(UpdateCheckFailure.SourceNotConfigured)
        return try {
            val deadline = UpdateCheckDeadline()
            withTimeoutOrNull(deadline.remainingMillis()) {
                val local = installed() ?: updateFailure(UpdateCheckFailure.InstalledIdentityUnavailable)
                selectUpdateRelease(GiteeReleaseCatalog(config, transport, deadline).read(), config, local)
            } ?: UpdateCheckResult.Failed(UpdateCheckFailure.Timeout)
        } catch (failure: UpdateCheckException) {
            UpdateCheckResult.Failed(failure.reason)
        } catch (_: IllegalArgumentException) {
            UpdateCheckResult.Failed(UpdateCheckFailure.CatalogInvalid)
        } catch (_: IOException) {
            UpdateCheckResult.Failed(UpdateCheckFailure.Network)
        }
    }
}

internal fun selectUpdateRelease(
    catalog: List<UpdateRelease>,
    config: UpdateSource,
    installed: InstalledUpdateIdentity,
): UpdateCheckResult {
    if (catalog.isEmpty()) return UpdateCheckResult.NoRelease
    val higher = catalog.filter { it.versionCode > installed.versionCode }.sortedByDescending { it.versionCode }
    if (higher.isEmpty()) return UpdateCheckResult.UpToDate
    val channelMatches = higher.filter {
        it.packageName == installed.packageName &&
            it.certificateSha256 == config.certificateSha256 &&
            it.certificateSha256 == installed.certificateSha256
    }
    val compatible = channelMatches.firstOrNull { it.minSdk <= installed.sdk }
    if (compatible != null) {
        return UpdateCheckResult.Available(compatible, higher.first().takeIf { it.versionCode > compatible.versionCode })
    }
    return if (channelMatches.size == higher.size) UpdateCheckResult.DeviceUnsupported
    else UpdateCheckResult.ChannelUnsupported
}
