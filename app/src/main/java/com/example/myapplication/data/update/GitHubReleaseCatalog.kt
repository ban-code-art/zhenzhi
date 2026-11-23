package com.example.myapplication.data.update

import com.example.myapplication.data.reader.export.BackupV2Json
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Reads and cryptographically verifies release catalog and manifests from a public GitHub release repository. */
internal class GitHubUpdateCheck(
    private val source: UpdateSource?,
    private val transport: UpdateMetadataTransport,
) {
    suspend fun check(installed: () -> InstalledUpdateIdentity?): UpdateCheckResult {
        val config = source ?: return UpdateCheckResult.Failed(UpdateCheckFailure.SourceNotConfigured)
        return try {
            val deadline = UpdateCheckDeadline()
            withTimeoutOrNull(deadline.remainingMillis()) {
                val local = installed() ?: updateFailure(UpdateCheckFailure.InstalledIdentityUnavailable)
                selectUpdateRelease(GitHubReleaseCatalog(config, transport, deadline).read(), config, local)
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

internal class GitHubReleaseCatalog(
    private val source: UpdateSource,
    private val transport: UpdateMetadataTransport,
    private val deadline: UpdateCheckDeadline,
) {
    private data class CatalogEntry(
        val tag: String,
        val notes: String,
        val notesSha256: String,
        val manifestAssetName: String,
        val manifestSha256: String,
        val manifestSizeBytes: Long,
    )

    suspend fun read(): List<UpdateRelease> {
        val catalogJson = transport.get(source.githubCatalogUrl, UpdateMetadataRole.Manifest, deadline)
            .json(UpdateMetadataRole.Manifest)
            .obj("schemaVersion", "source", "releases")
        require(catalogJson["schemaVersion"].unsigned(1L..1L) == 1L)
        val origin = catalogJson["source"].obj("owner", "repo")
        require(origin["owner"].string() == source.owner && origin["repo"].string() == source.repository)

        val entries = catalogJson["releases"] as? JsonArray ?: updateFailure(UpdateCheckFailure.CatalogInvalid)
        val parsed = entries.mapNotNull(::catalogRow)

        val boundReleases = coroutineScope {
            parsed.map { entry ->
                async { entry to bind(entry) }
            }.awaitAll()
        }
        val tags = mutableSetOf<String>()
        val versions = mutableSetOf<Long>()
        return boundReleases.map { (entry, bound) ->
            if (!tags.add(entry.tag) || !versions.add(bound.versionCode)) {
                updateFailure(UpdateCheckFailure.Conflict)
            }
            bound
        }
    }

    private fun catalogRow(row: JsonElement): CatalogEntry? {
        val obj = row.obj("tag", "channel", "prerelease", "name", "notes", "notesSha256", "manifest")
        val tag = obj["tag"].string().also { require(it.isNotEmpty() && it.length <= 160 && it.matches(managedTag)) }
        if (!tag.startsWith(source.tagPrefix)) return null
        val channel = obj["channel"].string()
        if (channel != source.channel) return null
        val preview = (obj["prerelease"] as? JsonPrimitive)?.takeUnless { it.isString }?.content
        require(preview == "true" || preview == "false")
        if (preview == "true") return null
        obj["name"].string()
        val notes = obj["notes"].string()
        require(notes.encodeToByteArray().size <= 131_072)
        val notesSha = obj["notesSha256"].digest()
        require(notesSha == updateSha256(notes.encodeToByteArray()))
        val manifest = obj["manifest"].obj("assetName", "sha256", "sizeBytes")
        val assetName = manifest["assetName"].string().also { require(it == "update.json") }
        val manifestSha = manifest["sha256"].digest()
        val manifestSize = manifest["sizeBytes"].unsigned(1L..UpdateMetadataRole.Manifest.maxBytes.toLong())
        return CatalogEntry(tag, notes, notesSha, assetName, manifestSha, manifestSize)
    }

    private suspend fun bind(entry: CatalogEntry): UpdateRelease {
        val manifestUrl = source.githubReleaseAssetUrl(entry.tag, entry.manifestAssetName)
        val manifestBytes = transport.get(manifestUrl, UpdateMetadataRole.Manifest, deadline)
        require(manifestBytes.bytes.size.toLong() == entry.manifestSizeBytes)
        require(updateSha256(manifestBytes.bytes) == entry.manifestSha256)

        val manifest = manifestBytes.json(UpdateMetadataRole.Manifest)
            .obj("schemaVersion", "source", "release", "channel", "application", "assets")
        require(manifest["schemaVersion"].unsigned(1L..1L) == 1L)
        val origin = manifest["source"].obj("owner", "repo")
        require(origin["owner"].string() == source.owner && origin["repo"].string() == source.repository)
        require(manifest["channel"].string() == source.channel)

        val releaseInfo = manifest["release"].obj("tag", "notesSha256")
        require(releaseInfo["tag"].string() == entry.tag && releaseInfo["notesSha256"].digest() == entry.notesSha256)

        val assets = manifest["assets"].obj("apk", "checksum")
        val apk = assets["apk"].obj("name", "abi", "sizeBytes", "sha256")
        val apkName = apk["name"].string().also { require(it.length <= 180 && it.matches(apkFileName)) }
        require(apk["abi"].string() == "universal")
        val apkSize = apk["sizeBytes"].unsigned(1L..100_000_000L)
        val apkSha = apk["sha256"].digest()
        val sidecar = assets["checksum"].obj("name")
        val sidecarName = "$apkName.sha256"
        require(sidecar["name"].string() == sidecarName)

        val application = manifest["application"].obj(
            "packageName", "versionCode", "versionName", "minSdk", "targetSdk", "signingCertificateSha256",
        )
        val packageName = application["packageName"].string().also {
            require(it.length <= 200 && it.matches(androidPackage))
        }
        val versionName = application["versionName"].string().also {
            require(it.isNotBlank() && it.codePointCount(0, it.length) <= 80 && it.none { c -> c.code < 32 })
        }
        val code = application["versionCode"].unsigned(1L..Int.MAX_VALUE.toLong())
        val minSdk = application["minSdk"].unsigned(1L..1000).toInt()
        application["targetSdk"].unsigned(minSdk.toLong()..1000)
        val certificate = application["signingCertificateSha256"].digest()

        val checksumUrl = source.githubReleaseAssetUrl(entry.tag, sidecarName)
        val checksum = transport.get(checksumUrl, UpdateMetadataRole.Checksum, deadline)
        require(checksum.bytes.contentEquals("$apkSha\n".encodeToByteArray()))

        val canonicalApkUrl = source.githubReleaseAssetUrl(entry.tag, apkName)
        val download = UpdateApk(
            url = canonicalApkUrl,
            sha256 = apkSha,
            sizeBytes = apkSize,
            mirrorUrls = source.mirrorUrlsFor(canonicalApkUrl),
        )
        return UpdateRelease(
            code, versionName, minSdk, packageName, certificate, apkSize, entry.notes,
            source.githubReleasePage(entry.tag), download,
        )
    }

    private fun UpdateMetadata.json(role: UpdateMetadataRole): JsonElement =
        BackupV2Json.parse(bytes, role.maxBytes).also { validateDepth(it, 0) }

    private fun validateDepth(element: JsonElement, depth: Int) {
        require(depth <= 32)
        when (element) {
            is JsonObject -> element.values.forEach { validateDepth(it, depth + 1) }
            is JsonArray -> element.forEach { validateDepth(it, depth + 1) }
            is JsonPrimitive -> Unit
        }
    }

    private fun JsonElement?.obj(vararg keys: String): JsonObject {
        val value = this as? JsonObject ?: updateFailure(UpdateCheckFailure.CatalogInvalid)
        require(keys.isEmpty() || value.keys == keys.toSet())
        return value
    }

    private fun JsonElement?.string(): String {
        val value = this as? JsonPrimitive
        require(value != null && value.isString)
        return value.content
    }

    private fun JsonElement?.unsigned(range: LongRange): Long {
        val value = this as? JsonPrimitive
        require(value != null && !value.isString && value.content.matches(unsignedInteger))
        return requireNotNull(value.content.toLongOrNull()).also { require(it in range) }
    }

    private fun JsonElement?.digest(): String = string().also { require(it.matches(updateDigest)) }

    companion object {
        private val unsignedInteger = Regex("0|[1-9][0-9]*")
        private val managedTag = Regex("s1-(release|debug-compatible)-[A-Za-z0-9][A-Za-z0-9._-]*")
        private val apkFileName = Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk")
        private val androidPackage = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
