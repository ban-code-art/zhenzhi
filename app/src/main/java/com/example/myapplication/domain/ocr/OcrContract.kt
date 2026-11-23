package com.example.myapplication.domain.ocr

import com.example.myapplication.domain.reader.ExtractionQuality
import com.example.myapplication.domain.reader.NormalizedRect

/**
 * 离线 OCR 契约（W5-03）。
 *
 * 设计约束（第五期详细设计 §3/§12）：
 *  - 引擎必须可离线打包，模型版本与 extractor 版本都进任务键；
 *  - 按页/tile 处理，页内文字框按阅读顺序归一，旋转先归一；
 *  - 引擎不返回置信度时，[OcrPageResult.providerConfidence] 保持 null，绝不编造百分比；
 *  - 低质量结果只能是 OCR_REVIEW_REQUIRED，不得自动用于出题；
 *  - 未配置引擎时明确返回 [OcrOutcome.Unavailable]，不伪造识别文本。
 */
enum class OcrAvailability {
    /** 没有可识别内容（空文档/无页面）。 */
    NONE,

    /** 页面自带文字层，无需 OCR。 */
    TEXT_LAYER,

    /** 存在缺文本页，等待 OCR。 */
    OCR_PENDING,

    /** OCR 已完成且质量达标。 */
    OCR_READY,

    /** OCR 完成但需人工核对（低质量/复杂版式）。 */
    OCR_REVIEW_REQUIRED,
}

/** OCR 任务键：同一页同区域同模型才可复用结果。 */
data class OcrTaskKey(
    val documentId: String,
    val sourceFileHash: String,
    val pageIndex: Int,
    val regionHash: String? = null,
    val ocrModelVersion: String,
    val extractorVersion: String,
) {
    init {
        require(documentId.isNotBlank()) { "documentId must not be blank" }
        require(pageIndex >= 0) { "pageIndex must be >= 0" }
        require(ocrModelVersion.isNotBlank()) { "ocrModelVersion must not be blank" }
    }
}

/** 单页（或页内 tile）识别请求。tile 坐标只描述渲染范围，不改变任务键语义。 */
data class OcrPageRequest(
    val taskKey: OcrTaskKey,
    val rotationDegrees: Int = 0,
    val tileIndex: Int = 0,
    val tileCount: Int = 1,
    val maxDimensionPx: Int = 1600,
) {
    init {
        require(rotationDegrees in SUPPORTED_ROTATIONS) { "unsupported rotation: $rotationDegrees" }
        require(tileCount >= 1) { "tileCount must be >= 1" }
        require(tileIndex in 0 until tileCount) { "tileIndex out of range" }
        require(maxDimensionPx in MIN_DIMENSION..MAX_DIMENSION) { "maxDimensionPx out of range" }
    }

    companion object {
        val SUPPORTED_ROTATIONS = setOf(0, 90, 180, 270)
        const val MIN_DIMENSION = 320
        const val MAX_DIMENSION = 4096
    }
}

/** 归一化文字框（0..1，左上原点）；[readingOrder] 由版面排序给出，不是遍历顺序。 */
data class OcrTextBox(
    val text: String,
    val rect: NormalizedRect,
    val readingOrder: Int,
) {
    init {
        require(text.isNotBlank()) { "text box text must not be blank" }
        require(readingOrder >= 0) { "readingOrder must be >= 0" }
    }
}

data class OcrPageResult(
    val taskKey: OcrTaskKey,
    val boxes: List<OcrTextBox>,
    val quality: ExtractionQuality,
    /** 引擎未提供置信度时必须为 null。 */
    val providerConfidence: Double? = null,
    /** 质量启发式算法版本；与引擎模型版本分开记录。 */
    val qualityAlgorithmVersion: String,
) {
    init {
        require(qualityAlgorithmVersion.isNotBlank()) { "qualityAlgorithmVersion must not be blank" }
        require(providerConfidence == null || providerConfidence in 0.0..1.0) {
            "providerConfidence must be within 0..1"
        }
    }

    val text: String get() = boxes.sortedBy { it.readingOrder }.joinToString("\n") { it.text }
}

sealed interface OcrOutcome {
    data class Recognized(val result: OcrPageResult) : OcrOutcome

    /** 引擎缺失/未配置/不支持该语言；调用方必须展示原因，不能当成功。 */
    data class Unavailable(val code: String, val message: String) : OcrOutcome
}

interface OcrEngine {
    val engineId: String
    val modelVersion: String
    val languages: Set<String>

    fun isAvailable(): Boolean

    suspend fun recognize(request: OcrPageRequest, pageImage: ByteArray): OcrOutcome
}
