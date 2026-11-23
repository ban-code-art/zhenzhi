package com.example.myapplication.data.reader.importer

import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 归档容器的统一读取口径（P5-01/P5-02 共用）。
 *
 * 为什么单独抽出来：EPUB/FB2.zip/CBZ/MHTML 都是容器格式，每个适配器各自实现一遍
 * 「条目数上限 + 单条目上限 + 总解压上限 + 路径穿越 + 压缩比」很容易漏掉其中一项，
 * 而漏掉的那一项就是 ZIP 炸弹 / 目录穿越。这里给一份**带默认上限**的实现，适配器
 * 只表达"要读哪个条目"，安全边界统一在此。
 */
object ArchiveLimits {
    /** 条目数上限：正常电子书远小于此值。 */
    const val MAX_ENTRIES = 10_000

    /** 单条目解压上限（默认 32 MiB，与 EPUB 一期口径一致）。 */
    const val MAX_ENTRY_BYTES = 32L * 1024 * 1024

    /** 整包解压上限。 */
    const val MAX_TOTAL_BYTES = 512L * 1024 * 1024

    /**
     * 压缩比上限：声明的解压体积 / 压缩体积 超过该倍数视为炸弹。
     * 正常文本约 2–6 倍，图片（已压缩）接近 1 倍，200 倍只可能是刻意构造。
     */
    const val MAX_COMPRESSION_RATIO = 200L
}

class ArchiveSupport(
    private val maxEntries: Int = ArchiveLimits.MAX_ENTRIES,
    private val maxEntryBytes: Long = ArchiveLimits.MAX_ENTRY_BYTES,
    private val maxTotalBytes: Long = ArchiveLimits.MAX_TOTAL_BYTES,
) {
    /**
     * 列出全部文件条目（不含目录），并完成条目数与路径合法性校验。
     * 只读中央目录，不解压任何内容。
     */
    fun listFiles(zip: ZipFile): List<ZipEntry> {
        val entries = mutableListOf<ZipEntry>()
        val iterator = zip.entries()
        var count = 0
        while (iterator.hasMoreElements()) {
            val entry = iterator.nextElement()
            count += 1
            if (count > maxEntries) {
                throw DocumentParseException("LIMIT_EXCEEDED", "压缩包条目过多（上限 $maxEntries）")
            }
            if (entry.isDirectory) continue
            normalizeName(entry.name)
            entries += entry
        }
        return entries
    }

    /** 读取单个条目，超过 [maxEntryBytes] 立刻失败（不是截断）。 */
    fun readEntry(zip: ZipFile, entry: ZipEntry, maxBytes: Long = maxEntryBytes): ByteArray {
        val declared = entry.size
        if (declared > maxBytes) {
            throw DocumentParseException(
                "LIMIT_EXCEEDED",
                "条目 ${entry.name} 声明体积 ${declared} 字节，超过上限 $maxBytes",
            )
        }
        val compressed = entry.compressedSize
        if (declared > 0 && compressed > 0 && declared / compressed > ArchiveLimits.MAX_COMPRESSION_RATIO) {
            throw DocumentParseException(
                "LIMIT_EXCEEDED",
                "条目 ${entry.name} 压缩比异常（${declared}/${compressed}），疑似压缩炸弹",
            )
        }
        return zip.getInputStream(entry).use { input -> readAtMost(input, maxBytes, entry.name) }
    }

    /** 整包累计读取：调用方把每次读到的字节数累加进来，超限即失败。 */
    fun accumulate(total: Long, add: Int): Long {
        val next = total + add
        if (next > maxTotalBytes) {
            throw DocumentParseException("LIMIT_EXCEEDED", "解压内容超过整包上限 $maxTotalBytes 字节")
        }
        return next
    }

    private fun readAtMost(input: InputStream, maxBytes: Long, name: String): ByteArray {
        val buffer = ByteArray(8 * 1024)
        val out = java.io.ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw DocumentParseException("LIMIT_EXCEEDED", "条目 $name 实际解压超过上限 $maxBytes 字节")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    companion object {
        /**
         * 归一化条目路径并阻断穿越：反斜杠转正斜杠、去掉 `./`，拒绝绝对路径、
         * 盘符和任何 `..` 段。返回可直接用于写盘的相对路径。
         */
        fun normalizeName(rawName: String): String {
            val name = rawName.replace('\\', '/')
            if (name.startsWith("/")) {
                throw DocumentParseException("CORRUPT_INPUT", "压缩包含绝对路径条目: $rawName")
            }
            if (name.length >= 2 && name[1] == ':') {
                throw DocumentParseException("CORRUPT_INPUT", "压缩包含盘符路径条目: $rawName")
            }
            val segments = name.split('/').filter { it.isNotEmpty() && it != "." }
            if (segments.any { it == ".." }) {
                throw DocumentParseException("CORRUPT_INPUT", "压缩包含路径穿越条目: $rawName")
            }
            if (segments.isEmpty()) {
                throw DocumentParseException("CORRUPT_INPUT", "压缩包含空路径条目: $rawName")
            }
            return segments.joinToString("/")
        }

        /**
         * 漫画页自然排序：`page2` 必须排在 `page10` 前面。
         * 纯数字段按数值比较，其它字符按小写比较（结果稳定，不依赖 Locale）。
         */
        fun naturalCompare(left: String, right: String): Int {
            var i = 0
            var j = 0
            while (i < left.length && j < right.length) {
                val lc = left[i]
                val rc = right[j]
                val lDigit = lc.isDigit()
                val rDigit = rc.isDigit()
                if (lDigit && rDigit) {
                    var iEnd = i
                    while (iEnd < left.length && left[iEnd].isDigit()) iEnd++
                    var jEnd = j
                    while (jEnd < right.length && right[jEnd].isDigit()) jEnd++
                    val lNum = left.substring(i, iEnd).trimStart('0').ifBlank { "0" }
                    val rNum = right.substring(j, jEnd).trimStart('0').ifBlank { "0" }
                    val byValue = if (lNum.length != rNum.length) {
                        lNum.length - rNum.length
                    } else {
                        lNum.compareTo(rNum)
                    }
                    if (byValue != 0) return byValue
                    i = iEnd
                    j = jEnd
                } else {
                    val byChar = lc.lowercaseChar().compareTo(rc.lowercaseChar())
                    if (byChar != 0) return byChar
                    i++
                    j++
                }
            }
            return (left.length - i) - (right.length - j)
        }
    }
}

/** 判断一个文件是否是 ZIP 容器（只看魔数，不看扩展名）。 */
fun File.looksLikeZip(): Boolean {
    if (!isFile || length() < 4) return false
    val head = ByteArray(4)
    val read = inputStream().use { it.read(head) }
    if (read < 4) return false
    return head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
        ((head[2] == 0x03.toByte() && head[3] == 0x04.toByte()) ||
            (head[2] == 0x05.toByte() && head[3] == 0x06.toByte()))
}
