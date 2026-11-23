package com.example.myapplication.ui.knowledge.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.myapplication.domain.reader.*
import kotlin.math.roundToInt

internal data class PdfGlyphAddress(val page:Int,val start:Int,val end:Int)

/** A fit transform shared by pixels, glyph hit testing, persisted rectangles and handles. */
internal data class PdfPagePlacement(val page:PdfTextPage,val left:Float,val top:Float,val width:Float,val height:Float) {
    fun hit(point:Offset):PdfGlyphAddress? = page.glyphAt((point.x-left)/width,(point.y-top)/height)
        ?.let { PdfGlyphAddress(page.pageIndex,it.start,it.end) }
}
internal fun pdfPagePlacements(pages:List<PdfTextPage>,width:Float,height:Float):List<PdfPagePlacement> {
    if(pages.isEmpty() || width<=0 || height<=0 || pages.any { it.width<=0 || it.height<=0 })return emptyList()
    val ratios=pages.map { it.height.toFloat()/it.width }
    val fittedWidth=minOf(width,height/ratios.sum())
    var top=(height-fittedWidth*ratios.sum())/2f
    return pages.mapIndexed { index,page -> PdfPagePlacement(page,(width-fittedWidth)/2f,top,fittedWidth,fittedWidth*ratios[index])
        .also { top+=it.height } }
}
internal fun pdfRangesBetween(pages:List<PdfTextPage>,a:PdfGlyphAddress,b:PdfGlyphAddress):List<PdfPageTextRange> {
    val first=if(a.page<b.page || a.page==b.page&&a.start<=b.start)a else b
    val last=if(first===a)b else a
    return pages.filter { it.pageIndex in first.page..last.page }.map {
        PdfPageTextRange(it,if(it.pageIndex==first.page)first.start else 0,if(it.pageIndex==last.page)last.end else it.text.length)
    }.takeIf { it.isNotEmpty() && it.size<=2 && selectAcrossPdfPages(it)!=null }.orEmpty()
}

internal fun pdfSelectionEndpoints(pages:List<PdfTextPage>,selection:ReaderSelectionEvent?):Pair<PdfGlyphAddress,PdfGlyphAddress>? {
    val locators=selection?.fragments?.mapNotNull { it.locator as? ReaderLocator.Fixed }.orEmpty()
    if(locators.isEmpty())return null
    fun address(locator:ReaderLocator.Fixed,end:Boolean):PdfGlyphAddress? {
        val page=pages.singleOrNull { it.pageIndex==locator.pageIndex } ?: return null
        val rect=if(end)locator.rects.lastOrNull() else locator.rects.firstOrNull()
        val glyph=page.glyphs.firstOrNull { it.rect==rect } ?: return null
        return PdfGlyphAddress(page.pageIndex,glyph.start,glyph.end)
    }
    return (address(locators.first(),false) ?: return null) to (address(locators.last(),true) ?: return null)
}

@Composable
internal fun PdfTextSelectionLayer(bitmap:Bitmap,adjacent:Bitmap?,pages:List<PdfTextPage>,selection:ReaderSelectionEvent?,
    decorations:List<ReaderDecoration>,zoom:PdfZoomState,enabled:Boolean,onSelect:(List<PdfPageTextRange>,String?)->Unit,
    modifier:Modifier=Modifier,ownerKey:Any?=pages) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val displayed=if(adjacent!=null)pages.take(2) else pages.take(1)
    val placements=pdfPagePlacements(displayed,size.width.toFloat(),size.height.toFloat())
    LaunchedEffect(zoom,size,displayed) {
        zoom.updateGeometry(size,Size(placements.firstOrNull()?.width ?: 0f,
            placements.sumOf { it.height.toDouble() }.toFloat()))
    }
    var anchor by remember(ownerKey,pages) { mutableStateOf<PdfGlyphAddress?>(null) }
    var endpoint by remember(ownerKey,pages) { mutableStateOf<PdfGlyphAddress?>(null) }
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentSelection by rememberUpdatedState(selection)
    val currentPlacements by rememberUpdatedState(placements)
    val currentPages by rememberUpdatedState(displayed)
    val handleHalf=with(LocalDensity.current){12.dp.toPx()}
    fun hit(point:Offset)=placements.firstNotNullOfOrNull { it.hit(point) }
    fun publish(a:PdfGlyphAddress,b:PdfGlyphAddress){
        val ranges=pdfRangesBetween(displayed,a,b)
        if(ranges.isNotEmpty())currentOnSelect(ranges,currentSelection?.selectionId)
    }
    val transform=rememberTransformableState { scale,pan,_ -> zoom.transform(scale,pan) }
    Box(modifier.clipToBounds().onSizeChanged { size=it }) {
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX=zoom.scale;scaleY=zoom.scale;translationX=zoom.offset.x;translationY=zoom.offset.y }
            .transformable(transform,canPan={zoom.scale>1f})) {
            Canvas(Modifier.fillMaxSize().testTag("pdf-text-selection-layer")
                .pointerInput(ownerKey,placements,enabled) { if(enabled)detectDragGesturesAfterLongPress(
                    onDragStart={ point -> hit(point)?.let { anchor=it;endpoint=it;publish(it,it) } },
                    onDrag={ change,_ -> hit(change.position)?.let { end -> anchor?.let { start -> endpoint=end;publish(start,end) } };change.consume() }) }) {
                placements.forEachIndexed { index,placement ->
                    val frame=if(index==0)bitmap else adjacent
                    if(frame!=null&&!frame.isRecycled)drawImage(frame.asImageBitmap(),dstOffset=IntOffset(placement.left.roundToInt(),placement.top.roundToInt()),
                        dstSize=IntSize(placement.width.roundToInt().coerceAtLeast(1),placement.height.roundToInt().coerceAtLeast(1)))
                    fun paint(rect:NormalizedRect,color:Color){drawRect(color,Offset(placement.left+rect.left*placement.width,placement.top+rect.top*placement.height),
                        Size(rect.width*placement.width,rect.height*placement.height))}
                    decorations.forEach { item -> item.targets.filterIsInstance<ReaderLocator.Fixed>().filter { it.pageIndex==placement.page.pageIndex }.forEach { target ->
                        val color=runCatching { Color(android.graphics.Color.parseColor(item.colorHex)).copy(alpha=.35f) }.getOrDefault(Color.Yellow.copy(alpha=.35f))
                        target.rects.forEach { paint(it,color) }
                    } }
                    selection?.fragments?.mapNotNull { it.locator as? ReaderLocator.Fixed }?.filter { it.pageIndex==placement.page.pageIndex }
                        ?.forEach { target -> target.rects.forEach { paint(it,Color(0x663377FF)) } }
                }
            }
            if(enabled&&selection!=null){
                val locators=selection.fragments.mapNotNull { it.locator as? ReaderLocator.Fixed }
                listOf(false,true).forEach { end ->
                    val locator=if(end)locators.lastOrNull() else locators.firstOrNull()
                    val placement=placements.singleOrNull { it.page.pageIndex==locator?.pageIndex }
                    val rect=if(end)locator?.rects?.lastOrNull() else locator?.rects?.firstOrNull()
                    if(placement!=null&&rect!=null){
                        val point=Offset(placement.left+(if(end)rect.right else rect.left)*placement.width,placement.top+rect.bottom*placement.height)
                        val currentPoint by rememberUpdatedState(point)
                        Box(Modifier.offset { IntOffset((point.x-handleHalf).roundToInt(),point.y.roundToInt()) }.size(24.dp)
                            .testTag(if(end)"pdf-selection-end-handle" else "pdf-selection-start-handle")
                            .background(Color(0xFF3377FF),androidx.compose.foundation.shape.CircleShape)
                            .pointerInput(ownerKey,placements,end) {
                              var dragged=Offset.Zero
                              var firstDrag=false
                              detectDragGestures(onDragStart={ local ->
                                dragged=currentPoint+Offset(local.x-handleHalf,local.y)
                                // Compose reports the slop-trigger position here, then repeats its
                                // over-slop delta in the first onDrag. That position already owns it.
                                firstDrag=true
                                pdfSelectionEndpoints(currentPages,currentSelection)?.let { (start,finish) -> anchor=start;endpoint=finish }
                              },onDrag={change,amount ->
                                if(firstDrag)firstDrag=false else dragged+=amount
                                currentPlacements.firstNotNullOfOrNull { it.hit(dragged) }?.let { glyph ->
                                    val other=if(end)anchor else endpoint
                                    if(other!=null){if(end)endpoint=glyph else anchor=glyph;publish(if(end)other else glyph,if(end)glyph else other)}
                                };change.consume()
                            }) })
                    }
                }
            }
        }
    }
}
