package com.example.myapplication.data.reader.content

import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.channels.FileLock
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

data class StagedContentPackage(val versionId: String, val validation: ContentPackageValidation)
data class ContentPackageActiveVersion(val versionId: String, val generation: Long)
data class PinnedContentPackage(val activeVersion: ContentPackageActiveVersion, val input: ContentPackageArchiveInput)

/** File-layer publication foundation. No Room/seeder/default production entry is connected. */
class ContentPackageVersionStore internal constructor(
    private val root: File,
    private val pointerMove: (File, File) -> Unit,
) {
    constructor(root: File) : this(root, { source, target ->
        Api24PrivateFiles.atomicMove(source, target, replace = true)
    })

    private val rootPath = Api24PrivateFiles.normalized(root).also {
        require(it.parentFile != null) { "store: filesystem root is not an app-private directory" }
    }
    private val monitor = rootLocks.computeIfAbsent(Api24PrivateFiles.key(rootPath)) { Any() }

    fun stage(file: File): StagedContentPackage = locked {
        readCurrent() // A malformed existing pointer must never be treated as a fresh store.
        val source = Api24PrivateFiles.normalized(file)
        val snapshot = readBounded(source, MAX_ZIP, 22)
        val input = ContentPackageArchive.read(snapshot)
        val report = validate(input, ContentValidationMode.CANONICAL)
        require(report.canonicalValid == true) { "store.stage: canonical validation failed: ${report.issues}" }
        val id = ContentPackageCodec.sha256(snapshot)
        val target = versionPath(id)
        if (Api24PrivateFiles.exists(target)) {
            readVersionSnapshot(id) // Never replace a corrupted file already occupying this hash.
        } else {
            val temporary = forcedTemporary(versionsPath(), ".stage-", snapshot)
            try {
                checkLinks(target)
                Api24PrivateFiles.atomicMove(temporary, target, replace = false)
            } finally {
                discardTemporary(temporary)
            }
        }
        StagedContentPackage(id, report.copy(issues = Collections.unmodifiableList(report.issues.toList())))
    }

    fun activate(
        versionId: String,
        expectedGeneration: Long,
        professionalVerifier: ContentProfessionalReviewVerifier? = null,
    ): ContentPackageActiveVersion {
        requireVersionId(versionId)
        require(expectedGeneration >= 0) { "store.activate: negative expected generation" }
        return locked {
            val current = readCurrent()?.activeVersion
            val generation = current?.generation ?: 0
            check(generation == expectedGeneration) { "store.activate: generation conflict" }
            check(generation < Long.MAX_VALUE) { "store.activate: activation generation exhausted" }
            val snapshot = readVersionSnapshot(versionId)
            val input = ContentPackageArchive.read(snapshot)
            val callbackManifestJson = ContentPackageCodec.utf8(input.manifestBytes)
            val verifier = professionalVerifier?.let { external ->
                ContentProfessionalReviewVerifier { request ->
                    external.isVerified(request.copy(
                        subject = deepObject(request.subject),
                        manifest = Json.parseToJsonElement(callbackManifestJson).jsonObject,
                        review = deepObject(request.review),
                        recordBytes = request.recordBytes.copyOf(),
                        chapterBindings = Collections.unmodifiableList(request.chapterBindings.map { it.copy() }),
                    ))
                }
            }
            val report = validate(input, ContentValidationMode.PRODUCTION, verifier)
            require(report.productionValid) { "store.activate: production validation failed: ${report.issues}" }
            val next = ContentPackageActiveVersion(versionId, generation + 1)
            val pointerBytes = "{\"generation\":${next.generation},\"versionId\":\"${next.versionId}\"}\n".encodeToByteArray()
            val temporary = forcedTemporary(rootPath, ".active-", pointerBytes)
            try {
                // Callbacks cannot make stale gate/CAS/hash results publish different persisted bytes.
                check(readCurrent()?.activeVersion == current) { "store.activate: generation changed during validation" }
                readVersionSnapshot(versionId)
                checkLinks(pointerPath())
                pointerMove(temporary, pointerPath()) // Linearization point. No cancellation checks after this succeeds.
                next
            } finally {
                discardTemporary(temporary) // Best effort; never turn a committed publication into a reported failure.
            }
        }
    }

    fun active(): ContentPackageActiveVersion? = locked { readCurrent()?.activeVersion }

    /** Generation, version hash and private bytes are pinned together under the same root lock. */
    fun pinActive(): PinnedContentPackage? = locked { readCurrent() }

    fun readVersion(versionId: String): ContentPackageArchiveInput {
        requireVersionId(versionId)
        return locked {
            readCurrent()
            defensiveInput(ContentPackageArchive.read(readVersionSnapshot(versionId)))
        }
    }

    private fun readCurrent(): PinnedContentPackage? {
        val pointer = pointerPath()
        checkLinks(pointer)
        if (!Api24PrivateFiles.exists(pointer)) return null
        val bytes = readBounded(pointer, MAX_POINTER, 1)
        val text = try { ContentPackageCodec.utf8(bytes) } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("store.active: pointer is not UTF-8", error)
        }
        val match = requireNotNull(POINTER.matchEntire(text)) { "store.active: malformed pointer" }
        val generation = requireNotNull(match.groupValues[1].toLongOrNull()) { "store.active: generation overflow" }
        require(generation > 0) { "store.active: invalid generation" }
        val id = match.groupValues[2]
        val input = ContentPackageArchive.read(readVersionSnapshot(id))
        return PinnedContentPackage(ContentPackageActiveVersion(id, generation), defensiveInput(input))
    }

    private fun readVersionSnapshot(id: String): ByteArray {
        val snapshot = readBounded(versionPath(id), MAX_ZIP, 22)
        require(ContentPackageCodec.sha256(snapshot) == id) { "store.version: ZIP sha256 mismatch" }
        return snapshot
    }

    private fun validate(
        input: ContentPackageArchiveInput,
        mode: ContentValidationMode,
        verifier: ContentProfessionalReviewVerifier? = null,
    ): ContentPackageValidation = ContentPackageValidator().validate(
        input.manifestBytes.copyOf(),
        input.resources.mapValues { (_, bytes) -> bytes.copyOf() },
        mode,
        professionalReviewVerifier = verifier,
    )

    private fun deepObject(value: JsonObject): JsonObject =
        Json.parseToJsonElement(ContentPackageCodec.canonicalJson(value)).jsonObject

    private fun defensiveInput(input: ContentPackageArchiveInput): ContentPackageArchiveInput = ContentPackageArchiveInput(
        input.manifestBytes.copyOf(),
        Collections.unmodifiableMap(input.resources.mapValues { (_, bytes) -> bytes.copyOf() }),
    )

    private fun readBounded(path: File, maximum: Long, minimum: Long): ByteArray {
        checkLinks(path)
        require(Api24PrivateFiles.regular(path)) { "store.input: missing/non-regular file" }
        require(path.length() in minimum..maximum) { "store.input: file size outside bounds" }
        val output = ByteArrayOutputStream()
        Api24PrivateFiles.input(path).use { input ->
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                require(total <= maximum) { "store.input: size limit exceeded while reading" }
                output.write(buffer, 0, count)
            }
            require(total >= minimum) { "store.input: truncated file" }
        }
        return output.toByteArray()
    }

    private fun forcedTemporary(directory: File, prefix: String, bytes: ByteArray): File {
        checkLinks(directory)
        val temporary = File.createTempFile(prefix, ".tmp", directory)
        try {
            checkLinks(temporary)
            Api24PrivateFiles.writeForced(temporary, bytes)
            return temporary
        } catch (error: Exception) {
            discardTemporary(temporary)
            throw error
        }
    }

    private fun discardTemporary(path: File) {
        // Delete one private generated name only. Historical ZIPs are never garbage-collected here.
        if (Api24PrivateFiles.within(rootPath, path)) runCatching { path.delete() }
    }

    private fun <T> locked(action: () -> T): T = synchronized(monitor) {
        prepareDirectory(rootPath)
        prepareDirectory(versionsPath())
        val lockPath = childPath(".store.lock")
        checkLinks(lockPath)
        require(!Api24PrivateFiles.exists(lockPath) || Api24PrivateFiles.regular(lockPath)) { "store: invalid lock file" }
        val handle = Api24PrivateFiles.openLock(lockPath)
        var lock: FileLock? = null
        try {
            lock = handle.channel.lock()
            checkLinks(rootPath)
            checkLinks(versionsPath())
            checkLinks(lockPath)
            action()
        } finally {
            // Releasing resources must not mask an original failure or invalidate a committed result.
            runCatching { lock?.release() }
            runCatching { handle.close() }
        }
    }

    private fun prepareDirectory(path: File) = Api24PrivateFiles.prepareDirectory(path)

    private fun childPath(name: String): File = Api24PrivateFiles.normalized(File(rootPath, name)).also {
        require(Api24PrivateFiles.within(rootPath, it) && it != rootPath) { "store: path escapes private root" }
    }
    private fun versionsPath(): File = childPath("versions")
    private fun pointerPath(): File = childPath("active.json")
    private fun versionPath(id: String): File {
        requireVersionId(id)
        return childPath("versions/$id.zip")
    }
    private fun requireVersionId(id: String) { require(VERSION_ID.matches(id)) { "store: invalid version id" } }

    /** Existing links/reparse paths are refused; an app-private root is still the trust boundary. */
    private fun checkLinks(path: File) = Api24PrivateFiles.checkLinks(path)

    companion object {
        private const val MAX_ZIP = 256L * 1024 * 1024
        private const val MAX_POINTER = 256L
        private val VERSION_ID = Regex("[0-9a-f]{64}")
        private val POINTER = Regex("\\{\"generation\":([1-9][0-9]*),\"versionId\":\"([0-9a-f]{64})\"\\}\\n")
        private val rootLocks = ConcurrentHashMap<String, Any>()
    }
}
