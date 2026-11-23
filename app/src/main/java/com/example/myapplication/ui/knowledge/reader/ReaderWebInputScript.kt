package com.example.myapplication.ui.knowledge.reader

/** Passive DOM observation: never preventDefault or stop propagation of native interaction. */
internal val readerWebInputScript = """
    (function(){
      if(window.__readerInputInstalled) return;
      window.__readerInputInstalled=true;
      var gesture=null;
      var finger=false;
      document.addEventListener('pointerdown',function(e){finger=e.pointerType==='touch';},{passive:true});
      var controls='a,button,input,textarea,select,option,label,summary,audio,video,iframe,canvas,[onclick],[role="button"],[role="link"],[role="slider"],[role="checkbox"],[role="textbox"],[role="switch"],[tabindex],[data-reader-highlight],[contenteditable]:not([contenteditable="false"])';
      function interactive(target){return !!(target && target.closest && target.closest(controls));}
      function selected(){var s=window.getSelection();return !!(s && !s.isCollapsed);}
      document.addEventListener('touchstart',function(e){
        window.__readerPendingTap=null;
        if(gesture || e.touches.length!==1){gesture=null;return;}
        var t=e.touches[0];
        gesture={id:t.identifier,x:t.clientX,y:t.clientY,time:performance.now(),move:0,
          events:[e],section:window.__readerSectionId||'',epoch:window.__readerInputEpoch,
          blocked:!finger||t.touchType==='stylus'||interactive(e.target)||selected()||e.defaultPrevented};
      },{passive:true});
      document.addEventListener('touchmove',function(e){
        if(!gesture) return;
        if(e.touches.length!==1){gesture=null;return;}
        var t=e.touches[0];
        if(t.identifier!==gesture.id){gesture=null;return;}
        gesture.move=Math.max(gesture.move,Math.hypot(t.clientX-gesture.x,t.clientY-gesture.y));
        gesture.events.push(e);
        gesture.blocked=gesture.blocked||e.defaultPrevented;
      },{passive:true});
      document.addEventListener('touchcancel',function(){gesture=null;window.__readerPendingTap=null;},{passive:true});
      document.addEventListener('contextmenu',function(){if(gesture)gesture.blocked=true;window.__readerPendingTap=null;});
      document.addEventListener('touchend',function(e){
        if(!gesture) return;
        if(e.touches.length!==0){gesture=null;return;}
        var g=gesture,t=e.changedTouches[0];gesture=null;
        if(!t||t.identifier!==g.id) return;
        g.move=Math.max(g.move,Math.hypot(t.clientX-g.x,t.clientY-g.y));
        g.duration=performance.now()-g.time;
        g.events.push(e);g.endX=t.clientX;g.endY=t.clientY;
        g.blocked=g.blocked||e.defaultPrevented||interactive(e.target)||selected();
        // Wait through click dispatch; page handlers retain ownership of consumed clicks.
        window.__readerPendingTap=g;
        setTimeout(function(){if(window.__readerPendingTap===g)window.__readerPendingTap=null;},750);
      },{passive:true});
      document.addEventListener('click',function(e){
        var g=window.__readerPendingTap;window.__readerPendingTap=null;
        if(!g||!e.isTrusted||e.detail===0||performance.now()-g.time>g.duration+750||
          Math.hypot(e.clientX-g.endX,e.clientY-g.endY)>8) return;
        setTimeout(function(){
          var blocked=g.blocked||g.events.some(function(event){return event.defaultPrevented;})||
            e.defaultPrevented||interactive(e.target)||selected()||g.epoch!==window.__readerInputEpoch;
          try{ReaderBridge.onViewportTap(g.x,g.y,window.innerWidth,window.innerHeight,
            g.duration,g.move,blocked,g.section,g.epoch);}catch(ignore){}
        },0);
      });
    })();
""".trimIndent()
