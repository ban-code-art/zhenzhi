(function () {
    window.poc = { state: { status: 'loading', errors: [], userAgent: navigator.userAgent } };
    window.addEventListener('error', function (e) {
        window.poc.state.errors.push(String(e.message || e.type));
    });
    if (!('noModule' in document.createElement('script'))) {
        window.poc.state.status = 'unavailable';
        window.poc.state.code = 'WEBVIEW_ES_MODULES_UNSUPPORTED';
        document.getElementById('status').textContent = window.poc.state.code;
    }
}());
