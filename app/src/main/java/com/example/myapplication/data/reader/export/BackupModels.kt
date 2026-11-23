package com.example.myapplication.data.reader.export

import com.example.myapplication.data.reader.notes.AnnotationColor
import com.example.myapplication.domain.reader.NormalizedRect
import com.example.myapplication.domain.reader.ReaderLocator
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import com.example.myapplication.domain.reader.ReaderLocatorValidator
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 导出/恢复的请求与结果模型（纯 JVM，便于单测）。
 *
 * 说明：本文件里的 `ExportRequest/RestorePreview/RestoreReport` 都是**不可变结果对象**，
 * 不持有 Activity/Context；真正落盘与写库分别由 [BackupPackageWriter] 与 [RestoreCoordinator] 完成。
 */

/** 导出请求：用户显式选择文档与内容类型；原书正文与聊天不可选（强制 false）。 */
data class ExportRequest(
    val userId: Long,
    val documentIds: List<String>,
    val includeNotes: Boolean = true,
    val includeAnnotations: Boolean = true,
    val includeInk: Boolean = true,
    /** 设计 §5.1：默认且仅支持 false；传 true 由导出入口拒绝，不静默忽略。 */
    val includeSources: Boolean = false,
    /** 设计 §18：聊天历史不进 v1 包。 */
    val includeChatHistory: Boolean = false,
) {
    fun normalizedDocumentIds(): List<String> =
        documentIds.map(String::trim).filter(String::isNotEmpty).distinct()

    fun validationIssues(): List<BackupIssue> {
        val issues = mutableListOf<BackupIssue>()
        if (userId <= 0L) {
            issues += BackupIssue(BackupIssueCodes.INVALID_REQUEST, "userId 必须为正数")
        }
        val ids = normalizedDocumentIds()
        if (ids.isEmpty()) {
            issues += BackupIssue(BackupIssueCodes.INVALID_REQUEST, "至少选择一个文档")
        }
        if (ids.size > MAX_DOCUMENTS) {
            issues += BackupIssue(BackupIssueCodes.INVALID_REQUEST, "单次导出文档数不能超过 $MAX_DOCUMENTS")
        }
        if (includeSources) {
            issues += BackupIssue(BackupIssueCodes.SOURCE_INCLUDED, "v1 不支持导出原书正文")
        }
        if (includeChatHistory) {
            issues += BackupIssue(BackupIssueCodes.UNSUPPORTED_OPTION, "v1 不支持导出聊天历史")
        }
        if (!includeNotes && !includeAnnotations && !includeInk) {
            issues += BackupIssue(BackupIssueCodes.INVALID_REQUEST, "笔记/标注/手写至少选择一项")
        }
        return issues
    }

    companion object {
        const val MAX_DOCUMENTS = 200
    }
}

/** 预览中的单文档条目（不含任何密钥/正文原书数据）。 */
data class ExportDocumentPreview(
    val documentId: String,
    val exportDocumentId: String,
    val title: String,
    val format: String,
    val sourceSha256: String?,
    val noteChars: Int,
    val annotationCount: Int,
    val inkPageCount: Int,
    val estimatedBytes: Long,
)

/** 导出预览：清单/条数/预计大小，用于“导出前预览选项”页面。 */
data class ExportPreview(
    val exportId: String,
    val documents: List<ExportDocumentPreview>,
    val noteFileCount: Int,
    val annotationCount: Int,
    val inkPageCount: Int,
    val estimatedBytes: Long,
    val options: BackupOptions,
    val warnings: List<String>,
) {
    val documentCount: Int get() = documents.size

    val estimatedMiB: Double get() = estimatedBytes / (1024.0 * 1024.0)
}

/** 被拒绝导出的文档（无权限/已删除/未就绪），用于预览明示而不是静默丢弃。 */
data class ExportRejection(
    val documentId: String,
    val code: String,
    val detail: String,
)

/** 导出结果：只有完整写出 manifest + 校验通过才算 Completed。 */
sealed interface ExportOutcome {
    data class Rejected(val code: String, val detail: String) : ExportOutcome

    data class Completed(
        val preview: ExportPreview,
        val directory: File,
        val manifestHash: String,
        val rejectedDocuments: List<ExportRejection>,
    ) : ExportOutcome
}

/** 恢复预览：数量、是否检测到同书、预测冲突、损坏条目。 */
data class RestorePreview(
    val exportId: String,
    val manifestHash: String,
    val appVersion: String,
    val createdAt: Long,
    val sourceContentHash: String?,
    val documentCount: Int,
    val noteCount: Int,
    val annotationCount: Int,
    val inkFileCount: Int,
    val totalBytes: Long,
    val sameBookDetected: Boolean,
    /** exportDocumentId -> 本地可复用 documentId（只收 READY 的内置书/本人导入书）。 */
    val reusableDocumentIds: Map<String, String>,
    val predictedConflicts: Int,
    val invalidAnnotations: Int,
    /** 无同 hash 原书、将恢复为“来源待关联”的文档数。 */
    val noSourceDocumentCount: Int,
    val warnings: List<String>,
)

/** 一条恢复问题；retryable=true 表示可重试，blocking=true 表示整体未提交。 */
data class RestoreIssue(
    val code: String,
    val detail: String,
    val retryable: Boolean = false,
    val blocking: Boolean = false,
    val path: String? = null,
) {
    override fun toString(): String =
        buildString {
            append(code)
            path?.let { append("(").append(it).append(")") }
            append(": ").append(detail)
            if (retryable) append(" [可重试]")
        }
}

enum class RestoreStage { APPLIED, PARTIAL, FAILED }

/**
 * 恢复报告。
 *
 * 关键语义：`stage` 与 `fullyApplied` 只反映**已提交**的结果；
 * 有损坏/可重试项时 stage=PARTIAL，绝不把部分失败报成全部成功。
 */
data class RestoreReport(
    val restoreId: String,
    val exportId: String,
    val manifestHash: String,
    val userId: Long,
    val stage: RestoreStage,
    val documentsAdded: Int,
    val documentsReused: Int,
    val documentsNoSource: Int,
    val notesAdded: Int,
    val notesReused: Int,
    val noteCopies: Int,
    val annotationsAdded: Int,
    val annotationsReused: Int,
    val inkPagesAdded: Int,
    val inkPagesReused: Int,
    val conflicts: Int,
    val damaged: Int,
    val retryable: Int,
    val skipped: Int,
    val documentIdMap: Map<String, String>,
    val copyDocumentIds: List<String>,
    val noSourceDocumentIds: List<String>,
    val issues: List<RestoreIssue>,
    val warnings: List<String>,
) {
    val fullyApplied: Boolean
        get() = stage == RestoreStage.APPLIED && damaged == 0 && retryable == 0 &&
            issues.none { it.blocking }

    /**
     * “新增/复用/冲突/损坏/可重试”五类数量（报告页与测试共用同一口径）。
     */
    fun summary(): Map<String, Int> = linkedMapOf(
        "added" to (documentsAdded + notesAdded + annotationsAdded + inkPagesAdded),
        "reused" to (documentsReused + notesReused + annotationsReused + inkPagesReused),
        "conflicts" to conflicts,
        "damaged" to damaged,
        "retryable" to retryable,
        "skipped" to skipped,
    )
}

/** 恢复结果。失败（校验/事务）返回 Rejected，且不写库。 */
sealed interface RestoreOutcome {
    data class Previewed(val preview: RestorePreview) : RestoreOutcome

    data class Applied(val report: RestoreReport) : RestoreOutcome

    /** 已提交但存在损坏/可重试/冲突项，调用方必须展示明细。 */
    data class PartialFailure(val report: RestoreReport) : RestoreOutcome

    data class Rejected(val code: String, val detail: String) : RestoreOutcome
}

/**
 * 恢复映射表（§19）：包内 export 命名空间 -> 当前用户 namespace。
 *
 * - documentIds：exportDocumentId -> 本地 documentId
 * - contentRevisions：exportDocumentId -> 复用真实书时的本地 contentRevision
 * - notebookIds：exportDocumentId -> 本地 notebookId
 */
data class RestoreIdMap(
    val documentIds: Map<String, String> = emptyMap(),
    val contentRevisions: Map<String, String> = emptyMap(),
    val notebookIds: Map<String, String> = emptyMap(),
)

/**
 * Locator 重写（纯函数）。
 *
 * 只改归属与位置引用，不改写历史 quote / 偏移 / rect；目标文档无法解析时返回 null，
 * 由调用方计为 damaged，绝不把旧用户 internal id 当成可用路径。
 */
object RestoreLocatorRewriter {
    fun rewrite(
        locator: ReaderLocator,
        idMap: RestoreIdMap,
        sectionId: String? = null,
        notebookId: String? = null,
    ): ReaderLocator? {
        val targetDocumentId = idMap.documentIds[locator.documentId] ?: return null
        val targetRevision = idMap.contentRevisions[locator.documentId] ?: locator.contentRevision
        val targetSectionId = sectionId ?: locator.sectionId
        val rewritten = when (locator) {
            is ReaderLocator.Reflow -> locator.copy(
                documentId = targetDocumentId,
                contentRevision = targetRevision,
                sectionId = targetSectionId,
            )

            is ReaderLocator.Fixed -> locator.copy(
                documentId = targetDocumentId,
                contentRevision = targetRevision,
                sectionId = targetSectionId,
            )

            is ReaderLocator.Note -> locator.copy(
                documentId = targetDocumentId,
                contentRevision = targetRevision,
                sectionId = targetSectionId,
                notebookId = notebookId ?: idMap.notebookIds[locator.documentId] ?: locator.notebookId,
            )
        }
        return if (ReaderLocatorValidator.validate(rewritten).isValid) rewritten else null
    }
}

/**
 * 导出侧引用映射：把内部 id 改写为包内命名空间。
 *
 * 包内 locator.documentId 必须是 exportDocumentId（manifest 校验会强制），
 * 这样包内不出现任何内部文档 id，也不能借包内 URI 访问他人 internal id。
 */
object PortableReferenceMapper {
    fun toPortable(
        locator: ReaderLocator,
        exportDocumentId: String,
        portableNotebookId: String? = null,
    ): ReaderLocator? {
        val portable = when (locator) {
            is ReaderLocator.Reflow -> locator.copy(documentId = exportDocumentId)
            is ReaderLocator.Fixed -> locator.copy(documentId = exportDocumentId)
            is ReaderLocator.Note -> locator.copy(
                documentId = exportDocumentId,
                notebookId = portableNotebookId ?: locator.notebookId,
            )
        }
        return if (ReaderLocatorValidator.validate(portable).isValid) portable else null
    }
}

/**
 * 当前用户可复用文档索引。
 *
 * 只把 READY 的内置书与本人导入书算作“可复用”；同名不同 hash 的文档一律不合并。
 */
data class RestoreDocumentIndex(
    val importedByHash: Map<String, String> = emptyMap(),
    val builtinByRevision: Map<String, String> = emptyMap(),
) {
    fun reusableDocumentId(document: BackupDocument): String? {
        document.sourceSha256?.let { hash -> importedByHash[hash]?.let { return it } }
        val revision = document.contentRevision ?: return null
        return builtinByRevision[revision]
    }
}

/** 纯逻辑的恢复预览判定，供 JVM 单测覆盖 sameBookDetected。 */
object RestorePreviewer {
    fun resolveReusableDocument(document: BackupDocument, index: RestoreDocumentIndex): String? =
        index.reusableDocumentId(document)

    fun sameBookDetected(manifest: BackupManifest, index: RestoreDocumentIndex): Boolean =
        manifest.documents.any { resolveReusableDocument(it, index) != null }
}

/**
 * annotations.json 的一条（含可选 rects / inkPage）。
 *
 * 额外字段 `userId/ownerUserId` 即使出现在包内也**不读取**（恢复只绑定当前用户）。
 */
data class BackupAnnotationEntry(
    val id: String,
    val documentId: String,
    val type: String,
    val quote: String?,
    val color: String?,
    val underline: Boolean,
    val sectionId: String?,
    val sectionTitle: String?,
    val locator: ReaderLocator,
    val contentRevision: String,
    val createdAt: Long,
    val updatedAt: Long,
    val noteText: String?,
    val rects: List<NormalizedRect> = emptyList(),
    val inkPage: Int? = null,
)

/** 解码结果：fatal=true 表示整份 annotations.json 不可用（preview 应拒绝）。 */
data class DecodedAnnotations(
    val entries: List<BackupAnnotationEntry>,
    val issues: List<BackupIssue>,
    val fatal: Boolean,
)

/**
 * annotations.json 编解码。
 *
 * 与 manifest 不同，这里**容忍未知字段**：包内残留的 `userId` 等旧字段会被忽略而不是报错，
 * 恢复永远使用当前用户 id（跨用户 userId 被忽略的设备用例即覆盖此点）。
 */
object BackupAnnotationCodec {
    private const val SCHEMA_VERSION = 1
    private const val MAX_QUOTE_CHARS = 8192
    private const val MAX_NOTE_CHARS = 1_000_000
    private const val MAX_RAW_BYTES = 96 * 1024 * 1024
    private const val MAX_ENTRIES = 100_000

    private val strictRoot: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = true
        allowSpecialFloatingPointValues = false
    }

    private val tolerantEntry: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = true
        allowSpecialFloatingPointValues = false
    }

    fun encode(entries: List<BackupAnnotationEntry>): String {
        entries.forEach { entry ->
            val issues = validateEntry(entry)
            if (issues.isNotEmpty()) {
                throw BackupCodecException(
                    "标注条目非法：${issues.joinToString("; ") { it.toString() }}",
                    null,
                    issues,
                )
            }
        }
        return buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            put("annotations", buildJsonArray {
                entries.forEach { add(it.toJson()) }
            })
        }.toString()
    }

    fun decodeEntries(raw: String): DecodedAnnotations {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_RAW_BYTES) {
            return DecodedAnnotations(
                emptyList(),
                listOf(BackupIssue(BackupIssueCodes.PACKAGE_TOO_LARGE, "annotations.json 超过 $MAX_RAW_BYTES 字节")),
                fatal = true,
            )
        }
        val root = try {
            strictRoot.parseToJsonElement(raw)
        } catch (error: Exception) {
            return DecodedAnnotations(
                emptyList(),
                listOf(BackupIssue(BackupIssueCodes.ANNOTATIONS_CORRUPT, "annotations.json 不是合法 JSON")),
                fatal = true,
            )
        }
        val obj = root as? JsonObject
            ?: return DecodedAnnotations(
                emptyList(),
                listOf(BackupIssue(BackupIssueCodes.ANNOTATIONS_CORRUPT, "annotations.json 根节点必须是对象")),
                fatal = true,
            )
        val unknown = obj.keys - setOf("schemaVersion", "annotations")
        if (unknown.isNotEmpty()) {
            return DecodedAnnotations(
                emptyList(),
                listOf(BackupIssue(BackupIssueCodes.ANNOTATIONS_CORRUPT, "未知根字段：${unknown.sorted().joinToString(",")}")),
                fatal = true,
            )
        }
        val version = (obj["schemaVersion"] as? JsonPrimitive)?.content?.toIntOrNull()
        if (version != SCHEMA_VERSION) {
            return DecodedAnnotations(
                emptyList(),
                listOf(BackupIssue(BackupIssueCodes.UNSUPPORTED_MANIFEST_VERSION, "annotations schemaVersion 不受支持")),
                fatal = true,
            )
        }
        val array = obj["annotations"] as? JsonArray
            ?: return DecodedAnnotations(
                emptyList(),
                listOf(BackupIssue(BackupIssueCodes.ANNOTATIONS_CORRUPT, "annotations 必须是数组")),
                fatal = true,
            )
        if (array.size > MAX_ENTRIES) {
            return DecodedAnnotations(
                emptyList(),
                listOf(BackupIssue(BackupIssueCodes.PACKAGE_TOO_LARGE, "标注条目超过 $MAX_ENTRIES")),
                fatal = true,
            )
        }

        val entries = mutableListOf<BackupAnnotationEntry>()
        val issues = mutableListOf<BackupIssue>()
        array.forEachIndexed { index, element ->
            try {
                val entry = element.toEntry("annotations[$index]")
                val entryIssues = validateEntry(entry)
                if (entryIssues.isEmpty()) {
                    entries += entry
                } else {
                    issues += entryIssues.map { issue ->
                        issue.copy(detail = "annotations[$index] ${issue.detail}")
                    }
                }
            } catch (error: Exception) {
                issues += BackupIssue(
                    BackupIssueCodes.INVALID_ANNOTATION,
                    "annotations[$index] 无法解析：${error.message}",
                )
            }
        }
        return DecodedAnnotations(entries, issues, fatal = false)
    }

    fun validateEntry(entry: BackupAnnotationEntry): List<BackupIssue> {
        val issues = mutableListOf<BackupIssue>()
        if (entry.id.isBlank() || entry.id.length > 512 || entry.id.any(Char::isISOControl)) {
            issues += BackupIssue(BackupIssueCodes.INVALID_ANNOTATION, "id 非法")
        }
        if (entry.documentId.isBlank() || entry.documentId.length > 128) {
            issues += BackupIssue(BackupIssueCodes.INVALID_ANNOTATION, "documentId 非法")
        }
        if (entry.type !in ANNOTATION_TYPES) {
            issues += BackupIssue(BackupIssueCodes.INVALID_ANNOTATION, "type 不受支持：${entry.type}")
        }
        if (entry.type != "INK" && entry.quote.isNullOrBlank()) {
            issues += BackupIssue(BackupIssueCodes.INVALID_ANNOTATION, "非手写标注必须带 quote")
        }
        entry.quote?.let { quote ->
            if (quote.length > MAX_QUOTE_CHARS) {
                issues += BackupIssue(BackupIssueCodes.INVALID_ANNOTATION, "quote 超长")
            }
        }
        entry.noteText?.let { note ->
            if (note.length > MAX_NOTE_CHARS) {
                issues += BackupIssue(BackupIssueCodes.INVALID_ANNOTATION, "noteText 超长")
            }
        }
        entry.color?.let { color ->
            if (runCatching { AnnotationColor.valueOf(color) }.isFailure) {
                issues += BackupIssue(BackupIssueCodes.INVALID_ANNOTATION, "color 不受支持：$color")
            }
        }
        if (entry.createdAt < 0 || entry.updatedAt < 0) {
            issues += BackupIssue(BackupIssueCodes.INVALID_ANNOTATION, "时间戳不能为负")
        }
        if (entry.contentRevision.isBlank()) {
            issues += BackupIssue(BackupIssueCodes.INVALID_ANNOTATION, "contentRevision 不能为空")
        }
        if (entry.locator.documentId != entry.documentId) {
            issues += BackupIssue(
                BackupIssueCodes.INVALID_LOCATOR,
                "locator.documentId 必须等于标注的 documentId（包内命名空间）",
            )
        }
        val locatorValidation = ReaderLocatorValidator.validate(entry.locator)
        if (!locatorValidation.isValid) {
            issues += BackupIssue(
                BackupIssueCodes.INVALID_LOCATOR,
                "locator 非法：${locatorValidation.errors.joinToString("; ")}",
            )
        }
        entry.rects.forEachIndexed { index, rect ->
            val values = listOf(rect.left, rect.top, rect.right, rect.bottom)
            if (values.any { !it.isFinite() } || rect.width <= 0f || rect.height <= 0f ||
                rect.left < -0.0001f || rect.top < -0.0001f ||
                rect.right > 1.0001f || rect.bottom > 1.0001f
            ) {
                issues += BackupIssue(BackupIssueCodes.INVALID_ANNOTATION, "rects[$index] 非法")
            }
        }
        entry.inkPage?.let { page ->
            if (page < 0) issues += BackupIssue(BackupIssueCodes.INVALID_ANNOTATION, "inkPage 不能为负")
        }
        return issues
    }

    private fun BackupAnnotationEntry.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("documentId", documentId)
        put("type", type)
        quote?.let { put("quote", it) }
        color?.let { put("color", it) }
        put("underline", underline)
        sectionId?.let { put("sectionId", it) }
        sectionTitle?.let { put("sectionTitle", it) }
        put("locator", Json.parseToJsonElement(ReaderLocatorCodec.encode(locator)))
        put("contentRevision", contentRevision)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        noteText?.let { put("noteText", it) }
        if (rects.isNotEmpty()) {
            put("rects", buildJsonArray { rects.forEach { add(it.toJsonObject()) } })
        }
        inkPage?.let { put("inkPage", it) }
    }

    private fun JsonElement.toEntry(field: String): BackupAnnotationEntry {
        val obj = this as? JsonObject ?: throw BackupCodecException("$field 必须是对象")
        // 容忍未知字段（含包内遗留的 userId/ownerUserId），但绝不读取它们作为归属。
        val locatorElement = obj["locator"] ?: throw BackupCodecException("$field 缺少 locator")
        val locator = ReaderLocatorCodec.decode(locatorElement.toString())
        val rects = (obj["rects"] as? JsonArray)?.mapIndexed { index, item ->
            item.toRect("$field.rects[$index]")
        } ?: emptyList()
        return BackupAnnotationEntry(
            id = obj.requireString("id"),
            documentId = obj.requireString("documentId"),
            type = obj.requireString("type"),
            quote = obj.optionalString("quote"),
            color = obj.optionalString("color"),
            underline = obj.optionalBoolean("underline") ?: false,
            sectionId = obj.optionalString("sectionId"),
            sectionTitle = obj.optionalString("sectionTitle"),
            locator = locator,
            contentRevision = obj.requireString("contentRevision"),
            createdAt = obj.optionalLong("createdAt") ?: 0L,
            updatedAt = obj.optionalLong("updatedAt") ?: (obj.optionalLong("createdAt") ?: 0L),
            noteText = obj.optionalString("noteText"),
            rects = rects,
            inkPage = obj.optionalInt("inkPage"),
        )
    }

    private fun JsonElement.toRect(field: String): NormalizedRect {
        val obj = this as? JsonObject ?: throw BackupCodecException("$field 必须是对象")
        return NormalizedRect(
            left = obj.requireFloat("left", field),
            top = obj.requireFloat("top", field),
            right = obj.requireFloat("right", field),
            bottom = obj.requireFloat("bottom", field),
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

    private fun JsonObject.optionalInt(name: String): Int? {
        val element = this[name] ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive
            ?: throw BackupCodecException("$name 必须是整数或 null")
        if (primitive.isString) throw BackupCodecException("$name 必须是整数或 null")
        return primitive.content.toIntOrNull() ?: throw BackupCodecException("$name 必须是整数或 null")
    }

    private fun JsonObject.optionalLong(name: String): Long? {
        val element = this[name] ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive
            ?: throw BackupCodecException("$name 必须是整数或 null")
        if (primitive.isString) throw BackupCodecException("$name 必须是整数或 null")
        return primitive.content.toLongOrNull() ?: throw BackupCodecException("$name 必须是整数或 null")
    }

    private fun JsonObject.optionalBoolean(name: String): Boolean? {
        val element = this[name] ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive
            ?: throw BackupCodecException("$name 必须是布尔值或 null")
        return when (primitive.content) {
            "true" -> true
            "false" -> false
            else -> throw BackupCodecException("$name 必须是布尔值或 null")
        }
    }

    private fun JsonObject.requireFloat(name: String, prefix: String): Float {
        val element = this[name] ?: throw BackupCodecException("缺少 $prefix.$name")
        val primitive = element as? JsonPrimitive
            ?: throw BackupCodecException("$prefix.$name 必须是数字")
        if (primitive.isString) throw BackupCodecException("$prefix.$name 必须是数字")
        val value = primitive.content.toFloatOrNull()
            ?: throw BackupCodecException("$prefix.$name 必须是数字")
        if (!value.isFinite()) throw BackupCodecException("$prefix.$name 必须有限")
        return value
    }

    private val ANNOTATION_TYPES = setOf("HIGHLIGHT", "NOTE", "INK")
}
