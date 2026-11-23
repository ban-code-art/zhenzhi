// Identity is captured at event creation; native code never stamps a late event as current.
window.PocBridge = (() => {
    let token = null, sequence = 0;
    return Object.freeze({
        get token() { return token; },
        bind(serialized, announce = true) {
            token = Object.freeze(JSON.parse(serialized));
            sequence = 0;
            if (announce) window.dispatchEvent(new Event('m03-gecko-bound'));
        },
        emit(kind, value) {
            if (!token || !Number.isSafeInteger(sequence)) return;
            window.postMessage({ kind: 'm03-gecko-event', envelope: {
                kind, token, eventSequence: String(sequence++), value
            } }, location.origin);
        }
    });
})();
window.PocClipboard = Object.freeze({
    copyText(text) {
        if (typeof text === 'string' && text.length <= 8192)
            window.PocBridge.emit('copy', { text });
    }
});
