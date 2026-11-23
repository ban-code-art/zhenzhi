package com.example.myapplication.data.reader.export

import java.io.*
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.CRC32
import java.util.zip.ZipInputStream
import com.example.myapplication.data.reader.content.ContentPackageCodec

class BackupV2ArchiveSnapshot internal constructor(val backup: BackupV2Package, contents: Map<String, ByteArray>) {
    private val contents = contents.mapValues { it.value.copyOf() }
    val paths: Set<String> = Collections.unmodifiableSet(LinkedHashSet(this.contents.keys))
    internal val expandedBytes: Long = this.contents.values.sumOf { it.size.toLong() }
    fun fileBytes(path: String): ByteArray = contents.getValue(path).copyOf()
}
data class BackupV2Published(val directory: File, val backup: BackupV2Package)

/** ZIP validation consumes one private bounded snapshot. Publishing is limited to a caller-owned private root. */
object BackupV2Archive {
    private val rootLocks = ConcurrentHashMap<String, ReentrantLock>()
    private data class Header(val name: String, val size: Long, val compressed: Long, val crc: Long, val local: Long, val flags: Int, val method: Int)
    fun read(bytes: ByteArray): BackupV2ArchiveSnapshot = readSnapshot(bytes, { false })
    fun read(file: File): BackupV2ArchiveSnapshot {
        v2require(file.isFile && sameCanonical(file) && file.length() in 22..BackupV2Limits.TOTAL_BYTES, "INVALID_ARCHIVE", "regular bounded ZIP required")
        val bytes = file.inputStream().use { input -> boundedRead(input, BackupV2Limits.TOTAL_BYTES, { false }) }
        return read(bytes)
    }
    private fun readSnapshot(bytes: ByteArray, cancelled: () -> Boolean, expandedLimit: Long = BackupV2Limits.TOTAL_BYTES,
        entryLimit: Int = BackupV2Limits.FILES + 1): BackupV2ArchiveSnapshot {
        val contents = readEntries(bytes, expandedLimit, entryLimit, cancelled) { name ->
            if (name == "manifest.json") BackupV2Limits.MANIFEST_BYTES.toLong() else BackupV2Limits.FILE_BYTES.toLong()
        }
        val manifest = contents["manifest.json"] ?: throw BackupV2Exception("MANIFEST_MISSING", "ZIP manifest required")
        val records = contents["records.json"] ?: throw BackupV2Exception("RECORDS_MISSING", "ZIP records required")
        val backup = BackupV2Codec.decode(manifest, records)
        val declared = backup.manifest.files.map { it.path }.toSet() + "manifest.json"
        v2require(contents.keys == declared, "UNDECLARED_ENTRY", "ZIP entry set differs from manifest")
        backup.manifest.files.forEach { file ->
            val data = contents.getValue(file.path)
            v2require(data.size.toLong() == file.bytes, "SIZE_MISMATCH", file.path)
            v2require(ContentPackageCodec.sha256(data) == file.sha256, "HASH_MISMATCH", file.path)
        }
        cancellation(cancelled)
        return BackupV2ArchiveSnapshot(backup, contents)
    }

    internal fun readNested(bytes: ByteArray, expandedLimit: Long, entryLimit: Int) =
        readSnapshot(bytes, { false }, expandedLimit, entryLimit)

    internal fun readEntries(bytes: ByteArray, expandedLimit: Long, entryLimit: Int, cancelled: () -> Boolean = { false },
        maxBytes: (String) -> Long): Map<String, ByteArray> {
        v2require(bytes.size.toLong() in 22..BackupV2Limits.TOTAL_BYTES, "LIMIT_EXCEEDED", "compressed ZIP size")
        val snapshot = bytes.copyOf()
        val expected = preflight(snapshot, expandedLimit, entryLimit, maxBytes).associateBy { it.name }
        val contents = linkedMapOf<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(snapshot), Charsets.US_ASCII).use { zip ->
                while (true) {
                    cancellation(cancelled)
                    val entry = zip.nextEntry ?: break
                    val header = expected[entry.name] ?: throw BackupV2Exception("INVALID_ARCHIVE", "unlisted local header")
                    v2require(!contents.containsKey(entry.name), "DUPLICATE_PATH", "duplicate local name")
                    val crc = CRC32()
                    val limit = minOf(header.size, maxBytes(entry.name))
                    val output = ByteArrayOutputStream(minOf(header.size, 8192L).toInt())
                    val buffer = ByteArray(8192); var count = 0L
                    while (true) {
                        cancellation(cancelled)
                        val read = zip.read(buffer); if (read < 0) break; if (read == 0) continue
                        count += read.toLong(); total += read.toLong()
                        v2require(count <= limit && total <= expandedLimit, "LIMIT_EXCEEDED", "expanded ZIP bytes")
                        crc.update(buffer, 0, read); output.write(buffer, 0, read)
                    }
                    zip.closeEntry()
                    v2require(count == header.size && crc.value == header.crc && entry.size == header.size && entry.compressedSize == header.compressed && entry.crc == header.crc, "CRC_MISMATCH", "local/central size/CRC")
                    contents[entry.name] = output.toByteArray()
                }
            }
        } catch (e: BackupV2Exception) { throw e }
        catch (e: IOException) { throw BackupV2Exception("CORRUPT_ARCHIVE", "ZIP stream invalid", e) }
        v2require(contents.keys == expected.keys, "INVALID_ARCHIVE", "missing local entries")
        cancellation(cancelled)
        return contents
    }
    fun extract(bytes: ByteArray, privateRoot: File, outputName: String = "backup-${UUID.randomUUID()}", lockTimeoutMs: Long = 5000, cancelled: () -> Boolean = { false }): BackupV2Published =
        publish(readSnapshot(bytes, cancelled), privateRoot, outputName, lockTimeoutMs, cancelled)

    /** Private root must exclude non-cooperating writers. JVM + OS locks coordinate all cooperating archive instances/processes. */
    fun publish(snapshot: BackupV2ArchiveSnapshot, privateRoot: File, outputName: String = "backup-${UUID.randomUUID()}", lockTimeoutMs: Long = 5000, cancelled: () -> Boolean = { false }): BackupV2Published {
        v2require(lockTimeoutMs in 0..60_000, "INVALID_ARGUMENT", "lock timeout range")
        v2require(outputName.length <= 160 && !outputName.contains('/') && BackupV2Codec.safePath(outputName), "INVALID_PATH", "controlled direct child name required")
        val root = privateRoot.absoluteFile
        v2require(sameCanonical(root), "SYMLINK_FORBIDDEN", "private root contains alias/link")
        if (!root.exists()) v2require(root.mkdirs() || root.isDirectory, "IO_FAILURE", "cannot create private root")
        v2require(root.isDirectory && sameCanonical(root), "INVALID_ARGUMENT", "private root must be ordinary directory")
        val lock = rootLocks.getOrPut(root.canonicalPath) { ReentrantLock() }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(lockTimeoutMs)
        var acquired = false
        try {
            do {
                cancellation(cancelled); acquired = lock.tryLock()
                if (!acquired && System.nanoTime() < deadline) Thread.sleep(10)
            } while (!acquired && System.nanoTime() < deadline)
            v2require(acquired, "LOCK_TIMEOUT", "private root JVM lock busy")
            val lockFile = File(root, ".backup-v2.publish.lock")
            v2require(sameCanonical(lockFile) && (!lockFile.exists() || lockFile.isFile), "SYMLINK_FORBIDDEN", "invalid OS lock file")
            RandomAccessFile(lockFile, "rw").use { handle ->
                var osLock: FileLock? = null
                try {
                    do {
                        cancellation(cancelled)
                        osLock = try { handle.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                        if (osLock == null && System.nanoTime() < deadline) Thread.sleep(10)
                    } while (osLock == null && System.nanoTime() < deadline)
                    v2require(osLock != null, "LOCK_TIMEOUT", "private root OS lock busy")
                    return publishLocked(snapshot, root, outputName, cancelled)
                } finally { osLock?.release() }
            }
        } catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw BackupV2Exception("CANCELLED", "interrupted", e) }
        finally { if (acquired) lock.unlock() }
    }
    private fun publishLocked(snapshot: BackupV2ArchiveSnapshot, root: File, name: String, cancelled: () -> Boolean): BackupV2Published {
        val target = File(root, name)
        boundary(target, root)
        v2require(!target.exists(), "DESTINATION_EXISTS", "refuse replacing existing child")
        val staging = File(root, ".backup-v2-${UUID.randomUUID()}.staging")
        boundary(staging, root)
        v2require(!staging.exists() && staging.mkdir(), "IO_FAILURE", "cannot create unique staging")
        var published = false
        try {
            snapshot.paths.sorted().forEach { path ->
                cancellation(cancelled)
                v2require(BackupV2Codec.safePath(path), "INVALID_PATH", path)
                val file = File(staging, path.replace('/', File.separatorChar)); boundary(file, staging)
                val parent = file.parentFile!!
                if (!parent.exists()) v2require(parent.mkdirs(), "IO_FAILURE", "cannot create private resource directory")
                boundary(file, staging)
                v2require(!file.exists(), "DESTINATION_EXISTS", "staging entry already exists")
                FileOutputStream(file).use { stream -> stream.write(snapshot.fileBytes(path)); stream.flush(); stream.fd.sync() }
            }
            cancellation(cancelled)
            boundary(root, root.parentFile!!); boundary(staging, root); boundary(target, root)
            v2require(!target.exists(), "DESTINATION_EXISTS", "destination appeared under lock")
            v2require(staging.renameTo(target), "ATOMIC_PUBLISH_FAILED", "same-parent rename failed; no copy fallback")
            published = true
            return BackupV2Published(target, snapshot.backup)
        } finally { if (!published) removeOwnedStaging(staging, root) }
    }
    private fun removeOwnedStaging(staging: File, root: File) {
        boundary(staging, root)
        v2require(staging.name.startsWith(".backup-v2-") && staging.name.endsWith(".staging"), "INVALID_PATH", "not owned staging")
        if (staging.exists()) {
            val files = staging.walkBottomUp().toList()
            files.forEach { file -> if (file != staging) boundary(file, staging)
                v2require(file.delete() || !file.exists(), "CLEANUP_FAILED", "owned staging could not be removed") }
        }
    }
    private fun boundary(file: File, parent: File) {
        v2require(sameCanonical(file) && file.canonicalPath.startsWith(parent.canonicalPath + File.separator), "SYMLINK_FORBIDDEN", "private path escaped or linked")
    }
    private fun sameCanonical(file: File): Boolean = file.absoluteFile.path == file.canonicalFile.path
    private fun cancellation(cancelled: () -> Boolean) { v2require(!cancelled() && !Thread.currentThread().isInterrupted, "CANCELLED", "archive cancelled") }
    private fun boundedRead(input: InputStream, limit: Long, cancelled: () -> Boolean): ByteArray {
        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192); var count = 0L
        while (true) { cancellation(cancelled); val read = input.read(buffer); if (read < 0) break; if (read == 0) continue
            count += read.toLong(); v2require(count <= limit, "LIMIT_EXCEEDED", "input size"); output.write(buffer, 0, read) }
        return output.toByteArray()
    }
    private fun preflight(bytes: ByteArray, expandedLimit: Long, entryLimit: Int, maxBytes: (String) -> Long): List<Header> {
        val end = (bytes.size - 22 downTo maxOf(0, bytes.size - 65_557)).firstOrNull { offset ->
            u32(bytes, offset) == 0x06054b50L && offset.toLong() + 22 + u16(bytes, offset + 20) == bytes.size.toLong()
        } ?: throw BackupV2Exception("INVALID_ARCHIVE", "ZIP end record absent")
        v2require(u16(bytes, end + 4) == 0 && u16(bytes, end + 6) == 0 && u16(bytes, end + 8) == u16(bytes, end + 10), "INVALID_ARCHIVE", "multi-disk ZIP")
        val count = u16(bytes, end + 10); val centralSize = u32(bytes, end + 12); val centralOffset = u32(bytes, end + 16)
        v2require(count in 1..entryLimit && centralOffset + centralSize == end.toLong(), "LIMIT_EXCEEDED", "central count/bounds")
        val result = ArrayList<Header>(count); val names = hashSetOf<String>(); var total = 0L; var cursor = centralOffset
        repeat(count) {
            v2require(cursor + 46 <= end, "INVALID_ARCHIVE", "truncated central header")
            val pos = cursor.toInt(); v2require(u32(bytes, pos) == 0x02014b50L, "INVALID_ARCHIVE", "central signature")
            val flags = u16(bytes, pos + 8); val method = u16(bytes, pos + 10)
            val compressed = u32(bytes, pos + 20); val size = u32(bytes, pos + 24)
            val nameLen = u16(bytes, pos + 28); val extraLen = u16(bytes, pos + 30); val commentLen = u16(bytes, pos + 32)
            val mode = ((u32(bytes, pos + 38) ushr 16) and 0xf000).toInt(); val local = u32(bytes, pos + 42)
            v2require(flags and 0xf7f7 == 0 && method in setOf(0, 8) && mode in setOf(0, 0x8000) && u16(bytes, pos + 34) == 0, "INVALID_ARCHIVE", "unsupported/encrypted/special ZIP entry")
            v2require(nameLen in 1..1024 && cursor + 46 + nameLen + extraLen + commentLen <= end && local < centralOffset && compressed != 0xffffffffL && size != 0xffffffffL, "INVALID_ARCHIVE", "ZIP64/entry bounds")
            cursor += 46
            val raw = bytes.copyOfRange(cursor.toInt(), cursor.toInt() + nameLen)
            v2require(raw.all { it.toInt() in 0..127 }, "INVALID_PATH", "ASCII ZIP paths required")
            val name = raw.toString(Charsets.US_ASCII)
            v2require(BackupV2Codec.safePath(name), "INVALID_PATH", name)
            v2require(names.add(name.lowercase(Locale.ROOT)), "DUPLICATE_PATH", name)
            v2require(size <= maxBytes(name), "LIMIT_EXCEEDED", "declared single entry")
            total += size; v2require(total <= expandedLimit, "LIMIT_EXCEEDED", "declared total bytes")
            cursor += nameLen + extraLen + commentLen
            result += Header(name, size, compressed, u32(bytes, pos + 16), local, flags, method)
        }
        v2require(cursor == end.toLong(), "INVALID_ARCHIVE", "central trailing data")
        var localEnd = 0L
        result.sortedBy { it.local }.forEach { h ->
            val p = h.local.toInt()
            v2require(h.local == localEnd && h.local + 30 <= centralOffset, "INVALID_ARCHIVE", "local offsets/gaps/overlap")
            v2require(u32(bytes, p) == 0x04034b50L && u16(bytes, p + 6) == h.flags && u16(bytes, p + 8) == h.method, "INVALID_ARCHIVE", "local/central header mismatch")
            val n = u16(bytes, p + 26); val extra = u16(bytes, p + 28)
            val data = h.local + 30 + n + extra
            v2require(data + h.compressed <= centralOffset && n == h.name.length, "INVALID_ARCHIVE", "local entry bounds")
            v2require(bytes.copyOfRange(p + 30, p + 30 + n).toString(Charsets.US_ASCII) == h.name, "INVALID_ARCHIVE", "local path mismatch")
            localEnd = data + h.compressed
            if (h.flags and 8 != 0) {
                v2require(localEnd + 12 <= centralOffset, "INVALID_ARCHIVE", "missing data descriptor")
                if (u32(bytes, localEnd.toInt()) == 0x08074b50L) localEnd += 4
                v2require(localEnd + 12 <= centralOffset && u32(bytes, localEnd.toInt()) == h.crc && u32(bytes, localEnd.toInt() + 4) == h.compressed && u32(bytes, localEnd.toInt() + 8) == h.size, "CRC_MISMATCH", "data descriptor")
                localEnd += 12
            } else v2require(u32(bytes, p + 14) == h.crc && u32(bytes, p + 18) == h.compressed && u32(bytes, p + 22) == h.size, "CRC_MISMATCH", "local metadata")
        }
        v2require(localEnd == centralOffset, "INVALID_ARCHIVE", "local data not complete")
        return result
    }
    private fun u16(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
    private fun u32(bytes: ByteArray, offset: Int): Long = u16(bytes, offset).toLong() or (u16(bytes, offset + 2).toLong() shl 16)
}
