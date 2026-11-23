package com.example.myapplication.ui.knowledge.reader

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.example.myapplication.data.reader.ink.InkStroke
import com.example.myapplication.data.reader.ink.InkTool
import com.example.myapplication.data.reader.notes.ReaderAnnotationView
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import com.example.myapplication.domain.reader.ReaderTurnDirection

internal data class CourseViewportBoundary(val atBoundary: Boolean, val viewportJson: String?)

internal class ReaderJsBridge {
    var progressCallback: (Float, String) -> Unit = { _, _ -> }
    var semanticViewportCallback: (String) -> Unit = {}
    var positionInvalidatedCallback: (String, String) -> Unit = { _, _ -> }
    /** Current document dimensions in CSS pixels, plus the CSS-to-device scale. */
    var scrollMetricsCallback: (String) -> Unit = {}
    @JavascriptInterface
    fun onPositionInvalidated(section: String, epoch: String) { positionInvalidatedCallback(section, epoch) }
    @JavascriptInterface
    fun onScrollMetrics(raw: String) { scrollMetricsCallback(raw) }
    @JavascriptInterface
    fun onSemanticViewport(raw: String) { semanticViewportCallback(raw) }

    var selectionCallback: (String?) -> Unit = { _ -> }
    var viewportTapCallback: (Double, Double, Double, Double, Double, Double, Boolean, String, String) -> Unit = { _, _, _, _, _, _, _, _, _ -> }

    @JavascriptInterface
    fun onViewportTap(x: Double, y: Double, width: Double, height: Double, duration: Double, movement: Double, blocked: Boolean, section: String, epoch: String) {
        viewportTapCallback(x, y, width, height, duration, movement, blocked, section, epoch)
    }

    @JavascriptInterface
    fun onProgress(progress: Double, text: String) {
        progressCallback(progress.toFloat(), text)
    }

    @JavascriptInterface
    fun onSelection(raw: String?) {
        selectionCallback(raw)
    }
}

@SuppressLint("JavascriptInterface")
internal fun WebView.installReaderBridge(bridge: ReaderJsBridge) {
    addJavascriptInterface(bridge, "ReaderBridge")
}

internal class ReaderWebController {
    var inputEpoch: String = ""
        private set
    var webView: WebView? = null
        set(value) {
            if (field !== value) {
                field = value
                invalidatePage()
            }
        }
    var settings: ReaderSettings = ReaderSettings()
    var appearanceColors: ReaderAppearanceColors? = null
    var sectionId: String? = null
    var documentId: String? = null
    var contentRevision: String? = null
    private var installedSection: String? = null

    fun invalidatePage() {
        installedSection = null
        inputEpoch = java.util.UUID.randomUUID().toString()
    }
    var annotations: List<ReaderAnnotationView> = emptyList()

    /** Fresh geometry and semantic position from the same installed page, never a cached progress guess. */
    suspend fun courseBoundary(direction: ReaderTurnDirection, expectedDocument: String?, expectedRevision: String?, expectedSection: String?): CourseViewportBoundary? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            val view = webView ?: return@withContext null
            if (documentId != expectedDocument || contentRevision != expectedRevision || sectionId != expectedSection ||
                installedSection != expectedSection || view.width <= 0 || view.height <= 0) return@withContext null
            val epoch = inputEpoch
            val next = direction == ReaderTurnDirection.NEXT
            suspend fun readDom(): String? = kotlinx.coroutines.withTimeoutOrNull(1500) {
                kotlinx.coroutines.suspendCancellableCoroutine<String?> { continuation ->
                    view.evaluateJavascript("""
                        (function(){
                          if(document.readyState!=='complete'||!window.__readerSemantic||window.__readerSemantic.isRestoring||
                            window.__readerSemantic.epoch!==window.__readerInputEpoch||window.__readerSemantic.sectionId!==window.__readerSectionId||
                            (document.fonts&&document.fonts.status==='loading'))return null;
                          var root=document.scrollingElement||document.documentElement;
                          var y=root.scrollTop,max=Math.max(0,root.scrollHeight-root.clientHeight);
                          if(!Number.isFinite(y)||!Number.isFinite(max)||root.clientHeight<=0)return null;
                          return {epoch:window.__readerInputEpoch,section:window.__readerSectionId,
                            y:y,max:max,boundary:$next?y>=max-2:y<=2,viewport:window.__readerSemantic.capture()};
                        })()
                    """.trimIndent()) { result -> if (continuation.isActive) continuation.resumeWith(Result.success(result)) }
                }
            }
            val first = readDom()?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return@withContext null
            kotlinx.coroutines.delay(75)
            val raw = readDom() ?: return@withContext null
            if (view !== webView || inputEpoch != epoch || documentId != expectedDocument ||
                contentRevision != expectedRevision || sectionId != expectedSection) return@withContext null
            val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return@withContext null
            val y = obj["y"]?.jsonPrimitive?.doubleOrNull ?: return@withContext null
            val max = obj["max"]?.jsonPrimitive?.doubleOrNull ?: return@withContext null
            val oldY = first["y"]?.jsonPrimitive?.doubleOrNull ?: return@withContext null
            val oldMax = first["max"]?.jsonPrimitive?.doubleOrNull ?: return@withContext null
            if (kotlin.math.abs(y - oldY) > 1 || kotlin.math.abs(max - oldMax) > 1) return@withContext null
            if (obj["epoch"]?.jsonPrimitive?.contentOrNull != epoch || obj["section"]?.jsonPrimitive?.contentOrNull != expectedSection)
                return@withContext null
            val boundary = obj["boundary"]?.jsonPrimitive?.booleanOrNull ?: return@withContext null
            CourseViewportBoundary(boundary, obj["viewport"]?.takeIf { it.toString() != "null" }?.toString())
        }

    /** Read the live DOM on every write, including the interval before queued bridge events arrive. */
    suspend fun ownsBookmarkPosition(input: BookmarkPositionInput): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            val view = webView ?: return@withContext false
            if (input.token.documentId != documentId || input.token.contentRevision != contentRevision) return@withContext false
            val epoch = inputEpoch
            val raw = kotlinx.coroutines.withTimeoutOrNull(1500) {
                kotlinx.coroutines.suspendCancellableCoroutine<String?> { continuation ->
                    view.evaluateJavascript("""
                        (function(){
                          if(!window.__readerSemantic || window.__readerSemantic.isRestoring) return null;
                          var nativeSelection=window.getSelection();
                          return {epoch:window.__readerInputEpoch,sectionId:window.__readerSectionId,
                            hasNativeSelection:!!(nativeSelection&&!nativeSelection.isCollapsed&&nativeSelection.rangeCount),
                            selection:window.__readerAnnotationSelection&&window.__readerAnnotationSelection(),
                            viewport:window.__readerSemantic.capture()};
                        })()
                    """.trimIndent()) { value -> if (continuation.isActive) continuation.resumeWith(Result.success(value)) }
                }
            } ?: return@withContext false
            if (view !== webView || epoch != inputEpoch) return@withContext false
            legacyBookmarkDomOwns(input, raw, epoch)
        }

    fun next() {
        webView?.evaluateJavascript("window.__readerNext && window.__readerNext()", null)
    }

    fun prev() {
        webView?.evaluateJavascript("window.__readerPrev && window.__readerPrev()", null)
    }

    fun seek(progress: Float) {
        webView?.evaluateJavascript("window.__readerSeek && window.__readerSeek(${progress.coerceIn(0f, 1f)})", null)
    }

    fun locateTarget(locator: com.example.myapplication.domain.reader.ReaderLocator, viewportOffset: Float? = null) {
        val offset = viewportOffset?.takeIf { it.isFinite() }?.toString() ?: "null"
        webView?.evaluateJavascript("window.__readerLocateTarget && window.__readerLocateTarget(${readerAnnotationLocatorPayload(locator)},$offset)", null)
    }

    fun clearSelection(version: Long) {
        webView?.evaluateJavascript(
            "window.__readerSelectionVersion='$version';window.__readerClearSelection && window.__readerClearSelection()",
            null,
        )
    }

    fun decorate() {
        val targetSection = sectionId
        if (targetSection.isNullOrBlank()) return
        val payload = readerAnnotationPayload(annotations, targetSection, documentId, contentRevision)
        webView?.evaluateJavascript(
            "window.__readerDecorate && window.__readerDecorate(${payload.toString()})",
            null,
        )
    }

    fun applySettings() {
        if (webView == null) return
        val preservePosition = installedSection != null && installedSection == sectionId
        if (installedSection != sectionId) {
            inputEpoch = java.util.UUID.randomUUID().toString()
            installedSection = sectionId
        }
        val sectionLiteral = JsonPrimitive(sectionId.orEmpty()).toString()
        val epochLiteral = JsonPrimitive(inputEpoch).toString()
        webView?.evaluateJavascript(ReaderSemanticViewportScript.install(sectionId.orEmpty(), inputEpoch), null)
        if (preservePosition) webView?.evaluateJavascript(ReaderSemanticViewportScript.beginLayout(), null)
        val cssLiteral = readerSettingsStyleLiteral(settings, appearanceColors)
        val textColorLiteral = JsonPrimitive(appearanceColors?.foregroundHex ?: settings.fontColor.resolve(settings.theme)).toString()
        val paperColorLiteral = JsonPrimitive(readerPaperColor(settings, appearanceColors)).toString()
        val fontScale = settings.fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f
        val lineHeight = settings.lineHeight.takeIf { it.isFinite() && it > 0f } ?: 1.75f
        webView?.evaluateJavascript(
            """
            (function(){
              function install(){
                var head=document.head||document.getElementsByTagName('head')[0];
                if(!head){setTimeout(install,50);return;}
                var style=document.getElementById('reader-style');
                if(!style){style=document.createElementNS('http://www.w3.org/1999/xhtml','style');style.id='reader-style';head.appendChild(style);}
                // Body inheritance cannot override an EPUB paragraph's fixed px typography.
                // Measure the author cascade before any reader overrides, then scale all
                // computed sizes together; relative headings/inline spans keep their ratios.
                var originals=window.__readerTypographyOriginals;
                if(!originals){originals=new WeakMap();window.__readerTypographyOriginals=originals;}
                var textElements=Array.from(document.querySelectorAll('html,body,body *')).filter(function(element){
                  return !element.closest('svg,math,script,style,noscript,[data-reader-annotation-layer]');
                });
                style.textContent='';
                textElements.forEach(function(element){
                  var saved=originals.get(element);
                  if(!saved){
                    saved={font:element.style.getPropertyValue('font-size'),fontPriority:element.style.getPropertyPriority('font-size'),
                      line:element.style.getPropertyValue('line-height'),linePriority:element.style.getPropertyPriority('line-height')};
                    originals.set(element,saved);
                  }
                  if(saved.font)element.style.setProperty('font-size',saved.font,saved.fontPriority);
                  else element.style.removeProperty('font-size');
                  if(saved.line)element.style.setProperty('line-height',saved.line,saved.linePriority);
                  else element.style.removeProperty('line-height');
                });
                // Read every baseline before mutating any parent, preventing em/% compounding.
                var sizes=textElements.map(function(element){return parseFloat(getComputedStyle(element).fontSize);});
                style.textContent=$cssLiteral;
                textElements.forEach(function(element,index){
                  var base=sizes[index];
                  if(Number.isFinite(base)&&base>0)element.style.setProperty('font-size',(base*$fontScale)+'px','important');
                  element.style.setProperty('line-height',String($lineHeight),'important');
                  // Imported pages can paint an opaque paper layer on every paragraph/container.
                  // An author inline !important also beats html/body stylesheet overrides.
                  // Keep actual illustrations, highlighted text and annotation overlays intact.
                  if(!element.closest('mark,img,picture,video,canvas,figure')){
                    element.style.setProperty('background-color',
                      element===document.documentElement||element===document.body?$paperColorLiteral:'transparent','important');
                    element.style.setProperty('background-image','none','important');
                  }
                });
                // Inline author !important declarations otherwise win over the reader stylesheet.
                document.querySelectorAll('body,body *').forEach(function(element){
                  if(element.closest('svg')) return;
                  element.style.setProperty('color',$textColorLiteral,'important');
                  element.style.setProperty('-webkit-text-fill-color',$textColorLiteral,'important');
                });
                window.__readerSectionId=$sectionLiteral;
                window.__readerInputEpoch=$epochLiteral;
                var blocks=document.body.querySelectorAll('h1,h2,h3,p,li,blockquote,pre,td,th');
                for(var i=0;i<blocks.length;i++){
                  if(!blocks[i].getAttribute('data-reader-block')) blocks[i].setAttribute('data-reader-block','block-'+i);
                }
                function textNodes(){
                  var nodes=[], walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,{
                    acceptNode:function(node){
                      var p=node.parentElement;
                      if(!p) return NodeFilter.FILTER_REJECT;
                      var tag=p.tagName.toLowerCase();
                      if(tag==='script'||tag==='style'||tag==='noscript') return NodeFilter.FILTER_REJECT;
                      return NodeFilter.FILTER_ACCEPT;
                    }
                  });
                  var current;
                  while(current=walker.nextNode()) nodes.push(current);
                  return nodes;
                }
                function fullText(){return textNodes().map(function(n){return n.nodeValue||'';}).join('');}
                function offsetFor(node,offset){
                  if(node.nodeType!==3){
                    var r=document.createRange();r.setStart(node,offset);r.collapse(true);
                    var list=textNodes(),sum=0;
                    for(var k=0;k<list.length;k++){
                      var probe=document.createRange();probe.selectNodeContents(list[k]);
                      if(probe.compareBoundaryPoints(Range.END_TO_START,r)<=0)sum+=(list[k].nodeValue||'').length;
                      else break;
                    }
                    return sum;
                  }
                  var nodes=textNodes(), total=0;
                  for(var i=0;i<nodes.length;i++){
                    if(nodes[i]===node) return total+offset;
                    total+=(nodes[i].nodeValue||'').length;
                  }
                  return total;
                }
                function blockFor(node){
                  var element=node.nodeType===1?node:node.parentElement;
                  var block=element&&element.closest('[data-reader-block]');
                  return block?block.getAttribute('data-reader-block'):'';
                }
                if(!window.__readerSelectionInstalled){
                  window.__readerSelectionInstalled=true;
                  document.addEventListener('selectionchange',function(){
                    var selection=window.getSelection();
                    var payload;
                    if(!selection||selection.isCollapsed||!selection.rangeCount) payload={cleared:true};
                    else payload=(window.__readerAnnotationSelection&&window.__readerAnnotationSelection())||{unavailable:true};
                    payload.sectionId=window.__readerSectionId;
                    payload.epoch=window.__readerInputEpoch||'';
                    payload.selectionVersion=window.__readerSelectionVersion||'0';
                    try{ReaderBridge.onSelection(JSON.stringify(payload));}catch(e){}
                  });
                }
                window.__readerClearSelection=function(){
                  try{var s=window.getSelection();if(s)s.removeAllRanges();}catch(e){}
                };
                $readerAnnotationDecorationScript
                if(!window.__readerNext){
                  window.__readerNext=function(){window.scrollBy({top:window.innerHeight*0.86,behavior:'smooth'});};
                  window.__readerPrev=function(){window.scrollBy({top:-window.innerHeight*0.86,behavior:'smooth'});};
                  window.__readerSeek=function(p){var m=Math.max(1,document.documentElement.scrollHeight-window.innerHeight);window.scrollTo(0,m*Math.max(0,Math.min(1,p)));};
                  window.__readerReport=function(){
                    if(window.__readerSemantic&&window.__readerSemantic.isRestoring)return;
                    window.__readerScrollPending=false;
                    var point=window.__readerSemantic&&window.__readerSemantic.report();
                    if(point)try{ReaderBridge.onProgress(point.progress,point.quote.substring(0,320));}catch(e){}
                  };
                  var scrollContainers=[];
                  function containerKey(element){
                    var path=[];
                    while(element&&element!==document.body){
                      var parent=element.parentElement;
                      if(!parent) return '';
                      path.unshift(Array.prototype.indexOf.call(parent.children,element));
                      element=parent;
                    }
                    return path.join('.');
                  }
                  function refreshContainers(){
                    scrollContainers=Array.from(document.body.querySelectorAll('*')).filter(function(element){
                      if(!element.clientWidth||!element.clientHeight) return false;
                      var overflow=getComputedStyle(element).overflowX;
                      return overflow==='auto'||overflow==='scroll';
                    }).slice(0,256).map(function(element){return {element:element,key:containerKey(element)};});
                  }
                  window.__readerRefreshInkContainers=refreshContainers;
                  window.__readerInkContainers=function(){return scrollContainers;};
                  window.__readerMetrics=function(){
                    var root=document.scrollingElement||document.documentElement;
                    return {sectionId:window.__readerSectionId,epoch:window.__readerInputEpoch,
                      scrollWidth:Math.max(root.scrollWidth,window.innerWidth),
                      scrollHeight:Math.max(1,root.scrollHeight),innerWidth:Math.max(1,window.innerWidth),
                      scale:window.devicePixelRatio*(window.visualViewport?window.visualViewport.scale:1),
                      containers:scrollContainers.filter(function(item){return item.key&&item.key.length<=192;}).map(function(item){
                        var element=item.element, rect=element.getBoundingClientRect();
                        var left=rect.left+window.scrollX+element.clientLeft, top=rect.top+window.scrollY+element.clientTop;
                        // RTL overflow has a negative scrollLeft. Store the visible offset from the physical left.
                        var maxLeft=Math.max(0,element.scrollWidth-element.clientWidth);
                        var leftOffset=getComputedStyle(element).direction==='rtl'?maxLeft+element.scrollLeft:element.scrollLeft;
                        return {key:item.key,left:left,top:top,width:element.clientWidth,height:element.clientHeight,
                          contentWidth:element.scrollWidth,contentHeight:element.scrollHeight,
                          scrollX:leftOffset,scrollY:element.scrollTop};
                      })};
                  };
                  var metricsFrame=0;
                  window.__readerReportMetrics=function(){
                    if(metricsFrame) return;
                    metricsFrame=requestAnimationFrame(function(){
                      metricsFrame=0;
                      try{ReaderBridge.onScrollMetrics(JSON.stringify(window.__readerMetrics()));}catch(e){}
                    });
                  };
                  document.addEventListener('scroll',function(e){
                    if(window.__readerInkEditing&&e.target!==document&&e.target!==document.documentElement&&e.target!==document.body)
                      window.__readerReportMetrics();
                  },{capture:true,passive:true});
                  window.addEventListener('scroll',function(){
                    if(!window.__readerScrollPending){
                      window.__readerScrollPending=true;
                      try{ReaderBridge.onPositionInvalidated(window.__readerSectionId||'',window.__readerInputEpoch||'');}catch(e){}
                    }
                    clearTimeout(window.__readerTimer);
                    window.__readerTimer=setTimeout(function(){requestAnimationFrame(window.__readerReport);},180);
                  },{passive:true});
                  window.addEventListener('resize',function(){
                    refreshContainers();
                    window.__readerReportMetrics();
                  });
                  if(window.visualViewport)window.visualViewport.addEventListener('resize',window.__readerReportMetrics);
                  if(window.ResizeObserver){
                    window.__readerInkResizeObserver=new ResizeObserver(window.__readerReportMetrics);
                    window.__readerInkResizeObserver.observe(document.body);
                  }
                }
                $readerWebInkScript
                window.__readerReport && window.__readerReport();
                window.__readerRefreshInkContainers && window.__readerRefreshInkContainers();
                window.__readerInk.refresh();
                window.__readerReportMetrics && window.__readerReportMetrics();
              }
              install();
            })();
            """.trimIndent(),
            null,
        )
        if (preservePosition) webView?.evaluateJavascript(ReaderSemanticViewportScript.endLayout(), null)
        webView?.evaluateJavascript("window.__readerInputEpoch=${JsonPrimitive(inputEpoch)};" + readerWebInputScript, null)
    }

    fun displayInk(strokes: List<InkStroke>, editing: Boolean, onDisplayed: (Boolean) -> Unit) {
        val view = webView ?: return onDisplayed(false)
        val epoch = inputEpoch
        val scope = "window.__readerInputEpoch===${JsonPrimitive(epoch)}&&window.__readerInk"
        view.evaluateJavascript(
            "if($scope){window.__readerInkEditing=$editing;window.__readerInk.begin();" +
                "if($editing)window.__readerReportMetrics();}",
            null,
        )
        // Bound each WebView message even for old manifests with thousands of points per stroke.
        var first = 0
        while (first < strokes.size) {
            var end = first + 1
            var points = strokes[first].points.size
            while (end < strokes.size && points + strokes[end].points.size <= InkStroke.MAX_POINTS) {
                points += strokes[end++].points.size
            }
            val payload = buildJsonArray {
                strokes.subList(first, end).forEach { stroke ->
                    add(buildJsonObject {
                        put("color", stroke.color)
                        put("width", stroke.width)
                        put("documentWidth", stroke.usesDocumentWidth)
                        put("marker", stroke.tool == InkTool.MARKER)
                        stroke.scrollContainer?.let { put("container", it) }
                        put("points", buildJsonArray {
                            stroke.points.forEach { point ->
                                add(buildJsonArray {
                                    add(JsonPrimitive(point.x))
                                    add(JsonPrimitive(point.y))
                                })
                            }
                        })
                    })
                }
            }
            view.evaluateJavascript("$scope&&window.__readerInk.append($payload)", null)
            first = end
        }
        view.evaluateJavascript("$scope&&window.__readerInk.finish()") { result ->
            if (view !== webView || epoch != inputEpoch || result != "true") {
                onDisplayed(false)
            } else {
                view.postVisualStateCallback(0L, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) = onDisplayed(view === webView && epoch == inputEpoch)
                })
            }
        }
    }
}

private fun readerPaperColor(settings: ReaderSettings, colors: ReaderAppearanceColors?): String =
    colors?.backgroundHex ?: when (settings.theme) {
        ReaderTheme.LIGHT -> "#FAFAFA"
        ReaderTheme.SEPIA -> "#F7F0E3"
        ReaderTheme.NIGHT -> "#1E2622"
    }

internal fun readerSettingsStyleLiteral(settings: ReaderSettings, colors: ReaderAppearanceColors? = null): String {
    val background = readerPaperColor(settings, colors)
    val color = colors?.foregroundHex ?: settings.fontColor.resolve(settings.theme)
    val fontSize = (settings.fontScale * 100).toInt()
    val css = "html{font-size:100% !important;}html,body{background:$background !important;color:$color !important;" +
        "line-height:${settings.lineHeight} !important;" +
        "padding-bottom:80px !important;}body{padding-left:${settings.marginDp.coerceIn(0, 64)}px !important;" +
        "font-size:${fontSize}% !important;line-height:${settings.lineHeight} !important;padding-right:${settings.marginDp.coerceIn(0, 64)}px !important;}" +
        "body *:not(svg):not(svg *){color:$color !important;-webkit-text-fill-color:$color !important;}" +
        "img{max-width:100% !important;height:auto !important;}" +
        if (settings.immersivePreference) {
            "html,body{padding-top:0px !important;margin-top:0px !important;}body>:first-child{margin-top:0px !important;}"
        } else ""
    return JsonPrimitive(css).toString()
}
