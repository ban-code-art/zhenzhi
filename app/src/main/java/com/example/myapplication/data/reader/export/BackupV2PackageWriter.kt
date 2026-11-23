package com.example.myapplication.data.reader.export

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupV2WrittenPackage internal constructor(bytes: ByteArray, val backup: BackupV2Package, changes: List<BackupV2ProjectionChange>) {
    private val snapshot = bytes.copyOf()
    val bytes get() = snapshot.copyOf()
    val packageSha256 = fileDigest(snapshot)
    val projectionChanges = v2List(changes)
}

/** Standard STORED ZIP, re-read by the independent archive contract before it can leave private storage. */
class BackupV2PackageWriter(private val snapshotter: BackupV2RevisionSnapshot,
    private val byteLimit: Long = BackupV2Limits.TOTAL_BYTES, private val fileLimit: Int = BackupV2Limits.FILES,
    private val beforeProofFileCopy: (String) -> Unit = {}) {
    fun encode(base: BackupV2Package, proofs: List<BackupV2RevisionProof> = emptyList()): BackupV2WrittenPackage {
        rejectInk(base)
        val verified = proofs.map { it as? BackupV2SourceProof ?: throw BackupV2Exception("UNVERIFIED_SOURCE_PROOF", "strict file proof required") }
        v2require(verified.map { it.exportDocumentId to it.revision }.toSet().size == verified.size, "SOURCE_METADATA_MISMATCH", "duplicate proof revision")
        val byDocument = verified.groupBy { it.exportDocumentId }
        val selected = base.manifest.documents.map { it.exportDocumentId }.toSet()
        v2require(byDocument.keys.all { it in selected }, "SOURCE_METADATA_MISMATCH", "proof outside selected roots")
        val budget = BackupV2FileBudget(byteLimit, fileLimit)
        budget.reserve(0, 2)
        val resources = linkedMapOf<String, BackupV2SourceProof>()
        val files = arrayListOf(BackupV2File("records.json", "0".repeat(64), 0, "RECORDS"))
        val documents = base.manifest.documents.map { document ->
            val revisions = byDocument[document.exportDocumentId].orEmpty()
            if (revisions.isEmpty()) {
                v2require(!base.manifest.options.includeSources, "SOURCE_STRUCTURE_INCOMPLETE", "every selected document needs a real fixed source proof")
                BackupV2Document(document.exportDocumentId, document.kind, document.title, document.format, document.sourceSha256,
                    document.contentRevision, document.chapterKey, null, emptyList(), document.sectionIds)
            } else {
                val head = revisions.singleOrNull { it.revision == document.contentRevision }
                    ?: throw BackupV2Exception("SOURCE_STRUCTURE_INCOMPLETE", "selected head has no fixed source proof")
                v2require(head.capture.document.title == document.title && head.capture.document.format == document.format,
                    "SOURCE_METADATA_MISMATCH", "wire metadata differs from fixed source")
                revisions.forEach { proof -> proof.paths.forEach { path ->
                    v2require(!resources.containsKey(path), "DUPLICATE_PATH", path)
                    val size = proof.fileSize(path)
                    budget.reserve(size)
                    resources[path] = proof
                    files += BackupV2File(path, proof.fileHash(path), size, if (path == head.sourcePackagePath) "SOURCE" else "SNAPSHOT")
                } }
                val sectionIds = revisions.flatMap { it.capture.sections }.map { it.id }
                v2require(sectionIds.toSet().size == sectionIds.size, "SOURCE_METADATA_MISMATCH", "section identity shared across revisions")
                BackupV2Document(document.exportDocumentId, document.kind, document.title, document.format, head.manifest.sourceSha256,
                    document.contentRevision, document.chapterKey, head.sourcePackagePath,
                    revisions.flatMap { it.paths }.filter { it != head.sourcePackagePath }.sorted(), sectionIds.sorted())
            }
        }
        v2require(base.manifest.files.all { it.path == "records.json" }, "UNBOUND_RESOURCE", "writer requires explicit source proofs; loose base resources cannot be copied")
        val manifest = BackupV2Manifest(base.manifest.exportId, base.manifest.createdAtUtc, base.manifest.appVersion, base.manifest.testOnly,
            base.manifest.contentHash, BackupV2Options(verified.isNotEmpty() || base.manifest.options.includeSources), documents, files.sortedBy { it.path }, base.manifest.courseChapterRefs)
        val encoded = BackupV2Codec.encode(BackupV2Package(manifest, base.records))
        val manifestBytes = encoded.manifestBytes; val recordsBytes = encoded.recordsBytes
        budget.reserve(manifestBytes.size.toLong() + recordsBytes.size.toLong(), 0)
        // Standard STORED headers + fixed timestamp extras are bounded before any large defensive copy.
        val names = resources.keys + listOf("manifest.json", "records.json")
        budget.reserve(22L + names.sumOf { 76L + it.length * 2L + 128L }, 0)
        val contents = linkedMapOf<String, ByteArray>()
        resources.forEach { (path, proof) ->
            beforeProofFileCopy(path)
            val bytes = proof.fileBytes(path)
            v2require(bytes.size.toLong() == proof.fileSize(path), "SIZE_MISMATCH", "immutable proof size differs")
            contents[path] = bytes
        }
        contents["manifest.json"] = manifestBytes; contents["records.json"] = recordsBytes
        val output = ByteArrayOutputStream()
        ZipOutputStream(output, Charsets.US_ASCII).use { zip -> contents.toSortedMap().forEach { (path, bytes) ->
            v2require(BackupV2Codec.safePath(path), "INVALID_PATH", path)
            val entry = ZipEntry(path).apply { method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size
                crc = CRC32().apply { update(bytes) }.value; time = 0 }
            zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
        } }
        val bytes = output.toByteArray()
        v2require(bytes.size.toLong() <= byteLimit, "LIMIT_EXCEEDED", "stored ZIP byte budget")
        val oracle = BackupV2Archive.read(bytes)
        snapshotter.fromArchive(oracle)
        return BackupV2WrittenPackage(bytes, oracle.backup, encoded.projectionChanges)
    }

    fun publish(packet: BackupV2WrittenPackage, target: File): File {
        val bytes = packet.bytes
        v2require(fileDigest(bytes) == packet.packageSha256, "HASH_MISMATCH", "private output changed")
        val archive = BackupV2Archive.read(bytes); snapshotter.fromArchive(archive)
        atomicFileWrite(target.absoluteFile, bytes, replace = false)
        return target.absoluteFile
    }
}
