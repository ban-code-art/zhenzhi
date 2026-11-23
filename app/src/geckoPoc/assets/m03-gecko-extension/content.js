/* Structured probe operations only. This extension is never bundled in production. */
if (location.pathname === '/m03/index-gecko.html') {
    const port = browser.runtime.connectNative('m03Poc');
    let token = null, heldToken = null, stopped = false;
    const held = [];
    const sameToken = (a, b) => a && b && ['userId', 'sessionEpoch', 'documentId',
        'contentRevision', 'sessionId', 'layoutGeneration'].every(key => a[key] === b[key]);
    const post = message => { if (!stopped) port.postMessage(message); };
    const operations = new Set(['turn', 'relayout', 'copy', 'clear', 'close', 'probeNetwork',
        'collectSelection', 'visibleTextPoint', 'selectProgrammatically', 'inspectLocator']);
    const execute = async message => {
        // Capture the request identity before awaiting actual rendering.
        const reply = { id: String(message.id), token: message.token };
        try {
            if (message.op === 'bind') {
                if (token) throw new Error('BRIDGE_ALREADY_BOUND');
                const deadline = Date.now() + 30000;
                while (!window.wrappedJSObject.PocBridge && Date.now() < deadline && !stopped)
                    await new Promise(resolve => setTimeout(resolve, 20));
                if (stopped) return;
                window.wrappedJSObject.PocBridge.bind(JSON.stringify(message.token));
                token = message.token;
                reply.value = true;
                post(reply);
                return;
            }
            const relayout = message.op === 'relayout' && token &&
                sameToken({ ...message.token, layoutGeneration: token.layoutGeneration }, token) &&
                BigInt(message.token.layoutGeneration) === BigInt(token.layoutGeneration) + 1n;
            if (!sameToken(message.token, token) && !relayout) throw new Error('STALE_BRIDGE_TOKEN');
            if (relayout) {
                token = message.token;
                window.wrappedJSObject.PocBridge.bind(JSON.stringify(token), false);
            }
            if (message.op === 'holdEvents') {
                heldToken = { ...token };
                reply.value = true;
            } else if (message.op === 'holdNextLayoutEvents') {
                // Fault transport only: preserve real next-layout envelopes, including their identity.
                heldToken = { ...token, layoutGeneration: String(BigInt(token.layoutGeneration) + 1n) };
                reply.value = true;
            } else if (message.op === 'releaseEvents') {
                heldToken = null;
                reply.value = held.length;
                for (const event of held.splice(0)) post(event);
            } else if (message.op === 'replayEvent') {
                post(message.event);
                reply.value = true;
            } else if (message.op === 'disconnectBridge') {
                port.disconnect();
                return;
            } else {
            const poc = window.wrappedJSObject.poc;
            if (message.op === 'state') reply.value = poc ? JSON.parse(JSON.stringify(poc.state)) : { status: 'loading' };
            else if (message.op === 'layout') {
                const view = window.wrappedJSObject.document.querySelector('foliate-view');
                reply.value = view ? Array.from(view.renderer.getContents(), ({ doc }) => ({
                    htmlWritingMode: doc.defaultView.getComputedStyle(doc.documentElement).writingMode,
                    bodyWritingMode: doc.defaultView.getComputedStyle(doc.body).writingMode,
                    paragraphWritingMode: doc.defaultView.getComputedStyle(doc.querySelector('p') || doc.body).writingMode,
                    styles: Array.from(doc.styleSheets, sheet => ({ href: sheet.href, disabled: sheet.disabled }))
                })) : [];
            }
            else if ((message.op === 'nativeDragTarget' || message.op === 'nativeDragTargetInterior' || message.op === 'nativeDragTargetOutside')) {
                const page = window.wrappedJSObject;
                const span = Array.from(page.document.querySelectorAll('.textLayer span')).find(el => el.textContent.startsWith('Left 01'));
                if (!span?.firstChild) throw new Error('MISSING_NATIVE_DRAG_TARGET');
                const range = page.document.createRange();
                range.selectNodeContents(span);
                const rect = range.getBoundingClientRect();
                // Gecko preserves the vertical grab offset but hit-tests X directly.
                // Keep X inside the text; the outside variant retains the R21 negative control.
                reply.value = { x: rect.right + (message.op === 'nativeDragTargetOutside' ? 8 : -2), y: rect.bottom + 10,
                    caretX: rect.right, caretY: rect.bottom, text: span.textContent, dpr: page.devicePixelRatio };
            }
            else if (message.op === 'selectionDiagnostics') {
                const page = window.wrappedJSObject;
                const selection = page.getSelection();
                const node = value => value ? { name: value.nodeName, text: value.textContent?.slice(0, 120),
                    parent: value.parentElement?.className } : null;
                const rects = range => Array.from(range.getClientRects(), r => ({
                    left: r.left, top: r.top, right: r.right, bottom: r.bottom }));
                const endpoint = (container, offset) => {
                    if (!container) return null;
                    const range = page.document.createRange();
                    range.setStart(container, offset); range.collapse(true);
                    return { node: node(container), offset, rects: rects(range) };
                };
                const hit = (x, y) => {
                    const caret = page.document.caretPositionFromPoint(x, y);
                    return { x, y, caret: caret ? endpoint(caret.offsetNode, caret.offset) : null,
                        element: node(page.document.elementFromPoint(x, y)) };
                };
                reply.value = { time: page.performance.now(), dpr: page.devicePixelRatio,
                    viewport: { width: page.innerWidth, height: page.innerHeight,
                        scrollX: page.scrollX, scrollY: page.scrollY,
                        visual: page.visualViewport ? { offsetLeft: page.visualViewport.offsetLeft,
                            offsetTop: page.visualViewport.offsetTop, scale: page.visualViewport.scale } : null },
                    pointHits: message.point ? [hit(message.point.x, message.point.y),
                        hit(message.point.x - 8, message.point.y - 10)] : [],
                    anchorCaret: endpoint(selection.anchorNode, selection.anchorOffset),
                    focusCaret: endpoint(selection.focusNode, selection.focusOffset),
                    quote: selection.toString(), collapsed: selection.isCollapsed,
                    anchor: node(selection.anchorNode), anchorOffset: selection.anchorOffset,
                    focus: node(selection.focusNode), focusOffset: selection.focusOffset,
                    ranges: Array.from({ length: selection.rangeCount }, (_, i) => {
                        const range = selection.getRangeAt(i);
                        return { start: node(range.startContainer), startOffset: range.startOffset,
                            end: node(range.endContainer), endOffset: range.endOffset, quote: range.toString(), rects: rects(range) };
                    }) };
            }
            else {
                if (!poc || !operations.has(message.op)) throw new Error('UNAVAILABLE_PROBE_OPERATION');
                // Pass primitives across the content-script/page compartment boundary.
                const value = await poc[message.op](message.locator === undefined ? undefined : JSON.stringify(message.locator));
                reply.value = value === undefined ? null : JSON.parse(JSON.stringify(value));
            }
            }
        } catch (e) { reply.error = `${e}\n${e.stack || ''}`; }
        post(reply);
    };
    // Rendering mutations are ordered even when a native waiting coroutine is cancelled.
    let queue = Promise.resolve();
    port.onMessage.addListener(message => { queue = queue.then(() => execute(message)); });
    const forward = event => {
        if (event.source === window && event.origin === location.origin &&
            event.data?.kind === 'm03-gecko-event') {
            const envelope = JSON.parse(JSON.stringify(event.data.envelope));
            if (heldToken && sameToken(envelope.token, heldToken)) {
                if (held.length < 128) held.push(envelope);
            } else post(envelope);
        }
    };
    window.addEventListener('message', forward);
    port.onDisconnect.addListener(() => {
        stopped = true;
        held.length = 0;
        window.removeEventListener('message', forward);
    });
}
