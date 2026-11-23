package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.domain.reader.*
import kotlinx.serialization.json.*

/** Prepared production block text runs are the only offset authority in the isolated reader. */
internal object ReflowAnnotationDecorationScript {
    fun payload(document: PreparedReflowDocument, items: List<ReaderDecoration>): JsonArray? =
        ReflowAnnotationDecorationPayload.build(document, items).payload

    val install = """
    (function(){
      if(window.__reflowAnnotations)return true;
      var items=[],layer=null,dragging=false,frame=0,status=[];
      function host(){
        if(layer&&layer.isConnected)return layer;
        layer=document.createElement('div');layer.setAttribute('data-reflow-annotation-layer','1');layer.setAttribute('aria-hidden','true');
        layer.style.cssText='position:fixed;inset:0;overflow:hidden;pointer-events:none;z-index:2147483000;contain:strict;';
        document.documentElement.appendChild(layer);return layer;
      }
      function rangeFor(item){
        var all=document.querySelectorAll('[data-reader-block-id]'),root=null;
        for(var i=0;i<all.length;i++)if(all[i].getAttribute('data-reader-block-id')===item.blockId){if(root)return null;root=all[i];}
        if(!root||root.textContent!==item.text)return null;
        var start=null,end=null;
        for(var j=0;j<item.runs.length;j++){
          var run=item.runs[j],node=root;
          for(var p=0;p<run.path.length;p++)node=node&&node.childNodes[run.path[p]];
          if(!node||node.nodeType!==3||node.nodeValue!==run.text||run.end-run.start!==run.text.length)return null;
          if(item.canonicalStart>=run.start&&item.canonicalStart<run.end)start={node:node,offset:item.canonicalStart-run.start};
          if(item.canonicalEnd>run.start&&item.canonicalEnd<=run.end)end={node:node,offset:item.canonicalEnd-run.start};
        }
        if(!start||!end)return null;
        var range=document.createRange();range.setStart(start.node,start.offset);range.setEnd(end.node,end.offset);
        return range.toString()===item.quote?range:null;
      }
      function visibleRects(range,viewport){
        var result=[],root=range.commonAncestorContainer;
        function append(node){
          if(!window.__isolatedReflow.viewport.contains(node)||!range.intersectsNode(node))return;
          var element=node.parentElement,visibility=getComputedStyle(element).visibility;
          if(visibility==='hidden'||visibility==='collapse')return;
          var c={left:Math.max(0,viewport.left),right:Math.min(innerWidth,viewport.right),
            top:Math.max(0,viewport.top),bottom:Math.min(innerHeight,viewport.bottom)},inside=true;
          for(var p=element;p;p=p.parentElement){
            var s=getComputedStyle(p);
            if(s.display==='none'||Number(s.opacity)===0||(s.clipPath&&s.clipPath!=='none'))return;
            if(inside){
              var r=p.getBoundingClientRect(),sx=p.offsetWidth?r.width/p.offsetWidth:1,sy=p.offsetHeight?r.height/p.offsetHeight:1;
              if(/hidden|clip|scroll|auto/.test(s.overflowX)){c.left=Math.max(c.left,r.left+p.clientLeft*sx);c.right=Math.min(c.right,r.left+(p.clientLeft+p.clientWidth)*sx);}
              if(/hidden|clip|scroll|auto/.test(s.overflowY)){c.top=Math.max(c.top,r.top+p.clientTop*sy);c.bottom=Math.min(c.bottom,r.top+(p.clientTop+p.clientHeight)*sy);}
            }
            if(p===window.__isolatedReflow.viewport)inside=false;
          }
          if(c.right<=c.left||c.bottom<=c.top)return;
          var part=document.createRange();part.setStart(node,node===range.startContainer?range.startOffset:0);
          part.setEnd(node,node===range.endContainer?range.endOffset:node.length);
          var rects=part.getClientRects();
          for(var i=0;i<rects.length;i++){var r=rects[i],left=Math.max(r.left,c.left),right=Math.min(r.right,c.right),
            top=Math.max(r.top,c.top),bottom=Math.min(r.bottom,c.bottom);
            if(right>left&&bottom>top)result.push({left:left,right:right,top:top,bottom:bottom});}
        }
        if(root.nodeType===3)append(root);
        else {var walker=document.createTreeWalker(root,NodeFilter.SHOW_TEXT),node;while(node=walker.nextNode())append(node);}
        return result;
      }
      function render(){
        frame=0;if(dragging)return true;
        var h=host();while(h.firstChild)h.removeChild(h.firstChild);status=[];
        var api=window.__isolatedReflow;if(!api)return false;
        var viewport=api.viewport.getBoundingClientRect(),valid=true;
        for(var i=0;i<items.length;i++){
          var item=items[i],range=rangeFor(item),painted=0;
          if(!range){status.push({id:item.id,ordinal:item.ordinal,status:'SOURCE_CHANGED'});valid=false;continue;}
          var rects=visibleRects(range,viewport);
          for(var j=0;j<rects.length;j++){
            var r=rects[j],left=Math.max(r.left,viewport.left),right=Math.min(r.right,viewport.right),
                top=Math.max(r.top,viewport.top),bottom=Math.min(r.bottom,viewport.bottom);
            if(right<=left||bottom<=top)continue;
            var m=document.createElement('span');m.setAttribute('data-reflow-annotation',item.id);m.setAttribute('data-reflow-target',String(item.ordinal));
            m.style.cssText='position:absolute;pointer-events:none;opacity:.42;mix-blend-mode:multiply;';
            m.style.left=left+'px';m.style.top=top+'px';m.style.width=(right-left)+'px';m.style.height=(bottom-top)+'px';
            m.style.backgroundColor=item.color;if(item.underline)m.style.borderBottom='2px solid '+item.color;
            h.appendChild(m);painted++;
          }
          status.push({id:item.id,ordinal:item.ordinal,status:'EXACT',painted:painted,start:item.canonicalStart,end:item.canonicalEnd});
        }
        return valid;
      }
      function refresh(){if(!frame)frame=requestAnimationFrame(render);}
      document.addEventListener('pointerdown',function(){dragging=true;},{passive:true});
      function end(){dragging=false;refresh();}
      document.addEventListener('pointerup',end,{passive:true});document.addEventListener('pointercancel',end,{passive:true});window.addEventListener('blur',end);
      window.addEventListener('resize',refresh);if(document.fonts&&document.fonts.ready)document.fonts.ready.then(refresh);
      window.__reflowAnnotations={set:function(next){items=next;return render();},refresh:refresh,
        diagnostics:function(){return status.slice();}};
      return true;
    })()
    """.trimIndent()
}
