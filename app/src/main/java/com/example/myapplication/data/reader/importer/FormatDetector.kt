package com.example.myapplication.data.reader.importer

import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

enum class ReaderFormat {
    EPUB,
    PDF,
    TXT,
    MARKDOWN,
    HTML,
    MHTML,
    FB2,
    MOBI,
    AZW,
    AZW3,
    CBZ,
    UNKNOWN,
}

enum class FormatConfidence {
    HIGH,
    MEDIUM,
    LOW,
}

data class FormatDetection(
    val format: ReaderFormat,
    val confidence: FormatConfidence,
    val evidence: String,
)

/**
 * Content-first format sniffing. File extensions are hints only; a misleading
 * extension must not turn arbitrary bytes into a PDF/EPUB import.
 *
 * 第五期补充（P5-01）：容器型格式（EPUB/FB2.zip/CBZ）只看前 4 KiB 无法判定，
 * 因此新增 [detect] 的 File 重载——先按魔数嗅探，ZIP 容器再读**中央目录**条目名判定，
 * 仍然不看扩展名下结论（扩展名只影响置信度与 AZW/AZW3 区分）。
 */
object FormatDetector {
    private const val DEFAULT_PROBE_BYTES = 4096
    /** PalmDB 补读上限：record0 偏移再大也只补读到 1 MiB，避免为探测读整本大书。 */
    private const val EXTENDED_PROBE_LIMIT = 1 shl 20
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val ZIP_EMPTY_MAGIC = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    private val PDF_MAGIC = "%PDF-".toByteArray(StandardCharsets.US_ASCII)
    private val MOBI_MAGIC = "MOBI".toByteArray(StandardCharsets.US_ASCII)
    private const val PALM_TYPE_OFFSET = 60
    private const val PALM_CREATOR_OFFSET = 64
    private const val PALM_HEADER_MIN_BYTES = 78

    fun detect(
        input: InputStream,
        displayName: String?,
        maxProbeBytes: Int = DEFAULT_PROBE_BYTES,
    ): FormatDetection {
        require(maxProbeBytes >= 8) { "probe size is too small" }
        val probe = ByteArray(maxProbeBytes)
        var length = 0
        while (length < probe.size) {
            val read = input.read(probe, length, probe.size - length)
            if (read < 0) break
            if (read == 0) continue
            length += read
        }
        return detect(probe.copyOf(length), displayName)
    }

    fun detect(header: ByteArray, displayName: String?): FormatDetection {
        val extension = extensionOf(displayName)
        val hasPdfMagic = header.indexOf(PDF_MAGIC, maxStart = 1024) >= 0
        val isZip = header.startsWith(ZIP_MAGIC) || header.startsWith(ZIP_EMPTY_MAGIC)
        val textLike = looksLikeText(header)

        return when {
            hasPdfMagic -> FormatDetection(
                ReaderFormat.PDF,
                FormatConfidence.HIGH,
                "PDF header (extension=$extension)",
            )

            // PalmDB 容器：offset 60 是类型 "BOOK"，64 是创建者（MOBI/AZW/AZW3 都是 "MOBI"）。
            // 这类格式没有独立魔数，只能看容器头；扩展名只用来区分 AZW 家族。
            isPalmBook(header) -> detectPalmBook(header, extension)

            isZip && extension == "epub" -> FormatDetection(
                ReaderFormat.EPUB,
                FormatConfidence.HIGH,
                "ZIP container with .epub hint",
            )

            isZip -> FormatDetection(
                ReaderFormat.UNKNOWN,
                FormatConfidence.LOW,
                "ZIP container without a supported extension; content probe required",
            )

            // MHTML 必须在 HTML 之前判：MHTML 的分段里同样含 <html>，
            // 但它有 MIME 头（MIME-Version/Content-Location/Content-Type: multipart/related）。
            textLike && looksLikeMhtml(header) -> FormatDetection(
                ReaderFormat.MHTML,
                if (extension in MHTML_EXTENSIONS) FormatConfidence.HIGH else FormatConfidence.MEDIUM,
                "MIME headers with embedded HTML (extension=$extension)",
            )

            textLike && looksLikeHtml(header) -> FormatDetection(
                ReaderFormat.HTML,
                if (extension in HTML_EXTENSIONS) FormatConfidence.HIGH else FormatConfidence.MEDIUM,
                "HTML markup in text content (extension=$extension)",
            )

            extension in MARKDOWN_EXTENSIONS && textLike -> FormatDetection(
                ReaderFormat.MARKDOWN,
                if (header.startsWith(UTF8_BOM) || header.startsWith(UTF16_LE_BOM) || header.startsWith(UTF16_BE_BOM)) {
                    FormatConfidence.MEDIUM
                } else {
                    FormatConfidence.HIGH
                },
                "Markdown extension with text content",
            )

            extension == "txt" && textLike -> FormatDetection(
                ReaderFormat.TXT,
                FormatConfidence.HIGH,
                "TXT extension with text content",
            )

            textLike -> FormatDetection(
                ReaderFormat.TXT,
                FormatConfidence.LOW,
                "Plain text content without a decisive extension",
            )

            else -> FormatDetection(
                ReaderFormat.UNKNOWN,
                FormatConfidence.LOW,
                "No supported magic or text encoding detected (extension=$extension)",
            )
        }
    }

    /**
     * 文件级嗅探：ZIP 容器需要中央目录才能区分 EPUB / FB2.zip / CBZ，
     * 因此导入入口应当调用这个重载而不是只传流。
     */
    fun detect(file: File, displayName: String?): FormatDetection {
        if (!file.isFile) {
            return FormatDetection(ReaderFormat.UNKNOWN, FormatConfidence.LOW, "source file is missing")
        }
        var header = readProbe(file, DEFAULT_PROBE_BYTES)
        // PalmDB 的 record0（含 MOBI 头）在记录表之后：记录多的书它会落在 4 KiB 探针之外，
        // 那样就读不到 version，AZW3 会被当成 MOBI7。这里按记录表声明的偏移补读一次。
        if (isPalmBook(header)) {
            val record0 = palmRecord0Offset(header)
            val needed = if (record0 == null) 0 else record0 + 24
            if (record0 != null && needed > header.size) {
                val extended = readProbe(file, minOf(needed, EXTENDED_PROBE_LIMIT))
                if (extended.size > header.size) header = extended
            }
        }
        // 容器型格式以**中央目录**为准：扩展名叫 .epub 但内容是漫画包的必须判成 CBZ，
        // 反之缺扩展名的真 EPUB 也要能识别。扩展名只在条目判定里作置信度提示。
        val isZip = header.startsWith(ZIP_MAGIC) || header.startsWith(ZIP_EMPTY_MAGIC)
        if (!isZip) return detect(header, displayName)
        if (!file.looksLikeZip()) {
            return FormatDetection(ReaderFormat.UNKNOWN, FormatConfidence.LOW, "ZIP 头不完整或已损坏")
        }
        return try {
            ZipFile(file).use { zip -> detectZip(zip, displayName) }
        } catch (error: DocumentParseException) {
            FormatDetection(
                ReaderFormat.UNKNOWN,
                FormatConfidence.LOW,
                "${error.code}: ${error.message}",
            )
        } catch (error: java.util.zip.ZipException) {
            FormatDetection(ReaderFormat.UNKNOWN, FormatConfidence.LOW, "损坏的 ZIP 容器: ${error.message}")
        }
    }

    /**
     * 按 ZIP 条目名判定容器类型（只读中央目录，不解压正文）。
     *
     * 判定顺序刻意固定：EPUB 有 `mimetype`/`*.opf`，FB2.zip 有 `*.fb2`，
     * 漫画包只有图片。三者互斥，缺一个判据就会把漫画包误判成 EPUB。
     */
    fun detectZip(zip: ZipFile, displayName: String?): FormatDetection {
        val extension = extensionOf(displayName)
        val names = ArchiveSupport().listFiles(zip).map { it.name.replace('\\', '/').lowercase() }
        if (names.isEmpty()) {
            return FormatDetection(ReaderFormat.UNKNOWN, FormatConfidence.LOW, "ZIP container has no files")
        }
        val hasOpf = names.any { it.endsWith(".opf") }
        val hasEpubMimetype = names.contains("mimetype") && runCatching {
            zip.getInputStream(zip.getEntry("mimetype")).use { input ->
                val bytes = ByteArray(64)
                val read = input.read(bytes)
                read > 0 && String(bytes, 0, read, StandardCharsets.US_ASCII).contains("application/epub+zip")
            }
        }.getOrDefault(false)
        val hasFb2 = names.any { it.endsWith(".fb2") }
        val hasHtml = names.any { it.endsWith(".html") || it.endsWith(".htm") || it.endsWith(".xhtml") }
        val imageCount = names.count { name -> IMAGE_EXTENSIONS.any { name.endsWith(".$it") } }

        return when {
            hasEpubMimetype || hasOpf -> FormatDetection(
                ReaderFormat.EPUB,
                if (hasEpubMimetype) FormatConfidence.HIGH else FormatConfidence.MEDIUM,
                "ZIP 含 EPUB 包描述（mimetype=$hasEpubMimetype, opf=$hasOpf）",
            )

            hasFb2 -> FormatDetection(
                ReaderFormat.FB2,
                FormatConfidence.HIGH,
                "ZIP 含 FB2 文档（fb2.zip 容器）",
            )

            imageCount >= 3 && !hasHtml -> FormatDetection(
                ReaderFormat.CBZ,
                FormatConfidence.HIGH,
                "ZIP 含 $imageCount 个图片条目且无网页/包描述",
            )

            imageCount >= 1 && extension in COMIC_EXTENSIONS -> FormatDetection(
                ReaderFormat.CBZ,
                FormatConfidence.MEDIUM,
                "图片条目 $imageCount 个 + .$extension 扩展名",
            )

            // 只有网页没有包描述的 ZIP 不是任何受支持的单本书格式：
            // 既不能当 EPUB（缺 spine），也不该当漫画（没有图）。
            hasHtml -> FormatDetection(
                ReaderFormat.UNKNOWN,
                FormatConfidence.LOW,
                "ZIP 含网页但缺少包描述（不支持网页压缩包）",
            )

            else -> FormatDetection(
                ReaderFormat.UNKNOWN,
                FormatConfidence.LOW,
                "ZIP 内容不属于受支持的书籍格式（图片 $imageCount）",
            )
        }
    }

    private fun detectPalmBook(header: ByteArray, extension: String?): FormatDetection {
        val version = mobiVersion(header)
        // 内容优先：MOBI 头的 version 比扩展名可靠（同一本书常被改名成 .azw/.mobi）。
        // 只有读不到 version 时才退回扩展名，且解析器（LegacyEbookDocumentParser）用的是同一口径，
        // 避免"检测说 AZW3、解析说 MOBI"这种前后不一致。
        val format = when {
            version != null && version >= 8 -> ReaderFormat.AZW3
            version != null -> ReaderFormat.MOBI
            extension == "azw3" -> ReaderFormat.AZW3
            extension == "azw" -> ReaderFormat.AZW
            else -> ReaderFormat.MOBI
        }
        val confidence = if (extension in PALM_EXTENSIONS) FormatConfidence.HIGH else FormatConfidence.MEDIUM
        return FormatDetection(
            format,
            confidence,
            "PalmDB BOOK 容器（extension=$extension, mobiVersion=${version ?: "unknown"}）",
        )
    }

    /** PalmDB 头：PALM_TYPE_OFFSET 处为 "BOOK" 且创建者为 "MOBI"（AZW 家族同源）。 */
    private fun isPalmBook(bytes: ByteArray): Boolean {
        if (bytes.size < PALM_HEADER_MIN_BYTES) return false
        val type = String(bytes, PALM_TYPE_OFFSET, 4, StandardCharsets.US_ASCII)
        val creator = String(bytes, PALM_CREATOR_OFFSET, 4, StandardCharsets.US_ASCII)
        return type == "BOOK" && (creator == "MOBI" || creator == "MBOOK")
    }

    /**
     * 读取 MOBI 头里的 version 字段。
     *
     * 必须走 PalmDB 记录表定位 record0，再在 record0 + 16 处找 "MOBI" 魔数：
     * PalmDB 头的 creator 字段本身就是 "MOBI"（offset 64），直接在文件头搜魔数会命中它，
     * 于是 version 从记录表里读出一堆垃圾数字（曾把 AZW3 判成 MOBI）。
     * 只有拿不到 record0 偏移时才退回"头部盲搜"。读不到返回 null，调用方按扩展名处理。
     */
    private fun mobiVersion(bytes: ByteArray): Int? {
        val magic = mobiHeaderMagicOffset(bytes) ?: return null
        val versionOffset = magic + 20
        if (versionOffset + 4 > bytes.size) return null
        return ((bytes[versionOffset].toInt() and 0xFF) shl 24) or
            ((bytes[versionOffset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[versionOffset + 2].toInt() and 0xFF) shl 8) or
            (bytes[versionOffset + 3].toInt() and 0xFF)
    }

    /** MOBI 头魔数位置：优先按 PalmDB 记录表定位 record0；失败时在头 1 KiB 盲搜。 */
    private fun mobiHeaderMagicOffset(bytes: ByteArray): Int? {
        val record0 = palmRecord0Offset(bytes)
        if (record0 != null) {
            val magicAt = record0 + 16
            if (magicAt + 4 <= bytes.size) {
                val magic = String(bytes, magicAt, 4, StandardCharsets.US_ASCII)
                if (magic == "MOBI") return magicAt
            }
        }
        val found = bytes.indexOf(MOBI_MAGIC, maxStart = 1024)
        // offset 64 是 PalmDB 的 creator 字段（也是 "MOBI"），不是 MOBI 头，不能拿它的 +20 当 version。
        return if (found < 0 || found == PALM_CREATOR_OFFSET) null else found
    }

    /** MHTML/MHT：MIME 头 + multipart related / Content-Location。 */
    private fun looksLikeMhtml(bytes: ByteArray): Boolean {
        val head = headText(bytes, 4096).lowercase()
        val hasMimeVersion = head.contains("mime-version:")
        val hasRelated = head.contains("multipart/related") ||
            head.contains("content-location:") ||
            head.contains("content-type: text/html")
        return hasMimeVersion && hasRelated
    }

    /** HTML：文档类型或根标签/主体标签成对出现，避免把提到 "<html" 的普通文本当成网页。 */
    private fun looksLikeHtml(bytes: ByteArray): Boolean {
        val head = headText(bytes, 4096).lowercase()
        return head.contains("<!doctype html") ||
            head.contains("<html") ||
            (head.contains("<body") && (head.contains("<head") || head.contains("<meta")))
    }

    private fun headText(bytes: ByteArray, limit: Int): String =
        String(bytes, 0, minOf(limit, bytes.size), StandardCharsets.ISO_8859_1)

    private fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        if (bytes.startsWith(UTF8_BOM) || bytes.startsWith(UTF16_LE_BOM) || bytes.startsWith(UTF16_BE_BOM)) {
            return true
        }
        if (bytes.take(1024).any { it == 0.toByte() }) return false
        var printable = 0
        var total = 0
        for (byte in bytes.take(2048)) {
            total += 1
            val value = byte.toInt() and 0xFF
            if (value == 0x09 || value == 0x0A || value == 0x0D || value >= 0x20) printable += 1
        }
        return total == 0 || printable * 100 / total >= 95
    }

    fun extensionOf(displayName: String?): String? = displayName
        ?.takeIf { it.contains('.') }
        ?.substringAfterLast('.')
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() && it.length <= 16 }

    /** 读取文件头部不超过 [limit] 字节（文件更短时返回实际长度）。 */
    private fun readProbe(file: File, limit: Int): ByteArray = file.inputStream().use { input ->
        val buffer = ByteArray(limit)
        var length = 0
        while (length < buffer.size) {
            val read = input.read(buffer, length, buffer.size - length)
            if (read < 0) break
            if (read == 0) continue
            length += read
        }
        buffer.copyOf(length)
    }

    /** PalmDB 记录 0 的偏移（头 78..81，大端）；不足 78 字节返回 null。 */
    private fun palmRecord0Offset(bytes: ByteArray): Int? {
        if (bytes.size < PALM_HEADER_MIN_BYTES) return null
        val offset = ((bytes[78].toInt() and 0xFF) shl 24) or
            ((bytes[79].toInt() and 0xFF) shl 16) or
            ((bytes[80].toInt() and 0xFF) shl 8) or
            (bytes[81].toInt() and 0xFF)
        return offset.takeIf { it > 0 }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (index in prefix.indices) {
            if (this[index] != prefix[index]) return false
        }
        return true
    }

    private fun ByteArray.indexOf(needle: ByteArray, maxStart: Int): Int {
        if (needle.isEmpty()) return 0
        val lastStart = minOf(maxStart, size - needle.size)
        if (lastStart < 0) return -1
        for (start in 0..lastStart) {
            var matches = true
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return start
        }
        return -1
    }

    private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdown", "mkd")
    private val HTML_EXTENSIONS = setOf("html", "htm")
    private val MHTML_EXTENSIONS = setOf("mhtml", "mht")
    private val COMIC_EXTENSIONS = setOf("cbz", "zip")
    private val PALM_EXTENSIONS = setOf("mobi", "azw", "azw3", "prc", "pdb")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")
}
