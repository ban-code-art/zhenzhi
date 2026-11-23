package com.example.myapplication.data.reader.importer

import com.example.myapplication.data.reader.storage.ContentManifest
import com.example.myapplication.data.reader.storage.DocumentStorageException
import com.example.myapplication.data.reader.storage.ManifestFileKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * M04 Markdown 附件（相对本地图片）采集契约。
 *
 * - 导入入口（[ImportCoordinator]）在仍持有 SAF/文件授权时，按 [ImportAttachmentResolver] 把 MD 引用的
 *   相对图片复制进本 job staging 的 `attachments/`，并写入逐引用状态索引；Worker 稍后只读私有副本。
 * - 解析器把采集成功的图片按内容哈希写入 revision 的 `resources/md-<sha256>.<ext>`（IMAGE），
 *   其余引用替换为带状态的可见占位，绝不保留指向不存在文件的图片或伪造内容；不下载外网图片。
 * - revision 内的 `resources/markdown-attachments.json` 记录引用→资源映射，重新解析据此只用旧 revision 已保存的文件。
 */
enum class MarkdownAttachmentStatus(val label: String) {
    COLLECTED("已采集"),
    NOT_ACCESSIBLE("未获得图片所在位置的访问授权，可与文档一同选择图片文件"),
    MISSING("图片文件不存在"),
    OUT_OF_SCOPE("图片路径超出授权范围"),
    PERMISSION_DENIED("无权读取图片"),
    AMBIGUOUS("存在多个同名图片，无法确定对应文件"),
    LIMIT_EXCEEDED("图片超过大小或数量限制"),
    UNSUPPORTED_TYPE("不是可识别的图片文件"),
    READ_FAILED("图片读取失败"),
    REMOTE_NOT_LOADED("外部网络图片不会自动加载"),
    INVALID_REFERENCE("图片路径无效"),
}

data class MarkdownAttachmentLimits(
    val maxCount: Int = 200,
    val maxBytes: Long = 32L * 1024 * 1024,
    val maxTotalBytes: Long = 128L * 1024 * 1024,
) {
    init {
        require(maxCount > 0 && maxBytes > 0 && maxTotalBytes > 0) { "attachment limits must be positive" }
    }
}

sealed interface MarkdownResourceTarget {
    /** 空、页内锚点、data URI：保持原样。 */
    data object Ignored : MarkdownResourceTarget
    data object Remote : MarkdownResourceTarget
    /** 规范化后的相对路径，使用 `/` 分隔，可能以 `../` 开头（是否允许由 resolver 决定）。 */
    data class Local(val path: String) : MarkdownResourceTarget
    data class Rejected(val status: MarkdownAttachmentStatus) : MarkdownResourceTarget
}

object MarkdownResourceReference {
    private const val MAX_PATH_LENGTH = 512
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    private val REMOTE_SCHEMES = setOf("http", "https", "ftp")

    fun classify(raw: String): MarkdownResourceTarget {
        val value = raw.trim()
        if (value.isEmpty() || value.startsWith("#")) return MarkdownResourceTarget.Ignored
        if (value.startsWith("data:", ignoreCase = true)) return MarkdownResourceTarget.Ignored
        if (value.startsWith("//") || value.startsWith("\\\\")) return MarkdownResourceTarget.Remote
        SCHEME.find(value)?.let { match ->
            val scheme = match.groupValues[1].lowercase(Locale.ROOT)
            return if (scheme in REMOTE_SCHEMES) MarkdownResourceTarget.Remote
            // 单字母 scheme 是 Windows 盘符；file:/content: 等绝对位置不属于文档相对授权范围。
            else MarkdownResourceTarget.Rejected(MarkdownAttachmentStatus.OUT_OF_SCOPE)
        }
        if (value.startsWith("/") || value.startsWith("\\")) {
            return MarkdownResourceTarget.Rejected(MarkdownAttachmentStatus.OUT_OF_SCOPE)
        }
        // URL 语义：未编码的 ? / # 开始 query/fragment；文件名里的 # 必须写成 %23。
        val withoutSuffix = value.substringBefore('?').substringBefore('#')
        val decoded = percentDecode(withoutSuffix)
            ?: return MarkdownResourceTarget.Rejected(MarkdownAttachmentStatus.INVALID_REFERENCE)
        if (decoded.any(Char::isISOControl)) return MarkdownResourceTarget.Rejected(MarkdownAttachmentStatus.INVALID_REFERENCE)
        val normalized = normalize(decoded.replace('\\', '/'))
            ?: return MarkdownResourceTarget.Rejected(MarkdownAttachmentStatus.INVALID_REFERENCE)
        if (normalized.length > MAX_PATH_LENGTH) return MarkdownResourceTarget.Rejected(MarkdownAttachmentStatus.INVALID_REFERENCE)
        if (normalized.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
            return MarkdownResourceTarget.Rejected(MarkdownAttachmentStatus.OUT_OF_SCOPE)
        }
        return MarkdownResourceTarget.Local(normalized)
    }

    /** `base` 目录（如 `content`）+ 相对路径，结果越出根目录返回 null。 */
    fun resolveAgainst(base: String, path: String): String? {
        val joined = normalize(if (base.isEmpty()) path else "$base/$path") ?: return null
        return joined.takeUnless { it == ".." || it.startsWith("../") }
    }

    /** 只解码 %XX；`+` 保持字面量。非法 % 序列按字面量处理（与浏览器对 `%` 的编码一致）。解码后非 UTF-8 返回 null。 */
    internal fun percentDecode(value: String): String? {
        val out = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            if (ch == '%' && index + 2 < value.length &&
                hex(value[index + 1]) >= 0 && hex(value[index + 2]) >= 0
            ) {
                out.write(hex(value[index + 1]) * 16 + hex(value[index + 2]))
                index += 3
                continue
            }
            val codePoint = value.codePointAt(index)
            out.write(String(Character.toChars(codePoint)).toByteArray(StandardCharsets.UTF_8))
            index += Character.charCount(codePoint)
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(out.toByteArray()))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun normalize(path: String): String? {
        val stack = ArrayDeque<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty() && stack.last() != "..") stack.removeLast() else stack.addLast("..")
                else -> stack.addLast(segment)
            }
        }
        if (stack.isEmpty() || stack.last() == "..") return null
        return stack.joinToString("/")
    }

    private fun hex(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        in 'A'..'F' -> ch - 'A' + 10
        else -> -1
    }
}

/** 只认可的图片格式；扩展名来自内容嗅探而不是引用文件名。 */
object MarkdownImageSniffer {
    private const val HEADER_BYTES = 1024

    fun extensionOf(file: File): String? {
        val header = ByteArray(HEADER_BYTES)
        val read = file.inputStream().use { input ->
            var total = 0
            while (total < header.size) {
                val count = input.read(header, total, header.size - total)
                if (count < 0) break
                total += count
            }
            total
        }
        return extensionOf(header.copyOf(read))
    }

    fun extensionOf(header: ByteArray): String? {
        fun startsWith(vararg bytes: Int, offset: Int = 0) =
            header.size >= offset + bytes.size && bytes.indices.all { header[offset + it] == bytes[it].toByte() }
        fun ascii(text: String, offset: Int = 0) = startsWith(*text.map { it.code }.toIntArray(), offset = offset)
        return when {
            startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "png"
            startsWith(0xFF, 0xD8, 0xFF) -> "jpg"
            ascii("GIF87a") || ascii("GIF89a") -> "gif"
            ascii("RIFF") && ascii("WEBP", offset = 8) -> "webp"
            ascii("ftypavif", offset = 4) || ascii("ftypavis", offset = 4) -> "avif"
            ascii("BM") && header.size >= 14 -> "bmp"
            looksLikeSvg(header) -> "svg"
            else -> null
        }
    }

    private fun looksLikeSvg(header: ByteArray): Boolean {
        val text = String(header, StandardCharsets.UTF_8).removePrefix("\uFEFF").trimStart().lowercase(Locale.ROOT)
        val prologOk = text.startsWith("<svg") || text.startsWith("<?xml") || text.startsWith("<!--") ||
            text.startsWith("<!doctype svg")
        return prologOk && text.contains("<svg")
    }
}

data class MarkdownAttachmentRecord(
    val path: String,
    val status: MarkdownAttachmentStatus,
    val sha256: String? = null,
    val bytes: Long? = null,
    val extension: String? = null,
    /** 仅 revision 索引：revision 内资源相对路径。 */
    val resource: String? = null,
)

object MarkdownAttachmentIndexCodec {
    private const val VERSION = 1
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val EXTENSIONS = setOf("png", "jpg", "gif", "webp", "avif", "bmp", "svg")

    fun encode(records: List<MarkdownAttachmentRecord>): String = buildJsonObject {
        put("version", VERSION)
        put("entries", JsonArray(records.sortedBy { it.path }.map { record ->
            buildJsonObject {
                put("path", record.path)
                put("status", record.status.name)
                record.sha256?.let { put("sha256", it) }
                record.bytes?.let { put("bytes", it) }
                record.extension?.let { put("extension", it) }
                record.resource?.let { put("resource", it) }
            }
        }))
    }.toString()

    /** 解析失败或字段不合法抛 [IllegalArgumentException]；sha256 必须是规范哈希，防止索引被篡改成路径。 */
    fun decode(text: String): List<MarkdownAttachmentRecord> {
        val root = Json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("index root")
        require((root["version"] as? JsonPrimitive)?.content == VERSION.toString()) { "index version" }
        val entries = root["entries"] as? JsonArray ?: throw IllegalArgumentException("index entries")
        return entries.map { element ->
            val obj = element as? JsonObject ?: throw IllegalArgumentException("index entry")
            fun string(name: String): String? = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val status = MarkdownAttachmentStatus.valueOf(string("status") ?: throw IllegalArgumentException("status"))
            val sha = string("sha256")?.also { require(SHA256.matches(it)) { "sha256" } }
            val extension = string("extension")?.also { require(it in EXTENSIONS) { "extension" } }
            if (status == MarkdownAttachmentStatus.COLLECTED) require(sha != null && extension != null) { "collected entry" }
            MarkdownAttachmentRecord(
                path = string("path") ?: throw IllegalArgumentException("path"),
                status = status,
                sha256 = sha,
                bytes = (obj["bytes"] as? JsonPrimitive)?.longOrNull,
                extension = extension,
                resource = string("resource"),
            )
        }
    }
}

sealed interface MarkdownAttachmentResolution {
    data class Available(val file: File, val expectedSha256: String?) : MarkdownAttachmentResolution
    data class Unavailable(val status: MarkdownAttachmentStatus) : MarkdownAttachmentResolution
}

/** 解析期附件来源：只读私有副本（staging 或旧 revision），不访问外部 URI。 */
fun interface MarkdownAttachmentProvider {
    fun resolve(path: String): MarkdownAttachmentResolution
}

/** 导入入口在授权有效期内执行：把相对图片复制进 staging，并写逐引用状态索引。 */
class MarkdownAttachmentCollector(
    private val limits: MarkdownAttachmentLimits = MarkdownAttachmentLimits(),
) {
    /**
     * @return 每个不同本地相对引用一条记录；仅远程/非法图片也发布空索引，供 Worker 生成显式占位。
     * 无图片或全部引用为 [MarkdownResourceTarget.Ignored] 时返回空且不写索引。
     * @throws DocumentStorageException 写私有存储失败（不是附件本身的问题）。
     */
    fun collect(markdownFile: File, resolver: ImportAttachmentResolver?, directory: File): List<MarkdownAttachmentRecord> {
        if (directory.exists() && !directory.deleteRecursively()) {
            throw DocumentStorageException("could not reset markdown attachment staging")
        }
        if (!markdownFile.isFile || markdownFile.length() > ParserIO.MAX_TEXT_BYTES) return emptyList()
        val targets = MarkdownDocumentParser.imageDestinations(MarkdownDocumentParser.readMarkdown(markdownFile))
            .map(MarkdownResourceReference::classify)
        if (targets.all { it == MarkdownResourceTarget.Ignored }) return emptyList()
        val paths = targets
            .mapNotNull { (it as? MarkdownResourceTarget.Local)?.path }
            .distinct()
        val blobs = File(directory, StagedMarkdownAttachments.BLOBS)
        if (!blobs.isDirectory && !blobs.mkdirs()) throw DocumentStorageException("could not create attachment staging")
        var count = 0
        var total = 0L
        val records = paths.map { path ->
            when {
                resolver == null -> MarkdownAttachmentRecord(path, MarkdownAttachmentStatus.NOT_ACCESSIBLE)
                count >= limits.maxCount -> MarkdownAttachmentRecord(path, MarkdownAttachmentStatus.LIMIT_EXCEEDED)
                else -> fetch(path, resolver, blobs, limits.maxTotalBytes - total).also { record ->
                    if (record.status == MarkdownAttachmentStatus.COLLECTED) {
                        count += 1
                        total += record.bytes ?: 0L
                    }
                }
            }
        }
        val index = File(directory, StagedMarkdownAttachments.INDEX)
        val temporary = File(directory, "index.tmp-${UUID.randomUUID()}")
        try {
            temporary.writeText(MarkdownAttachmentIndexCodec.encode(records), StandardCharsets.UTF_8)
            if (!temporary.renameTo(index)) throw DocumentStorageException("could not publish attachment index")
        } catch (error: IOException) {
            temporary.delete()
            throw error as? DocumentStorageException ?: DocumentStorageException("could not write attachment index", error)
        }
        return records
    }

    private fun fetch(path: String, resolver: ImportAttachmentResolver, blobs: File, remainingTotal: Long): MarkdownAttachmentRecord {
        fun failed(status: MarkdownAttachmentStatus) = MarkdownAttachmentRecord(path, status)
        val lookup = try {
            resolver.resolve(path)
        } catch (_: SecurityException) {
            return failed(MarkdownAttachmentStatus.PERMISSION_DENIED)
        } catch (_: FileNotFoundException) {
            return failed(MarkdownAttachmentStatus.MISSING)
        } catch (_: IOException) {
            return failed(MarkdownAttachmentStatus.READ_FAILED)
        }
        val found = when (lookup) {
            is ImportAttachmentLookup.Found -> lookup
            ImportAttachmentLookup.NotFound -> return failed(MarkdownAttachmentStatus.MISSING)
            ImportAttachmentLookup.OutOfScope -> return failed(MarkdownAttachmentStatus.OUT_OF_SCOPE)
            ImportAttachmentLookup.PermissionDenied -> return failed(MarkdownAttachmentStatus.PERMISSION_DENIED)
            ImportAttachmentLookup.Ambiguous -> return failed(MarkdownAttachmentStatus.AMBIGUOUS)
        }
        val limit = minOf(limits.maxBytes, remainingTotal)
        if (limit <= 0) return failed(MarkdownAttachmentStatus.LIMIT_EXCEEDED)
        val temporary = File(blobs, "tmp-${UUID.randomUUID()}")
        try {
            val input: InputStream = try {
                found.openStream()
            } catch (_: FileNotFoundException) {
                return failed(MarkdownAttachmentStatus.MISSING)
            } catch (_: SecurityException) {
                return failed(MarkdownAttachmentStatus.PERMISSION_DENIED)
            } catch (_: IOException) {
                return failed(MarkdownAttachmentStatus.READ_FAILED)
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            val outcome: MarkdownAttachmentStatus? = input.use { stream ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = try {
                            stream.read(buffer)
                        } catch (_: SecurityException) {
                            return@use MarkdownAttachmentStatus.PERMISSION_DENIED
                        } catch (_: IOException) {
                            return@use MarkdownAttachmentStatus.READ_FAILED
                        }
                        if (read < 0) break
                        bytes += read
                        if (bytes > limit) return@use MarkdownAttachmentStatus.LIMIT_EXCEEDED
                        digest.update(buffer, 0, read)
                        try {
                            output.write(buffer, 0, read)
                        } catch (error: IOException) {
                            throw DocumentStorageException("could not stage markdown attachment", error)
                        }
                    }
                    null
                }
            }
            if (outcome != null) return failed(outcome)
            val extension = MarkdownImageSniffer.extensionOf(temporary)
                ?: return failed(MarkdownAttachmentStatus.UNSUPPORTED_TYPE)
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val target = File(blobs, sha)
            if (!target.isFile && !temporary.renameTo(target)) {
                throw DocumentStorageException("could not move markdown attachment into place")
            }
            return MarkdownAttachmentRecord(path, MarkdownAttachmentStatus.COLLECTED, sha, bytes, extension)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }
}

/** Worker 侧：只读 staging 中已采集的副本。索引不存在（旧 job/无待处理图片）时 [load] 返回 null，解析器保持原行为。 */
class StagedMarkdownAttachments private constructor(
    private val blobs: File,
    private val records: Map<String, MarkdownAttachmentRecord>,
    private val fallback: MarkdownAttachmentStatus,
) : MarkdownAttachmentProvider {
    override fun resolve(path: String): MarkdownAttachmentResolution {
        val record = records[path] ?: return MarkdownAttachmentResolution.Unavailable(fallback)
        if (record.status != MarkdownAttachmentStatus.COLLECTED) return MarkdownAttachmentResolution.Unavailable(record.status)
        val sha = record.sha256 ?: return MarkdownAttachmentResolution.Unavailable(MarkdownAttachmentStatus.READ_FAILED)
        return MarkdownAttachmentResolution.Available(File(blobs, sha), sha)
    }

    companion object {
        const val DIRECTORY = "attachments"
        const val INDEX = "index.json"
        const val BLOBS = "blobs"

        fun directoryFor(stagingJobDirectory: File): File = File(stagingJobDirectory, DIRECTORY)

        fun load(directory: File): StagedMarkdownAttachments? {
            val index = File(directory, INDEX)
            if (!index.isFile) return null
            val records = try {
                MarkdownAttachmentIndexCodec.decode(index.readText(StandardCharsets.UTF_8))
            } catch (_: Exception) {
                // 索引损坏：不信任任何副本，全部显式标为读取失败，正文照常发布。
                return StagedMarkdownAttachments(File(directory, BLOBS), emptyMap(), MarkdownAttachmentStatus.READ_FAILED)
            }
            return StagedMarkdownAttachments(
                File(directory, BLOBS),
                records.associateBy { it.path },
                MarkdownAttachmentStatus.NOT_ACCESSIBLE,
            )
        }
    }
}

/**
 * 重新解析侧：只使用旧 revision 已保存且在 manifest 中声明的文件。
 * 先按 revision 附件索引映射；旧 revision 没有索引时，按 section 目录（content/）解析相对路径，
 * 命中 manifest 中非 SECTION/SOURCE 文件才可用（兼容 R15 手工放置资源的 revision）。
 */
class RevisionMarkdownAttachments(
    manifest: ContentManifest,
    private val resolveFile: (String) -> File,
) : MarkdownAttachmentProvider {
    private val files = manifest.files.associateBy { it.relativePath }
    private val index: Map<String, MarkdownAttachmentRecord> = files[MarkdownDocumentParser.ATTACHMENT_INDEX_PATH]
        ?.let { runCatching { MarkdownAttachmentIndexCodec.decode(resolveFile(it.relativePath).readText(StandardCharsets.UTF_8)) }.getOrNull() }
        ?.associateBy { it.path }
        .orEmpty()

    override fun resolve(path: String): MarkdownAttachmentResolution {
        val record = index[path]
        if (record?.status == MarkdownAttachmentStatus.COLLECTED) {
            val declared = record.resource?.let(files::get)?.takeIf { it.kind == ManifestFileKind.IMAGE }
            if (declared != null) return MarkdownAttachmentResolution.Available(resolveFile(declared.relativePath), declared.sha256)
        }
        val candidate = MarkdownResourceReference.resolveAgainst(MarkdownDocumentParser.SECTION_DIRECTORY, path)
            ?: return MarkdownAttachmentResolution.Unavailable(MarkdownAttachmentStatus.OUT_OF_SCOPE)
        val legacy = files[candidate]?.takeIf { it.kind != ManifestFileKind.SECTION && it.kind != ManifestFileKind.SOURCE }
        if (legacy != null) return MarkdownAttachmentResolution.Available(resolveFile(legacy.relativePath), legacy.sha256)
        val status = record?.status?.takeIf { it != MarkdownAttachmentStatus.COLLECTED }
            ?: if (record != null) MarkdownAttachmentStatus.READ_FAILED else MarkdownAttachmentStatus.NOT_ACCESSIBLE
        return MarkdownAttachmentResolution.Unavailable(status)
    }
}

/** file:// 或测试用：只允许 MD 所在目录（含子目录）内的真实文件，canonical 路径防符号链接逃逸。 */
class DirectoryAttachmentResolver(baseDirectory: File) : ImportAttachmentResolver {
    private val root: File = baseDirectory.canonicalFile

    override fun resolve(path: String): ImportAttachmentLookup {
        if (path == ".." || path.startsWith("../")) return ImportAttachmentLookup.OutOfScope
        val target = File(root, path.replace('/', File.separatorChar)).canonicalFile
        if (!target.path.startsWith(root.path + File.separator)) return ImportAttachmentLookup.OutOfScope
        if (!target.isFile) return ImportAttachmentLookup.NotFound
        if (!target.canRead()) return ImportAttachmentLookup.PermissionDenied
        return ImportAttachmentLookup.Found { target.inputStream() }
    }
}

/**
 * SAF 单文档授权：用户在同一次选择中一并选中的文件。只按引用的文件名精确匹配显示名，
 * 同名多于一个时返回 [ImportAttachmentLookup.Ambiguous]，不猜测。
 */
class SelectedFilesAttachmentResolver(
    private val files: List<Pair<String, () -> InputStream>>,
) : ImportAttachmentResolver {
    override fun resolve(path: String): ImportAttachmentLookup {
        val name = path.substringAfterLast('/')
        val matches = files.filter { it.first == name }
        return when (matches.size) {
            0 -> ImportAttachmentLookup.NotFound
            1 -> ImportAttachmentLookup.Found(matches.single().second)
            else -> ImportAttachmentLookup.Ambiguous
        }
    }
}

/** 依次尝试；任一命中即用，否则返回信息量最大的失败。 */
class CompositeAttachmentResolver(private val resolvers: List<ImportAttachmentResolver>) : ImportAttachmentResolver {
    override fun resolve(path: String): ImportAttachmentLookup {
        val failures = mutableListOf<ImportAttachmentLookup>()
        for (resolver in resolvers) {
            val result = try {
                resolver.resolve(path)
            } catch (_: SecurityException) {
                ImportAttachmentLookup.PermissionDenied
            }
            if (result is ImportAttachmentLookup.Found) return result
            failures += result
        }
        return PRIORITY.firstOrNull { it in failures } ?: ImportAttachmentLookup.NotFound
    }

    private companion object {
        val PRIORITY = listOf(
            ImportAttachmentLookup.Ambiguous,
            ImportAttachmentLookup.PermissionDenied,
            ImportAttachmentLookup.NotFound,
            ImportAttachmentLookup.OutOfScope,
        )
    }
}

/** 选择批次划分：批次内含 Markdown 时，图片文件视为附件而不是独立书籍。 */
object ImportBatchPlanner {
    private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdown", "mkd")
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "avif", "bmp", "svg")

    data class Entry(val displayName: String?, val mimeType: String?)
    data class Plan(val books: List<Int>, val companions: List<Int>)

    fun isMarkdown(entry: Entry): Boolean =
        extension(entry.displayName) in MARKDOWN_EXTENSIONS || entry.mimeType?.lowercase(Locale.ROOT) == "text/markdown"

    fun isImage(entry: Entry): Boolean =
        entry.mimeType?.lowercase(Locale.ROOT)?.startsWith("image/") == true || extension(entry.displayName) in IMAGE_EXTENSIONS

    fun plan(entries: List<Entry>): Plan {
        if (entries.none(::isMarkdown)) return Plan(entries.indices.toList(), emptyList())
        val companions = entries.indices.filter { !isMarkdown(entries[it]) && isImage(entries[it]) }
        return Plan(entries.indices.filter { it !in companions }, companions)
    }

    private fun extension(name: String?): String? =
        name?.substringAfterLast('/')?.takeIf { it.contains('.') }?.substringAfterLast('.')?.lowercase(Locale.ROOT)
}
