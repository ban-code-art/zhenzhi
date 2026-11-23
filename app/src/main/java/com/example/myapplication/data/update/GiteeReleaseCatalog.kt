package com.example.myapplication.data.update

import com.example.myapplication.data.reader.export.BackupV2Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class GiteeReleaseCatalog(
    private val source: UpdateSource,
    private val transport: UpdateMetadataTransport,
    private val deadline: UpdateCheckDeadline,
) {
    private data class Release(val id: Long, val tag: String, val preview: Boolean?, val notes: String?)
    private data class Attachment(val id: Long, val name: String, val size: Long)

    suspend fun read(): List<UpdateRelease> {
        val catalog = directory(source.catalogUrl, ::releaseRow, Release::id)
        val (head, hasNext) = page(source.catalogUrl, 1, ::releaseRow)
        if (head != catalog.take(PageSize) || (head.size < PageSize && hasNext)) {
            updateFailure(UpdateCheckFailure.CatalogIncomplete)
        }

        val tags = mutableSetOf<String>()
        val versions = mutableSetOf<Long>()
        return catalog.filter { it.preview == false }.map { release ->
            val entry = "${source.catalogUrl}/${release.id}"
            if (releaseRow(transport.get(entry, UpdateMetadataRole.Api, deadline).json(UpdateMetadataRole.Api)) != release) {
                updateFailure(UpdateCheckFailure.CatalogIncomplete)
            }
            val bound = bind(entry, release)
            if (!tags.add(release.tag) || !versions.add(bound.versionCode)) {
                updateFailure(UpdateCheckFailure.Conflict)
            }
            bound
        }
    }

    private fun releaseRow(row: JsonElement): Release {
        val obj = row.obj()
        val id = obj["id"].unsigned(1L..MaxId)
        val tag = obj["tag_name"].string().also { require(it.isNotEmpty()) }
        if (!tag.startsWith(source.tagPrefix)) return Release(id, tag, null, null)
        val preview = (obj["prerelease"] as? JsonPrimitive)?.takeUnless { it.isString }?.content
        require(preview == "true" || preview == "false")
        if (preview == "true") return Release(id, tag, true, null)
        require(tag.matches(managedTag) && tag.length <= 160)
        val notes = obj["body"].let { if (it == JsonNull) "" else it.string() }
        require(notes.encodeToByteArray().size <= 131_072)
        return Release(id, tag, false, notes)
    }

    private fun attachmentRow(row: JsonElement): Attachment {
        val obj = row.obj()
        val name = obj["name"].string().also { require(it.isNotEmpty()) }
        return Attachment(obj["id"].unsigned(1L..MaxId), name, obj["size"].unsigned(0L..Long.MAX_VALUE))
    }

    private suspend fun bind(entry: String, release: Release): UpdateRelease {
        val attachments = directory("$entry/attach_files", ::attachmentRow, Attachment::id)
        val manifestRow = attachments.singleNamed("update.json")
        require(manifestRow.size in 1L..UpdateMetadataRole.Manifest.maxBytes.toLong())
        val manifest = fetchAttachment(entry, manifestRow, UpdateMetadataRole.Manifest)
            .json(UpdateMetadataRole.Manifest).obj("schemaVersion", "source", "release", "channel", "application", "assets")
        require(manifest["schemaVersion"].unsigned(1L..1L) == 1L)
        val origin = manifest["source"].obj("owner", "repo")
        require(origin["owner"].string() == source.owner && origin["repo"].string() == source.repository)
        require(manifest["channel"].string() == source.channel)

        val releaseInfo = manifest["release"].obj("id", "tag", "notesSha256")
        require(releaseInfo["id"].unsigned(1L..MaxId) == release.id && releaseInfo["tag"].string() == release.tag)
        val notes = requireNotNull(release.notes)
        require(releaseInfo["notesSha256"].digest() == updateSha256(notes.encodeToByteArray()))

        val assets = manifest["assets"].obj("apk", "checksum")
        val apk = assets["apk"].obj("attachFileId", "name", "abi", "sizeBytes", "sha256")
        val apkName = apk["name"].string().also { require(it.length <= 180 && it.matches(apkFileName)) }
        val apkRow = attachments.singleNamed(apkName)
        require(apk["abi"].string() == "universal")
        require(apk["attachFileId"].unsigned(1L..MaxId) == apkRow.id)
        require(apk["sizeBytes"].unsigned(1L..100_000_000L) == apkRow.size)
        val apkSha = apk["sha256"].digest()
        val sidecar = assets["checksum"].obj("attachFileId", "name")
        val sidecarName = "$apkName.sha256"
        require(sidecar["name"].string() == sidecarName)
        val sidecarRow = attachments.singleNamed(sidecarName)
        require(sidecar["attachFileId"].unsigned(1L..MaxId) == sidecarRow.id && sidecarRow.size == 65L)
        require(setOf(manifestRow.id, apkRow.id, sidecarRow.id).size == 3)

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

        val checksum = fetchAttachment(entry, sidecarRow, UpdateMetadataRole.Checksum)
        require(checksum.bytes.contentEquals("$apkSha\n".encodeToByteArray()))
        val download = UpdateApk("$entry/attach_files/${apkRow.id}/download", apkSha, apkRow.size)
        return UpdateRelease(
            code, versionName, minSdk, packageName, certificate, apkRow.size, notes, source.releasePage(release.tag), download,
        )
    }

    private suspend fun fetchAttachment(entry: String, row: Attachment, role: UpdateMetadataRole): UpdateMetadata {
        val response = transport.get("$entry/attach_files/${row.id}/download", role, deadline)
        require(response.bytes.size.toLong() == row.size)
        return response
    }

    private fun List<Attachment>.singleNamed(name: String): Attachment =
        requireNotNull(singleOrNull { it.name == name })

    private suspend fun <T> directory(
        url: String,
        decode: (JsonElement) -> T,
        id: (T) -> Long,
    ): List<T> {
        val entries = mutableListOf<T>()
        val seen = mutableSetOf<Long>()
        for (index in 1..5) {
            val (rows, hasNext) = page(url, index, decode)
            if (rows.any { !seen.add(id(it)) }) updateFailure(UpdateCheckFailure.CatalogIncomplete)
            entries.addAll(rows)
            if (rows.size < PageSize) {
                if (hasNext) updateFailure(UpdateCheckFailure.CatalogIncomplete)
                return entries
            }
        }
        updateFailure(UpdateCheckFailure.CatalogIncomplete)
    }

    private suspend fun <T> page(
        url: String,
        index: Int,
        decode: (JsonElement) -> T,
    ): Pair<List<T>, Boolean> {
        val metadata = transport.get("$url?page=$index&per_page=$PageSize&direction=desc", UpdateMetadataRole.Api, deadline)
        val array = metadata.json(UpdateMetadataRole.Api) as? JsonArray
            ?: updateFailure(UpdateCheckFailure.CatalogInvalid)
        require(array.size <= PageSize)
        val ids = array.map { it.obj()["id"].unsigned(1L..MaxId) }
        require(ids.toSet().size == ids.size)
        return array.map(decode) to metadata.hasNextPage
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
        private const val PageSize = 20
        private const val MaxId = 9_007_199_254_740_991L
        private val unsignedInteger = Regex("0|[1-9][0-9]*")
        private val managedTag = Regex("s1-(release|debug-compatible)-[A-Za-z0-9][A-Za-z0-9._-]*")
        private val apkFileName = Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk")
        private val androidPackage = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
