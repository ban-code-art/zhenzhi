package com.example.myapplication.data.ocr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * PDF 页渲染（W5-03 前置）：按有界分辨率把一页渲染成 PNG，供 OCR 使用。
 *
 * - 只读打开源 PDF，不修改原文件；
 * - 按页渲染，不缓存整本位图（设计 §3 内存预算）；
 * - 页面旋转由 [PdfRenderer] 按页面自身属性输出，调用方不需要再转；
 * - 分辨率上限 [MIN_DIMENSION]..[MAX_DIMENSION]，避免超大图触发 OOM。
 */
class PdfPageRenderer {

    data class RenderedPage(
        val pageIndex: Int,
        val width: Int,
        val height: Int,
        val pngBytes: ByteArray,
    )

    /** One bounded crop of a rendered page. Bounds use normalized page coordinates. */
    data class RenderedTile(
        val pageIndex: Int,
        val tileIndex: Int,
        val tileCount: Int,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val width: Int,
        val height: Int,
        val pngBytes: ByteArray,
    )

    fun pageCount(pdf: File): Int = withRenderer(pdf) { it.pageCount }

    fun render(pdf: File, pageIndex: Int, maxDimensionPx: Int = DEFAULT_MAX_DIMENSION): RenderedPage =
        withRenderer(pdf) { renderer ->
            require(pageIndex in 0 until renderer.pageCount) {
                "pageIndex out of range: $pageIndex / ${renderer.pageCount}"
            }
            require(maxDimensionPx in MIN_DIMENSION..MAX_DIMENSION) {
                "maxDimensionPx out of range: $maxDimensionPx"
            }
            renderer.openPage(pageIndex).use { page ->
                val scale = maxDimensionPx.toFloat() / max(page.width, page.height).coerceAtLeast(1)
                val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                val bitmap = createBitmap(width, height)
                // PDF 页默认透明；先铺白底，避免 OCR 把透明当黑底。
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val bytes = ByteArrayOutputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                    output.toByteArray()
                }
                bitmap.recycle()
                RenderedPage(pageIndex = pageIndex, width = width, height = height, pngBytes = bytes)
            }
        }

    /**
     * Render large pages as overlapping crops without ever allocating a full-resolution page
     * bitmap. OCR boxes returned for a tile can be mapped to page coordinates through its bounds.
     */
    fun renderTiles(
        pdf: File,
        pageIndex: Int,
        maxPageDimensionPx: Int = DEFAULT_MAX_DIMENSION,
        tileDimensionPx: Int = DEFAULT_TILE_DIMENSION,
        overlapPx: Int = DEFAULT_TILE_OVERLAP,
    ): List<RenderedTile> = withRenderer(pdf) { renderer ->
        require(pageIndex in 0 until renderer.pageCount) {
            "pageIndex out of range: $pageIndex / ${renderer.pageCount}"
        }
        require(maxPageDimensionPx in MIN_DIMENSION..MAX_DIMENSION) {
            "maxPageDimensionPx out of range: $maxPageDimensionPx"
        }
        require(tileDimensionPx in MIN_TILE_DIMENSION..MAX_TILE_DIMENSION) {
            "tileDimensionPx out of range: $tileDimensionPx"
        }
        require(overlapPx in 0 until tileDimensionPx / 2) { "overlapPx out of range" }

        renderer.openPage(pageIndex).use { page ->
            val scale = maxPageDimensionPx.toFloat() / max(page.width, page.height).coerceAtLeast(1)
            val pageWidth = (page.width * scale).roundToInt().coerceAtLeast(1)
            val pageHeight = (page.height * scale).roundToInt().coerceAtLeast(1)
            val lefts = tileStarts(pageWidth, tileDimensionPx, overlapPx)
            val tops = tileStarts(pageHeight, tileDimensionPx, overlapPx)
            val positions = tops.flatMap { top -> lefts.map { left -> left to top } }
            positions.mapIndexed { index, (left, top) ->
                val width = minOf(tileDimensionPx, pageWidth - left)
                val height = minOf(tileDimensionPx, pageHeight - top)
                val bitmap = createBitmap(width, height)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    // Map page points directly into this crop; avoid a page-sized intermediate bitmap.
                    val matrix = Matrix().apply {
                        setValues(
                            floatArrayOf(
                                scale, 0f, -left.toFloat(),
                                0f, scale, -top.toFloat(),
                                0f, 0f, 1f,
                            ),
                        )
                    }
                    page.render(
                        bitmap,
                        Rect(0, 0, width, height),
                        matrix,
                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                    )
                    val bytes = ByteArrayOutputStream().use { output ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                        output.toByteArray()
                    }
                    RenderedTile(
                        pageIndex = pageIndex,
                        tileIndex = index,
                        tileCount = positions.size,
                        left = left.toFloat() / pageWidth,
                        top = top.toFloat() / pageHeight,
                        right = (left + width).toFloat() / pageWidth,
                        bottom = (top + height).toFloat() / pageHeight,
                        width = width,
                        height = height,
                        pngBytes = bytes,
                    )
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    private fun tileStarts(total: Int, tileSize: Int, overlap: Int): List<Int> {
        if (total <= tileSize) return listOf(0)
        val count = kotlin.math.ceil(total.toDouble() / (tileSize - overlap).coerceAtLeast(1)).toInt()
            .coerceAtLeast(2)
        val lastStart = total - tileSize
        if (lastStart <= 0) return listOf(0)
        // Spread the unavoidable overlap evenly and pin the last crop to the page edge.
        return (0 until count).map { index ->
            (lastStart.toDouble() * index / (count - 1)).roundToInt()
        }.distinct()
    }

    private fun <T> withRenderer(pdf: File, block: (PdfRenderer) -> T): T {
        require(pdf.isFile) { "PDF 不存在: ${pdf.absolutePath}" }
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer -> return block(renderer) }
        }
    }

    companion object {
        const val DEFAULT_MAX_DIMENSION = 1600
        const val MIN_DIMENSION = 320
        const val MAX_DIMENSION = 4096
        const val MIN_TILE_DIMENSION = 320
        const val MAX_TILE_DIMENSION = 2048
        const val DEFAULT_TILE_DIMENSION = 1800
        const val DEFAULT_TILE_OVERLAP = 128
    }
}
