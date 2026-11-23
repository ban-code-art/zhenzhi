package com.example.myapplication.ui.knowledge.reader

import kotlinx.serialization.json.JsonPrimitive

/** Local DOM offsets are UTF-16 offsets, matching Range and the existing canonical locator. */
internal object ReaderSemanticViewportScript {
    fun install(sectionId: String, epoch: String): String = """
        (function(){
          var section=${JsonPrimitive(sectionId)},epoch=${JsonPrimitive(epoch)};
          var old=window.__readerSemantic;
          if(old&&old.sectionId===section&&old.epoch===epoch)return;
          if(old){old.disposed=true;if(old.dispose)old.dispose();}
          var api={sectionId:section,epoch:epoch,isRestoring:false,disposed:false,generation:0,anchor:null};
          window.__readerSemantic=api;
          function live(){return !api.disposed&&window.__readerSemantic===api;}
          var cachedSource=null,sourceObserver=null;
          function sourceChanged(records){
            if(records.some(function(record){var n=record.target,p=n.nodeType===1?n:n.parentElement;
              return !(p&&p.closest('script,style,noscript,[data-reader-annotation-layer]'));}))cachedSource=null;
          }
          if(window.MutationObserver&&document.body){
            sourceObserver=new MutationObserver(sourceChanged);
            sourceObserver.observe(document.body,{subtree:true,childList:true,characterData:true});
          }
          api.dispose=function(){api.disposed=true;cachedSource=null;if(sourceObserver)sourceObserver.disconnect();};
          function sourceIndex(){
            // Drain synchronous mutations too: writes may query the DOM before an observer callback.
            if(sourceObserver)sourceChanged(sourceObserver.takeRecords());
            if(cachedSource)return cachedSource;
            var out=[],w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,{acceptNode:function(n){
              var p=n.parentElement;
              if(!p||p.closest('script,style,noscript,[data-reader-annotation-layer]'))return NodeFilter.FILTER_REJECT;
              return NodeFilter.FILTER_ACCEPT;
            }}),n;
            while(n=w.nextNode())out.push(n);
            cachedSource={nodes:out,text:out.map(function(n){return n.nodeValue||'';}).join('')};
            return cachedSource;
          }
          function progress(){return Math.max(0,Math.min(1,window.scrollY/Math.max(1,document.documentElement.scrollHeight-window.innerHeight)));}
          function rectAt(n,i){var r=document.createRange();r.setStart(n,i);r.setEnd(n,Math.min(i+1,n.length));return r.getBoundingClientRect();}
          function contextSlice(text,start,end){
            if(start>0&&/[\uDC00-\uDFFF]/.test(text.charAt(start)))start++;
            if(end<text.length&&/[\uDC00-\uDFFF]/.test(text.charAt(end)))end--;
            return text.substring(start,end);
          }
          function clipFor(n){
            var clip={left:0,top:0,right:innerWidth,bottom:innerHeight};
            for(var p=n.parentElement;p;p=p.parentElement){
              var s=getComputedStyle(p);
              if(s.display==='none'||s.visibility==='hidden'||s.visibility==='collapse'||Number(s.opacity)===0)return null;
              var r=p.getBoundingClientRect();
              if(/hidden|clip|scroll|auto/.test(s.overflowX)){clip.left=Math.max(clip.left,r.left+p.clientLeft);clip.right=Math.min(clip.right,r.left+p.clientLeft+p.clientWidth);}
              if(/hidden|clip|scroll|auto/.test(s.overflowY)){clip.top=Math.max(clip.top,r.top+p.clientTop);clip.bottom=Math.min(clip.bottom,r.top+p.clientTop+p.clientHeight);}
            }
            return clip.right>clip.left&&clip.bottom>clip.top?clip:null;
          }
          function intersects(r,c){return r.width>0&&r.height>0&&r.right>c.left&&r.left<c.right&&r.bottom>c.top&&r.top<c.bottom;}
          function singleLtrLineSpan(n,rects,c){
            var value=n.nodeValue||'',len=value.length,p=n.parentElement;
            // Printable ASCII cannot introduce RTL runs. One preserved horizontal fragment,
            // without direction overrides, transforms or negative spacing, advances in DOM order.
            // Everything else retains the exact per-glyph path below, including surrogate pairs.
            if(len<256||!p||rects.length!==1||/[^\u0020-\u007E]/.test(value))return null;
            var style=getComputedStyle(p);
            if(value.indexOf(' ')>=0&&!/^(pre|pre-wrap|break-spaces)$/.test(style.whiteSpace))return null;
            for(var parent=p;parent;parent=parent.parentElement){
              if(parent.namespaceURI!=='http://www.w3.org/1999/xhtml')return null;
              var s=getComputedStyle(parent);
              if(s.direction!=='ltr'||s.writingMode!=='horizontal-tb'||
                !/^(normal|isolate)$/.test(s.unicodeBidi)||s.transform!=='none'||s.perspective!=='none'||
                (s.rotate&&s.rotate!=='none')||(s.scale&&s.scale!=='none')||(s.translate&&s.translate!=='none')||
                (s.zoom&&s.zoom!=='normal'&&Number(s.zoom)!==1)||
                parseFloat(s.letterSpacing)<0||parseFloat(s.wordSpacing)<0)return null;
            }
            var first=rectAt(n,0),middle=rectAt(n,len>>1),last=rectAt(n,len-1);
            function sameLine(r){return r.width>0&&r.height>0&&Math.abs(r.top-first.top)<.01&&Math.abs(r.bottom-first.bottom)<.01;}
            if(!sameLine(first)||!sameLine(middle)||!sameLine(last)||
              first.left>middle.left||middle.left>last.left||first.right>middle.right||middle.right>last.right)return null;
            var lo=0,hi=len;
            while(lo<hi){var mid=(lo+hi)>>1;if(rectAt(n,mid).right<=c.left)lo=mid+1;else hi=mid;}
            var start=lo;lo=start;hi=len;
            while(lo<hi){var mid=(lo+hi)>>1;if(rectAt(n,mid).left<c.right)lo=mid+1;else hi=mid;}
            var end=lo;
            if(start>=end)return {start:start,end:start};
            // Validate the two cuts against real neighboring glyphs before trusting the fast path.
            if(!intersects(rectAt(n,start),c)||!intersects(rectAt(n,end-1),c)||
              (start>0&&rectAt(n,start-1).right>c.left)||(end<len&&rectAt(n,end).left<c.right))return null;
            return {start:start,end:end};
          }
          function visibleSpans(list,all){
            var pieces=[],offset=0;
            var viewport={left:0,top:0,right:innerWidth,bottom:innerHeight};
            function add(start,end,block){
              if(start>=end)return;
              if(start>0&&/[\uDC00-\uDFFF]/.test(all.charAt(start)))start++;
              if(end<all.length&&/[\uDC00-\uDFFF]/.test(all.charAt(end)))end--;
              if(start>=end)return;
              var previous=pieces.length?pieces[pieces.length-1]:null;
              if(previous&&previous.end===start&&previous.block===block)previous.end=end;
              else pieces.push({start:start,end:end,block:block});
            }
            for(var j=0;j<list.length;j++){
              var n=list[j],len=n.length,r=document.createRange(),visible=false;
              r.selectNodeContents(n);var rects=r.getClientRects();
              // Offscreen source nodes need no ancestor style/overflow walks.
              for(var k=0;k<rects.length;k++)if(intersects(rects[k],viewport)){visible=true;break;}
              if(!visible){offset+=len;continue;}
              var c=clipFor(n);visible=false;
              if(c)for(var k=0;k<rects.length;k++)if(intersects(rects[k],c)){visible=true;break;}
              if(!visible){offset+=len;continue;}
              var p=n.parentElement,b=p&&p.closest('[data-reader-block]'),block=b?b.getAttribute('data-reader-block'):section;
              var horizontal=singleLtrLineSpan(n,rects,c);
              if(horizontal){add(offset+horizontal.start,offset+horizontal.end,block);offset+=len;continue;}
              var lo=0,hi=len;
              while(lo<hi){var mid=(lo+hi)>>1;if(rectAt(n,mid).bottom<=c.top)lo=mid+1;else hi=mid;}
              var first=lo,last=len;
              while(first<last){var mid=(first+last)>>1;if(rectAt(n,mid).top<c.bottom)first=mid+1;else last=mid;}
              var end=first;
              if(end>lo&&!/[\r\n\t\u200B-\u200D\uFEFF]/.test(n.nodeValue.substring(lo,end))){
                var band=document.createRange();band.setStart(n,lo);band.setEnd(n,end);
                var bands=band.getClientRects(),whole=bands.length>0;
                for(var k=0;k<bands.length;k++)if(!intersects(bands[k],c)||bands[k].left<c.left-.01||bands[k].right>c.right+.01){whole=false;break;}
                // Fully visible text lines can be proved with one Range rather than one per glyph.
                if(whole){add(offset+lo,offset+end,block);offset+=len;continue;}
              }
              var run=-1;
              for(var i=lo;i<len;i++){
                var cr=rectAt(n,i);
                if(cr.top>=c.bottom){if(run>=0)add(offset+run,offset+i,block);run=-1;break;}
                if(intersects(cr,c)){if(run<0)run=i;}
                else if(run>=0){add(offset+run,offset+i,block);run=-1;}
              }
              if(run>=0)add(offset+run,offset+len,block);
              offset+=len;
            }
            var out=[];
            for(var j=0;j<pieces.length;j++){
              var part=pieces[j];
              for(var start=part.start;start<part.end;){
                var end=Math.min(part.end,start+8192);
                if(end<all.length&&/[\uDC00-\uDFFF]/.test(all.charAt(end)))end--;
                if(end<=start)break;
                var quote=all.substring(start,end);
                if(quote.trim())out.push({sectionId:section,blockId:part.block,canonicalStart:start,canonicalEnd:end,quote:quote,
                  prefix:contextSlice(all,Math.max(0,start-48),start),suffix:contextSlice(all,end,Math.min(all.length,end+48))});
                start=end;
              }
            }
            return out;
          }
          function capture(){
            if(!live()||!document.body)return null;
            var index=sourceIndex(),list=index.nodes,offset=0,all=index.text;
            var viewport={left:0,top:0,right:innerWidth,bottom:innerHeight};
            for(var j=0;j<list.length;j++){
              var n=list[j],len=n.length,r=document.createRange();r.selectNodeContents(n);
              var rects=r.getClientRects(),visible=false;
              for(var k=0;k<rects.length;k++)if(intersects(rects[k],viewport)){visible=true;break;}
              if(!visible){offset+=len;continue;}
              var c=clipFor(n);visible=false;
              if(c)for(var k=0;k<rects.length;k++)if(intersects(rects[k],c)){visible=true;break;}
              if(!visible){offset+=len;continue;}
              // Reflow text lines have monotonically increasing vertical coordinates.
              var horizontal=singleLtrLineSpan(n,rects,c),lo=horizontal?horizontal.start:0,hi=len;
              if(!horizontal)while(lo<hi){var mid=(lo+hi)>>1;if(rectAt(n,mid).bottom<=c.top)lo=mid+1;else hi=mid;}
              var limit=horizontal?horizontal.end:len;
              for(var i=lo;i<limit;i++){
                var rect=rectAt(n,i);
                if(rect.top>=c.bottom)break;
                if(!intersects(rect,c)||/\s/.test(n.nodeValue.charAt(i)))continue;
                var start=offset+i,end=Math.min(all.length,start+48);
                // Do not split surrogate pairs at either quote boundary.
                if(start>0&&/[\uDC00-\uDFFF]/.test(all.charAt(start)))start--;
                if(end<all.length&&/[\uDC00-\uDFFF]/.test(all.charAt(end)))end++;
                var p=n.parentElement,b=p&&p.closest('[data-reader-block]');
                return {sectionId:section,epoch:epoch,canonicalStart:start,canonicalEnd:end,quote:all.substring(start,end),
                  prefix:contextSlice(all,Math.max(0,start-48),start),suffix:contextSlice(all,end,Math.min(all.length,end+48)),
                  blockId:b?b.getAttribute('data-reader-block'):section,viewportOffset:rect.top,progress:progress(),visibleRanges:visibleSpans(list,all)};
              }
              offset+=len;
            }
            return null;
          }
          function restore(a){
            if(!live()||!a||a.sectionId!==section||a.epoch!==epoch||!Number.isFinite(a.canonicalStart)||!Number.isFinite(a.viewportOffset))return false;
            var index=sourceIndex(),list=index.nodes,all=index.text,start=a.canonicalStart;
            if(start<0||start!==Math.floor(start)||!a.quote||all.substring(start,start+a.quote.length)!==a.quote)return false;
            var offset=0;
            for(var i=0;i<list.length;i++){
              var n=list[i];
              if(start<offset+n.length){var rect=rectAt(n,start-offset);window.scrollBy(0,rect.top-a.viewportOffset);return true;}
              offset+=n.length;
            }
            return false;
          }
          api.capture=capture;
          api.report=function(){if(!live()||api.isRestoring)return null;var a=capture();if(a)try{ReaderBridge.onSemanticViewport(JSON.stringify(a));}catch(e){}return a;};
          api.begin=function(){if(!live())return;if(!api.isRestoring)api.anchor=capture();api.isRestoring=true;api.generation++;};
          api.end=function(){if(!live())return;var ticket=api.generation;
            requestAnimationFrame(function(){requestAnimationFrame(function(){
              if(!live()||ticket!==api.generation)return;
              var ok=restore(api.anchor);api.anchor=null;api.isRestoring=false;
              if(ok)api.report();
              if(window.__readerReport)window.__readerReport();
            });});
          };
          api.restore=function(a){if(!live())return false;var ok=restore(a);if(ok)api.report();return ok;};
        })();
    """.trimIndent()

    fun beginLayout(): String = "window.__readerSemantic&&window.__readerSemantic.begin();"
    fun endLayout(): String = "window.__readerSemantic&&window.__readerSemantic.end();"
    fun report(): String = "window.__readerSemantic&&window.__readerSemantic.report();"
    /** JSON is passed as a string literal, never executable source. Epoch must match the live DOM. */
    fun restore(snapshotJson: String): String =
        "window.__readerSemantic&&window.__readerSemantic.restore(JSON.parse(${JsonPrimitive(snapshotJson)}));"
}
