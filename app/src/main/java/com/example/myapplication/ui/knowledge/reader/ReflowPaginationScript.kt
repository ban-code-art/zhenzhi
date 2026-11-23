package com.example.myapplication.ui.knowledge.reader

/** CSS pagination and natural block-axis scrolling share verified source geometry and anchors. */
internal object ReflowPaginationScript {
    val install = """
        (function(){try{
          var nodes=Array.prototype.slice.call(document.body.querySelectorAll('[data-reader-block-id]'));
          var originalElements=Array.prototype.slice.call(document.body.querySelectorAll('*'));
          if(!nodes.length)throw Error('REFLOW_NO_PRODUCTION_BLOCKS');
          var mode=getComputedStyle(nodes[0]).writingMode;
          if(['horizontal-tb','vertical-rl','vertical-lr'].indexOf(mode)<0)throw Error('REFLOW_WRITING_MODE_UNSUPPORTED');
          function checkModes(){
            for(var i=0;i<nodes.length;i++){
              if(getComputedStyle(nodes[i]).writingMode!==mode)throw Error('REFLOW_MIXED_WRITING_MODES');
              var walker=document.createTreeWalker(nodes[i],NodeFilter.SHOW_TEXT),text;
              while(text=walker.nextNode())if(text.textContent.trim()&&getComputedStyle(text.parentElement).writingMode!==mode)
                throw Error('REFLOW_MIXED_WRITING_MODES');
            }
          }
          // A horizontal body containing one vertical root is valid. The rendered blocks, rather
          // than an otherwise empty ancestor, determine the section's writing mode.
          checkModes();
          var direction=getComputedStyle(document.body).direction;
          var vertical=mode!=='horizontal-tb',sign=direction==='rtl'?-1:1;
          var viewport=document.createElement('div'),content=document.createElement('div');
          while(document.body.firstChild)content.appendChild(document.body.firstChild);
          viewport.appendChild(content);document.body.appendChild(viewport);
          // Assign individual host properties: cssText would erase the author's html writing-mode.
          document.documentElement.style.height='100%';document.documentElement.style.width='100%';
          document.documentElement.style.overflow='hidden';
          document.body.style.margin='0';document.body.style.overflow='hidden';
          viewport.style.cssText='position:fixed;inset:16px;overflow:hidden;writing-mode:horizontal-tb;direction:ltr;contain:layout paint size';
          content.style.cssText='position:absolute;left:0;top:0;margin:0;padding:0;min-width:0;min-height:0;max-width:none;max-height:none;column-gap:0;column-fill:auto;column-count:auto;overflow:visible';
          content.style.writingMode=mode;content.style.direction=direction;
          var dirty=true,width=0,height=0,extent=0,pageCount=1,pageIndex=0,origin=null,starts=Object.create(null);
          var scrolling=false,scrollOffset=0,maxScroll=0;
          var api={viewport:viewport,content:content};
          api.setFlowMode=function(value){
            if(value!=='SCROLL'&&value!=='PAGINATED')throw Error('REFLOW_INVALID_FLOW_MODE');
            var next=value==='SCROLL';if(next!==scrolling){scrolling=next;scrollOffset=0;dirty=true;}
          };
          var occurrence=0;
          function moved(){if(occurrence>=Number.MAX_SAFE_INTEGER)throw Error('REFLOW_POSITION_EXHAUSTED');occurrence++;}
          api.occurrence=function(){return String(occurrence);};
          api.invalidate=function(){dirty=true;};
          function painted(element){
            for(var n=element;n;n=n.parentElement){
              var style=getComputedStyle(n);
              if(style.display==='none'||style.visibility==='hidden'||style.visibility==='collapse'||Number(style.opacity)===0)return false;
            }
            return true;
          }
          function fragments(node,visit){
            if(!painted(node))return;
            if(node.tagName==='IMG'){
              visitClipped(node.getClientRects(),node.parentElement,visit);
              return;
            }
            // Element rectangles can span an entire long paragraph, including empty pages.
            // Text-node ranges retain the browser's individual line/column fragments.
            var walker=document.createTreeWalker(node,NodeFilter.SHOW_TEXT),text;
            while(text=walker.nextNode()){
              if(!text.textContent.trim()||!painted(text.parentElement))continue;
              var range=document.createRange();range.selectNodeContents(text);var rects=range.getClientRects();
              visitClipped(rects,text.parentElement,visit);
            }
          }
          function visitClipped(rects,element,visit){
            var left=-Infinity,top=-Infinity,right=Infinity,bottom=Infinity;
            // Range rectangles include text outside an author's overflow clip. Measure only
            // painted fragments, but never clip against our viewport or host: later pages
            // must still contribute their full extent while the pager is at its origin.
            for(var p=element;p&&p!==content;p=p.parentElement){
              var style=getComputedStyle(p),clipX=/^(hidden|clip|scroll|auto)$/.test(style.overflowX),
                  clipY=/^(hidden|clip|scroll|auto)$/.test(style.overflowY);
              if(!clipX&&!clipY)continue;
              var r=p.getBoundingClientRect(),sx=p.offsetWidth?r.width/p.offsetWidth:1,sy=p.offsetHeight?r.height/p.offsetHeight:1;
              if(clipX){left=Math.max(left,r.left+p.clientLeft*sx);right=Math.min(right,r.left+(p.clientLeft+p.clientWidth)*sx);}
              if(clipY){top=Math.max(top,r.top+p.clientTop*sy);bottom=Math.min(bottom,r.top+(p.clientTop+p.clientHeight)*sy);}
            }
            for(var i=0;i<rects.length;i++){
              var r=rects[i],l=Math.max(left,r.left),t=Math.max(top,r.top),rr=Math.min(right,r.right),bb=Math.min(bottom,r.bottom);
              if(rr>l&&bb>t)visit({left:l,top:t,right:rr,bottom:bb,width:rr-l,height:bb-t});
            }
          }
          function colorPainted(value){
            var channels=value.match(/[\d.]+/g);
            return channels&&channels.length>=3&&(channels.length<4||Number(channels[3])>0);
          }
          function extraVisualFragments(node,visit){
            if(!painted(node))return;
            if(node.tagName==='IMG'){fragments(node,visit);return;}
            var pseudos=['::before','::after'],hasGenerated=false;
            for(var i=0;i<pseudos.length;i++){
              var pseudo=getComputedStyle(node,pseudos[i]),generated=pseudo.content;
              if(generated&&generated!=='none'&&generated!=='normal'&&generated!=='""'&&generated!=="''"&&
                  pseudo.display!=='none'&&pseudo.visibility!=='hidden'&&Number(pseudo.opacity)!==0)hasGenerated=true;
            }
            // Generated content has no independent Text Range. A text-bearing paragraph or
            // container box cannot prove its visual fragments or supply a canonical anchor.
            if(node.childElementCount||node.textContent.trim()){
              if(hasGenerated)throw Error('REFLOW_GENERATED_CONTENT_UNSUPPORTED');
              return;
            }
            // Atomic empty leaves retain their generated labels, backgrounds and borders.
            var style=getComputedStyle(node),hasPaint=hasGenerated||style.backgroundImage!=='none'||colorPainted(style.backgroundColor);
            var sides=['Top','Right','Bottom','Left'];
            for(var i=0;i<sides.length;i++)if(parseFloat(style['border'+sides[i]+'Width'])>0&&colorPainted(style['border'+sides[i]+'Color']))hasPaint=true;
            if(hasPaint)visitClipped(node.getClientRects(),node.parentElement,visit);
          }
          function startOf(rect){
            if(scrolling)return vertical?(mode==='vertical-rl'?origin.right-rect.right:rect.left-origin.left):rect.top-origin.top;
            if(vertical)return sign>0?rect.top-origin.top:origin.top+height-rect.bottom;
            return sign>0?rect.left-origin.left:origin.left+width-rect.right;
          }
          function endOf(rect){
            if(scrolling)return vertical?(mode==='vertical-rl'?origin.right-rect.left:rect.right-origin.left):rect.bottom-origin.top;
            if(vertical)return sign>0?rect.bottom-origin.top:origin.top+height-rect.top;
            return sign>0?rect.right-origin.left:origin.left+width-rect.left;
          }
          function position(){
            if(scrolling){
              content.style.transform='none';
              scrollOffset=Math.max(0,Math.min(maxScroll,scrollOffset));
              viewport.scrollTop=vertical?0:scrollOffset;
              viewport.scrollLeft=vertical?(mode==='vertical-rl'?-scrollOffset:scrollOffset):0;
              return;
            }
            var shift=-sign*pageIndex*extent;
            content.style.transform=vertical?'translate3d(0,'+shift+'px,0)':'translate3d('+shift+'px,0,0)';
            viewport.scrollLeft=0;viewport.scrollTop=0;
          }
          api.layout=function(){
            var w=viewport.clientWidth,h=viewport.clientHeight,bounds=viewport.getBoundingClientRect();
            if(w<=0||h<=0)throw Error('REFLOW_EMPTY_VIEWPORT');
            if(!dirty&&w===width&&h===height&&origin&&bounds.left===origin.left&&bounds.top===origin.top)return;
            checkModes();width=w;height=h;extent=scrolling?(vertical?w:h):(vertical?h:w);
            viewport.style.overflow=scrolling?'auto':'hidden';
            viewport.style.direction=scrolling&&vertical&&mode==='vertical-rl'?'rtl':'ltr';
            content.style.left=scrolling&&vertical&&mode==='vertical-rl'?'auto':'0';
            content.style.right=scrolling&&vertical&&mode==='vertical-rl'?'0':'auto';
            content.style.width=scrolling&&vertical?'max-content':w+'px';
            content.style.height=scrolling&&!vertical?'auto':h+'px';
            // Multicol column-width is an INLINE size. Vertical columns therefore form physical
            // pages down/up the y axis, while each page retains its authored RL/LR text columns.
            content.style.columnWidth=scrolling?'auto':extent+'px';
            content.style.transform='none';viewport.scrollLeft=0;viewport.scrollTop=0;
            origin=viewport.getBoundingClientRect();starts=Object.create(null);
            var end=0,rendered=0,visualPages=Object.create(null),anchoredPages=Object.create(null);
            function measure(rect,canonical){
              // Unfragmented orthogonal overflow (for example a fixed-size root/table) cannot
              // be represented by this one-axis pager. Fail rather than commit an offscreen block.
              var horizontalAxis=scrolling?vertical:!vertical;
              var low=horizontalAxis?rect.top-origin.top:rect.left-origin.left;
              var high=horizontalAxis?rect.bottom-origin.top:rect.right-origin.left;
              if(low < -2 || high > (horizontalAxis?height:width)+2)throw Error('REFLOW_UNSUPPORTED_OVERFLOW');
              var first=startOf(rect),last=endOf(rect);
              if(!isFinite(first)||!isFinite(last))throw Error('REFLOW_LAYOUT_TOO_LARGE');
              end=Math.max(end,last);
              if(last>0.5){
                var firstPage=Math.max(0,Math.floor((first+0.5)/extent));
                var lastPage=Math.max(firstPage,Math.floor((last-0.5)/extent));
                if(lastPage>=100000)throw Error('REFLOW_LAYOUT_TOO_LARGE');
                for(var page=firstPage;page<=lastPage;page++){
                  visualPages[page]=true;if(canonical)anchoredPages[page]=true;
                }
              }
              return first;
            }
            for(var i=0;i<nodes.length;i++){
              var id=nodes[i].getAttribute('data-reader-block-id'),first=Infinity;
              fragments(nodes[i],function(rect){
                first=Math.min(first,measure(rect,true));rendered++;
              });
              if(first!==Infinity)starts[id]=Math.max(0,first);
            }
            if(!rendered)throw Error('REFLOW_NO_RENDERED_PRODUCTION_BLOCK');
            // Measure original text and images plus empty painted leaves, keeping visual extent
            // separate from the verified production blocks that are permitted to anchor a page.
            fragments(content,function(rect){measure(rect,false);});
            for(var i=0;i<originalElements.length;i++)extraVisualFragments(originalElements[i],function(rect){measure(rect,false);});
            maxScroll=Math.max(0,end-extent);
            pageCount=scrolling?Math.max(1,Math.ceil(maxScroll/extent)+1):Math.max(1,Math.ceil((end-0.5)/extent));
            if(!isFinite(pageCount)||pageCount>100000)throw Error('REFLOW_LAYOUT_TOO_LARGE');
            for(var page=0;page<pageCount;page++)if(visualPages[page]&&!anchoredPages[page])
              throw Error('REFLOW_UNANCHORED_VISUAL_PAGE');
            pageIndex=Math.max(0,Math.min(pageCount-1,pageIndex));dirty=false;position();moved();
          };
          api.count=function(){return pageCount;};
          api.index=function(){return pageIndex;};
          api.page=function(index){
            if(!isFinite(index))throw Error('REFLOW_INVALID_PAGE');
            pageIndex=Math.max(0,Math.min(pageCount-1,Math.floor(index)));
            if(scrolling)scrollOffset=Math.min(maxScroll,pageIndex*extent);
            position();moved();
          };
          api.turn=function(direction){
            if(!scrolling){api.page(pageIndex+direction);return;}
            syncScroll();scrollOffset=Math.max(0,Math.min(maxScroll,scrollOffset+direction*extent));
            position();syncScroll();moved();
          };
          function visibleBounds(){
            var b=viewport.getBoundingClientRect(),v=window.visualViewport;
            var left=v?v.offsetLeft:0,top=v?v.offsetTop:0,right=left+(v?v.width:innerWidth),bottom=top+(v?v.height:innerHeight);
            return {left:Math.max(left,b.left),top:Math.max(top,b.top),right:Math.min(right,b.right),bottom:Math.min(bottom,b.bottom)};
          }
          function visible(node){
            var b=visibleBounds(),found=false;
            fragments(node,function(r){if(r.right>b.left+0.5&&r.left<b.right-0.5&&r.bottom>b.top+0.5&&r.top<b.bottom-0.5)found=true;});
            return found;
          }
          api.locate=function(id){
            for(var i=0;i<nodes.length;i++)if(nodes[i].getAttribute('data-reader-block-id')===id){
              if(starts[id]===undefined)throw Error('REFLOW_BLOCK_NOT_RENDERED');
              if(scrolling){scrollOffset=Math.min(maxScroll,starts[id]);position();syncScroll();moved();}
              else api.page(Math.floor((starts[id]+0.5)/extent));
              if(!visible(nodes[i]))throw Error('REFLOW_BLOCK_NOT_VISIBLE');
              return;
            }
            throw Error('REFLOW_BLOCK_NOT_FOUND');
          };
          api.locateRange=function(range){
            if(!range||range.startContainer.ownerDocument!==document)throw Error('REFLOW_INVALID_RANGE');
            // Measure with the pager's own writing-mode axis after removing its current translation.
            api.page(0);
            var rs=range.getClientRects();if(!rs.length)throw Error('REFLOW_RANGE_NOT_RENDERED');
            var targetOffset=startOf(rs[0]),destination=Math.floor((targetOffset+0.5)/extent);
            if(destination<0||destination>=pageCount)throw Error('REFLOW_RANGE_OUTSIDE_LAYOUT');
            if(scrolling){scrollOffset=Math.max(0,Math.min(maxScroll,targetOffset));position();syncScroll();moved();}
            else api.page(destination);
            var bounds=visibleBounds(),visible=false,located=range.getClientRects();
            for(var i=0;i<located.length;i++){var r=located[i];
              if(r.right>bounds.left+.5&&r.left<bounds.right-.5&&r.bottom>bounds.top+.5&&r.top<bounds.bottom-.5)visible=true;
            }
            if(!visible)throw Error('REFLOW_RANGE_NOT_VISIBLE');
          };
          api.report=function(){
            if(scrolling)syncScroll();
            for(var i=0;i<nodes.length;i++)if(visible(nodes[i]))
              return {blockId:nodes[i].getAttribute('data-reader-block-id'),pageIndex:pageIndex,pageCount:pageCount,
                scrollProgression:scrolling?(maxScroll===0?0:scrollOffset/maxScroll):null,
                atStart:scrolling?scrollOffset<=.5:pageIndex===0,atEnd:scrolling?scrollOffset>=maxScroll-.5:pageIndex===pageCount-1};
            throw Error('REFLOW_NO_VISIBLE_PRODUCTION_BLOCK');
          };
          function syncScroll(){
            if(!scrolling||dirty)return;
            var value=vertical?(mode==='vertical-rl'?-viewport.scrollLeft:viewport.scrollLeft):viewport.scrollTop;
            value=Math.max(0,Math.min(maxScroll,value));
            var previous=scrollOffset;scrollOffset=value;
            pageIndex=value>=maxScroll-.5?pageCount-1:Math.floor((value+.5)/extent);
            if(previous!==value)moved();
          }
          viewport.addEventListener('scroll',function(){
            syncScroll();
            if(window.__reflowAnnotations)window.__reflowAnnotations.refresh();
            try{window.__isolatedReflowSelectionBridge.onViewportChanged();}catch(e){}
          },{passive:true});
          if(document.fonts&&document.fonts.addEventListener)document.fonts.addEventListener('loadingdone',api.invalidate);
          for(var i=0;i<document.images.length;i++){
            document.images[i].addEventListener('load',api.invalidate);document.images[i].addEventListener('error',api.invalidate);
          }
          window.addEventListener('resize',api.invalidate);
          window.__isolatedReflow=api;api.layout();return true;
        }catch(e){return {error:String(e.message||e)};}})()
    """.trimIndent()
}
