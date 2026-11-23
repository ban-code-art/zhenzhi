package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.data.reader.ReflowDomSelectionProjector
import java.util.ArrayDeque
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** Builds the oracle from trusted prepared HTML, then retains the actual native node identities. */
internal object ReflowSelectionScript {
    private const val ANCHOR = "data-reader-block-id"
    // Source capacity is independent of the 128 selected-fragment protocol limit. Keep
    // the existing 2 MiB payload / 65,536 structure-node / depth budgets as additional bounds.
    internal const val MAX_SOURCE_BLOCKS = 1024
    private data class Walk(val node: Node, val path: List<Int>)

    fun buildPayload(document: PreparedReflowDocument, binding: ReflowSelectionBinding): JsonObject? {
        if (!ReflowSelectionValidation.binding(binding)) return null
        return try {
            val parsed = Jsoup.parse(document.html)
            val maps = ReflowDomSelectionProjector.annotate(parsed, document.blocks)
            val roots = parsed.getElementsByAttribute(ANCHOR).toList()
            val mapped = maps.withIndex().filter { it.value.isMappable }
            if (mapped.isEmpty() || mapped.size > MAX_SOURCE_BLOCKS) return null
            var structureCount = 0
            val blocks = buildJsonArray {
                for ((index, map) in mapped) {
                    val root = roots[index]
                    val block = document.blocks[index]
                    val stack = ArrayDeque<Walk>()
                    stack.addLast(Walk(root, emptyList()))
                    var leafCount = 0
                    var textCount = 0
                    val structure = buildJsonArray {
                        while (stack.isNotEmpty()) {
                            val frame = stack.removeLast()
                            leafCount++
                            structureCount++
                            require(frame.path.size <= 128 && leafCount <= 16_384 && structureCount <= 65_536)
                            add(buildJsonObject {
                                put("path", buildJsonArray { frame.path.forEach { add(it) } })
                                when (val node = frame.node) {
                                    is Element -> { put("kind", "ELEMENT"); put("tag", node.normalName()) }
                                    is TextNode -> {
                                        textCount++
                                        require(textCount <= 4096)
                                        put("kind", "TEXT"); put("text", node.wholeText)
                                    }
                                    is Comment -> { put("kind", "COMMENT"); put("text", node.data) }
                                    else -> error("UNSUPPORTED_SELECTION_STRUCTURE")
                                }
                            })
                            for (child in frame.node.childNodeSize() - 1 downTo 0)
                                stack.addLast(Walk(frame.node.childNode(child), frame.path + child))
                        }
                    }
                    add(buildJsonObject {
                        put("rootTag", root.normalName()); put("blockId", block.blockId)
                        put("sectionId", block.sectionId); put("nativeLocator", block.nativeLocator)
                        put("canonicalStart", block.canonicalStart); put("canonicalEnd", block.canonicalEnd)
                        put("structure", structure)
                        put("runs", buildJsonArray {
                            map.nodes.forEach { run -> add(buildJsonObject {
                                put("path", buildJsonArray { run.nodePath.forEach { add(it) } })
                                put("text", run.rawText); put("canonicalStart", run.canonicalStart); put("canonicalEnd", run.canonicalEnd)
                            }) }
                        })
                    })
                }
            }
            val payload = buildJsonObject {
                put("version", 1); put("token", ReflowSelectionValidation.tokenJson(binding.token))
                put("bindingId", binding.bindingId); put("blocks", blocks)
            }
            payload.takeIf { it.toString().toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024 }
        } catch (_: Exception) { null }
    }

    /** Reconstruct one code point at the saved canonical start without changing native selection. */
    fun locate(document: PreparedReflowDocument, locator: com.example.myapplication.domain.reader.ReaderLocator.Reflow): String? = try {
        val index = document.blocks.indexOfFirst { it.sectionId == locator.sectionId && it.blockId == locator.blockId }
        require(index >= 0)
        val block = document.blocks[index]
        require(locator.nativeLocator == block.nativeLocator && locator.canonicalStart in block.canonicalStart..block.canonicalEnd)
        val parsed = Jsoup.parse(document.html)
        val mapping = ReflowDomSelectionProjector.annotate(parsed, document.blocks)[index]
        require(mapping.isMappable)
        val runs = buildJsonArray { mapping.nodes.forEach { run -> add(buildJsonObject {
            put("path", buildJsonArray { run.nodePath.forEach { add(it) } })
            put("text", run.rawText); put("start", run.canonicalStart); put("end", run.canonicalEnd)
        }) } }
        val id = JsonPrimitive(block.blockId).toString()
        val sourceText = JsonPrimitive(block.text).toString()
        val start = locator.canonicalStart
        """(function(){var roots=document.querySelectorAll('[data-reader-block-id]'),root=null;
            for(var i=0;i<roots.length;i++)if(roots[i].getAttribute('data-reader-block-id')===$id){if(root)throw Error('DUPLICATE_BLOCK');root=roots[i];}
            if(!root||root.textContent!==$sourceText)throw Error('SOURCE_CHANGED');
            var runs=$runs,chosen=null,offset=0;
            for(var i=0;i<runs.length;i++){var run=runs[i],node=root;
                for(var j=0;j<run.path.length;j++)node=node&&node.childNodes[run.path[j]];
                if(!node||node.nodeType!==3||node.nodeValue!==run.text)throw Error('SOURCE_CHANGED');
                if(!chosen&&$start>=run.start&&$start<run.end){chosen=node;offset=$start-run.start;}
                if(!chosen&&i===runs.length-1&&$start===run.end){chosen=node;offset=Math.max(0,node.nodeValue.length-1);
                    if(offset>0&&node.nodeValue.charCodeAt(offset)>=56320&&node.nodeValue.charCodeAt(offset)<=57343)offset--;}
            }
            if(!chosen)throw Error('RANGE_NOT_FOUND');
            var end=offset+1,c=chosen.nodeValue.charCodeAt(offset);if(c>=55296&&c<=56319)end++;
            var range=document.createRange();range.setStart(chosen,offset);range.setEnd(chosen,end);api.locateRange(range);
        })()""".trimIndent()
    } catch (_: Exception) { null }

    fun install(document: PreparedReflowDocument, binding: ReflowSelectionBinding): String? {
        val payload = buildPayload(document, binding) ?: return null
        return """(function(payload){
            'use strict';
            var previous=window.__isolatedReflowSelection;
            if(previous&&typeof previous.stop==='function')previous.stop();
            var doc=document, body=doc.body, alive=true, presented=false, activated=false;
            var sequence='0', counter='0', current=null, focusTicket=null, emptyTicket=null;
            var MAX='9223372036854775807', LAST='9223372036854775806';
            var proofs=[];
            function plusOne(value){
                if(value===MAX)return null;
                var out='',carry=1;
                for(var i=value.length-1;i>=0;i--){
                    var d=value.charCodeAt(i)-48+carry;
                    if(d===10){out='0'+out;carry=1;}else{out=String.fromCharCode(48+d)+out;carry=0;}
                }
                if(carry)out='1'+out;
                return out.length>MAX.length||(out.length===MAX.length&&out>MAX)?null:out;
            }
            function wellFormed(text){
                for(var i=0;i<text.length;i++){
                    var n=text.charCodeAt(i);
                    if(n>=55296&&n<=56319){
                        if(++i>=text.length)return false;
                        n=text.charCodeAt(i);if(n<56320||n>57343)return false;
                    }else if(n>=56320&&n<=57343)return false;
                }return true;
            }
            function boundary(text,offset){
                return Number.isInteger(offset)&&offset>=0&&offset<=text.length&&
                    (offset===0||offset===text.length||!(text.charCodeAt(offset-1)>=55296&&text.charCodeAt(offset-1)<=56319&&text.charCodeAt(offset)>=56320&&text.charCodeAt(offset)<=57343));
            }
            function samePath(a,b){if(a.length!==b.length)return false;for(var i=0;i<a.length;i++)if(a[i]!==b[i])return false;return true;}
            function at(root,path){var node=root;for(var i=0;i<path.length;i++){if(!node||!node.childNodes||path[i]>=node.childNodes.length)return null;node=node.childNodes[path[i]];}return node;}
            function unique(id){
                var nodes=doc.querySelectorAll('[data-reader-block-id]'),found=null;
                for(var i=0;i<nodes.length;i++)if(nodes[i].getAttribute('data-reader-block-id')===id){if(found)return null;found=nodes[i];}
                return found;
            }
            function verify(proof,first){
                if(document!==doc||doc.body!==body||!proof.root||unique(proof.value.blockId)!==proof.root||
                    !proof.root.isConnected||proof.root.ownerDocument!==doc||!body.contains(proof.root)||proof.root.tagName.toLowerCase()!==proof.value.rootTag)return false;
                var stack=[{node:proof.root,path:[]}],index=0,texts=0;
                while(stack.length){
                    var item=stack.pop(),node=item.node,expected=proof.value.structure[index];
                    if(!expected||index>=16384||item.path.length>128||!samePath(item.path,expected.path)||
                        !node.isConnected||node.ownerDocument!==doc)return false;
                    if(!first&&proof.refs[index]!==node)return false;
                    if(expected.kind==='ELEMENT'){
                        if(node.nodeType!==1||node.tagName.toLowerCase()!==expected.tag)return false;
                    }else if(expected.kind==='TEXT'){
                        if(++texts>4096||node.nodeType!==3||node.nodeValue!==expected.text||!wellFormed(node.nodeValue))return false;
                    }else if(expected.kind==='COMMENT'){
                        if(node.nodeType!==8||node.nodeValue!==expected.text)return false;
                    }else return false;
                    if(first)proof.refs.push(node);
                    index++;
                    for(var j=node.childNodes.length-1;j>=0;j--)stack.push({node:node.childNodes[j],path:item.path.concat([j])});
                }
                return index===proof.value.structure.length;
            }
            try{
                if(!body||!payload.blocks.length||payload.blocks.length>$MAX_SOURCE_BLOCKS)return false;
                for(var i=0;i<payload.blocks.length;i++){
                    var value=payload.blocks[i],proof={value:value,root:unique(value.blockId),refs:[],runs:[],text:''};
                    if(!verify(proof,true))return false;
                    for(var j=0;j<value.runs.length;j++){
                        var run=value.runs[j],node=at(proof.root,run.path);
                        if(!node||node.nodeType!==3||node.nodeValue!==run.text)return false;
                        proof.runs.push({node:node,value:run});proof.text+=run.text;
                    }
                    if(proof.text.length!==value.canonicalEnd-value.canonicalStart)return false;
                    proofs.push(proof);
                }
            }catch(e){return false;}
            function selectedRange(){
                var s=doc.getSelection();
                return s&&!s.isCollapsed&&s.rangeCount===1?s.getRangeAt(0):null;
            }
            function preexistingRanges(){
                var s=doc.getSelection(),ranges=[];
                if(s&&!s.isCollapsed)for(var i=0;i<s.rangeCount;i++)ranges.push(s.getRangeAt(i));
                return ranges;
            }
            var quarantined=preexistingRanges();
            function unavailable(reason,terminal){
                if(!alive||!activated||!presented)return;
                var next=plusOne(sequence);if(!next){stop();return;}
                sequence=next;
                if(sequence===MAX){reason='SEQUENCE_EXHAUSTED';terminal=true;}
                send({version:1,token:payload.token,bindingId:payload.bindingId,eventSequence:sequence,unavailableReason:reason});
                if(terminal)stop();
            }
            function send(value){
                var raw=JSON.stringify(value);
                if(raw.length>131072){stop();return;}
                try{window.__isolatedReflowSelectionBridge.postMessage(raw);}catch(e){stop();}
            }
            function endpoint(node,offset){
                if(!node||node.nodeType!==3||!boundary(node.nodeValue,offset))return null;
                for(var i=0;i<proofs.length;i++)for(var j=0;j<proofs[i].runs.length;j++){
                    var run=proofs[i].runs[j];if(run.node===node)return {proof:proofs[i],offset:run.value.canonicalStart+offset};
                }return null;
            }
            function read(){
                if(!alive||document!==doc||doc.body!==body)return {error:'DOCUMENT_CHANGED'};
                var s=doc.getSelection();
                if(!s||s.rangeCount===0||s.isCollapsed)return {empty:true};
                if(s.rangeCount!==1)return {error:'MULTIPLE_RANGES'};
                var r=s.getRangeAt(0);
                if(quarantined.indexOf(r)>=0)return {error:'PREEXISTING_RANGE'};
                if(quarantined.length)quarantined=[];
                var a=endpoint(r.startContainer,r.startOffset),b=endpoint(r.endContainer,r.endOffset);
                if(!a||!b)return {error:'UNSUPPORTED_NATIVE_RANGE'};
                var first=proofs.indexOf(a.proof),last=proofs.indexOf(b.proof),fragments=[],total=0;
                if(first<0||last<first)return {error:'UNPROVEN_READING_ORDER'};
                for(var i=first;i<=last;i++){
                    var proof=proofs[i];
                    if(!verify(proof,false))return {error:'NATIVE_TREE_CHANGED'};
                    var start=i===first?a.offset:proof.value.canonicalStart,end=i===last?b.offset:proof.value.canonicalEnd;
                    if(start>end)return {error:'UNSUPPORTED_NATIVE_RANGE'};
                    if(start===end)continue;
                    var quote=proof.text.slice(start-proof.value.canonicalStart,end-proof.value.canonicalStart);
                    if(!quote.length||!/\S/.test(quote)||!wellFormed(quote))return {error:'UNSUPPORTED_NATIVE_QUOTE'};
                    total+=quote.length;if(total>8192)return {error:'UNSUPPORTED_NATIVE_QUOTE'};
                    fragments.push({sectionId:proof.value.sectionId,blockId:proof.value.blockId,nativeLocator:proof.value.nativeLocator,
                        quote:quote,canonicalStart:start,canonicalEnd:end});
                    if(fragments.length>128)return {error:'UNSUPPORTED_NATIVE_RANGE'};
                }
                if(!fragments.length)return {error:'UNSUPPORTED_NATIVE_RANGE'};
                // Reject selected non-whitespace text not represented by the prepared source.
                var walker=doc.createTreeWalker(body,NodeFilter.SHOW_TEXT),node;
                while((node=walker.nextNode())){
                    if(!r.intersectsNode(node))continue;
                    var lo=node===r.startContainer?r.startOffset:0,hi=node===r.endContainer?r.endOffset:node.nodeValue.length;
                    if(hi>lo&&/\S/.test(node.nodeValue.slice(lo,hi))&&!endpoint(node,lo))return {error:'UNMAPPED_SELECTED_TEXT'};
                }
                return {range:r,proof:a.proof,start:a.offset,end:b.offset,quote:fragments.map(function(f){return f.quote;}).join('\n'),fragments:fragments,
                    startNode:r.startContainer,startOffset:r.startOffset,endNode:r.endContainer,endOffset:r.endOffset};
            }
            function nextSequence(){
                if(sequence===LAST){unavailable('SEQUENCE_EXHAUSTED',true);return null;}
                var next=plusOne(sequence);if(!next){stop();return null;}sequence=next;return next;
            }
            function publish(value,phase){
                var id=current&&current.range===value.range?current.id:null;
                if(!id){
                    var next=plusOne(counter);
                    if(!next){unavailable('SELECTION_ID_EXHAUSTED',true);return false;}
                    counter=next;id=payload.bindingId+'-'+counter;
                }
                var seq=nextSequence();if(!seq)return false;
                focusTicket=null;emptyTicket=null;
                current={id:id,seq:seq,phase:phase,range:value.range,proof:value.proof,startNode:value.startNode,startOffset:value.startOffset,
                    endNode:value.endNode,endOffset:value.endOffset,start:value.start,end:value.end,quote:value.quote,fragments:value.fragments};
                send({version:1,token:payload.token,bindingId:payload.bindingId,eventSequence:seq,selectionId:id,phase:phase,
                    fragments:value.fragments,screenRects:[]});
                return alive;
            }
            function sameToken(value){
                if(!value)return false;
                var fields=['userId','sessionEpoch','documentId','contentRevision','sessionId','layoutGeneration'];
                if(Object.keys(value).length!==fields.length)return false;
                for(var i=0;i<fields.length;i++)if(typeof value[fields[i]]!=='string'||value[fields[i]]!==payload.token[fields[i]])return false;
                return true;
            }
            function captured(request){
                if(!alive||!activated||!presented||!current||!request||!sameToken(request.token)||request.bindingId!==payload.bindingId||
                    request.selectionId!==current.id||typeof request.eventSequence!=='string'||request.eventSequence!==current.seq||current.seq!==sequence)return null;
                var value=read();
                if(value.error||value.empty||value.range!==current.range||value.proof!==current.proof||
                    value.startNode!==current.startNode||value.startOffset!==current.startOffset||
                    value.endNode!==current.endNode||value.endOffset!==current.endOffset||
                    value.start!==current.start||value.end!==current.end||value.quote!==current.quote||
                    JSON.stringify(value.fragments)!==JSON.stringify(current.fragments))return null;
                try{
                    if(window.__isolatedReflowSelectionBridge.isBindingActive(JSON.stringify({token:payload.token,bindingId:payload.bindingId}))!==true)return null;
                }catch(e){return null;}
                return value;
            }
            function cleared(cause,snapshot){
                var seq=nextSequence();if(!seq)return false;
                send({version:1,token:payload.token,bindingId:payload.bindingId,eventSequence:seq,selectionId:snapshot.id,
                    phase:'CLEARED',fragments:[],screenRects:[],emptyCause:cause});
                return alive;
            }
            function changed(){
                if(!alive||!activated||!presented)return;
                try{
                    var value=read();
                    if(value.empty){
                        quarantined=[];
                        if(emptyTicket)return;
                        if(focusTicket&&current&&focusTicket.id===current.id&&focusTicket.seq===current.seq&&verify(current.proof,false)){
                            var ticket=focusTicket;focusTicket=null;emptyTicket=ticket;
                            cleared('TEMPORARY_FOCUS_LOSS',ticket);
                        }else{focusTicket=null;unavailable('UNKNOWN_COLLAPSE',false);}
                    }else if(value.error){focusTicket=null;emptyTicket=null;unavailable(value.error,false);}
                    else publish(value,'DRAGGING');
                }catch(e){focusTicket=null;emptyTicket=null;unavailable('NATIVE_SELECTION_FAILED',false);}
            }
            function blurred(){
                if(!alive||!activated||!presented)return;
                if(focusTicket){changed();return;}
                unavailable('BLUR',false);
            }
            function stop(){
                alive=false;activated=false;focusTicket=null;emptyTicket=null;
                doc.removeEventListener('selectionchange',changed);
                window.removeEventListener('blur',blurred);
            }
            var api={
                bindingId:payload.bindingId,
                bookmarkViewport:function(){
                    if(!alive||!activated||!presented||!window.__isolatedReflow)return null;
                    var pager=window.__isolatedReflow,b=pager.viewport.getBoundingClientRect();
                    function clipFor(node){
                        var element=node.parentElement;if(!element||!pager.viewport.contains(node))return null;
                        var visibility=getComputedStyle(element).visibility;
                        if(visibility==='hidden'||visibility==='collapse')return null;
                        var c={left:Math.max(0,b.left+.5),right:Math.min(innerWidth,b.right-.5),
                            top:Math.max(0,b.top+.5),bottom:Math.min(innerHeight,b.bottom-.5)};
                        var withinViewport=true;
                        for(var p=element;p;p=p.parentElement){
                            var style=getComputedStyle(p);
                            if(style.display==='none'||Number(style.opacity)===0)return null;
                            // A shaped clip cannot be proven by rectangular Range geometry.
                            if(style.clipPath&&style.clipPath!=='none')return null;
                            // The pager is fixed to the screen. Its body/html ancestors can
                            // have zero layout height and do not clip it with their box overflow.
                            // Keep checking their paint visibility, but only intersect overflow
                            // geometry inside the fixed viewport (including the viewport itself).
                            if(withinViewport){
                                var r=p.getBoundingClientRect(),sx=p.offsetWidth?r.width/p.offsetWidth:1,sy=p.offsetHeight?r.height/p.offsetHeight:1;
                                if(/hidden|clip|scroll|auto/.test(style.overflowX)){
                                    c.left=Math.max(c.left,r.left+p.clientLeft*sx);c.right=Math.min(c.right,r.left+(p.clientLeft+p.clientWidth)*sx);
                                }
                                if(/hidden|clip|scroll|auto/.test(style.overflowY)){
                                    c.top=Math.max(c.top,r.top+p.clientTop*sy);c.bottom=Math.min(c.bottom,r.top+(p.clientTop+p.clientHeight)*sy);
                                }
                            }
                            if(p===pager.viewport)withinViewport=false;
                        }
                        return c.right>c.left&&c.bottom>c.top?c:null;
                    }
                    function visible(range,c){var rs=range.getClientRects();for(var k=0;k<rs.length;k++){
                        var r=rs[k];if(r.width>0&&r.height>0&&r.right>c.left&&r.left<c.right&&r.bottom>c.top&&r.top<c.bottom)return true;
                    }return false;}
                    var spans=[],probes=0;
                    for(var i=0;i<proofs.length;i++){
                        var proof=proofs[i];if(!verify(proof,false))return null;
                        for(var j=0;j<proof.runs.length;j++){
                            var run=proof.runs[j],text=run.node.nodeValue;if(!text.length)continue;
                            var clip=clipFor(run.node);if(!clip)continue;
                            var range=doc.createRange();range.setStart(run.node,0);range.setEnd(run.node,text.length);
                            if(!visible(range,clip))continue;
                            var low=1,high=text.length;
                            while(low<high){var mid=Math.floor((low+high)/2);range.setEnd(run.node,mid);
                                if(visible(range,clip))high=mid;else low=mid+1;}
                            var offset=low-1;while(offset>0&&!boundary(text,offset))offset--;
                            range.setEnd(run.node,text.length);low=offset;high=text.length;
                            while(low<high){var mid=Math.ceil((low+high)/2);range.setStart(run.node,mid);
                                if(visible(range,clip))low=mid;else high=mid-1;}
                            var finish=Math.min(text.length,low+1);while(finish<text.length&&!boundary(text,finish))finish++;
                            // A run may cross clipped lines/columns. Prove every emitted code point;
                            // a first/last bounding interval would wrongly include hidden gaps.
                            for(var at=offset;at<finish;){
                                if(++probes>32768)return null;
                                var end=at+1;while(end<text.length&&!boundary(text,end))end++;
                                range.setStart(run.node,at);range.setEnd(run.node,end);
                                if(visible(range,clip)){
                                    var span={sectionId:proof.value.sectionId,blockId:proof.value.blockId,
                                        start:run.value.canonicalStart+at,end:run.value.canonicalStart+end};
                                    var previous=spans.length?spans[spans.length-1]:null;
                                    if(previous&&previous.blockId===span.blockId&&previous.sectionId===span.sectionId&&previous.end===span.start&&span.end-previous.start<=8192)previous.end=span.end;
                                    else spans.push(span);
                                    if(spans.length>128)return null;
                                }
                                at=end;
                            }
                        }
                    }
                    if(!spans.length)return null;
                    return {blockId:spans[0].blockId,offset:spans[0].start,spans:spans,
                        occurrence:payload.bindingId+':'+pager.occurrence()};
                },
                proveBookmark:function(request){
                    try{
                        if(!request||!sameToken(request.token)||request.bindingId!==payload.bindingId||
                            request.eventSequence!==sequence)return false;
                        var viewport=api.bookmarkViewport(),expected=request.viewport;
                        if(!viewport||!expected||viewport.blockId!==expected.blockId||viewport.offset!==expected.offset||
                            viewport.occurrence!==expected.occurrence||!Array.isArray(expected.spans)||viewport.spans.length!==expected.spans.length||
                            !viewport.spans.every(function(v,i){var e=expected.spans[i];return v.sectionId===e.sectionId&&v.blockId===e.blockId&&v.start===e.start&&v.end===e.end;}))return false;
                        if(request.selectionId===null){
                            var value=read();return current===null&&value.empty===true&&!quarantined.length&&
                                Array.isArray(request.fragments)&&request.fragments.length===0&&window.__isolatedReflowSelectionBridge.isBindingActive(
                                    JSON.stringify({token:payload.token,bindingId:payload.bindingId}))===true;
                        }
                        var value=captured(request);
                        return !!value&&current.phase==='STABLE'&&Array.isArray(request.fragments)&&
                            request.fragments.length===value.fragments.length&&request.fragments.every(function(f,i){
                                var v=value.fragments[i];return f.sectionId===v.sectionId&&f.blockId===v.blockId&&
                                    f.nativeLocator===v.nativeLocator&&f.quote===v.quote&&f.canonicalStart===v.canonicalStart&&f.canonicalEnd===v.canonicalEnd;
                            });
                    }catch(e){return false;}
                },
                stop:stop,
                setPresented:function(value){
                    if(!alive)return false;
                    value=value===true;if(value===presented)return true;
                    if(!value){if(activated&&presented)unavailable('SOURCE_HIDDEN',false);presented=false;focusTicket=null;emptyTicket=null;}
                    else{quarantined=preexistingRanges();presented=true;if(activated&&quarantined.length)unavailable('PREEXISTING_RANGE',false);}
                    return alive;
                },
                activate:function(){
                    if(!alive||!presented)return false;
                    if(activated)return true;
                    activated=true;if(quarantined.length)unavailable('PREEXISTING_RANGE',false);return alive;
                },
                copy:function(request){
                    try{
                        if(!request||Object.keys(request).length!==7||request.version!==1||
                            typeof request.operationId!=='string'||!request.operationId.length||request.operationId.length>512||
                            !/\S/.test(request.operationId)||/[\u0000-\u001f\u007f-\u009f]/.test(request.operationId)||!wellFormed(request.operationId))return false;
                        var value=captured(request),fragment=request.fragment;
                        if(!value||value.fragments.length!==1||current.phase!=='STABLE'||!fragment||Object.keys(fragment).length!==6||
                            fragment.sectionId!==value.proof.value.sectionId||fragment.blockId!==value.proof.value.blockId||
                            fragment.nativeLocator!==value.proof.value.nativeLocator||fragment.quote!==value.quote||
                            !Number.isInteger(fragment.canonicalStart)||!Number.isInteger(fragment.canonicalEnd)||
                            fragment.canonicalStart!==value.start||fragment.canonicalEnd!==value.end)return false;
                        var raw=JSON.stringify(request);if(raw.length>131072)return false;
                        return window.__isolatedReflowSelectionBridge.commitCopy(raw)===true;
                    }catch(e){return false;}
                },
                confirmStable:function(request){var value=captured(request);return !!value&&publish(value,'STABLE');},
                temporaryFocusHandoff:function(request){
                    if(!captured(request))return false;
                    focusTicket={id:current.id,seq:current.seq,range:current.range};emptyTicket=null;return true;
                },
                compareAndClear:function(request){
                    if(!captured(request))return false;
                    var snapshot=current;focusTicket=null;emptyTicket=null;
                    doc.getSelection().removeAllRanges();
                    if(selectedRange()!==null){unavailable('CLEAR_FAILED',false);return false;}
                    current=null;quarantined=[];emptyTicket={id:snapshot.id,seq:snapshot.seq};
                    return cleared('EXPLICIT_CLEAR',snapshot);
                }
            };
            window.__isolatedReflowSelection=Object.freeze(api);
            doc.addEventListener('selectionchange',changed);
            window.addEventListener('blur',blurred);
            return true;
        })($payload)""".trimIndent()
    }
}
