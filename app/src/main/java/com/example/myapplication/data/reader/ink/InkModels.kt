package com.example.myapplication.data.reader.ink

import com.example.myapplication.domain.reader.NormalizedRect
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

enum class InkTool { PEN, MARKER, ERASER }

enum class InkAnchorKind { FIXED_PAGE, TEXT_RANGE, BLOCK_RECT, LAYOUT_SNAPSHOT }

data class InkPoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1f,
    val timestamp: Long = 0,
) {
    init {
        require(x.isFinite() && y.isFinite()) { "ink point must be finite" }
        require(x in 0f..1f && y in 0f..1f) { "ink point must be normalized" }
        require(pressure.isFinite() && pressure in 0f..1f) { "ink pressure must be normalized" }
    }
}

data class InkStroke(
    val strokeId: String,
    val tool: InkTool,
    val color: String,
    val width: Float,
    val points: List<InkPoint>,
    val createdAt: Long,
    val usesDocumentWidth: Boolean = false,
    val scrollContainer: String? = null,
) {
    init {
        require(scrollContainer == null || (scrollContainer.length in 1..192 && scrollContainer.all { it.isDigit() || it == '.' }))
        require(strokeId.isNotBlank()) { "strokeId must not be blank" }
        require(color.matches(Regex("^#[0-9a-fA-F]{6}$"))) { "ink color must be #RRGGBB" }
        require(width.isFinite() && width > 0f) { "ink width must be positive" }
        require(points.size in 1..MAX_POINTS) { "ink stroke point count is invalid" }
    }

    fun hitTest(x: Float, y: Float, tolerance: Float): Boolean =
        points.any { point ->
            val dx = point.x - x
            val dy = point.y - y
            dx * dx + dy * dy <= tolerance * tolerance
        }

    companion object {
        const val MAX_POINTS = 4096
    }
}

data class InkManifest(
    val documentId: String,
    val sectionId: String,
    val contentRevision: String,
    val pageIndex: Int,
    val pageBox: NormalizedRect,
    val rotationDegrees: Int,
    val anchorKind: InkAnchorKind,
    val layoutKey: String,
    val revision: Long,
    val strokes: List<InkStroke>,
    val createdAt: Long,
    val updatedAt: Long,
) {
    init {
        require(documentId.isNotBlank() && sectionId.isNotBlank() && contentRevision.isNotBlank())
        require(pageIndex >= 0)
        require(rotationDegrees in setOf(0, 90, 180, 270))
        require(layoutKey.isNotBlank())
        require(strokes.size <= MAX_STROKES)
        require(strokes.map { it.strokeId }.distinct().size == strokes.size)
    }

    fun adding(stroke: InkStroke, now: Long): InkManifest =
        copy(strokes = strokes + stroke, updatedAt = now)

    fun removing(strokeId: String, now: Long): InkManifest =
        copy(strokes = strokes.filterNot { it.strokeId == strokeId }, updatedAt = now)

    companion object {
        const val MAX_STROKES = 1000
    }
}

object InkManifestCodec {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false; explicitNulls = true }

    fun encode(manifest: InkManifest): String = buildJsonObject {
        put("schemaVersion", 1)
        put("documentId", manifest.documentId)
        put("sectionId", manifest.sectionId)
        put("contentRevision", manifest.contentRevision)
        put("pageIndex", manifest.pageIndex)
        put("pageBox", manifest.pageBox.toJson())
        put("rotation", manifest.rotationDegrees)
        put("anchorKind", manifest.anchorKind.name)
        put("layoutKey", manifest.layoutKey)
        put("revision", manifest.revision)
        put("createdAt", manifest.createdAt)
        put("updatedAt", manifest.updatedAt)
        put("strokes", buildJsonArray {
            manifest.strokes.forEach { stroke ->
                add(buildJsonObject {
                    put("strokeId", stroke.strokeId)
                    put("tool", stroke.tool.name)
                    put("color", stroke.color)
                    put("width", stroke.width)
                    put("createdAt", stroke.createdAt)
                    if (stroke.usesDocumentWidth) put("usesDocumentWidth", true)
                    stroke.scrollContainer?.let { put("scrollContainer", it) }
                    put("points", buildJsonArray {
                        stroke.points.forEach { point ->
                            add(buildJsonObject {
                                put("x", point.x)
                                put("y", point.y)
                                put("pressure", point.pressure)
                                put("time", point.timestamp)
                            })
                        }
                    })
                })
            }
        })
    }.toString()

    fun decode(raw: String): InkManifest {
        val root = json.parseToJsonElement(raw).jsonObject
        return InkManifest(
            documentId = root.string("documentId"),
            sectionId = root.string("sectionId"),
            contentRevision = root.string("contentRevision"),
            pageIndex = root.int("pageIndex"),
            pageBox = root.obj("pageBox").toRect(),
            rotationDegrees = root.int("rotation"),
            anchorKind = InkAnchorKind.valueOf(root.string("anchorKind")),
            layoutKey = root.string("layoutKey"),
            revision = root.long("revision"),
            strokes = root.array("strokes").mapIndexed { index, element -> element.toStroke(index) },
            createdAt = root.long("createdAt"),
            updatedAt = root.long("updatedAt"),
        )
    }

    fun decodeOrNull(raw: String?): InkManifest? = raw?.let { runCatching { decode(it) }.getOrNull() }

    private fun JsonElement.toStroke(index: Int): InkStroke {
        val obj = asObject("strokes[$index]")
        return InkStroke(
            strokeId = obj.string("strokeId"),
            tool = InkTool.valueOf(obj.string("tool")),
            color = obj.string("color"),
            width = obj.float("width"),
            points = obj.array("points").mapIndexed { pointIndex, point ->
                val value = point.asObject("strokes[$index].points[$pointIndex]")
                InkPoint(
                    x = value.float("x"),
                    y = value.float("y"),
                    pressure = value.float("pressure"),
                    timestamp = value.long("time"),
                )
            },
            createdAt = obj.long("createdAt"),
            usesDocumentWidth = (obj["usesDocumentWidth"] as? JsonPrimitive)?.content == "true",
            scrollContainer = (obj["scrollContainer"] as? JsonPrimitive)?.content,
        )
    }

    private fun NormalizedRect.toJson(): JsonObject = buildJsonObject {
        put("left", left)
        put("top", top)
        put("right", right)
        put("bottom", bottom)
    }

    private fun JsonObject.toRect(): NormalizedRect = NormalizedRect(
        left = float("left"),
        top = float("top"),
        right = float("right"),
        bottom = float("bottom"),
    )

    private fun JsonElement.asObject(field: String): JsonObject =
        this as? JsonObject ?: error("$field must be an object")

    private fun JsonObject.string(name: String): String =
        (this[name] as? JsonPrimitive)?.content ?: error("missing $name")

    private fun JsonObject.int(name: String): Int =
        (this[name] as? JsonPrimitive)?.content?.toIntOrNull() ?: error("missing $name")

    private fun JsonObject.long(name: String): Long =
        (this[name] as? JsonPrimitive)?.content?.toLongOrNull() ?: error("missing $name")

    private fun JsonObject.float(name: String): Float =
        (this[name] as? JsonPrimitive)?.content?.toFloatOrNull() ?: error("missing $name")

    private fun JsonObject.obj(name: String): JsonObject =
        this[name] as? JsonObject ?: error("missing $name")

    private fun JsonObject.array(name: String): JsonArray =
        this[name] as? JsonArray ?: error("missing $name")
}

/**
 * 墨迹文件存储（二期 P1-14）。
 *
 * 根目录只是共享父目录（默认 `files/reader/ink`）；真正的读写根按用户解析为
 * `u<userId>/doc-<sha(documentId)>/section-<sha(sectionId)>/page-N.json`。
 *
 * 内置书对本地所有用户可读，若墨迹路径不含 userId，甲用户的笔迹会出现在乙用户
 * 页面并互相覆盖。因此本类**不保存"当前用户"**：每次读写都必须显式传入 userId，
 * 容器也不得在初始化时固定用户（用户可能在会话中切换）。
 *
 * 缺用户（userId <= 0）时读取返回 null、写入直接拒绝，绝不回落到共享目录；
 * 相对路径首段必须是 `u<userId>`，他人路径视为非法（读取返回 null、删除返回 false），
 * 旧版共享路径 `doc-.../section-.../page-N.json` 不再解析，避免跨用户串读。
 */
class InkStore(private val rootDirectory: File) {
    fun load(userId: Long, relativePath: String): InkManifest? {
        if (userId <= 0L) return null
        val file = runCatching { resolve(userId, relativePath) }.getOrNull() ?: return null
        return runCatching {
            InkManifestCodec.decode(file.readText(StandardCharsets.UTF_8))
        }.getOrNull()
    }

    fun loadManifest(userId: Long, relativePath: String): InkManifest? = load(userId, relativePath)

    fun save(
        userId: Long,
        documentId: String,
        sectionId: String,
        contentRevision: String,
        pageIndex: Int,
        manifest: InkManifest,
    ): String {
        require(userId > 0L) { "ink storage requires a user" }
        require(manifest.documentId == documentId)
        require(manifest.sectionId == sectionId)
        require(manifest.contentRevision == contentRevision)
        require(manifest.pageIndex == pageIndex)
        val relativePath = relativePath(userId, documentId, sectionId, pageIndex)
        val target = resolve(userId, relativePath)
        target.parentFile?.mkdirs()
        val temporary = File(requireNotNull(target.parentFile), "${target.name}.tmp-${UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(InkManifestCodec.encode(manifest).toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            if (target.exists() && !target.delete()) {
                throw IllegalStateException("could not replace ink manifest")
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                if (!temporary.delete()) temporary.deleteOnExit()
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
        return relativePath
    }

    fun delete(userId: Long, relativePath: String): Boolean {
        if (userId <= 0L) return false
        return runCatching { resolve(userId, relativePath).delete() }.getOrDefault(false)
    }

    /** 用户墨迹根（`files/reader/ink/u<userId>`）；用户非法时拒绝，不回落到共享根。 */
    fun userDirectory(userId: Long): File {
        require(userId > 0L) { "ink storage requires a user" }
        return File(rootDirectory, userSegment(userId))
    }

    private fun relativePath(userId: Long, documentId: String, sectionId: String, pageIndex: Int): String {
        require(userId > 0L) { "ink storage requires a user" }
        require(pageIndex >= 0)
        val doc = sha256(documentId).take(20)
        val section = sha256(sectionId).take(20)
        return "${userSegment(userId)}/doc-$doc/section-$section/page-$pageIndex.json"
    }

    private fun resolve(userId: Long, relativePath: String): File {
        require(userId > 0L) { "ink storage requires a user" }
        val root = rootDirectory.canonicalFile
        val userRoot = userDirectory(userId).canonicalFile
        val normalized = relativePath.replace('\\', '/').trim()
        require(normalized.isNotEmpty() && !normalized.startsWith("/")) { "ink path is invalid" }
        require(normalized.split('/').first() == userSegment(userId)) {
            "ink path belongs to another user"
        }
        val candidate = File(root, normalized.replace('/', File.separatorChar)).canonicalFile
        val prefix = userRoot.path + File.separator
        require(candidate.path.startsWith(prefix)) { "ink path escapes storage root" }
        return candidate
    }

    private fun userSegment(userId: Long): String = "u$userId"

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
