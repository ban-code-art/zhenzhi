package com.example.myapplication.data.ocr

import com.example.myapplication.domain.ocr.OcrPageResult
import com.example.myapplication.domain.ocr.OcrTaskKey
import com.example.myapplication.domain.ocr.OcrTextBox
import com.example.myapplication.domain.reader.ExtractionQuality
import com.example.myapplication.domain.reader.NormalizedRect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OCR 逐页检查点（文件持久化，不动 Room schema）。
 *
 * 任务键（documentId/sourceFileHash/engineId/modelVersion/extractorVersion）任一变化，
 * 旧检查点即作废：换了文件、换了模型或换了抽取器都不能拿旧识别结果冒充新结果。
 */
data class OcrCheckpoint(
    val schemaVersion: Int,
    val documentId: String,
    val sourceFileHash: String,
    val engineId: String,
    val modelVersion: String,
    val extractorVersion: String,
    val updatedAt: Long,
    val pages: List<OcrPageCheckpoint>,
) {
    fun matches(
        documentId: String,
        sourceFileHash: String,
        engineId: String,
        modelVersion: String,
        extractorVersion: String,
    ): Boolean =
        schemaVersion == OcrCheckpointCodec.SCHEMA_VERSION &&
            this.documentId == documentId &&
            this.sourceFileHash == sourceFileHash &&
            this.engineId == engineId &&
            this.modelVersion == modelVersion &&
            this.extractorVersion == extractorVersion

    fun page(pageIndex: Int): OcrPageCheckpoint? = pages.firstOrNull { it.pageIndex == pageIndex }
}

data class OcrPageCheckpoint(
    val pageIndex: Int,
    val quality: ExtractionQuality,
    val text: String,
    val boxes: List<OcrBoxCheckpoint>,
    val providerConfidence: Double?,
    val algorithmVersion: String,
    val humanVerifiedByUserId: Long? = null,
    val humanVerifiedAt: Long? = null,
) {
    fun toResult(taskKey: OcrTaskKey): OcrPageResult = OcrPageResult(
        taskKey = taskKey,
        boxes = boxes.map { box ->
            OcrTextBox(
                text = box.text,
                rect = NormalizedRect(box.left, box.top, box.right, box.bottom),
                readingOrder = box.readingOrder,
            )
        },
        quality = quality,
        providerConfidence = providerConfidence,
        qualityAlgorithmVersion = algorithmVersion,
    )

    companion object {
        fun from(pageIndex: Int, result: OcrPageResult): OcrPageCheckpoint = OcrPageCheckpoint(
            pageIndex = pageIndex,
            quality = result.quality,
            text = result.text,
            boxes = result.boxes.map { box ->
                OcrBoxCheckpoint(
                    text = box.text,
                    left = box.rect.left,
                    top = box.rect.top,
                    right = box.rect.right,
                    bottom = box.rect.bottom,
                    readingOrder = box.readingOrder,
                )
            },
            providerConfidence = result.providerConfidence,
            algorithmVersion = result.qualityAlgorithmVersion,
        )
    }
}

data class OcrBoxCheckpoint(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val readingOrder: Int,
)

/** JSON 编解码；坏文件返回 null，由调用方当作“没有检查点”。 */
object OcrCheckpointCodec {
    const val SCHEMA_VERSION = 1
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(checkpoint: OcrCheckpoint): String = buildJsonObject {
        put("schemaVersion", checkpoint.schemaVersion)
        put("documentId", checkpoint.documentId)
        put("sourceFileHash", checkpoint.sourceFileHash)
        put("engineId", checkpoint.engineId)
        put("modelVersion", checkpoint.modelVersion)
        put("extractorVersion", checkpoint.extractorVersion)
        put("updatedAt", checkpoint.updatedAt)
        putJsonArray("pages") {
            checkpoint.pages.sortedBy { it.pageIndex }.forEach { page ->
                add(
                    buildJsonObject {
                        put("pageIndex", page.pageIndex)
                        put("quality", page.quality.name)
                        put("text", page.text)
                        put("providerConfidence", page.providerConfidence)
                        put("algorithmVersion", page.algorithmVersion)
                        page.humanVerifiedByUserId?.let { put("humanVerifiedByUserId", it) }
                        page.humanVerifiedAt?.let { put("humanVerifiedAt", it) }
                        putJsonArray("boxes") {
                            page.boxes.forEach { box ->
                                add(
                                    buildJsonObject {
                                        put("text", box.text)
                                        put("left", box.left)
                                        put("top", box.top)
                                        put("right", box.right)
                                        put("bottom", box.bottom)
                                        put("readingOrder", box.readingOrder)
                                    },
                                )
                            }
                        }
                    },
                )
            }
        }
    }.toString()

    fun decode(raw: String): OcrCheckpoint? = runCatching {
        val root = json.parseToJsonElement(raw).jsonObject
        val schemaVersion = root.int("schemaVersion") ?: return null
        val pages = root["pages"]?.jsonArray?.mapNotNull { element ->
            val page = element.jsonObject
            val pageIndex = page.int("pageIndex") ?: return@mapNotNull null
            val quality = page.string("quality")
                ?.let { runCatching { ExtractionQuality.valueOf(it) }.getOrNull() }
                ?: return@mapNotNull null
            OcrPageCheckpoint(
                pageIndex = pageIndex,
                quality = quality,
                text = page.string("text").orEmpty(),
                boxes = page["boxes"]?.jsonArray?.mapNotNull { boxElement ->
                    val box = boxElement.jsonObject
                    OcrBoxCheckpoint(
                        text = box.string("text").orEmpty(),
                        left = box.float("left") ?: return@mapNotNull null,
                        top = box.float("top") ?: return@mapNotNull null,
                        right = box.float("right") ?: return@mapNotNull null,
                        bottom = box.float("bottom") ?: return@mapNotNull null,
                        readingOrder = box.int("readingOrder") ?: 0,
                    )
                }.orEmpty(),
                providerConfidence = page.float("providerConfidence")?.toDouble(),
                algorithmVersion = page.string("algorithmVersion").orEmpty(),
                humanVerifiedByUserId = page.long("humanVerifiedByUserId"),
                humanVerifiedAt = page.long("humanVerifiedAt"),
            )
        }.orEmpty()
        OcrCheckpoint(
            schemaVersion = schemaVersion,
            documentId = root.string("documentId").orEmpty(),
            sourceFileHash = root.string("sourceFileHash").orEmpty(),
            engineId = root.string("engineId").orEmpty(),
            modelVersion = root.string("modelVersion").orEmpty(),
            extractorVersion = root.string("extractorVersion").orEmpty(),
            updatedAt = root.long("updatedAt") ?: 0L,
            pages = pages,
        )
    }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.content?.toIntOrNull()

    private fun JsonObject.long(key: String): Long? =
        this[key]?.jsonPrimitive?.content?.toLongOrNull()

    private fun JsonObject.float(key: String): Float? =
        this[key]?.jsonPrimitive?.content?.toFloatOrNull()
}
