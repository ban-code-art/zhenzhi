package com.example.myapplication.data.reader.importer.kf8

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * KF8（AZW3）索引层：FDST 流切分 + INDX（TAGX / IDXT / CNCX）解析 + skeleton/fragment 拼接。
 *
 * 为什么单独一层：MOBI7 的正文就是一段连续的 HTML，解压完直接切节即可；
 * KF8 的 flow 0 却是"骨架 + 片段"两段结构——骨架（skeleton）只给出每份 XHTML 的框架，
 * 每个骨架里真正的内容被拆成若干片段（fragment）存放在 flow 尾部，再按插入位置塞回去。
 * 只解压不重组的结果就是"正文缺段 + 顺序错"，所以必须走这一层。
 *
 * 规范来源：KindleUnpack `mobi_header.py` / `mobi_index.py` / `mobi_k8proc.py`。
 * 与 KindleUnpack 的差异都在各函数 KDoc 里写明（主要是 INDX 头长度的基准点、
 * INDX 头之后 TAGX 的定位方式，以及 tag 号用 1 字节读取）。
 *
 * 安全口径：所有长度/偏移先校验再分配。记录数 ≤ 4096、条目数 ≤ 100000、
 * 单条切片 ≤ 16 MiB、总切片 ≤ 64 MiB，越界一律抛 [Kf8IndexException] 并带固定错误码，
 * 绝不因为一个坏文件把内存打爆。
 */
class Kf8IndexException(val code: String, message: String) : Exception("$code: $message")

/**
 * FDST（流描述表）解析。
 *
 * 记录布局：`"FDST"` + 4 字节保留 + `u32 count`（偏移 8）+ `count` 组
 * `(u32 start, u32 end)`（从偏移 12 起），区间指向**解压后正文**的字节位置。
 * flow 0 是"骨架 + 片段"的拼接体，flow 1..n 通常是 CSS / SVG，不属于正文。
 */
object Kf8Fdst {

    /** 一条 flow 在解压后正文里的字节区间 `[start, end)`。 */
    data class Flow(val start: Int, val end: Int) {
        val length: Int get() = end - start
    }

    /**
     * 解析结果。[reason] 为 null 表示成功，否则是固定失败码（不含任何正文内容）：
     * - `FDST_TRUNCATED`：记录不足 12 字节，或声明 count 的区间表被截断；
     * - `FDST_MAGIC_MISMATCH`：偏移 0 不是 `"FDST"`；
     * - `FDST_COUNT_INVALID`：count 不在 1..[MAX_FLOWS]；
     * - `FDST_RANGE_INVALID`：某条 start ≥ end、end 超出正文长度、或出现无符号溢出；
     * - `FDST_ORDER_INVALID`：start 出现倒退（必须单调不减）；
     * - `LIMIT_EXCEEDED`：所有 flow 的长度总和超过 [MAX_TOTAL_BYTES]。
     */
    data class Result(val flows: List<Flow>, val reason: String?) {
        val ok: Boolean get() = reason == null
    }

    /** 单条记录的 flow 数量上限：真实 AZW3 一般 2–5 条，64 已经足够宽松。 */
    const val MAX_FLOWS = 64

    /** flow 总跨度上限，与正文解压上限（64 MiB）同量级。 */
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    private const val HEADER_BYTES = 12
    private const val FLOW_ENTRY_BYTES = 8

    /**
     * 解析 FDST 记录。
     *
     * [textLength] 是解压后正文的字节长度，用来卡住 `end > textLength` 的越界区间；
     * 传 0 或负数按"正文不可用"处理，直接返回 `FDST_RANGE_INVALID`。
     */
    fun parse(record: ByteArray, textLength: Int): Result {
        if (textLength <= 0) return fail("FDST_RANGE_INVALID")
        if (record.size < HEADER_BYTES) return fail("FDST_TRUNCATED")
        if (!matchesAscii(record, 0, "FDST")) return fail("FDST_MAGIC_MISMATCH")
        val count = u32(record, 8)
        if (count !in 1..MAX_FLOWS) return fail("FDST_COUNT_INVALID")
        val tableEnd = HEADER_BYTES + count * FLOW_ENTRY_BYTES
        if (tableEnd > record.size) return fail("FDST_TRUNCATED")

        val flows = ArrayList<Flow>(count)
        var previousStart = -1
        var total = 0L
        for (index in 0 until count) {
            val base = HEADER_BYTES + index * FLOW_ENTRY_BYTES
            val start = u32(record, base)
            val end = u32(record, base + 4)
            // u32 读进 Int 后可能变负：那是 >0x7FFFFFFF 的无符号值，按越界处理。
            if (start < 0 || end < 0) return fail("FDST_RANGE_INVALID")
            if (start >= end) return fail("FDST_RANGE_INVALID")
            if (end > textLength) return fail("FDST_RANGE_INVALID")
            if (start < previousStart) return fail("FDST_ORDER_INVALID")
            previousStart = start
            total += (end - start).toLong()
            if (total > MAX_TOTAL_BYTES) return fail("LIMIT_EXCEEDED")
            flows += Flow(start, end)
        }
        return Result(flows, null)
    }

    private fun fail(reason: String): Result = Result(emptyList(), reason)

    private fun u32(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > bytes.size) return -1
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun matchesAscii(bytes: ByteArray, offset: Int, text: String): Boolean {
        if (offset < 0 || offset + text.length > bytes.size) return false
        for (index in text.indices) {
            if (bytes[offset + index] != text[index].code.toByte()) return false
        }
        return true
    }
}

/** KF8 两张索引表的对外数据结构（解析结果只有偏移与长度，不含正文）。 */
object Kf8Tables {

    /** 一条骨架：名称（CNCX 文本）、在 flow 0 里的起点与长度。 */
    data class SkeletonEntry(
        val name: String,
        val start: Int,
        val length: Int,
    )

    /** 一条片段：插入位置（相对所属骨架起点）、文件号、序号、在 flow 0 里的起点与长度。 */
    data class FragmentEntry(
        val insertPosition: Int,
        val fileNumber: Int,
        val sequence: Int,
        val start: Int,
        val length: Int,
    )

    data class Skeleton(val entries: List<SkeletonEntry>)

    data class Fragment(val entries: List<FragmentEntry>)
}

/**
 * KindleUnpack 风格的 INDX 记录解析器。
 *
 * 记录布局（本实现的口径，逐条对应 KindleUnpack `mobi_index.py` 的读法）：
 * - 偏移 0：`"INDX"`；
 * - 偏移 4：`u32 headerLength`（含自身，即从记录起点算的头长度）；
 * - 偏移 0x14：`u32 IDXT 偏移`；偏移 0x18：`u32 indexCount`（本记录的条目数）；
 * - 头之后（headerLength 处）以 `"TAGX"` 开头时是标签表：偏移 4 是 TAGX 段总长
 *   （含 12 字节头），偏移 8 是控制字节数量；随后每条 16 字节的标签定义：
 *   `u32 tag` / `u32 valuesPerEntry` / `u32 bitmask` / `u8 endFlag` / `u8 valueBytes`；
 * - TAGX 之后是条目区，直到 IDXT 块（`"IDXT"` + `u16 count` + `count * u16 偏移`，
 *   偏移相对本记录起点，指向每个条目 key 文本的 NUL 结尾字符串）；
 * - 条目 = 一个控制字节 + `控制字节 & 0x7F` 组 `(1 字节 tag 号, 按 TAGX 取宽度的值)`；
 *   控制字节 bit7 置位表示"同一个条目后面还有一段"（可多段累加）。
 *
 * 值宽度：`valueBytes` 为 1..4 时按大端定长无符号整数读；为 0 或 5..8 时按
 * KindleUnpack 的 forward 变长整数读（每字节 7 位，最高位为 1 表示最后一个字节）。
 *
 * 与 KindleUnpack 的差异：KindleUnpack 里 header 长度字段的基准点与 TAGX 定位在不同
 * 版本/不同索引里有 0x04 的漂移；本实现固定按"值含自身、TAGX 紧跟头之后"处理，
 * 并把猜测成本压到最低——猜错只会让整张表解析失败，由调用方回退到原文。
 */
class Kf8IndxParser {

    /**
     * 解析 skeleton 表。
     *
     * [records] 是连续的若干条 PalmDB 记录：第一条以 `"INDX"` 开头的是索引头，
     * 紧随其后不以 `"INDX"` 开头的那条按 CNCX 文本记录处理（跳过 16 字节头），
     * 其余 `"INDX"` 记录按数据记录逐条解析条目。
     */
    fun readSkeletonTable(records: List<ByteArray>): Kf8Tables.Skeleton {
        val index = parseIndex(records)
        val nameTag = requireTag(index.tagMap, TAG_SKELETON_NAME, "skeleton 名称")
        val extentTag = requireTag(index.tagMap, TAG_EXTENT, "skeleton 起点/长度")
        var total = 0L
        val entries = ArrayList<Kf8Tables.SkeletonEntry>(index.entries.size)
        index.entries.forEach { entry ->
            val nameOffset = entry.values[nameTag.tag]?.firstOrNull()
                ?: throw Kf8IndexException(
                    "KF8_SKELETON_NAME_MISSING",
                    "skeleton 条目缺少 tag ${nameTag.tag}（名称在 CNCX 里的偏移）",
                )
            val extent = entry.values[extentTag.tag]
            if (extent == null || extent.size < 2) {
                throw Kf8IndexException(
                    "KF8_SKELETON_EXTENT_MISSING",
                    "skeleton 条目缺少 tag ${extentTag.tag} 的起点/长度",
                )
            }
            total += validateExtent(extent[0], extent[1], "skeleton", total)
            entries += Kf8Tables.SkeletonEntry(
                name = cncxString(index.cncx, nameOffset),
                start = extent[0],
                length = extent[1],
            )
        }
        return Kf8Tables.Skeleton(entries)
    }

    /**
     * 解析 fragment 表。
     *
     * 条目字段：插入位置取自条目 key 文本（十进制整数），文件号取 tagMap[3]，
     * 序号取 tagMap[4]，起点/长度取 tagMap[6] 的两个值。
     */
    fun readFragmentTable(records: List<ByteArray>): Kf8Tables.Fragment {
        val index = parseIndex(records)
        val fileTag = requireTag(index.tagMap, TAG_FILE_NUMBER, "fragment 文件号")
        val sequenceTag = requireTag(index.tagMap, TAG_SEQUENCE, "fragment 序号")
        val extentTag = requireTag(index.tagMap, TAG_EXTENT, "fragment 起点/长度")
        var total = 0L
        val entries = ArrayList<Kf8Tables.FragmentEntry>(index.entries.size)
        index.entries.forEach { entry ->
            val key = entry.key
            val insertPosition = key?.trim()?.toIntOrNull()
                ?: throw Kf8IndexException(
                    "KF8_FRAGMENT_KEY_INVALID",
                    "fragment 条目 key 不是十进制整数：${key ?: "<缺少 key 文本>"}",
                )
            if (insertPosition < 0) {
                throw Kf8IndexException(
                    "KF8_FRAGMENT_KEY_INVALID",
                    "fragment 条目 key 为负数：$insertPosition",
                )
            }
            val fileNumber = entry.values[fileTag.tag]?.firstOrNull()
                ?: throw Kf8IndexException(
                    "KF8_FRAGMENT_FILE_MISSING",
                    "fragment 条目缺少 tag ${fileTag.tag}（文件号）",
                )
            val sequence = entry.values[sequenceTag.tag]?.firstOrNull()
                ?: throw Kf8IndexException(
                    "KF8_FRAGMENT_SEQUENCE_MISSING",
                    "fragment 条目缺少 tag ${sequenceTag.tag}（序号）",
                )
            val extent = entry.values[extentTag.tag]
            if (extent == null || extent.size < 2) {
                throw Kf8IndexException(
                    "KF8_FRAGMENT_EXTENT_MISSING",
                    "fragment 条目缺少 tag ${extentTag.tag} 的起点/长度",
                )
            }
            total += validateExtent(extent[0], extent[1], "fragment", total)
            entries += Kf8Tables.FragmentEntry(
                insertPosition = insertPosition,
                fileNumber = fileNumber,
                sequence = sequence,
                start = extent[0],
                length = extent[1],
            )
        }
        return Kf8Tables.Fragment(entries)
    }

    // ---------------------------------------------------------------- 通用解析

    private data class TagDefinition(val tag: Int, val valuesPerEntry: Int, val valueBytes: Int)

    private data class Entry(val key: String?, val values: Map<Int, List<Int>>)

    private class ParsedIndex(
        val tagMap: Map<Int, TagDefinition>,
        val cncx: ByteArray,
        val entries: List<Entry>,
    )

    private data class IndxHeader(
        val headerLength: Int,
        val idxtOffset: Int,
        val indexCount: Int,
    )

    private fun parseIndex(records: List<ByteArray>): ParsedIndex {
        if (records.isEmpty()) {
            throw Kf8IndexException("KF8_INDX_MISSING", "INDX 记录列表为空")
        }
        if (records.size > MAX_RECORDS) {
            throw Kf8IndexException(
                "LIMIT_EXCEEDED",
                "INDX 记录数 ${records.size} 超过上限 $MAX_RECORDS",
            )
        }
        val mainIndex = records.indexOfFirst { matchesAscii(it, 0, INDX_MAGIC) }
        if (mainIndex < 0) {
            throw Kf8IndexException("KF8_INDX_MAGIC_MISMATCH", "记录里没有以 \"INDX\" 开头的索引头")
        }
        val mainHeader = parseHeader(records[mainIndex])
        val tagMap = parseTagx(records[mainIndex], mainHeader)
        // CNCX 紧跟在索引头之后，且不以 "INDX" 开头（KindleUnpack 跳过其 16 字节头）。
        val cncx = records.drop(mainIndex + 1)
            .firstOrNull { !matchesAscii(it, 0, INDX_MAGIC) }
            ?.let { cncxPayload(it) }
            ?: ByteArray(0)

        val entries = ArrayList<Entry>()
        records.forEach { record ->
            if (!matchesAscii(record, 0, INDX_MAGIC)) return@forEach
            val header = parseHeader(record)
            if (header.indexCount <= 0) return@forEach
            entries += parseEntries(record, header, tagMap)
            if (entries.size > MAX_ENTRIES) {
                throw Kf8IndexException(
                    "LIMIT_EXCEEDED",
                    "INDX 条目数 ${entries.size} 超过上限 $MAX_ENTRIES",
                )
            }
        }
        if (entries.isEmpty()) {
            throw Kf8IndexException("KF8_INDX_ENTRIES_EMPTY", "INDX 记录里没有可用条目")
        }
        return ParsedIndex(tagMap, cncx, entries)
    }

    private fun parseHeader(record: ByteArray): IndxHeader {
        if (record.size < INDX_HEADER_MIN_BYTES) {
            throw Kf8IndexException(
                "KF8_INDX_TRUNCATED",
                "INDX 记录只有 ${record.size} 字节，不足最小头长度 $INDX_HEADER_MIN_BYTES",
            )
        }
        if (!matchesAscii(record, 0, INDX_MAGIC)) {
            throw Kf8IndexException("KF8_INDX_MAGIC_MISMATCH", "记录不以 \"INDX\" 开头")
        }
        val headerLength = u32(record, INDX_HEADER_LENGTH_FIELD)
        if (headerLength < INDX_HEADER_MIN_BYTES || headerLength > record.size) {
            throw Kf8IndexException(
                "KF8_INDX_HEADER_LENGTH_INVALID",
                "INDX 头长度 $headerLength 不在 $INDX_HEADER_MIN_BYTES..${record.size}",
            )
        }
        val idxtOffset = u32(record, INDX_IDXT_OFFSET_FIELD)
        if (idxtOffset < 0 || idxtOffset > record.size) {
            throw Kf8IndexException(
                "KF8_IDXT_OFFSET_INVALID",
                "IDXT 偏移 $idxtOffset 不在 0..${record.size}",
            )
        }
        if (idxtOffset in 1 until headerLength) {
            throw Kf8IndexException(
                "KF8_IDXT_OFFSET_INVALID",
                "IDXT 偏移 $idxtOffset 落在 INDX 头内部（头长度 $headerLength）",
            )
        }
        val indexCount = u32(record, INDX_INDEX_COUNT_FIELD)
        if (indexCount < 0 || indexCount > MAX_ENTRIES) {
            throw Kf8IndexException(
                "KF8_INDEX_COUNT_INVALID",
                "indexCount=$indexCount 不在 0..$MAX_ENTRIES",
            )
        }
        return IndxHeader(headerLength, idxtOffset, indexCount)
    }

    private fun parseTagx(record: ByteArray, header: IndxHeader): Map<Int, TagDefinition> {
        val offset = header.headerLength
        if (offset + TAGX_HEADER_BYTES > record.size || !matchesAscii(record, offset, TAGX_MAGIC)) {
            throw Kf8IndexException(
                "KF8_TAGX_MISSING",
                "INDX 头之后（offset=$offset）没有 \"TAGX\" 段",
            )
        }
        val tagxLength = u32(record, offset + 4)
        if (tagxLength < TAGX_HEADER_BYTES || offset.toLong() + tagxLength > record.size) {
            throw Kf8IndexException(
                "KF8_TAGX_LENGTH_INVALID",
                "TAGX 长度 $tagxLength 越界（记录 ${record.size} 字节，起点 $offset）",
            )
        }
        val end = offset + tagxLength
        val map = LinkedHashMap<Int, TagDefinition>()
        var cursor = offset + TAGX_HEADER_BYTES
        while (cursor + TAG_DEFINITION_BYTES <= end) {
            val tag = u32(record, cursor)
            val valuesPerEntry = u32(record, cursor + 4)
            // cursor + 8 是 bitmask（原样保留在记录里，这里不参与判定），
            // cursor + 12 是 endFlag，cursor + 13 是值宽度。
            val valueBytes = record[cursor + 13].toInt() and 0xFF
            if (tag in 1..MAX_TAG_NUMBER && valuesPerEntry in 1..MAX_VALUES_PER_ENTRY) {
                map[tag] = TagDefinition(tag, valuesPerEntry, valueBytes)
            }
            cursor += TAG_DEFINITION_BYTES
        }
        if (map.isEmpty()) {
            throw Kf8IndexException("KF8_TAGX_EMPTY", "TAGX 里没有可用的标签定义")
        }
        return map
    }

    private fun parseEntries(
        record: ByteArray,
        header: IndxHeader,
        tagMap: Map<Int, TagDefinition>,
    ): List<Entry> {
        val firstTagOffset = entriesStartOffset(record, header)
        val end = if (header.idxtOffset > firstTagOffset) header.idxtOffset else record.size
        val idTexts = readIdTexts(record, header)
        val entries = ArrayList<Entry>(minOf(header.indexCount, START_CAPACITY))
        var cursor = firstTagOffset
        for (index in 0 until header.indexCount) {
            val values = LinkedHashMap<Int, MutableList<Int>>()
            var chunks = 0
            var more = true
            while (more) {
                if (cursor >= end) {
                    throw Kf8IndexException(
                        "KF8_ENTRY_TRUNCATED",
                        "条目 $index 在 offset=$cursor 处被截断（条目区结束于 $end）",
                    )
                }
                val control = record[cursor].toInt() and 0xFF
                cursor += 1
                repeat(control and CONTROL_TAG_MASK) {
                    if (cursor >= end) {
                        throw Kf8IndexException(
                            "KF8_ENTRY_TRUNCATED",
                            "条目 $index 的 tag 号被截断（offset=$cursor）",
                        )
                    }
                    val tag = record[cursor].toInt() and 0xFF
                    cursor += 1
                    val definition = tagMap[tag]
                        ?: throw Kf8IndexException(
                            "KF8_TAG_UNKNOWN",
                            "条目 $index 出现 TAGX 未定义的 tag=$tag",
                        )
                    val read = readValues(record, cursor, end, definition)
                    cursor = read.second
                    values.getOrPut(tag) { mutableListOf() } += read.first
                }
                more = control and CONTROL_CONTINUE_BIT != 0
                chunks += 1
                if (more && chunks >= MAX_CHUNKS_PER_ENTRY) {
                    throw Kf8IndexException(
                        "LIMIT_EXCEEDED",
                        "条目 $index 的控制字节段数超过上限 $MAX_CHUNKS_PER_ENTRY",
                    )
                }
            }
            entries += Entry(key = idTexts.getOrNull(index), values = values)
        }
        return entries
    }

    /** 条目区起点：INDX 头之后；若头之后紧跟 TAGX（数据记录偶见），跳过整段 TAGX。 */
    private fun entriesStartOffset(record: ByteArray, header: IndxHeader): Int {
        val offset = header.headerLength
        if (offset + TAGX_HEADER_BYTES <= record.size && matchesAscii(record, offset, TAGX_MAGIC)) {
            val tagxLength = u32(record, offset + 4)
            if (tagxLength >= TAGX_HEADER_BYTES && offset.toLong() + tagxLength <= record.size) {
                return offset + tagxLength
            }
        }
        return offset
    }

    private fun readValues(
        record: ByteArray,
        offset: Int,
        end: Int,
        definition: TagDefinition,
    ): Pair<List<Int>, Int> {
        val values = ArrayList<Int>(definition.valuesPerEntry)
        var cursor = offset
        repeat(definition.valuesPerEntry) {
            val read = readValue(record, cursor, end, definition.valueBytes)
            values += read.first
            cursor = read.second
        }
        return values to cursor
    }

    private fun readValue(record: ByteArray, offset: Int, end: Int, valueBytes: Int): Pair<Int, Int> {
        if (valueBytes in 1..4) {
            if (offset < 0 || offset + valueBytes > end) {
                throw Kf8IndexException(
                    "KF8_TAG_VALUE_TRUNCATED",
                    "定长 tag 值越界（offset=$offset，宽度=$valueBytes，条目区结束于 $end）",
                )
            }
            var value = 0
            for (index in 0 until valueBytes) {
                value = (value shl 8) or (record[offset + index].toInt() and 0xFF)
            }
            if (value < 0) {
                throw Kf8IndexException("KF8_TAG_VALUE_INVALID", "tag 无符号值超出 Int 范围")
            }
            return value to (offset + valueBytes)
        }
        // 0 与 5..8：KindleUnpack 的 forward 变长整数，末字节最高位为 1。
        var value = 0
        var cursor = offset
        var consumed = 0
        while (true) {
            if (cursor < 0 || cursor >= end) {
                throw Kf8IndexException(
                    "KF8_VARINT_TRUNCATED",
                    "变长整数被截断（offset=$offset，条目区结束于 $end）",
                )
            }
            val byte = record[cursor].toInt() and 0xFF
            cursor += 1
            consumed += 1
            value = (value shl 7) or (byte and 0x7F)
            if (value < 0) {
                throw Kf8IndexException("KF8_TAG_VALUE_INVALID", "变长整数超出 Int 范围")
            }
            if (byte and CONTINUATION_VALUE_BIT != 0) return value to cursor
            if (consumed >= MAX_VARINT_BYTES) {
                throw Kf8IndexException(
                    "KF8_VARINT_TOO_LONG",
                    "变长整数超过 $MAX_VARINT_BYTES 字节仍未结束（offset=$offset）",
                )
            }
        }
    }

    private fun readIdTexts(record: ByteArray, header: IndxHeader): List<String> {
        val offset = header.idxtOffset
        if (offset <= 0) return emptyList()
        if (offset + IDXT_HEADER_BYTES > record.size || !matchesAscii(record, offset, IDXT_MAGIC)) {
            return emptyList()
        }
        val count = u16(record, offset + 4)
        if (count > MAX_ENTRIES) {
            throw Kf8IndexException("LIMIT_EXCEEDED", "IDXT 条目数 $count 超过上限 $MAX_ENTRIES")
        }
        if (offset + IDXT_HEADER_BYTES + count * 2 > record.size) {
            throw Kf8IndexException(
                "KF8_IDXT_TRUNCATED",
                "IDXT 偏移表被截断（count=$count，记录 ${record.size} 字节）",
            )
        }
        return (0 until count).map { index ->
            readNulString(record, u16(record, offset + IDXT_HEADER_BYTES + index * 2))
        }
    }

    private fun readNulString(record: ByteArray, offset: Int): String {
        if (offset !in 0..record.size) {
            throw Kf8IndexException(
                "KF8_ID_TEXT_RANGE_INVALID",
                "条目 key 文本偏移 $offset 越界（记录 ${record.size} 字节）",
            )
        }
        var end = offset
        while (end < record.size && (record[end].toInt() and 0xFF) != 0 && end - offset < MAX_ID_TEXT_BYTES) {
            end += 1
        }
        return String(record, offset, end - offset, StandardCharsets.UTF_8).trim()
    }

    private fun cncxPayload(record: ByteArray): ByteArray =
        if (record.size > CNCX_HEADER_BYTES) {
            record.copyOfRange(CNCX_HEADER_BYTES, record.size)
        } else {
            ByteArray(0)
        }

    private fun cncxString(payload: ByteArray, offset: Int): String {
        if (payload.isEmpty()) return ""
        if (offset !in payload.indices) {
            throw Kf8IndexException(
                "KF8_CNCX_RANGE_INVALID",
                "CNCX 文本偏移 $offset 越界（CNCX 长度 ${payload.size}）",
            )
        }
        var end = offset
        while (end < payload.size && (payload[end].toInt() and 0xFF) != 0 && end - offset < MAX_CNCX_STRING_BYTES) {
            end += 1
        }
        return String(payload, offset, end - offset, StandardCharsets.UTF_8).trim()
    }

    private fun requireTag(
        tagMap: Map<Int, TagDefinition>,
        tag: Int,
        label: String,
    ): TagDefinition = tagMap[tag]
        ?: throw Kf8IndexException("KF8_TAGX_TAG_MISSING", "TAGX 缺少 $label 所需的 tag=$tag")

    /** 校验单条切片并返回长度，同时把累计总长也卡在同一处。 */
    private fun validateExtent(start: Int, length: Int, label: String, consumed: Long): Int {
        if (start < 0) {
            throw Kf8IndexException("KF8_EXTENT_INVALID", "$label 切片起点为负：$start")
        }
        if (length <= 0) {
            throw Kf8IndexException("KF8_EXTENT_INVALID", "$label 切片长度为 0 或负：$length")
        }
        if (length > MAX_SLICE_BYTES) {
            throw Kf8IndexException(
                "LIMIT_EXCEEDED",
                "$label 切片长度 $length 超过单条上限 $MAX_SLICE_BYTES",
            )
        }
        if (consumed + length > MAX_TOTAL_SLICE_BYTES) {
            throw Kf8IndexException(
                "LIMIT_EXCEEDED",
                "$label 累计切片长度超过总上限 $MAX_TOTAL_SLICE_BYTES",
            )
        }
        return length
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > bytes.size) {
            throw Kf8IndexException("KF8_READ_OUT_OF_RANGE", "u16 读取越界（offset=$offset）")
        }
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun u32(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > bytes.size) return -1
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun matchesAscii(bytes: ByteArray, offset: Int, text: String): Boolean {
        if (offset < 0 || offset + text.length > bytes.size) return false
        for (index in text.indices) {
            if (bytes[offset + index] != text[index].code.toByte()) return false
        }
        return true
    }

    private companion object {
        const val INDX_MAGIC = "INDX"
        const val TAGX_MAGIC = "TAGX"
        const val IDXT_MAGIC = "IDXT"

        /** 最小 INDX 头：至少覆盖到 indexCount（0x18 + 4）。 */
        const val INDX_HEADER_MIN_BYTES = 0x1C
        const val INDX_HEADER_LENGTH_FIELD = 0x04
        const val INDX_IDXT_OFFSET_FIELD = 0x14
        const val INDX_INDEX_COUNT_FIELD = 0x18
        const val TAGX_HEADER_BYTES = 12
        const val TAG_DEFINITION_BYTES = 16
        const val IDXT_HEADER_BYTES = 6
        const val CNCX_HEADER_BYTES = 16

        /** 控制字节：低 7 位是 tag 对数，bit7 表示同一条目还有下一段。 */
        const val CONTROL_TAG_MASK = 0x7F
        const val CONTROL_CONTINUE_BIT = 0x80

        /** 变长整数末字节标记位。 */
        const val CONTINUATION_VALUE_BIT = 0x80

        const val MAX_RECORDS = 4096
        const val MAX_ENTRIES = 100_000
        const val MAX_CHUNKS_PER_ENTRY = 64
        const val MAX_VARINT_BYTES = 4
        const val MAX_ID_TEXT_BYTES = 64
        const val MAX_CNCX_STRING_BYTES = 512
        const val MAX_TAG_NUMBER = 0xFF
        const val MAX_VALUES_PER_ENTRY = 16
        const val MAX_SLICE_BYTES = 16 * 1024 * 1024
        const val MAX_TOTAL_SLICE_BYTES = 64L * 1024 * 1024
        const val START_CAPACITY = 16

        /** skeleton 名称（CNCX 偏移）/ fragment 文件号 / 序号 / 起点+长度。 */
        const val TAG_SKELETON_NAME = 1
        const val TAG_FILE_NUMBER = 3
        const val TAG_SEQUENCE = 4
        const val TAG_EXTENT = 6
    }
}

/**
 * 按 KindleUnpack `mobi_k8proc.buildParts` 的顺序把 fragment 插回 skeleton。
 *
 * 每条骨架先按 `[start, start + length)` 从 flow 0 切出自己的片段，再把属于它的
 * fragment 按序号（[Kf8Tables.FragmentEntry.sequence]）升序、以插入位置（相对该骨架起点）
 * 把片段字节塞进去：`skeleton[0:insert] + fragment + skeleton[insert:]`。
 *
 * 任何校验不过都返回 null（由调用方回退到 flow 0 原文），校验规则：
 * `<html` 与 `</html>` 都存在且数量成对、`<body` 存在、结果不含 `\u0000`、总长不超过输入。
 */
object Kf8Reassembler {

    private const val MAX_SLICE_BYTES = 16 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    private const val HTML_OPEN = "<html"
    private const val HTML_CLOSE = "</html>"
    private const val BODY_OPEN = "<body"

    /**
     * [text] 是 flow 0 的字节；[charset] 用来把拼好的字节解成字符串。
     * 返回 null 表示这条链路不可信（拼接越界或结构校验不过）。
     */
    fun reassemble(
        text: ByteArray,
        skeleton: Kf8Tables.Skeleton,
        fragment: Kf8Tables.Fragment,
        charset: Charset,
    ): String? {
        if (text.isEmpty() || text.size > MAX_TOTAL_BYTES) return null
        if (skeleton.entries.isEmpty()) return null

        val groups = HashMap<Int, MutableList<Kf8Tables.FragmentEntry>>()
        fragment.entries.forEach { entry ->
            val slot = skeletonSlot(entry.fileNumber, skeleton.entries.size) ?: return null
            groups.getOrPut(slot) { mutableListOf() } += entry
        }

        val output = ByteArrayOutputStream(minOf(text.size, INITIAL_BUFFER_BYTES))
        skeleton.entries.forEachIndexed { index, entry ->
            val slice = sliceOf(text, entry.start, entry.length) ?: return null
            val fragments = groups[index].orEmpty()
                .sortedWith(compareBy({ it.sequence }, { it.insertPosition }))
            var cursor = 0
            fragments.forEach { piece ->
                val insert = piece.insertPosition
                if (insert < cursor || insert > slice.size) return null
                val bytes = sliceOf(text, piece.start, piece.length) ?: return null
                output.write(slice, cursor, insert - cursor)
                output.write(bytes, 0, bytes.size)
                if (output.size().toLong() > text.size.toLong()) return null
                cursor = insert
            }
            output.write(slice, cursor, slice.size - cursor)
            if (output.size().toLong() > text.size.toLong()) return null
        }

        val bytes = output.toByteArray()
        if (bytes.isEmpty() || bytes.size > text.size) return null
        val html = String(bytes, charset)
        if (html.isEmpty() || html.length > text.size) return null
        if (html.indexOf('\u0000') >= 0) return null
        return if (isWellFormed(html)) html else null
    }

    /** 出参切片：起止都必须落在 text 内，长度不超过单条上限。 */
    private fun sliceOf(text: ByteArray, start: Int, length: Int): ByteArray? {
        if (start < 0 || length <= 0 || length > MAX_SLICE_BYTES) return null
        if (start.toLong() + length > text.size) return null
        return text.copyOfRange(start, start + length)
    }

    /**
     * fragment 的 fileNumber 是 1 起文件号（真实 KF8 的 tag 3 约定）；
     * 越界时按 0 起下标容错一次，两者都不成立就判定这条 fragment 不可信。
     */
    private fun skeletonSlot(fileNumber: Int, skeletonCount: Int): Int? {
        if (fileNumber in 1..skeletonCount) return fileNumber - 1
        if (fileNumber in 0 until skeletonCount) return fileNumber
        return null
    }

    private fun isWellFormed(html: String): Boolean {
        val openCount = countOccurrences(html, HTML_OPEN)
        if (openCount == 0) return false
        val closeCount = countOccurrences(html, HTML_CLOSE)
        if (openCount != closeCount) return false
        val firstOpen = indexOfIgnoreCase(html, HTML_OPEN, 0)
        val lastClose = lastIndexOfIgnoreCase(html, HTML_CLOSE)
        if (firstOpen < 0 || lastClose < 0 || firstOpen > lastClose) return false
        return indexOfIgnoreCase(html, BODY_OPEN, 0) >= 0
    }

    private fun countOccurrences(text: String, needle: String): Int {
        var count = 0
        var index = indexOfIgnoreCase(text, needle, 0)
        while (index >= 0) {
            count += 1
            index = indexOfIgnoreCase(text, needle, index + needle.length)
        }
        return count
    }

    private fun indexOfIgnoreCase(text: String, needle: String, from: Int): Int {
        var index = from.coerceAtLeast(0)
        val last = text.length - needle.length
        while (index <= last) {
            if (matchesIgnoreCase(text, index, needle)) return index
            index += 1
        }
        return -1
    }

    private fun lastIndexOfIgnoreCase(text: String, needle: String): Int {
        var index = text.length - needle.length
        while (index >= 0) {
            if (matchesIgnoreCase(text, index, needle)) return index
            index -= 1
        }
        return -1
    }

    private fun matchesIgnoreCase(text: String, offset: Int, needle: String): Boolean {
        for (index in needle.indices) {
            if (text[offset + index].lowercaseChar() != needle[index].lowercaseChar()) return false
        }
        return true
    }

    private const val INITIAL_BUFFER_BYTES = 64 * 1024
}
