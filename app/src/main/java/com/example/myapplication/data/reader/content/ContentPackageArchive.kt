package com.example.myapplication.data.reader.content

import java.io.File
import java.io.ByteArrayInputStream
import java.util.zip.CRC32
import java.util.zip.ZipInputStream

data class ContentPackageArchiveInput(
    val manifestBytes: ByteArray,
    val resources: Map<String, ByteArray>,
)

/** Reads a bounded candidate in memory; no entry is ever extracted or published. */
object ContentPackageArchive {
    private const val MAX_TOTAL = 256L * 1024 * 1024
    private const val MAX_FILE = 8L * 1024 * 1024
    private const val MAX_MANIFEST = 1024L * 1024
    private const val MAX_ENTRIES = 10_000

    fun read(file: File): ContentPackageArchiveInput {
        require(file.isFile && file.length() in 22..MAX_TOTAL) { "archive: invalid compressed size" }
        // Both central metadata and local entry streams consume the same private snapshot.
        val snapshot = file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var count = 0L
            while (true) {
                val length = input.read(buffer)
                if (length < 0) break
                if (length == 0) continue
                count += length
                require(count <= MAX_TOTAL) { "archive: compressed size limit exceeded" }
                output.write(buffer, 0, length)
            }
            output.toByteArray()
        }
        return read(snapshot)
    }

    /** Central and local headers always consume an independent snapshot of the same bounded bytes. */
    fun read(bytes: ByteArray): ContentPackageArchiveInput {
        require(bytes.size.toLong() in 22..MAX_TOTAL) { "archive: invalid compressed size" }
        val snapshot = bytes.copyOf()
        val expected = preflight(snapshot).associateBy { it.name }
        val contents = linkedMapOf<String, ByteArray>()
        var actualTotal = 0L
        val seenLocal = hashSetOf<String>()
        ZipInputStream(ByteArrayInputStream(snapshot), Charsets.US_ASCII).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val header = requireNotNull(expected[entry.name]) { "archive: local entry absent from central directory" }
                require(seenLocal.add(entry.name)) { "archive: duplicate local entry" }
                if (entry.isDirectory) {
                    require(zip.read() == -1) { "archive: directory contains data" }
                    zip.closeEntry()
                    require(entry.size == 0L && entry.compressedSize == header.compressedSize && entry.crc == header.crc) { "archive: inconsistent directory header" }
                    continue
                }
                val limit = if (entry.name == "manifest.json") MAX_MANIFEST else MAX_FILE
                val crc = CRC32()
                val bytes = run {
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var count = 0L
                    while (true) {
                        val length = zip.read(buffer)
                        if (length < 0) break
                        if (length == 0) continue
                        count += length
                        actualTotal += length
                        require(count <= limit && actualTotal <= MAX_TOTAL) { "${entry.name}: expanded size limit exceeded" }
                        crc.update(buffer, 0, length)
                        output.write(buffer, 0, length)
                    }
                    require(count == header.size && crc.value == header.crc) { "${entry.name}: size/CRC mismatch" }
                    output.toByteArray()
                }
                zip.closeEntry()
                require(entry.size == header.size && entry.compressedSize == header.compressedSize && entry.crc == header.crc) { "archive: local/central metadata mismatch" }
                contents[entry.name] = bytes
            }
        }
        require(seenLocal.size == expected.size) { "archive: missing local entries" }
        val manifest = requireNotNull(contents.remove("manifest.json")) { "manifest.json: missing root manifest" }
        require(manifest.isNotEmpty()) { "manifest.json: empty manifest" }
        return ContentPackageArchiveInput(manifest, contents.toMap())
    }

    private data class Header(val name: String, val size: Long, val compressedSize: Long, val crc: Long)

    private fun preflight(snapshot: ByteArray): List<Header> {
        val length = snapshot.size.toLong()
        val tailStart = maxOf(0L, length - 65_557)
        val tail = snapshot.copyOfRange(tailStart.toInt(), snapshot.size)
        val end = (tail.size - 22 downTo 0).firstOrNull { offset ->
            u32(tail, offset) == 0x06054b50L && offset + 22 + u16(tail, offset + 20) == tail.size
        } ?: throw IllegalArgumentException("archive: missing ZIP end record")
        require(u16(tail, end + 4) == 0 && u16(tail, end + 6) == 0 &&
            u16(tail, end + 8) == u16(tail, end + 10)) { "archive: multi-disk ZIP is unsupported" }
        val count = u16(tail, end + 10)
        val centralSize = u32(tail, end + 12)
        val centralOffset = u32(tail, end + 16)
        require(count in 1..MAX_ENTRIES && centralSize != 0xffffffffL && centralOffset != 0xffffffffL) {
            "archive: entry limit exceeded or ZIP64 is unsupported"
        }
        require(centralOffset + centralSize == tailStart + end) { "archive: inconsistent central directory bounds" }
        var cursor = centralOffset.toInt()
        val seen = hashSetOf<String>()
        var total = 0L
        val result = ArrayList<Header>(count)
        repeat(count) {
            require(cursor + 46L <= centralOffset + centralSize) { "archive: truncated central header" }
            val header = snapshot.copyOfRange(cursor, cursor + 46)
            cursor += 46
            require(u32(header, 0) == 0x02014b50L) { "archive: invalid central signature" }
            val flags = u16(header, 8)
            val method = u16(header, 10)
            require(flags and 0x41 == 0 && method in listOf(0, 8)) { "archive: encrypted/unsupported entry" }
            val compressed = u32(header, 20)
            val expanded = u32(header, 24)
            val nameLength = u16(header, 28)
            val extraLength = u16(header, 30)
            val commentLength = u16(header, 32)
            val mode = (u32(header, 38) ushr 16).toInt() and 0xf000
            require(mode != 0xa000 && mode in listOf(0, 0x8000, 0x4000)) { "archive: symbolic links/special entries are forbidden" }
            require(u16(header, 34) == 0 && compressed != 0xffffffffL && expanded != 0xffffffffL &&
                u32(header, 42) < centralOffset) { "archive: ZIP64/multi-disk/invalid local offset" }
            require(nameLength in 1..1024 && cursor.toLong() + nameLength + extraLength + commentLength <= centralOffset + centralSize) {
                "archive: invalid entry name/metadata length"
            }
            val rawName = snapshot.copyOfRange(cursor, cursor + nameLength)
            cursor += nameLength
            require(rawName.all { it.toInt() in 0..127 }) { "archive: entry paths must be ASCII" }
            val name = rawName.toString(Charsets.US_ASCII)
            val path = name.removeSuffix("/")
            require(path.matches(Regex("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")) &&
                path.split('/').none { it == "." || it == ".." }) { "$name: unsafe entry path" }
            require(seen.add(path.lowercase(java.util.Locale.ROOT))) { "$name: duplicate/case-colliding entry" }
            if (name.endsWith('/')) {
                require(expanded == 0L && mode != 0x8000) { "$name: invalid directory entry" }
            } else {
                require(mode != 0x4000 && expanded <= if (name == "manifest.json") MAX_MANIFEST else MAX_FILE) { "$name: entry size/type limit exceeded" }
            }
            total += expanded
            require(total <= MAX_TOTAL) { "archive: expanded package limit exceeded" }
            cursor += extraLength + commentLength
            result += Header(name, expanded, compressed, u32(header, 16))
        }
        require(cursor.toLong() == centralOffset + centralSize) { "archive: unexpected central directory data" }
        require(result.any { it.name == "manifest.json" }) { "manifest.json: missing root manifest" }
        return result
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        u16(bytes, offset).toLong() or (u16(bytes, offset + 2).toLong() shl 16)
}
