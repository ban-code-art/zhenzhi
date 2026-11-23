(function (generation) {
    "use strict";
    const body = document.body;
    const selectionBridge = window.CourseDraftSelectionBridge;
    if (!body || !selectionBridge || window.courseDraftSelection) return;

    function clear() {
        const selection = window.getSelection();
        if (selection) selection.removeAllRanges();
    }

    function report() {
        const selection = window.getSelection();
        if (!selection || !selection.rangeCount || selection.isCollapsed ||
            !body.contains(selection.anchorNode) || !body.contains(selection.focusNode)) {
            selectionBridge.report(generation, 0, 0, "");
            return;
        }
        const range = selection.getRangeAt(0);
        const before = document.createRange();
        before.selectNodeContents(body);
        before.setEnd(range.startContainer, range.startOffset);
        const raw = range.toString();
        const leading = raw.length - raw.trimStart().length;
        let quote = raw.trim().slice(0, 8192);
        if (/[\uD800-\uDBFF]$/.test(quote)) quote = quote.slice(0, -1);
        const start = before.toString().length + leading;
        selectionBridge.report(generation, start, start + quote.length, quote);
    }

    // The source parser separates blocks with whitespace. Keep a map to the rendered text
    // so a verified quote can cross inline markup without guessing a screen coordinate.
    function reveal(quote) {
        if (typeof quote !== "string" || !quote.trim()) return false;
        const walker = document.createTreeWalker(body, NodeFilter.SHOW_TEXT);
        const positions = [];
        let text = "";
        while (walker.nextNode()) {
            const node = walker.currentNode;
            if (node.parentElement && node.parentElement.closest("script,style,noscript,template")) continue;
            for (let offset = 0; offset < node.length; offset++) {
                if (/\s/.test(node.data[offset])) continue;
                text += node.data[offset];
                positions.push({ node: node, offset: offset });
            }
        }
        let needle = quote.replace(/\s/g, "");
        let start = text.indexOf(needle);
        // Bundled markdown tables are rendered as table cells; their source-only pipes
        // and separator rows are the sole additional presentation transform accepted.
        if (start < 0 && quote.includes("|") && body.querySelector("table")) {
            needle = quote.replace(/(?:\|[ \t]*:?-{3,}:?[ \t]*)+\|?/g, "")
                .replace(/[\s|]/g, "");
            start = needle ? text.indexOf(needle) : -1;
        }
        if (start < 0 || text.indexOf(needle, start + 1) >= 0) return false;
        const first = positions[start];
        const last = positions[start + needle.length - 1];
        if (!first || !last) return false;
        const range = document.createRange();
        range.setStart(first.node, first.offset);
        range.setEnd(last.node, last.offset + 1);
        const selection = window.getSelection();
        if (!selection) return false;
        selection.removeAllRanges();
        selection.addRange(range);
        const element = first.node.parentElement;
        if (element) element.scrollIntoView({ block: "center", inline: "nearest" });
        const bounds = range.getClientRects()[0];
        if (bounds) window.scrollBy(0, bounds.top - window.innerHeight / 3);
        report();
        return true;
    }

    function decorate(highlights) {
        clear();
        body.querySelectorAll("mark[data-course-highlight]").forEach(function (mark) {
            mark.parentNode.replaceChild(mark.firstChild, mark);
        });
        body.normalize();
        if (!highlights.length) return;

        const walker = document.createTreeWalker(body, NodeFilter.SHOW_TEXT);
        const nodes = [];
        while (walker.nextNode()) nodes.push(walker.currentNode);
        let start = 0;
        nodes.forEach(function (node) {
            const end = start + node.length;
            const matching = highlights.filter(function (highlight) {
                return highlight.start < end && highlight.end > start;
            });
            if (matching.length) {
                const edges = new Set([start, end]);
                matching.forEach(function (highlight) {
                    edges.add(Math.max(start, highlight.start));
                    edges.add(Math.min(end, highlight.end));
                });
                const sorted = Array.from(edges).sort(function (a, b) { return a - b; });
                const fragment = document.createDocumentFragment();
                for (let i = 0; i < sorted.length - 1; i++) {
                    const from = sorted[i];
                    const to = sorted[i + 1];
                    const text = document.createTextNode(node.data.slice(from - start, to - start));
                    let color;
                    for (let j = matching.length - 1; j >= 0; j--) {
                        const highlight = matching[j];
                        if (highlight.start <= from && highlight.end >= to) {
                            color = highlight.color;
                            break;
                        }
                    }
                    if (color) {
                        const mark = document.createElement("mark");
                        mark.setAttribute("data-course-highlight", "");
                        mark.style.backgroundColor = color;
                        mark.appendChild(text);
                        fragment.appendChild(mark);
                    } else fragment.appendChild(text);
                }
                node.parentNode.replaceChild(fragment, node);
            }
            start = end;
        });
    }

    document.addEventListener("selectionchange", report);
    window.courseDraftSelection = { clear: clear, decorate: decorate, reveal: reveal };
    report();
})
