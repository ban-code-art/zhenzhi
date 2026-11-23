package com.example.myapplication.ui.knowledge.reader

import android.graphics.RectF
import android.graphics.Path
import com.example.myapplication.domain.reader.NormalizedRect
import com.example.myapplication.domain.reader.ReaderLocatorValidator
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDVectorFont
import com.tom_roush.pdfbox.pdmodel.font.PDTrueTypeFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1CFont
import com.tom_roush.pdfbox.pdmodel.font.PDType3Font
import com.tom_roush.pdfbox.pdmodel.font.PDCIDFontType2
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.util.Vector
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** API24 path using the already bundled PDFBox. No retained document or unbounded page cache. */
class AndroidPdfReaderSurface : PdfReaderSurface {
    private class Unavailable(val reason: String) : RuntimeException(reason)

    /** Type3 is executable content. Only bounded, uncolored, filled vector paths are proven. */
    private fun type3Path(font: PDType3Font, code: Int, checkActive: () -> Unit): Path {
        val charProc = font.getCharProc(code) ?: throw Unavailable("UNSUPPORTED_FONT_GEOMETRY")
        val bytes = charProc.contents.use { input ->
            val bounded = ByteArray(64 * 1024 + 1)
            var size = 0
            while (size < bounded.size) {
                checkActive()
                val count = input.read(bounded, size, bounded.size - size)
                if (count < 0) break
                if (count == 0) throw Unavailable("UNSUPPORTED_FONT_GEOMETRY")
                size += count
            }
            if (size > 64 * 1024) throw Unavailable("PDF_FONT_PATH_LIMIT")
            bounded.copyOf(size)
        }
        val parser = PDFStreamParser(bytes)
        val operands = mutableListOf<COSNumber>()
        val current = Path()
        val painted = Path()
        var metrics = false
        var declaredBounds: RectF? = null
        var hasCurrentPoint = false
        var tokens = 0
        fun numbers(count: Int): List<Float> {
            if (operands.size != count) throw Unavailable("UNSUPPORTED_FONT_GEOMETRY")
            return operands.map { it.floatValue() }.also {
                if (it.any { value -> !value.isFinite() || abs(value) > 1_000_000f })
                    throw Unavailable("UNSUPPORTED_FONT_GEOMETRY")
            }
        }
        while (true) {
            checkActive()
            val token = parser.parseNextToken() ?: break
            if (++tokens > 8192) throw Unavailable("PDF_FONT_PATH_LIMIT")
            when (token) {
                is COSNumber -> {
                    if (operands.size >= 6) throw Unavailable("UNSUPPORTED_FONT_GEOMETRY")
                    operands += token
                }
                is Operator -> {
                    if (!metrics && token.name != "d1") throw Unavailable("UNSUPPORTED_FONT_GEOMETRY")
                    when (token.name) {
                        "d1" -> {
                            val values = numbers(6)
                            if (metrics || values[2] > values[4] || values[3] > values[5])
                                throw Unavailable("UNSUPPORTED_FONT_GEOMETRY")
                            declaredBounds = RectF(values[2], values[3], values[4], values[5])
                            metrics = true
                        }
                        "m" -> numbers(2).let { current.moveTo(it[0], it[1]); hasCurrentPoint = true }
                        "l" -> numbers(2).let {
                            if (!hasCurrentPoint) throw Unavailable("UNSUPPORTED_FONT_GEOMETRY")
                            current.lineTo(it[0], it[1])
                        }
                        "c" -> numbers(6).let {
                            if (!hasCurrentPoint) throw Unavailable("UNSUPPORTED_FONT_GEOMETRY")
                            current.cubicTo(it[0], it[1], it[2], it[3], it[4], it[5])
                        }
                        "re" -> numbers(4).let {
                            current.moveTo(it[0], it[1]); current.lineTo(it[0] + it[2], it[1])
                            current.lineTo(it[0] + it[2], it[1] + it[3]); current.lineTo(it[0], it[1] + it[3]); current.close()
                            hasCurrentPoint = true
                        }
                        "h" -> {
                            numbers(0)
                            if (!hasCurrentPoint) throw Unavailable("UNSUPPORTED_FONT_GEOMETRY")
                            current.close()
                        }
                        "f", "F", "f*" -> {
                            numbers(0)
                            current.fillType = if (token.name == "f*") Path.FillType.EVEN_ODD else Path.FillType.WINDING
                            // Resolve fill rules and cancelling contours before claiming an outline.
                            if (!painted.op(current, Path.Op.UNION)) throw Unavailable("UNSUPPORTED_FONT_GEOMETRY")
                            current.reset()
                            hasCurrentPoint = false
                        }
                        // No clipping, images, forms, text, transforms, strokes, colors or alpha may hide part of a glyph.
                        else -> throw Unavailable("UNSUPPORTED_FONT_GEOMETRY")
                    }
                    operands.clear()
                }
                else -> throw Unavailable("UNSUPPORTED_FONT_GEOMETRY")
            }
        }
        if (!metrics || operands.isNotEmpty() || !current.isEmpty) throw Unavailable("UNSUPPORTED_FONT_GEOMETRY")
        if (!painted.isEmpty) {
            val actual = RectF().also { painted.computeBounds(it, true) }
            val declared = checkNotNull(declaredBounds)
            // A renderer may use d1's cache box. Never claim an outline extending beyond it.
            if (listOf(actual.left, actual.top, actual.right, actual.bottom).any { !it.isFinite() } ||
                actual.left < declared.left || actual.top < declared.top ||
                actual.right > declared.right || actual.bottom > declared.bottom)
                throw Unavailable("UNSUPPORTED_FONT_GEOMETRY")
        }
        return painted
    }

    override suspend fun loadPage(source: File, pageIndex: Int, rendererWidth: Int, rendererHeight: Int): PdfTextPage =
        withContext(Dispatchers.IO) {
            require(pageIndex >= 0 && rendererWidth > 0 && rendererHeight > 0)
            fun unavailable(code: String, rotation: Int = 0) = PdfTextPage(pageIndex,rendererWidth,rendererHeight,rotation,emptyList(),code)
            try {
                // Stop before TextStripper's retained TextPosition lists can grow without a bound.
                if (source.length() > 256L * 1024 * 1024) return@withContext unavailable("PDF_SOURCE_LIMIT")
                PDDocument.load(source, MemoryUsageSetting.setupMixed(16L * 1024 * 1024, 256L * 1024 * 1024)).use { document ->
                    currentCoroutineContext().ensureActive()
                    if (pageIndex >= document.numberOfPages) return@withContext unavailable("PAGE_MISSING")
                    val page = document.getPage(pageIndex)
                    val rotation = ((page.rotation % 360) + 360) % 360
                    if (rotation !in setOf(0,90,180,270)) return@withContext unavailable("UNSUPPORTED_PAGE_ROTATION")
                    val crop = page.cropBox
                    val width = crop.width
                    val height = crop.height
                    val swapped = rotation == 90 || rotation == 270
                    val displayWidth = if (swapped) height else width
                    val displayHeight = if (swapped) width else height
                    if (!width.isFinite() || !height.isFinite() || width <= 0 || height <= 0 ||
                        abs(displayWidth-rendererWidth) > 1f || abs(displayHeight-rendererHeight) > 1f ||
                        abs(page.userUnit-1f) > .0001f) return@withContext unavailable("RENDERER_GEOMETRY_MISMATCH",rotation)
                    val glyphs = mutableListOf<PdfTextGlyph>()
                    var offset = 0
                    var seenGlyphs = 0
                    var seenUnits = 0L
                    val outlines = linkedMapOf<Pair<PDFont,Int>,RectF>()
                    val coroutine = currentCoroutineContext()
                    val stripper = object : PDFTextStripper() {
                        private var pathEmpty = true
                        private var wholePageRectangle = false
                        private var pendingWholePageClip = false
                        override fun showGlyph(textRenderingMatrix: Matrix, font: PDFont, code: Int,
                            unicode: String?, displacement: Vector) {
                            coroutine.ensureActive()
                            // LegacyTextStripper can otherwise coerce unknown codes or silently skip composite glyphs.
                            if (font.toUnicode(code) == null) throw Unavailable("UNSUPPORTED_TEXT_MAPPING")
                            super.showGlyph(textRenderingMatrix,font,code,unicode,displacement)
                        }

                        override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
                            coroutine.ensureActive()
                            // PDF renderers can defer transforming path coordinates until termination.
                            // Keep one CTM for construction, the clip rule and n/paint.
                            if (!pathEmpty && operator.name in setOf("cm", "q", "Q"))
                                throw Unavailable("UNSUPPORTED_TEXT_VISIBILITY")
                            // W/W* selects a clip rule; the renderer applies the completed path at n/paint.
                            // Once proven, no later subpath may turn that rectangle into a hole or smaller clip.
                            if (pendingWholePageClip && operator.name in setOf("re", "m", "l", "c", "v", "y", "h"))
                                throw Unavailable("UNSUPPORTED_TEXT_VISIBILITY")
                            if (operator.name == "re") {
                                // Skia wraps a page in one rectangle clip. Prove its transformed corners cover
                                // the entire CropBox; any other path or smaller clip remains unsupported.
                                val matrix = graphicsState.currentTransformationMatrix
                                val values = operands.mapNotNull { (it as? COSNumber)?.floatValue() }
                                wholePageRectangle = pathEmpty && operands.size == 4 && values.size == 4 && values.all { it.isFinite() } &&
                                    matrix.shearX == 0f && matrix.shearY == 0f && run {
                                        val a = matrix.transformPoint(values[0], values[1])
                                        val b = matrix.transformPoint(values[0] + values[2], values[1] + values[3])
                                        listOf(a.x, a.y, b.x, b.y).all { it.isFinite() } &&
                                            min(a.x, b.x) <= crop.lowerLeftX && min(a.y, b.y) <= crop.lowerLeftY &&
                                            max(a.x, b.x) >= crop.upperRightX && max(a.y, b.y) >= crop.upperRightY
                                    }
                                pathEmpty = false
                            } else if (operator.name in setOf("m", "l", "c", "v", "y", "h")) {
                                pathEmpty = false; wholePageRectangle = false
                            }
                            // TextStripper cannot prove partial clipping, form BBoxes or optional-content visibility.
                            if ((operator.name in setOf("W", "W*") && !wholePageRectangle) || operator.name == "BDC" ||
                                (operator.name == "Do" && operands.firstOrNull().let {
                                    it is COSName && resources.getXObject(it) is PDFormXObject
                                })) throw Unavailable("UNSUPPORTED_TEXT_VISIBILITY")
                            if (operator.name in setOf("W", "W*")) pendingWholePageClip = true
                            super.processOperator(operator, operands)
                            if (operator.name in setOf("n", "f", "F", "f*", "S", "s", "B", "B*", "b", "b*")) {
                                pathEmpty = true; wholePageRectangle = false; pendingWholePageClip = false
                            }
                        }

                        override fun processTextPosition(position: TextPosition) {
                            coroutine.ensureActive()
                            seenGlyphs++
                            seenUnits += position.unicode?.length ?: 0
                            if (seenGlyphs > 50000 || seenUnits > 262144) throw Unavailable("PAGE_TEXT_LIMIT")
                            val state = graphicsState
                            if (state.textState.renderingMode.intValue() != 0 || state.nonStrokeAlphaConstant != 1.0 ||
                                state.softMask != null) throw Unavailable("UNSUPPORTED_TEXT_VISIBILITY")
                            if (position.font.isVertical || position.characterCodes.size != 1 ||
                                position.font.toUnicode(position.characterCodes[0]) == null)
                                throw Unavailable("UNSUPPORTED_TEXT_MAPPING")
                            super.processTextPosition(position)
                        }

                        override fun writeString(text: String, positions: MutableList<TextPosition>) {
                            coroutine.ensureActive()
                            for (position in positions) {
                                val unicode = position.unicode ?: continue
                                if (unicode.isEmpty()) continue
                                // PDFBox LegacyPDFStreamEngine has already subtracted CropBox lower-left.
                                // Direction-adjusted x/y ignore /Rotate; apply it exactly once below.
                                val matrix = position.textMatrix
                                if (abs(position.dir) > .01f || abs(matrix.shearX) > .001f || abs(matrix.shearY) > .001f ||
                                    !ReaderLocatorValidator.isWellFormedUtf16(unicode) || unicode.contains('\uFFFD')) {
                                    throw Unavailable("UNSUPPORTED_TEXT_GEOMETRY")
                                }
                                val font = position.font
                                val code = position.characterCodes.single()
                                // TextStripper may merge a separate diacritic into this Unicode string while
                                // retaining only the base character code/path. That is not a proven whole glyph.
                                if (unicode != font.toUnicode(code)) throw Unavailable("UNSUPPORTED_TEXT_MAPPING")
                                // Simple fonts use an encoded glyph name; Type3 obtains that glyph's own charproc.
                                val glyphName = when (font) {
                                    is PDType1Font -> font.encoding.getName(code)
                                    is PDType1CFont -> font.encoding.getName(code)
                                    is PDType3Font -> font.encoding.getName(code)
                                    else -> null
                                }
                                val hasGlyph = when (font) {
                                    is PDVectorFont -> font.hasGlyph(code)
                                    is PDType1Font -> font.hasGlyph(checkNotNull(glyphName))
                                    is PDType1CFont -> font.hasGlyph(checkNotNull(glyphName))
                                    is PDType3Font -> font.hasGlyph(checkNotNull(glyphName))
                                    else -> false
                                }
                                if (!hasGlyph) throw Unavailable("UNSUPPORTED_FONT_GEOMETRY")
                                val key = font to code
                                val outline = outlines[key] ?: RectF().also { bounds ->
                                    val path = when (font) {
                                        is PDVectorFont -> font.getPath(code)
                                        is PDType1Font -> font.getPath(checkNotNull(glyphName))
                                        is PDType1CFont -> font.getPath(checkNotNull(glyphName))
                                        is PDType3Font -> type3Path(font, code) { coroutine.ensureActive() }
                                        else -> throw Unavailable("UNSUPPORTED_FONT_GEOMETRY")
                                    }
                                    path.computeBounds(bounds,true)
                                    // PDVectorFont paths for TrueType use unitsPerEm; PDF font matrices use 1000 units.
                                    val ttf = when(font) {
                                        is PDTrueTypeFont -> font.trueTypeFont
                                        is PDType0Font -> (font.descendantFont as? PDCIDFontType2)?.trueTypeFont
                                        else -> null
                                    }
                                    val units = ttf?.header?.unitsPerEm ?: 1000
                                    if (units <= 0) throw Unavailable("UNSUPPORTED_FONT_GEOMETRY")
                                    val scale = 1000f/units
                                    bounds.set(bounds.left*scale,bounds.top*scale,bounds.right*scale,bounds.bottom*scale)
                                    if (outlines.size >= 1024) outlines.remove(outlines.keys.first())
                                    outlines[key] = bounds
                                }
                                var left = position.xDirAdj
                                var right = left + position.widthDirAdj
                                var top = position.yDirAdj-position.heightDir
                                var bottom = position.yDirAdj
                                if (!outline.isEmpty) {
                                    // Include the actual font outline (not legacy bbox/2), notably descenders and overhangs.
                                    for (x in listOf(outline.left,outline.right)) for (y in listOf(outline.top,outline.bottom)) {
                                        val fontPoint = font.fontMatrix.transformPoint(x,y)
                                        val point = matrix.transformPoint(fontPoint.x,fontPoint.y)
                                        left=min(left,point.x);right=max(right,point.x)
                                        top=min(top,height-point.y);bottom=max(bottom,height-point.y)
                                    }
                                } else if (!unicode.isBlank()) throw Unavailable("UNSUPPORTED_FONT_GEOMETRY")
                                val rect = PdfTextGeometry.rotate(NormalizedRect(left/width,top/height,right/width,bottom/height),rotation)
                                if (!PdfTextGeometry.valid(rect)) throw Unavailable("INVALID_GLYPH_GEOMETRY")
                                glyphs += PdfTextGlyph(unicode,offset,offset+unicode.length,rect)
                                offset += unicode.length
                            }
                        }
                    }
                    stripper.startPage = pageIndex+1
                    stripper.endPage = pageIndex+1
                    stripper.sortByPosition = true
                    stripper.getText(document)
                    currentCoroutineContext().ensureActive()
                    when {
                        glyphs.isEmpty() -> unavailable("NO_TEXT",rotation)
                        else -> PdfTextPage(pageIndex,rendererWidth,rendererHeight,rotation,Collections.unmodifiableList(glyphs.toList()))
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (unsupported: Unavailable) { unavailable(unsupported.reason)
            } catch (_: OutOfMemoryError) { unavailable("PDF_TEXT_MEMORY_LIMIT")
            } catch (_: Exception) { unavailable("PDF_TEXT_EXTRACTION_FAILED") }
        }
}
