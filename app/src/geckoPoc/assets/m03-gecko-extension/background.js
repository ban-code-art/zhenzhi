// This runtime belongs solely to the opt-in debug host. Reject HTTP(S) outside its asset origin.
browser.webRequest.onBeforeRequest.addListener(details => {
    const url = new URL(details.url);
    return { cancel: url.hostname !== '127.0.0.1' ||
        !(url.pathname.startsWith('/m03/') || url.pathname.startsWith('/m03-gecko/')) };
}, { urls: ['http://*/*', 'https://*/*'] }, ['blocking']);
