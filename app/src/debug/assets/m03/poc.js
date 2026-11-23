// Isolated real-library PoC, not the frozen ReaderEngineAdapter implementation.
const params = new URLSearchParams(location.search);
const engine = params.get('engine') || 'epub';
const sample = params.get('sample') || (engine === 'pdf' ? 'controlled.pdf' : 'controlled.epub');
const state = window.poc.state;
Object.assign(state, { engine, sample, pages: [], relocations: [], selection: null, copied: '', resources: 0 });
const reader = document.getElementById('reader');
const status = document.getElementById('status');
let view, pdf, task, pdfjs;
const layers = [];
const canvases = [];
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
function collectSelection() {
    const selected = [];
    for (const { doc, index } of docs()) {
        const selection = doc.getSelection();
        if (!selection || selection.isCollapsed || !selection.rangeCount) continue;
        const range = selection.getRangeAt(0);
        const frame = doc === document ? { left: 0, top: 0 } : doc.defaultView.frameElement.getBoundingClientRect();
        selected.push({ index, quote: selection.toString(), rects: Array.from(range.getClientRects())
            .filter(r => [r.left, r.top, r.right, r.bottom].every(Number.isFinite) && r.width > 0 && r.height > 0).map(r => ({
            left: r.left + frame.left, top: r.top + frame.top,
            right: r.right + frame.left, bottom: r.bottom + frame.top, width: r.width, height: r.height
        })) });
    }
    state.selection = selected.length ? selected : null;
    return selected;
}
function attachSelection(doc) {
    doc.addEventListener('selectionchange', collectSelection);
}
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
    const layer = document.createElement('div'); layer.className = 'textLayer';
    box.append(canvas, layer); reader.append(box); canvases.push(canvas);
    const content = await page.getTextContent();
    await page.render({ canvasContext: canvas.getContext('2d'), viewport,
        transform: [devicePixelRatio, 0, 0, devicePixelRatio, 0, 0] }).promise;
    const textLayer = new pdfjs.TextLayer({ textContentSource: content, container: layer, viewport });
    layers.push(textLayer); await textLayer.render();
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
    return record;
}
async function openPdf() {
    pdfjs = await import('./pdfjs/pdf.min.mjs');
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
        return state.relocations.at(-1) || null;
    },
    async relayout() {
        if (view) { view.renderer.setStyles('body{font-size:140%!important;line-height:1.8!important}'); await delay(350); }
        else { await renderPdfPage(1, 1.25, 90); }
        return { relocations: state.relocations.length, pages: state.pages.length };
    },
    copy() {
        const selected = collectSelection(); state.copied = selected.map(x => x.quote).join('\n');
        if (state.copied) PocClipboard.copyText(state.copied);
        return state.copied;
    },
    clear() { for (const { doc } of docs()) doc.getSelection()?.removeAllRanges(); collectSelection(); },
    async close() {
        if (view) { view.close(); view.remove(); view = null; }
        for (const layer of layers) layer.cancel(); layers.length = 0;
        for (const canvas of canvases) { canvas.width = 0; canvas.height = 0; } canvases.length = 0;
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
} catch (e) { fail(e); }
