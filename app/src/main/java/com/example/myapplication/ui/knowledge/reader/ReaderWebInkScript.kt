package com.example.myapplication.ui.knowledge.reader

/**
 * Paint completed ink in the same scrolling layer as the document. Compose still samples
 * viewport geometry to capture points and erase strokes, but it does not drive saved ink motion.
 */
internal val readerWebInkScript = """
    (function(){
      if(window.__readerInk) return;
      var svgNs='http://www.w3.org/2000/svg', layers=new Map(), pending=[], strokes=[], frame=0;
      function restore(container,layer){
        layer.svg.remove();
        if(layer.position && container.style.getPropertyValue('position')==='relative' &&
            container.style.getPropertyPriority('position')==='important'){
          if(layer.position.value) container.style.setProperty('position',layer.position.value,layer.position.priority);
          else container.style.removeProperty('position');
        }
      }
      function layerFor(container){
        var layer=layers.get(container);
        if(layer && layer.svg.isConnected) return layer.svg;
        var svg=document.createElementNS(svgNs,'svg'), position=null;
        svg.setAttribute('data-reader-annotation-layer','ink');
        svg.setAttribute('aria-hidden','true');
        svg.style.cssText='position:absolute!important;display:block!important;margin:0!important;padding:0!important;border:0!important;'+
          'min-width:0!important;max-width:none!important;min-height:0!important;max-height:none!important;'+
          'overflow:hidden!important;pointer-events:none!important;z-index:2147483100;';
        if(container!==document.documentElement && getComputedStyle(container).position==='static'){
          position={value:container.style.getPropertyValue('position'),priority:container.style.getPropertyPriority('position')};
          container.style.setProperty('position','relative','important');
        }
        container.appendChild(svg);
        layers.set(container,{svg:svg,position:position});
        return svg;
      }
      function draw(){
        frame=0;
        if(!strokes.length){
          layers.forEach(function(layer,container){restore(container,layer);});layers.clear();return;
        }
        var root=document.scrollingElement||document.documentElement;
        var docWidth=Math.max(root.scrollWidth,window.innerWidth), docHeight=Math.max(1,root.scrollHeight);
        var layoutWidth=Math.max(1,window.innerWidth), entries=window.__readerInkContainers();
        var byKey=new Map(), groups=new Map();
        entries.forEach(function(item){
          var element=item.element, rect=element.getBoundingClientRect();
          var left=rect.left+window.scrollX+element.clientLeft, top=rect.top+window.scrollY+element.clientTop;
          byKey.set(item.key,{element:element,left:left,top:top,width:element.clientWidth,height:element.clientHeight});
        });
        strokes.forEach(function(stroke){
          var basis=stroke.documentWidth?docWidth:layoutWidth;
          var anchor=stroke.container?byKey.get(stroke.container):null;
          if(stroke.container&&!anchor)return;
          // Legacy strokes stored root coordinates before overflow containers had stable IDs.
          if(!stroke.container){
            byKey.forEach(function(candidate){
              if(stroke.points.every(function(point){
                var x=point[0]*basis,y=point[1]*docHeight;
                return x>=candidate.left&&x<candidate.left+candidate.width&&y>=candidate.top&&y<candidate.top+candidate.height;
              }))anchor=candidate;
            });
          }
          var target=anchor?anchor.element:document.documentElement;
          if(!groups.has(target))groups.set(target,[]);
          groups.get(target).push({stroke:stroke,basis:basis,anchor:anchor});
        });
        layers.forEach(function(layer,container){
          if(!groups.has(container)){restore(container,layer);layers.delete(container);}
        });
        groups.forEach(function(group,target){
          var nested=target!==document.documentElement;
          var width=nested?target.scrollWidth:docWidth, height=nested?target.scrollHeight:docHeight;
          var svg=layerFor(target);
          var rtl=nested&&getComputedStyle(target).direction==='rtl';
          svg.style.setProperty('left',rtl?'auto':'0','important');
          svg.style.setProperty('right',rtl?'0':'auto','important');
          svg.style.setProperty('top','0','important');
          svg.style.setProperty('width',width+'px','important');
          svg.style.setProperty('height',height+'px','important');
          svg.setAttribute('viewBox','0 0 '+width+' '+height);
          var paths=document.createDocumentFragment();
          group.forEach(function(item){
            var stroke=item.stroke;
            var coords=stroke.points.map(function(point){
              if(stroke.container)return [point[0]*width,point[1]*height];
              return [point[0]*item.basis-(item.anchor?item.anchor.left:0),
                point[1]*docHeight-(item.anchor?item.anchor.top:0)];
            });
            var path=document.createElementNS(svgNs,'path');
            var line=coords.map(function(p,i){return (i?'L':'M')+p[0]+' '+p[1];}).join(' ');
            if(coords.length===1)line+=' l 0.01 0';
            path.setAttribute('d',line);
            path.setAttribute('fill','none');
            path.setAttribute('stroke',stroke.color);
            var widthBasis=stroke.documentWidth?layoutWidth:Math.min(innerWidth,innerHeight);
            var scale=window.devicePixelRatio*(window.visualViewport?window.visualViewport.scale:1);
            var strokeWidth=Math.max(1.5/Math.max(scale,1),stroke.width*widthBasis);
            path.setAttribute('stroke-width',strokeWidth*(stroke.marker?2.2:1));
            path.setAttribute('stroke-linecap','round');
            path.setAttribute('stroke-linejoin','round');
            paths.appendChild(path);
          });
          while(svg.firstChild)svg.removeChild(svg.firstChild);
          svg.appendChild(paths);
        });
      }
      function refresh(){
        if(strokes.length && !frame)frame=requestAnimationFrame(draw);
      }
      window.__readerInk={
        begin:function(){pending=[];},
        append:function(batch){pending=pending.concat(batch);},
        finish:function(){
          strokes=pending;pending=[];
          if(frame)cancelAnimationFrame(frame);
          draw();return true;
        },
        refresh:refresh
      };
      window.addEventListener('resize',refresh);
      if(window.visualViewport)window.visualViewport.addEventListener('resize',refresh);
      if(window.ResizeObserver)new ResizeObserver(refresh).observe(document.body);
    })();
""".trimIndent()
