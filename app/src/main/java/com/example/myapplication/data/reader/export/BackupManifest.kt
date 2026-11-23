package com.example.myapplication.data.reader.export

import com.example.myapplication.domain.reader.NormalizedRect
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import com.example.myapplication.domain.reader.ReaderLocatorValidator
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 备份包 v1 契约（W5-05 导出 / W5-06 恢复）。
 *
 * 设计依据：第五期详细设计 §5、§13、§18、§19，实施规划 §6（P5-04）与 §11（W5-05/W5-06）。
 *
 * 本文件只放**纯 JVM** 逻辑（manifest 模型、编解码、校验、包校验、隐私守卫），
 * 不引用 Android/Room，便于 `BackupManifestTest` 在 JVM 上直接覆盖路径穿越、hash 不匹配、
 * 超大包等安全用例。真正的落盘/数据库写入在 [BackupPackageWriter] 与 [RestoreCoordinator]。
 *
 * 边界（§18）：
 * - 包内只有 source content identity（hash/revision）与 typed 只读内容；
 * - 不含 AI key、密钥密文、聊天全文、未选择的全书正文、其它用户数据；
 * - 不含 writerEpoch / MutationReceipt / active_slot / 未确认提议 / secret 引用。
 */

/** schema 版本：与 §13 的 `schemaVersion: 1` 对齐，v2 另行定义，不复用此版本号。 */
object BackupSchema {
    const val VERSION: Int = 1

    /** 包根目录里的 manifest 固定文件名。 */
    const val MANIFEST_FILE: String = "manifest.json"

    /** 标注导出文件名（包根）。 */
    const val ANNOTATIONS_FILE: String = "annotations.json"
}

/** 备份包内文件类别；**没有** SOURCE 类别，因此结构上不可能携带原书正文。 */
enum class BackupFileKind {
    NOTE,
    ANNOTATIONS,
    INK,
    ASSET,
}

/** manifest.files 的一项：相对路径 + 内容 hash + 字节数 + 类别。 */
data class BackupFile(
    val path: String,
    val sha256: String,
    val bytes: Long,
    val kind: BackupFileKind,
)

/** manifest.documents 的一项：导出命名空间下的文档身份与内容定位。 */
data class BackupDocument(
    val exportDocumentId: String,
    val title: String,
    val author: String? = null,
    val format: String = "UNKNOWN",
    /** v1 只支持“不含原书正文”，此字段必须为 false；为 true 的包直接拒绝。 */
    val sourceIncluded: Boolean = false,
    /** 稳定内容身份（导入文档为源文件 sha256；内置书为 null）。 */
    val sourceSha256: String? = null,
    /** 内容代次（内置/导入都由内容派生，用于同内容复用与后续绑定校验）。 */
    val contentRevision: String? = null,
    /** 该文档的 Markdown 笔记文件（相对路径）。 */
    val notesFile: String? = null,
    /** 笔记正文字符数（用于预览，不用于信任正文内容）。 */
    val noteChars: Int = 0,
    val annotationCount: Int = 0,
    val inkPageCount: Int = 0,
    /** 导出时的阅读位置（导出命名空间下的 locator，恢复时重映射）。 */
    val lastLocator: ReaderLocator? = null,
)

/** 导出选项；聊天/原书默认且强制不导出（§5.1、§18）。 */
data class BackupOptions(
    val includeSources: Boolean = false,
    val includeChatHistory: Boolean = false,
)

/**
 * 备份包 manifest。
 *
 * `sourceContentHash` 仅在“单文档导出且该文档有稳定 hash”时写入；多文档包为 null，
 * 每个文档的身份见 [BackupDocument.sourceSha256]（避免把多本书折叠成一个假 hash）。
 */
data class BackupManifest(
    val schemaVersion: Int = BackupSchema.VERSION,
    val exportId: String,
    val createdAt: Long,
    val appVersion: String,
    val sourceContentHash: String?,
    val documents: List<BackupDocument>,
    val files: List<BackupFile>,
    val options: BackupOptions = BackupOptions(),
)

/** 校验/校验器使用的稳定错误码（测试与报告都按码断言）。 */
object BackupIssueCodes {
    const val UNSUPPORTED_MANIFEST_VERSION = "UNSUPPORTED_MANIFEST_VERSION"
    const val INVALID_MANIFEST = "MANIFEST_INVALID"
    const val MANIFEST_MISSING = "MANIFEST_MISSING"
    const val MANIFEST_TOO_LARGE = "MANIFEST_TOO_LARGE"
    const val INVALID_EXPORT_ID = "INVALID_EXPORT_ID"
    const val INVALID_PATH = "PATH_INVALID"
    const val PATH_ESCAPE = "PATH_ESCAPE"
    const val DUPLICATE_PATH = "DUPLICATE_PATH"
    const val PATH_NOT_ALLOWED = "PATH_NOT_ALLOWED"
    const val INVALID_HASH = "INVALID_HASH"
    const val PACKAGE_TOO_LARGE = "PACKAGE_TOO_LARGE"
    const val FILE_MISSING = "FILE_MISSING"
    const val FILE_NOT_REGULAR = "FILE_NOT_REGULAR"
    const val SYMLINK_FORBIDDEN = "SYMLINK_FORBIDDEN"
    const val SIZE_MISMATCH = "SIZE_MISMATCH"
    const val HASH_MISMATCH = "HASH_MISMATCH"
    const val UNSUPPORTED_OPTION = "UNSUPPORTED_OPTION"
    const val SOURCE_INCLUDED = "SOURCE_INCLUDED_UNSUPPORTED"
    const val SENSITIVE_FIELD = "SENSITIVE_FIELD"
    const val INVALID_DOCUMENT = "INVALID_DOCUMENT"
    const val DUPLICATE_DOCUMENT = "DUPLICATE_DOCUMENT"
    const val MISSING_NOTE_FILE = "MISSING_NOTE_FILE"
    const val INK_PATH_NOT_ALLOWED = "INK_PATH_NOT_ALLOWED"
    const val ANNOTATIONS_CORRUPT = "ANNOTATIONS_CORRUPT"
    const val INVALID_USER = "INVALID_USER"
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val NO_EXPORTABLE_DOCUMENT = "NO_EXPORTABLE_DOCUMENT"
    const val EXPORT_FAILED = "EXPORT_FAILED"
    const val EXPORT_EXISTS = "EXPORT_EXISTS"
    const val RESTORE_FAILED = "RESTORE_FAILED"
    const val RESTORE_STAGING_FAILED = "RESTORE_STAGING_FAILED"
    const val INVALID_LOCATOR = "INVALID_LOCATOR"
    const val INVALID_ANNOTATION = "INVALID_ANNOTATION"
    const val NOTE_TOO_LARGE = "NOTE_TOO_LARGE"
    const val INK_CORRUPT = "INK_CORRUPT"
    const val INK_CONFLICT = "INK_CONFLICT"
    const val NOTE_CONFLICT = "NOTE_CONFLICT"
    const val ANNOTATION_CONFLICT = "ANNOTATION_CONFLICT"
    const val STORAGE_FAILURE = "STORAGE_FAILURE"
}

/** 一条校验问题。 */
data class BackupIssue(
    val code: String,
    val detail: String,
    val path: String? = null,
) {
    override fun toString(): String = if (path == null) "$code: $detail" else "$code($path): $detail"
}

/** 校验结果。 */
data class BackupValidation(val issues: List<BackupIssue>) {
    val isValid: Boolean get() = issues.isEmpty()
    val firstCode: String? get() = issues.firstOrNull()?.code

    fun detailText(): String = issues.joinToString("; ") { it.toString() }

    companion object {
        val VALID = BackupValidation(emptyList())
    }
}

class BackupCodecException(
    message: String,
    cause: Throwable? = null,
    val issues: List<BackupIssue> = emptyList(),
) : IllegalArgumentException(message, cause)

/**
 * 包内相对路径规则：
 * - 只允许 `/` 分隔、只允许相对路径；
 * - 拒绝 `..`、`.`、空片段、绝对路径、Windows 盘符与反斜杠；
 * - 拒绝控制字符、保留名 manifest.json；
 * - 只允许 `notes/`、`ink/` 前缀与根级 annotations.json。
 */
object BackupPaths {
    private val DRIVE_PREFIX = Regex("^[A-Za-z]:")
    private const val MAX_PATH_LENGTH = 512

    fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank() || path.length > MAX_PATH_LENGTH) return false
        if (path.any(Char::isISOControl)) return false
        if (path.startsWith('/') || path.startsWith('\\')) return false
        if (DRIVE_PREFIX.containsMatchIn(path)) return false
        if (path.contains('\\')) return false
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return false
        return true
    }

    fun isAllowedPackagePath(path: String): Boolean {
        if (!isSafeRelativePath(path)) return false
        if (path == BackupSchema.MANIFEST_FILE) return false
        if (path == BackupSchema.ANNOTATIONS_FILE) return true
        return path.startsWith("notes/") || path.startsWith("ink/")
    }
}

/**
 * manifest 语义校验（不碰磁盘）。
 *
 * 这里同时承担“隐私白名单”职责：包内不允许出现 source 正文、聊天、密钥类字段，
 * 也不允许 `options.includeSources/includeChatHistory = true`。
 */
object BackupManifestValidator {
    private val EXPORT_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    private val DOCUMENT_KEY = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    private val HASH = Regex("^[0-9a-f]{64}$")
    private const val MAX_TEXT = 512
    private const val MAX_FORMAT = 64
    private const val MAX_REVISION = 512

    fun validate(
        manifest: BackupManifest,
        maxPackageBytes: Long = BackupPackageVerifier.DEFAULT_MAX_PACKAGE_BYTES,
    ): BackupValidation {
        val issues = mutableListOf<BackupIssue>()

        if (manifest.schemaVersion != BackupSchema.VERSION) {
            issues += BackupIssue(
                BackupIssueCodes.UNSUPPORTED_MANIFEST_VERSION,
                "只支持 schemaVersion=${BackupSchema.VERSION}，实际为 ${manifest.schemaVersion}",
            )
        }
        if (!EXPORT_ID.matches(manifest.exportId)) {
            issues += BackupIssue(
                BackupIssueCodes.INVALID_EXPORT_ID,
                "exportId 必须由字母/数字开头，只含字母数字点划线下划线，长度 1..64",
            )
        }
        if (manifest.createdAt < 0) {
            issues += BackupIssue(BackupIssueCodes.INVALID_MANIFEST, "createdAt 不能为负")
        }
        validateText(manifest.appVersion, "appVersion", issues)
        manifest.sourceContentHash?.let { hash ->
            if (!HASH.matches(hash)) {
                issues += BackupIssue(BackupIssueCodes.INVALID_HASH, "sourceContentHash 必须是 64 位小写 sha256")
            }
        }
        if (manifest.options.includeSources) {
            issues += BackupIssue(
                BackupIssueCodes.SOURCE_INCLUDED,
                "v1 备份包不携带原书正文，includeSources 必须为 false",
            )
        }
        if (manifest.options.includeChatHistory) {
            issues += BackupIssue(
                BackupIssueCodes.UNSUPPORTED_OPTION,
                "v1 备份包不携带聊天历史，includeChatHistory 必须为 false",
            )
        }

        if (manifest.documents.isEmpty()) {
            issues += BackupIssue(BackupIssueCodes.INVALID_MANIFEST, "documents 不能为空")
        }
        val documentKeys = mutableSetOf<String>()
        manifest.documents.forEachIndexed { index, document ->
            val field = "documents[$index]"
            if (!DOCUMENT_KEY.matches(document.exportDocumentId)) {
                issues += BackupIssue(
                    BackupIssueCodes.INVALID_DOCUMENT,
                    "$field.exportDocumentId 必须是不含路径分隔符的稳定键",
                )
            }
            if (!documentKeys.add(document.exportDocumentId)) {
                issues += BackupIssue(
                    BackupIssueCodes.DUPLICATE_DOCUMENT,
                    "exportDocumentId 重复：${document.exportDocumentId}",
                )
            }
            validateText(document.title, "$field.title", issues)
            document.author?.let { validateText(it, "$field.author", issues) }
            if (document.format.isBlank() || document.format.length > MAX_FORMAT) {
                issues += BackupIssue(BackupIssueCodes.INVALID_DOCUMENT, "$field.format 非法")
            }
            if (document.sourceIncluded) {
                issues += BackupIssue(
                    BackupIssueCodes.SOURCE_INCLUDED,
                    "$field.sourceIncluded 必须为 false：v1 不导出原书正文",
                )
            }
            document.sourceSha256?.let { hash ->
                if (!HASH.matches(hash)) {
                    issues += BackupIssue(
                        BackupIssueCodes.INVALID_HASH,
                        "$field.sourceSha256 必须是 64 位小写 sha256",
                    )
                }
            }
            document.contentRevision?.let { revision ->
                if (revision.isBlank() || revision.length > MAX_REVISION || revision.any(Char::isISOControl)) {
                    issues += BackupIssue(BackupIssueCodes.INVALID_DOCUMENT, "$field.contentRevision 非法")
                }
            }
            if (document.noteChars < 0 || document.annotationCount < 0 || document.inkPageCount < 0) {
                issues += BackupIssue(BackupIssueCodes.INVALID_DOCUMENT, "$field 的计数不能为负")
            }
            document.notesFile?.let { notes ->
                if (!BackupPaths.isSafeRelativePath(notes) || !notes.startsWith("notes/")) {
                    issues += BackupIssue(BackupIssueCodes.INVALID_PATH, "$field.notesFile 必须是 notes/ 下的安全相对路径")
                }
            }
            document.lastLocator?.let { locator ->
                val validation = ReaderLocatorValidator.validate(locator)
                if (!validation.isValid) {
                    issues += BackupIssue(
                        BackupIssueCodes.INVALID_LOCATOR,
                        "$field.lastLocator 非法：${validation.errors.joinToString("; ")}",
                    )
                }
                if (locator.documentId != document.exportDocumentId) {
                    issues += BackupIssue(
                        BackupIssueCodes.INVALID_LOCATOR,
                        "$field.lastLocator.documentId 必须等于 exportDocumentId（包内不得出现内部文档 id）",
                    )
                }
            }
        }

        if (manifest.files.isEmpty()) {
            issues += BackupIssue(BackupIssueCodes.INVALID_MANIFEST, "files 不能为空")
        }
        val paths = mutableSetOf<String>()
        var totalBytes = 0L
        var overflow = false
        manifest.files.forEachIndexed { index, file ->
            val field = "files[$index]"
            if (!BackupPaths.isAllowedPackagePath(file.path)) {
                issues += BackupIssue(
                    if (BackupPaths.isSafeRelativePath(file.path)) {
                        BackupIssueCodes.PATH_NOT_ALLOWED
                    } else {
                        BackupIssueCodes.INVALID_PATH
                    },
                    "$field.path 不在允许的包内白名单（notes/、ink/、annotations.json）内：${file.path}",
                    file.path,
                )
            }
            if (!paths.add(file.path.lowercase())) {
                issues += BackupIssue(BackupIssueCodes.DUPLICATE_PATH, "$field.path 重复：${file.path}", file.path)
            }
            if (!HASH.matches(file.sha256)) {
                issues += BackupIssue(BackupIssueCodes.INVALID_HASH, "$field.sha256 必须是 64 位小写 sha256", file.path)
            }
            if (file.bytes < 0) {
                issues += BackupIssue(BackupIssueCodes.INVALID_MANIFEST, "$field.bytes 不能为负", file.path)
            }
            totalBytes = runCatching { Math.addExact(totalBytes, file.bytes) }.getOrElse {
                overflow = true
                Long.MAX_VALUE
            }
            if (overflow) {
                issues += BackupIssue(BackupIssueCodes.PACKAGE_TOO_LARGE, "包内字节数溢出", file.path)
                overflow = false
            }
            if (file.kind == BackupFileKind.INK) {
                val segments = file.path.split('/')
                if (segments.size < 3 || segments[1] !in documentKeys) {
                    issues += BackupIssue(
                        BackupIssueCodes.INK_PATH_NOT_ALLOWED,
                        "$field.path 必须位于 ink/<exportDocumentId>/ 下",
                        file.path,
                    )
                }
            }
        }
        if (totalBytes > maxPackageBytes) {
            issues += BackupIssue(
                BackupIssueCodes.PACKAGE_TOO_LARGE,
                "包内声明总大小 $totalBytes 超过上限 $maxPackageBytes",
            )
        }

        manifest.documents.forEachIndexed { index, document ->
            val notes = document.notesFile ?: return@forEachIndexed
            val declared = manifest.files.firstOrNull { it.path == notes }
            if (declared == null) {
                issues += BackupIssue(
                    BackupIssueCodes.MISSING_NOTE_FILE,
                    "documents[$index].notesFile 未在 files 中声明：$notes",
                    notes,
                )
            } else if (declared.kind != BackupFileKind.NOTE) {
                issues += BackupIssue(
                    BackupIssueCodes.MISSING_NOTE_FILE,
                    "documents[$index].notesFile 的 kind 必须是 NOTE",
                    notes,
                )
            }
        }

        return BackupValidation(issues)
    }

    private fun validateText(value: String, field: String, issues: MutableList<BackupIssue>) {
        if (value.isBlank()) {
            issues += BackupIssue(BackupIssueCodes.INVALID_MANIFEST, "$field 不能为空")
            return
        }
        if (value.length > MAX_TEXT) {
            issues += BackupIssue(BackupIssueCodes.INVALID_MANIFEST, "$field 过长")
        }
        if (value.any(Char::isISOControl)) {
            issues += BackupIssue(BackupIssueCodes.INVALID_MANIFEST, "$field 含控制字符")
        }
    }
}

private val STRICT_JSON = Json {
    ignoreUnknownKeys = false
    isLenient = false
    explicitNulls = true
    allowSpecialFloatingPointValues = false
}

/**
 * manifest 编解码。
 *
 * canonical 规则：按固定字段顺序构建 JSON 对象（不排序键），manifest 自身不在 files 中，
 * 因此不存在自引用 hash；`manifestHash = sha256(UTF-8(encode(manifest)))`。
 */
object BackupManifestCodec {
    private val TOP_LEVEL_KEYS = setOf(
        "schemaVersion", "exportId", "createdAt", "appVersion", "sourceContentHash",
        "documents", "files", "options",
    )
    private val DOCUMENT_KEYS = setOf(
        "exportDocumentId", "title", "author", "format", "sourceIncluded", "sourceSha256",
        "contentRevision", "notesFile", "noteChars", "annotationCount", "inkPageCount", "lastLocator",
    )
    private val FILE_KEYS = setOf("path", "sha256", "bytes", "kind")
    private val OPTION_KEYS = setOf("includeSources", "includeChatHistory")

    fun encode(manifest: BackupManifest): String {
        val validation = BackupManifestValidator.validate(manifest)
        if (!validation.isValid) {
            throw BackupCodecException("manifest 非法：${validation.detailText()}", null, validation.issues)
        }
        val json = buildJsonObject {
            put("schemaVersion", manifest.schemaVersion)
            put("exportId", manifest.exportId)
            put("createdAt", manifest.createdAt)
            put("appVersion", manifest.appVersion)
            manifest.sourceContentHash?.let { put("sourceContentHash", it) }
            put("documents", buildJsonArray {
                manifest.documents.forEach { document ->
                    add(buildJsonObject {
                        put("exportDocumentId", document.exportDocumentId)
                        put("title", document.title)
                        document.author?.let { put("author", it) }
                        put("format", document.format)
                        put("sourceIncluded", document.sourceIncluded)
                        document.sourceSha256?.let { put("sourceSha256", it) }
                        document.contentRevision?.let { put("contentRevision", it) }
                        document.notesFile?.let { put("notesFile", it) }
                        put("noteChars", document.noteChars)
                        put("annotationCount", document.annotationCount)
                        put("inkPageCount", document.inkPageCount)
                        document.lastLocator?.let {
                            put("lastLocator", STRICT_JSON.parseToJsonElement(ReaderLocatorCodec.encode(it)))
                        }
                    })
                }
            })
            put("files", buildJsonArray {
                manifest.files.forEach { file ->
                    add(buildJsonObject {
                        put("path", file.path)
                        put("sha256", file.sha256)
                        put("bytes", file.bytes)
                        put("kind", file.kind.name)
                    })
                }
            })
            put("options", buildJsonObject {
                put("includeSources", manifest.options.includeSources)
                put("includeChatHistory", manifest.options.includeChatHistory)
            })
        }
        return json.toString()
    }

    fun decode(
        text: String,
        maxPackageBytes: Long = BackupPackageVerifier.DEFAULT_MAX_PACKAGE_BYTES,
    ): BackupManifest {
        val root = try {
            STRICT_JSON.parseToJsonElement(text)
        } catch (error: Exception) {
            throw BackupCodecException("manifest 不是合法 JSON", error)
        }
        val obj = root as? JsonObject
            ?: throw BackupCodecException("manifest 根节点必须是 JSON 对象")
        obj.rejectUnknownKeys(TOP_LEVEL_KEYS)
        val manifest = BackupManifest(
            schemaVersion = obj.requireInt("schemaVersion"),
            exportId = obj.requireString("exportId"),
            createdAt = obj.requireLong("createdAt"),
            appVersion = obj.requireString("appVersion"),
            sourceContentHash = obj.optionalString("sourceContentHash"),
            documents = obj.requireArray("documents").mapIndexed { index, element ->
                element.toDocument("documents[$index]")
            },
            files = obj.requireArray("files").mapIndexed { index, element ->
                element.toFile("files[$index]")
            },
            options = obj.optionalObject("options")?.toOptions() ?: BackupOptions(),
        )
        val validation = BackupManifestValidator.validate(manifest, maxPackageBytes)
        if (!validation.isValid) {
            throw BackupCodecException("manifest 校验失败：${validation.detailText()}", null, validation.issues)
        }
        return manifest
    }

    /** manifest 的稳定 hash（不含自引用字段，files 内也不允许出现 manifest.json）。 */
    fun canonicalHash(manifest: BackupManifest): String =
        BackupPackageVerifier.sha256(encode(manifest))

    private fun JsonElement.toDocument(field: String): BackupDocument {
        val obj = this as? JsonObject ?: throw BackupCodecException("$field 必须是对象")
        obj.rejectUnknownKeys(DOCUMENT_KEYS)
        val lastLocator = obj.optionalObject("lastLocator")?.let { element ->
            try {
                ReaderLocatorCodec.decode(element.toString())
            } catch (error: Exception) {
                throw BackupCodecException("$field.lastLocator 非法", error)
            }
        }
        return BackupDocument(
            exportDocumentId = obj.requireString("exportDocumentId"),
            title = obj.requireString("title"),
            author = obj.optionalString("author"),
            format = obj.requireString("format"),
            sourceIncluded = obj.optionalBoolean("sourceIncluded") ?: false,
            sourceSha256 = obj.optionalString("sourceSha256"),
            contentRevision = obj.optionalString("contentRevision"),
            notesFile = obj.optionalString("notesFile"),
            noteChars = obj.optionalInt("noteChars") ?: 0,
            annotationCount = obj.optionalInt("annotationCount") ?: 0,
            inkPageCount = obj.optionalInt("inkPageCount") ?: 0,
            lastLocator = lastLocator,
        )
    }

    private fun JsonElement.toFile(field: String): BackupFile {
        val obj = this as? JsonObject ?: throw BackupCodecException("$field 必须是对象")
        obj.rejectUnknownKeys(FILE_KEYS)
        val kindText = obj.requireString("kind")
        val kind = runCatching { BackupFileKind.valueOf(kindText) }
            .getOrElse { throw BackupCodecException("$field.kind 不受支持：$kindText") }
        return BackupFile(
            path = obj.requireString("path"),
            sha256 = obj.requireString("sha256"),
            bytes = obj.requireLong("bytes"),
            kind = kind,
        )
    }

    private fun JsonObject.toOptions(): BackupOptions {
        rejectUnknownKeys(OPTION_KEYS)
        return BackupOptions(
            includeSources = optionalBoolean("includeSources") ?: false,
            includeChatHistory = optionalBoolean("includeChatHistory") ?: false,
        )
    }

    private fun JsonObject.requireString(name: String): String {
        val element = this[name] ?: throw BackupCodecException("缺少 $name")
        val primitive = element as? JsonPrimitive ?: throw BackupCodecException("$name 必须是字符串")
        if (!primitive.isString) throw BackupCodecException("$name 必须是字符串")
        return primitive.content
    }

    private fun JsonObject.optionalString(name: String): String? {
        val element = this[name] ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive
            ?: throw BackupCodecException("$name 必须是字符串或 null")
        if (!primitive.isString) throw BackupCodecException("$name 必须是字符串或 null")
        return primitive.content
    }

    private fun JsonObject.requireInt(name: String): Int =
        optionalInt(name) ?: throw BackupCodecException("缺少整数 $name")

    private fun JsonObject.optionalInt(name: String): Int? {
        val element = this[name] ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive
            ?: throw BackupCodecException("$name 必须是整数或 null")
        if (primitive.isString) throw BackupCodecException("$name 必须是整数或 null")
        return runCatching { primitive.content.toInt() }.getOrElse { throw BackupCodecException("$name 必须是整数") }
    }

    private fun JsonObject.requireLong(name: String): Long {
        val element = this[name] ?: throw BackupCodecException("缺少整数 $name")
        val primitive = element as? JsonPrimitive
            ?: throw BackupCodecException("$name 必须是整数")
        if (primitive.isString) throw BackupCodecException("$name 必须是整数")
        return primitive.longOrNull ?: throw BackupCodecException("$name 必须是整数")
    }

    private fun JsonObject.optionalBoolean(name: String): Boolean? {
        val element = this[name] ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive
            ?: throw BackupCodecException("$name 必须是布尔值或 null")
        if (!primitive.isString) {
            val value = primitive.content
            if (value == "true") return true
            if (value == "false") return false
        }
        throw BackupCodecException("$name 必须是布尔值或 null")
    }

    private fun JsonObject.requireArray(name: String): JsonArray {
        val element = this[name] ?: throw BackupCodecException("缺少 $name")
        return element as? JsonArray ?: throw BackupCodecException("$name 必须是数组")
    }

    private fun JsonObject.optionalObject(name: String): JsonObject? {
        val element = this[name] ?: return null
        if (element is JsonNull) return null
        return element as? JsonObject ?: throw BackupCodecException("$name 必须是对象或 null")
    }

    private fun JsonObject.rejectUnknownKeys(allowed: Set<String>) {
        val unknown = keys - allowed
        if (unknown.isNotEmpty()) {
            // 未知字段既可能是版本更新，也可能有人把密钥/内部字段塞进包里：
            // 统一按"manifest 非法"报 issue，调用方可直接用同一个错误展示口径。
            val detail = "manifest 含未知字段：${unknown.sorted().joinToString(",")}"
            throw BackupCodecException(
                detail,
                null,
                listOf(BackupIssue(BackupIssueCodes.INVALID_MANIFEST, detail)),
            )
        }
    }
}

/**
 * 隐私守卫：只扫描 **JSON 键名**（不扫自由文本，避免用户笔记里的普通词误判）。
 *
 * 结构上 v1 包根本不读 AI/聊天/密钥表；该守卫用于在写包后做一次自检，
 * 也供测试断言导出物不含 `secret` / `cipher` / `apiKey` 等敏感键。
 */
object BackupPrivacyGuard {
    /** 归一化后命中即视为敏感键名。 */
    private val FORBIDDEN_KEYS = setOf(
        "secret", "secretref", "secretrefid", "cipher", "ciphertext", "encryptedsecret",
        "apikey", "apikeyref", "password", "passwordhash", "privatekey", "keystore",
        "token", "accesstoken", "refreshtoken", "authtoken",
        "chatmessages", "chatmessage", "messages", "conversation", "conversationid",
        "proposal", "proposals", "activeslot", "activewriter", "writerepoch",
        "mutationreceipt", "mutationreceipts", "receiptjson",
    )

    fun normalizeKey(key: String): String =
        key.lowercase().filter { it.isLetterOrDigit() }

    fun isSensitiveKey(key: String): Boolean = normalizeKey(key) in FORBIDDEN_KEYS

    /** 深度遍历 JSON 对象的键名，返回命中的敏感键（含路径）。 */
    fun findSensitiveKeys(element: JsonElement, path: String = ""): List<String> {
        return when (element) {
            is JsonObject -> element.entries.flatMap { (key, value) ->
                val childPath = if (path.isEmpty()) key else "$path.$key"
                val hit = if (isSensitiveKey(key)) listOf(childPath) else emptyList()
                hit + findSensitiveKeys(value, childPath)
            }

            is JsonArray -> element.flatMapIndexed { index, value ->
                findSensitiveKeys(value, if (path.isEmpty()) "[$index]" else "$path[$index]")
            }

            else -> emptyList()
        }
    }

    fun findSensitiveKeys(rawJson: String): List<String> {
        val element = runCatching { STRICT_JSON.parseToJsonElement(rawJson) }.getOrNull()
            ?: return emptyList()
        return findSensitiveKeys(element)
    }
}

/** 包校验时读到的 manifest 结果。 */
data class BackupManifestLoad(
    val packageDirectory: File?,
    val manifest: BackupManifest?,
    val issues: List<BackupIssue>,
) {
    val isValid: Boolean get() = packageDirectory != null && manifest != null && issues.isEmpty()
}

/**
 * 备份包磁盘校验（manifest 版本、每文件 sha256、路径白名单、软链、总大小上限）。
 *
 * 恢复的 preview() 第一步就调用它；失败只返回问题列表，**不写库**。
 */
object BackupPackageVerifier {
    const val DEFAULT_MAX_PACKAGE_BYTES: Long = 512L * 1024 * 1024
    const val MAX_MANIFEST_BYTES: Long = 2L * 1024 * 1024
    private const val COPY_BUFFER_BYTES = 64 * 1024

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun sha256(text: String): String = sha256(text.toByteArray(StandardCharsets.UTF_8))

    fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    /**
     * 在允许的根目录内解析相对路径：
     * 先做白名单/相对路径校验，再用 `canonicalPath.startsWith(root + separator)` 阻止越界。
     */
    fun resolveInside(root: File, relativePath: String): File? {
        if (!BackupPaths.isSafeRelativePath(relativePath)) return null
        return runCatching {
            val canonicalRoot = root.canonicalFile
            val candidate = File(canonicalRoot, relativePath.replace('/', File.separatorChar)).canonicalFile
            val prefix = canonicalRoot.path + File.separator
            if (!candidate.path.startsWith(prefix)) null else candidate
        }.getOrNull()
    }

    /**
     * 软链判定（API24 兼容，不依赖 java.nio.file）：
     * 解析后的文件名与声明文件名不同，说明最后一段是链接；目录段软链由 canonical 越界检查兜底。
     */
    fun looksLikeSymlink(file: File): Boolean {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return true
        val ignoreCase = File.separatorChar == '\\'
        return !canonical.name.equals(file.name, ignoreCase = ignoreCase)
    }

    /** 读取并解析 manifest；任何失败都转成结构化问题，不抛出。 */
    fun loadManifest(
        packageDirectory: File,
        maxPackageBytes: Long = DEFAULT_MAX_PACKAGE_BYTES,
    ): BackupManifestLoad {
        val dir = runCatching { packageDirectory.canonicalFile }.getOrNull()
            ?: return BackupManifestLoad(null, null, listOf(BackupIssue(BackupIssueCodes.FILE_MISSING, "备份包目录不可解析")))
        if (!dir.isDirectory) {
            return BackupManifestLoad(dir, null, listOf(BackupIssue(BackupIssueCodes.FILE_MISSING, "备份包目录不存在")))
        }
        val manifestFile = File(dir, BackupSchema.MANIFEST_FILE)
        if (!manifestFile.isFile) {
            return BackupManifestLoad(dir, null, listOf(BackupIssue(BackupIssueCodes.MANIFEST_MISSING, "缺少 manifest.json")))
        }
        if (manifestFile.length() > MAX_MANIFEST_BYTES) {
            return BackupManifestLoad(
                dir,
                null,
                listOf(BackupIssue(BackupIssueCodes.MANIFEST_TOO_LARGE, "manifest.json 超过 $MAX_MANIFEST_BYTES 字节")),
            )
        }
        return try {
            val text = manifestFile.readText(StandardCharsets.UTF_8)
            val manifest = BackupManifestCodec.decode(text, maxPackageBytes)
            BackupManifestLoad(dir, manifest, emptyList())
        } catch (error: BackupCodecException) {
            val issues = error.issues.ifEmpty {
                listOf(BackupIssue(BackupIssueCodes.INVALID_MANIFEST, error.message.orEmpty()))
            }
            BackupManifestLoad(dir, null, issues)
        } catch (error: Exception) {
            BackupManifestLoad(
                dir,
                null,
                listOf(BackupIssue(BackupIssueCodes.INVALID_MANIFEST, "manifest.json 读取失败：${error.message}")),
            )
        }
    }

    /**
     * 逐文件校验：存在、普通文件、非软链、大小一致、sha256 一致；另校验声明总大小上限。
     * 返回的问题列表为空才表示可以进入恢复。
     */
    fun verify(
        packageDirectory: File,
        manifest: BackupManifest,
        maxPackageBytes: Long = DEFAULT_MAX_PACKAGE_BYTES,
    ): BackupValidation {
        val issues = mutableListOf<BackupIssue>()
        val dir = runCatching { packageDirectory.canonicalFile }.getOrNull()
        if (dir == null || !dir.isDirectory) {
            issues += BackupIssue(BackupIssueCodes.FILE_MISSING, "备份包目录不存在")
            return BackupValidation(issues)
        }
        val semantic = BackupManifestValidator.validate(manifest, maxPackageBytes)
        issues += semantic.issues

        var declaredTotal = 0L
        manifest.files.forEach { file ->
            declaredTotal = runCatching { Math.addExact(declaredTotal, file.bytes) }.getOrDefault(Long.MAX_VALUE)
        }
        if (declaredTotal > maxPackageBytes) {
            issues += BackupIssue(
                BackupIssueCodes.PACKAGE_TOO_LARGE,
                "包内声明总大小超过上限 $maxPackageBytes 字节",
            )
            return BackupValidation(issues)
        }

        manifest.files.forEach { file ->
            val target = resolveInside(dir, file.path)
            if (target == null) {
                issues += BackupIssue(BackupIssueCodes.PATH_ESCAPE, "文件路径越界或被拒绝", file.path)
                return@forEach
            }
            if (looksLikeSymlink(target)) {
                issues += BackupIssue(BackupIssueCodes.SYMLINK_FORBIDDEN, "不允许软链接文件", file.path)
                return@forEach
            }
            if (!target.exists()) {
                issues += BackupIssue(BackupIssueCodes.FILE_MISSING, "清单声明的文件缺失", file.path)
                return@forEach
            }
            if (!target.isFile) {
                issues += BackupIssue(BackupIssueCodes.FILE_NOT_REGULAR, "清单声明的位置不是普通文件", file.path)
                return@forEach
            }
            if (target.length() != file.bytes) {
                issues += BackupIssue(
                    BackupIssueCodes.SIZE_MISMATCH,
                    "文件大小 ${target.length()} 与清单 ${file.bytes} 不一致",
                    file.path,
                )
                return@forEach
            }
            val actual = runCatching { sha256Of(target) }.getOrNull()
            if (actual != file.sha256) {
                issues += BackupIssue(BackupIssueCodes.HASH_MISMATCH, "文件 sha256 与清单不一致", file.path)
            }
        }
        return BackupValidation(issues)
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** 把矩形编码成 JSON 键（标注可选 rects 共用）。 */
internal fun NormalizedRect.toJsonObject(): JsonObject = buildJsonObject {
    put("left", left)
    put("top", top)
    put("right", right)
    put("bottom", bottom)
}