package com.example.myapplication.data.ocr

import com.example.myapplication.domain.ocr.OcrEngine
import com.example.myapplication.domain.ocr.OcrOutcome
import com.example.myapplication.domain.ocr.OcrPageRequest
import com.example.myapplication.domain.ocr.OcrPageResult
import com.example.myapplication.domain.ocr.OcrTextBox
import com.example.myapplication.domain.ocr.OcrTaskKey
import com.example.myapplication.domain.reader.ExtractionQuality
import com.example.myapplication.domain.reader.NormalizedRect
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * OCR 逐页执行器（W5-03）。
 *
 * 职责边界：
 *  - 只处理 [Request.pages] 中尚未完成的页；页按有界重叠 tile 识别，合并后每页落检查点；
 *  - 任务键变化（源文件 hash/引擎/模型/extractor）→ 旧检查点作废，从头识别；
 *  - 单页失败记录后继续，不因一页坏图丢掉整本；
 *  - 引擎不可用直接返回 `Unavailable`，不伪造文本；
 *  - 取消（CancellationException）原样抛出，已完成页保留。
 *
 * 双栏/表格等几何歧义会降为人工复核；进程中断时只重跑未完成的当前页。
 */
class OcrWorker(
    private val engine: OcrEngine,
    private val store: OcrCheckpointStore,
    private val renderer: PdfPageRenderer = PdfPageRenderer(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Request(
        val documentId: String,
        val sourceFile: File,
        val extractorVersion: String,
        val pages: List<Int>,
        val maxDimensionPx: Int = DEFAULT_OCR_PAGE_DIMENSION,
        val tileDimensionPx: Int = PdfPageRenderer.DEFAULT_TILE_DIMENSION,
        val tileOverlapPx: Int = PdfPageRenderer.DEFAULT_TILE_OVERLAP,
    )

    data class PageFailure(val pageIndex: Int, val code: String, val message: String)

    data class Report(
        val documentId: String,
        val checkpoint: OcrCheckpoint,
        val processed: List<Int>,
        val skipped: List<Int>,
        val failures: List<PageFailure>,
    ) {
        val totalPages: Int get() = checkpoint.pages.size
    }

    sealed interface Outcome {
        data class Completed(val report: Report) : Outcome
        data class Unavailable(val code: String, val message: String) : Outcome
        data class Failed(val code: String, val message: String) : Outcome
    }

    suspend fun run(
        request: Request,
        onProgress: suspend (done: Int, total: Int, pageIndex: Int) -> Unit = { _, _, _ -> },
    ): Outcome {
        val requested = request.pages.distinct().sorted()
        if (requested.isEmpty()) return Outcome.Failed("OCR_NO_PAGES", "没有要识别的页")
        if (!engine.isAvailable()) {
            return Outcome.Unavailable("OCR_ENGINE_UNAVAILABLE", "OCR 引擎不可用（${engine.engineId}）")
        }
        val sourceHash = FileHashes.sha256(request.sourceFile)
        val pageCount = try {
            renderer.pageCount(request.sourceFile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return Outcome.Failed("OCR_PDF_UNREADABLE", "PDF 无法打开：${error.javaClass.simpleName}")
        }

        val existing = store.load(request.documentId)?.takeIf { checkpoint ->
            checkpoint.matches(
                documentId = request.documentId,
                sourceFileHash = sourceHash,
                engineId = engine.engineId,
                modelVersion = engine.modelVersion,
                extractorVersion = request.extractorVersion,
            )
        }
        val pages = existing?.pages.orEmpty().associateBy { it.pageIndex }.toMutableMap()
        val failures = mutableListOf<PageFailure>()
        val processed = mutableListOf<Int>()
        val skipped = mutableListOf<Int>()
        val validPages = requested.filter { it in 0 until pageCount }
        requested.filter { it !in 0 until pageCount }.forEach { page ->
            failures += PageFailure(page, "OCR_PAGE_OUT_OF_RANGE", "页号超出范围：$page / $pageCount")
        }
        var checkpoint = OcrCheckpoint(
            schemaVersion = OcrCheckpointCodec.SCHEMA_VERSION,
            documentId = request.documentId,
            sourceFileHash = sourceHash,
            engineId = engine.engineId,
            modelVersion = engine.modelVersion,
            extractorVersion = request.extractorVersion,
            updatedAt = clock(),
            pages = pages.values.sortedBy { it.pageIndex },
        )
        var done = 0
        for (pageIndex in validPages) {
            if (pages.containsKey(pageIndex)) {
                skipped += pageIndex
                done += 1
                onProgress(done, validPages.size, pageIndex)
                continue
            }
            val renderedTiles = try {
                renderer.renderTiles(
                    pdf = request.sourceFile,
                    pageIndex = pageIndex,
                    maxPageDimensionPx = request.maxDimensionPx,
                    tileDimensionPx = request.tileDimensionPx,
                    overlapPx = request.tileOverlapPx,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failures += PageFailure(pageIndex, "OCR_RENDER_FAILED", error.javaClass.simpleName)
                done += 1
                onProgress(done, validPages.size, pageIndex)
                continue
            }
            val pageTaskKey = OcrTaskKey(
                documentId = request.documentId,
                sourceFileHash = sourceHash,
                pageIndex = pageIndex,
                ocrModelVersion = engine.modelVersion,
                extractorVersion = request.extractorVersion,
            )
            val tileBoxes = mutableListOf<OcrTextBox>()
            var pageHasFailedTile = false
            renderedTiles.forEach { tile ->
                val tileTaskKey = pageTaskKey.copy(regionHash = "tile:${tile.tileIndex}/${tile.tileCount}")
                when (
                    val outcome = engine.recognize(
                        OcrPageRequest(
                            taskKey = tileTaskKey,
                            tileIndex = tile.tileIndex,
                            tileCount = tile.tileCount,
                            maxDimensionPx = maxOf(tile.width, tile.height),
                        ),
                        tile.pngBytes,
                    )
                ) {
                    is OcrOutcome.Recognized -> {
                        if (outcome.result.taskKey != tileTaskKey) {
                            failures += PageFailure(
                                pageIndex,
                                "OCR_TASK_KEY_MISMATCH",
                                "tile ${tile.tileIndex} 返回了其他任务的结果",
                            )
                            pageHasFailedTile = true
                        } else {
                            outcome.result.boxes.forEach { tileBoxes += tile.toPageBox(it) }
                        }
                    }

                    is OcrOutcome.Unavailable -> {
                        failures += PageFailure(
                            pageIndex,
                            "${outcome.code}_TILE_${tile.tileIndex}",
                            outcome.message,
                        )
                        pageHasFailedTile = true
                    }
                }
            }

            if (!pageHasFailedTile) {
                val boxes = deduplicateOverlap(tileBoxes)
                    .sortedWith(compareBy<OcrTextBox> { it.rect.top }.thenBy { it.rect.left })
                    .mapIndexed { order, box -> box.copy(readingOrder = order) }
                // Heuristic quality is not a human verification. Every non-empty OCR page
                // must be explicitly reviewed before it can become searchable content.
                val quality = if (boxes.isEmpty()) {
                    ExtractionQuality.NO_TEXT
                } else {
                    ExtractionQuality.OCR_REVIEW_REQUIRED
                }
                val pageResult = OcrPageResult(
                    taskKey = pageTaskKey,
                    boxes = boxes,
                    quality = quality,
                    providerConfidence = null,
                    qualityAlgorithmVersion = "${MlKitOcrEngine.QUALITY_ALGORITHM_VERSION}+tiles-v1",
                )
                pages[pageIndex] = OcrPageCheckpoint.from(pageIndex, pageResult)
                checkpoint = checkpoint.copy(updatedAt = clock(), pages = pages.values.sortedBy { it.pageIndex })
                store.save(checkpoint)
                processed += pageIndex
            }
            done += 1
            onProgress(done, validPages.size, pageIndex)
        }
        return Outcome.Completed(
            Report(
                documentId = request.documentId,
                checkpoint = checkpoint,
                processed = processed,
                skipped = skipped,
                failures = failures,
            ),
        )
    }

    private fun PdfPageRenderer.RenderedTile.toPageBox(box: OcrTextBox): OcrTextBox = box.copy(
        rect = NormalizedRect(
            left = (left + box.rect.left * (right - left)).coerceIn(0f, 1f),
            top = (top + box.rect.top * (bottom - top)).coerceIn(0f, 1f),
            right = (left + box.rect.right * (right - left)).coerceIn(0f, 1f),
            bottom = (top + box.rect.bottom * (bottom - top)).coerceIn(0f, 1f),
        ),
    )

    /** Remove same-line duplicates created by the overlap between adjacent crops. */
    private fun deduplicateOverlap(boxes: List<OcrTextBox>): List<OcrTextBox> {
        val result = mutableListOf<OcrTextBox>()
        boxes.sortedWith(compareBy<OcrTextBox> { it.rect.top }.thenBy { it.rect.left }).forEach { candidate ->
            val normalizedText = candidate.text.trim().replace(WHITESPACE, " ")
            val duplicateIndex = result.indexOfFirst { existing ->
                existing.text.trim().replace(WHITESPACE, " ") == normalizedText &&
                    intersectionOverUnion(existing.rect, candidate.rect) >= MIN_DUPLICATE_IOU
            }
            if (duplicateIndex < 0) {
                result += candidate
            } else {
                val existing = result[duplicateIndex]
                if (candidate.rect.area() > existing.rect.area()) result[duplicateIndex] = candidate
            }
        }
        return result
    }

    private fun intersectionOverUnion(a: NormalizedRect, b: NormalizedRect): Float {
        val width = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f)
        val height = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
        val intersection = width * height
        val union = a.area() + b.area() - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun NormalizedRect.area(): Float = width.coerceAtLeast(0f) * height.coerceAtLeast(0f)

    private companion object {
        const val DEFAULT_OCR_PAGE_DIMENSION = 3200
        val WHITESPACE = Regex("\\s+")
        const val MIN_DUPLICATE_IOU = 0.55f
    }
}
