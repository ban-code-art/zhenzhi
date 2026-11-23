package com.example.myapplication.data.reader.export

import com.example.myapplication.data.local.entity.*
import com.example.myapplication.data.reader.storage.*
import com.example.myapplication.data.reader.content.Api24PrivateFiles
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.json.*

/** Only this factory creates source proofs; no digest-only implementation can authorize publication. */
class BackupV2SourceProof internal constructor(
    override val capture: BackupV2RevisionCapture,
    internal val manifest: ContentManifest,
    val proofPath: String,
    val originalManifestPath: String,
    val sourcePackagePath: String,
    internal val resourcePaths: Map<String, String>,
    proofBytes: ByteArray,
    originalManifestBytes: ByteArray,
    files: Map<String, ByteArray>,
) : BackupV2RevisionProof {
    override val exportDocumentId get() = capture.document.id
    override val revision get() = capture.revision
    private val proofSnapshot = proofBytes.copyOf()
    private val manifestSnapshot = originalManifestBytes.copyOf()
    private val fileSnapshots = v2Map(files.mapValues { it.value.copyOf() })
    override val proofDigest = fileDigest(proofSnapshot)
    override val resourceDigest = revisionResourceDigest(manifest)
    val proofBytes get() = proofSnapshot.copyOf()
    val originalManifestBytes get() = manifestSnapshot.copyOf()
    internal val paths get() = fileSnapshots.keys
    internal fun fileBytes(path: String) = fileSnapshots.getValue(path).copyOf()
    internal fun fileSize(path: String) = fileSnapshots.getValue(path).size.toLong()
    internal fun fileHash(path: String) = fileDigest(fileSnapshots.getValue(path))
}

class BackupV2PreparedRevision internal constructor(
    internal val proof: BackupV2SourceProof,
    internal val manifest: ContentManifest,
    internal val sections: List<ReaderSectionEntity>,
    internal val outline: List<ReaderOutlineNodeEntity>,
    val jobId: String,
    val planHash: String,
    val contentHash: String,
) {
    val documentId get() = manifest.documentId
    val revision get() = manifest.contentRevision
    val manifestSha256 get() = fileDigest(ContentManifestCodec.encode(manifest).toByteArray(Charsets.UTF_8))
}

class BackupV2RevisionSnapshot(private val storage: DocumentStorage,
    private val beforePublicationManifestRead: (File) -> Unit = {},
    private val captureByteLimit: Long = BackupV2Limits.TOTAL_BYTES,
    private val captureFileLimit: Int = BackupV2Limits.FILES,
    private val beforeCaptureResourceRead: (File) -> Unit = {}) {
    fun verifyProof(proof: BackupV2RevisionProof) {
        val p = proof as? BackupV2SourceProof ?: throw BackupV2Exception("UNVERIFIED_SOURCE_PROOF", "strict file proof required")
        val manifest = strictManifest(p.originalManifestBytes)
        validateCapture(p.capture, manifest)
        v2require(p.manifest == manifest && p.proofDigest == fileDigest(p.proofBytes) && p.resourceDigest == revisionResourceDigest(manifest), "SOURCE_METADATA_MISMATCH", "proof binding differs")
        manifest.files.forEach { f -> verifyBytes(p.fileBytes(p.resourcePaths.getValue(f.relativePath)), f.sha256, f.bytes, f.relativePath) }
        v2require(encodeFileJson(proofJson(p.capture, manifest, p.originalManifestPath, p.originalManifestBytes, p.resourcePaths)).contentEquals(p.proofBytes),
            "SOURCE_METADATA_MISMATCH", "source proof schema differs")
    }
    fun capture(capture: BackupV2RevisionCapture, requestedRevision: String): BackupV2SourceProof {
        v2require(capture.revision == requestedRevision && capture.revisionEntity.status == "READY", "SOURCE_NOT_READY", "exact READY revision required")
        return captureAll(listOf(capture)).single()
    }

    /** Metadata for the whole frozen set is budgeted before any resource array is read. */
    fun captureAll(captures: List<BackupV2RevisionCapture>): List<BackupV2SourceProof> {
        v2require(captures.size <= BackupV2Limits.FILES && captures.map { it.document.id to it.revision }.toSet().size == captures.size,
            "SOURCE_METADATA_MISMATCH", "unique fixed revisions required")
        val budget = BackupV2FileBudget(captureByteLimit, captureFileLimit)
        budget.reserve(0, 2) // The final archive also needs records.json and manifest.json.
        val inputs = captures.map { capture -> preflightCapture(capture, budget) }
        val reads = BackupV2FileBudget(captureByteLimit, captureFileLimit)
        reads.reserve(0, 2)
        return v2List(inputs.map { input -> readCapture(input, reads) })
    }

    private data class CaptureInput(val capture: BackupV2RevisionCapture, val manifest: ContentManifest, val raw: ByteArray,
        val portable: BackupV2RevisionCapture, val proofPath: String, val rawPath: String,
        val resourcePaths: Map<String, String>, val proofBytes: ByteArray)

    private fun preflightCapture(capture: BackupV2RevisionCapture, budget: BackupV2FileBudget): CaptureInput {
        val requestedRevision = capture.revision
        v2require(capture.revisionEntity.status == "READY", "SOURCE_NOT_READY", "exact READY revision required")
        val directory = storage.revisionDirectory(capture.document.id, requestedRevision)
        val manifestFile = File(directory, "manifest.json")
        v2require(manifestFile.isFile, "SOURCE_RESOURCE_MISSING", "fixed manifest missing")
        v2require(File(capture.revisionEntity.manifestPath).canonicalFile == manifestFile.canonicalFile, "SOURCE_METADATA_MISMATCH", "manifest SQL path differs")
        val rawSize = manifestFile.length()
        v2require(rawSize in 0..BackupV2Limits.MANIFEST_BYTES.toLong(), "LIMIT_EXCEEDED", "fixed manifest byte limit")
        budget.reserve(rawSize, 1)
        val raw = readFile(manifestFile, rawSize)
        val manifest = strictManifest(raw)
        manifest.files.forEach { budget.reserve(it.bytes, 1) }
        validateCapture(capture, manifest)
        val source = source(manifest)
        v2require(capture.revisionEntity.sourceBlobPath != null && File(capture.revisionEntity.sourceBlobPath).canonicalFile ==
            storage.resolveFile(capture.document.id, requestedRevision, source.relativePath).canonicalFile,
            "SOURCE_METADATA_MISMATCH", "source SQL path differs")
        val base = "snapshots/${v2Digest(capture.document.id)}/$requestedRevision"
        val proofPath = "$base/proof.json"
        val rawPath = "$base/original-manifest.json"
        val resourcePaths = manifest.files.sortedBy { it.relativePath }.mapIndexed { index, f -> f.relativePath to "$base/resource-$index.bin" }.toMap()
        val portable = portableCapture(capture, resourcePaths.getValue(source.relativePath), rawPath)
        val proofBytes = encodeFileJson(proofJson(portable, manifest, rawPath, raw, resourcePaths))
        budget.reserve(proofBytes.size.toLong(), 1)
        return CaptureInput(capture, manifest, raw, portable, proofPath, rawPath, v2Map(resourcePaths), proofBytes)
    }

    private fun readCapture(input: CaptureInput, budget: BackupV2FileBudget): BackupV2SourceProof {
        val capture = input.capture; val requestedRevision = capture.revision; val manifest = input.manifest
        val published = storage.openRevision(capture.document.id, requestedRevision, verifyFiles = true)
            ?: throw BackupV2Exception("SOURCE_RESOURCE_MISSING", "fixed revision missing")
        v2require(published.manifestSha256 == fileDigest(input.raw), "SOURCE_METADATA_MISMATCH", "manifest changed during capture")
        budget.reserve(input.raw.size.toLong(), 1); budget.reserve(input.proofBytes.size.toLong(), 1)
        val bytes = linkedMapOf<String, ByteArray>()
        manifest.files.forEach { f ->
            checkRelativePath(f.relativePath)
            budget.reserve(f.bytes, 1)
            val file = storage.resolveFile(capture.document.id, requestedRevision, f.relativePath)
            beforeCaptureResourceRead(file)
            val data = readFile(file, f.bytes)
            verifyBytes(data, f.sha256, f.bytes, f.relativePath)
            bytes[input.resourcePaths.getValue(f.relativePath)] = data
        }
        bytes[input.rawPath] = input.raw; bytes[input.proofPath] = input.proofBytes
        // Check the original immutable revision again after the last resource read.
        v2require(storage.openRevision(capture.document.id, requestedRevision, true)?.manifestSha256 == published.manifestSha256,
            "SOURCE_METADATA_MISMATCH", "revision changed during capture")
        return BackupV2SourceProof(input.portable, manifest, input.proofPath, input.rawPath,
            input.resourcePaths.getValue(source(manifest).relativePath), input.resourcePaths, input.proofBytes, input.raw, bytes)
    }

    fun fromArchive(archive: BackupV2ArchiveSnapshot): List<BackupV2SourceProof> {
        rejectInk(archive.backup)
        if (!archive.backup.manifest.options.includeSources) return emptyList()
        val results = archive.backup.manifest.documents.flatMap { doc ->
            val paths = doc.snapshotPaths.filter { it.matches(Regex("snapshots/[0-9a-f]{64}/[0-9a-f]{64}/proof\\.json")) }
            v2require(paths.isNotEmpty(), "SOURCE_STRUCTURE_INCOMPLETE", "historical source-only backup is not a fixed revision proof")
            val proofs = paths.map { path -> decodeProof(archive, doc, path) }
            v2require(proofs.map { it.revision }.toSet().size == proofs.size, "SOURCE_METADATA_MISMATCH", "duplicate fixed revisions")
            val head = proofs.singleOrNull { it.revision == doc.contentRevision }
                ?: throw BackupV2Exception("SOURCE_STRUCTURE_INCOMPLETE", "head revision proof missing")
            v2require(head.sourcePackagePath == doc.sourcePath && head.manifest.sourceSha256 == doc.sourceSha256,
                "SOURCE_METADATA_MISMATCH", "wire source does not bind fixed proof")
            v2require(doc.sectionIds.toSet() == proofs.flatMap { it.capture.sections }.map { it.id }.toSet(), "SOURCE_METADATA_MISMATCH", "wire section identities differ")
            val covered = proofs.flatMap { it.paths }.toSet() - head.sourcePackagePath
            v2require(covered == doc.snapshotPaths.toSet(), "SOURCE_STRUCTURE_INCOMPLETE", "unbound or missing fixed resources")
            proofs
        }
        v2require(results.map { it.proofPath }.toSet().size == results.size, "SOURCE_METADATA_MISMATCH", "proof shared across documents")
        return v2List(results)
    }

    fun bind(proof: BackupV2RevisionProof, targetDocumentId: String, idMap: BackupV2IdMap, outlineIds: Map<String, String>,
        jobId: String, planHash: String, contentHash: String): BackupV2PreparedRevision {
        val verified = proof as? BackupV2SourceProof ?: throw BackupV2Exception("UNVERIFIED_SOURCE_PROOF", "proof was not produced by file factory")
        v2require(idMap.documents[proof.exportDocumentId] == targetDocumentId, "INVALID_ID_MAP", "planned document differs")
        checkHash(planHash); checkHash(contentHash)
        val sections = verified.capture.sections.map { row -> row.copy(id = idMap.section(row.id), documentId = targetDocumentId,
            parentId = row.parentId?.let(idMap::section)) }
        val outline = verified.capture.outline.map { row ->
            fun outlineId(id: String) = outlineIds[id] ?: throw BackupV2Exception("MISSING_ID_MAPPING", "outline:$id")
            row.copy(id = outlineId(row.id), documentId = targetDocumentId, nodeKey = outlineId(row.id),
                parentId = row.parentId?.let(::outlineId), targetSectionId = row.targetSectionId?.let(idMap::section),
                targetLocatorJson = row.targetLocatorJson?.let { text ->
                    BackupV2JsonProjection.remapColumn(BackupV2Table.READER_STATES, "locator_json", text, idMap)
                })
        }
        val manifest = verified.manifest.copy(jobId = jobId, documentId = targetDocumentId,
            sections = sections.map { ManifestSection(it.id, it.orderIndex, it.title, it.resourceHref, it.charCount, it.pageStart, it.pageEnd) },
            outline = outline.map { ManifestOutlineNode(it.nodeKey, it.parentId, it.orderIndex, it.title, it.targetSectionId, it.targetLocatorJson) })
        v2require(ContentManifestValidator.validate(manifest).isValid, "SOURCE_METADATA_MISMATCH", "mapped manifest invalid")
        return BackupV2PreparedRevision(verified, manifest, v2List(sections), v2List(outline), jobId, planHash, contentHash)
    }

    fun prepare(prepared: BackupV2PreparedRevision) {
        val directory = storage.stagingRevisionDirectory(prepared.jobId, prepared.revision)
        ordinaryFile(directory, false)
        v2require(directory.mkdirs() || directory.isDirectory, "IO_FAILURE", "staging creation failed")
        prepared.manifest.files.forEach { descriptor ->
            val target = File(directory, descriptor.relativePath)
            ownedPath(directory, target)
            v2require(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory, "IO_FAILURE", "resource parent creation failed")
            val data = prepared.proof.fileBytes(prepared.proof.resourcePaths.getValue(descriptor.relativePath))
            if (target.exists()) verifyBytes(readFile(target, BackupV2Limits.FILE_BYTES.toLong()), descriptor.sha256, descriptor.bytes, descriptor.relativePath)
            else atomicFileWrite(target, data, replace = false)
        }
        storage.writeManifest(directory, prepared.manifest)
        val root = storageRoot(directory)
        syncPrivateDirectories(root, prepared.manifest.files.map { File(directory, it.relativePath).parentFile!! } + directory)
        syncPrivateDirectory(root.parentFile!!, root.parentFile!!)
    }

    fun publish(prepared: BackupV2PreparedRevision): BackupV2Publication {
        val staged = storage.stagingRevisionDirectory(prepared.jobId, prepared.revision)
        val root = storageRoot(staged)
        val published = storage.publishRevision(staged, prepared.manifest)
        syncPrivateDirectories(root, prepared.manifest.files.map { File(published.directory, it.relativePath).parentFile!! } +
            published.directory + staged.parentFile!!)
        val source = source(published.manifest)
        val publication = BackupV2Publication(prepared.planHash, prepared.contentHash, prepared.proof.exportDocumentId, prepared.documentId,
            prepared.revision, prepared.proof.proofDigest, File(published.directory, "manifest.json").absolutePath,
            File(published.directory, source.relativePath).absolutePath, published.manifest.sourceSha256, published.manifestSha256,
            published.manifest.extractorVersion, prepared.proof.capture.revisionEntity.createdAt, prepared.sections, prepared.outline, prepared.proof.resourceDigest)
        verifyPublication(publication)
        return publication
    }

    fun verifyPublication(publication: BackupV2Publication) {
        checkHash(publication.planHash); checkHash(publication.contentHash); checkHash(publication.proofDigest)
        val actual = storage.openRevision(publication.targetDocumentId, publication.revision, true)
            ?: throw BackupV2Exception("SOURCE_RESOURCE_MISSING", "published revision absent")
        val rawFile = File(actual.directory, "manifest.json")
        beforePublicationManifestRead(rawFile)
        val rawManifest = readFile(rawFile, BackupV2Limits.MANIFEST_BYTES.toLong())
        val manifest = strictManifest(rawManifest)
        v2require(actual.manifestSha256 == publication.manifestSha256 && fileDigest(rawManifest) == publication.manifestSha256 && manifest.sourceSha256 == publication.sourceSha256 &&
            manifest.extractorVersion == publication.extractorVersion && rawFile.canonicalFile == File(publication.manifestPath).canonicalFile &&
            revisionResourceDigest(manifest) == publication.resourceDigest,
            "SOURCE_METADATA_MISMATCH", "published binding differs")
        val src = source(manifest)
        v2require(File(publication.sourceBlobPath).canonicalFile == storage.resolveFile(publication.targetDocumentId, publication.revision, src.relativePath).canonicalFile,
            "SOURCE_METADATA_MISMATCH", "published source path differs")
        val document = ReaderDocumentEntity(publication.targetDocumentId, null, "IMPORTED", format = "UNKNOWN", title = "Restored",
            contentRevision = publication.revision, extractorVersion = publication.extractorVersion, importStatus = "READY", createdAt = publication.createdAt, updatedAt = publication.createdAt)
        val revision = ReaderContentRevisionEntity(publication.targetDocumentId, publication.revision, publication.sourceSha256,
            publication.sourceBlobPath, publication.manifestPath, publication.extractorVersion, "READY", publication.createdAt)
        validateCapture(BackupV2RevisionCapture(document, revision, publication.sections, publication.outline), manifest)
    }

    internal fun stagingPath(prepared: BackupV2PreparedRevision) = storage.stagingJobDirectory(prepared.jobId).absolutePath
    internal fun alreadyPublished(prepared: BackupV2PreparedRevision): Boolean = storage.openRevision(prepared.documentId, prepared.revision, true)?.let {
        v2require(it.manifestSha256 == prepared.manifestSha256, "SOURCE_METADATA_MISMATCH", "published revision conflicts with frozen preparation")
        true
    } ?: false
    internal fun removeOwnedStaging(jobId: String, registeredPath: String) {
        val expected = storage.stagingJobDirectory(jobId)
        v2require(expected.absoluteFile == File(registeredPath).absoluteFile, "INVALID_JOURNAL", "staging ownership differs")
        ordinaryFile(expected, false)
        storage.deleteStagingJob(jobId)
        syncPrivateDirectory(expected.parentFile!!.parentFile!!, expected.parentFile!!)
    }

    private fun decodeProof(archive: BackupV2ArchiveSnapshot, doc: BackupV2Document, path: String): BackupV2SourceProof {
        val rawProof = archive.fileBytes(path)
        val obj = fileJson(rawProof, BackupV2Limits.MANIFEST_BYTES)
        val root = BackupV2Json.obj(obj, setOf("schemaVersion", "exportDocumentId", "revision", "format", "kind", "title", "author", "extractorVersion", "createdAt", "manifest", "files", "sections", "outline"))
        v2require(BackupV2Json.integer(root["schemaVersion"]) == 1L, "UNSUPPORTED_SOURCE_PROOF_VERSION", "source proof version")
        val documentId = BackupV2Json.string(root["exportDocumentId"]); val revision = BackupV2Json.string(root["revision"])
        v2require(documentId == doc.exportDocumentId && path == "snapshots/${v2Digest(documentId)}/$revision/proof.json", "SOURCE_METADATA_MISMATCH", "proof namespace")
        checkHash(revision)
        val manifestDescriptor = BackupV2Json.obj(root.getValue("manifest"), setOf("path", "sha256", "bytes"))
        val rawPath = BackupV2Json.string(manifestDescriptor["path"])
        v2require(rawPath == path.substringBeforeLast('/') + "/original-manifest.json", "INVALID_PATH", "raw manifest namespace")
        val raw = archive.fileBytes(rawPath)
        verifyBytes(raw, BackupV2Json.string(manifestDescriptor["sha256"]), BackupV2Json.integer(manifestDescriptor["bytes"]), rawPath)
        val manifest = strictManifest(raw)
        val descriptors = BackupV2Json.array(root["files"])
        v2require(descriptors.size == manifest.files.size && descriptors.size <= BackupV2Limits.FILES, "SOURCE_METADATA_MISMATCH", "resource descriptors")
        val resources = linkedMapOf<String, String>(); val bytes = linkedMapOf<String, ByteArray>()
        val sorted = manifest.files.sortedBy { it.relativePath }
        descriptors.forEachIndexed { index, value ->
            val f = BackupV2Json.obj(value, setOf("relativePath", "path", "sha256", "bytes", "kind"))
            val descriptor = sorted[index]; val packagePath = BackupV2Json.string(f["path"])
            v2require(BackupV2Json.string(f["relativePath"]) == descriptor.relativePath && packagePath == path.substringBeforeLast('/') + "/resource-$index.bin" &&
                BackupV2Json.string(f["kind"]) == descriptor.kind.name && BackupV2Json.string(f["sha256"]) == descriptor.sha256 &&
                BackupV2Json.integer(f["bytes"]) == descriptor.bytes, "SOURCE_METADATA_MISMATCH", "resource descriptor differs")
            checkRelativePath(descriptor.relativePath)
            val data = archive.fileBytes(packagePath); verifyBytes(data, descriptor.sha256, descriptor.bytes, packagePath)
            resources[descriptor.relativePath] = packagePath; bytes[packagePath] = data
        }
        val created = decimalLong(root["createdAt"])
        val format = BackupV2Json.string(root["format"]); val kind = BackupV2Json.string(root["kind"])
        v2require(kind in setOf("IMPORTED", "BUILTIN") && format == doc.format && BackupV2Json.string(root["title"]) == doc.title,
            "SOURCE_METADATA_MISMATCH", "portable document metadata")
        val document = ReaderDocumentEntity(documentId, null, kind, format = format, title = BackupV2Json.string(root["title"]),
            author = nullableText(root["author"]), blobPath = resources.getValue(source(manifest).relativePath),
            fileSha256 = manifest.sourceSha256, contentRevision = revision, extractorVersion = BackupV2Json.string(root["extractorVersion"]),
            importStatus = "READY", createdAt = created, updatedAt = created)
        val revisionEntity = ReaderContentRevisionEntity(documentId, revision, manifest.sourceSha256, document.blobPath, rawPath,
            document.extractorVersion, "READY", created)
        val sections = BackupV2Json.array(root["sections"]).map { value ->
            val s = BackupV2Json.obj(value, setOf("id", "parentId", "orderIndex", "title", "depth", "resourceHref", "canonicalPath", "charCount", "pageStart", "pageEnd"))
            ReaderSectionEntity(BackupV2Json.string(s["id"]), documentId, revision, nullableText(s["parentId"]), int(s["orderIndex"]),
                BackupV2Json.string(s["title"]), int(s["depth"]), BackupV2Json.string(s["resourceHref"]), BackupV2Json.string(s["canonicalPath"]),
                int(s["charCount"]), nullableInt(s["pageStart"]), nullableInt(s["pageEnd"]))
        }
        val outline = BackupV2Json.array(root["outline"]).map { value ->
            val n = BackupV2Json.obj(value, setOf("id", "nodeKey", "parentId", "orderIndex", "title", "targetSectionId", "targetLocatorJson"))
            ReaderOutlineNodeEntity(BackupV2Json.string(n["id"]), documentId, revision, BackupV2Json.string(n["nodeKey"]), nullableText(n["parentId"]),
                int(n["orderIndex"]), BackupV2Json.string(n["title"]), nullableText(n["targetSectionId"]), nullableText(n["targetLocatorJson"]))
        }
        val capture = BackupV2RevisionCapture(document, revisionEntity, sections, outline)
        validateCapture(capture, manifest)
        v2require(encodeFileJson(proofJson(capture, manifest, rawPath, raw, resources)).contentEquals(rawProof), "SOURCE_METADATA_MISMATCH", "proof is not canonical schema 1")
        bytes[rawPath] = raw; bytes[path] = rawProof
        return BackupV2SourceProof(capture, manifest, path, rawPath, resources.getValue(source(manifest).relativePath), v2Map(resources), rawProof, raw, bytes)
    }

    private fun validateCapture(c: BackupV2RevisionCapture, m: ContentManifest) {
        v2require(m.documentId == c.document.id && m.contentRevision == c.revision && m.sourceSha256 == c.revisionEntity.sourceSha256 &&
            m.extractorVersion == c.revisionEntity.extractorVersion, "SOURCE_METADATA_MISMATCH", "revision/source/extractor differs")
        v2require(c.sections.isNotEmpty() && c.sections.size == m.sections.size && c.sections.map { it.id }.toSet().size == c.sections.size &&
            c.sections.map { it.orderIndex }.toSet().size == c.sections.size, "SOURCE_METADATA_MISMATCH", "complete unique sections required")
        val sortedRows = c.sections.sortedBy { it.orderIndex }; val sortedManifest = m.sections.sortedBy { it.orderIndex }
        val exact = sortedRows.map { it.id } == sortedManifest.map { it.id }
        val qualified = c.document.id.startsWith("imported:") && sortedRows.map { it.id } == sortedManifest.map { "${c.document.id}:${c.revision}:${it.id}" }
        v2require(exact || qualified, "SOURCE_METADATA_MISMATCH", "manifest section identity scheme differs")
        val files = m.files.associateBy { it.relativePath }
        sortedRows.zip(sortedManifest).forEach { (r, s) ->
            v2require(r.orderIndex == s.orderIndex && r.title == s.title && r.resourceHref == s.resourceHref && r.charCount == s.charCount &&
                r.pageStart == s.pageStart && r.pageEnd == s.pageEnd && r.depth >= 0 && (r.parentId == null || c.sections.any { it.id == r.parentId }),
                "SOURCE_METADATA_MISMATCH", "section metadata differs")
            v2require(files[r.resourceHref]?.kind == ManifestFileKind.SECTION && files.containsKey(r.canonicalPath), "SOURCE_RESOURCE_MISSING", "section/canonical resource is not declared")
            checkRelativePath(r.resourceHref); checkRelativePath(r.canonicalPath)
        }
        v2require(c.outline.size == m.outline.size && c.outline.map { it.id }.toSet().size == c.outline.size &&
            c.outline.map { it.nodeKey }.toSet().size == c.outline.size, "SOURCE_METADATA_MISMATCH", "complete outline required")
        val rowsByKey = c.outline.associateBy { it.nodeKey }
        val sectionIds = sortedManifest.zip(sortedRows).associate { it.first.id to it.second.id }
        m.outline.forEach { n ->
            val r = rowsByKey[n.nodeKey] ?: throw BackupV2Exception("SOURCE_METADATA_MISMATCH", "outline node absent")
            v2require(r.parentId == n.parentKey?.let { rowsByKey[it]?.id } && r.orderIndex == n.orderIndex && r.title == n.title &&
                r.targetSectionId == n.targetSectionId?.let { sectionIds[it] } && r.targetLocatorJson == n.targetLocatorJson,
                "SOURCE_METADATA_MISMATCH", "outline metadata differs")
            r.targetLocatorJson?.let { text ->
                val parsed = fileJson(text.toByteArray(Charsets.UTF_8), BackupV2Limits.MANIFEST_BYTES)
                val projected = BackupV2JsonProjection.remapColumn(BackupV2Table.READER_STATES, "locator_json", v2Canonical(parsed),
                    BackupV2IdMap(documents = mapOf(c.document.id to c.document.id), sections = c.sections.associate { it.id to it.id }))
                v2require(projected == v2Canonical(parsed), "SOURCE_METADATA_MISMATCH", "outline locator scope differs")
            }
        }
        source(m)
    }

    private fun portableCapture(c: BackupV2RevisionCapture, sourcePath: String, manifestPath: String): BackupV2RevisionCapture {
        val d = c.document.copy(ownerUserId = null, legacyChapterId = null, blobPath = sourcePath, fileSha256 = c.revisionEntity.sourceSha256,
            contentRevision = c.revision, extractorVersion = c.revisionEntity.extractorVersion, metadataRevision = 1, accessEpoch = 1,
            importStatus = "READY", deletedAt = null, createdAt = c.revisionEntity.createdAt, updatedAt = c.revisionEntity.createdAt)
        return BackupV2RevisionCapture(d, c.revisionEntity.copy(sourceBlobPath = sourcePath, manifestPath = manifestPath), c.sections, c.outline)
    }

    private fun proofJson(c: BackupV2RevisionCapture, m: ContentManifest, rawPath: String, raw: ByteArray, paths: Map<String, String>) = buildJsonObject {
        put("schemaVersion", 1); put("exportDocumentId", c.document.id); put("revision", c.revision); put("format", c.document.format)
        put("kind", c.document.kind); put("title", c.document.title); put("author", c.document.author?.let(::JsonPrimitive) ?: JsonNull)
        put("extractorVersion", c.revisionEntity.extractorVersion); put("createdAt", c.revisionEntity.createdAt.toString())
        put("manifest", buildJsonObject { put("path", rawPath); put("sha256", fileDigest(raw)); put("bytes", raw.size) })
        put("files", JsonArray(m.files.sortedBy { it.relativePath }.map { f -> buildJsonObject {
            put("relativePath", f.relativePath); put("path", paths.getValue(f.relativePath)); put("sha256", f.sha256); put("bytes", f.bytes); put("kind", f.kind.name)
        } }))
        put("sections", JsonArray(c.sections.sortedBy { it.orderIndex }.map { s -> buildJsonObject {
            put("id", s.id); put("parentId", s.parentId?.let(::JsonPrimitive) ?: JsonNull); put("orderIndex", s.orderIndex); put("title", s.title); put("depth", s.depth)
            put("resourceHref", s.resourceHref); put("canonicalPath", s.canonicalPath); put("charCount", s.charCount)
            put("pageStart", s.pageStart?.let(::JsonPrimitive) ?: JsonNull); put("pageEnd", s.pageEnd?.let(::JsonPrimitive) ?: JsonNull)
        } }))
        put("outline", JsonArray(c.outline.sortedBy { it.id }.map { n -> buildJsonObject {
            put("id", n.id); put("nodeKey", n.nodeKey); put("parentId", n.parentId?.let(::JsonPrimitive) ?: JsonNull); put("orderIndex", n.orderIndex); put("title", n.title)
            put("targetSectionId", n.targetSectionId?.let(::JsonPrimitive) ?: JsonNull); put("targetLocatorJson", n.targetLocatorJson?.let(::JsonPrimitive) ?: JsonNull)
        } }))
    }
}

internal fun fileDigest(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
internal fun revisionResourceDigest(manifest: ContentManifest): String = fileDigest(v2Canonical(JsonArray(manifest.files.sortedBy { it.relativePath }.map { f ->
    buildJsonObject { put("relativePath", f.relativePath); put("sha256", f.sha256); put("bytes", f.bytes); put("kind", f.kind.name) }
})).toByteArray(Charsets.UTF_8))
internal fun checkHash(value: String) = v2require(value.matches(Regex("[0-9a-f]{64}")), "HASH_MISMATCH", "SHA256 required")
internal fun fileJson(bytes: ByteArray, limit: Int): JsonElement = try { BackupV2Json.parse(bytes, limit) }
catch (error: BackupV2Exception) { if (error.code == "DUPLICATE_JSON_PROPERTY") throw BackupV2Exception("DUPLICATE_JSON_KEY", "duplicate key", error) else throw error }
internal fun strictManifest(raw: ByteArray): ContentManifest {
    fileJson(raw, BackupV2Limits.MANIFEST_BYTES)
    val m = ContentManifestCodec.decode(raw.toString(Charsets.UTF_8))
    v2require(m.files.size <= BackupV2Limits.FILES && m.sections.size <= BackupV2Limits.RECORDS && m.outline.size <= BackupV2Limits.RECORDS,
        "LIMIT_EXCEEDED", "manifest collections")
    m.files.forEach { checkRelativePath(it.relativePath); v2require(it.bytes <= BackupV2Limits.FILE_BYTES, "LIMIT_EXCEEDED", "manifest resource size") }
    return m.copy(files = v2List(m.files), sections = v2List(m.sections), outline = v2List(m.outline))
}
internal fun source(m: ContentManifest): ManifestFile {
    val source = m.files.singleOrNull { it.kind == ManifestFileKind.SOURCE }
        ?: throw BackupV2Exception("SOURCE_STRUCTURE_INCOMPLETE", "one real original SOURCE descriptor required")
    v2require(source.bytes > 0 && source.sha256 == m.sourceSha256, "SOURCE_METADATA_MISMATCH", "original source descriptor differs")
    return source
}
internal fun checkRelativePath(path: String) = v2require(BackupV2Codec.safePath(path), "UNSUPPORTED_RESOURCE_LAYOUT", "resource is outside portable private revision layout")
internal fun encodeFileJson(root: JsonElement): ByteArray {
    BackupV2Codec.checkEncodedSize(root, BackupV2Limits.MANIFEST_BYTES)
    return v2Canonical(root).toByteArray(Charsets.UTF_8)
}
internal fun nullableText(element: JsonElement?): String? = if (element === JsonNull) null else BackupV2Json.string(element)
internal fun int(element: JsonElement?): Int {
    val value = BackupV2Json.integer(element); v2require(value in Int.MIN_VALUE..Int.MAX_VALUE, "INVALID_JSON_SHAPE", "Int overflow"); return value.toInt()
}
internal fun nullableInt(element: JsonElement?): Int? = if (element === JsonNull) null else int(element)
internal fun decimalLong(element: JsonElement?): Long {
    val text = BackupV2Json.string(element); v2require(text.matches(Regex("0|[1-9][0-9]*")), "INVALID_JSON_SHAPE", "unsigned decimal Long required")
    return text.toLongOrNull() ?: throw BackupV2Exception("INVALID_JSON_SHAPE", "Long overflow")
}
internal fun rejectInk(backup: BackupV2Package) = v2require(backup.manifest.files.none { it.kind == "INK" } &&
    backup.records[BackupV2Table.READER_ANNOTATIONS].none { it.text("ink_blob_path") != null }, "UNSUPPORTED_INK_RESOURCE", "INK publication ownership contract is unavailable")
internal fun verifyBytes(bytes: ByteArray, hash: String, count: Long, path: String) {
    checkHash(hash); v2require(bytes.size.toLong() == count, "SIZE_MISMATCH", path); v2require(fileDigest(bytes) == hash, "HASH_MISMATCH", path)
}
internal fun ordinaryFile(file: File, requireFile: Boolean) {
    v2require(file.absoluteFile.normalize() == file.canonicalFile, "SYMLINK_FORBIDDEN", "aliased storage path")
    v2require(!requireFile || file.isFile, "SOURCE_RESOURCE_MISSING", "regular resource required")
}
internal fun ownedPath(root: File, target: File) {
    ordinaryFile(root, false); ordinaryFile(target, false)
    v2require(target.canonicalPath.startsWith(root.canonicalPath + File.separator), "INVALID_PATH", "path leaves private root")
}
internal fun readFile(file: File, limit: Long): ByteArray {
    ordinaryFile(file, true)
    v2require(file.length() in 0..limit, "LIMIT_EXCEEDED", "resource length")
    return file.inputStream().use { input ->
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); var total = 0L
        while (true) { val n = input.read(buffer); if (n < 0) break; if (n == 0) continue; total += n
            v2require(total <= limit, "LIMIT_EXCEEDED", "resource grew while reading"); out.write(buffer, 0, n) }
        out.toByteArray()
    }
}
internal fun atomicFileWrite(target: File, bytes: ByteArray, replace: Boolean) {
    ordinaryFile(target, false)
    val creationBoundary = existingDirectoryAncestor(target)
    val parent = target.parentFile!!; v2require(parent.mkdirs() || parent.isDirectory, "IO_FAILURE", "private directory creation")
    val temporary = File(parent, ".${target.name}.${java.util.UUID.randomUUID()}.tmp")
    try {
        FileOutputStream(temporary).use { out -> out.write(bytes); out.fd.sync() }
        if (!replace) v2require(!target.exists(), "DESTINATION_EXISTS", "refuse replacing file")
        Api24PrivateFiles.atomicMove(temporary, target, replace)
        if (System.getProperty("java.vm.name") == "Dalvik") {
            syncPrivateDirectory(parent, parent)
        }
        syncPrivateDirectories(creationBoundary, listOf(parent))
    } finally { temporary.delete() }
}

/** Both Reader layouts have four components beneath the private storage root. */
private fun storageRoot(revisionDirectory: File): File {
    val revisions = revisionDirectory.parentFile ?: throw BackupV2Exception("INVALID_PATH", "revision parent absent")
    val namespace = revisions.parentFile?.parentFile ?: throw BackupV2Exception("INVALID_PATH", "revision namespace absent")
    v2require(revisions.name == "revisions" && namespace.name in setOf("staging", "documents"), "INVALID_PATH", "uncontrolled storage layout")
    return namespace.parentFile ?: throw BackupV2Exception("INVALID_PATH", "storage root absent")
}

/** Android directory fsync is required before a Room receipt can make publication durable.
 * JVM ATOMIC_MOVE supports process-crash tests; Windows does not claim a power-loss guarantee. */
internal fun syncPrivateDirectory(root: File, directory: File) {
    ordinaryFile(root, false); ordinaryFile(directory, false)
    v2require(directory.canonicalFile == root.canonicalFile || directory.canonicalPath.startsWith(root.canonicalPath + File.separator),
        "INVALID_PATH", "directory sync leaves private root")
    v2require(root.isDirectory && directory.isDirectory, "IO_FAILURE", "directory sync target is unavailable")
    if (System.getProperty("java.vm.name") == "Dalvik") {
        val fd = android.system.Os.open(directory.absolutePath, android.system.OsConstants.O_RDONLY, 0)
        Api24PrivateFiles.withCleanup({ android.system.Os.fsync(fd) }, { android.system.Os.close(fd) })
    }
}
internal fun syncPrivateDirectories(root: File, leaves: List<File>) {
    val directories = linkedSetOf<File>()
    leaves.forEach { leaf ->
        if (leaf.canonicalFile != root.canonicalFile) ownedPath(root, leaf)
        var current = leaf
        while (true) {
            directories += current.absoluteFile
            if (current.canonicalFile == root.canonicalFile) break
            current = current.parentFile ?: throw BackupV2Exception("INVALID_PATH", "directory parent absent")
        }
    }
    directories.sortedByDescending { it.path.length }.forEach { syncPrivateDirectory(root, it) }
}
internal fun existingDirectoryAncestor(target: File): File {
    var candidate = target.parentFile ?: throw BackupV2Exception("INVALID_PATH", "private root parent absent")
    while (!candidate.exists()) candidate = candidate.parentFile ?: throw BackupV2Exception("INVALID_PATH", "private root ancestor absent")
    ordinaryFile(candidate, false)
    v2require(candidate.isDirectory, "IO_FAILURE", "private root ancestor is not a directory")
    return candidate
}
