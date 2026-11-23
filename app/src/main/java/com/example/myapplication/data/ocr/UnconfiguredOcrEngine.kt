package com.example.myapplication.data.ocr

import com.example.myapplication.domain.ocr.OcrEngine
import com.example.myapplication.domain.ocr.OcrOutcome
import com.example.myapplication.domain.ocr.OcrPageRequest

/**
 * 未配置 OCR 引擎时的默认实现（当前版本）。
 *
 * 与 `UnconfiguredExternalSearchPort` 同一原则：能力缺失就明确报缺失，
 * 不返回空字符串伪装“识别成功”，也不偷偷调用在线服务。
 */
class UnconfiguredOcrEngine : OcrEngine {
    override val engineId: String = "unconfigured"
    override val modelVersion: String = "none"
    override val languages: Set<String> = emptySet()

    override fun isAvailable(): Boolean = false

    override suspend fun recognize(request: OcrPageRequest, pageImage: ByteArray): OcrOutcome =
        OcrOutcome.Unavailable(
            code = CODE_NOT_CONFIGURED,
            message = "未配置离线 OCR 引擎：扫描页暂不生成文本，也不会自动出题",
        )

    companion object {
        const val CODE_NOT_CONFIGURED = "OCR_ENGINE_NOT_CONFIGURED"
    }
}
