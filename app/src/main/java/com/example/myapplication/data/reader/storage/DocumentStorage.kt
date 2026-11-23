package com.example.myapplication.data.reader.storage

import com.example.myapplication.domain.reader.DocumentRef
import java.io.File
import com.example.myapplication.domain.reader.SourceReadBudget
import com.example.myapplication.domain.reader.SourceReadLimitExceeded
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

open class DocumentStorageException(message: String, cause: Throwable? = null) : IOException(message, cause)

class SourceTooLargeException(val maxBytes: Long) :
    DocumentStorageException("source exceeds the $maxBytes byte limit")

class InvalidStoragePathException(message: String) : DocumentStorageException(message)

class ManifestVerificationException(message: String, cause: Throwable? = null) :
    DocumentStorageException(message, cause)

class RevisionConflictException(message: String) : DocumentStorageException(message)

data class StagedSource(
    val jobId: String,
    val file: File,
    val sha256: String,
    val bytes: Long,
)

data class StoredManifest(
    val manifest: ContentManifest,
    val manifestFile: File,
    val manifestSha256: String,
)

data class PublishedRevision(
    val documentId: String,
    val revision: String,
    val directory: File,
    val manifest: ContentManifest,
    val manifestSha256: String,
)

/**
 * File-system side of import publication.
 *
 * The storage layer owns staging, hashing and atomic directory publication. It
 * deliberately does not decide whether a user may read a document; that belongs
 * to the repository/DocumentAccess layer. All paths are derived from hashes so
 * that document ids containing characters such as ':' are safe on Windows and
 * Android, and every manifest path is resolved inside its revision directory.
 */
class DocumentStorage(
    rootDirectory: File,
    private val maxSourceBytes: Long = DEFAULT_MAX_SOURCE_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val root: File = rootDirectory.canonicalFile
    private val stagingRoot: File = File(root, "staging")
    private val documentsRoot: File = File(root, "documents")

    init {
        require(maxSourceBytes > 0) { "maxSourceBytes must be positive" }
        ensureDirectory(root)
        ensureDirectory(stagingRoot)
        ensureDirectory(documentsRoot)
    }

    fun stageSource(jobId: String, input: InputStream): StagedSource {
        validateText(jobId, "jobId")
        val jobDirectory = ensureDirectory(stagingJobDirectory(jobId))
        val temporary = File(jobDirectory, "source.tmp-${UUID.randomUUID()}")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    if (total > maxSourceBytes) {
                        throw SourceTooLargeException(maxSourceBytes)
                    }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
            val sha256 = digest.digest().toHex()
            val finalFile = File(jobDirectory, "source-$sha256.bin")
            if (finalFile.exists()) {
                if (finalFile.length() != total) {
                    throw DocumentStorageException("existing staged source has an unexpected size")
                }
                deleteFile(temporary)
            } else {
                jobDirectory.listFiles()
                    ?.filter { it.isFile && it.name.startsWith("source-") && it.name.endsWith(".bin") }
                    ?.forEach(::deleteFile)
                if (!temporary.renameTo(finalFile)) {
                    throw DocumentStorageException("could not move staged source into place")
                }
            }
            return StagedSource(jobId = jobId, file = finalFile, sha256 = sha256, bytes = total)
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    fun stagingJobDirectory(jobId: String): File {
        validateText(jobId, "jobId")
        return File(stagingRoot, sha256Hex(jobId))
    }

    fun stagingRevisionDirectory(jobId: String, revision: String): File {
        validateRevision(revision)
        return File(File(stagingJobDirectory(jobId), "revisions"), revision)
    }

    /**
     * Verifies every declared file against the manifest and writes
     * manifest.json atomically inside the staging revision directory.
     */
    fun writeManifest(revisionDirectory: File, manifest: ContentManifest): StoredManifest {
        ensureInside(stagingRoot, revisionDirectory, "staging revision directory")
        ensureDirectory(revisionDirectory)
        verifyManifestFiles(revisionDirectory, manifest)
        val encoded = ContentManifestCodec.encode(manifest)
        val manifestFile = File(revisionDirectory, MANIFEST_FILE_NAME)
        atomicWriteText(manifestFile, encoded)
        return StoredManifest(
            manifest = manifest,
            manifestFile = manifestFile,
            manifestSha256 = sha256Hex(encoded),
        )
    }

    /**
     * Atomically publishes a verified staging revision. A target that already
     * exists is accepted only when its manifest is byte-identical; it is never
     * overwritten in place.
     */
    fun publishRevision(
        revisionDirectory: File,
        manifest: ContentManifest,
    ): PublishedRevision {
        validateRevision(manifest.contentRevision)
        validateText(manifest.documentId, "documentId")
        DocumentRef(manifest.documentId)
        ensureInside(stagingRoot, revisionDirectory, "staging revision directory")
        val stagedManifest = readManifest(revisionDirectory)
        if (stagedManifest.manifest.documentId != manifest.documentId ||
            stagedManifest.manifest.contentRevision != manifest.contentRevision ||
            stagedManifest.manifest.sourceSha256 != manifest.sourceSha256
        ) {
            throw RevisionConflictException("staged manifest identity does not match the publish request")
        }
        verifyManifestFiles(revisionDirectory, stagedManifest.manifest)

        val target = revisionDirectory(manifest.documentId, manifest.contentRevision)
        if (target.exists()) {
            val existing = openRevision(manifest.documentId, manifest.contentRevision, verifyFiles = true)
                ?: throw RevisionConflictException("published revision is not readable")
            if (existing.manifestSha256 == stagedManifest.manifestSha256) {
                deleteRecursively(revisionDirectory, stagingRoot)
                return existing
            }
            throw RevisionConflictException("revision already exists with different content")
        }

        ensureDirectory(requireNotNull(target.parentFile))
        if (!revisionDirectory.renameTo(target)) {
            throw DocumentStorageException("could not atomically publish revision")
        }
        val publishedManifest = readManifest(target)
        return PublishedRevision(
            documentId = manifest.documentId,
            revision = manifest.contentRevision,
            directory = target,
            manifest = publishedManifest.manifest,
            manifestSha256 = publishedManifest.manifestSha256,
        )
    }

    fun openRevision(
        documentId: String,
        revision: String,
        verifyFiles: Boolean = false,
        sourceBudget: SourceReadBudget? = null,
    ): PublishedRevision? {
        validateText(documentId, "documentId")
        validateRevision(revision)
        val directory = revisionDirectory(documentId, revision)
        if (!directory.isDirectory) return null
        val stored = try {
            readManifest(directory, sourceBudget)
        } catch (limit: SourceReadLimitExceeded) {
            throw limit
        } catch (error: IOException) {
            throw ManifestVerificationException("published manifest is unreadable", error)
        }
        if (stored.manifest.documentId != documentId || stored.manifest.contentRevision != revision) {
            throw ManifestVerificationException("published manifest identity does not match its directory")
        }
        if (verifyFiles) verifyManifestFiles(directory, stored.manifest, sourceBudget)
        return PublishedRevision(
            documentId = documentId,
            revision = revision,
            directory = directory,
            manifest = stored.manifest,
            manifestSha256 = stored.manifestSha256,
        )
    }

    fun resolveFile(documentId: String, revision: String, relativePath: String): File {
        val directory = revisionDirectory(documentId, revision)
        if (!directory.isDirectory) throw DocumentStorageException("revision is not published")
        return resolveInside(directory, relativePath)
    }

    fun revisionDirectory(documentId: String, revision: String): File {
        validateText(documentId, "documentId")
        validateRevision(revision)
        return File(
            File(File(documentsRoot, sha256Hex(documentId)), "revisions"),
            revision,
        )
    }

    fun sha256Of(file: File): String {
        if (!file.isFile) throw DocumentStorageException("hash target is not a file")
        return sha256Hex(file)
    }
    fun deleteStagingJob(jobId: String): Boolean {
        val directory = stagingJobDirectory(jobId)
        if (!directory.exists()) return false
        deleteRecursively(directory, stagingRoot)
        return true
    }

    fun deleteRevision(documentId: String, revision: String): Boolean {
        val directory = revisionDirectory(documentId, revision)
        if (!directory.exists()) return false
        deleteRecursively(directory, documentsRoot)
        return true
    }

    private fun readManifest(directory: File, sourceBudget: SourceReadBudget? = null): StoredManifest {
        val manifestFile = File(directory, MANIFEST_FILE_NAME)
        if (!manifestFile.isFile) throw DocumentStorageException("manifest.json is missing")
        val bytes = sourceBudget?.readFile(manifestFile) ?: manifestFile.readBytes()
        val text = String(bytes, StandardCharsets.UTF_8)
        val manifest = ContentManifestCodec.decode(text)
        return StoredManifest(
            manifest = manifest,
            manifestFile = manifestFile,
            manifestSha256 = sha256Hex(bytes),
        )
    }

    private fun verifyManifestFiles(directory: File, manifest: ContentManifest, sourceBudget: SourceReadBudget? = null) {
        val validation = ContentManifestValidator.validate(manifest)
        if (!validation.isValid) {
            throw ManifestVerificationException(
                "manifest is invalid: ${validation.errors.joinToString("; ")}",
            )
        }
        manifest.files.forEach { file ->
            sourceBudget?.checkpoint()
            val actual = resolveInside(directory, file.relativePath)
            if (!actual.isFile) {
                throw ManifestVerificationException("declared file is missing: ${file.relativePath}")
            }
            if (actual.length() != file.bytes) {
                throw ManifestVerificationException("declared size does not match: ${file.relativePath}")
            }
            if (sha256Hex(actual, sourceBudget) != file.sha256) {
                throw ManifestVerificationException("declared hash does not match: ${file.relativePath}")
            }
        }
    }

    private fun resolveInside(rootDirectory: File, relativePath: String): File {
        val errors = mutableListOf<String>()
        ContentManifestValidator.validateRelativePath(relativePath, "relativePath", errors)
        if (errors.isNotEmpty()) throw InvalidStoragePathException(errors.joinToString("; "))
        val rootCanonical = rootDirectory.canonicalFile
        val candidate = File(rootCanonical, relativePath.replace('/', File.separatorChar)).canonicalFile
        val prefix = rootCanonical.path + File.separator
        if (!candidate.path.startsWith(prefix)) {
            throw InvalidStoragePathException("path escapes its storage root")
        }
        return candidate
    }

    private fun ensureInside(rootDirectory: File, child: File, field: String) {
        val rootCanonical = rootDirectory.canonicalFile
        val childCanonical = child.canonicalFile
        val prefix = rootCanonical.path + File.separator
        if (!childCanonical.path.startsWith(prefix)) {
            throw InvalidStoragePathException("$field is outside its storage root")
        }
    }

    private fun atomicWriteText(target: File, text: String) {
        ensureDirectory(requireNotNull(target.parentFile))
        val parent = requireNotNull(target.parentFile)
        val temporary = File(parent, "${target.name}.tmp-${UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(text.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            replaceFile(temporary, target)
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun replaceFile(source: File, target: File) {
        if (!target.exists()) {
            if (!source.renameTo(target)) throw DocumentStorageException("could not install ${target.name}")
            return
        }
        val backup = File(requireNotNull(target.parentFile), "${target.name}.bak-${UUID.randomUUID()}")
        if (!target.renameTo(backup)) {
            throw DocumentStorageException("could not prepare replacement of ${target.name}")
        }
        if (source.renameTo(target)) {
            backup.delete()
            return
        }
        backup.renameTo(target)
        throw DocumentStorageException("could not replace ${target.name}")
    }

    private fun deleteRecursively(target: File, allowedRoot: File) {
        ensureInside(allowedRoot, target, "delete target")
        if (target.isDirectory) {
            target.listFiles()?.forEach { child -> deleteRecursively(child, allowedRoot) }
        }
        deleteFile(target)
    }

    private fun deleteFile(file: File) {
        if (file.exists() && !file.delete()) {
            throw DocumentStorageException("could not delete ${file.name}")
        }
    }

    private fun ensureDirectory(directory: File): File {
        if (!directory.exists() && !directory.mkdirs()) {
            throw DocumentStorageException("could not create storage directory")
        }
        if (!directory.isDirectory) throw DocumentStorageException("storage path is not a directory")
        return directory
    }

    private fun validateText(value: String, field: String) {
        if (value.isBlank() || value.length > MAX_ID_LENGTH || value.any(Char::isISOControl)) {
            throw InvalidStoragePathException("$field is invalid")
        }
    }

    private fun validateRevision(revision: String) {
        if (!REVISION_PATTERN.matches(revision)) {
            throw InvalidStoragePathException("revision must be a lowercase SHA-256")
        }
    }

    private fun sha256Hex(value: String): String =
        sha256Hex(value.toByteArray(StandardCharsets.UTF_8))

    private fun sha256Hex(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).toHex()

    private fun sha256Hex(file: File, sourceBudget: SourceReadBudget? = null): String {
        if (sourceBudget != null) return sourceBudget.hashFile(file)
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        const val DEFAULT_MAX_SOURCE_BYTES: Long = 512L * 1024 * 1024
        const val MANIFEST_FILE_NAME = "manifest.json"
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val MAX_ID_LENGTH = 512
        private val REVISION_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}
