// Isolated real-library PoC, not the frozen ReaderEngineAdapter implementation.
const params = new URLSearchParams(location.search);
const engine = params.get('engine') || 'epub';
const sample = params.get('sample') || (engine === 'pdf' ? 'controlled.pdf' : 'controlled.epub');
const state = window.poc.state;
Object.assign(state, { engine, sample, pages: [], relocations: [], selection: null, copied: '', resources: 0 });
const reader = document.getElementById('reader');
const status = document.getElementById('status');
let view, pdf, task, pdfjs, TextLayerBuilder;
const layers = [];
const canvases = [];
const selectionDocs = new Set();
const pageViews = new Map();
let layoutReady = false, selectionSignature = null;
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const rect = r => ({ left: r.left, top: r.top, right: r.right, bottom: r.bottom, width: r.width, height: r.height });
const fail = e => {
    state.status = 'failed'; state.code = e.name || 'RENDER_FAILED';
    state.errors.push(String(e.stack || e)); status.textContent = state.code;
};
window.addEventListener('unhandledrejection', e => fail(e.reason));

function docs() {
    return view ? view.renderer.getContents().map(x => ({ ...x, root: x.doc.body }))
        : [{ doc: document, root: reader, index: 0 }];
}
function textNodes(root) {
    const doc = root.ownerDocument;
    const walker = doc.createTreeWalker(root, NodeFilter.SHOW_TEXT, { acceptNode(node) {
        return node.parentElement?.closest('script,style,noscript') ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
    } });
    const nodes = [];
    for (let node = walker.nextNode(); node; node = walker.nextNode()) nodes.push(node);
    return nodes;
}
const canonicalText = root => textNodes(root).map(node => node.nodeValue).join('');
function blockId(node) {
    const element = node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement;
    const block = element.closest('p,h1,h2,h3,h4,h5,h6,li,pre,blockquote') || element;
    // An explicit fixture namespace, never an imported manifest's block identity.
    return `fixture-dom:${Array.from(element.ownerDocument.body.querySelectorAll('*')).indexOf(block)}`;
}
function reflowLocator(range, index) {
    const doc = range.startContainer.ownerDocument;
    const prefix = doc.createRange();
    prefix.selectNodeContents(doc.body); prefix.setEnd(range.startContainer, range.startOffset);
    const start = canonicalText(prefix.cloneContents()).length;
    const quote = range.toString();
    const all = canonicalText(doc.body);
    if (!quote.trim() || all.slice(start, start + quote.length) !== quote) throw new Error('UNMAPPED_FIXTURE_RANGE');
    return { schemaVersion: 1, kind: 'REFLOW', documentId: PocBridge.token.documentId,
        contentRevision: PocBridge.token.contentRevision, sectionId: `fixture:epub:${view.book.sections[index].id}`,
        nativeLocator: view.getCFI(index, range), exactQuote: quote, canonicalStart: start,
        canonicalEnd: start + quote.length, blockId: blockId(range.startContainer) };
}
function currentReflowLocator() {
    const visible = view.lastLocation?.range;
    const index = view.lastLocation?.section?.current;
    if (!visible || !Number.isInteger(index)) return null;
    for (const node of textNodes(visible.startContainer.ownerDocument.body)) {
        if (!visible.intersectsNode(node)) continue;
        const start = node === visible.startContainer ? visible.startOffset : 0;
        const end = Math.min(node.length, start + 48, node === visible.endContainer ? visible.endOffset : node.length);
        if (end <= start || !node.nodeValue.slice(start, end).trim()) continue;
        const range = node.ownerDocument.createRange(); range.setStart(node, start); range.setEnd(node, end);
        return reflowLocator(range, index);
    }
    return null;
}
function visiblePdfPage() {
    const viewport = reader.getBoundingClientRect();
    return Array.from(pageViews.keys()).map(box => {
        const bounds = box.getBoundingClientRect();
        return { box, area: Math.max(0, Math.min(bounds.bottom, viewport.bottom) - Math.max(bounds.top, viewport.top)) *
            Math.max(0, Math.min(bounds.right, viewport.right) - Math.max(bounds.left, viewport.left)) };
    }).sort((a, b) => b.area - a.area)[0]?.box;
}
function fixedLocator(box, screenRects = [], quote) {
    const { viewport, record } = pageViews.get(box);
    const bounds = box.getBoundingClientRect();
    const [x0, y0, x1, y1] = viewport.viewBox;
    const rects = screenRects.map(r => {
        const points = [[r.left, r.top], [r.right, r.top], [r.left, r.bottom], [r.right, r.bottom]]
            .map(([x, y]) => viewport.convertToPdfPoint(x - bounds.left, y - bounds.top));
        const xs = points.map(p => p[0]), ys = points.map(p => p[1]);
        // CropBox, unrotated, top-left origin with y down. No clamping of mapping errors.
        return { left: (Math.min(...xs) - x0) / (x1 - x0), right: (Math.max(...xs) - x0) / (x1 - x0),
            top: (y1 - Math.max(...ys)) / (y1 - y0), bottom: (y1 - Math.min(...ys)) / (y1 - y0) };
    });
    return { schemaVersion: 1, kind: 'FIXED', documentId: PocBridge.token.documentId,
        contentRevision: PocBridge.token.contentRevision, sectionId: `fixture:pdf:page-${record.pageNumber}`,
        pageIndex: record.pageNumber - 1, pageBox: { left: 0, top: 0, right: 1, bottom: 1 },
        rotation: viewport.rotation, rects, ...(quote ? { textQuote: quote } : {}) };
}
function collectSelection() {
    state.selectionViewport = rect(reader.getBoundingClientRect());
    const selected = [];
    for (const { doc, index } of docs()) {
        const selection = doc.getSelection();
        if (!selection || selection.isCollapsed || !selection.rangeCount) continue;
        const range = selection.getRangeAt(0);
        const frame = doc === document ? { left: 0, top: 0 } : doc.defaultView.frameElement.getBoundingClientRect();
        const screenRects = source => Array.from(source.getClientRects())
            .filter(r => [r.left, r.top, r.right, r.bottom].every(Number.isFinite) && r.width > 0 && r.height > 0).map(r => ({
            left: r.left + frame.left, top: r.top + frame.top,
            right: r.right + frame.left, bottom: r.bottom + frame.top, width: r.width, height: r.height
        }));
        if (view) {
            const rects = screenRects(range);
            if (rects.length) selected.push({ index, quote: range.toString(), rects,
                ...(PocBridge.token ? { locator: reflowLocator(range, index) } : {}) });
        } else for (const [box] of pageViews) {
            const layer = box.querySelector('.textLayer');
            if (!range.intersectsNode(layer)) continue;
            const pageRange = doc.createRange(); pageRange.selectNodeContents(layer);
            const clipped = range.cloneRange();
            if (clipped.compareBoundaryPoints(Range.START_TO_START, pageRange) < 0)
                clipped.setStart(pageRange.startContainer, pageRange.startOffset);
            if (clipped.compareBoundaryPoints(Range.END_TO_END, pageRange) > 0)
                clipped.setEnd(pageRange.endContainer, pageRange.endOffset);
            const quote = clipped.toString(), rects = screenRects(clipped);
            if (quote.trim() && rects.length) selected.push({ index: Number(box.dataset.page) - 1, quote, rects,
                ...(PocBridge.token ? { locator: fixedLocator(box, rects, quote) } : {}) });
        }
    }
    state.selection = selected.length ? selected : null;
    if (layoutReady && state.status === 'ready') {
        const origin = state.selectionViewport;
        const value = { phase: selected.length ? 'STABLE' : 'CLEARED', fragments: selected,
            screenRects: selected.flatMap(fragment => fragment.rects.map(r => ({
                left: r.left - origin.left, top: r.top - origin.top,
                right: r.right - origin.left, bottom: r.bottom - origin.top
            }))) };
        const signature = JSON.stringify(value);
        if (signature !== selectionSignature) {
            selectionSignature = signature;
            PocBridge.emit('selection', value);
        }
    }
    return selected;
}
function attachSelection(doc) {
    if (selectionDocs.has(doc)) return;
    selectionDocs.add(doc);
    doc.addEventListener('selectionchange', collectSelection);
}
function reportFeature(restoredLocator) {
    if (!layoutReady || state.status !== 'ready' || !PocBridge.token) return;
    const restored = restoredLocator?.schemaVersion === 1 ? restoredLocator : null;
    const page = view ? null : restored ? Array.from(pageViews.keys()).find(box => Number(box.dataset.page) === restored.pageIndex + 1) : visiblePdfPage();
    const locator = restored || (view ? currentReflowLocator() : page ? fixedLocator(page) : null);
    if (!locator) return;
    const hasText = view ? true : pageViews.get(page).record.textCount > 0;
    PocBridge.emit('feature', { status: state.status, engine,
        locator, renderable: true, preciseSelectionGeometry: false, reflowable: !!view,
        ocrState: hasText ? 'NOT_REQUIRED' : 'REQUIRED',
        capabilities: { selectableText: hasText, images: true, fixedLayout: !view,
            textExtraction: hasText, ink: false, search: false },
        progress: view ? state.relocations.at(-1) || null : {
            pageNumber: page?.dataset.page || null,
            scrollTop: reader.scrollTop
        } });
}
window.addEventListener('m03-gecko-bound', reportFeature);
attachSelection(document);
function visibleTextPoint() {
    for (const { doc, root } of docs()) {
        const walker = doc.createTreeWalker(root, NodeFilter.SHOW_TEXT);
        let node;
        while ((node = walker.nextNode())) {
            if (!(node.textContent || '').trim() || /^(SCRIPT|STYLE)$/.test(node.parentElement.tagName)) continue;
            if (view && !node.parentElement.closest('p')) continue;
            if (!view && !node.parentElement.closest('.textLayer')) continue;
            const range = doc.createRange(); range.setStart(node, 0); range.setEnd(node, Math.min(6, node.length));
            const r = range.getBoundingClientRect();
            if (r.width < 2 || r.height < 2) continue;
            let x = r.left + Math.min(r.width / 2, 12), y = r.top + r.height / 2;
            if (doc !== document) {
                const f = doc.defaultView.frameElement.getBoundingClientRect();
                x += f.left; y += f.top;
            }
            if (x > 5 && x < innerWidth - 10 && y > reader.getBoundingClientRect().top + 5 && y < innerHeight - 20)
                return { x, y, text: node.textContent.slice(0, 80), rect: rect(r), dpr: devicePixelRatio };
        }
    }
    return null;
}
function selectProgrammatically() {
    for (const { doc, root } of docs()) {
        const walker = doc.createTreeWalker(root, NodeFilter.SHOW_TEXT);
        let node;
        while ((node = walker.nextNode())) {
            if (node.length < 12 || /^(SCRIPT|STYLE)$/.test(node.parentElement.tagName)) continue;
            if (!view && !node.parentElement.closest('.textLayer')) continue;
            const range = doc.createRange(); range.setStart(node, 0); range.setEnd(node, Math.min(70, node.length));
            const selection = doc.getSelection(); selection.removeAllRanges(); selection.addRange(range);
            return collectSelection();
        }
    }
    return [];
}
async function openEpub() {
    await import('./foliate/view.js');
    view = document.createElement('foliate-view'); reader.append(view);
    view.addEventListener('load', e => attachSelection(e.detail.doc));
    view.addEventListener('relocate', e => {
        state.relocations.push({ cfi: e.detail.cfi, fraction: e.detail.fraction, index: e.detail.section?.current });
        if (state.relocations.length > 60) state.relocations.shift();
        reportFeature();
    });
    await view.open(`samples/${sample}`);
    view.renderer.setAttribute('flow', 'paginated');
    view.renderer.setAttribute('max-column-count', '1');
    await view.init({ showTextStart: true });
    await delay(200);
    state.sections = view.book.sections.length; state.resources = 1;
    state.pageText = docs().map(x => x.root.textContent.slice(0, 600)).join('\n');
}
async function renderPdfPage(pageNumber, scaleFactor = 1, rotationOverride) {
    const page = await pdf.getPage(pageNumber);
    const base = page.getViewport({ scale: 1, rotation: rotationOverride ?? page.rotate });
    const scale = Math.min((reader.clientWidth - 24) / base.width, 1.8) * scaleFactor;
    const viewport = page.getViewport({ scale, rotation: rotationOverride ?? page.rotate });
    const box = document.createElement('div'); box.className = 'page'; box.dataset.page = pageNumber;
    box.style.width = `${viewport.width}px`; box.style.height = `${viewport.height}px`;
    box.style.setProperty('--total-scale-factor', viewport.scale * viewport.userUnit);
    const canvas = document.createElement('canvas');
    canvas.width = Math.ceil(viewport.width * devicePixelRatio); canvas.height = Math.ceil(viewport.height * devicePixelRatio);
    canvas.style.width = `${viewport.width}px`; canvas.style.height = `${viewport.height}px`;
    const textLayer = new TextLayerBuilder({ pdfPage: page });
    const layer = textLayer.div;
    box.append(canvas, layer); reader.append(box); canvases.push(canvas);
    const content = await page.getTextContent();
    await page.render({ canvasContext: canvas.getContext('2d'), viewport,
        transform: [devicePixelRatio, 0, 0, devicePixelRatio, 0, 0] }).promise;
    layers.push(textLayer); await textLayer.render({ viewport });
    const first = layer.querySelector('span');
    const record = { pageNumber, width: viewport.width, height: viewport.height, rotation: viewport.rotation,
        viewBox: viewport.viewBox, scale, textCount: content.items.filter(x => x.str?.trim()).length,
        text: content.items.map(x => x.str || '').join(' '),
        firstRect: first ? rect(first.getBoundingClientRect()) : null };
    const source = [page.view[0] + 30, page.view[1] + 30];
    const screen = viewport.convertToViewportPoint(...source);
    const back = viewport.convertToPdfPoint(...screen);
    record.roundTripError = Math.max(...source.map((n, i) => Math.abs(n - back[i])));
    state.pages.push(record);
    pageViews.set(box, { page, viewport, record });
    return record;
}
async function openPdf() {
    pdfjs = await import('./pdfjs/pdf.min.mjs');
    // The unmodified, matching-version viewer owns native selection bookkeeping.
    // Low-level TextLayer alone omits the end-of-content and selection listeners.
    globalThis.pdfjsLib = pdfjs;
    ({ TextLayerBuilder } = await import('/m03-gecko/pdf_viewer.mjs'));
    state.textLayerMode = 'official-viewer-builder';
    pdfjs.GlobalWorkerOptions.workerSrc = new URL('./pdfjs/pdf.worker.min.mjs', location.href).href;
    task = pdfjs.getDocument({ url: new URL(`samples/${sample}`, location.href).href,
        cMapUrl: new URL('./pdfjs/cmaps/', location.href).href, cMapPacked: true,
        standardFontDataUrl: new URL('./pdfjs/standard_fonts/', location.href).href,
        wasmUrl: new URL('./pdfjs/wasm/', location.href).href,
        isEvalSupported: false, useSystemFonts: false });
    pdf = await task.promise;
    // Diagnostic only, verified against the pinned PDFDocumentLoadingTask source.
    state.workerMode = task._worker?.port instanceof Worker ? 'real-worker' : 'fallback';
    state.pdfVersion = pdfjs.version; state.pageCount = pdf.numPages;
    for (let n = 1; n <= Math.min(pdf.numPages, 3); n++) await renderPdfPage(n);
    state.resources = 1 + layers.length + canvases.length;
}
window.poc = {
    state, collectSelection, visibleTextPoint, selectProgrammatically,
    async turn() {
        if (view) { await view.next(); await delay(250); }
        else reader.scrollTop = Math.min(reader.scrollHeight, reader.scrollTop + reader.clientHeight);
        reportFeature();
        return state.relocations.at(-1) || null;
    },
    async relayout() {
        layoutReady = false;
        selectionSignature = null;
        for (const { doc } of docs()) doc.getSelection()?.removeAllRanges();
        let restoredLocator;
        if (view) {
            const anchor = currentReflowLocator();
            view.renderer.setStyles('body{font-size:140%!important;line-height:1.8!important}');
            await delay(350);
            if (anchor) {
                await view.goTo(anchor.nativeLocator);
                const resolved = view.resolveCFI(anchor.nativeLocator);
                const doc = docs().find(entry => entry.index === resolved.index)?.doc;
                if (!doc || resolved.anchor(doc).toString() !== anchor.exactQuote)
                    throw new Error('FIXTURE_ANCHOR_NOT_RESTORED');
                restoredLocator = anchor;
            }
        } else {
            const current = visiblePdfPage();
            const pageNumber = current ? Number(current.dataset.page) : 1;
            const offset = current ? Math.max(0, reader.getBoundingClientRect().top - current.getBoundingClientRect().top) / current.clientHeight : 0;
            for (const layer of layers) layer.cancel(); layers.length = 0;
            for (const canvas of canvases) { canvas.width = 0; canvas.height = 0; } canvases.length = 0;
            pageViews.clear(); reader.replaceChildren();
            for (let n = 1; n <= Math.min(pdf.numPages, 3); n++) await renderPdfPage(n, 1.25, 90);
            const restored = Array.from(pageViews.keys()).find(box => Number(box.dataset.page) === pageNumber);
            reader.scrollTop += restored.getBoundingClientRect().top - reader.getBoundingClientRect().top + offset * restored.clientHeight;
            restoredLocator = fixedLocator(restored);
            state.resources = 1 + layers.length + canvases.length;
        }
        layoutReady = true;
        reportFeature(restoredLocator);
        collectSelection();
        return { relocations: state.relocations.length, pages: state.pages.length };
    },
    inspectLocator(serialized) {
        const locator = JSON.parse(serialized);
        if (view && locator.kind === 'REFLOW') {
            const resolved = view.resolveCFI(locator.nativeLocator);
            const contents = docs().find(entry => entry.index === resolved.index);
            if (!contents || locator.sectionId !== `fixture:epub:${view.book.sections[resolved.index].id}`)
                throw new Error('FIXTURE_SECTION_NOT_LOADED');
            const range = resolved.anchor(contents.doc);
            return { canonicalSlice: canonicalText(contents.doc.body).slice(locator.canonicalStart, locator.canonicalEnd),
                cfiQuote: range.toString(), blockContainsStart: blockId(range.startContainer) === locator.blockId };
        }
        const box = Array.from(pageViews.keys()).find(box => Number(box.dataset.page) === locator.pageIndex + 1);
        if (!box) throw new Error('FIXTURE_PAGE_NOT_LOADED');
        const { viewport } = pageViews.get(box);
        const [x0, y0, x1, y1] = viewport.viewBox;
        let roundTripError = 0;
        for (const rect of locator.rects) for (const [x, y] of [[rect.left, rect.top], [rect.right, rect.bottom]]) {
            const source = [x0 + x * (x1 - x0), y1 - y * (y1 - y0)];
            const back = viewport.convertToPdfPoint(...viewport.convertToViewportPoint(...source));
            roundTripError = Math.max(roundTripError, ...source.map((n, i) => Math.abs(n - back[i])));
        }
        return { pageIndex: Number(box.dataset.page) - 1, rotation: viewport.rotation,
            roundTripError, rectCount: locator.rects.length, cropBox: viewport.viewBox };
    },
    copy() {
        const selected = collectSelection(); state.copied = selected.map(x => x.quote).join('\n');
        if (state.copied) PocClipboard.copyText(state.copied);
        return state.copied;
    },
    clear() { for (const { doc } of docs()) doc.getSelection()?.removeAllRanges(); collectSelection(); },
    async close() {
        layoutReady = false;
        for (const doc of selectionDocs) doc.removeEventListener('selectionchange', collectSelection);
        selectionDocs.clear();
        window.removeEventListener('m03-gecko-bound', reportFeature);
        if (view) { view.close(); view.remove(); view = null; }
        for (const layer of layers) layer.cancel(); layers.length = 0;
        for (const canvas of canvases) { canvas.width = 0; canvas.height = 0; } canvases.length = 0;
        pageViews.clear();
        if (task) { await task.destroy(); task = null; pdf = null; }
        reader.replaceChildren(); state.resources = 0; state.status = 'closed';
    },
    probeNetwork() { return fetch('https://example.invalid/m03-network-probe').then(() => false, () => true); }
};
for (const id of ['copy', 'clear', 'next']) document.getElementById(id).addEventListener('pointerdown', e => e.preventDefault());
document.getElementById('copy').onclick = () => window.poc.copy();
document.getElementById('clear').onclick = () => window.poc.clear();
document.getElementById('next').onclick = () => window.poc.turn().catch(fail);
try {
    if (!/^[\w.-]+$/.test(sample)) throw new Error('INVALID_SAMPLE_PATH');
    if (engine === 'epub') await openEpub();
    else if (engine === 'pdf') await openPdf();
    else throw new Error('UNSUPPORTED_ENGINE');
    state.status = 'ready'; status.textContent = `${engine} · 离线就绪`;
    layoutReady = true;
    reportFeature();
} catch (e) { fail(e); }
