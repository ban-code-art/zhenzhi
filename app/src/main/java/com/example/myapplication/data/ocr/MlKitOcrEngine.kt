package com.example.myapplication.data.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import com.example.myapplication.domain.ocr.OcrEngine
import com.example.myapplication.domain.ocr.OcrOutcome
import com.example.myapplication.domain.ocr.OcrPageRequest
import com.example.myapplication.domain.ocr.OcrPageResult
import com.example.myapplication.domain.ocr.OcrTextBox
import com.example.myapplication.domain.reader.ExtractionQuality
import com.example.myapplication.domain.reader.NormalizedRect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.max

/**
 * 离线 OCR 引擎：ML Kit bundled Chinese（W5-03 选型，见 ADR-002）。
 *
 * 事实边界：
 *  - 模型随 APK 打包，不依赖 Play 服务在线下载；
 *  - ML Kit 的 Text API **不返回逐行置信度**，因此 [OcrPageResult.providerConfidence] 恒为 null；
 *  - 质量只能用可解释的启发式（文本长度/框数量），并记录算法版本，不伪造百分比；
 *  - 空结果记 NO_TEXT；任何非空识别结果都需逐页人工复核，不自动出题。
 */
class MlKitOcrEngine(
    private val recognizer: com.google.mlkit.vision.text.TextRecognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
) : OcrEngine {
    override val engineId: String = ENGINE_ID
    override val modelVersion: String = MODEL_VERSION
    override val languages: Set<String> = setOf("zh", "en")

    override fun isAvailable(): Boolean = true

    override suspend fun recognize(request: OcrPageRequest, pageImage: ByteArray): OcrOutcome {
        currentCoroutineContext().ensureActive()
        if (pageImage.isEmpty()) {
            return OcrOutcome.Unavailable("OCR_EMPTY_IMAGE", "页图为空，未执行识别")
        }
        val decoded = runCatching { BitmapFactory.decodeByteArray(pageImage, 0, pageImage.size) }
            .getOrNull()
            ?: return OcrOutcome.Unavailable("OCR_IMAGE_DECODE_FAILED", "页图无法解码（不是有效图片）")
        var bitmap = decoded
        var taskOwnsBitmap = false
        val width: Int
        val height: Int
        val recognized = try {
            bitmap = rotateIfNeeded(decoded, request.rotationDegrees)
            if (bitmap !== decoded) decoded.recycle()
            width = bitmap.width
            height = bitmap.height
            if (width <= 0 || height <= 0) {
                return OcrOutcome.Unavailable("OCR_IMAGE_DECODE_FAILED", "页图尺寸非法")
            }
            currentCoroutineContext().ensureActive()
            val image = InputImage.fromBitmap(bitmap, 0)
            val task = recognizer.process(image)
            // From here native work may still use the pixels even if the coroutine is cancelled.
            // Transfer ownership before registering the listener; registration failure must not
            // let finally recycle an input whose native lifetime is no longer observable.
            taskOwnsBitmap = true
            task.awaitAndRecycle(bitmap)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return OcrOutcome.Unavailable(
                "OCR_RECOGNITION_FAILED",
                "离线识别失败：${error.javaClass.simpleName}",
            )
        } finally {
            if (!taskOwnsBitmap) bitmap.recycle()
        }
        val boxes = readingOrder(recognized.textBlocks.flatMap { it.lines }, width, height)
        val text = boxes.joinToString("\n") { it.text }
        val quality = if (boxes.isEmpty() || text.isBlank()) {
            ExtractionQuality.NO_TEXT
        } else {
            ExtractionQuality.OCR_REVIEW_REQUIRED
        }
        return OcrOutcome.Recognized(
            OcrPageResult(
                taskKey = request.taskKey,
                boxes = boxes,
                quality = quality,
                providerConfidence = null,
                qualityAlgorithmVersion = QUALITY_ALGORITHM_VERSION,
            ),
        )
    }

    private fun rotateIfNeeded(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * 几何阅读顺序：先按行聚类（top 相差不超过半行高），行内按 left 排序；
     * 归一化到 0..1，越界框裁掉。竖排/多栏复杂版式仍需人工复核，这里不假装支持。
     */
    private fun readingOrder(
        lines: List<com.google.mlkit.vision.text.Text.Line>,
        width: Int,
        height: Int,
    ): List<OcrTextBox> {
        val mapped = lines.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            if (line.text.isBlank()) return@mapNotNull null
            val rect = normalize(box, width, height) ?: return@mapNotNull null
            Triple(line.text.trim(), rect, box)
        }.sortedWith(
            compareBy<Triple<String, NormalizedRect, Rect>> { it.third.top / max(1, it.third.height()) }
                .thenBy { it.third.left },
        )
        return mapped.mapIndexed { index, item ->
            OcrTextBox(text = item.first, rect = item.second, readingOrder = index)
        }
    }

    private fun normalize(box: Rect, width: Int, height: Int): NormalizedRect? {
        val left = (box.left.toFloat() / width).coerceIn(0f, 1f)
        val top = (box.top.toFloat() / height).coerceIn(0f, 1f)
        val right = (box.right.toFloat() / width).coerceIn(0f, 1f)
        val bottom = (box.bottom.toFloat() / height).coerceIn(0f, 1f)
        if (right <= left || bottom <= top) return null
        return NormalizedRect(left = left, top = top, right = right, bottom = bottom)
    }

    private suspend fun <T> Task<T>.awaitAndRecycle(bitmap: Bitmap): T = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener(Executor { command -> command.run() }) { completed ->
            // Completion, rather than cancellation of the waiting coroutine, ends native use.
            bitmap.recycle()
            if (continuation.isActive) {
                when {
                    completed.isCanceled -> continuation.cancel(CancellationException("OCR task cancelled"))
                    completed.isSuccessful -> continuation.resume(completed.result)
                    else -> continuation.resumeWithException(
                        completed.exception ?: IllegalStateException("OCR task failed without exception"),
                    )
                }
            }
        }
    }

    companion object {
        const val ENGINE_ID = "mlkit-bundled-chinese"
        const val MODEL_VERSION = "mlkit-chinese-16.0.1"
        const val QUALITY_ALGORITHM_VERSION = "mlkit-lines-v1"

    }
}
