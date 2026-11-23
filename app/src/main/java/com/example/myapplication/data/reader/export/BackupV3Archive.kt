package com.example.myapplication.data.reader.export

import com.example.myapplication.data.reader.content.ContentPackageCodec
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.*

/** V3 envelope. The nested V2 converter remains the only parser for base records. */
internal object BackupV3Archive {
    private const val MANIFEST = "manifest.json"
    private const val BASE = "base/v2.zip"
    private const val REVIEW = "review-state/1.json"
    private const val ENCYCLOPEDIA = "encyclopedia-state/1.json"
    private const val PRACTICE_PREFIX = "source-practice/v1/"
    private const val FORMAT = "s1-composite-backup"
    internal const val TOTAL_BYTES = 96 * 1024 * 1024L
    private const val BASE_BYTES = 64 * 1024 * 1024L
    internal const val PRACTICE_BYTES = 4 * 1024 * 1024
    internal const val MAX_PRACTICE_ENTRIES = 128
    private const val MANIFEST_BYTES = 128 * 1024
    private val digest = Regex("[a-f0-9]{64}")
    internal val practiceId = Regex("[a-f0-9]{64}")

    class Snapshot internal constructor(
        val packageSha256: String,
        val base: BackupV2ArchiveSnapshot,
        val review: PortableReviewState,
        private val nestedBytes: ByteArray,
        private val practice: Map<String, ByteArray>,
        val encyclopedia: PortableEncyclopediaState = PortableEncyclopediaState(),
    ) {
        fun baseBytes(): ByteArray = nestedBytes.copyOf()
        fun practiceEntries(): Map<String, ByteArray> = practice.mapValues { it.value.copyOf() }
    }

    fun write(
        base: ByteArray,
        review: PortableReviewState,
        practice: Map<String, ByteArray> = emptyMap(),
        encyclopedia: PortableEncyclopediaState = PortableEncyclopediaState(),
    ): ByteArray {
        v2require(practice.size <= MAX_PRACTICE_ENTRIES && practice.keys.all(practiceId::matches),
            "INVALID_PRACTICE_ID", "source practice archive id")
        val parts = linkedMapOf(BASE to base.copyOf(), REVIEW to PortableReviewCodec.write(review))
        if (encyclopedia.favorites.isNotEmpty()) {
            parts[ENCYCLOPEDIA] = PortableEncyclopediaCodec.write(encyclopedia)
        }
        practice.toSortedMap().forEach { (id, bytes) -> parts[practicePath(id)] = bytes.copyOf() }
        parts.forEach { (path, bytes) -> checkPartSize(path, bytes.size.toLong()) }
        validatePracticeSelection(review, practice.keys)
        val manifest = manifest(parts).toString().toByteArray(Charsets.UTF_8)
        checkPartSize(MANIFEST, manifest.size.toLong())
        val outerBytes = manifest.size.toLong() + parts.values.sumOf { it.size.toLong() }
        BackupV2Archive.readNested(parts.getValue(BASE), TOTAL_BYTES - outerBytes, BackupV2Limits.FILES - parts.size)
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                (linkedMapOf(MANIFEST to manifest) + parts).forEach { (path, bytes) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray().also { bytes ->
            v2require(bytes.size.toLong() <= TOTAL_BYTES, "LIMIT_EXCEEDED", "compressed V3 ZIP size")
        }
    }

    fun read(bytes: ByteArray): Snapshot {
        v2require(bytes.size.toLong() <= TOTAL_BYTES, "LIMIT_EXCEEDED", "compressed V3 ZIP size")
        val input = bytes.copyOf()
        val parts = BackupV2Archive.readEntries(input, TOTAL_BYTES, MAX_PRACTICE_ENTRIES + 4, maxBytes = ::partLimit)
        val manifestBytes = parts[MANIFEST] ?: throw BackupV2Exception("MANIFEST_MISSING", "V3 manifest")
        val listed = BackupV2Json.obj(BackupV2Json.parse(manifestBytes, MANIFEST_BYTES),
            setOf("format", "schemaVersion", "parts"))
        v2require(BackupV2Json.string(listed["format"]) == FORMAT && BackupV2Json.integer(listed["schemaVersion"]) == 3L,
            "UNSUPPORTED_VERSION", "V3 envelope")
        val declared = linkedSetOf<String>()
        BackupV2Json.array(listed["parts"]).forEach { element ->
            val part = BackupV2Json.obj(element, setOf("path", "version", "bytes", "sha256"))
            val path = BackupV2Json.string(part["path"])
            v2require(path != MANIFEST && declared.add(path), "INVALID_MANIFEST", "duplicate or reserved V3 path")
            val size = BackupV2Json.integer(part["bytes"])
            checkPartSize(path, size)
            v2require(BackupV2Json.integer(part["version"]) == partVersion(path), "INVALID_MANIFEST", "V3 part version")
            val hash = BackupV2Json.string(part["sha256"])
            val content = parts[path] ?: throw BackupV2Exception("UNDECLARED_ENTRY", "missing V3 entry")
            v2require(size == content.size.toLong(), "SIZE_MISMATCH", path)
            v2require(digest.matches(hash) && ContentPackageCodec.sha256(content) == hash, "HASH_MISMATCH", path)
        }
        v2require(BASE in declared && REVIEW in declared && parts.keys == declared + MANIFEST,
            "UNDECLARED_ENTRY", "required V3 entries and exact manifest listing")
        val base = parts.getValue(BASE)
        val review = PortableReviewCodec.read(parts.getValue(REVIEW))
        val encyclopedia = if (ENCYCLOPEDIA in declared) {
            PortableEncyclopediaCodec.read(parts.getValue(ENCYCLOPEDIA))
        } else {
            PortableEncyclopediaState()
        }
        val practice = declared.mapNotNull { path -> practiceIdOf(path)?.let { it to parts.getValue(path) } }.toMap()
        validatePracticeSelection(review, practice.keys)
        val nested = BackupV2Archive.readNested(base, TOTAL_BYTES - parts.values.sumOf { it.size.toLong() },
            BackupV2Limits.FILES + 1 - parts.size)
        return Snapshot(ContentPackageCodec.sha256(input), nested, review, base, practice, encyclopedia)
    }

    private fun manifest(parts: Map<String, ByteArray>) = buildJsonObject {
        put("format", FORMAT)
        put("schemaVersion", 3)
        putJsonArray("parts") {
            parts.forEach { (path, bytes) ->
                add(buildJsonObject {
                    put("path", path)
                    put("version", partVersion(path))
                    put("bytes", bytes.size)
                    put("sha256", ContentPackageCodec.sha256(bytes))
                })
            }
        }
    }

    private fun partVersion(path: String): Long = if (path == BASE) 2 else 1

    private fun partLimit(path: String): Long = when (path) {
        MANIFEST -> MANIFEST_BYTES.toLong()
        BASE -> BASE_BYTES
        REVIEW -> PortableReviewCodec.MAX_BYTES.toLong()
        ENCYCLOPEDIA -> PortableEncyclopediaCodec.MAX_BYTES.toLong()
        else -> if (practiceIdOf(path) != null) PRACTICE_BYTES.toLong()
            else throw BackupV2Exception("UNDECLARED_ENTRY", "unexpected V3 path")
    }

    private fun checkPartSize(path: String, size: Long) {
        v2require(size in 1..partLimit(path), "LIMIT_EXCEEDED", "V3 part size")
    }

    private fun practicePath(id: String) = "$PRACTICE_PREFIX$id.json"

    private fun practiceIdOf(path: String): String? =
        path.removePrefix(PRACTICE_PREFIX).removeSuffix(".json").takeIf { practiceId.matches(it) && practicePath(it) == path }

    private fun validatePracticeSelection(review: PortableReviewState, entries: Set<String>) {
        val referenced = review.items.filter { it.source.kind == "practice" }.mapNotNull { it.source.id }.toSet()
        v2require(referenced.containsAll(entries), "UNDECLARED_ENTRY", "source practice entry has no selected review card")
    }
}
