package com.example.myapplication.data.knowledge.retrieval

import java.io.File
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

/**
 * ANN manifest 文件（设计 §6/§13 / 接口明细 §14）。
 *
 * 布局（little-endian）：
 *   magic "ACANN1" (6B) | version u16 | dim u16 | count u32 |
 *   generationHashLen u16 | generationHash UTF-8 |
 *   count × (chunkId i64 | float32[dim]) |
 *   payloadSha256 (32B)
 *
 * 校验和覆盖 magic 之后到向量区结束的全部字节；损坏/截断/版本不符一律判为
 * 不可用，调用方回退 exact 并报告降级，绝不拿半个 manifest 冒充完整索引。
 */
object AnnManifestCodec {
    private val MAGIC = byteArrayOf(0x41, 0x43, 0x41, 0x4E, 0x4E, 0x31) // "ACANN1"
    private const val VERSION = 1
    private const val CHECKSUM_BYTES = 32

    data class Entry(val chunkId: Long, val vector: FloatArray) {
        override fun equals(other: Any?): Boolean =
            other is Entry && chunkId == other.chunkId && vector.contentEquals(other.vector)

        override fun hashCode(): Int = 31 * chunkId.hashCode() + vector.contentHashCode()
    }

    data class Manifest(
        val dim: Int,
        val generationHash: String,
        val entries: List<Entry>,
        val checksum: String,
    )

    data class FileManifest(
        val dim: Int,
        val generationHash: String,
        val entryCount: Int,
        val checksum: String,
        internal val entriesOffset: Long,
    )

    fun encode(dim: Int, generationHash: String, entries: List<Entry>): ByteArray {
        require(dim in 1..Short.MAX_VALUE.toInt())
        require(entries.size <= Int.MAX_VALUE)
        require(entries.all { it.vector.size == dim && it.vector.all(Float::isFinite) })
        val hashBytes = generationHash.toByteArray(Charsets.UTF_8)
        require(hashBytes.size <= 0xFFFF)
        val body = ByteBuffer.allocate(
            2 + 2 + 4 + 2 + hashBytes.size + entries.size * (8 + dim * 4),
        ).order(ByteOrder.LITTLE_ENDIAN)
        body.putShort(VERSION.toShort())
        body.putShort(dim.toShort())
        body.putInt(entries.size)
        body.putShort(hashBytes.size.toShort())
        body.put(hashBytes)
        entries.forEach { entry ->
            body.putLong(entry.chunkId)
            entry.vector.forEach { body.putFloat(it) }
        }
        val payload = body.array()
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        return ByteBuffer.allocate(MAGIC.size + payload.size + CHECKSUM_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(MAGIC)
            .put(payload)
            .put(digest)
            .array()
    }

    fun decode(bytes: ByteArray): Manifest? {
        if (bytes.size < MAGIC.size + 2 + 2 + 4 + 2 + CHECKSUM_BYTES) return null
        for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return null
        val payloadEnd = bytes.size - CHECKSUM_BYTES
        val payload = bytes.copyOfRange(MAGIC.size, payloadEnd)
        val expected = MessageDigest.getInstance("SHA-256").digest(payload)
        for (i in expected.indices) if (expected[i] != bytes[payloadEnd + i]) return null

        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val version = buffer.short.toInt()
        if (version != VERSION) return null
        val dim = buffer.short.toInt()
        if (dim <= 0) return null
        val count = buffer.int
        if (count < 0) return null
        val hashLen = buffer.short.toInt() and 0xFFFF
        if (hashLen < 0 || buffer.remaining() < hashLen) return null
        val hashBytes = ByteArray(hashLen)
        buffer.get(hashBytes)
        val required = count.toLong() * (8L + dim * 4L)
        if (buffer.remaining().toLong() != required) return null
        val entries = ArrayList<Entry>(count)
        repeat(count) {
            val chunkId = buffer.long
            val vector = FloatArray(dim)
            for (i in 0 until dim) {
                val value = buffer.float
                if (!value.isFinite()) return null
                vector[i] = value
            }
            entries += Entry(chunkId, vector)
        }
        return Manifest(
            dim = dim,
            generationHash = hashBytes.toString(Charsets.UTF_8),
            entries = entries,
            checksum = expected.joinToString("") { "%02x".format(it) },
        )
    }

    /** Validate a manifest in bounded memory; vectors are checked one record at a time. */
    fun validateFile(file: File): FileManifest? = runCatching {
        if (!file.isFile || file.length() < MAGIC.size + 10L + CHECKSUM_BYTES) return null
        BufferedInputStream(FileInputStream(file), IO_BUFFER_BYTES).use { input ->
            val magic = ByteArray(MAGIC.size)
            readFully(input, magic)
            if (!magic.contentEquals(MAGIC)) return null

            val prefix = ByteArray(10)
            readFully(input, prefix)
            val header = ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN)
            val version = header.short.toInt() and 0xFFFF
            val dim = header.short.toInt() and 0xFFFF
            val count = header.int
            val hashLen = header.short.toInt() and 0xFFFF
            if (version != VERSION || dim <= 0 || count < 0) return null
            val fixedRecordBytes = 8L + dim.toLong() * 4L
            if (fixedRecordBytes > MAX_RECORD_BYTES) return null
            val expectedLength = MAGIC.size.toLong() + prefix.size + hashLen + count.toLong() * fixedRecordBytes + CHECKSUM_BYTES
            if (expectedLength != file.length()) return null

            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(prefix)
            val hashBytes = ByteArray(hashLen)
            readFully(input, hashBytes)
            digest.update(hashBytes)
            val recordBytes = ByteArray(fixedRecordBytes.toInt())
            repeat(count) {
                readFully(input, recordBytes)
                digest.update(recordBytes)
                val record = ByteBuffer.wrap(recordBytes).order(ByteOrder.LITTLE_ENDIAN)
                record.long // chunk id
                repeat(dim) {
                    if (!record.float.isFinite()) return null
                }
            }
            val expectedChecksum = ByteArray(CHECKSUM_BYTES)
            readFully(input, expectedChecksum)
            if (input.read() != -1) return null
            val actualChecksum = digest.digest()
            if (!MessageDigest.isEqual(actualChecksum, expectedChecksum)) return null
            FileManifest(
                dim = dim,
                generationHash = hashBytes.toString(Charsets.UTF_8),
                entryCount = count,
                checksum = actualChecksum.joinToString("") { "%02x".format(it) },
                entriesOffset = MAGIC.size.toLong() + prefix.size + hashLen,
            )
        }
    }.getOrNull()

    /** Read one entry for compatibility callers. Query paths should use [forEachFileEntry]. */
    fun readFileEntry(file: File, manifest: FileManifest, index: Int): Entry {
        require(index in 0 until manifest.entryCount)
        val recordSize = 8 + manifest.dim * 4
        val offset = manifest.entriesOffset + index.toLong() * recordSize
        val bytes = ByteArray(recordSize)
        RandomAccessFile(file, "r").use { stream ->
            stream.seek(offset)
            stream.readFully(bytes)
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val chunkId = buffer.long
        val vector = FloatArray(manifest.dim) { buffer.float }
        return Entry(chunkId, vector)
    }

    /** Stream entries without retaining decoded vectors between callbacks. */
    fun forEachFileEntry(file: File, manifest: FileManifest, action: (Entry) -> Unit) {
        val recordSize = 8 + manifest.dim * 4
        val bytes = ByteArray(recordSize)
        RandomAccessFile(file, "r").use { stream ->
            stream.seek(manifest.entriesOffset)
            repeat(manifest.entryCount) {
                stream.readFully(bytes)
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val chunkId = buffer.long
                val vector = FloatArray(manifest.dim) { buffer.float }
                action(Entry(chunkId, vector))
            }
        }
    }

    private fun readFully(input: java.io.InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) throw java.io.EOFException("truncated ANN manifest")
            offset += read
        }
    }

    private const val IO_BUFFER_BYTES = 1 shl 20
    private const val MAX_RECORD_BYTES = 1L shl 20
}

/**
 * manifest 文件读写：先写临时文件再原子替换，读时校验 checksum。
 * 文件是可重建派生物；损坏时删除并返回 null（调用方回退 exact）。
 */
class AnnManifestStore(private val root: File) {
    init {
        if (!root.exists()) root.mkdirs()
    }

    data class Stored(
        val file: File,
        val manifest: AnnManifestCodec.Manifest,
        private val fileManifest: AnnManifestCodec.FileManifest,
    ) {
        fun forEachEntry(action: (AnnManifestCodec.Entry) -> Unit) =
            AnnManifestCodec.forEachFileEntry(file, fileManifest, action)
    }

    fun write(
        userId: Long,
        profileFingerprint: String,
        dim: Int,
        generationHash: String,
        entries: List<AnnManifestCodec.Entry>,
    ): Stored = writeStreaming(userId, profileFingerprint, dim, generationHash, entries.size, entries::get)

    /** Build a large ANN file in bounded memory from a deterministic entry source. */
    fun writeStreaming(
        userId: Long,
        profileFingerprint: String,
        dim: Int,
        generationHash: String,
        count: Int,
        entryAt: (Int) -> AnnManifestCodec.Entry,
    ): Stored {
        require(dim in 1..Short.MAX_VALUE.toInt())
        require(count >= 0)
        val directory = userDirectory(userId, profileFingerprint)
        if (!directory.exists()) directory.mkdirs()
        val target = File(directory, "manifest.bin")
        val temporary = File(directory, "manifest.tmp-${UUID.randomUUID()}")
        val backup = File(directory, "manifest.previous")
        val generationBytes = generationHash.toByteArray(Charsets.UTF_8)
        require(generationBytes.size <= 0xFFFF)
        val digest = MessageDigest.getInstance("SHA-256")
        val header = ByteBuffer.allocate(2 + 2 + 4 + 2 + generationBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1.toShort()).putShort(dim.toShort()).putInt(count)
            .putShort(generationBytes.size.toShort()).put(generationBytes).array()
        val record = ByteBuffer.allocate(8 + dim * 4).order(ByteOrder.LITTLE_ENDIAN)
        FileOutputStream(temporary).use { fileOutput ->
            BufferedOutputStream(fileOutput, 1 shl 20).use { output ->
                output.write(MAGIC)
                output.write(header)
                digest.update(header)
                repeat(count) { index ->
                    val entry = entryAt(index)
                    require(entry.vector.size == dim && entry.vector.all { it.isFinite() }) {
                        "manifest entry $index has invalid dimension or value"
                    }
                    record.clear()
                    record.putLong(entry.chunkId)
                    entry.vector.forEach(record::putFloat)
                    val bytes = record.array()
                    output.write(bytes)
                    digest.update(bytes)
                }
                output.write(digest.digest())
            }
        }
        if (backup.exists() && !backup.delete()) {
            temporary.delete()
            throw java.io.IOException("could not clear previous ann manifest backup")
        }
        val hadTarget = target.exists()
        if (hadTarget && !target.renameTo(backup)) {
            temporary.delete()
            throw java.io.IOException("could not preserve existing ann manifest")
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            if (hadTarget) backup.renameTo(target)
            throw java.io.IOException("could not install ann manifest")
        }
        backup.delete()
        return read(userId, profileFingerprint) ?: throw java.io.IOException("could not validate installed ann manifest")
    }

    /** 读取并校验；任何异常（缺失/损坏/校验失败）返回 null。 */
    fun read(userId: Long, profileFingerprint: String): Stored? {
        val file = File(userDirectory(userId, profileFingerprint), "manifest.bin")
        if (!file.isFile) return null
        val fileManifest = AnnManifestCodec.validateFile(file) ?: run {
            file.delete()
            return null
        }
        val entries = object : AbstractList<AnnManifestCodec.Entry>() {
            override val size: Int get() = fileManifest.entryCount
            override fun get(index: Int): AnnManifestCodec.Entry =
                AnnManifestCodec.readFileEntry(file, fileManifest, index)
        }
        val manifest = AnnManifestCodec.Manifest(
            dim = fileManifest.dim,
            generationHash = fileManifest.generationHash,
            entries = entries,
            checksum = fileManifest.checksum,
        )
        return Stored(file, manifest, fileManifest)
    }

    fun delete(userId: Long, profileFingerprint: String): Boolean {
        val file = File(userDirectory(userId, profileFingerprint), "manifest.bin")
        return !file.exists() || file.delete()
    }

    fun checksumOf(userId: Long, profileFingerprint: String): String? =
        read(userId, profileFingerprint)?.manifest?.checksum

    private fun userDirectory(userId: Long, profileFingerprint: String): File =
        File(File(root, "u$userId"), sanitize(profileFingerprint))

    private fun sanitize(value: String): String =
        value.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")

    private companion object {
        private val MAGIC = byteArrayOf(0x41, 0x43, 0x41, 0x4E, 0x4E, 0x31)
    }
}

/**
 * 重建策略（设计 §13）：delta 达到 active 向量 5% 或 1000 条（取先者）排重建任务。
 */
object AnnRebuildPolicy {
    const val DELTA_THRESHOLD_ABSOLUTE = 1000
    const val DELTA_THRESHOLD_PERCENT = 5

    fun shouldRebuild(baseCount: Int, deltaCount: Int): Boolean {
        if (deltaCount <= 0) return false
        if (baseCount <= 0) return true
        val percentThreshold = (baseCount * DELTA_THRESHOLD_PERCENT + 99) / 100
        return deltaCount >= minOf(DELTA_THRESHOLD_ABSOLUTE, percentThreshold)
    }
}
