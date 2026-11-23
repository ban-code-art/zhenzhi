package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.data.reader.notes.*
import com.example.myapplication.domain.reader.ReaderLocator
import kotlinx.serialization.json.*

private fun annotationLocatorObject(locator: ReaderLocator.Reflow) = buildJsonObject {
    put("documentId", locator.documentId); put("sectionId", locator.sectionId); put("contentRevision", locator.contentRevision)
    put("canonicalStart", locator.canonicalStart); put("canonicalEnd", locator.canonicalEnd)
    put("quote", locator.exactQuote); put("prefix", locator.prefix.orEmpty()); put("suffix", locator.suffix.orEmpty())
    put("blockId", locator.blockId)
}

fun readerAnnotationLocatorPayload(locator: ReaderLocator): String =
    (locator as? ReaderLocator.Reflow)?.let(::annotationLocatorObject)?.toString() ?: "null"

/** Every target is independently filtered; the parent section is only a legacy convenience. */
fun readerAnnotationPayload(annotations: List<ReaderAnnotationView>, sectionId: String,
    documentId: String? = null, contentRevision: String? = null): String = buildJsonArray {
    annotations.filter { it.kind != AnnotationKind.INK }.forEach { annotation ->
        val targets = annotation.targets.map { it.locator }.ifEmpty { listOfNotNull(annotation.locator) }
        targets.forEachIndexed { index, target ->
            val locator = target as? ReaderLocator.Reflow ?: return@forEachIndexed
            if (locator.sectionId != sectionId || (documentId != null && locator.documentId != documentId) ||
                (contentRevision != null && locator.contentRevision != contentRevision)) return@forEachIndexed
            add(buildJsonObject {
                annotationLocatorObject(locator).forEach { (key, value) -> put(key, value) }
                put("id", annotation.id); put("targetIndex", index); put("color", annotation.style.color.hex)
                put("underline", annotation.style.underline)
            })
        }
    }
}.toString()

/** Browser-painted ranges and the older WebView overlay never mutate source text nodes. */
val readerAnnotationDecorationScript: String = """
(function(){
  if(window.__readerAnnotationEngine){window.__readerAnnotationEngine.install();return;}
  var items=[],resolved=[],diagnostics=[],layer=null,style=null,frame=0,dragging=false,dirty=true;
  var highlightKeys=[],nativeHighlights=!!(!window.__readerForceAnnotationOverlay&&window.CSS&&CSS.highlights&&
    typeof window.Highlight==='function'&&CSS.supports('selector(::highlight(reader-saved))'));
  function excluded(node){
    var p=node.parentElement;
    return !p || !!p.closest('script,style,noscript,[data-reader-annotation-layer]');
  }
  function indexText(){
    var nodes=[],text='',walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,{
      acceptNode:function(n){return excluded(n)?NodeFilter.FILTER_REJECT:NodeFilter.FILTER_ACCEPT;}
    }),n;
    while((n=walker.nextNode())){var value=n.nodeValue||'';nodes.push({node:n,start:text.length,end:text.length+value.length});text+=value;}
    return {nodes:nodes,text:text};
  }
  function endpoint(index,offset,end){
    for(var i=0;i<index.nodes.length;i++){var n=index.nodes[i];
      if(offset>=n.start && (offset<n.end || (end&&offset===n.end)))return {node:n.node,offset:offset-n.start};
    }
    var last=index.nodes[index.nodes.length-1];
    return last&&offset===last.end?{node:last.node,offset:last.end-last.start}:null;
  }
  function makeRange(index,start,end){
    if(start<0||end<=start||end>index.text.length)return null;
    var a=endpoint(index,start,false),b=endpoint(index,end,true);if(!a||!b)return null;
    var r=document.createRange();r.setStart(a.node,a.offset);r.setEnd(b.node,b.offset);return r;
  }
  function contextMatches(text,start,end,item){
    return (!item.prefix||text.slice(Math.max(0,start-item.prefix.length),start)===item.prefix)&&
      (!item.suffix||text.slice(end,end+item.suffix.length)===item.suffix);
  }
  function resolve(index,item){
    if(!item||!item.quote||item.sectionId!==(window.__readerSectionId||''))return {status:'WRONG_SECTION'};
    var start=item.canonicalStart,end=item.canonicalEnd,text=index.text;
    if(Number.isInteger(start)&&Number.isInteger(end)&&text.slice(start,end)===item.quote&&contextMatches(text,start,end,item)){
      var exact=makeRange(index,start,end);if(exact)return {status:'EXACT',range:exact,start:start,end:end};
    }
    var positions=[],at=0;
    while(at<=text.length){var pos=text.indexOf(item.quote,at);if(pos<0)break;
      if(contextMatches(text,pos,pos+item.quote.length,item))positions.push(pos);
      if(positions.length>1)break;at=pos+1;
    }
    if(positions.length!==1)return {status:positions.length?'AMBIGUOUS':'NOT_FOUND'};
    start=positions[0];end=start+item.quote.length;
    var range=makeRange(index,start,end);return range?{status:'RELOCATED',range:range,start:start,end:end}:{status:'NOT_FOUND'};
  }
  function ensureLayer(){
    if(layer&&layer.isConnected)return layer;
    layer=document.createElementNS('http://www.w3.org/1999/xhtml','div');
    layer.setAttribute('data-reader-annotation-layer','1');layer.setAttribute('aria-hidden','true');
    // Scroll with the page's compositor even when JavaScript has not drawn the next frame.
    layer.style.cssText='position:absolute;left:0;top:0;width:0;height:0;pointer-events:none;z-index:2147483000;overflow:visible;';
    document.documentElement.appendChild(layer);return layer;
  }
  function rects(range){try{return Array.prototype.slice.call(range.getClientRects());}catch(e){return [];}}
  function visibleRects(range){
    var result=[],root=range.commonAncestorContainer;
    function append(node){
      if(!range.intersectsNode(node))return;
      var element=node.parentElement,visibility=getComputedStyle(element).visibility;
      if(visibility==='hidden'||visibility==='collapse')return;
      var c={left:0,right:innerWidth,top:0,bottom:innerHeight};
      for(var p=element;p;p=p.parentElement){
        var s=getComputedStyle(p);
        if(s.display==='none'||Number(s.opacity)===0||(s.clipPath&&s.clipPath!=='none'))return;
        var r=p.getBoundingClientRect(),sx=p.offsetWidth?r.width/p.offsetWidth:1,sy=p.offsetHeight?r.height/p.offsetHeight:1;
        if(/hidden|clip|scroll|auto/.test(s.overflowX)){c.left=Math.max(c.left,r.left+p.clientLeft*sx);c.right=Math.min(c.right,r.left+(p.clientLeft+p.clientWidth)*sx);}
        if(/hidden|clip|scroll|auto/.test(s.overflowY)){c.top=Math.max(c.top,r.top+p.clientTop*sy);c.bottom=Math.min(c.bottom,r.top+(p.clientTop+p.clientHeight)*sy);}
      }
      if(c.right<=c.left||c.bottom<=c.top)return;
      var part=document.createRange();part.setStart(node,node===range.startContainer?range.startOffset:0);
      part.setEnd(node,node===range.endContainer?range.endOffset:node.length);
      rects(part).forEach(function(r){var left=Math.max(r.left,c.left),right=Math.min(r.right,c.right),
        top=Math.max(r.top,c.top),bottom=Math.min(r.bottom,c.bottom);
        if(right>left&&bottom>top)result.push({left:left,top:top,width:right-left,height:bottom-top});});
    }
    if(root.nodeType===3)append(root);
    else {var walker=document.createTreeWalker(root,NodeFilter.SHOW_TEXT),node;while(node=walker.nextNode())append(node);}
    return result;
  }
  function renderNativeHighlights(){
    var groups=new Map();
    resolved.forEach(function(entry){
      var color=/^#[0-9a-fA-F]{6}$/.test(entry.item.color)?entry.item.color.toLowerCase():'#ffe58a';
      var key='reader-saved-'+color.slice(1)+(entry.item.underline?'-underlined':'');
      if(!groups.has(key))groups.set(key,{color:color,underline:entry.item.underline,ranges:new Highlight()});
      groups.get(key).ranges.add(entry.range);
    });
    if(!style||!style.isConnected){
      style=document.createElementNS('http://www.w3.org/1999/xhtml','style');
      style.setAttribute('data-reader-highlight-style','1');(document.head||document.documentElement).appendChild(style);
    }
    var rules=[];
    groups.forEach(function(group,key){
      CSS.highlights.set(key,group.ranges);
      rules.push('::highlight('+key+'){background-color:'+group.color+'6b;'+
        (group.underline?'text-decoration:underline 2px '+group.color+';':'')+'}');
    });
    highlightKeys.forEach(function(key){if(!groups.has(key))CSS.highlights.delete(key);});
    highlightKeys=Array.from(groups.keys());
    style.textContent=rules.join('\n');
  }
  function draw(){
    frame=0;
    if(dirty){
      var index=indexText();diagnostics=[];resolved=[];
      items.forEach(function(item){var result=resolve(index,item);
        diagnostics.push({id:item.id,targetIndex:item.targetIndex,status:result.status,start:result.start,end:result.end});
        if(result.range)resolved.push({item:item,range:result.range});
      });
      dirty=false;
      if(nativeHighlights)renderNativeHighlights();
    }
    if(nativeHighlights)return;
    if(!resolved.length&&!layer)return;
    var host=ensureLayer(),origin=host.getBoundingClientRect(),nodes=document.createDocumentFragment();
    resolved.forEach(function(entry){visibleRects(entry.range).forEach(function(rect){
      var mark=document.createElementNS('http://www.w3.org/1999/xhtml','span');
      mark.setAttribute('data-reader-highlight',entry.item.id);mark.setAttribute('data-reader-target',String(entry.item.targetIndex));
      mark.style.cssText='position:absolute;pointer-events:none;mix-blend-mode:multiply;opacity:.42;';
      mark.style.left=(rect.left-origin.left)+'px';mark.style.top=(rect.top-origin.top)+'px';mark.style.width=rect.width+'px';mark.style.height=rect.height+'px';
      mark.style.backgroundColor=entry.item.color||'#FFE58A';
      if(entry.item.underline)mark.style.borderBottom='2px solid '+(entry.item.color||'#FFE58A');nodes.appendChild(mark);
    });});
    while(host.firstChild)host.removeChild(host.firstChild);
    host.appendChild(nodes);
  }
  function schedule(){if(!frame&&(!nativeHighlights||dirty))frame=requestAnimationFrame(draw);}
  function refresh(){dirty=true;schedule();}
  window.__readerDecorate=function(next){
    items=Array.isArray(next)?next.slice():[];dirty=true;if(frame)cancelAnimationFrame(frame);draw();return items.length;
  };
  window.__readerLocateTarget=function(item,viewportOffset){
    if(dragging)return {status:'SELECTION_DRAGGING'};
    var result=resolve(indexText(),item);if(!result.range)return {status:result.status};
    var rs=rects(result.range);if(!rs.length)return {status:'NO_LAYOUT'};
    var targetY=Number.isFinite(viewportOffset)?viewportOffset:window.innerHeight/2;
    window.scrollBy({top:rs[0].top-targetY,left:0,behavior:'auto'});schedule();
    return {status:result.status,canonicalStart:result.start,canonicalEnd:result.end};
  };
  // Selection coordinates share the exact text index used by decoration, including element endpoints.
  window.__readerAnnotationSelection=function(){
    var selection=window.getSelection();if(!selection||selection.isCollapsed||!selection.rangeCount)return null;
    var range=selection.getRangeAt(0),index=indexText();
    function offset(container,offset){
      if(container.nodeType===3){for(var i=0;i<index.nodes.length;i++)if(index.nodes[i].node===container)return index.nodes[i].start+offset;}
      var boundary=document.createRange();try{boundary.setStart(container,offset);boundary.collapse(true);}catch(e){return null;}
      for(var j=0;j<index.nodes.length;j++){var n=index.nodes[j],probe=document.createRange();probe.selectNodeContents(n.node);
        if(boundary.compareBoundaryPoints(Range.START_TO_START,probe)<=0)return n.start;
      }return index.text.length;
    }
    var start=offset(range.startContainer,range.startOffset),end=offset(range.endContainer,range.endOffset);
    if(start===null||end===null||end<=start)return null;
    var quote=index.text.slice(start,end);if(!quote.trim()||quote.length>8192)return null;
    var section=window.__readerSectionId||'',pieces=[],seen=Object.create(null);
    function validUtf16(text){for(var i=0;i<text.length;i++){var c=text.charCodeAt(i);
      if(c>=0xD800&&c<=0xDBFF){var next=text.charCodeAt(++i);if(!(next>=0xDC00&&next<=0xDFFF))return false;}
      else if(c>=0xDC00&&c<=0xDFFF)return false;}return true;}
    function context(a,b){a=Math.max(0,a);b=Math.min(index.text.length,b);
      if(a>0&&/[\uDC00-\uDFFF]/.test(index.text.charAt(a)))a++;
      if(b<index.text.length&&/[\uDC00-\uDFFF]/.test(index.text.charAt(b)))b--;
      return index.text.slice(a,Math.max(a,b));}
    if(!section||!validUtf16(quote))return null;
    var blocks=document.body.querySelectorAll('[data-reader-block]');
    for(var k=0;k<blocks.length;k++){var id=blocks[k].getAttribute('data-reader-block');
      if(!id||!id.trim()||id===section||seen[id])return null;seen[id]=true;}
    index.nodes.forEach(function(n){var a=Math.max(start,n.start),b=Math.min(end,n.end);if(b<=a)return;
      var owner=n.node.parentElement&&n.node.parentElement.closest('[data-reader-block]');
      var block=owner?owner.getAttribute('data-reader-block'):section,previous=pieces[pieces.length-1];
      if(previous&&previous.blockId===block&&previous.canonicalEnd===a)previous.canonicalEnd=b;
      else pieces.push({sectionId:section,blockId:block,canonicalStart:a,canonicalEnd:b});
    });
    var fragments=[],used=Object.create(null);
    for(var k=0;k<pieces.length;k++){var part=pieces[k],a=part.canonicalStart,b=part.canonicalEnd;
      part.quote=index.text.slice(a,b);if(!part.quote.trim())continue;
      if(!validUtf16(part.quote)||used[part.blockId])return null;used[part.blockId]=true;
      part.prefix=context(a-48,a);part.suffix=context(b,b+48);fragments.push(part);
    }
    if(!fragments.length||fragments.length>128)return null;
    return {sectionId:section,blockId:fragments[0].blockId,canonicalStart:start,canonicalEnd:end,quote:quote,
      prefix:context(start-48,start),suffix:context(end,end+48),fragments:fragments};
  };
  window.__readerAnnotationDiagnostics=function(){if(dirty){if(frame)cancelAnimationFrame(frame);draw();}return diagnostics.slice();};
  window.__readerAnnotationRects=function(id){
    window.__readerAnnotationDiagnostics();
    var result=[];
    resolved.forEach(function(entry){
      if(entry.item.id===id)result=result.concat(visibleRects(entry.range));
    });
    return result;
  };
  document.addEventListener('pointerdown',function(){dragging=true;},{passive:true});
  function endDrag(){dragging=false;schedule();}
  document.addEventListener('pointerup',endDrag,{passive:true});document.addEventListener('pointercancel',endDrag,{passive:true});
  window.addEventListener('blur',endDrag);
  if(!nativeHighlights){
    window.addEventListener('scroll',schedule,{passive:true});
    document.addEventListener('scroll',schedule,{capture:true,passive:true});
    window.addEventListener('resize',schedule);
    if(window.visualViewport)window.visualViewport.addEventListener('resize',schedule);
    if(document.fonts&&document.fonts.ready)document.fonts.ready.then(schedule);
    if(window.ResizeObserver)new ResizeObserver(schedule).observe(document.body);
  }
  var decorate=window.__readerDecorate,locateTarget=window.__readerLocateTarget;
  new MutationObserver(function(records){
    if(records.some(function(record){var n=record.target;var p=n.nodeType===1?n:n.parentElement;
      return !(p&&p.closest('[data-reader-annotation-layer]'));}))refresh();
  }).observe(document.body,{subtree:true,childList:true,characterData:true});
  window.__readerAnnotationEngine={refresh:refresh,install:function(){window.__readerDecorate=decorate;window.__readerLocateTarget=locateTarget;refresh();}};refresh();
})();
""".trimIndent()
