package com.example.myapplication.data.reader.importer

import android.util.Log
import com.example.myapplication.data.reader.importer.kf8.Kf8Fdst
import com.example.myapplication.data.reader.importer.kf8.Kf8IndexException
import com.example.myapplication.data.reader.importer.kf8.Kf8IndxParser
import com.example.myapplication.data.reader.importer.kf8.Kf8Reassembler
import com.example.myapplication.data.reader.storage.ManifestFileKind
import com.example.myapplication.data.reader.storage.ManifestOutlineNode
import com.example.myapplication.data.reader.storage.ManifestSection
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/**
 * MOBI / AZW / AZW3（PalmDB 容器）解析器。
 *
 * 容器层：PalmDB 头（type 在 offset 60，记录数在 76，记录信息表从 78 起每条 8 字节，
 * 记录偏移相对文件起点）+ 记录 0 里的 PalmDOC 头 + MOBI 头。
 *
 * 正文层：
 * - compression=1 原文；compression=2 走 PalmDOC LZ77；compression=17480 走 HUFF/CDIC；
 * - 加密（PalmDOC encryptionType / MOBI DRM flags / EXTH DRM 记录）一律 `DRM_UNSUPPORTED`；
 * - 文本是 HTML 片段：按 `<mbp:pagebreak/>` 或顶层 `<h1>/<h2>` 切节；
 *   没有任何标题/分页符时按每 8 KiB 文本切节，标题为"第 N 节"；
 * - KF8（AZW3，`version >= 8`）的 flow 0 是"骨架 + 片段"结构：解压后先读 FDST 切流，
 *   再解析 skeleton/fragment 两张 INDX 表把片段插回骨架（见 [com.example.myapplication.data.reader.importer.kf8]）；
 *   任何一步失败都回退到 flow 0 原文并记 `KF8_*` 警告码，不让重组失败变成"书打不开"；
 * - 合并文件（MOBI7 + KF8 同包，EXTH 121 `Mobi8-Boundary-Section` 指向 KF8 头记录）：
 *   先按该记录号读出 KF8 段的 PalmDOC/MOBI 头并重组正文，成功就用 KF8 正文；
 *   任何一步失败都回退 MOBI7 段（正文、图片、标题、EXTH 仍按 record0），只留 `KF8_*` 诊断码；
 * - `<img recindex>` 从 PalmDB 图片记录读取并落盘到 `images/image-<n>.<ext>`，
 *   读不到就移除该 `<img>`（不留断链，files 里也不登记）。
 *
 * 安全口径：单条 PalmDB 记录 ≤ 32 MiB、正文解压后 ≤ 64 MiB，偏移必须落在文件内；
 * 所有解析失败都用 [DocumentParseException] 的固定错误码。
 */
class LegacyEbookDocumentParser : DocumentParser {

    override fun parse(sourceFile: File, outputDirectory: File, titleHint: String?): ParsedDocument {
        if (!sourceFile.isFile) {
            throw DocumentParseException("INVALID_ARGUMENT", "MOBI 源文件不存在：${sourceFile.name}")
        }
        return try {
            RandomAccessFile(sourceFile, "r").use { file ->
                parsePalmDatabase(file, sourceFile, outputDirectory, titleHint)
            }
        } catch (error: DocumentParseException) {
            throw error
        } catch (error: IOException) {
            throw DocumentParseException("CORRUPT_INPUT", "读取 PalmDB 失败：${error.message}", error)
        }
    }

    private fun parsePalmDatabase(
        file: RandomAccessFile,
        sourceFile: File,
        outputDirectory: File,
        titleHint: String?,
    ): ParsedDocument {
        val fileLength = file.length()
        val offsets = readRecordOffsets(file, fileLength)
        val record0 = readRecord(file, offsets, 0, fileLength)

        val palmDoc = readPalmDocHeader(record0)
        if (palmDoc.encryptionType != 0) {
            throw DocumentParseException(
                "DRM_UNSUPPORTED",
                "MOBI 内容已加密（PalmDOC encryptionType=${palmDoc.encryptionType}），暂不支持 DRM",
            )
        }

        val mobi = readMobiHeader(record0)
        if (mobi != null && mobi.drmFlags != 0) {
            throw DocumentParseException(
                "DRM_UNSUPPORTED",
                "MOBI 头声明了 DRM 标志（drmFlags=${mobi.drmFlags}），暂不支持",
            )
        }
        val exth = mobi?.let { readExth(record0, it) }
        if (exth?.hasDrm == true) {
            throw DocumentParseException("DRM_UNSUPPORTED", "MOBI EXTH 含 DRM 记录，暂不支持")
        }

        // 正文来源按内容分三条链路，顺序固定：
        // 1) record0 `version >= 8`：独立 KF8（AZW3），正文记录从 1 起，解压后走 FDST + skeleton/fragment 重组；
        // 2) `version < 8` 且 EXTH 121（Mobi8-Boundary-Section）指向有效记录：**合并文件**
        //    （MOBI7 + KF8 同包）。先按 boundary 把 KF8 段读出来并重组，成功就用 KF8 正文与图片起点；
        //    任何一步失败（头不合法 / 正文记录坏 / 空 / 重组不过）都回退 MOBI7 段，行为与旧实现一致；
        // 3) 其余（普通 MOBI7）：完全走旧路径。
        // 设计口径：KF8 提取是"保真升级"，不是"能不能读"的前提——合并文件里 MOBI7 段本身可读，
        // 所以 KF8 段失败只降级不报错（只留 `KF8_*` 诊断码，正文严禁进日志）。
        val effectiveText: String
        val firstImageIndex: Int
        if (mobi != null && mobi.version >= 8) {
            val textBytes = readPalmDocTextBytes(file, offsets, fileLength, palmDoc, mobi)
            val text = decodeMobiText(textBytes, mobi)
            if (text.isBlank()) {
                throw DocumentParseException("CORRUPT_INPUT", "MOBI 正文为空")
            }
            // KF8（AZW3）的 flow 0 不是一段连续 HTML：只解压不重组就缺段/顺序错。
            // 重组链路任何一步失败都回退到 flow 0 原文（只记警告码，不记正文）。
            effectiveText = reassembleKf8Text(file, offsets, fileLength, mobi, textBytes) ?: text
            firstImageIndex = mobi.firstImageIndex
        } else {
            val boundary = exth?.boundaryRecord?.takeIf { it > 0 }
            val segment = boundary?.let { readKf8Segment(file, offsets, fileLength, it, mobi) }
            if (segment != null) {
                effectiveText = segment.text
                firstImageIndex = segment.firstImageIndex
            } else {
                val textBytes = readPalmDocTextBytes(file, offsets, fileLength, palmDoc, mobi)
                val text = decodeMobiText(textBytes, mobi)
                if (text.isBlank()) {
                    throw DocumentParseException("CORRUPT_INPUT", "MOBI 正文为空")
                }
                effectiveText = text
                firstImageIndex = mobi?.firstImageIndex ?: 0
            }
        }

        val images = MobiImageRegistry(
            outputDirectory = outputDirectory,
            firstImageIndex = firstImageIndex,
            recordCount = offsets.size,
        ) { recordIndex -> readRecord(file, offsets, recordIndex, fileLength) }
        val drafts = buildSectionDrafts(effectiveText, images)
        if (drafts.isEmpty()) {
            throw DocumentParseException("CORRUPT_INPUT", "MOBI 没有可读正文")
        }

        val files = mutableListOf<ParsedFile>()
        val sections = mutableListOf<ManifestSection>()
        val outline = mutableListOf<ManifestOutlineNode>()
        drafts.forEachIndexed { index, draft ->
            val number = index + 1
            val sectionId = "section-$number"
            val relativePath = "sections/$sectionId.html"
            val title = draft.title ?: "第 $number 节"
            val bodyHtml = StringBuilder()
                .append("<h1>")
                .append(ParserIO.escapeHtml(title))
                .append("</h1>")
            draft.blocks.forEach { bodyHtml.append(it) }
            ParserIO.writeText(outputDirectory, relativePath, ParserIO.htmlDocument(title, bodyHtml.toString()))
            files += ParsedFile(relativePath, ManifestFileKind.SECTION)
            sections += ManifestSection(
                id = sectionId,
                orderIndex = index,
                title = title,
                resourceHref = relativePath,
                charCount = title.length + draft.bodyChars,
            )
            outline += ManifestOutlineNode(
                nodeKey = "toc-$number",
                orderIndex = index,
                title = title,
                targetSectionId = sectionId,
            )
        }
        // 图片是在正文渲染时按需落盘的，合并到 files 末尾即可（manifest 不关心顺序）。
        files += images.files

        // 书名优先级：EXTH Updated Title > MOBI Full Name > 调用方给的 titleHint > 首节标题 > 文件名。
        // 前两者是书自己的元数据，比文件名可靠；titleHint 是导入入口的兜底。
        // 元数据同样要过 cleanText：EXTH 字符串可能带控制字符，直接进 manifest 会被校验拒绝。
        val metadataTitle = (exth?.title ?: readFullName(record0, mobi))
            ?.let { cleanText(it) }
            ?.takeIf { it.isNotEmpty() }
        val title = metadataTitle
            ?: titleHint?.let { cleanText(it) }?.takeIf { it.isNotEmpty() }
            ?: sections.firstOrNull()?.title
            ?: ParserIO.titleFromName(sourceFile.name)
        val author = exth?.author?.let { cleanText(it) }?.takeIf { it.isNotEmpty() }

        // 设计口径：version >= 8（KF8）判 AZW3，否则判 MOBI。
        // 扩展名（.azw / .azw3）与这里冲突时**以内容判定为准**，所以此处刻意不读扩展名：
        //   .azw  + version>=8 → AZW3（内核是 KF8）
        //   .azw3 + version<8  → MOBI（内核是 MOBI7，按 MOBI7 解才不会读错）
        // 扩展名只影响导入源落盘命名（FormatRegistry.extensionOf），不参与格式判定。
        val version = mobi?.version ?: 0
        val format = if (version >= 8) ReaderFormat.AZW3 else ReaderFormat.MOBI

        return ParsedDocument(
            title = title,
            author = author,
            format = format,
            files = files,
            sections = sections,
            outline = outline,
        )
    }

    // ---------------------------------------------------------------- PalmDB 容器

    /**
     * 读取并校验 PalmDB 头与记录信息表，返回每条记录的起始偏移（文件绝对偏移）。
     *
     * 校验顺序是有意的：先看 type 再分配/读取记录表，避免一个畸形的 65535 记录声明
     * 让后面的读取越界；记录偏移必须落在 [记录表末尾, 文件长度] 之间且单调不减。
     */
    private fun readRecordOffsets(file: RandomAccessFile, fileLength: Long): LongArray {
        if (fileLength < PALM_HEADER_BYTES) {
            throw DocumentParseException("CORRUPT_INPUT", "不是 PalmDB 容器：文件只有 $fileLength 字节")
        }
        val header = ByteArray(PALM_HEADER_BYTES)
        file.seek(0)
        file.readFully(header)
        val type = String(header, PALM_TYPE_OFFSET, 4, StandardCharsets.US_ASCII)
        if (type != "BOOK") {
            throw DocumentParseException(
                "CORRUPT_INPUT",
                "不是 MOBI 书籍：PalmDB type=$type（期望 BOOK）",
            )
        }
        val recordCount = u16(header, PALM_RECORD_COUNT_OFFSET)
        if (recordCount <= 0) {
            throw DocumentParseException("CORRUPT_INPUT", "PalmDB 记录数为 0")
        }
        val tableEnd = PALM_HEADER_BYTES.toLong() + recordCount.toLong() * RECORD_INFO_BYTES
        if (tableEnd > fileLength) {
            throw DocumentParseException(
                "CORRUPT_INPUT",
                "PalmDB 记录信息表越界：需要 $tableEnd 字节，文件只有 $fileLength 字节",
            )
        }
        val table = ByteArray(recordCount * RECORD_INFO_BYTES)
        file.seek(PALM_HEADER_BYTES.toLong())
        file.readFully(table)
        val offsets = LongArray(recordCount)
        for (index in 0 until recordCount) {
            offsets[index] = u32Long(table, index * RECORD_INFO_BYTES)
        }
        for (index in offsets.indices) {
            val offset = offsets[index]
            if (offset < tableEnd || offset > fileLength) {
                throw DocumentParseException(
                    "CORRUPT_INPUT",
                    "PalmDB 记录 $index 偏移越界：$offset（合法范围 $tableEnd..$fileLength）",
                )
            }
            if (index > 0 && offset < offsets[index - 1]) {
                throw DocumentParseException(
                    "CORRUPT_INPUT",
                    "PalmDB 记录 $index 偏移倒退：$offset < ${offsets[index - 1]}",
                )
            }
        }
        return offsets
    }

    /** 按记录信息表切一条记录；单记录上限 [MAX_RECORD_BYTES] 在分配内存前就会拒绝。 */
    private fun readRecord(
        file: RandomAccessFile,
        offsets: LongArray,
        index: Int,
        fileLength: Long,
    ): ByteArray {
        if (index < 0 || index >= offsets.size) {
            throw DocumentParseException("CORRUPT_INPUT", "PalmDB 记录索引越界：$index")
        }
        val start = offsets[index]
        val end = if (index + 1 < offsets.size) offsets[index + 1] else fileLength
        val size = end - start
        if (size < 0) {
            throw DocumentParseException("CORRUPT_INPUT", "PalmDB 记录 $index 长度为负（$size）")
        }
        if (size > MAX_RECORD_BYTES) {
            throw DocumentParseException(
                "LIMIT_EXCEEDED",
                "PalmDB 记录 $index 体积 $size 字节，超过单记录上限 $MAX_RECORD_BYTES",
            )
        }
        if (size == 0L) return ByteArray(0)
        val buffer = ByteArray(size.toInt())
        file.seek(start)
        file.readFully(buffer)
        return buffer
    }

    // ---------------------------------------------------------------- PalmDOC / MOBI 头

    private fun readPalmDocHeader(record0: ByteArray): PalmDocHeader {
        if (record0.size < PALMDOC_HEADER_BYTES) {
            throw DocumentParseException("CORRUPT_INPUT", "PalmDOC 头不完整（只有 ${record0.size} 字节）")
        }
        return PalmDocHeader(
            compression = u16(record0, 0),
            textLength = u32Long(record0, 4),
            textRecordCount = u16(record0, 8),
            encryptionType = u16(record0, 12),
        )
    }

    /**
     * MOBI 头紧跟 PalmDOC 头（record0 + 16）。没有 "MOBI" 魔数时返回 null，
     * 按 MOBI7 + PalmDOC 处理（文本编码退回 BOM/UTF-8/GB18030 嗅探）。
     */
    private fun readMobiHeader(record0: ByteArray): MobiHeader? {
        val start = PALMDOC_HEADER_BYTES
        if (record0.size < start + 24) return null
        if (!matchesAscii(record0, start, "MOBI")) return null
        val remaining = record0.size - start
        val declaredLength = u32(record0, start + 4)
        val declaredValid = declaredLength in 8..remaining
        val headerLength = if (declaredValid) declaredLength else remaining

        // MOBI 头声明的字段只在声明长度真的覆盖到它时才读：否则头尾之后的 Full Name /
        // 索引数据会被误当成字段值（例如把书名首字节当成 firstImageIndex，图片会全错）。
        fun fieldAt(offset: Int): Int =
            if (declaredValid && headerLength >= offset + 4) u32OrZero(record0, start + offset) else 0
        fun fieldAt16(offset: Int): Int =
            if (declaredValid && headerLength >= offset + 2) u16(record0, start + offset) else 0

        return MobiHeader(
            headerLength = headerLength,
            version = u32OrZero(record0, start + 20),
            textEncoding = u32OrZero(record0, start + 12),
            firstImageIndex = fieldAt(92),
            fullNameOffset = fieldAt(68),
            fullNameLength = fieldAt(72),
            // 设计稿的"EXTH 标志在 +128"是 record0 视角（魔数在 record0+16，16+112=128）；
            // 换成 MOBI 魔数视角就是标准头里的 +112（0x70）。两者在这里指向同一批字节。
            exthFlags = fieldAt(112),
            // DRM flags 在 MOBI 头 +160（0xA0）：同样只在头部长度覆盖到该字段时才读，
            // 否则会把正文/索引数据误当成加密标志，造成"好书上不去"的假 DRM。
            drmFlags = fieldAt(160),
            huffRecordIndex = fieldAt(96),
            huffRecordCount = fieldAt(100),
            extraDataFlags = fieldAt16(226),
            // KF8（version >= 8）新增字段。注意 fieldAt 的 offset 是相对 MOBI 魔数
            // （record0 + 16）的，等于"绝对偏移 − 0x10"：
            // 0xC0 FDST 记录号 → 0xB0；0xC4 FDST 流数量 → 0xB4；
            // 0xF8 fragment 索引 → 0xE8；0xFC skeleton 索引 → 0xEC；0x104 guide 索引 → 0xF4。
            fdstIndex = fieldAt(0xB0),
            fdstCount = fieldAt(0xB4),
            fragmentIndex = fieldAt(0xE8),
            skeletonIndex = fieldAt(0xEC),
            guideIndex = fieldAt(0xF4),
        )
    }

    /**
     * 解析 EXTH 头（MOBI 头之后，以 "EXTH" 魔数开头）。
     * 只取书名（503）、作者（100）与 DRM 记录（406/407/408），其余类型跳过。
     */
    private fun readExth(record0: ByteArray, mobi: MobiHeader): ExthInfo {
        val start = PALMDOC_HEADER_BYTES + mobi.headerLength
        if (start.toLong() + 12 > record0.size) return ExthInfo.EMPTY
        if (!matchesAscii(record0, start, "EXTH")) return ExthInfo.EMPTY
        val count = u32(record0, start + 8)
        if (count <= 0) return ExthInfo.EMPTY
        var title: String? = null
        var author: String? = null
        var hasDrm = false
        var boundaryRecord: Int? = null
        var cursor = start + 12
        var index = 0
        val maxRecords = minOf(count, MAX_EXTH_RECORDS)
        while (index < maxRecords) {
            if (cursor.toLong() + 8 > record0.size) break
            val type = u32(record0, cursor)
            val length = u32(record0, cursor + 4)
            if (length < 8 || cursor.toLong() + length > record0.size) break
            when (type) {
                EXTH_AUTHOR -> if (author == null) author = decodeExthText(record0, cursor + 8, length - 8)
                EXTH_UPDATED_TITLE -> if (title == null) title = decodeExthText(record0, cursor + 8, length - 8)
                EXTH_DRM_SERVER, EXTH_DRM_COMMERCE, EXTH_DRM_EBOOKBASE -> hasDrm = true
                EXTH_MOBI8_BOUNDARY -> if (boundaryRecord == null && length >= 12) {
                    boundaryRecord = u32OrZero(record0, cursor + 8)
                }
            }
            cursor += length
            index += 1
        }
        return ExthInfo(
            title = title?.takeIf { it.isNotBlank() },
            author = author?.takeIf { it.isNotBlank() },
            hasDrm = hasDrm,
            boundaryRecord = boundaryRecord,
        )
    }

    private fun decodeExthText(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.UTF_8).trim()

    private fun readFullName(record0: ByteArray, mobi: MobiHeader?): String? {
        if (mobi == null) return null
        val offset = mobi.fullNameOffset
        val length = mobi.fullNameLength
        if (offset <= 0 || length <= 0) return null
        if (offset.toLong() + length > record0.size) return null
        val name = String(record0, offset, length, Charsets.UTF_8).trim()
        return name.takeIf { it.isNotEmpty() && it.none(Char::isISOControl) }
    }

    // ---------------------------------------------------------------- 正文

    /**
     * 构造 HUFF/CDIC 解码器。
     *
     * [textStart] 是正文首条记录的绝对记录号：MOBI7 是 1；合并文件的 KF8 段是
     * `boundary + 1`（KF8 头自己占一条记录）。它与 HUFF 记录做重叠校验时必须用同一基准，
     * 否则合并文件会被误判成"字典与正文重叠"而整段读不出来。
     */
    private fun createHuffDecoder(
        file: RandomAccessFile,
        offsets: LongArray,
        fileLength: Long,
        palmDoc: PalmDocHeader,
        mobi: MobiHeader?,
        textStart: Int = 1,
    ): HuffCdicDecoder {
        val header = mobi ?: throw DocumentParseException(
            "CORRUPT_INPUT",
            "HUFF/CDIC 正文缺少 MOBI 头",
        )
        val firstRecord = header.huffRecordIndex
        val recordCount = header.huffRecordCount
        if (firstRecord <= 0 || recordCount < 2 || recordCount > MAX_HUFF_RECORDS) {
            throw DocumentParseException("CORRUPT_INPUT", "HUFF/CDIC 记录范围无效")
        }
        val endExclusive = firstRecord.toLong() + recordCount
        if (endExclusive > offsets.size) {
            throw DocumentParseException("CORRUPT_INPUT", "HUFF/CDIC 记录超出 PalmDB 范围")
        }
        val textRecordCount = palmDoc.textRecordCount.takeIf { it in 1..(offsets.size - textStart) }
            ?: (offsets.size - textStart)
        val textEndExclusive = textStart + textRecordCount
        if (firstRecord < textEndExclusive && endExclusive > textStart) {
            throw DocumentParseException("CORRUPT_INPUT", "HUFF/CDIC 记录与正文记录重叠")
        }
        val records = mutableListOf<ByteArray>()
        var totalBytes = 0L
        for (recordIndex in firstRecord until endExclusive.toInt()) {
            val record = readRecord(file, offsets, recordIndex, fileLength)
            totalBytes += record.size
            if (totalBytes > MAX_HUFF_DATA_BYTES) {
                throw DocumentParseException("LIMIT_EXCEEDED", "HUFF/CDIC 字典数据超过安全上限")
            }
            records += record
        }
        return HuffCdicDecoder(records, ParserIO.MAX_TEXT_BYTES)
    }

    /**
     * 解压并拼接文本记录 `firstTextRecord ..< firstTextRecord + count`。
     *
     * `count`（PalmDOC 头 +8）只作声明参考：超出实际记录数时按可用数截断，
     * 绝不允许按声明值去读越界记录；`textLength`（+4）是有效正文长度，
     * 最后一条文本记录后面常跟索引数据，声明值合理时才截取。
     *
     * [firstTextRecord] 是正文首条记录的**绝对记录号**：MOBI7 与独立 KF8 都是 1，
     * 合并文件的 KF8 段是 `boundary + 1`（KF8 头自己占一条记录）。
     */
    private fun readTextRecords(
        file: RandomAccessFile,
        offsets: LongArray,
        fileLength: Long,
        palmDoc: PalmDocHeader,
        huffDecoder: HuffCdicDecoder?,
        extraDataFlags: Int,
        firstTextRecord: Int = 1,
    ): ByteArray {
        val available = offsets.size - firstTextRecord
        if (available <= 0) {
            throw DocumentParseException(
                "CORRUPT_INPUT",
                "PalmDB 在记录 $firstTextRecord 之后没有文本记录",
            )
        }
        val declared = palmDoc.textRecordCount
        val count = if (declared in 1..available) declared else available
        val output = ByteArrayOutputStream()
        var total = 0L
        for (index in firstTextRecord until firstTextRecord + count) {
            val raw = stripTrailingData(readRecord(file, offsets, index, fileLength), extraDataFlags, index)
            val chunk = when (palmDoc.compression) {
                COMPRESSION_NONE -> raw
                COMPRESSION_PALMDOC -> palmDocLz77(raw, index)
                COMPRESSION_HUFF_CDIC -> huffDecoder?.decompress(raw, ParserIO.MAX_TEXT_BYTES - total)
                    ?: throw DocumentParseException("CORRUPT_INPUT", "HUFF/CDIC 字典不可用")
                else -> throw DocumentParseException(
                    "UNSUPPORTED_FORMAT",
                    "未知的 PalmDOC 压缩类型：${palmDoc.compression}",
                )
            }
            total += chunk.size
            if (total > ParserIO.MAX_TEXT_BYTES) {
                throw DocumentParseException("LIMIT_EXCEEDED", "MOBI 正文解压后超过 64 MiB 上限")
            }
            output.write(chunk, 0, chunk.size)
        }
        val all = output.toByteArray()
        if (all.isEmpty()) {
            throw DocumentParseException("CORRUPT_INPUT", "MOBI 正文为空")
        }
        val declaredLength = palmDoc.textLength
        return if (declaredLength > 0 && declaredLength <= all.size.toLong()) {
            all.copyOf(declaredLength.toInt())
        } else {
            all
        }
    }

    /**
     * 按 [palmDoc] 声明的压缩方式把 `firstTextRecord` 起的正文记录解压成字节。
     *
     * MOBI7 与 KF8 段共用这一条链路，差别只在三处：正文起点记录号、HUFF 记录范围、
     * 尾部数据标志（都由调用方传进来的头决定）。未知压缩类型仍按 `UNSUPPORTED_FORMAT` 上抛，
     * 合并文件里由 [readKf8Segment] 捕获并转成 `KF8_*` 码后回退 MOBI7。
     */
    private fun readPalmDocTextBytes(
        file: RandomAccessFile,
        offsets: LongArray,
        fileLength: Long,
        palmDoc: PalmDocHeader,
        mobi: MobiHeader?,
        firstTextRecord: Int = 1,
    ): ByteArray {
        val huffDecoder = when (palmDoc.compression) {
            COMPRESSION_NONE, COMPRESSION_PALMDOC -> null
            COMPRESSION_HUFF_CDIC -> createHuffDecoder(
                file = file,
                offsets = offsets,
                fileLength = fileLength,
                palmDoc = palmDoc,
                mobi = mobi,
                textStart = firstTextRecord,
            )
            else -> throw DocumentParseException(
                "UNSUPPORTED_FORMAT",
                "未知的 PalmDOC 压缩类型：${palmDoc.compression}",
            )
        }
        return readTextRecords(
            file = file,
            offsets = offsets,
            fileLength = fileLength,
            palmDoc = palmDoc,
            huffDecoder = huffDecoder,
            extraDataFlags = mobi?.extraDataFlags ?: 0,
            firstTextRecord = firstTextRecord,
        )
    }

    /** Remove flagged trailing metadata before decoding compressed text records. */
    private fun stripTrailingData(record: ByteArray, extraDataFlags: Int, recordIndex: Int): ByteArray {
        if (extraDataFlags == 0 || record.isEmpty()) return record
        var trailingBytes = 0
        var flags = extraDataFlags ushr 1
        while (flags != 0) {
            if (flags and 1 != 0) {
                val remaining = record.size - trailingBytes
                val entrySize = readBackwardMobiVarint(record, remaining, recordIndex)
                if (entrySize <= 0L || entrySize > remaining.toLong()) {
                    throw DocumentParseException(
                        "CORRUPT_INPUT",
                        "MOBI 记录 $recordIndex 的尾部 entry 长度越界",
                    )
                }
                trailingBytes += entrySize.toInt()
            }
            flags = flags ushr 1
        }
        if (extraDataFlags and 1 != 0) {
            val remaining = record.size - trailingBytes
            if (remaining <= 0) {
                throw DocumentParseException("CORRUPT_INPUT", "MOBI 记录 $recordIndex 缺少 overlap 计数字节")
            }
            val overlapCount = (record[remaining - 1].toInt() and 0x03) + 1
            if (overlapCount > remaining) {
                throw DocumentParseException("CORRUPT_INPUT", "MOBI 记录 $recordIndex 的 overlap 长度越界")
            }
            trailingBytes += overlapCount
        }
        return if (trailingBytes == 0) record else record.copyOf(record.size - trailingBytes)
    }

    /** Reads the backward-encoded variable-width total entry size from the record tail. */
    private fun readBackwardMobiVarint(record: ByteArray, endExclusive: Int, recordIndex: Int): Long {
        var bitPosition = 0
        var result = 0L
        var cursor = endExclusive - 1
        var bytesRead = 0
        while (cursor >= 0 && bytesRead < MAX_MOBI_VARINT_BYTES) {
            val value = record[cursor].toInt() and 0xFF
            result = result or ((value and 0x7F).toLong() shl bitPosition)
            bitPosition += 7
            bytesRead += 1
            if (value and 0x80 != 0) return result
            if (bitPosition >= 28) break
            cursor -= 1
        }
        throw DocumentParseException(
            "CORRUPT_INPUT",
            "MOBI 记录 $recordIndex 的尾部 entry 长度编码无效",
        )
    }

    /**
     * PalmDOC LZ77 解压。
     *
     * 字节分派（PalmDOC / MOBI7 实际布局）：
     * - 0x00：输出一个 0x00；
     * - 0x01–0x08：随后 N 字节原样输出（literal run）；
     * - 0x09–0x7F：原样输出该字节；
     * - 0x80–0xBF：两字节回退引用，pair = (首字节 << 8) | 次字节，
     *   distance = (pair & 0x3FFF) >> 3（高 11 位），length = (pair & 0x07) + 3（低 3 位）；
     * - 0xC0–0xFF：输出空格 + (字节 ^ 0x80)（单字节高频词压缩）。
     *
     * 与设计稿的偏差：设计稿写的是 distance = pair & 0x3FFF、length = ((c>>3)&7)+3，
     * 这两段位是重叠的（距离的 bit11–13 与长度的 bit3–5 落在同一批位上），
     * 按该式无法编码任意 (距离, 长度) 组合，也无法与真实 MOBI7 文件互通；
     * 这里按 14 bit = 11 bit 距离 + 3 bit 长度的实际布局拆分。
     */
    private fun palmDocLz77(data: ByteArray, recordIndex: Int): ByteArray {
        val output = GrowableBuffer(ParserIO.MAX_TEXT_BYTES)
        var index = 0
        while (index < data.size) {
            val c = data[index].toInt() and 0xFF
            index += 1
            when {
                c == 0x00 -> output.writeByte(0x00)
                c <= 0x08 -> {
                    // 0x01–0x08 后面必须真的跟着 N 个字节；不足说明记录被截断，不能静默少输出。
                    if (index + c > data.size) {
                        throw DocumentParseException(
                            "CORRUPT_INPUT",
                            "PalmDOC 记录 $recordIndex 的字面量串被截断（需要 $c 字节，只剩 ${data.size - index}）",
                        )
                    }
                    output.write(data, index, c)
                    index += c
                }
                c <= 0x7F -> output.writeByte(c)
                c <= 0xBF -> {
                    if (index >= data.size) {
                        throw DocumentParseException(
                            "CORRUPT_INPUT",
                            "PalmDOC 记录 $recordIndex 的回退引用被截断",
                        )
                    }
                    val pair = (c shl 8) or (data[index].toInt() and 0xFF)
                    index += 1
                    val distance = (pair and 0x3FFF) shr 3
                    val length = (pair and 0x07) + 3
                    if (distance <= 0 || distance > output.size) {
                        throw DocumentParseException(
                            "CORRUPT_INPUT",
                            "PalmDOC 记录 $recordIndex 的回退距离越界：distance=$distance，已输出 ${output.size} 字节",
                        )
                    }
                    output.copyBack(distance, length)
                }
                else -> {
                    output.writeByte(0x20)
                    output.writeByte(c xor 0x80)
                }
            }
        }
        return output.toByteArray()
    }

    private fun decodeMobiText(bytes: ByteArray, mobi: MobiHeader?): String {
        val encoding = mobi?.textEncoding ?: 0
        val decoded = when (encoding) {
            TEXT_ENCODING_UTF8 -> String(bytes, Charsets.UTF_8)
            TEXT_ENCODING_CP1252 -> String(bytes, Charset.forName("windows-1252"))
            else -> ParserIO.decodeText(bytes)
        }
        return decoded.trimEnd('\u0000')
    }

    // ---------------------------------------------------------------- KF8 正文重组

    /**
     * 从合并文件（MOBI7 + KF8 同包）里抽出的 KF8 段。
     *
     * [text] 是 skeleton/fragment 重组后的正文 HTML；[firstImageIndex] 是 **KF8 头**声明的
     * 首张图片记录号（`<img recindex>` 从它起算）；[textEncoding] 是解码正文用的编码，
     * 与 [MobiHeader.textEncoding] 一致，留作调用方/诊断对照。
     */
    private data class Kf8Segment(
        val text: String,
        val firstImageIndex: Int,
        val textEncoding: Int,
    )

    /**
     * 读取并重组合并文件的 KF8 段，任何一步失败返回 null（调用方回退 MOBI7 正文）。
     *
     * [boundary] 是 EXTH 121 `Mobi8-Boundary-Section` 给出的 **KF8 头所在绝对记录号**。
     * 段结构：记录 `boundary` = PalmDOC 头 + MOBI 头（`version >= 8`、`encryptionType == 0`），
     * 正文记录是 `boundary + 1 .. boundary + textRecordCount`；压缩 / 尾部数据标志 /
     * 文本编码 / FDST / skeleton / fragment / firstImageIndex 全部取 **KF8 头**的字段，
     * 不借用 record0 的（record0 是 MOBI7 段，两段记录基数不同）。
     *
     * [headerMobi7] 只在 KF8 头没声明图片起点时兜底（合并文件常把图片记录共用在一段），
     * 不参与正文解码。
     *
     * 失败码（只记码，不记正文）：
     * - `KF8_HEADER_INVALID: <detail>`：boundary 越界、KF8 头读不出、`version < 8`、或已加密；
     * - `KF8_TEXT_RECORD_INVALID: <detail>`：正文记录读不出/压缩类型不支持/超上限；
     * - `KF8_TEXT_EMPTY`：KF8 正文解出来是空串；
     * - `KF8_JOINT_REASSEMBLY_FAILED: <detail>`：FDST 缺失，或重组链路失败
     *   （具体原因由 [reassembleKf8Text] 另行记录自己的 `KF8_FDST_*` / `KF8_INDEX_*` 码）。
     */
    private fun readKf8Segment(
        file: RandomAccessFile,
        offsets: LongArray,
        fileLength: Long,
        boundary: Int,
        headerMobi7: MobiHeader?,
    ): Kf8Segment? {
        if (boundary <= 0 || boundary >= offsets.size) {
            warnKf8("KF8_HEADER_INVALID: boundary=$boundary / records=${offsets.size}")
            return null
        }
        if (offsets.size - boundary < 2) {
            warnKf8("KF8_TEXT_RECORD_INVALID: boundary=$boundary has no text records")
            return null
        }
        val headerRecord = try {
            readRecord(file, offsets, boundary, fileLength)
        } catch (error: DocumentParseException) {
            warnKf8("KF8_HEADER_INVALID: ${error.code}")
            return null
        }
        val kf8PalmDoc = try {
            readPalmDocHeader(headerRecord)
        } catch (error: DocumentParseException) {
            warnKf8("KF8_HEADER_INVALID: ${error.code}")
            return null
        }
        val kf8Mobi = readMobiHeader(headerRecord)
        if (kf8Mobi == null || kf8Mobi.version < 8) {
            warnKf8("KF8_HEADER_INVALID: version=${kf8Mobi?.version ?: -1}")
            return null
        }
        if (kf8PalmDoc.encryptionType != 0) {
            warnKf8("KF8_HEADER_INVALID: encryption=${kf8PalmDoc.encryptionType}")
            return null
        }
        // FDST 记录号是重组的入口：没有它就只能回退 MOBI7，不必再白读一遍正文。
        if (kf8Mobi.fdstIndex <= 0 || kf8Mobi.fdstIndex >= offsets.size) {
            warnKf8("KF8_JOINT_REASSEMBLY_FAILED: fdst=${kf8Mobi.fdstIndex}")
            return null
        }

        val textBytes = try {
            readPalmDocTextBytes(
                file = file,
                offsets = offsets,
                fileLength = fileLength,
                palmDoc = kf8PalmDoc,
                mobi = kf8Mobi,
                firstTextRecord = boundary + 1,
            )
        } catch (error: DocumentParseException) {
            warnKf8("KF8_TEXT_RECORD_INVALID: ${error.code}")
            return null
        }
        if (decodeMobiText(textBytes, kf8Mobi).isBlank()) {
            warnKf8("KF8_TEXT_EMPTY")
            return null
        }

        val reassembled = reassembleKf8Text(file, offsets, fileLength, kf8Mobi, textBytes)
        if (reassembled == null) {
            // reassembleKf8Text 已记录具体原因码，这里只标记"合并文件的 KF8 段整体不可用"。
            warnKf8("KF8_JOINT_REASSEMBLY_FAILED: boundary=$boundary")
            return null
        }
        return Kf8Segment(
            text = reassembled,
            // KF8 头没声明图片起点时退回 MOBI7 头的：合并文件里两段常共用同一批图片记录。
            firstImageIndex = kf8Mobi.firstImageIndex.takeIf { it > 0 } ?: (headerMobi7?.firstImageIndex ?: 0),
            textEncoding = kf8Mobi.textEncoding,
        )
    }

    /**
     * 尝试按 KF8 的 skeleton/fragment 结构重组 flow 0 正文，失败返回 null。
     *
     * 触发条件：传进来的 [mobi] 是 **KF8 头**（`version >= 8`）且它给出了有效的 FDST 记录号。
     * 两个调用方都满足：独立 KF8 传 record0 的头，合并文件传 [readKf8Segment] 读出的 KF8 段头
     * （索引号、FDST、正文起点全是绝对记录号，可以直接用）。不满足时直接返回 null，
     * 调用方继续用原 text，等价于"旧行为完全不变"。
     *
     * 顺序：FDST 记录 → [Kf8Fdst.parse] 切流 → 读 skeleton/fragment 的 INDX 记录
     * （最多 [MAX_KF8_INDEX_RECORDS] 条，记录号必须落在 PalmDB 记录范围内）→
     * [Kf8IndxParser] 解析两张表 → [Kf8Reassembler.reassemble] 拼接。
     *
     * 失败码（只记码，不记正文）：
     * - `KF8_FDST_RECORD_INVALID: <code>`：FDST 记录读不出来；
     * - `KF8_FDST_INVALID: <reason>`：FDST 表校验失败（reason 取 [Kf8Fdst.parse] 的返回）；
     * - `KF8_FDST_COUNT_MISMATCH: <head> != <actual>`：MOBI 头声明的 flow 数与 FDST 不一致；
     * - `KF8_FLOW_RANGE_INVALID: ...`：flow 0 区间不落在正文内；
     * - `KF8_INDEX_RANGE_INVALID: ...` / `KF8_INDEX_RECORD_INVALID: <code>`：索引记录号越界或读取失败；
     * - `KF8_SKELETON_TABLE_INVALID: <code>` / `KF8_FRAGMENT_TABLE_INVALID: <code>`：INDX 解析失败；
     * - `KF8_REASSEMBLY_UNVERIFIED`：拼出来的 HTML 结构校验不过。
     */
    private fun reassembleKf8Text(
        file: RandomAccessFile,
        offsets: LongArray,
        fileLength: Long,
        mobi: MobiHeader?,
        textBytes: ByteArray,
    ): String? {
        if (mobi == null || mobi.version < 8) return null
        val fdstIndex = mobi.fdstIndex
        if (fdstIndex <= 0 || fdstIndex >= offsets.size) return null

        val fdstRecord = try {
            readRecord(file, offsets, fdstIndex, fileLength)
        } catch (error: DocumentParseException) {
            warnKf8("KF8_FDST_RECORD_INVALID: ${error.code}")
            return null
        }
        val fdst = Kf8Fdst.parse(fdstRecord, textBytes.size)
        if (!fdst.ok) {
            warnKf8("KF8_FDST_INVALID: ${fdst.reason}")
            return null
        }
        if (mobi.fdstCount > 0 && mobi.fdstCount != fdst.flows.size) {
            warnKf8("KF8_FDST_COUNT_MISMATCH: ${mobi.fdstCount} != ${fdst.flows.size}")
            return null
        }
        val flow = fdst.flows.first()
        if (flow.start < 0 || flow.end > textBytes.size || flow.start >= flow.end) {
            warnKf8("KF8_FLOW_RANGE_INVALID: ${flow.start}..${flow.end} / ${textBytes.size}")
            return null
        }
        val flowBytes = textBytes.copyOfRange(flow.start, flow.end)

        val skeletonRecords = readIndexRecords(file, offsets, fileLength, mobi.skeletonIndex) ?: return null
        val fragmentRecords = readIndexRecords(file, offsets, fileLength, mobi.fragmentIndex) ?: return null

        val parser = Kf8IndxParser()
        val skeleton = try {
            parser.readSkeletonTable(skeletonRecords)
        } catch (error: Kf8IndexException) {
            warnKf8("KF8_SKELETON_TABLE_INVALID: ${error.code}")
            return null
        }
        val fragment = try {
            parser.readFragmentTable(fragmentRecords)
        } catch (error: Kf8IndexException) {
            warnKf8("KF8_FRAGMENT_TABLE_INVALID: ${error.code}")
            return null
        }
        val reassembled = Kf8Reassembler.reassemble(flowBytes, skeleton, fragment, charsetFor(mobi))
        if (reassembled == null) {
            warnKf8("KF8_REASSEMBLY_UNVERIFIED")
            return null
        }
        return reassembled
    }

    /**
     * 从 [firstIndex] 起连读最多 [MAX_KF8_INDEX_RECORDS] 条记录给索引解析器。
     *
     * KF8 的索引是一串连续记录（索引头 + CNCX + 若干数据记录），但头部只给起点不给条数，
     * 所以这里固定读一个小上限：读多了会被解析器当成数据记录忽略或校验失败，
     * 读少了条目不全——两种情况都只会退化成"回退原文"，不会读坏内存。
     */
    private fun readIndexRecords(
        file: RandomAccessFile,
        offsets: LongArray,
        fileLength: Long,
        firstIndex: Int,
    ): List<ByteArray>? {
        if (firstIndex <= 0 || firstIndex >= offsets.size) {
            warnKf8("KF8_INDEX_RANGE_INVALID: $firstIndex / ${offsets.size}")
            return null
        }
        val end = minOf(offsets.size, firstIndex + MAX_KF8_INDEX_RECORDS)
        val records = ArrayList<ByteArray>(end - firstIndex)
        for (index in firstIndex until end) {
            records += try {
                readRecord(file, offsets, index, fileLength)
            } catch (error: DocumentParseException) {
                warnKf8("KF8_INDEX_RECORD_INVALID: ${error.code}")
                return null
            }
        }
        return records
    }

    /** KF8 正文编码：只认 MOBI 头声明的两种，其余按 UTF-8（重组结果只用于切节）。 */
    private fun charsetFor(mobi: MobiHeader): Charset = when (mobi.textEncoding) {
        TEXT_ENCODING_UTF8 -> StandardCharsets.UTF_8
        TEXT_ENCODING_CP1252 -> Charset.forName("windows-1252")
        else -> StandardCharsets.UTF_8
    }

    /** 只记警告码：KF8 链路失败要可观测，但正文严禁进日志。 */
    private fun warnKf8(code: String) {
        Log.w(LOG_TAG, code)
    }

    // ---------------------------------------------------------------- HTML → sections

    private fun buildSectionDrafts(text: String, images: MobiImageRegistry): List<MobiSectionDraft> {
        val document = Jsoup.parse(text, "", Parser.htmlParser())
        document.outputSettings().prettyPrint(false)
        removeDangerousContent(document)
        rewriteImages(document, images)
        val body = document.body() ?: return emptyList()

        val pieces = mutableListOf<MobiPiece>()
        body.childNodes().forEach { node ->
            when (node) {
                is TextNode -> {
                    val value = node.text().trim()
                    if (value.isNotEmpty()) {
                        pieces += MobiPiece.Content("<p>${ParserIO.escapeHtml(value)}</p>", value)
                    }
                }
                is Element -> {
                    when (localName(node)) {
                        "pagebreak" -> pieces += MobiPiece.PageBreak
                        "h1", "h2" -> pieces += MobiPiece.Heading(cleanText(node.text()).takeIf { it.isNotEmpty() })
                        else -> pieces += MobiPiece.Content(node.outerHtml(), node.text().trim())
                    }
                }
            }
        }
        if (pieces.isEmpty()) return emptyList()
        val hasBoundary = pieces.any { it is MobiPiece.Heading || it === MobiPiece.PageBreak }
        return if (hasBoundary) assembleByBoundary(pieces) else splitByLength(pieces)
    }

    private fun assembleByBoundary(pieces: List<MobiPiece>): List<MobiSectionDraft> {
        val drafts = mutableListOf<MobiSectionDraft>()
        var blocks = mutableListOf<String>()
        var bodyChars = 0
        var pendingTitle: String? = null

        fun flush() {
            if (blocks.isEmpty()) return
            drafts += MobiSectionDraft(pendingTitle, blocks, bodyChars)
            blocks = mutableListOf()
            bodyChars = 0
            pendingTitle = null
        }

        pieces.forEach { piece ->
            when (piece) {
                is MobiPiece.Heading -> {
                    flush()
                    pendingTitle = piece.title
                }
                MobiPiece.PageBreak -> flush()
                is MobiPiece.Content -> {
                    blocks += piece.html
                    bodyChars += piece.text.length
                }
            }
        }
        flush()
        return drafts
    }

    /** 没有标题也没有分页符时的兜底：按纯文本每 8 KiB 切一节。 */
    private fun splitByLength(pieces: List<MobiPiece>): List<MobiSectionDraft> {
        val drafts = mutableListOf<MobiSectionDraft>()
        var blocks = mutableListOf<String>()
        var chars = 0
        pieces.forEach { piece ->
            if (piece !is MobiPiece.Content) return@forEach
            if (chars > 0 && chars + piece.text.length > CHUNK_CHARS) {
                drafts += MobiSectionDraft(null, blocks, chars)
                blocks = mutableListOf()
                chars = 0
            }
            blocks += piece.html
            chars += piece.text.length
        }
        if (blocks.isNotEmpty()) {
            drafts += MobiSectionDraft(null, blocks, chars)
        }
        return drafts
    }

    private fun removeDangerousContent(document: Document) {
        document.select("script, style, iframe, object, embed, form, input, button, noscript").remove()
        document.getAllElements().forEach { element ->
            val keys = element.attributes().asList().map { it.key }
            keys.forEach { key ->
                if (key.startsWith("on", ignoreCase = true)) element.removeAttr(key)
            }
            listOf("href", "src").forEach { name ->
                if (element.hasAttr(name) &&
                    element.attr(name).trim().startsWith("javascript:", ignoreCase = true)
                ) {
                    element.removeAttr(name)
                }
            }
        }
    }

    private fun rewriteImages(document: Document, images: MobiImageRegistry) {
        document.getAllElements().forEach { element ->
            if (localName(element) != "img") return@forEach
            val recIndex = attrLocal(element, "recindex")?.trim()?.toIntOrNull() ?: return@forEach
            val relativePath = images.resolve(recIndex)
            if (relativePath == null) {
                // 记录里读不到对应图片：移除 <img>，不留断链（files 里也不登记）。
                element.remove()
            } else {
                element.attr("src", "../$relativePath")
                element.removeAttr("recindex")
            }
        }
    }

    // ---------------------------------------------------------------- 小工具

    private fun localName(element: Element): String =
        element.tagName().substringAfter(':').lowercase(Locale.ROOT)

    private fun attrLocal(element: Element, name: String): String? {
        element.attributes().forEach { attribute ->
            if (attribute.key.substringAfter(':').equals(name, ignoreCase = true)) {
                return attribute.value
            }
        }
        return null
    }

    private fun cleanText(value: String, maxLength: Int = 120): String {
        val builder = StringBuilder(value.length)
        value.forEach { ch ->
            if (ch.isISOControl()) {
                if (ch == '\n' || ch == '\t' || ch == '\r') builder.append(' ')
            } else {
                builder.append(ch)
            }
        }
        return builder.toString().replace(WHITESPACE, " ").trim().take(maxLength)
    }

    private fun matchesAscii(bytes: ByteArray, offset: Int, text: String): Boolean {
        if (offset < 0 || offset + text.length > bytes.size) return false
        for (index in text.indices) {
            if (bytes[offset + index] != text[index].code.toByte()) return false
        }
        return true
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun u32(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun u32OrZero(bytes: ByteArray, offset: Int): Int =
        if (offset < 0 || offset + 4 > bytes.size) 0 else u32(bytes, offset)

    /** 无符号 32 位读取：记录偏移是 u32，用 Int 会变成负数，必须用 Long。 */
    private fun u32Long(bytes: ByteArray, offset: Int): Long =
        if (offset < 0 || offset + 4 > bytes.size) 0L else u32(bytes, offset).toLong() and 0xFFFFFFFFL

    private data class PalmDocHeader(
        val compression: Int,
        val textLength: Long,
        val textRecordCount: Int,
        val encryptionType: Int,
    )

    private data class MobiHeader(
        val headerLength: Int,
        val version: Int,
        val textEncoding: Int,
        val firstImageIndex: Int,
        val fullNameOffset: Int,
        val fullNameLength: Int,
        val exthFlags: Int,
        val drmFlags: Int,
        val huffRecordIndex: Int,
        val huffRecordCount: Int,
        val extraDataFlags: Int,
        val fdstIndex: Int = 0,
        val fdstCount: Int = 0,
        val fragmentIndex: Int = 0,
        val skeletonIndex: Int = 0,
        val guideIndex: Int = 0,
    )

    private data class ExthInfo(
        val title: String?,
        val author: String?,
        val hasDrm: Boolean,
        /** EXTH 121：合并文件里 KF8 头所在记录号；不存在为 null。 */
        val boundaryRecord: Int? = null,
    ) {
        companion object {
            val EMPTY = ExthInfo(null, null, false)
        }
    }

    private data class MobiSectionDraft(
        val title: String?,
        val blocks: List<String>,
        val bodyChars: Int,
    )

    private sealed class MobiPiece {
        class Heading(val title: String?) : MobiPiece()
        object PageBreak : MobiPiece()
        class Content(val html: String, val text: String) : MobiPiece()
    }

    /** Decodes PalmDOC HUFF/CDIC blocks with bounded output and cycle checks on nested phrases. */
    private class HuffCdicDecoder(records: List<ByteArray>, private val outputLimit: Long) {
        private data class Prefix(val length: Int, val terminal: Boolean, val maxCode: Long)
        private data class Phrase(val bytes: ByteArray, val terminal: Boolean)

        private val prefixes: Array<Prefix>
        private val minCode = LongArray(MAX_CODE_BITS + 1)
        private val maxCode = LongArray(MAX_CODE_BITS + 1)
        private val phrases = mutableListOf<Phrase>()

        init {
            val huff = records.firstOrNull() ?: corrupt("HUFF 记录缺失")
            if (!hasMagic(huff, "HUFF") || uint32(huff, 4) != HUFF_HEADER_SIZE.toLong()) {
                corrupt("HUFF 头无效")
            }
            val dict1Offset = uint32(huff, 8).toIntChecked("HUFF dict1 偏移")
            val dict2Offset = uint32(huff, 12).toIntChecked("HUFF dict2 偏移")
            requireRange(huff, dict1Offset, PREFIX_COUNT * 4, "HUFF dict1")
            requireRange(huff, dict2Offset, MAX_CODE_BITS * 2 * 4, "HUFF dict2")

            prefixes = Array(PREFIX_COUNT) { index ->
                val packed = uint32(huff, dict1Offset + index * 4)
                val length = (packed and 0x1F).toInt()
                val terminal = packed and 0x80L != 0L
                var limit = packed ushr 8
                if (length !in 1..MAX_CODE_BITS) corrupt("HUFF dict1 码长无效")
                if (length <= PREFIX_BITS) {
                    if (!terminal) corrupt("HUFF 短码表标记无效")
                    limit = expandedMaximum(limit, length)
                }
                Prefix(length, terminal, limit)
            }

            for (length in 1..MAX_CODE_BITS) {
                val minValue = uint32(huff, dict2Offset + ((length - 1) * 2) * 4)
                val maxValue = uint32(huff, dict2Offset + ((length - 1) * 2 + 1) * 4)
                minCode[length] = expandedMinimum(minValue, length)
                maxCode[length] = expandedMaximum(maxValue, length)
                if (minCode[length] > maxCode[length]) corrupt("HUFF code range is inverted")
            }

            var expectedPhraseCount = -1
            var expectedBits = -1
            records.drop(1).forEach { cdic ->
                if (!hasMagic(cdic, "CDIC") || uint32(cdic, 4) != CDIC_HEADER_SIZE.toLong()) {
                    corrupt("CDIC 头无效")
                }
                val declaredCount = uint32(cdic, 8)
                val bits = uint32(cdic, 12).toInt()
                if (declaredCount !in 1..MAX_PHRASE_COUNT.toLong() || bits !in 0..MAX_PHRASE_BITS) {
                    corrupt("CDIC 字典规格无效")
                }
                if (expectedPhraseCount < 0) {
                    expectedPhraseCount = declaredCount.toInt()
                    expectedBits = bits
                } else if (declaredCount != expectedPhraseCount.toLong() || bits != expectedBits) {
                    corrupt("CDIC 字典分段不一致")
                }
                val remaining = expectedPhraseCount - phrases.size
                if (remaining <= 0) corrupt("CDIC 出现多余记录")
                val segmentCapacity = 1 shl expectedBits
                val count = minOf(segmentCapacity, remaining)
                val tableBytes = count * 2
                requireRange(cdic, CDIC_HEADER_SIZE, tableBytes, "CDIC phrase table")
                for (index in 0 until count) {
                    val relativeOffset = uint16(cdic, CDIC_HEADER_SIZE + index * 2)
                    if (relativeOffset < tableBytes) corrupt("CDIC phrase offset overlaps table")
                    val lengthAt = CDIC_HEADER_SIZE + relativeOffset
                    requireRange(cdic, lengthAt, 2, "CDIC phrase length")
                    val lengthFlag = uint16(cdic, lengthAt)
                    val phraseLength = lengthFlag and PHRASE_LENGTH_MASK
                    val phraseStart = lengthAt + 2
                    requireRange(cdic, phraseStart, phraseLength, "CDIC phrase bytes")
                    phrases += Phrase(
                        bytes = cdic.copyOfRange(phraseStart, phraseStart + phraseLength),
                        terminal = lengthFlag and PHRASE_TERMINAL_FLAG != 0,
                    )
                }
            }
            if (expectedPhraseCount <= 0 || phrases.size != expectedPhraseCount) {
                corrupt("CDIC 字典不完整")
            }
        }

        fun decompress(block: ByteArray, maxOutputBytes: Long): ByteArray {
            val output = GrowableBuffer(maxOutputBytes.coerceIn(0L, outputLimit))
            decodeBlock(block, output, BooleanArray(phrases.size), depth = 0)
            return output.toByteArray()
        }

        private fun decodeBlock(
            bytes: ByteArray,
            output: GrowableBuffer,
            activePhrases: BooleanArray,
            depth: Int,
        ) {
            var bitOffset = 0
            val bitLength = bytes.size.toLong() * 8L
            while (bitOffset.toLong() < bitLength) {
                val code = peekCode32(bytes, bitOffset)
                val prefix = prefixes[(code ushr 24).toInt()]
                var length = prefix.length
                var limit = prefix.maxCode
                while (code < minCode[length]) {
                    if (length == MAX_CODE_BITS) corrupt("HUFF code is shorter than every dictionary code")
                    length += 1
                    limit = maxCode[length]
                }
                if (code > limit) corrupt("HUFF code is outside its declared range")
                val remainingBits = bitLength - bitOffset
                // The last byte may contain unused padding bits; an incomplete final code is padding.
                if (remainingBits < length) return
                val phraseIndex = ((limit - code) ushr (MAX_CODE_BITS - length)).toInt()
                if (phraseIndex !in phrases.indices) corrupt("HUFF phrase index is outside CDIC")
                bitOffset += length
                appendPhrase(phraseIndex, output, activePhrases, depth)
            }
        }

        private fun appendPhrase(
            phraseIndex: Int,
            output: GrowableBuffer,
            activePhrases: BooleanArray,
            depth: Int,
        ) {
            val phrase = phrases[phraseIndex]
            if (phrase.terminal) {
                output.write(phrase.bytes, 0, phrase.bytes.size)
                return
            }
            if (depth >= MAX_PHRASE_NESTING || activePhrases[phraseIndex]) {
                corrupt("CDIC phrase nesting is cyclic or too deep")
            }
            activePhrases[phraseIndex] = true
            try {
                decodeBlock(phrase.bytes, output, activePhrases, depth + 1)
            } finally {
                activePhrases[phraseIndex] = false
            }
        }

        private fun peekCode32(bytes: ByteArray, bitOffset: Int): Long {
            val firstByte = bitOffset ushr 3
            val shift = bitOffset and 7
            var window = 0L
            for (index in 0 until 5) {
                val at = firstByte + index
                val next = if (at < bytes.size) bytes[at].toLong() and 0xFFL else 0L
                window = (window shl 8) or next
            }
            return (window ushr (8 - shift)) and UINT32_MAX
        }

        private fun expandedMinimum(value: Long, length: Int): Long {
            if (value >= (1L shl length)) corrupt("HUFF minimum code exceeds its bit width")
            return value shl (MAX_CODE_BITS - length)
        }

        private fun expandedMaximum(value: Long, length: Int): Long {
            val exclusive = value + 1L
            if (exclusive > (1L shl length)) corrupt("HUFF maximum code exceeds its bit width")
            return (exclusive shl (MAX_CODE_BITS - length)) - 1L
        }

        private fun Long.toIntChecked(label: String): Int {
            if (this > Int.MAX_VALUE) corrupt("$label is too large")
            return toInt()
        }

        private fun uint16(bytes: ByteArray, offset: Int): Int {
            requireRange(bytes, offset, 2, "uint16")
            return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
        }

        private fun uint32(bytes: ByteArray, offset: Int): Long {
            requireRange(bytes, offset, 4, "uint32")
            return ((bytes[offset].toLong() and 0xFFL) shl 24) or
                ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
                ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
                (bytes[offset + 3].toLong() and 0xFFL)
        }

        private fun hasMagic(bytes: ByteArray, magic: String): Boolean =
            bytes.size >= 4 && magic.indices.all { bytes[it] == magic[it].code.toByte() }

        private fun requireRange(bytes: ByteArray, offset: Int, length: Int, label: String) {
            if (offset < 0 || length < 0 || offset.toLong() + length > bytes.size) {
                corrupt("$label is outside record bounds")
            }
        }

        private fun corrupt(message: String): Nothing =
            throw DocumentParseException("CORRUPT_INPUT", "MOBI HUFF/CDIC 数据无效：$message")

        companion object {
            private const val HUFF_HEADER_SIZE = 24
            private const val CDIC_HEADER_SIZE = 16
            private const val PREFIX_BITS = 8
            private const val PREFIX_COUNT = 1 shl PREFIX_BITS
            private const val MAX_CODE_BITS = 32
            private const val MAX_PHRASE_BITS = 16
            private const val MAX_PHRASE_COUNT = 1 shl MAX_PHRASE_BITS
            private const val MAX_PHRASE_NESTING = 64
            private const val PHRASE_LENGTH_MASK = 0x7FFF
            private const val PHRASE_TERMINAL_FLAG = 0x8000
            private const val UINT32_MAX = 0xFFFF_FFFFL
        }
    }

    /** 可按需扩容的字节缓冲：LZ77 回退引用需要随机访问已输出内容（重叠复制）。 */
    private class GrowableBuffer(private val limit: Long) {
        private var data = ByteArray(INITIAL_BUFFER_BYTES)
        var size: Int = 0
            private set

        fun writeByte(value: Int) {
            ensureCapacity(1)
            data[size] = value.toByte()
            size += 1
        }

        fun write(source: ByteArray, offset: Int, length: Int) {
            if (length <= 0) return
            ensureCapacity(length)
            System.arraycopy(source, offset, data, size, length)
            size += length
        }

        fun copyBack(distance: Int, length: Int) {
            ensureCapacity(length)
            var sourceIndex = size - distance
            var remaining = length
            // 必须逐字节复制：distance < length 时源区间与目标区间重叠，
            // 一次性 arraycopy 会把"重复展开"的语义变成快照复制。
            while (remaining > 0) {
                data[size] = data[sourceIndex]
                size += 1
                sourceIndex += 1
                remaining -= 1
            }
        }

        fun toByteArray(): ByteArray = data.copyOf(size)

        private fun ensureCapacity(extra: Int) {
            if (size.toLong() + extra > limit) {
                throw DocumentParseException("LIMIT_EXCEEDED", "PalmDOC 解压结果超过 $limit 字节上限")
            }
            val required = size + extra
            if (required <= data.size) return
            var capacity = data.size
            while (capacity < required) {
                capacity = if (capacity > Int.MAX_VALUE / 2) Int.MAX_VALUE else capacity * 2
            }
            data = data.copyOf(capacity)
        }

        private companion object {
            const val INITIAL_BUFFER_BYTES = 64
        }
    }

    /**
     * MOBI 图片记录登记处：`recindex` 是 1 起的序号，对应记录
     * `firstImageIndex + recindex - 1`（MOBI 头 +92 声明首张图片记录下标）。
     */
    private class MobiImageRegistry(
        private val outputDirectory: File,
        private val firstImageIndex: Int,
        private val recordCount: Int,
        private val recordLoader: (Int) -> ByteArray,
    ) {
        val files = mutableListOf<ParsedFile>()
        private val resolved = mutableMapOf<Int, String?>()

        /** 返回相对输出根目录的路径（如 `images/image-1.png`），读不到返回 null。 */
        fun resolve(recIndex: Int): String? {
            if (resolved.containsKey(recIndex)) return resolved[recIndex]
            val path = load(recIndex)
            resolved[recIndex] = path
            return path
        }

        private fun load(recIndex: Int): String? {
            if (recIndex <= 0 || firstImageIndex <= 0) return null
            val recordIndex = firstImageIndex + recIndex - 1
            if (recordIndex <= 0 || recordIndex >= recordCount) return null
            val raw = try {
                recordLoader(recordIndex)
            } catch (error: DocumentParseException) {
                // 单张图片记录损坏不该让整本书导入失败；但资源上限类错误必须继续上抛，
                // 否则"跳过坏图"就变成了绕过 32 MiB/64 MiB 限制的后门。
                if (error.code == "LIMIT_EXCEEDED") throw error
                return null
            }
            val detected = detectImage(raw) ?: return null
            val extension = detected.first
            val payload = raw.copyOfRange(detected.second, raw.size)
            if (payload.isEmpty()) return null
            val relativePath = "images/image-$recIndex.$extension"
            ParserIO.writeBytes(outputDirectory, relativePath, payload)
            files += ParsedFile(relativePath, ManifestFileKind.IMAGE)
            return relativePath
        }

        private fun detectImage(raw: ByteArray): Pair<String, Int>? {
            val scanEnd = minOf(raw.size, IMAGE_SCAN_BYTES)
            var index = 0
            while (index < scanEnd) {
                if (isPng(raw, index)) return "png" to index
                if (isJpeg(raw, index)) return "jpg" to index
                if (isGif(raw, index)) return "gif" to index
                if (isWebp(raw, index)) return "webp" to index
                // "BM" 只有两个字节，在任意位置命中率太高，只在记录开头认。
                if (index == 0 && isBmp(raw, index)) return "bmp" to index
                index += 1
            }
            return null
        }

        private fun isPng(bytes: ByteArray, offset: Int): Boolean =
            offset + 8 <= bytes.size &&
                bytes[offset] == 0x89.toByte() &&
                bytes[offset + 1] == 0x50.toByte() &&
                bytes[offset + 2] == 0x4E.toByte() &&
                bytes[offset + 3] == 0x47.toByte() &&
                bytes[offset + 4] == 0x0D.toByte() &&
                bytes[offset + 5] == 0x0A.toByte() &&
                bytes[offset + 6] == 0x1A.toByte() &&
                bytes[offset + 7] == 0x0A.toByte()

        private fun isJpeg(bytes: ByteArray, offset: Int): Boolean =
            offset + 3 <= bytes.size &&
                bytes[offset] == 0xFF.toByte() &&
                bytes[offset + 1] == 0xD8.toByte() &&
                bytes[offset + 2] == 0xFF.toByte()

        private fun isGif(bytes: ByteArray, offset: Int): Boolean =
            offset + 6 <= bytes.size &&
                (matchesAscii(bytes, offset, "GIF87a") || matchesAscii(bytes, offset, "GIF89a"))

        private fun isWebp(bytes: ByteArray, offset: Int): Boolean =
            offset + 12 <= bytes.size &&
                matchesAscii(bytes, offset, "RIFF") &&
                matchesAscii(bytes, offset + 8, "WEBP")

        private fun isBmp(bytes: ByteArray, offset: Int): Boolean =
            offset + 2 <= bytes.size && bytes[offset] == 'B'.code.toByte() && bytes[offset + 1] == 'M'.code.toByte()

        private fun matchesAscii(bytes: ByteArray, offset: Int, text: String): Boolean {
            if (offset < 0 || offset + text.length > bytes.size) return false
            for (index in text.indices) {
                if (bytes[offset + index] != text[index].code.toByte()) return false
            }
            return true
        }

        private companion object {
            const val IMAGE_SCAN_BYTES = 128
        }
    }

    private companion object {
        const val PALM_HEADER_BYTES = 78
        const val PALM_TYPE_OFFSET = 60
        const val PALM_RECORD_COUNT_OFFSET = 76
        const val RECORD_INFO_BYTES = 8
        const val PALMDOC_HEADER_BYTES = 16
        const val MAX_RECORD_BYTES = 32L * 1024 * 1024
        const val MAX_HUFF_DATA_BYTES = 32L * 1024 * 1024
        const val CHUNK_CHARS = 8 * 1024
        const val MAX_EXTH_RECORDS = 1000

        /** KF8 索引连读上限：索引头 + CNCX + 最多两条数据记录。 */
        const val MAX_KF8_INDEX_RECORDS = 4
        const val LOG_TAG = "LegacyEbook"

        const val COMPRESSION_NONE = 1
        const val COMPRESSION_PALMDOC = 2
        const val COMPRESSION_HUFF_CDIC = 17480
        const val MAX_HUFF_RECORDS = 256
        const val MAX_MOBI_VARINT_BYTES = 4

        const val TEXT_ENCODING_UTF8 = 65001
        const val TEXT_ENCODING_CP1252 = 1252

        const val EXTH_AUTHOR = 100
        const val EXTH_UPDATED_TITLE = 503
        const val EXTH_DRM_SERVER = 406
        const val EXTH_DRM_COMMERCE = 407
        const val EXTH_DRM_EBOOKBASE = 408

        /** EXTH 121 `Mobi8-Boundary-Section`：合并文件里 KF8 头所在的 PDB 记录号。 */
        const val EXTH_MOBI8_BOUNDARY = 121

        val WHITESPACE = Regex("\\s+")
    }
}
